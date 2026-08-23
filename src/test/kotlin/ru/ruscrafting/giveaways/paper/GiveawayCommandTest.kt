package ru.ruscrafting.giveaways.paper

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.bukkit.command.Command
import org.bukkit.command.CommandSender
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
})
