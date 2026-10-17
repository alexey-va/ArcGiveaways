package ru.ruscrafting.giveaways.paper

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import org.bukkit.Bukkit
import org.bukkit.plugin.ServicePriority
import ru.arc.paper.api.ArcRedisService
import ru.arc.paper.api.ArcNativeCleanupService
import ru.arc.paper.redis.PaperRedisClient
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.arc.paper.testing.loadPlugin
import ru.arc.redis.TestRedisModuleConfig
import ru.ruscrafting.giveaways.config.GiveawayConfig
import ru.ruscrafting.giveaways.config.GiveawayRedisBootstrap
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class ArcGiveawaysPluginLifecycleTest : FreeSpec({
    "maintenance configuration failure is handled before enable returns" {
        MockBukkitTestRuntime.open().use { paper ->
            val host = paper.createSimplePlugin("ARC")
            paper.server.servicesManager.register(ArcNativeCleanupService::class.java, mockk<ArcNativeCleanupService>(), host, ServicePriority.Normal)
            paper.server.servicesManager.register(ArcRedisService::class.java, mockk<ArcRedisService>(), host, ServicePriority.Normal)
            var preparations = 0
            mockkObject(GiveawayConfig.Companion)
            every { GiveawayConfig.inspect(any()) } answers {
                Bukkit.isPrimaryThread() shouldBe false
                preparations++
                throw IllegalStateException("isolated configuration failure")
            }
            try {
                val plugin = paper.loadPlugin<ArcGiveawaysPlugin>()
                preparations shouldBe 1
                plugin.isEnabled shouldBe false
                plugin.onDisable()
                preparations shouldBe 1
            } finally { unmockkObject(GiveawayConfig.Companion) }
        }
    }

    "maintenance startup failure waits for Redis close before returning" {
        MockBukkitTestRuntime.open().use { paper ->
            val host = paper.createSimplePlugin("ARC")
            paper.server.servicesManager.register(ArcNativeCleanupService::class.java, mockk<ArcNativeCleanupService>(), host, ServicePriority.Normal)
            val provider = mockk<ArcRedisService>()
            paper.server.servicesManager.register(ArcRedisService::class.java, provider, host, ServicePriority.Normal)
            val closeEntered = CountDownLatch(1)
            val releaseClose = CountDownLatch(1)
            val returned = AtomicBoolean()
            val closedBeforeReturn = AtomicBoolean()
            val manager = mockk<PaperRedisClient> {
                every { retainDuringShutdown() } returns Unit
                every { isConnected() } returns true
                every { healthCheck() } returns CompletableFuture.completedFuture(false)
                every { close() } answers {
                    Bukkit.isPrimaryThread() shouldBe false
                    closeEntered.countDown()
                    check(releaseClose.await(5, TimeUnit.SECONDS))
                    Unit
                }
            }
            mockkObject(GiveawayRedisBootstrap, PaperRedisClient.Companion)
            every { GiveawayRedisBootstrap.load(any(), any()) } returns TestRedisModuleConfig()
            every { PaperRedisClient.open(provider, any(), any(), any()) } answers { Bukkit.isPrimaryThread() shouldBe false; manager }
            val release = Thread {
                try {
                    if (closeEntered.await(5, TimeUnit.SECONDS)) closedBeforeReturn.set(!returned.get())
                } finally { releaseClose.countDown() }
            }.apply { start() }
            try {
                val plugin = paper.loadPlugin<ArcGiveawaysPlugin>()
                returned.set(true)
                closedBeforeReturn.get() shouldBe true
                plugin.isEnabled shouldBe false
            } finally {
                releaseClose.countDown(); release.join(5_000)
                unmockkObject(GiveawayRedisBootstrap, PaperRedisClient.Companion)
            }
        }
    }
})
