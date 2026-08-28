package ru.ruscrafting.giveaways.paper

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer

class GiveawayPresentationTest : StringSpec({
    "chat announcement is isolated by a blank line above and below" {
        val rendered = GiveawayPresentation.chatAnnouncement(Component.text("body"), Component.text("join"))

        PlainTextComponentSerializer.plainText().serialize(rendered) shouldBe "\nbody\njoin\n"
    }
})
