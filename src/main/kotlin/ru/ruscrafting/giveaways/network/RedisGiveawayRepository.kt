package ru.ruscrafting.giveaways.network

import com.google.gson.Gson
import ru.arc.redis.RedisOperations
import ru.arc.redis.network.RedisReplayPolicy
import ru.arc.redis.network.ValidatedRedisTopic
import ru.arc.redis.safety.BoundedJsonCodec
import ru.arc.redis.safety.JsonObjectContract
import ru.arc.redis.safety.JsonResourceBounds
import ru.arc.redis.safety.RedisHashConsumeResult
import ru.arc.redis.safety.RedisHashDecision
import ru.arc.redis.safety.RedisHashUpdateResult
import ru.arc.redis.safety.RedisHashUpdater
import ru.ruscrafting.giveaways.domain.GiveawayParticipant
import ru.ruscrafting.giveaways.domain.GiveawayRecord
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicReference

enum class GiveawayEventType { CREATED, UPDATED, JOINED, TERMINAL, DELETED }

data class GiveawayWireEvent(
    val protocolVersion: Int = 1,
    val type: GiveawayEventType,
    val giveawayId: String,
    val revision: Long,
    val participant: GiveawayParticipant? = null,
)

data class PendingJoin(
    val giveawayId: String,
    val expiresAtMs: Long,
)

sealed interface RepositoryUpdate {
    data class Changed(val before: GiveawayRecord, val after: GiveawayRecord) : RepositoryUpdate
    data class Rejected(val current: GiveawayRecord) : RepositoryUpdate
    data object Missing : RepositoryUpdate
    data object Contended : RepositoryUpdate
}

