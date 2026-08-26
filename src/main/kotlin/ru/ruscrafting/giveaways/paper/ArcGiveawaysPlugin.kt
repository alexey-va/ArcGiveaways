package ru.ruscrafting.giveaways.paper

import com.google.gson.Gson
import kotlinx.coroutines.runBlocking
import org.bukkit.plugin.java.JavaPlugin
import org.slf4j.LoggerFactory
import ru.arc.config.ConfigManager
import ru.arc.core.PaperArcRuntime
import ru.arc.core.Tasks
import ru.arc.paper.network.BungeeBackendTransfer
import ru.arc.redis.RedisManager
import ru.arc.redis.ServerIdentity
import ru.ruscrafting.giveaways.config.GiveawayConfig
import ru.ruscrafting.giveaways.config.GiveawayLocale
import ru.ruscrafting.giveaways.config.GiveawayRedisBootstrap
import ru.ruscrafting.giveaways.network.RedisGiveawayRepository

class ArcGiveawaysPlugin : JavaPlugin() {
    private var redis: RedisManager? = null
    private var service: GiveawayService? = null
    private var transfer: BungeeBackendTransfer? = null
    private lateinit var settings: GiveawayConfig
    private lateinit var locale: GiveawayLocale
    private lateinit var itemNames: RussianItemNames

    override fun onEnable() {
        saveDefaultConfig()
        saveResourceIfMissing("lang/ru.yml")
        saveResourceIfMissing("lang/en.yml")
        PaperArcRuntime.installScheduling(this)

        try {
            settings = GiveawayConfig.load(dataFolder.toPath())
            val redisConfig = GiveawayRedisBootstrap.load(dataFolder.toPath(), settings)
            locale = GiveawayLocale(dataFolder.toPath()) { settings }
            itemNames = RussianItemNames(dataFolder.toPath().resolveSibling("ARC").resolve("lang.json"), logger)
            val manager = RedisManager(redisConfig.connection(), ServerIdentity { settings.serverId }, LoggerFactory.getLogger("ArcGiveaways.Redis"))
            if (!manager.isConnected() || !runBlocking { manager.healthCheck() }) error("Redis connection is unavailable")
            redis = manager
            val repository = RedisGiveawayRepository(manager, Gson(), settings.maximumParticipants)
            val backendTransfer = BungeeBackendTransfer(this) { failure ->
                logger.log(java.util.logging.Level.WARNING, "ArcGiveaways backend transfer send failed", failure)
            }.also { transfer = it }
            service = GiveawayService(
                this,
                settings,
                locale,
                repository,
                InventoryJournalStore(dataFolder.toPath()),
                itemNames,
                backendTransfer,
            ).also { it.start() }
            val command = GiveawayCommand(requireNotNull(service), locale, ::reloadPlugin)
            requireNotNull(getCommand("giveaway")).apply { setExecutor(command); tabCompleter = command }
            server.pluginManager.registerEvents(GiveawayListener(requireNotNull(service)), this)
            manager.init()
            logger.info("ArcGiveaways enabled on ${settings.serverId}; Redis coordination is required")
        } catch (failure: Throwable) {
            logger.log(java.util.logging.Level.SEVERE, "ArcGiveaways failed closed during startup", failure)
            server.pluginManager.disablePlugin(this)
        }
    }

    override fun onDisable() {
        runCatching { service?.close() }
        runCatching { transfer?.close() }
        runCatching { redis?.close() }
        Tasks.reset()
    }

    private fun reloadPlugin(): Result<Unit> = runCatching {
        val currentServerId = settings.serverId
        val candidate = GiveawayConfig.inspect(dataFolder.toPath())
        require(candidate.serverId == currentServerId) { "server-id requires a restart" }
        GiveawayLocale.validateFiles(dataFolder.toPath())
        ConfigManager.reloadAll()
        settings = GiveawayConfig.load(dataFolder.toPath())
        itemNames.reload()
        requireNotNull(service).reload(settings)
    }

    private fun saveResourceIfMissing(path: String) {
        val target = dataFolder.toPath().resolve(path)
        if (!java.nio.file.Files.isRegularFile(target)) saveResource(path, false)
    }
}
