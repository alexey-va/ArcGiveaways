package ru.ruscrafting.giveaways.network

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import ru.arc.redis.InMemoryRedis
import ru.ruscrafting.giveaways.domain.GiveawayEngine
import ru.ruscrafting.giveaways.domain.GiveawayStatus
import ru.ruscrafting.giveaways.giveaway
import java.util.UUID

class GiveawayHostLeaseCoordinatorTest : StringSpec({
    val engine = GiveawayEngine(maxParticipants = 3, minimumParticipants = 2) { 0 }

    "a recovery-only record cannot keep the host slot forever" {
        val repository = RedisGiveawayRepository(InMemoryRedis())
        val existing = giveaway(status = GiveawayStatus.AWAITING_REFUND)
        val replacement = UUID.randomUUID().toString()
        repository.create(existing).join()
        repository.claimHost(existing.hostId, existing.id).join()
        val coordinator = GiveawayHostLeaseCoordinator(repository, { completed(BackendAvailability.LIVE) }) { record ->
            engine.cancel(record, 50_000, "owner_backend_unavailable")
        }

        coordinator.claim(existing.hostId, replacement).join() shouldBe HostClaimOutcome.Claimed
        repository.hostGiveawayId(existing.hostId).join() shouldBe replacement
    }

    "a live owner blocks replacement but a confirmed dead owner is cancelled and reclaimed" {
        val repository = RedisGiveawayRepository(InMemoryRedis())
        val existing = giveaway()
        repository.create(existing).join()
        repository.claimHost(existing.hostId, existing.id).join()
        val blocked = GiveawayHostLeaseCoordinator(repository, { completed(BackendAvailability.LIVE) }) { record ->
            engine.cancel(record, 50_000, "owner_backend_unavailable")
        }

        blocked.claim(existing.hostId, UUID.randomUUID().toString()).join() shouldBe HostClaimOutcome.Blocked(existing.id)

        val replacement = UUID.randomUUID().toString()
        val recovering = GiveawayHostLeaseCoordinator(repository, { completed(BackendAvailability.UNAVAILABLE) }) { record ->
            engine.cancel(record, 50_000, "owner_backend_unavailable")
        }
        recovering.claim(existing.hostId, replacement).join() shouldBe HostClaimOutcome.Claimed
        repository.load(existing.id).join()!!.status shouldBe GiveawayStatus.AWAITING_REFUND
        repository.hostGiveawayId(existing.hostId).join() shouldBe replacement
    }
})

private fun completed(value: BackendAvailability) = java.util.concurrent.CompletableFuture.completedFuture(value)
