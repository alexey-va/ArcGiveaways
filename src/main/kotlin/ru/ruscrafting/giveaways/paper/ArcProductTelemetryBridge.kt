package ru.ruscrafting.giveaways.paper

import org.bukkit.Bukkit
import ru.arc.paper.api.ArcTelemetryProvider
import java.util.UUID

/** Optional ARC product event bridge; telemetry failures never affect delivery. */
internal object ArcProductTelemetryBridge {
    fun itemGranted(playerId: UUID, operationId: String): Boolean {
        if (!Bukkit.getPluginManager().isPluginEnabled("ARC")) return false
        return recordWith(
            gateway = { id, source, event, stableId ->
                AvailableArcTelemetry.recordEvent(id, source, event, stableId)
            },
            playerId = playerId,
            operationId = operationId,
        )
    }

    private object AvailableArcTelemetry {
        fun recordEvent(playerId: UUID, source: String, event: String, stableId: String): Boolean =
            Bukkit.getServicesManager().load(ArcTelemetryProvider::class.java)
                ?.recordEvent(playerId, source, event, stableId) == true
    }

    internal fun recordWith(
        gateway: (UUID, String, String, String) -> Boolean,
        playerId: UUID,
        operationId: String,
    ): Boolean = runCatching { gateway(playerId, "arcgiveaways", "giveaway_item_granted", operationId) }.getOrDefault(false)
}
