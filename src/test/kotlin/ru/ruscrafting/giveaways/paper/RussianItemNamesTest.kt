package ru.ruscrafting.giveaways.paper

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import java.nio.file.Files
import java.util.logging.Logger

class RussianItemNamesTest : StringSpec({
    "vanilla item names are rendered from the Russian catalog even when Paper supplies literal English" {
        val root = Files.createTempDirectory("arcgiveaways-item-names-")
        try {
            val catalog = root.resolve("lang.json")
            Files.writeString(catalog, """{"block.minecraft.white_bed":"Белая кровать"}""")
            val names = RussianItemNames(catalog, Logger.getAnonymousLogger())

            val paperName = Component.empty().append(Component.text("White Bed"))
            val rendered = names.localize(paperName, "block.minecraft.white_bed")

            PlainTextComponentSerializer.plainText().serialize(rendered) shouldBe "Белая кровать"
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    "custom item names remain unchanged" {
        val root = Files.createTempDirectory("arcgiveaways-custom-item-name-")
        try {
            val catalog = root.resolve("lang.json")
            Files.writeString(catalog, """{"item.minecraft.echo_shard":"Осколок эха"}""")
            val names = RussianItemNames(catalog, Logger.getAnonymousLogger())
            val custom = Component.text("Инструмент демонтажа")

            names.localize(custom, "item.minecraft.echo_shard", customName = true) shouldBe custom
        } finally {
            root.toFile().deleteRecursively()
        }
    }
})
