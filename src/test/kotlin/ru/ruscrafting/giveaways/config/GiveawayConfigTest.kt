package ru.ruscrafting.giveaways.config

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.assertions.throwables.shouldThrow
import org.opentest4j.TestAbortedException
import ru.arc.config.ConfigManager
import java.nio.file.Files
import java.nio.file.Path

class GiveawayConfigTest :
    StringSpec({
        "tracked runtime profiles expose the exact server-id key" {
            val repositoryRoot = System.getProperty("ruscrafting.opsRoot")?.let(Path::of)
                ?: throw TestAbortedException("RusCrafting ops checkout is not configured")
            mapOf(
                "classic" to "spawn",
                "classic_survival" to "survival",
                "parkour" to "parkour",
            ).forEach { (runtime, expected) ->
                val profile = repositoryRoot.resolve("$runtime/plugins/ArcGiveaways/config.yml")
                Files.readAllLines(profile).first() shouldBe "server-id: $expected"
                ConfigManager.clear()
                GiveawayConfig.load(profile.parent).also { settings ->
                    settings.serverId shouldBe expected
                    settings.hostCooldownSeconds shouldBe 0
                    settings.hostHandoffSeconds shouldBe 45
                    settings.defaultLocale shouldBe "ru"
                    settings.useClientLocale shouldBe false
                    settings.visualEffects.intensity shouldBe 1.0
                    settings.visualEffects.ambient.fireworkIntervalSeconds shouldBe 6
                    settings.visualEffects.countdown.particleCount shouldBe 320
                    settings.visualEffects.drawing.particleCount shouldBe 480
                    settings.visualEffects.winner.fireworkCount shouldBe 18
                    settings.visualEffects.glow.enabled shouldBe true
                }
            }
            ConfigManager.clear()
        }

        "host cooldown is disabled when the setting is omitted" {
            val root = Files.createTempDirectory("arcgiveaways-default-cooldown-")
            try {
                ConfigManager.clear()
                GiveawayConfig.load(root).hostCooldownSeconds shouldBe 0
            } finally {
                ConfigManager.clear()
                root.toFile().deleteRecursively()
            }
        }

        "visual spectacle defaults are vivid but bounded" {
            val root = Files.createTempDirectory("arcgiveaways-default-effects-")
            try {
                ConfigManager.clear()
                val effects = GiveawayConfig.load(root).visualEffects

                effects.countdownThresholdSeconds shouldBe 5
                effects.intensity shouldBe 1.0
                effects.ambient.particleCount shouldBe 160
                effects.ambient.fireworkIntervalSeconds shouldBe 6
                effects.drawing.particleCount shouldBe 480
                effects.winner.particleCount shouldBe 1000
                effects.winner.fireworkCount shouldBe 18
                effects.fireworkStyle.colors.size shouldBe 6
                effects.glow shouldBe GiveawayGlowSettings(enabled = true, host = true, participants = true)
            } finally {
                ConfigManager.clear()
                root.toFile().deleteRecursively()
            }
        }

        "visual spectacle rejects unsafe particle counts" {
            val root = Files.createTempDirectory("arcgiveaways-invalid-effects-")
            try {
                Files.writeString(
                    root.resolve("config.yml"),
                    """
                    server-id: spawn
                    effects:
                      scenes:
                        ambient:
                          particle-count: 601
                    """.trimIndent(),
                )
                ConfigManager.clear()

                shouldThrow<IllegalArgumentException> { GiveawayConfig.load(root) }
            } finally {
                ConfigManager.clear()
                root.toFile().deleteRecursively()
            }
        }

        "starting giveaways is deny-by-default and owned by LuckPerms" {
            val descriptor = requireNotNull(javaClass.getResourceAsStream("/plugin.yml"))
                .bufferedReader()
                .use { it.readText() }

            Regex("arcgiveaways\\.start:\\s*\\n\\s*default: false").containsMatchIn(descriptor) shouldBe true
        }

        "Redis bootstrap heals a persisted node identity without changing its connection" {
            val root = Files.createTempDirectory("arcgiveaways-redis-identity-")
            try {
                Files.writeString(
                    root.resolve("config.yml"),
                    """
                    server-id: survival
                    redis:
                      import-arc-credentials: false
                    """.trimIndent(),
                )
                Files.createDirectories(root.resolve("modules"))
                Files.writeString(
                    root.resolve("modules/redis.yml"),
                    """
                    enabled: true
                    host: 127.0.0.1
                    port: 16379
                    username: fixture
                    password: fixture-secret
                    server-name: spawn
                    main-server: true
                    """.trimIndent(),
                )

                ConfigManager.clear()
                val settings = GiveawayConfig.load(root)
                val redis = GiveawayRedisBootstrap.load(root, settings)

                redis.serverName shouldBe "survival"
                redis.mainServer shouldBe false
                redis.host shouldBe "127.0.0.1"
                redis.port shouldBe 16379
                redis.username shouldBe "fixture"
                redis.password shouldBe "fixture-secret"
            } finally {
                ConfigManager.clear()
                root.toFile().deleteRecursively()
            }
        }
    })
