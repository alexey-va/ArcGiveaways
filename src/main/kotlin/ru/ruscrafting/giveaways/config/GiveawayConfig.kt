package ru.ruscrafting.giveaways.config

import ru.arc.config.Config
import ru.arc.config.ConfigManager
import ru.arc.network.BackendServerId
import ru.arc.redis.RedisConnectionSettingsSnapshot
import ru.arc.redis.RedisConfigBootstrap
import ru.arc.redis.RedisModuleConfig
import java.nio.file.Files
import java.nio.file.Path

data class GiveawayStageEffectSettings(
    val enabled: Boolean,
    val intervalSeconds: Int,
    val particleCount: Int,
    val radius: Double,
    val fireworkIntervalSeconds: Int,
)

data class GiveawayWinnerEffectSettings(
    val enabled: Boolean,
    val particleCount: Int,
    val radius: Double,
    val fireworkCount: Int,
    val fireworkIntervalTicks: Int,
)

data class GiveawayFireworkStyle(
    val power: Int,
    val colors: List<String>,
    val fadeColors: List<String>,
)

data class GiveawayGlowSettings(
    val enabled: Boolean,
    val host: Boolean,
    val participants: Boolean,
)

data class GiveawayItemDisplaySettings(
    val enabled: Boolean,
    val heightAboveHead: Double,
    val scale: Double,
    val rotationPeriodTicks: Int,
)

data class GiveawayVisualEffectSettings(
    val intensity: Double,
    val countdownThresholdSeconds: Int,
    val ambient: GiveawayStageEffectSettings,
    val countdown: GiveawayStageEffectSettings,
    val drawing: GiveawayStageEffectSettings,
    val winner: GiveawayWinnerEffectSettings,
    val fireworkStyle: GiveawayFireworkStyle,
    val glow: GiveawayGlowSettings,
    val itemDisplay: GiveawayItemDisplaySettings,
)

class GiveawayConfig(private val config: Config) {
    val serverId: String get() = config.string("server-id", "spawn").trim().lowercase()
    val radius: Double get() = config.double("giveaway.radius-blocks", 100.0)
    val openSeconds: Int get() = config.int("giveaway.open-seconds", 45)
    val drawingSeconds: Int get() = config.int("giveaway.drawing-seconds", 6)
    val minimumParticipants: Int get() = config.int("giveaway.minimum-participants", 1)
    val maximumParticipants: Int get() = config.int("giveaway.maximum-participants", 250)
    val maximumItemAmount: Int get() = config.int("giveaway.maximum-item-amount", 64)
    val hostCooldownSeconds: Int get() = config.int("giveaway.host-cooldown-seconds", 0)
    val terminalRetentionMinutes: Int get() = config.int("giveaway.terminal-retention-minutes", 30)
    val crossServerEnabled: Boolean get() = config.bool("cross-server.enabled", true)
    val transferOnClick: Boolean get() = config.bool("cross-server.transfer-on-click", true)
    val pendingJoinSeconds: Int get() = config.int("cross-server.pending-join-seconds", 45)
    val hostHandoffSeconds: Int get() = config.int("cross-server.host-handoff-seconds", 45)
    val presenceHeartbeatSeconds: Int get() = config.int("cross-server.presence-heartbeat-seconds", 5)
    val presenceLeaseSeconds: Int get() = config.int("cross-server.presence-lease-seconds", 20)
    val allowedOrigins: Set<String> get() = config.stringList(
        "cross-server.allowed-origins",
        listOf("spawn", "survival", "parkour"),
    ).map { BackendServerId.of(it.trim().lowercase()).value }.toSet()
    val importArcRedis: Boolean get() = config.bool("redis.import-arc-credentials", true)
    val bossBarEnabled: Boolean get() = config.bool("effects.bossbar", true)
    val actionBarEnabled: Boolean get() = config.bool("effects.actionbar", true)
    val titlesEnabled: Boolean get() = config.bool("effects.titles", true)
    val soundsEnabled: Boolean get() = config.bool("effects.sounds", true)
    val particlesEnabled: Boolean get() = config.bool("effects.particles", true)
    val fireworksEnabled: Boolean get() = config.bool("effects.fireworks", true)
    val visualEffects: GiveawayVisualEffectSettings get() = GiveawayVisualEffectSettings(
        intensity = config.double("effects.intensity", 1.0),
        countdownThresholdSeconds = config.int("effects.scenes.countdown-threshold-seconds", 5),
        ambient = stageEffects("effects.scenes.ambient", intervalSeconds = 1, particleCount = 160, radius = 2.6, fireworkIntervalSeconds = 6),
        countdown = stageEffects("effects.scenes.countdown", intervalSeconds = 1, particleCount = 320, radius = 3.6, fireworkIntervalSeconds = 1),
        drawing = stageEffects("effects.scenes.drawing", intervalSeconds = 1, particleCount = 480, radius = 4.4, fireworkIntervalSeconds = 1),
        winner = GiveawayWinnerEffectSettings(
            enabled = config.bool("effects.scenes.winner.enabled", true),
            particleCount = config.int("effects.scenes.winner.particle-count", 1000),
            radius = config.double("effects.scenes.winner.radius", 6.0),
            fireworkCount = config.int("effects.scenes.winner.firework-count", 18),
            fireworkIntervalTicks = config.int("effects.scenes.winner.firework-interval-ticks", 3),
        ),
        fireworkStyle = GiveawayFireworkStyle(
            power = config.int("effects.firework-style.power", 1),
            colors = config.stringList(
                "effects.firework-style.colors",
                listOf("#ff6b00", "#ffd166", "#ff2d95", "#7c4dff", "#38d9ff", "#7dff84"),
            ),
            fadeColors = config.stringList(
                "effects.firework-style.fade-colors",
                listOf("#ffffff", "#fff3c4"),
            ),
        ),
        glow = GiveawayGlowSettings(
            enabled = config.bool("effects.glow.enabled", true),
            host = config.bool("effects.glow.host", true),
            participants = config.bool("effects.glow.participants", true),
        ),
        itemDisplay = GiveawayItemDisplaySettings(
            enabled = config.bool("effects.item-display.enabled", true),
            heightAboveHead = config.double("effects.item-display.height-above-head", 0.65),
            scale = config.double("effects.item-display.scale", 1.25),
            rotationPeriodTicks = config.int("effects.item-display.rotation-period-ticks", 80),
        ),
    )
    val defaultLocale: String get() = config.string("locale.default", "ru").lowercase()
    val useClientLocale: Boolean get() = config.bool("locale.use-client-locale", false)

