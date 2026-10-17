package ru.ruscrafting.giveaways.paper

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.inventory.ItemStack
import ru.arc.core.BudgetedTaskQueue
import ru.arc.core.LifecycleTaskScope
import ru.arc.core.TestTaskScheduler
import ru.arc.paper.playerstate.NativePaperItemStackBinaryCodec
import ru.arc.paper.playerstate.PaperItemStackBinaryCodec
import ru.arc.paper.testing.MockBukkitTestRuntime

class InventoryPlanCaptureTest : FunSpec({
    test("native before and after serialization yields through the aggregate queue") {
        MockBukkitTestRuntime.open().use { paper ->
            val player = paper.addPlayer("Host")
            player.inventory.setItem(0, ItemStack.of(Material.DIAMOND, 2))
            val scheduler = TestTaskScheduler()
            val scope = LifecycleTaskScope(scheduler)
            val work = BudgetedTaskQueue(scope, maximumSteps = 1)
            val nativeAmounts = mutableListOf<Int>()
            var otherWork = 0
            val codec = object : PaperItemStackBinaryCodec by NativePaperItemStackBinaryCodec {
                override fun encodeItem(item: ItemStack): ByteArray {
                    check(Bukkit.isPrimaryThread())
                    nativeAmounts += item.amount
                    return byteArrayOf(item.amount.toByte())
                }
            }
            try {
                val capture = requireNotNull(InventoryPlan.removal(player, 0, 1)).capture(work, { true }, codec)
                val other = work.call({ true }) { otherWork++ }
                nativeAmounts.size shouldBe 0
                repeat(8) {
                    val before = nativeAmounts.size + otherWork
                    scheduler.tick()
                    (nativeAmounts.size + otherWork - before <= 1) shouldBe true
                }
                other.isDone shouldBe true
                nativeAmounts shouldBe listOf(2, 1)
                val changes = capture.join()
                changes.single().before?.toList() shouldBe listOf(2.toByte())
                changes.single().after?.toList() shouldBe listOf(1.toByte())
                player.inventory.getItem(0)?.amount shouldBe 2
            } finally { work.close(); scope.close() }
        }
    }

    test("closing before native capture completes its future and leaves the inventory intact") {
        MockBukkitTestRuntime.open().use { paper ->
            val player = paper.addPlayer("Host")
            player.inventory.setItem(0, ItemStack.of(Material.DIAMOND, 2))
            val scheduler = TestTaskScheduler()
            val scope = LifecycleTaskScope(scheduler)
            val work = BudgetedTaskQueue(scope, maximumSteps = 1)
            val capture = requireNotNull(InventoryPlan.removal(player, 0, 1)).capture(work, { true })
            work.close()
            scope.close()
            scheduler.tick(5)
            capture.isCompletedExceptionally shouldBe true
            player.inventory.getItem(0)?.amount shouldBe 2
        }
    }

    test("read-only native journal observation yields before reporting exact before and after states") {
        MockBukkitTestRuntime.open().use { paper ->
            val player = paper.addPlayer("Host")
            val before = ItemStack.of(Material.DIAMOND, 2)
            val after = ItemStack.of(Material.DIAMOND, 1)
            player.inventory.setItem(0, before)
            val plan = InventoryPlan.prepare(listOf(RawSlotChange(0,
                NativePaperItemStackBinaryCodec.encodeItem(before),
                NativePaperItemStackBinaryCodec.encodeItem(after),
            )))
            val scheduler = TestTaskScheduler()
            val scope = LifecycleTaskScope(scheduler)
            val work = BudgetedTaskQueue(scope, maximumSteps = 1)
            try {
                val observation = plan.observation(player)
                val pending = work.submit({ true }, observation::step)
                scheduler.tick()
                pending.isDone shouldBe false
                scheduler.tick(4)
                pending.isDone shouldBe true
                observation.state() shouldBe PlanState.BEFORE
                player.inventory.getItem(0)?.amount shouldBe 2
                player.inventory.setItem(0, after)
                val afterObservation = plan.observation(player)
                val observedAfter = work.submit({ true }, afterObservation::step)
                scheduler.tick(5)
                observedAfter.isDone shouldBe true
                afterObservation.state() shouldBe PlanState.AFTER
                player.inventory.getItem(0)?.amount shouldBe 1
            } finally { work.close(); scope.close() }
        }
    }
})
