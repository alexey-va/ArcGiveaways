package ru.ruscrafting.giveaways.paper

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import ru.ruscrafting.giveaways.domain.GiveawayStatus
import ru.ruscrafting.giveaways.giveaway
import ru.ruscrafting.giveaways.participant
import java.util.UUID

class GiveawayPvpProtectionTest : StringSpec({
    "host and joined participants are protected during an open giveaway" {
        val joined = participant()
        val record = giveaway(participants = listOf(joined))
        val drawing = giveaway(status = GiveawayStatus.DRAWING, participants = listOf(joined))

        GiveawayPvpProtection.isProtected(listOf(record), "spawn", UUID.fromString(record.hostId)) shouldBe true
        GiveawayPvpProtection.isProtected(listOf(record), "spawn", UUID.fromString(joined.playerId)) shouldBe true
        GiveawayPvpProtection.isProtected(listOf(drawing), "spawn", UUID.fromString(joined.playerId)) shouldBe true
    }

    "protection ends with the active draw and never crosses backend ownership" {
        val joined = participant()
        val cancelled = giveaway(status = GiveawayStatus.CANCELLED, participants = listOf(joined))
        val active = giveaway(participants = listOf(joined))

        GiveawayPvpProtection.isProtected(listOf(cancelled), "spawn", UUID.fromString(joined.playerId)) shouldBe false
        GiveawayPvpProtection.isProtected(listOf(active), "survival", UUID.fromString(joined.playerId)) shouldBe false
        GiveawayPvpProtection.isProtected(listOf(active), "spawn", UUID.randomUUID()) shouldBe false
    }
})
