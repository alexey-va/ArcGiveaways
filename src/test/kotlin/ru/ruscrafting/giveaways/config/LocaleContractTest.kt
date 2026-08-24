package ru.ruscrafting.giveaways.config

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.entity.Player
import ru.arc.config.Config
import ru.arc.config.ConfigManager
import java.nio.file.Files
import java.util.Locale

class LocaleContractTest :
    StringSpec({
        "Russian and English locales contain every message key" {
            val root = Files.createTempDirectory("arcgiveaways-locale-")
            try {
                Files.createDirectories(root.resolve("lang"))
                listOf("ru", "en").forEach { language ->
                    val resource = requireNotNull(javaClass.getResourceAsStream("/lang/$language.yml"))
                    resource.use { Files.copy(it, root.resolve("lang/$language.yml")) }
                }
                ConfigManager.clear()
                val russian = Config(root, "lang/ru.yml")
                val english = Config(root, "lang/en.yml")

                MessageKey.entries.forEach { key ->
                    russian.exists(key.path).shouldBeTrue()
                    english.exists(key.path).shouldBeTrue()
                    russian.string(key.path).isNotBlank().shouldBeTrue()
                    english.string(key.path).isNotBlank().shouldBeTrue()
                }
            } finally {
                ConfigManager.clear()
                root.toFile().deleteRecursively()
            }
        }

        "player names are inserted as literal components" {
            val root = Files.createTempDirectory("arcgiveaways-untrusted-")
            try {
                Files.createDirectories(root.resolve("lang"))
                listOf("ru", "en").forEach { language ->
                    requireNotNull(javaClass.getResourceAsStream("/lang/$language.yml")).use {
                        Files.copy(it, root.resolve("lang/$language.yml"))
                    }
                }
                ConfigManager.clear()
                val config = GiveawayConfig.load(root)
                val locale = GiveawayLocale(root) { config }
                val rendered = locale.render(
                    MessageKey.ANNOUNCEMENT,
                    values = mapOf(
                        "host" to Component.text("<red>unsafe</red>"),
                        "item" to Component.text("Алмаз"),
                        "seconds" to Component.text("45"),
                    ),
                )

                PlainTextComponentSerializer.plainText().serialize(rendered).contains("<red>unsafe</red>") shouldBe true
            } finally {
                ConfigManager.clear()
                root.toFile().deleteRecursively()
            }
        }

        "an English client still receives the Russian network surface" {
            val root = Files.createTempDirectory("arcgiveaways-forced-russian-")
            try {
                Files.createDirectories(root.resolve("lang"))
                listOf("ru", "en").forEach { language ->
                    requireNotNull(javaClass.getResourceAsStream("/lang/$language.yml")).use {
                        Files.copy(it, root.resolve("lang/$language.yml"))
                    }
                }
                Files.writeString(
                    root.resolve("config.yml"),
                    """
                    server-id: spawn
                    locale:
                      default: ru
                      use-client-locale: false
                    """.trimIndent(),
                )
                ConfigManager.clear()
                val config = GiveawayConfig.load(root)
                val locale = GiveawayLocale(root) { config }
                val player = mockk<Player>()
                every { player.locale() } returns Locale.US

                val rendered = locale.render(MessageKey.JOIN_BUTTON, player)

                PlainTextComponentSerializer.plainText().serialize(rendered) shouldBe "• [Присоединиться]"
            } finally {
                ConfigManager.clear()
                root.toFile().deleteRecursively()
            }
        }

        "Russian surface has no legacy all-caps labels or raw backend names" {
            val resource = requireNotNull(javaClass.getResourceAsStream("/lang/ru.yml"))
            val text = resource.bufferedReader().use { it.readText() }

            listOf("РАЗДАЧА", "ПОБЕДА", "GIVEAWAY", "JOIN GIVEAWAY", ">survival<").forEach { forbidden ->
                text.contains(forbidden) shouldBe false
            }
        }
    })
