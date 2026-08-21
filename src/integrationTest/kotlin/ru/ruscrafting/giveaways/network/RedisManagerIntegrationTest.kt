package ru.ruscrafting.giveaways.network

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import ru.arc.redis.RedisConnection
import ru.arc.redis.RedisManager
import ru.arc.redis.ServerIdentity
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Files
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

class RedisManagerIntegrationTest :
    StringSpec({
        "Lua compare-and-set is atomic against a disposable Redis server" {
            val port = ServerSocket(0).use { it.localPort }
            val directory = Files.createTempDirectory("arcgiveaways-redis-")
            val process = ProcessBuilder(
                System.getenv("REDIS_SERVER_BIN") ?: "redis-server",
                "--bind", "127.0.0.1",
                "--port", port.toString(),
                "--save", "",
                "--appendonly", "no",
                "--dir", directory.toString(),
            ).redirectErrorStream(true).start()
            val outputDrain = Thread { process.inputStream.bufferedReader().useLines { lines -> lines.forEach { _ -> } } }.apply {
                isDaemon = true
                start()
            }
            var redis: RedisManager? = null
            try {
                val deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos()
                while (System.nanoTime() < deadline) {
                    val ready = runCatching { Socket("127.0.0.1", port).use { } }.isSuccess
                    if (ready) break
                    Thread.sleep(25)
                }
                require(runCatching { Socket("127.0.0.1", port).use { } }.isSuccess) { "Disposable Redis did not become ready" }
                redis = RedisManager(RedisConnection("127.0.0.1", port), ServerIdentity { "integration" })
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
                process.destroy()
                if (!process.waitFor(5, TimeUnit.SECONDS)) process.destroyForcibly()
                outputDrain.join(1_000)
                directory.toFile().deleteRecursively()
            }
        }
    })
