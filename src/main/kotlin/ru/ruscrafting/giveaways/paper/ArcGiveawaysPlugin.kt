package ru.ruscrafting.giveaways.paper

import com.google.gson.Gson
import net.luckperms.api.LuckPerms
import org.bukkit.plugin.java.JavaPlugin
import ru.arc.config.Config
import ru.arc.config.ConfigManager
import ru.arc.core.BoundedAsyncExecutor
import ru.arc.core.BudgetedTaskQueue
import ru.arc.core.LifecycleTaskScope
import ru.arc.core.PaperArcRuntime
import ru.arc.core.Tasks
import ru.arc.core.BukkitTaskScheduler
import ru.arc.core.whenCompleteSync
import ru.arc.logging.ArcLogging
import ru.arc.logging.LoggingConfigSource
import ru.arc.logging.LoggingModuleConfig
import ru.arc.logging.LokiAttachTarget
import ru.arc.logging.LokiInstallSpec
import ru.arc.logging.paper.PaperLoggingPlatform
import ru.arc.paper.network.BungeeBackendTransfer
import ru.arc.paper.menu.PaperDialogRuntime
import ru.arc.paper.redis.PaperRedisClient
import ru.arc.paper.api.ArcRedisService
import ru.arc.paper.api.ArcNativeCleanupService
import ru.arc.paper.runtime.PaperPluginRuntime
import ru.arc.observability.RuntimeHealthContribution
import ru.arc.observability.RuntimeHealthState
import ru.ruscrafting.giveaways.config.GiveawayConfig
import ru.ruscrafting.giveaways.config.GiveawayLocale
import ru.ruscrafting.giveaways.config.GiveawayRedisBootstrap
import ru.ruscrafting.giveaways.domain.GiveawayRecord
import ru.ruscrafting.giveaways.network.GiveawayBackendDirectory
import ru.ruscrafting.giveaways.network.GiveawayBackendPresence
import ru.ruscrafting.giveaways.network.RedisGiveawayRepository
import java.nio.file.Files
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

open class ArcGiveawaysPlugin : JavaPlugin() {
    private var service: GiveawayService? = null
    private var transfer: BungeeBackendTransfer? = null
    private lateinit var settings: GiveawayConfig
    private lateinit var locale: GiveawayLocale
    private lateinit var itemNames: RussianItemNames
    private var pluginRuntime: PaperPluginRuntime? = null
    private class StorageGeneration(val worker: BoundedAsyncExecutor, val callbacks: LifecycleTaskScope,
                                    val nativeCleanup: ArcNativeCleanupService) {
        val nativeWork = BudgetedTaskQueue(callbacks)
        val drain = CompletableFuture<Unit>()
        val reloading = AtomicBoolean()
        @Volatile var redis: PaperRedisClient? = null
        var service: GiveawayService? = null
    }
    private var generation: StorageGeneration? = null
    private var predecessor = CompletableFuture.completedFuture(Unit)
    internal fun drained(): CompletableFuture<Unit> = (generation?.drain ?: predecessor).copy()

    private class Prepared(
        val settings: GiveawayConfig,
        val locale: GiveawayLocale,
        val itemNames: RussianItemNames,
        val manager: PaperRedisClient,
        val journal: InventoryJournalStore,
    )

    override fun onEnable() {
        val lifecycle = PaperPluginRuntime(this, "arc-giveaways", BukkitTaskScheduler(this)).also {
            pluginRuntime = it
            it.start("version" to pluginMeta.version)
        }
        predecessor.whenCompleteSync(lifecycle.tasks) { _, failure ->
            if (pluginRuntime !== lifecycle) return@whenCompleteSync
            if (failure != null) failStartup(failure) else beginGeneration(lifecycle)
        }
    }

