package ru.ruscrafting.giveaways.network

import ru.ruscrafting.giveaways.domain.GiveawayRecord
import java.util.concurrent.CompletableFuture

sealed interface HostClaimOutcome {
    data object Claimed : HostClaimOutcome
    data class Blocked(val giveawayId: String?) : HostClaimOutcome
}

/**
 * Reconciles the host hash with its authoritative giveaway record before claiming a new slot.
 * A dead owner may be cancelled only when backend presence has confirmed unavailability.
 */
class GiveawayHostLeaseCoordinator(
    private val repository: RedisGiveawayRepository,
    private val availability: (String) -> CompletableFuture<BackendAvailability>,
    private val cancelUnavailable: (GiveawayRecord) -> GiveawayRecord,
) {
    fun claim(hostId: String, giveawayId: String): CompletableFuture<HostClaimOutcome> =
        repository.claimHost(hostId, giveawayId).thenCompose { claimed ->
            if (claimed) CompletableFuture.completedFuture(HostClaimOutcome.Claimed)
            else recoverAndReplace(hostId, giveawayId)
        }

    private fun recoverAndReplace(hostId: String, giveawayId: String): CompletableFuture<HostClaimOutcome> =
        repository.hostGiveawayId(hostId).thenCompose { existingId ->
            if (existingId == null) {
                return@thenCompose repository.claimHost(hostId, giveawayId).thenApply { retried ->
                    if (retried) HostClaimOutcome.Claimed else HostClaimOutcome.Blocked(null)
                }
            }
            repository.load(existingId).thenCompose { existing ->
                when {
                    existing == null || !existing.reservesHost() -> replace(hostId, existingId, giveawayId)
                    else -> availability(existing.serverId).thenCompose { state ->
                        if (state == BackendAvailability.UNAVAILABLE) cancelThenReplace(hostId, existing, giveawayId)
                        else CompletableFuture.completedFuture(HostClaimOutcome.Blocked(existingId))
                    }
                }
            }
        }

    private fun cancelThenReplace(
        hostId: String,
        existing: GiveawayRecord,
        giveawayId: String,
    ): CompletableFuture<HostClaimOutcome> = repository.update(existing.id) { current ->
        if (current.hostId == hostId && current.reservesHost()) cancelUnavailable(current) else null
    }.thenCompose { result ->
        val released = when (result) {
            is RepositoryUpdate.Changed -> !result.after.reservesHost()
            is RepositoryUpdate.Rejected -> !result.current.reservesHost()
            RepositoryUpdate.Missing -> true
            RepositoryUpdate.Contended -> false
        }
        if (released) replace(hostId, existing.id, giveawayId)
        else CompletableFuture.completedFuture(HostClaimOutcome.Blocked(existing.id))
    }

    private fun replace(
        hostId: String,
        existingId: String,
        giveawayId: String,
    ): CompletableFuture<HostClaimOutcome> =
        repository.replaceHostClaim(hostId, existingId, giveawayId).thenApply { replaced ->
            if (replaced) HostClaimOutcome.Claimed else HostClaimOutcome.Blocked(existingId)
        }
}
