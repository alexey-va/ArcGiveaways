package ru.ruscrafting.giveaways.paper

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class GiveawayEscapeModeTest : StringSpec({
    "missing and invalid preferences default to actual history" {
        GiveawayEscapeMode.parse(null) shouldBe GiveawayEscapeMode.BACK
        GiveawayEscapeMode.parse("") shouldBe GiveawayEscapeMode.BACK
        GiveawayEscapeMode.parse("unexpected") shouldBe GiveawayEscapeMode.BACK
        GiveawayEscapeMode.parse("close") shouldBe GiveawayEscapeMode.CLOSE
        GiveawayEscapeMode.parse(" CLOSE ") shouldBe GiveawayEscapeMode.CLOSE
        GiveawayEscapeMode.parse(" BACK ") shouldBe GiveawayEscapeMode.BACK
    }
})
