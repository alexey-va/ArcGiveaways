package ru.ruscrafting.giveaways.paper

import com.google.gson.Gson
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import ru.ruscrafting.giveaways.domain.ItemPayload
import java.nio.channels.FileChannel
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
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
    val formatVersion: Int = 1,
    val giveawayId: String,
    val playerId: String,
    val kind: JournalKind,
    val status: JournalStatus,
    val item: ItemPayload,
    val changes: List<SlotChange>,
    val createdAtMs: Long,
) {
    fun validated(): InventoryJournalRecord {
        require(formatVersion == 1)
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

    fun apply(player: Player): Boolean {
        if (state(player) != PlanState.BEFORE) return false
        changes.forEach { change -> player.inventory.setItem(change.slot, decode(change.afterBase64)) }
        player.updateInventory()
        player.saveData()
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

class InventoryJournalStore(
    dataRoot: Path,
    private val gson: Gson = Gson(),
) {
    private val directory = dataRoot.resolve("data/inventory-journals")

    init {
        Files.createDirectories(directory)
    }

    fun write(record: InventoryJournalRecord) {
        val validated = record.validated()
        val target = path(validated.giveawayId, validated.kind)
        val temporary = Files.createTempFile(directory, ".${validated.giveawayId}-", ".tmp")
        val bytes = (gson.toJson(validated) + "\n").toByteArray(StandardCharsets.UTF_8)
        FileChannel.open(temporary, StandardOpenOption.WRITE).use { channel ->
            channel.write(java.nio.ByteBuffer.wrap(bytes))
            channel.force(true)
        }
        try {
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    fun delete(giveawayId: String, kind: JournalKind) {
        Files.deleteIfExists(path(giveawayId, kind))
    }

    fun loadAll(): List<InventoryJournalRecord> {
        if (!Files.isDirectory(directory)) return emptyList()
        return Files.list(directory).use { paths ->
            paths.filter { it.fileName.toString().endsWith(".json") }.map { file ->
                val raw = Files.readString(file)
                require(raw.length <= MAX_JOURNAL_CHARS) { "Inventory journal is too large" }
                gson.fromJson(raw, InventoryJournalRecord::class.java).validated()
            }.toList()
        }
    }

    private fun path(giveawayId: String, kind: JournalKind): Path {
        UUID.fromString(giveawayId)
        return directory.resolve("$giveawayId-${kind.name.lowercase()}.json")
    }

    companion object {
        private const val MAX_JOURNAL_CHARS = 3_000_000
    }
}
