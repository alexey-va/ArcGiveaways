package ru.ruscrafting.giveaways.paper

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import org.bukkit.Location
import org.bukkit.World
import ru.arc.core.BudgetedTaskQueue
import ru.arc.core.LifecycleTaskScope
import ru.arc.core.TestTaskScheduler
import java.lang.reflect.Proxy

class GiveawaySceneBudgetTest : StringSpec({
    "all scenes retain their particle totals and share a bounded native tick" {
        val scheduler = TestTaskScheduler()
        val scope = LifecycleTaskScope(scheduler)
        val queue = BudgetedTaskQueue(scope, maximumSteps = 128, nanoTime = { 0 })
        var calls = 0
        val counts = mutableMapOf<GiveawayVisualScene, Int>()
        val pending = GiveawayVisualScene.entries.map { scene ->
            val world = particleWorld { count -> calls++; counts[scene] = counts.getOrDefault(scene, 0) + count }
            val steps = NativeGiveawayPresentationPort.sceneSteps(Location(world, 2.0, 70.0, 3.0), scene, GiveawaySceneSpec(1_200, 2.0))
            queue.submit({ true }) { if (steps.hasNext()) { steps.next(); false } else true }
        }
        calls shouldBe 0
        scheduler.tick()
        calls shouldBe 128
        pending.any { it.isDone } shouldBe false
        scheduler.tick(40)
        pending.all { it.isDone && !it.isCompletedExceptionally } shouldBe true
        // Preserve the original integer rounding and the winner's extra villager burst.
        counts shouldBe mapOf(GiveawayVisualScene.AMBIENT to 1_200, GiveawayVisualScene.COUNTDOWN to 1_198,
            GiveawayVisualScene.DRAWING to 1_200, GiveawayVisualScene.WINNER to 1_240)
        queue.close()
        scope.close()
    }

    "superseded scene cannot emit more particles" {
        val scheduler = TestTaskScheduler()
        val scope = LifecycleTaskScope(scheduler)
        val queue = BudgetedTaskQueue(scope, maximumSteps = 2, nanoTime = { 0 })
        var calls = 0
        var current = true
        val steps = NativeGiveawayPresentationPort.sceneSteps(Location(particleWorld { calls++ }, 0.0, 70.0, 0.0),
            GiveawayVisualScene.WINNER, GiveawaySceneSpec(1_200, 2.0))
        val result = queue.submit({ current }) { if (steps.hasNext()) { steps.next(); false } else true }
        scheduler.tick()
        calls shouldBe 2
        current = false
        scheduler.tick(10)
        calls shouldBe 2
        result.isCompletedExceptionally shouldBe true
        queue.close()
        scope.close()
    }
})

private fun particleWorld(emit: (Int) -> Unit): World = Proxy.newProxyInstance(
    World::class.java.classLoader, arrayOf(World::class.java),
) { _, method, arguments ->
    when (method.name) {
        "getGameTime" -> 0L
        "spawnParticle" -> { emit(arguments!![2] as Int); null }
        "toString" -> "particle-test-world"
        else -> error("Unexpected native call: ${method.name}")
    }
} as World
