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
    private lateinit var storage: BoundedAsyncExecutor
    private lateinit var callbacks: LifecycleTaskScope
    private lateinit var nativeWork: BudgetedTaskQueue
    private var redis: PaperRedisClient? = null
    private val reloading = AtomicBoolean()
    private var nativeCleanupTail = CompletableFuture.completedFuture<Void>(null)

    private class Prepared(
        val settings: GiveawayConfig,
        val locale: GiveawayLocale,
        val itemNames: RussianItemNames,
        val manager: PaperRedisClient,
        val journal: InventoryJournalStore,
    )

    override fun onEnable() {
        if (!nativeCleanupTail.isDone || nativeCleanupTail.isCompletedExceptionally) {
            logger.severe("Previous giveaway visual cleanup is still pending or failed")
            server.pluginManager.disablePlugin(this)
            return
        }
        val lifecycle = PaperPluginRuntime(this, "arc-giveaways", BukkitTaskScheduler(this)).also {
            pluginRuntime = it
            it.start("version" to pluginMeta.version)
        }
        try {
            PaperArcRuntime.installScheduling(this)
            val nativeCleanup = requireNotNull(server.servicesManager.load(ArcNativeCleanupService::class.java)) {
                "Required ARC native cleanup provider is not registered"
            }
            val redisService = requireNotNull(server.servicesManager.load(ArcRedisService::class.java)) {
                "Required ARC Redis provider is not registered"
            }
            storage = BoundedAsyncExecutor("arc-giveaways-storage")
            callbacks = LifecycleTaskScope(BukkitTaskScheduler(this))
            nativeWork = BudgetedTaskQueue(callbacks)
            reloading.set(false)
            val ownerName = name
            val prepared = storage.submit {
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
                redis = manager
                manager.retainDuringShutdown()
                if (!manager.isConnected() || !manager.healthCheck().get(5, TimeUnit.SECONDS)) {
                    error("Redis connection is unavailable")
                }
                manager.init()
                Prepared(loadedSettings, loadedLocale, names, manager, InventoryJournalStore(dataFolder.toPath()))
            }.get()
            finishEnable(lifecycle, prepared, nativeCleanup)
        } catch (failure: Throwable) { failStartup(failure) }
    }

    private fun finishEnable(lifecycle: PaperPluginRuntime, prepared: Prepared, nativeCleanup: ArcNativeCleanupService) {
        val storage = this.storage
        val nativeWork = this.nativeWork
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
            nativeCleanup,
        ).also { lifecycle.own(it) }
        service = activeService
        activeService.start().get()
        finishReady(lifecycle, manager, activeService)
        lifecycle.ready("server" to settings.serverId, "redis" to manager.isConnected())
        lifecycle.reportHealthEvery(HEALTH_REPORT_TICKS)
        logger.info("ArcGiveaways enabled on ${settings.serverId}; Redis coordination is required")
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
        val lifecycle = pluginRuntime ?: return
        pluginRuntime = null
        val activeService = service.also { service = null }
        val activeRedis = redis.also { redis = null }
        var failure: Throwable? = null
        fun attempt(close: () -> Unit) {
            try { close() } catch (error: Throwable) {
                if (failure == null) failure = error else if (failure !== error) failure.addSuppressed(error)
            }
        }
        attempt { if (::nativeWork.isInitialized) nativeWork.close() }
        attempt { if (::callbacks.isInitialized) callbacks.close() }
        attempt { lifecycle.close() }
        if (activeService != null) nativeCleanupTail = runCatching { activeService.closeAsync() }
            .getOrElse { CompletableFuture.failedFuture(it) }
        // Detached native handles retire on the host; only accepted IO is awaited here.
        attempt {
            if (::storage.isInitialized) storage.closeAsync {
                var terminalFailure: Throwable? = null
                listOf<() -> Unit>(
                    { activeService?.closePersistence(); Unit },
                    { activeRedis?.close(); Unit },
                ).forEach { close ->
                    try { close() } catch (error: Throwable) {
                        if (terminalFailure == null) terminalFailure = error else if (terminalFailure !== error) terminalFailure.addSuppressed(error)
                    }
                }
                terminalFailure?.let { throw it }
            }.join()
        }
        failure?.let { logger.log(java.util.logging.Level.SEVERE, "Could not close ArcGiveaways storage", it) }
        transfer = null
        Tasks.reset()
    }

    private fun reloadPlugin(): CompletableFuture<Result<Unit>> {
        val lifecycle = pluginRuntime ?: return CompletableFuture.completedFuture(Result.failure(IllegalStateException("Plugin is closed")))
        val queue = nativeWork
        val worker = storage
        if (!reloading.compareAndSet(false, true)) {
            return CompletableFuture.completedFuture(Result.failure(IllegalStateException("Reload is already in progress")))
        }
        val current = settings
        val currentLocale = locale
        val currentItemNames = itemNames
        val currentService = requireNotNull(service)
        return worker.submit {
            val candidate = GiveawayConfig.inspect(dataFolder.toPath())
            require(candidate.serverId == current.serverId) { "server-id requires a restart" }
            require(candidate.allowedOrigins == current.allowedOrigins) { "cross-server.allowed-origins requires a restart" }
            require(candidate.presenceHeartbeatSeconds == current.presenceHeartbeatSeconds) { "presence-heartbeat-seconds requires a restart" }
            require(candidate.presenceLeaseSeconds == current.presenceLeaseSeconds) { "presence-lease-seconds requires a restart" }
            Triple(candidate, currentLocale.prepare(dataFolder.toPath()), currentItemNames.prepare())
        }.handle { prepared, failure -> prepared to failure }.thenCompose { (prepared, failure) ->
            queue.call({ pluginRuntime === lifecycle && service === currentService }) {
                try {
                    if (failure != null) Result.failure(failure.cause ?: failure)
                    else runCatching {
                        check(settings === current) { "Settings changed while reload was prepared" }
                        currentLocale.install(prepared.second)
                        currentItemNames.install(prepared.third)
                        settings = prepared.first
                        currentService.reload(settings)
                    }
                } finally { reloading.set(false) }
            }
        }.whenComplete { _, _ -> reloading.set(false) }
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
