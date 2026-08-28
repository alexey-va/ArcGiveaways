package ru.ruscrafting.giveaways.domain

import ru.arc.network.BackendServerId
import ru.arc.network.NetworkPlayerName
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID

enum class GiveawayStatus {
    PREPARING,
    OPEN,
    DRAWING,
    AWAITING_DELIVERY,
    AWAITING_REFUND,
    COMPLETED,
    CANCELLED,
}

data class ItemPayload(
    val materialKey: String,
    val amount: Int,
    val bytesBase64: String,
    val sha256: String,
) {
    fun decodedBytes(): ByteArray {
        require(materialKey.matches(RESOURCE_KEY)) { "Invalid item material key" }
        require(amount in 1..MAX_AMOUNT) { "Invalid item amount" }
        require(bytesBase64.length <= MAX_BASE64_CHARS) { "Item payload is too large" }
        val bytes = Base64.getDecoder().decode(bytesBase64)
        require(bytes.size in 1..MAX_BYTES) { "Item payload is empty or too large" }
        require(sha256(bytes) == sha256) { "Item payload checksum mismatch" }
        return bytes
    }

    companion object {
        const val MAX_AMOUNT = 64 * 54
        const val MAX_BYTES = 1_048_576
        const val MAX_BASE64_CHARS = 1_398_104
        private val RESOURCE_KEY = Regex("[a-z0-9_.-]+:[a-z0-9_./-]+")

        fun capture(materialKey: String, amount: Int, bytes: ByteArray): ItemPayload {
            require(bytes.size in 1..MAX_BYTES) { "Item payload is empty or too large" }
            return ItemPayload(materialKey, amount, Base64.getEncoder().encodeToString(bytes), sha256(bytes))
                .also { it.decodedBytes() }
        }

        private fun sha256(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }
}

data class GiveawayParticipant(
    val playerId: String,
    val playerName: String,
    val joinedAtMs: Long,
) {
    fun validated(): GiveawayParticipant {
        require(UUID.fromString(playerId).toString() == playerId) { "Invalid participant id" }
        NetworkPlayerName.of(playerName)
        require(joinedAtMs > 0) { "Invalid participant timestamp" }
        return this
    }
}

data class GiveawayRecord(
    val protocolVersion: Int = PROTOCOL_VERSION,
    val id: String,
    val revision: Long,
    val status: GiveawayStatus,
    val hostId: String,
    val hostName: String,
    val serverId: String,
    val worldName: String,
    val anchorX: Double,
    val anchorY: Double,
    val anchorZ: Double,
    val radius: Double,
    val item: ItemPayload,
    val createdAtMs: Long,
    val opensAtMs: Long,
    val drawAtMs: Long,
    val hostHandoffStartedAtMs: Long? = null,
    val hostHandoffUntilMs: Long? = null,
    val drawingEndsAtMs: Long? = null,
    val terminalAtMs: Long? = null,
    val participants: List<GiveawayParticipant> = emptyList(),
    val drawingCandidates: List<GiveawayParticipant> = emptyList(),
    val winner: GiveawayParticipant? = null,
    val terminalReason: String? = null,
) {
    fun validated(maxParticipants: Int = MAX_PARTICIPANTS): GiveawayRecord {
        require(protocolVersion == PROTOCOL_VERSION) { "Unsupported giveaway protocol" }
        require(UUID.fromString(id).toString() == id) { "Invalid giveaway id" }
        require(UUID.fromString(hostId).toString() == hostId) { "Invalid host id" }
        require(revision >= 0) { "Invalid revision" }
        NetworkPlayerName.of(hostName)
        BackendServerId.of(serverId)
        require(worldName.length in 1..128) { "Invalid world name" }
        require(listOf(anchorX, anchorY, anchorZ, radius).all(Double::isFinite)) { "Invalid giveaway location" }
        require(radius in 1.0..1000.0) { "Invalid giveaway radius" }
        require(createdAtMs > 0 && opensAtMs >= createdAtMs && drawAtMs > opensAtMs) { "Invalid giveaway timing" }
        require((hostHandoffStartedAtMs == null) == (hostHandoffUntilMs == null)) { "Incomplete host handoff timing" }
        if (hostHandoffStartedAtMs != null && hostHandoffUntilMs != null) {
            require(hostHandoffStartedAtMs >= createdAtMs && hostHandoffUntilMs > hostHandoffStartedAtMs) {
                "Invalid host handoff timing"
            }
            require(status == GiveawayStatus.OPEN || status == GiveawayStatus.DRAWING) {
                "Host handoff is only valid while a giveaway is running"
            }
        }
        require(participants.size <= maxParticipants.coerceIn(1, MAX_PARTICIPANTS)) { "Too many participants" }
        participants.forEach(GiveawayParticipant::validated)
        drawingCandidates.forEach(GiveawayParticipant::validated)
        require(participants.map { it.playerId }.distinct().size == participants.size) { "Duplicate participant" }
        require(drawingCandidates.map { it.playerId }.distinct().size == drawingCandidates.size) { "Duplicate draw candidate" }
        require(drawingCandidates.all { candidate -> participants.any { it.playerId == candidate.playerId } }) {
            "Draw candidate was not registered"
        }
        winner?.validated()
        if (winner != null) require(drawingCandidates.any { it.playerId == winner.playerId }) { "Winner was not in the draw snapshot" }
        when (status) {
            GiveawayStatus.DRAWING -> require(drawingEndsAtMs != null && drawingCandidates.isNotEmpty()) { "Drawing snapshot is missing" }
            GiveawayStatus.AWAITING_DELIVERY, GiveawayStatus.COMPLETED -> require(winner != null) { "Winner is missing" }
            else -> Unit
        }
        if (status == GiveawayStatus.COMPLETED || status == GiveawayStatus.CANCELLED) require(terminalAtMs != null) { "Terminal timestamp is missing" }
        require(terminalReason == null || terminalReason.length <= 96) { "Terminal reason is too long" }
        item.decodedBytes()
        return this
    }

    fun isActive(): Boolean = status in ACTIVE_STATUSES

    /** The host slot is needed only while this giveaway can still advance into a draw. */
    fun reservesHost(): Boolean = status in HOST_RESERVING_STATUSES

    fun displayId(): String = id.substring(0, 8)

    companion object {
        const val PROTOCOL_VERSION = 1
        const val MAX_PARTICIPANTS = 250
        private val ACTIVE_STATUSES = setOf(
            GiveawayStatus.PREPARING,
            GiveawayStatus.OPEN,
            GiveawayStatus.DRAWING,
            GiveawayStatus.AWAITING_DELIVERY,
            GiveawayStatus.AWAITING_REFUND,
        )
        private val HOST_RESERVING_STATUSES = setOf(
            GiveawayStatus.PREPARING,
            GiveawayStatus.OPEN,
            GiveawayStatus.DRAWING,
        )
    }
}

sealed interface JoinDecision {
    data class Joined(val record: GiveawayRecord) : JoinDecision
    data class AlreadyJoined(val record: GiveawayRecord) : JoinDecision
    data object NotOpen : JoinDecision
    data object HostExcluded : JoinDecision
    data object Full : JoinDecision
}

class GiveawayEngine(
    private val maxParticipants: Int,
    private val minimumParticipants: Int,
    private val winnerIndex: (Int) -> Int,
) {
    init {
        require(maxParticipants in 1..GiveawayRecord.MAX_PARTICIPANTS)
        require(minimumParticipants in 1..maxParticipants)
    }

    fun join(record: GiveawayRecord, participant: GiveawayParticipant): JoinDecision {
        record.validated(maxParticipants)
        participant.validated()
        if (record.status != GiveawayStatus.OPEN) return JoinDecision.NotOpen
        if (record.hostId == participant.playerId) return JoinDecision.HostExcluded
        if (record.participants.any { it.playerId == participant.playerId }) return JoinDecision.AlreadyJoined(record)
        if (record.participants.size >= maxParticipants) return JoinDecision.Full
        return JoinDecision.Joined(record.copy(participants = record.participants + participant))
    }

    fun open(record: GiveawayRecord): GiveawayRecord {
        require(record.status == GiveawayStatus.PREPARING)
        return record.copy(status = GiveawayStatus.OPEN)
    }

    fun beginDraw(record: GiveawayRecord, eligible: List<GiveawayParticipant>, nowMs: Long, drawingMillis: Long): GiveawayRecord {
        require(record.status == GiveawayStatus.OPEN)
        val unique = eligible.distinctBy { it.playerId }.filter { candidate -> record.participants.any { it.playerId == candidate.playerId } }
        return if (unique.size < minimumParticipants) {
            record.copy(
                status = GiveawayStatus.AWAITING_REFUND,
                terminalReason = "not_enough_participants",
                terminalAtMs = nowMs,
            )
        } else {
            record.copy(
                status = GiveawayStatus.DRAWING,
                drawingCandidates = unique,
                drawingEndsAtMs = Math.addExact(nowMs, drawingMillis),
            )
        }
    }

    fun selectWinner(record: GiveawayRecord): GiveawayRecord {
        require(record.status == GiveawayStatus.DRAWING)
        require(record.drawingCandidates.size >= minimumParticipants)
        val index = winnerIndex(record.drawingCandidates.size)
        require(index in record.drawingCandidates.indices) { "Winner selector returned an invalid index" }
        return record.copy(status = GiveawayStatus.AWAITING_DELIVERY, winner = record.drawingCandidates[index])
    }

    fun cancel(record: GiveawayRecord, nowMs: Long, reason: String): GiveawayRecord {
        require(record.isActive() && record.status != GiveawayStatus.AWAITING_DELIVERY)
        return record.copy(
            status = GiveawayStatus.AWAITING_REFUND,
            hostHandoffStartedAtMs = null,
            hostHandoffUntilMs = null,
            terminalAtMs = nowMs,
            terminalReason = reason.take(96),
        )
    }

    fun complete(record: GiveawayRecord, nowMs: Long): GiveawayRecord {
        require(record.status == GiveawayStatus.AWAITING_DELIVERY)
        return record.copy(status = GiveawayStatus.COMPLETED, terminalAtMs = nowMs, terminalReason = "delivered")
    }

    fun refunded(record: GiveawayRecord, nowMs: Long): GiveawayRecord {
        require(record.status == GiveawayStatus.AWAITING_REFUND)
        return record.copy(status = GiveawayStatus.CANCELLED, terminalAtMs = nowMs)
    }
}
