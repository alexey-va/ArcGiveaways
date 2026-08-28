package ru.ruscrafting.giveaways.paper

import com.google.gson.Gson
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import ru.arc.persistence.DurableRecordJournal
import ru.arc.persistence.DurableAcknowledgementOutcome
import ru.ruscrafting.giveaways.domain.ItemPayload
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

enum class JournalKind { ESCROW, PRIZE, REFUND }
enum class JournalStatus { PREPARED, APPLIED }
enum class PlanState { BEFORE, AFTER, AMBIGUOUS }

internal class InventoryRecoveryIncidentTracker {
    private val ambiguous = ConcurrentHashMap.newKeySet<String>()

    fun markAmbiguous(journalKey: String): Boolean = ambiguous.add(journalKey)

    fun clear(journalKey: String) {
        ambiguous.remove(journalKey)
    }

    fun clearAll() {
        ambiguous.clear()
    }
}

data class SlotChange(
    val slot: Int,
    val beforeBase64: String?,
    val afterBase64: String?,
)

data class InventoryJournalRecord(
    val formatVersion: Int = FORMAT_VERSION,
    val giveawayId: String,
    val playerId: String,
    val kind: JournalKind,
    val status: JournalStatus,
    val item: ItemPayload,
    val changes: List<SlotChange>,
    val createdAtMs: Long,
) {
    fun validated(): InventoryJournalRecord {
        require(formatVersion == FORMAT_VERSION)
        UUID.fromString(giveawayId)
        UUID.fromString(playerId)
        require(changes.isNotEmpty() && changes.size <= 36)
        require(changes.map { it.slot }.distinct().size == changes.size)
        require(changes.all { it.slot in 0..35 })
        changes.flatMap { listOfNotNull(it.beforeBase64, it.afterBase64) }.forEach { encoded ->
            require(encoded.length <= ItemPayload.MAX_BASE64_CHARS)
            ItemStack.deserializeBytes(Base64.getDecoder().decode(encoded))
        }
        item.decodedBytes()
        return this
    }

    companion object {
        const val FORMAT_VERSION = 1
    }
}

class InventoryPlan private constructor(val changes: List<SlotChange>) {
    fun state(player: Player): PlanState {
        val contents = player.inventory.storageContents
        val before = changes.all { change -> same(contents.getOrNull(change.slot), change.beforeBase64) }
        val after = changes.all { change -> same(contents.getOrNull(change.slot), change.afterBase64) }
        return when {
            before -> PlanState.BEFORE
            after -> PlanState.AFTER
            else -> PlanState.AMBIGUOUS
        }
    }

    fun apply(
        player: Player,
        playerData: GiveawayPlayerDataPersistence = NativeGiveawayPlayerDataPersistence,
    ): Boolean {
        if (state(player) != PlanState.BEFORE) return false
        changes.forEach { change -> player.inventory.setItem(change.slot, decode(change.afterBase64)) }
        player.updateInventory()
        playerData.persist(player)
        return state(player) == PlanState.AFTER
    }

