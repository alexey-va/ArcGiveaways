package ru.ruscrafting.giveaways.network

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import ru.arc.redis.InMemoryRedis
import ru.arc.redis.ServerIdentity
import ru.ruscrafting.giveaways.domain.GiveawayStatus
import ru.ruscrafting.giveaways.giveaway
import ru.ruscrafting.giveaways.participant
import java.util.UUID
import java.util.concurrent.CompletableFuture

class RedisGiveawayRepositoryTest :
    StringSpec({
        "only one backend can claim a host" {
            val redis = InMemoryRedis()
            val repository = RedisGiveawayRepository(redis)
            val host = UUID(0, 1).toString()
            val first = UUID(0, 2).toString()
            val second = UUID(0, 3).toString()

            repository.claimHost(host, first).join().shouldBeTrue()
            repository.claimHost(host, second).join().shouldBeFalse()
            repository.releaseHost(host, second).join().shouldBeFalse()
            repository.releaseHost(host, first).join().shouldBeTrue()
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
            val bus = repository.registerEvents(originAllowed = { true }) { event, _ -> events += event.type }
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
            bus.close()
        }

        "event boundary rejects untrusted origins, malformed payloads, and replay" {
            val redis = InMemoryRedis(ServerIdentity { "spawn" })
            val repository = RedisGiveawayRepository(redis)
            val received = mutableListOf<GiveawayWireEvent>()
            val bus = repository.registerEvents(originAllowed = { it == "survival" }) { event, _ -> received += event }

            repository.create(giveaway()).join().shouldBeTrue()
            val raw = redis.getPublishedMessages().single().message
            redis.simulateExternalMessage(RedisGiveawayRepository.EVENT_CHANNEL, raw, "evil")
            redis.simulateExternalMessage(RedisGiveawayRepository.EVENT_CHANNEL, "{not-json", "survival")
            redis.simulateExternalMessage(RedisGiveawayRepository.EVENT_CHANNEL, raw, "survival")
            redis.simulateExternalMessage(RedisGiveawayRepository.EVENT_CHANNEL, raw, "survival")

            received.size shouldBe 1
            received.single().type shouldBe GiveawayEventType.CREATED
            bus.close()
        }
    })
