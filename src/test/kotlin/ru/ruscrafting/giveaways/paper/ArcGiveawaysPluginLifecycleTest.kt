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
import java.util.concurrent.atomic.AtomicInteger

class ArcGiveawaysPluginLifecycleTest : FreeSpec({
    "reenable and a superseded waiting enable retain the exact accepted preparation" {
        MockBukkitTestRuntime.open().use { paper ->
            val host = paper.createSimplePlugin("ARC")
            paper.server.servicesManager.register(ArcNativeCleanupService::class.java, mockk<ArcNativeCleanupService>(), host, ServicePriority.Normal)
            paper.server.servicesManager.register(ArcRedisService::class.java, mockk<ArcRedisService>(), host, ServicePriority.Normal)
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val preparations = AtomicInteger()
            mockkObject(GiveawayConfig.Companion)
            every { GiveawayConfig.inspect(any()) } answers {
                Bukkit.isPrimaryThread() shouldBe false
                if (preparations.incrementAndGet() == 1) {
                    entered.countDown()
                    check(release.await(5, TimeUnit.SECONDS))
                }
                throw IllegalStateException("isolated lifecycle preparation")
            }
            var plugin: ArcGiveawaysPlugin? = null
            try {
                plugin = paper.loadPlugin<ArcGiveawaysPlugin>()
                awaitGiveawayLifecycle(paper) { entered.count == 0L }
                paper.server.pluginManager.disablePlugin(plugin)
                plugin.drained().isDone shouldBe false
                plugin.onDisable()
                plugin.drained().isDone shouldBe false
                paper.server.pluginManager.enablePlugin(plugin)
                paper.performTicks(3)
                preparations.get() shouldBe 1
                paper.server.pluginManager.disablePlugin(plugin)
                paper.server.pluginManager.enablePlugin(plugin)
                paper.performTicks(3)
                preparations.get() shouldBe 1
                release.countDown()
                awaitGiveawayLifecycle(paper) { preparations.get() == 2 }
                awaitGiveawayLifecycle(paper) { !plugin.isEnabled }
            } finally {
                release.countDown()
                plugin?.let { active -> paper.server.pluginManager.disablePlugin(active); active.drained().get(5, TimeUnit.SECONDS) }
                unmockkObject(GiveawayConfig.Companion)
            }
        }
    }

    "terminal Redis close runs on the old worker and failure prevents replacement acquisition" {
        MockBukkitTestRuntime.open().use { paper ->
            val host = paper.createSimplePlugin("ARC")
            paper.server.servicesManager.register(ArcNativeCleanupService::class.java, mockk<ArcNativeCleanupService>(), host, ServicePriority.Normal)
            val provider = mockk<ArcRedisService>()
            paper.server.servicesManager.register(ArcRedisService::class.java, provider, host, ServicePriority.Normal)
            val acquired = CountDownLatch(1)
            val closeEntered = CountDownLatch(1)
            val releaseClose = CountDownLatch(1)
            val opens = AtomicInteger()
            val manager = mockk<PaperRedisClient> {
                every { retainDuringShutdown() } returns Unit
                every { isConnected() } returns true
                every { healthCheck() } returns CompletableFuture.completedFuture(true)
                every { init() } returns Unit
                every { close() } answers {
                    Bukkit.isPrimaryThread() shouldBe false
                    closeEntered.countDown()
                    check(releaseClose.await(5, TimeUnit.SECONDS))
                    throw IllegalStateException("terminal Redis cleanup failed")
                }
            }
            mockkObject(GiveawayRedisBootstrap, PaperRedisClient.Companion)
            every { GiveawayRedisBootstrap.load(any(), any()) } returns TestRedisModuleConfig()
            every { PaperRedisClient.open(provider, any(), any(), any()) } answers {
                Bukkit.isPrimaryThread() shouldBe false
                opens.incrementAndGet()
                acquired.countDown()
                manager
            }
            var plugin: ArcGiveawaysPlugin? = null
            try {
                plugin = paper.loadPlugin<ArcGiveawaysPlugin>()
                paper.performTicks(1)
                acquired.await(5, TimeUnit.SECONDS) shouldBe true
                paper.server.pluginManager.disablePlugin(plugin)
                closeEntered.await(5, TimeUnit.SECONDS) shouldBe true
                plugin.drained().isDone shouldBe false
                paper.server.pluginManager.enablePlugin(plugin)
                paper.performTicks(3)
                opens.get() shouldBe 1
                releaseClose.countDown()
                awaitGiveawayLifecycle(paper) { !plugin.isEnabled }
                plugin.drained().isCompletedExceptionally shouldBe true
                opens.get() shouldBe 1
            } finally {
                releaseClose.countDown()
                plugin?.let { active ->
                    paper.server.pluginManager.disablePlugin(active)
                    runCatching { active.drained().get(5, TimeUnit.SECONDS) }
                }
                unmockkObject(GiveawayRedisBootstrap, PaperRedisClient.Companion)
            }
        }
    }
})

private fun awaitGiveawayLifecycle(paper: MockBukkitTestRuntime, ready: () -> Boolean) {
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
    while (!ready() && System.nanoTime() < deadline) { paper.performTicks(1); Thread.sleep(1) }
    ready() shouldBe true
}
