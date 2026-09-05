package ru.ruscrafting.giveaways.paper

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import net.kyori.adventure.text.Component
import org.bukkit.command.Command
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import ru.ruscrafting.giveaways.config.GiveawayLocale

class GiveawayCommandTest : StringSpec({
    "qa is console-safe and returns stable machine-readable lines" {
        val service = mockk<GiveawayService>()
        val locale = mockk<GiveawayLocale>()
        val sender = mockk<CommandSender>(relaxed = true)
        val command = mockk<Command>()
        every { sender.hasPermission("arcgiveaways.admin") } returns true
        val report = listOf(
            "ARCGIVEAWAYS_QA status=ok server=survival records=1 active=1 journals=2",
            "ARCGIVEAWAYS_QA_JOURNAL id=bd624545 kind=REFUND status=PREPARED player=ONLINE state=AFTER changes=1",
        )

        val handled = GiveawayCommand(service, locale, { Result.success(Unit) }) { query ->
            if (query == "bd624545") report else emptyList()
        }
            .onCommand(sender, command, "giveaway", arrayOf("qa", "bd624545"))

        handled shouldBe true
        verify(exactly = 1) { sender.sendMessage("ARCGIVEAWAYS_QA status=ok server=survival records=1 active=1 journals=2") }
        verify(exactly = 1) { sender.sendMessage("ARCGIVEAWAYS_QA_JOURNAL id=bd624545 kind=REFUND status=PREPARED player=ONLINE state=AFTER changes=1") }
    }

    "root and menu open the native menu while help stays textual" {
        val service = mockk<GiveawayService>(relaxed = true)
        val locale = mockk<GiveawayLocale>(relaxed = true)
        val player = mockk<Player>(relaxed = true)
        val command = mockk<Command>()
        var opened = 0
        val handler = GiveawayCommand(
            service,
            locale,
            { Result.success(Unit) },
            openMenu = { opened += 1 },
        )

        handler.onCommand(player, command, "giveaway", emptyArray()) shouldBe true
        handler.onCommand(player, command, "giveaway", arrayOf("menu")) shouldBe true
        handler.onCommand(player, command, "giveaway", arrayOf("help")) shouldBe true

        opened shouldBe 2
        verify(exactly = 1) { player.sendMessage(any<Component>()) }
    }
})