    private fun beginGeneration(lifecycle: PaperPluginRuntime) {
        try {
            PaperArcRuntime.installScheduling(this)
            val nativeCleanup = requireNotNull(server.servicesManager.load(ArcNativeCleanupService::class.java)) {
                "Required ARC native cleanup provider is not registered"
            }
            val owner = StorageGeneration(BoundedAsyncExecutor("arc-giveaways-storage"), LifecycleTaskScope(BukkitTaskScheduler(this)), nativeCleanup)
            generation = owner
            // ServicesManager and plugin identity are captured before any worker acquisition.
            val redisService = requireNotNull(server.servicesManager.load(ArcRedisService::class.java)) {
                "Required ARC Redis provider is not registered"
            }
            val ownerName = name
            owner.worker.submit {
                saveDefaultConfig()
                saveResourceIfMissing("lang/ru.yml")
                saveResourceIfMissing("lang/en.yml")
                mergeBundledDefaults()
                val loadedSettings = GiveawayConfig.inspect(dataFolder.toPath())
                installLogging(loadedSettings)
                val redisConfig = GiveawayRedisBootstrap.load(dataFolder.toPath(), loadedSettings)
                val loadedLocale = GiveawayLocale(dataFolder.toPath()) { settings }
                val names = RussianItemNames(dataFolder.toPath().resolveSibling("ARC").resolve("lang.json"), logger)
                val manager = PaperRedisClient.open(redisService, ownerName, redisConfig.connection(), loadedSettings.serverId)
                owner.redis = manager
                manager.retainDuringShutdown()
                if (!manager.isConnected() || !manager.healthCheck().get(5, TimeUnit.SECONDS)) {
                    error("Redis connection is unavailable")
                }
                manager.init()
                Prepared(loadedSettings, loadedLocale, names, manager, InventoryJournalStore(dataFolder.toPath()))
            }.whenCompleteSync(lifecycle.tasks) { prepared, failure ->
                if (generation !== owner || pluginRuntime !== lifecycle) return@whenCompleteSync
                if (failure != null || prepared == null) failStartup(failure ?: IllegalStateException("Missing giveaway configuration"))
                else runCatching { finishEnable(lifecycle, owner, prepared) }.onFailure(::failStartup)
            }
        } catch (failure: Throwable) { failStartup(failure) }
    }

    private fun finishEnable(lifecycle: PaperPluginRuntime, owner: StorageGeneration, prepared: Prepared) {
        val storage = owner.worker
        val nativeWork = owner.nativeWork
        settings = prepared.settings
        locale = prepared.locale
        itemNames = prepared.itemNames
        val manager = prepared.manager
        val gson = Gson()
        val repository = RedisGiveawayRepository(manager, gson, settings.maximumParticipants, storage)
        val backendDirectory = GiveawayBackendDirectory(
            redis = manager,
            localServerId = settings.serverId,
            allowedOrigins = settings.allowedOrigins,
            leaseMillis = settings.presenceLeaseSeconds * 1_000L,
            gson = gson,
        )
        val backendTransfer = BungeeBackendTransfer(this) { failure ->
            logger.log(java.util.logging.Level.WARNING, "ArcGiveaways backend transfer send failed", failure)
        }.also { transfer = it; lifecycle.own(it) }
        val activeService = GiveawayService(
            this,
            settings,
            locale,
            repository,
            prepared.journal,
            itemNames,
            backendTransfer,
            backendDirectory,
            storage,
            nativeWork,
            owner.nativeCleanup,
        ).also { lifecycle.own(it) }
        service = activeService
        owner.service = activeService
        activeService.start().whenComplete { _, failure ->
            nativeWork.call({ generation === owner && pluginRuntime === lifecycle }) {
                if (failure != null) failStartup(failure)
                else runCatching {
                    finishReady(lifecycle, manager, activeService)
                    lifecycle.ready("server" to settings.serverId, "redis" to manager.isConnected())
                    lifecycle.reportHealthEvery(HEALTH_REPORT_TICKS)
                    logger.info("ArcGiveaways enabled on ${settings.serverId}; Redis coordination is required")
                }.onFailure(::failStartup)
            }
        }
    }

