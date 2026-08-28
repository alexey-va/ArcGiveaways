package ru.ruscrafting.giveaways.paper

import com.google.gson.Gson
import kotlinx.coroutines.runBlocking
import org.bukkit.plugin.java.JavaPlugin
import org.slf4j.LoggerFactory
import ru.arc.config.Config
import ru.arc.config.ConfigManager
import ru.arc.core.PaperArcRuntime
import ru.arc.core.Tasks
import ru.arc.logging.ArcLogging
import ru.arc.logging.LoggingConfigSource
import ru.arc.logging.LoggingModuleConfig
import ru.arc.logging.LokiAttachTarget
import ru.arc.logging.LokiInstallSpec
import ru.arc.logging.paper.PaperLoggingPlatform
import ru.arc.metrics.core.ArcMetricsRuntime
import ru.arc.metrics.core.MetricPoint
import ru.arc.metrics.core.MetricsConfig
import ru.arc.metrics.core.MetricsIdentity
import ru.arc.metrics.core.RedisMetricsBinder
import ru.arc.paper.network.BungeeBackendTransfer
import ru.arc.paper.runtime.PaperPluginRuntime
import ru.arc.observability.RuntimeHealthContribution
import ru.arc.observability.RuntimeHealthState
import ru.arc.redis.RedisManager
import ru.arc.redis.ServerIdentity
import ru.ruscrafting.giveaways.config.GiveawayConfig
import ru.ruscrafting.giveaways.config.GiveawayLocale
import ru.ruscrafting.giveaways.config.GiveawayRedisBootstrap
import ru.ruscrafting.giveaways.domain.GiveawayRecord
import ru.ruscrafting.giveaways.network.GiveawayBackendDirectory
import ru.ruscrafting.giveaways.network.GiveawayBackendPresence
import ru.ruscrafting.giveaways.network.RedisGiveawayRepository
import java.nio.file.Files

class ArcGiveawaysPlugin : JavaPlugin() {
    private var redis: RedisManager? = null
    private var service: GiveawayService? = null
    private var transfer: BungeeBackendTransfer? = null
    private lateinit var settings: GiveawayConfig
    private lateinit var locale: GiveawayLocale
    private lateinit var itemNames: RussianItemNames
    private var pluginRuntime: PaperPluginRuntime? = null

