package ru.ruscrafting.giveaways.paper

import com.google.gson.Gson
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import ru.arc.core.BudgetedTaskQueue
import ru.arc.paper.playerstate.NativePaperItemStackBinaryCodec
import ru.arc.paper.playerstate.PaperItemStackBinaryCodec
import java.util.concurrent.CompletableFuture
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
) {
    @Transient @Volatile private var prepared: RawSlotChange? = null

    internal fun prepareBytes() {
        fun decode(value: String?): ByteArray? = value?.let {
            require(it.length <= ItemPayload.MAX_BASE64_CHARS)
            Base64.getDecoder().decode(it).also { bytes -> require(bytes.size in 1..ItemPayload.MAX_BYTES) }
        }
        prepared = RawSlotChange(slot, decode(beforeBase64), decode(afterBase64))
    }

    internal fun beforeBytes(): ByteArray? = requireNotNull(prepared) { "Slot bytes were not prepared" }.before
    internal fun afterBytes(): ByteArray? = requireNotNull(prepared) { "Slot bytes were not prepared" }.after

    companion object {
        internal fun fromRaw(raw: RawSlotChange): SlotChange = SlotChange(raw.slot,
            raw.before?.let { Base64.getEncoder().encodeToString(it) },
            raw.after?.let { Base64.getEncoder().encodeToString(it) },
        ).also { it.prepared = raw }
    }
}

internal data class RawSlotChange(val slot: Int, val before: ByteArray?, val after: ByteArray?)
internal data class NativeSlotChange(val slot: Int, val before: ItemStack?, val after: ItemStack?)

/** Native stacks stay here on Paper; only immutable byte DTOs cross to storage. */
class InventoryPlanCapture internal constructor(private val changes: List<NativeSlotChange>) {
    internal fun capture(
        nativeWork: BudgetedTaskQueue,
        isCurrent: () -> Boolean,
        codec: PaperItemStackBinaryCodec = NativePaperItemStackBinaryCodec,
    ): CompletableFuture<List<RawSlotChange>> {
        val result = mutableListOf<RawSlotChange>()
        var index = 0
        var before: ByteArray? = null
        var beforeCaptured = false
        return nativeWork.submit(isCurrent) {
            if (index >= changes.size) true else {
                val change = changes[index]
                if (!beforeCaptured) {
                    before = change.before?.let(codec::encodeItem)
                    beforeCaptured = true
                } else {
                    val after = change.after?.let(codec::encodeItem)
                    result += RawSlotChange(change.slot, before, after)
                    before = null
                    beforeCaptured = false
                    index++
                }
                false
            }
        }.thenApply { result }
    }
}

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
        changes.forEach(SlotChange::prepareBytes)
        item.decodedBytes()
        return this
    }

    companion object {
        const val FORMAT_VERSION = 1

        /** Native validation belongs on Paper; persistent JSON validation above is worker safe. */
        fun validateNative(change: SlotChange) {
            change.beforeBytes()?.let(NativePaperItemStackBinaryCodec::decodeItem)
            change.afterBytes()?.let(NativePaperItemStackBinaryCodec::decodeItem)
        }
    }
}

class InventoryPlan private constructor(val changes: List<SlotChange>) {
    internal fun observation(player: Player): InventoryPlanObservation = InventoryPlanObservation(player, changes)

    fun state(player: Player): PlanState {
        val contents = player.inventory.storageContents
        val before = changes.all { change -> same(contents.getOrNull(change.slot), change.beforeBytes()) }
        val after = changes.all { change -> same(contents.getOrNull(change.slot), change.afterBytes()) }
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
        changes.forEach { change -> player.inventory.setItem(change.slot, decode(change.afterBytes())) }
        player.updateInventory()
        playerData.persist(player)
        return state(player) == PlanState.AFTER
    }

    companion object {
        fun removal(player: Player, slot: Int, amount: Int): InventoryPlanCapture? {
            if (slot !in 0..8 || amount <= 0) return null
            val current = player.inventory.getItem(slot)?.takeUnless { it.type.isAir } ?: return null
            if (amount > current.amount) return null
            val before = current.clone()
            val after = if (amount == current.amount) null else current.clone().also { it.amount -= amount }
            return InventoryPlanCapture(listOf(NativeSlotChange(slot, before, after)))
        }

        fun delivery(player: Player, item: ItemStack): InventoryPlanCapture? {
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
                    if (InventoryStackMatcher.matches(before[slot], after[slot])) null
                    else NativeSlotChange(slot, before[slot], after[slot])
                }
            return changes.takeIf { it.isNotEmpty() }?.let(::InventoryPlanCapture)
        }

        fun from(changes: List<SlotChange>): InventoryPlan = InventoryPlan(changes)

        internal fun prepare(changes: List<RawSlotChange>): InventoryPlan = InventoryPlan(changes.map(SlotChange::fromRaw))

        private fun same(stack: ItemStack?, bytes: ByteArray?): Boolean =
            InventoryStackMatcher.matches(stack?.takeUnless { it.type.isAir }, decode(bytes))

        private fun decode(bytes: ByteArray?): ItemStack? = bytes?.let(NativePaperItemStackBinaryCodec::decodeItem)
    }
}

/** Read-only diagnostics serialize no inventory and decode at most one native item per step. */
internal class InventoryPlanObservation(private val player: Player, private val changes: List<SlotChange>) {
    private var index = 0
    private var phase = 0
    private var actual: ItemStack? = null
    private var before = true
    private var after = true

    fun step(): Boolean {
        if (index == changes.size) return true
        val change = changes[index]
        when (phase) {
            0 -> {
                actual = player.inventory.getItem(change.slot)?.takeUnless { it.type.isAir }?.clone()
                phase = 1
            }
            1 -> {
                val expected = change.beforeBytes()?.let(NativePaperItemStackBinaryCodec::decodeItem)
                before = before && InventoryStackMatcher.matches(actual, expected)
                phase = 2
            }
            else -> {
                val expected = change.afterBytes()?.let(NativePaperItemStackBinaryCodec::decodeItem)
                after = after && InventoryStackMatcher.matches(actual, expected)
                actual = null
                phase = 0
                index++
            }
        }
        return index == changes.size
    }

    fun state(): PlanState {
        check(index == changes.size)
        return when { before -> PlanState.BEFORE; after -> PlanState.AFTER; else -> PlanState.AMBIGUOUS }
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