class RedisGiveawayRepository(
    private val redis: RedisOperations,
    private val gson: Gson = Gson(),
    private val maxParticipants: Int = GiveawayRecord.MAX_PARTICIPANTS,
) {
    private val recordCodec = BoundedJsonCodec(
        gson = gson,
        type = GiveawayRecord::class.java,
        rootContract = JsonObjectContract(
            allowedFields = RECORD_FIELDS,
            requiredFields = RECORD_REQUIRED_FIELDS,
        ),
        bounds = JsonResourceBounds(
            maxCharacters = MAX_RECORD_CHARS,
            maxDepth = 12,
            maxContainerEntries = GiveawayRecord.MAX_PARTICIPANTS,
            maxTotalNodes = 10_000,
            maxStringCharacters = ru.ruscrafting.giveaways.domain.ItemPayload.MAX_BASE64_CHARS,
        ),
        validate = { record -> record.validated(maxParticipants) },
    )
    private val pendingCodec = BoundedJsonCodec(
        gson = gson,
        type = PendingJoin::class.java,
        rootContract = JsonObjectContract(PENDING_FIELDS),
        bounds = JsonResourceBounds(512, maxContainerEntries = 8, maxTotalNodes = 16),
        validate = ::validatePending,
    )
    private val eventCodec = BoundedJsonCodec(
        gson = gson,
        type = GiveawayWireEvent::class.java,
        rootContract = JsonObjectContract(
            allowedFields = EVENT_FIELDS,
            requiredFields = EVENT_FIELDS - "participant",
        ),
        bounds = JsonResourceBounds(512, maxContainerEntries = 8, maxTotalNodes = 16),
        validate = ::validateEvent,
    )
    private val records = RedisHashUpdater(redis, RECORDS_KEY, recordCodec, MAX_CAS_ATTEMPTS)
    private val pending = RedisHashUpdater(redis, PENDING_KEY, pendingCodec, MAX_CAS_ATTEMPTS)
    private val eventTopic = AtomicReference<ValidatedRedisTopic<GiveawayWireEvent>?>(null)
    private val eventTopicLock = Any()

    fun openEvents(
        originAllowed: (String) -> Boolean,
        listener: (GiveawayWireEvent, String) -> Unit,
    ): AutoCloseable = synchronized(eventTopicLock) {
        check(eventTopic.get() == null) { "Giveaway event topic is already registered" }
        val topic = ValidatedRedisTopic.open(
            redis = redis,
            channel = EVENT_CHANNEL,
            codec = eventCodec,
            originAllowed = originAllowed,
            replay = RedisReplayPolicy(
                messageId = { event ->
                    "${event.giveawayId}:${event.revision}:${event.type.name}:${event.participant?.playerId.orEmpty()}"
                },
                 ttlMillis = EVENT_DEDUPLICATION_MS,
                 maxEntries = MAX_SEEN_EVENTS,
            ),
            onMessage = listener,
        )
        eventTopic.set(topic)
        AutoCloseable {
            synchronized(eventTopicLock) {
                if (eventTopic.compareAndSet(topic, null)) topic.close()
            }
         }
     }

    fun create(record: GiveawayRecord): CompletableFuture<Boolean> {
        val validated = record.validated(maxParticipants)
        return records.update(validated.id) { current ->
            if (current == null) RedisHashDecision.Write(validated) else RedisHashDecision.Reject
        }.thenApply { result ->
            val created = result is RedisHashUpdateResult.Changed && result.before == null
            if (created) publish(GiveawayEventType.CREATED, validated)
            created
        }
    }

    fun load(id: String): CompletableFuture<GiveawayRecord?> =
        redis.loadMapEntries(RECORDS_KEY, id).thenApply { values -> values.firstOrNull()?.let(recordCodec::decode) }

    fun loadAll(): CompletableFuture<List<GiveawayRecord>> =
        redis.loadMap(RECORDS_KEY).thenApply { values -> values.values.map(recordCodec::decode) }

    fun update(
        id: String,
        transition: (GiveawayRecord) -> GiveawayRecord?,
    ): CompletableFuture<RepositoryUpdate> = records.update(id) { before ->
        if (before == null) return@update RedisHashDecision.Reject
        val proposed = transition(before) ?: return@update RedisHashDecision.Reject
        require(proposed.id == before.id && proposed.hostId == before.hostId) { "Giveaway identity cannot change" }
        RedisHashDecision.Write(proposed.copy(revision = before.revision + 1).validated(maxParticipants))
    }.thenApply { result ->
        when (result) {
            is RedisHashUpdateResult.Changed -> {
                val before = requireNotNull(result.before)
                val after = requireNotNull(result.after)
                publish(if (after.isActive()) GiveawayEventType.UPDATED else GiveawayEventType.TERMINAL, after)
                val knownParticipants = before.participants.mapTo(mutableSetOf(), GiveawayParticipant::playerId)
                after.participants.filter { knownParticipants.add(it.playerId) }
                    .forEach { participant -> publish(GiveawayEventType.JOINED, after, participant) }
                RepositoryUpdate.Changed(before, after)
            }
            is RedisHashUpdateResult.Rejected -> result.current?.let(RepositoryUpdate::Rejected) ?: RepositoryUpdate.Missing
            is RedisHashUpdateResult.Unchanged -> RepositoryUpdate.Rejected(result.current)
            is RedisHashUpdateResult.Contended -> RepositoryUpdate.Contended
        }
    }

    fun delete(record: GiveawayRecord): CompletableFuture<Boolean> =
        records.update(record.id) { current ->
            if (current == record) RedisHashDecision.Delete else RedisHashDecision.Reject
        }.thenApply { result ->
            val deleted = result is RedisHashUpdateResult.Changed && result.after == null
            if (deleted) publish(GiveawayEventType.DELETED, record)
            deleted
        }

    fun claimHost(hostId: String, giveawayId: String): CompletableFuture<Boolean> =
        redis.compareAndSetMapEntry(HOSTS_KEY, canonicalUuid(hostId, "host"), null, canonicalUuid(giveawayId, "giveaway"))

    fun hostGiveawayId(hostId: String): CompletableFuture<String?> =
        redis.loadMapEntries(HOSTS_KEY, canonicalUuid(hostId, "host")).thenApply { values ->
            values.firstOrNull()?.let { canonicalUuid(it, "claimed giveaway") }
        }

    fun replaceHostClaim(hostId: String, expectedGiveawayId: String, giveawayId: String): CompletableFuture<Boolean> =
        redis.compareAndSetMapEntry(
            HOSTS_KEY,
            canonicalUuid(hostId, "host"),
            canonicalUuid(expectedGiveawayId, "expected giveaway"),
            canonicalUuid(giveawayId, "giveaway"),
        )

    fun releaseHost(hostId: String, giveawayId: String): CompletableFuture<Boolean> =
        redis.compareAndSetMapEntry(HOSTS_KEY, canonicalUuid(hostId, "host"), canonicalUuid(giveawayId, "giveaway"), null)

    fun savePendingJoin(playerId: String, pending: PendingJoin): CompletableFuture<*> =
        redis.saveMapEntries(PENDING_KEY, canonicalUuid(playerId, "player"), pendingCodec.encode(pending))

    fun consumePendingJoin(playerId: String, nowMs: Long): CompletableFuture<PendingJoin?> =
        pending.consume(canonicalUuid(playerId, "player")).thenApply { result ->
            when (result) {
                is RedisHashConsumeResult.Consumed -> result.value.takeIf { it.expiresAtMs >= nowMs }
                is RedisHashConsumeResult.Rejected, is RedisHashConsumeResult.Contended -> null
            }
        }

    private fun publish(
        type: GiveawayEventType,
        record: GiveawayRecord,
        participant: GiveawayParticipant? = null,
    ) {
        val event = GiveawayWireEvent(
            type = type,
            giveawayId = record.id,
            revision = record.revision,
            participant = participant,
        )
        eventTopic.get()?.publish(event) ?: redis.publish(EVENT_CHANNEL, eventCodec.encode(event))
    }

    private fun validatePending(value: PendingJoin) {
        canonicalUuid(value.giveawayId, "pending giveaway")
        require(value.expiresAtMs > 0L) { "Pending join expiry is invalid" }
    }

    private fun validateEvent(value: GiveawayWireEvent) {
        require(value.protocolVersion == 1) { "Unsupported giveaway event protocol" }
        canonicalUuid(value.giveawayId, "event giveaway")
        require(value.revision >= 0L) { "Giveaway event revision is invalid" }
        if (value.type == GiveawayEventType.JOINED) requireNotNull(value.participant).validated()
        else require(value.participant == null) { "Only joined events may contain a participant" }
    }

    private fun canonicalUuid(value: String, label: String): String = value.also {
        require(runCatching { UUID.fromString(it).toString() == it }.getOrDefault(false)) { "Invalid $label id" }
    }

    companion object {
        const val RECORDS_KEY = "arc:giveaways:v1:records"
        const val HOSTS_KEY = "arc:giveaways:v1:hosts"
        const val PENDING_KEY = "arc:giveaways:v1:pending"
        const val EVENT_CHANNEL = "arc:giveaways:v1:events"
        private const val MAX_RECORD_CHARS = 1_600_000
        private const val MAX_CAS_ATTEMPTS = 12
        private const val EVENT_DEDUPLICATION_MS = 10L * 60L * 1_000L
        private const val MAX_SEEN_EVENTS = 10_000
        private val PENDING_FIELDS = setOf("giveawayId", "expiresAtMs")
        private val EVENT_FIELDS = setOf("protocolVersion", "type", "giveawayId", "revision", "participant")
        private val RECORD_FIELDS = setOf(
            "protocolVersion", "id", "revision", "status", "hostId", "hostName", "serverId", "worldName",
            "anchorX", "anchorY", "anchorZ", "radius", "item", "createdAtMs", "opensAtMs", "drawAtMs",
            "drawingEndsAtMs", "terminalAtMs", "participants", "drawingCandidates", "winner", "terminalReason",
        )
        private val RECORD_REQUIRED_FIELDS = RECORD_FIELDS - setOf(
            "drawingEndsAtMs", "terminalAtMs", "winner", "terminalReason",
        )
    }
}