    fun validated(): GiveawayConfig {
        BackendServerId.of(serverId)
        require(radius in 1.0..1000.0) { "giveaway.radius-blocks must be between 1 and 1000" }
        require(openSeconds in 10..3600) { "giveaway.open-seconds must be between 10 and 3600" }
        require(drawingSeconds in 1..30) { "giveaway.drawing-seconds must be between 1 and 30" }
        require(maximumParticipants in 1..250) { "giveaway.maximum-participants must be between 1 and 250" }
        require(minimumParticipants in 1..maximumParticipants) { "minimum-participants exceeds maximum-participants" }
        require(maximumItemAmount in 1..2304) { "maximum-item-amount must be between 1 and 2304" }
        require(hostCooldownSeconds in 0..86_400) { "host-cooldown-seconds is outside the supported range" }
        require(terminalRetentionMinutes in 5..1440) { "terminal-retention-minutes must be between 5 and 1440" }
        require(pendingJoinSeconds in 5..300) { "pending-join-seconds must be between 5 and 300" }
        require(hostHandoffSeconds in 10..300) { "host-handoff-seconds must be between 10 and 300" }
        require(presenceHeartbeatSeconds in 2..30) { "presence-heartbeat-seconds must be between 2 and 30" }
        require(presenceLeaseSeconds in (presenceHeartbeatSeconds * 3)..120) {
            "presence-lease-seconds must be at least three heartbeats and no more than 120"
        }
        require(allowedOrigins.isNotEmpty() && serverId in allowedOrigins) {
            "cross-server.allowed-origins must include this server-id"
        }
        validateVisualEffects(visualEffects)
        require(defaultLocale in setOf("ru", "en")) { "locale.default must be ru or en" }
        return this
    }

