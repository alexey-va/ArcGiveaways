package ru.ruscrafting.giveaways.network

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import ru.arc.redis.InMemoryRedis
import ru.ruscrafting.giveaways.domain.GiveawayStatus
import ru.ruscrafting.giveaways.giveaway
import ru.ruscrafting.giveaways.participant
import java.util.concurrent.CompletableFuture

class RedisGiveawayRepositoryTest :
    StringSpec({
        "only one backend can claim a host" {
            val redis = InMemoryRedis()
            val repository = RedisGiveawayRepository(redis)

            repository.claimHost("host", "one").join().shouldBeTrue()
            repository.claimHost("host", "two").join().shouldBeFalse()
            repository.releaseHost("host", "two").join().shouldBeFalse()
            repository.releaseHost("host", "one").join().shouldBeTrue()
        }

        "concurrent duplicate joins produce one participant" {
            val redis = InMemoryRedis()
            val repository = RedisGiveawayRepository(redis)
            val initial = giveaway()
            val entrant = participant()
            repository.create(initial).join().shouldBeTrue()

            val joins = (1..16).map {
                CompletableFuture.supplyAsync {
                    repository.update(initial.id) { current ->
                        if (current.status != GiveawayStatus.OPEN || current.participants.any { it.playerId == entrant.playerId }) null
                        else current.copy(participants = current.participants + entrant)
                    }.join()
                }
            }
            CompletableFuture.allOf(*joins.toTypedArray()).join()

            repository.load(initial.id).join()!!.participants shouldBe listOf(entrant)
            joins.count { it.join() is RepositoryUpdate.Changed } shouldBe 1
        }

        "events converge from create update and delete" {
            val redis = InMemoryRedis()
            val repository = RedisGiveawayRepository(redis)
            val events = mutableListOf<GiveawayEventType>()
            repository.registerEvents { event, _ -> events += event.type }
            val initial = giveaway()

            repository.create(initial).join().shouldBeTrue()
            val changed = repository.update(initial.id) { it.copy(status = GiveawayStatus.AWAITING_REFUND) }.join()
            val terminal = (changed as RepositoryUpdate.Changed).after.copy(status = GiveawayStatus.CANCELLED, terminalAtMs = 5_000)
            val final = (repository.update(initial.id) { terminal }.join() as RepositoryUpdate.Changed).after
            repository.delete(final).join().shouldBeTrue()

            events shouldContainExactlyInAnyOrder listOf(
                GiveawayEventType.CREATED,
                GiveawayEventType.UPDATED,
                GiveawayEventType.TERMINAL,
                GiveawayEventType.DELETED,
            )
        }
    })
