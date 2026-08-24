package ru.ruscrafting.giveaways.config

import ru.arc.config.Config
import ru.arc.config.ConfigManager
import ru.arc.redis.LegacyRedisSnapshot
import ru.arc.redis.RedisConfigBootstrap
import ru.arc.redis.RedisModuleConfig
import java.nio.file.Files
import java.nio.file.Path

class GiveawayConfig(private val config: Config) {
    val serverId: String get() = config.string("server-id", "spawn").trim().lowercase()
    val radius: Double get() = config.double("giveaway.radius-blocks", 100.0)
    val openSeconds: Int get() = config.int("giveaway.open-seconds", 45)
    val drawingSeconds: Int get() = config.int("giveaway.drawing-seconds", 6)
    val minimumParticipants: Int get() = config.int("giveaway.minimum-participants", 2)
    val maximumParticipants: Int get() = config.int("giveaway.maximum-participants", 250)
    val maximumItemAmount: Int get() = config.int("giveaway.maximum-item-amount", 64)
    val hostCooldownSeconds: Int get() = config.int("giveaway.host-cooldown-seconds", 300)
    val terminalRetentionMinutes: Int get() = config.int("giveaway.terminal-retention-minutes", 30)
    val crossServerEnabled: Boolean get() = config.bool("cross-server.enabled", true)
    val transferOnClick: Boolean get() = config.bool("cross-server.transfer-on-click", true)
    val pendingJoinSeconds: Int get() = config.int("cross-server.pending-join-seconds", 45)
    val importArcRedis: Boolean get() = config.bool("redis.import-arc-credentials", true)
    val bossBarEnabled: Boolean get() = config.bool("effects.bossbar", true)
    val titlesEnabled: Boolean get() = config.bool("effects.titles", true)
    val soundsEnabled: Boolean get() = config.bool("effects.sounds", true)
    val particlesEnabled: Boolean get() = config.bool("effects.particles", true)
    val fireworksEnabled: Boolean get() = config.bool("effects.fireworks", true)
    val defaultLocale: String get() = config.string("locale.default", "ru").lowercase()
    val useClientLocale: Boolean get() = config.bool("locale.use-client-locale", false)

    fun validated(): GiveawayConfig {
        require(serverId.matches(Regex("[a-z0-9_-]{1,32}"))) { "server-id must use lowercase letters, digits, _ or -" }
        require(radius in 1.0..1000.0) { "giveaway.radius-blocks must be between 1 and 1000" }
        require(openSeconds in 10..3600) { "giveaway.open-seconds must be between 10 and 3600" }
        require(drawingSeconds in 1..30) { "giveaway.drawing-seconds must be between 1 and 30" }
        require(maximumParticipants in 1..250) { "giveaway.maximum-participants must be between 1 and 250" }
        require(minimumParticipants in 1..maximumParticipants) { "minimum-participants exceeds maximum-participants" }
        require(maximumItemAmount in 1..2304) { "maximum-item-amount must be between 1 and 2304" }
        require(hostCooldownSeconds in 0..86_400) { "host-cooldown-seconds is outside the supported range" }
        require(terminalRetentionMinutes in 5..1440) { "terminal-retention-minutes must be between 5 and 1440" }
        require(pendingJoinSeconds in 5..300) { "pending-join-seconds must be between 5 and 300" }
        require(defaultLocale in setOf("ru", "en")) { "locale.default must be ru or en" }
        return this
    }

    companion object {
        fun load(dataRoot: Path): GiveawayConfig = GiveawayConfig(ConfigManager.of(dataRoot, "config.yml")).validated()

        fun inspect(dataRoot: Path): GiveawayConfig = GiveawayConfig(Config(dataRoot, "config.yml")).validated()
    }
}

object GiveawayRedisBootstrap {
    fun load(dataRoot: Path, settings: GiveawayConfig): RedisModuleConfig {
        val redisPath = dataRoot.resolve("modules/redis.yml")
        val existed = Files.isRegularFile(redisPath)
        RedisConfigBootstrap.ensure(dataRoot) {
            if (existed || !settings.importArcRedis) null else readArcRedis(dataRoot, settings.serverId)
        }
        val redisConfig = ConfigManager.ofModule(dataRoot, RedisModuleConfig.RESOURCE)
        var redis = RedisModuleConfig(redisConfig)
        val expectedMainServer = settings.serverId == "spawn"
        if (redis.serverName != settings.serverId || redis.mainServer != expectedMainServer) {
            redisConfig.setString("server-name", settings.serverId)
            redisConfig.setBoolean("main-server", expectedMainServer)
            redisConfig.saveStrict()
            redis = RedisModuleConfig(redisConfig)
        }
        require(redis.enabled) { "Redis is mandatory for ArcGiveaways" }
        return redis
    }

    private fun readArcRedis(dataRoot: Path, serverId: String): LegacyRedisSnapshot? {
        val pluginsRoot = dataRoot.parent ?: return null
        val arcRoot = pluginsRoot.resolve("ARC")
        val arcRedis = arcRoot.resolve("modules/redis.yml")
        if (!Files.isRegularFile(arcRedis)) return null
        val source = Config(arcRoot, "modules/redis.yml")
        return LegacyRedisSnapshot(
            enabled = true,
            host = source.string("host", "127.0.0.1"),
            port = source.int("port", 6379),
            username = source.string("username", ""),
            password = source.string("password", ""),
            serverName = serverId,
            mainServer = serverId == "spawn",
        )
    }
}