    private fun finishReady(lifecycle: PaperPluginRuntime, manager: PaperRedisClient, activeService: GiveawayService) {
        val dialogRuntime = PaperDialogRuntime(this).also { lifecycle.own(it) }
        val luckPerms = runCatching {
            server.servicesManager.getRegistration(LuckPerms::class.java)?.provider
        }.getOrNull()
        val escapePreference = LuckPermsGiveawayEscapePreference(luckPerms)
        val menu = GiveawayMenu(
            activeService,
            locale,
            closeDialog = dialogRuntime::close,
            escapeMode = escapePreference::mode,
            openDialog = { player, screen, reopen, onDismiss ->
                dialogRuntime.open(
                    player,
                    screen,
                    reopen,
                    onDismiss,
                    escapePreference.mode(player) == GiveawayEscapeMode.CLOSE,
                )
            },
        )
        lifecycle.registerHealth("runtime") {
            val redisReady = manager.isConnected()
            RuntimeHealthContribution(
                state = if (redisReady) RuntimeHealthState.UP else RuntimeHealthState.DEGRADED,
                recoveryBacklog = activeService.recoveryBacklog(),
                activeLeases = activeService.activeLeaseCount(),
                schemas = mapOf(
                    "backend_presence" to GiveawayBackendPresence.PROTOCOL_VERSION,
                    "giveaway_record" to GiveawayRecord.PROTOCOL_VERSION,
                    "inventory_journal" to InventoryJournalRecord.FORMAT_VERSION,
                ),
                dependencies = mapOf(
                    "backend_presence" to (activeService.backendLeaseCount() > 0),
                    "redis" to redisReady,
                ),
            )
        }
        val command = GiveawayCommand(
            activeService,
            locale,
            ::reloadPlugin,
            openMenu = { player ->
                dialogRuntime.beginFlow(player)
                menu.open(player)
            },
        )
        requireNotNull(getCommand("giveaway")).apply { setExecutor(command); tabCompleter = command }
        server.pluginManager.registerEvents(GiveawayListener(requireNotNull(service)), this)
    }

    private fun failStartup(failure: Throwable) {
        runCatching { pluginRuntime?.health?.markDown(); pluginRuntime?.emitHealth() }
        logger.log(java.util.logging.Level.SEVERE, "ArcGiveaways failed closed during startup", failure)
        server.pluginManager.disablePlugin(this)
    }

    override fun onDisable() {
        val owner = generation
        generation = null
        if (owner != null) predecessor = owner.drain
        val lifecycle = pluginRuntime
        pluginRuntime = null
        var failure: Throwable? = null
        fun attempt(close: () -> Unit) {
            try { close() } catch (error: Throwable) {
                if (failure == null) failure = error else failure?.addSuppressed(error)
            }
        }
        attempt { owner?.nativeWork?.close() }
        attempt { owner?.callbacks?.close() }
        attempt { lifecycle?.close() }
        if (owner != null) closeStorage(owner, failure)
        service = null
        transfer = null
        Tasks.reset()
    }

    private fun closeStorage(owner: StorageGeneration, nativeFailure: Throwable?) {
        val nativeDrain = runCatching { owner.service?.closeAsync() ?: CompletableFuture.completedFuture(null) }
            .getOrElse { CompletableFuture.failedFuture(it) }
        val persistenceDrain = owner.worker.closeAsync {
            var failure = nativeFailure
            listOf<() -> Unit>(
                { owner.service?.closePersistence(); Unit },
                { owner.redis?.close(); Unit },
            ).forEach { close ->
                try { close() } catch (error: Throwable) {
                    if (failure == null) failure = error else if (failure !== error) failure?.addSuppressed(error)
                }
            }
            failure?.let { throw it }
        }
        CompletableFuture.allOf(persistenceDrain, nativeDrain).whenComplete { _, _ ->
            val failures = listOf(persistenceDrain, nativeDrain).mapNotNull {
                runCatching { it.join() }.exceptionOrNull()?.let { error -> error.cause ?: error }
            }
            if (failures.isEmpty()) owner.drain.complete(Unit) else {
                val failure = failures.first()
                failures.drop(1).filterNot { it === failure }.forEach(failure::addSuppressed)
                owner.drain.completeExceptionally(failure)
                logger.log(java.util.logging.Level.SEVERE, "Could not close ArcGiveaways generation", failure)
            }
        }
    }

