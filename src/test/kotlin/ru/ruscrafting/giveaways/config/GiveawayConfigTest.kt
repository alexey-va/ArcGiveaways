package ru.ruscrafting.giveaways.config

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
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
                    settings.defaultLocale shouldBe "ru"
                    settings.useClientLocale shouldBe false
                }
            }
            ConfigManager.clear()
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
