package ru.ruscrafting.giveaways.paper

import ru.ruscrafting.giveaways.domain.GiveawayRecord
import ru.ruscrafting.giveaways.domain.GiveawayStatus
import java.util.UUID

object GiveawayPvpProtection {
    fun isProtected(records: Collection<GiveawayRecord>, serverId: String, playerId: UUID): Boolean {
        val id = playerId.toString()
        return records.any { record ->
            record.serverId == serverId &&
                record.status in setOf(GiveawayStatus.OPEN, GiveawayStatus.DRAWING) &&
                (record.hostId == id || record.participants.any { it.playerId == id })
        }
    }
}
