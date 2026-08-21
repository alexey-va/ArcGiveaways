package ru.ruscrafting.giveaways

import ru.ruscrafting.giveaways.domain.GiveawayParticipant
import ru.ruscrafting.giveaways.domain.GiveawayRecord
import ru.ruscrafting.giveaways.domain.GiveawayStatus
import ru.ruscrafting.giveaways.domain.ItemPayload
import java.util.UUID

fun giveaway(
    status: GiveawayStatus = GiveawayStatus.OPEN,
    participants: List<GiveawayParticipant> = emptyList(),
): GiveawayRecord =
    GiveawayRecord(
        id = UUID.randomUUID().toString(),
        revision = 0,
        status = status,
        hostId = UUID.randomUUID().toString(),
        hostName = "Host",
        serverId = "spawn",
        worldName = "world",
        anchorX = 0.0,
        anchorY = 64.0,
        anchorZ = 0.0,
        radius = 100.0,
        item = ItemPayload.capture("minecraft:diamond", 1, byteArrayOf(1, 2, 3)),
        createdAtMs = 1_000,
        opensAtMs = 1_000,
        drawAtMs = 46_000,
        participants = participants,
    )

fun participant(name: String = "Player"): GiveawayParticipant =
    GiveawayParticipant(UUID.randomUUID().toString(), name, 2_000)
