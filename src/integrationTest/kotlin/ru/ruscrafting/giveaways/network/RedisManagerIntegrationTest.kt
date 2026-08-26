package ru.ruscrafting.giveaways.network

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import ru.arc.redis.RedisConnection
import ru.arc.redis.RedisManager
import ru.arc.redis.ServerIdentity
import ru.arc.testing.containers.RedisTestService
import java.util.concurrent.CompletableFuture

class RedisManagerIntegrationTest :
    StringSpec({
        "Lua compare-and-set is atomic against a disposable Redis server" {
            RedisTestService.start().use { service ->
                var redis: RedisManager? = null
                try {
                    redis = RedisManager(
                        RedisConnection(service.endpoint.host, service.endpoint.port),
                        ServerIdentity { "integration" },
                    )
                    val connected = requireNotNull(redis) { "Disposable Redis did not become ready" }

                    val attempts = (1..32).map { value ->
                        CompletableFuture.supplyAsync {
                            connected.compareAndSetMapEntry("giveaways", "shared", null, value.toString()).join()
                        }
                    }
                    CompletableFuture.allOf(*attempts.toTypedArray()).join()
                    attempts.count { it.join() }.shouldBe(1)
                    connected.compareAndSetMapEntry("giveaways", "shared", "stale", null).join().shouldBeFalse()
                    val current = connected.loadMapEntries("giveaways", "shared").join().single()
                    connected.compareAndSetMapEntry("giveaways", "shared", current, null).join().shouldBeTrue()
                    connected.loadMapEntries("giveaways", "shared").join().single() shouldBe null
                } finally {
                    redis?.close()
                }
            }
        }
    })
