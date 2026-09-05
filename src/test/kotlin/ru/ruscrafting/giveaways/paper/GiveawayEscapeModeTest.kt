package ru.ruscrafting.giveaways.paper

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import net.kyori.adventure.text.Component
import ru.arc.paper.menu.PaperDialogActionId
import ru.arc.paper.menu.PaperDialogButton
import ru.arc.paper.menu.PaperDialogScreen

class GiveawayEscapeModeTest : StringSpec({
    "missing and invalid preferences default to close" {
        GiveawayEscapeMode.parse(null) shouldBe GiveawayEscapeMode.CLOSE
        GiveawayEscapeMode.parse("") shouldBe GiveawayEscapeMode.CLOSE
        GiveawayEscapeMode.parse("unexpected") shouldBe GiveawayEscapeMode.CLOSE
        GiveawayEscapeMode.parse(" BACK ") shouldBe GiveawayEscapeMode.BACK
    }

    "close mode keeps visible back as a normal action without exit_action" {
        val back = button("back")
        val screen = PaperDialogScreen(
            title = Component.text("title"),
            buttons = listOf(button("refresh")),
            exitButton = back,
        )

        val normalized = screen.forEscapeMode(GiveawayEscapeMode.CLOSE)

        normalized.exitButton shouldBe null
        normalized.buttons.map { it.id.value } shouldContainExactly listOf("refresh", "back")
        normalized.buttons.count { it.id == back.id } shouldBe 1
        normalized.canCloseWithEscape shouldBe true
    }

    "back mode preserves the native exit_action" {
        val back = button("back")
        val screen = PaperDialogScreen(
            title = Component.text("title"),
            buttons = listOf(button("refresh")),
            exitButton = back,
        )

        screen.forEscapeMode(GiveawayEscapeMode.BACK) shouldBe screen
    }

})

private fun button(id: String) = PaperDialogButton(
    id = PaperDialogActionId.of(id),
    label = Component.text(id),
    onClick = {},
)