    private fun reloadPlugin(): CompletableFuture<Result<Unit>> {
        val owner = generation ?: return CompletableFuture.completedFuture(Result.failure(IllegalStateException("Plugin is closed")))
        if (!owner.reloading.compareAndSet(false, true)) {
            return CompletableFuture.completedFuture(Result.failure(IllegalStateException("Reload is already in progress")))
        }
        val current = settings
        val currentLocale = locale
        val currentItemNames = itemNames
        val currentService = requireNotNull(owner.service)
        return owner.worker.submit {
            val candidate = GiveawayConfig.inspect(dataFolder.toPath())
            require(candidate.serverId == current.serverId) { "server-id requires a restart" }
            require(candidate.allowedOrigins == current.allowedOrigins) { "cross-server.allowed-origins requires a restart" }
            require(candidate.presenceHeartbeatSeconds == current.presenceHeartbeatSeconds) { "presence-heartbeat-seconds requires a restart" }
            require(candidate.presenceLeaseSeconds == current.presenceLeaseSeconds) { "presence-lease-seconds requires a restart" }
            Triple(candidate, currentLocale.prepare(dataFolder.toPath()), currentItemNames.prepare())
        }.handle { prepared, failure -> prepared to failure }.thenCompose { (prepared, failure) ->
            owner.nativeWork.call({ generation === owner }) {
                try {
                    if (failure != null) Result.failure(failure.cause ?: failure)
                    else runCatching {
                        check(settings === current) { "Settings changed while reload was prepared" }
                        currentLocale.install(prepared.second)
                        currentItemNames.install(prepared.third)
                        settings = prepared.first
                        currentService.reload(settings)
                    }
                } finally { owner.reloading.set(false) }
            }
        }.whenComplete { _, _ -> owner.reloading.set(false) }
    }

    private fun mergeBundledDefaults() {
        ConfigManager.of(dataFolder.toPath(), "config.yml").mergeMissingFromBundled("config.yml")
        listOf("ru", "en").forEach { language ->
            ConfigManager.of(dataFolder.toPath(), "lang/$language.yml")
                .mergeMissingFromBundled("lang/$language.yml")
        }
        GiveawayLocale.upgradeBundledVisuals(dataFolder.toPath())
        GiveawayLocale.validateFiles(dataFolder.toPath())
    }

    private fun installLogging(settings: GiveawayConfig) {
        val path = ConfigManager.moduleYamlPath(dataFolder.toPath(), LoggingModuleConfig.RESOURCE)
        val existed = Files.isRegularFile(path)
        val logging = ConfigManager.ofModule(dataFolder.toPath(), LoggingModuleConfig.RESOURCE)
        if (!existed) {
            logging.setString("labels.service_name", "arc-giveaways-${settings.serverId}")
            logging.setString("labels.job", "paper")
            logging.saveStrict()
        }
        ArcLogging.install(
            platform = PaperLoggingPlatform("ArcGiveaways", "ArcGiveaways"),
            configSource = object : LoggingConfigSource {
                override fun config(): Config = logging
                override fun configVersion(): Int = ConfigManager.getVersion()
            },
            loki = LokiInstallSpec(
                dataFolder = dataFolder.toPath(),
                target = LokiAttachTarget.LOGGER_PREFIX,
                loggerPrefix = "ru.ruscrafting.giveaways",
                appenderName = "ArcGiveawaysLokiAppender",
            ),
        )
    }

    private fun saveResourceIfMissing(path: String) {
        val target = dataFolder.toPath().resolve(path)
        if (!java.nio.file.Files.isRegularFile(target)) saveResource(path, false)
    }

    private companion object {
        const val HEALTH_REPORT_TICKS = 1_200L
    }
}