    override fun onEnable() {
        saveDefaultConfig()
        saveResourceIfMissing("lang/ru.yml")
        saveResourceIfMissing("lang/en.yml")
        PaperArcRuntime.installScheduling(this)

        try {
            mergeBundledDefaults()
            settings = GiveawayConfig.load(dataFolder.toPath())
            installLogging(settings)
            val lifecycle = PaperPluginRuntime(this, "arc-giveaways").also {
                pluginRuntime = it
                it.start("version" to pluginMeta.version)
            }
            val redisConfig = GiveawayRedisBootstrap.load(dataFolder.toPath(), settings)
            locale = GiveawayLocale(dataFolder.toPath()) { settings }
            itemNames = RussianItemNames(dataFolder.toPath().resolveSibling("ARC").resolve("lang.json"), logger)
            val manager = RedisManager(redisConfig.connection(), ServerIdentity { settings.serverId }, LoggerFactory.getLogger("ArcGiveaways.Redis"))
            lifecycle.own(manager)
            if (!manager.isConnected() || !runBlocking { manager.healthCheck() }) error("Redis connection is unavailable")
            redis = manager
            val gson = Gson()
            val repository = RedisGiveawayRepository(manager, gson, settings.maximumParticipants)
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
                InventoryJournalStore(dataFolder.toPath()),
                itemNames,
                backendTransfer,
                backendDirectory,
            ).also { lifecycle.own(it); it.start() }
            service = activeService
            installMetrics(lifecycle, manager, activeService)
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
            val command = GiveawayCommand(activeService, locale, ::reloadPlugin)
            requireNotNull(getCommand("giveaway")).apply { setExecutor(command); tabCompleter = command }
            server.pluginManager.registerEvents(GiveawayListener(requireNotNull(service)), this)
            manager.init()
            lifecycle.ready("server" to settings.serverId, "redis" to manager.isConnected())
            lifecycle.reportHealthEvery(HEALTH_REPORT_TICKS)
            logger.info("ArcGiveaways enabled on ${settings.serverId}; Redis coordination is required")
        } catch (failure: Throwable) {
            runCatching { pluginRuntime?.health?.markDown(); pluginRuntime?.emitHealth() }
            logger.log(java.util.logging.Level.SEVERE, "ArcGiveaways failed closed during startup", failure)
            server.pluginManager.disablePlugin(this)
        }
    }

    override fun onDisable() {
        runCatching { pluginRuntime?.close() }
        pluginRuntime = null
        Tasks.reset()
    }

    private fun reloadPlugin(): Result<Unit> = runCatching {
        val currentServerId = settings.serverId
        val candidate = GiveawayConfig.inspect(dataFolder.toPath())
        require(candidate.serverId == currentServerId) { "server-id requires a restart" }
        require(candidate.allowedOrigins == settings.allowedOrigins) { "cross-server.allowed-origins requires a restart" }
        require(candidate.presenceHeartbeatSeconds == settings.presenceHeartbeatSeconds) {
            "presence-heartbeat-seconds requires a restart"
        }
        require(candidate.presenceLeaseSeconds == settings.presenceLeaseSeconds) {
            "presence-lease-seconds requires a restart"
        }
        GiveawayLocale.validateFiles(dataFolder.toPath())
        ConfigManager.reloadAll()
        settings = GiveawayConfig.load(dataFolder.toPath())
        itemNames.reload()
        requireNotNull(service).reload(settings)
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

    private fun installMetrics(
        lifecycle: PaperPluginRuntime,
        manager: RedisManager,
        activeService: GiveawayService,
    ) {
        val resource = "metrics.yml"
        val path = ConfigManager.moduleYamlPath(dataFolder.toPath(), resource)
        val existed = Files.isRegularFile(path)
        val config = ConfigManager.ofModule(dataFolder.toPath(), resource)
        if (!existed) {
            config.setInt("bind-port", GIVEAWAY_METRICS_PORT)
            config.saveStrict()
        }
        val metrics = ArcMetricsRuntime(
            config = MetricsConfig(config),
            identity = MetricsIdentity(
                application = "ArcGiveaways",
                platform = "paper",
                serverName = settings.serverId,
                version = pluginMeta.version,
            ),
            dataPath = dataFolder.toPath(),
        )
        try {
            metrics.start()
        } catch (failure: Throwable) {
            runCatching(metrics::close)
            logger.log(java.util.logging.Level.WARNING, "ArcGiveaways metrics failed to start; gameplay remains available", failure)
            return
        }
        lifecycle.own(metrics)
        lifecycle.own(RedisMetricsBinder(manager, metrics.registry))
        lifecycle.tasks.runTimerAsync(0L, METRICS_SAMPLE_TICKS) {
            metrics.recordSnapshot("giveaways", "product") {
                listOf(
                    MetricPoint("arc_giveaways_active_records", "Active or recovering giveaway records", activeService.activeLeaseCount().toDouble()),
                    MetricPoint("arc_giveaways_backend_leases", "Observed live ArcGiveaways backends", activeService.backendLeaseCount().toDouble()),
                    MetricPoint("arc_giveaways_participants", "Participants across cached giveaway records", activeService.participantCount().toDouble()),
                    MetricPoint("arc_giveaways_recovery_backlog", "Durable inventory journals awaiting convergence", activeService.recoveryBacklog().toDouble()),
                )
            }
        }
    }

    private fun saveResourceIfMissing(path: String) {
        val target = dataFolder.toPath().resolve(path)
        if (!java.nio.file.Files.isRegularFile(target)) saveResource(path, false)
    }

    private companion object {
        const val GIVEAWAY_METRICS_PORT = 9951
        const val HEALTH_REPORT_TICKS = 1_200L
        const val METRICS_SAMPLE_TICKS = 100L
    }
}
