package ru.ruscrafting.giveaways.paper

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.bukkit.Material
import org.bukkit.inventory.ItemStack
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.arc.persistence.DurableAcknowledgementOutcome
import ru.ruscrafting.giveaways.domain.ItemPayload
import java.nio.file.Files
import java.util.Base64
import java.util.UUID

class InventoryJournalStoreTest : FunSpec({
    lateinit var paper: MockBukkitTestRuntime
    lateinit var dataRoot: java.nio.file.Path

    beforeEach {
        paper = MockBukkitTestRuntime.open()
        dataRoot = Files.createTempDirectory("arcgiveaways-journal-test-")
    }

    afterEach {
        paper.close()
        dataRoot.toFile().deleteRecursively()
    }

    test("exact acknowledgement retains a concurrently replaced journal") {
        val store = InventoryJournalStore(dataRoot)
        val prepared = journalRecord(JournalStatus.PREPARED)
        val applied = prepared.copy(status = JournalStatus.APPLIED)

        store.write(prepared) shouldBe prepared
        store.write(applied) shouldBe applied

        store.acknowledgeExactly(prepared) shouldBe DurableAcknowledgementOutcome.CONTENT_MISMATCH
        store.loadAll() shouldContainExactly listOf(applied)
        store.acknowledgeExactly(applied) shouldBe DurableAcknowledgementOutcome.ACKNOWLEDGED
        store.acknowledgeExactly(applied) shouldBe DurableAcknowledgementOutcome.ALREADY_ACKNOWLEDGED
    }
})

private fun journalRecord(status: JournalStatus): InventoryJournalRecord {
    val stack = ItemStack.of(Material.DIAMOND, 2)
    val bytes = stack.serializeAsBytes()
    val encoded = Base64.getEncoder().encodeToString(bytes)
    return InventoryJournalRecord(
        giveawayId = UUID.fromString("00000000-0000-0000-0000-000000000111").toString(),
        playerId = UUID.fromString("00000000-0000-0000-0000-000000000222").toString(),
        kind = JournalKind.PRIZE,
        status = status,
        item = ItemPayload.capture(stack.type.key.asString(), stack.amount, bytes),
        changes = listOf(SlotChange(slot = 0, beforeBase64 = null, afterBase64 = encoded)),
        createdAtMs = 1_787_730_000_000,
    )
}
