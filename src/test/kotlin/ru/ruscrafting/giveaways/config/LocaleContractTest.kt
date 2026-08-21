package ru.ruscrafting.giveaways.config

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import ru.arc.config.Config
import ru.arc.config.ConfigManager
import java.nio.file.Files

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
                val rendered = locale.render(MessageKey.STARTED, values = mapOf("id" to Component.text("<red>unsafe</red>")))

                PlainTextComponentSerializer.plainText().serialize(rendered).contains("<red>unsafe</red>") shouldBe true
            } finally {
                ConfigManager.clear()
                root.toFile().deleteRecursively()
            }
        }
    })
