package ru.ruscrafting.giveaways.network

import io.kotest.core.spec.style.StringSpec
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import ru.arc.redis.InMemoryRedis
import ru.arc.redis.RedisOperations
import ru.arc.testing.DeterministicClock
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException

class GiveawayBackendDirectoryTest : StringSpec({
    "backend presence is live while heartbeats are leased and unavailable after expiry" {
        val clock = DeterministicClock.atMillis(1_000)
        val redis = InMemoryRedis()
        val origins = setOf("spawn", "survival")
        val spawn = GiveawayBackendDirectory(redis, "spawn", origins, leaseMillis = 10_000, clockMs = clock::millis)
        val survival = GiveawayBackendDirectory(redis, "survival", origins, leaseMillis = 10_000, clockMs = clock::millis)

        survival.probe("spawn").join() shouldBe BackendAvailability.UNKNOWN

        spawn.heartbeat().join()
        survival.probe("spawn").join() shouldBe BackendAvailability.LIVE

        clock.advance(Duration.ofSeconds(10))
        survival.probe("spawn").join() shouldBe BackendAvailability.UNAVAILABLE

        spawn.close()
        survival.close()
    }

    "a Redis outage after an earlier refresh never proves a remote backend unavailable" {
        val clock = DeterministicClock.atMillis(1_000)
        val redis = FailingLoadsRedis(InMemoryRedis())
        val directory = GiveawayBackendDirectory(
            redis = redis,
            localServerId = "survival",
            allowedOrigins = setOf("spawn", "survival"),
            leaseMillis = 10_000,
            clockMs = clock::millis,
        )

        directory.refresh().join()
        clock.advance(Duration.ofSeconds(10))
        redis.failLoads = true

        shouldThrow<CompletionException> { directory.probe("spawn").join() }
        directory.availability("spawn") shouldBe BackendAvailability.UNKNOWN

        directory.close()
    }
})

private class FailingLoadsRedis(
    private val delegate: RedisOperations,
) : RedisOperations by delegate {
    var failLoads: Boolean = false

    override fun loadMap(key: String): CompletableFuture<Map<String, String>> =
        if (failLoads) CompletableFuture.failedFuture(IllegalStateException("Redis unavailable"))
        else delegate.loadMap(key)
}