    private fun stageEffects(
        path: String,
        intervalSeconds: Int,
        particleCount: Int,
        radius: Double,
        fireworkIntervalSeconds: Int,
    ): GiveawayStageEffectSettings = GiveawayStageEffectSettings(
        enabled = config.bool("$path.enabled", true),
        intervalSeconds = config.int("$path.interval-seconds", intervalSeconds),
        particleCount = config.int("$path.particle-count", particleCount),
        radius = config.double("$path.radius", radius),
        fireworkIntervalSeconds = config.int("$path.firework-interval-seconds", fireworkIntervalSeconds),
    )

    private fun validateVisualEffects(effects: GiveawayVisualEffectSettings) {
        require(effects.intensity in 0.1..1.0) { "effects.intensity must be between 0.1 and 1.0" }
        require(effects.countdownThresholdSeconds in 1..10 && effects.countdownThresholdSeconds <= openSeconds) {
            "effects.scenes.countdown-threshold-seconds must be between 1 and 10 and not exceed open-seconds"
        }
        listOf(
            "ambient" to effects.ambient,
            "countdown" to effects.countdown,
            "drawing" to effects.drawing,
        ).forEach { (name, stage) ->
            require(stage.intervalSeconds in 1..30) { "effects.scenes.$name.interval-seconds must be between 1 and 30" }
            require(stage.particleCount in 0..600) { "effects.scenes.$name.particle-count must be between 0 and 600" }
            require(stage.radius in 0.5..10.0) { "effects.scenes.$name.radius must be between 0.5 and 10" }
            require(stage.fireworkIntervalSeconds in 0..300) {
                "effects.scenes.$name.firework-interval-seconds must be between 0 and 300"
            }
        }
        require(effects.winner.particleCount in 0..1200) { "effects.scenes.winner.particle-count must be between 0 and 1200" }
        require(effects.winner.radius in 0.5..12.0) { "effects.scenes.winner.radius must be between 0.5 and 12" }
        require(effects.winner.fireworkCount in 0..24) { "effects.scenes.winner.firework-count must be between 0 and 24" }
        require(effects.winner.fireworkIntervalTicks in 1..40) {
            "effects.scenes.winner.firework-interval-ticks must be between 1 and 40"
        }
        require(effects.fireworkStyle.power in 0..2) { "effects.firework-style.power must be between 0 and 2" }
        require(effects.fireworkStyle.colors.size in 1..12) { "effects.firework-style.colors must contain between 1 and 12 colors" }
        require(effects.fireworkStyle.fadeColors.size in 1..12) {
            "effects.firework-style.fade-colors must contain between 1 and 12 colors"
        }
        require(effects.itemDisplay.heightAboveHead in 0.25..2.0) {
            "effects.item-display.height-above-head must be between 0.25 and 2.0"
        }
        require(effects.itemDisplay.scale in 0.25..3.0) {
            "effects.item-display.scale must be between 0.25 and 3.0"
        }
        require(effects.itemDisplay.rotationPeriodTicks in 20..1200) {
            "effects.item-display.rotation-period-ticks must be between 20 and 1200"
        }
        (effects.fireworkStyle.colors + effects.fireworkStyle.fadeColors).forEach { color ->
            require(HEX_COLOR.matches(color)) { "Invalid firework color: $color" }
        }
    }

    companion object {
        private val HEX_COLOR = Regex("#[0-9a-fA-F]{6}")

        fun load(dataRoot: Path): GiveawayConfig = GiveawayConfig(ConfigManager.of(dataRoot, "config.yml")).validated()

        fun inspect(dataRoot: Path): GiveawayConfig = GiveawayConfig(Config(dataRoot, "config.yml")).validated()
    }
}

object GiveawayRedisBootstrap {
    fun load(dataRoot: Path, settings: GiveawayConfig): RedisModuleConfig {
        val redisPath = dataRoot.resolve("modules/redis.yml")
        val existed = Files.isRegularFile(redisPath)
        RedisConfigBootstrap.ensure(dataRoot) {
            if (existed || !settings.importArcRedis) null else readArcRedisSettings(dataRoot, settings.serverId)
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

    private fun readArcRedisSettings(dataRoot: Path, serverId: String): RedisConnectionSettingsSnapshot? {
        val pluginsRoot = dataRoot.parent ?: return null
        val arcRoot = pluginsRoot.resolve("ARC")
        val arcRedis = arcRoot.resolve("modules/redis.yml")
        if (!Files.isRegularFile(arcRedis)) return null
        val source = Config(arcRoot, "modules/redis.yml")
        return RedisConnectionSettingsSnapshot(
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
