package ru.ruscrafting.giveaways.domain

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import ru.ruscrafting.giveaways.giveaway
import ru.ruscrafting.giveaways.participant

class GiveawayEngineTest :
    StringSpec({
        val engine = GiveawayEngine(maxParticipants = 3, minimumParticipants = 2) { bound -> bound - 1 }

        "join is unique, bounded, and excludes the host" {
            val initial = giveaway()
            val first = participant("First")
            val joined = (engine.join(initial, first) as JoinDecision.Joined).record

            engine.join(joined, first) shouldBe JoinDecision.AlreadyJoined(joined)
            engine.join(joined, GiveawayParticipant(initial.hostId, "Host", 2_000)) shouldBe JoinDecision.HostExcluded

            val full = (engine.join((engine.join(joined, participant("Second")) as JoinDecision.Joined).record, participant("Third")) as JoinDecision.Joined).record
            engine.join(full, participant("Fourth")) shouldBe JoinDecision.Full
        }

        "draw snapshots only eligible registered participants and selects once" {
            val first = participant("First")
            val second = participant("Second")
            val outsider = participant("Outsider")
            val open = giveaway(participants = listOf(first, second))

            val drawing = engine.beginDraw(open, listOf(first, second, outsider, first), nowMs = 50_000, drawingMillis = 6_000)
            drawing.status shouldBe GiveawayStatus.DRAWING
            drawing.drawingCandidates shouldBe listOf(first, second)

            val selected = engine.selectWinner(drawing)
            selected.status shouldBe GiveawayStatus.AWAITING_DELIVERY
            selected.winner shouldBe second
            shouldThrow<IllegalArgumentException> { engine.selectWinner(selected) }
        }

        "insufficient nearby participants moves the item into refund recovery" {
            val first = participant("First")
            val result = engine.beginDraw(giveaway(participants = listOf(first)), listOf(first), 50_000, 6_000)

            result.status shouldBe GiveawayStatus.AWAITING_REFUND
            result.terminalReason shouldBe "not_enough_participants"
        }

        "item payload rejects corruption" {
            val payload = ItemPayload.capture("minecraft:diamond", 1, byteArrayOf(1, 2, 3))
            shouldThrow<IllegalArgumentException> { payload.copy(bytesBase64 = "AQIE").decodedBytes() }
        }
    })