    companion object {
        fun removal(player: Player, slot: Int, amount: Int): InventoryPlan? {
            if (slot !in 0..8 || amount <= 0) return null
            val current = player.inventory.getItem(slot)?.takeUnless { it.type.isAir } ?: return null
            if (amount > current.amount) return null
            val before = encode(current)
            val after = if (amount == current.amount) null else encode(current.clone().also { it.amount -= amount })
            return InventoryPlan(listOf(SlotChange(slot, before, after)))
        }

        fun delivery(player: Player, item: ItemStack): InventoryPlan? {
            require(!item.type.isAir && item.amount > 0)
            val before = player.inventory.storageContents.map { it?.takeUnless { stack -> stack.type.isAir }?.clone() }.toMutableList()
            val after = before.map { it?.clone() }.toMutableList()
            var remaining = item.amount

            for (slot in after.indices) {
                val current = after[slot] ?: continue
                if (!current.isSimilar(item) || current.amount >= current.maxStackSize) continue
                val add = minOf(remaining, current.maxStackSize - current.amount)
                current.amount += add
                remaining -= add
                if (remaining == 0) break
            }
            if (remaining > 0) {
                for (slot in after.indices) {
                    if (after[slot] != null) continue
                    val add = minOf(remaining, item.maxStackSize)
                    after[slot] = item.clone().also { it.amount = add }
                    remaining -= add
                    if (remaining == 0) break
                }
            }
            if (remaining != 0) return null

            val changes =
                after.indices.mapNotNull { slot ->
                    val beforeBytes = encode(before[slot])
                    val afterBytes = encode(after[slot])
                    if (beforeBytes == afterBytes) null else SlotChange(slot, beforeBytes, afterBytes)
                }
            return changes.takeIf { it.isNotEmpty() }?.let(::InventoryPlan)
        }

        fun from(changes: List<SlotChange>): InventoryPlan = InventoryPlan(changes)

        private fun same(stack: ItemStack?, encoded: String?): Boolean =
            InventoryStackMatcher.matchesSerialized(stack, encoded)

        private fun encode(stack: ItemStack?): String? =
            stack?.takeUnless { it.type.isAir }?.serializeAsBytes()?.let { Base64.getEncoder().encodeToString(it) }

        private fun decode(encoded: String?): ItemStack? =
            encoded?.let { ItemStack.deserializeBytes(Base64.getDecoder().decode(it)) }
    }
}

internal object InventoryStackMatcher {
    fun matchesSerialized(actual: ItemStack?, expectedBase64: String?): Boolean {
        val normalizedActual = actual?.takeUnless { it.type.isAir }
        if (expectedBase64 == null) return normalizedActual == null
        val expected = runCatching {
            ItemStack.deserializeBytes(Base64.getDecoder().decode(expectedBase64))
        }.getOrNull()?.takeUnless { it.type.isAir } ?: return false
        return matches(normalizedActual, expected)
    }

    fun matches(actual: ItemStack?, expected: ItemStack?): Boolean {
        if (actual == null || expected == null) return actual == null && expected == null
        return runCatching { actual.amount == expected.amount && actual.isSimilar(expected) }.getOrDefault(false)
    }
}

interface InventoryJournalRepository {
    fun write(record: InventoryJournalRecord): InventoryJournalRecord

    fun acknowledgeExactly(record: InventoryJournalRecord): DurableAcknowledgementOutcome

    fun loadAll(): List<InventoryJournalRecord>
}

class InventoryJournalStore(
    dataRoot: Path,
    private val gson: Gson = Gson(),
) : InventoryJournalRepository {
    private val journal = DurableRecordJournal(
        root = dataRoot,
        relativeDirectory = Path.of("data/inventory-journals"),
        maxRecordBytes = MAX_JOURNAL_BYTES,
        encode = { record: InventoryJournalRecord ->
            (gson.toJson(record) + "\n").toByteArray(StandardCharsets.UTF_8)
        },
        decode = { bytes ->
            requireNotNull(gson.fromJson(bytes.toString(StandardCharsets.UTF_8), InventoryJournalRecord::class.java))
        },
        validate = InventoryJournalRecord::validated,
    )

    override fun write(record: InventoryJournalRecord): InventoryJournalRecord {
        val validated = record.validated()
        return journal.commit(recordId(validated.giveawayId, validated.kind), validated)
    }

    override fun acknowledgeExactly(record: InventoryJournalRecord): DurableAcknowledgementOutcome =
        journal.acknowledgeExactly(recordId(record.giveawayId, record.kind), record.validated()) { expected, current ->
            expected == current
        }

    override fun loadAll(): List<InventoryJournalRecord> = journal.loadAll().map { stored ->
        stored.value.also { record ->
            require(stored.recordId == recordId(record.giveawayId, record.kind)) {
                "Inventory journal filename does not match its record identity"
            }
        }
    }

    private fun recordId(giveawayId: String, kind: JournalKind): String {
        UUID.fromString(giveawayId)
        return "$giveawayId-${kind.name.lowercase()}"
    }

    companion object {
        private const val MAX_JOURNAL_BYTES = 3_000_000L
    }
}
