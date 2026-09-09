package ru.ruscrafting.giveaways.paper

import org.bukkit.Bukkit
import ru.arc.paper.api.ArcTelemetryProvider
import java.util.UUID

/** Optional ARC product event bridge; telemetry failures never affect delivery. */
internal object ArcProductTelemetryBridge {
    private val telemetry by lazy { Bukkit.getServicesManager().load(ArcTelemetryProvider::class.java) }

    fun itemGranted(playerId: UUID, operationId: String): Boolean = recordWith(
        gateway = { id, source, event, stableId ->
            telemetry?.recordEvent(id, source, event, stableId) == true
        },
        playerId = playerId,
        operationId = operationId,
    )

    internal fun recordWith(
        gateway: (UUID, String, String, String) -> Boolean,
        playerId: UUID,
        operationId: String,
    ): Boolean = runCatching { gateway(playerId, "arcgiveaways", "giveaway_item_granted", operationId) }.getOrDefault(false)
}
