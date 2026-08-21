package ru.ruscrafting.giveaways.network

import com.google.gson.Gson
import com.google.gson.JsonParseException
import ru.arc.redis.ChannelListener
import ru.arc.redis.RedisOperations
import ru.ruscrafting.giveaways.domain.GiveawayRecord
import java.util.concurrent.CompletableFuture

enum class GiveawayEventType { CREATED, UPDATED, TERMINAL, DELETED }

data class GiveawayWireEvent(
    val protocolVersion: Int = 1,
    val type: GiveawayEventType,
    val giveawayId: String,
    val revision: Long,
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
    fun registerEvents(listener: (GiveawayWireEvent, String) -> Unit): ChannelListener {
        val channelListener = ChannelListener { _, message, origin ->
            val event = runCatching { gson.fromJson(message, GiveawayWireEvent::class.java) }.getOrNull()
            if (event != null && event.protocolVersion == 1 && event.giveawayId.length <= 64) listener(event, origin)
        }
        redis.registerChannelUnique(EVENT_CHANNEL, channelListener)
        return channelListener
    }

    fun unregisterEvents(listener: ChannelListener) = redis.unregisterChannel(EVENT_CHANNEL, listener)

    fun create(record: GiveawayRecord): CompletableFuture<Boolean> {
        val validated = record.validated(maxParticipants)
        val encoded = encode(validated)
        return redis.compareAndSetMapEntry(RECORDS_KEY, validated.id, null, encoded).thenApply { created ->
            if (created) publish(GiveawayEventType.CREATED, validated)
            created
        }
    }

    fun load(id: String): CompletableFuture<GiveawayRecord?> =
        redis.loadMapEntries(RECORDS_KEY, id).thenApply { values -> values.firstOrNull()?.let(::decode) }

    fun loadAll(): CompletableFuture<List<GiveawayRecord>> =
        redis.loadMap(RECORDS_KEY).thenApply { values -> values.values.mapNotNull { runCatching { decode(it) }.getOrNull() } }

    fun update(
        id: String,
        transition: (GiveawayRecord) -> GiveawayRecord?,
    ): CompletableFuture<RepositoryUpdate> = updateAttempt(id, transition, 0)

    fun delete(record: GiveawayRecord): CompletableFuture<Boolean> =
        redis.compareAndSetMapEntry(RECORDS_KEY, record.id, encode(record), null).thenApply { deleted ->
            if (deleted) publish(GiveawayEventType.DELETED, record)
            deleted
        }

    fun claimHost(hostId: String, giveawayId: String): CompletableFuture<Boolean> =
        redis.compareAndSetMapEntry(HOSTS_KEY, hostId, null, giveawayId)

    fun releaseHost(hostId: String, giveawayId: String): CompletableFuture<Boolean> =
        redis.compareAndSetMapEntry(HOSTS_KEY, hostId, giveawayId, null)

    fun savePendingJoin(playerId: String, pending: PendingJoin): CompletableFuture<*> =
        redis.saveMapEntries(PENDING_KEY, playerId, gson.toJson(pending))

    fun consumePendingJoin(playerId: String, nowMs: Long): CompletableFuture<PendingJoin?> =
        redis.loadMapEntries(PENDING_KEY, playerId).thenCompose { values ->
            val raw = values.firstOrNull() ?: return@thenCompose CompletableFuture.completedFuture(null)
            val pending = runCatching { gson.fromJson(raw, PendingJoin::class.java) }.getOrNull()
            redis.compareAndSetMapEntry(PENDING_KEY, playerId, raw, null).thenApply {
                pending?.takeIf { it.expiresAtMs >= nowMs }
            }
        }

    private fun updateAttempt(
        id: String,
        transition: (GiveawayRecord) -> GiveawayRecord?,
        attempt: Int,
    ): CompletableFuture<RepositoryUpdate> {
        if (attempt >= MAX_CAS_ATTEMPTS) return CompletableFuture.completedFuture(RepositoryUpdate.Contended)
        return redis.loadMapEntries(RECORDS_KEY, id).thenCompose { values ->
            val beforeRaw = values.firstOrNull() ?: return@thenCompose CompletableFuture.completedFuture(RepositoryUpdate.Missing)
            val before = decode(beforeRaw)
            val proposed = transition(before) ?: return@thenCompose CompletableFuture.completedFuture(RepositoryUpdate.Rejected(before))
            require(proposed.id == before.id && proposed.hostId == before.hostId) { "Giveaway identity cannot change" }
            val after = proposed.copy(revision = before.revision + 1).validated(maxParticipants)
            val afterRaw = encode(after)
            redis.compareAndSetMapEntry(RECORDS_KEY, id, beforeRaw, afterRaw).thenCompose { changed ->
                if (changed) {
                    publish(if (after.isActive()) GiveawayEventType.UPDATED else GiveawayEventType.TERMINAL, after)
                    CompletableFuture.completedFuture(RepositoryUpdate.Changed(before, after))
                } else {
                    updateAttempt(id, transition, attempt + 1)
                }
            }
        }
    }

    private fun publish(type: GiveawayEventType, record: GiveawayRecord) {
        redis.publish(EVENT_CHANNEL, gson.toJson(GiveawayWireEvent(type = type, giveawayId = record.id, revision = record.revision)))
    }

    private fun encode(record: GiveawayRecord): String = gson.toJson(record).also {
        require(it.length <= MAX_RECORD_CHARS) { "Giveaway record is too large" }
    }

    private fun decode(raw: String): GiveawayRecord {
        if (raw.length > MAX_RECORD_CHARS) throw JsonParseException("Giveaway record is too large")
        return gson.fromJson(raw, GiveawayRecord::class.java).validated(maxParticipants)
    }

    companion object {
        const val RECORDS_KEY = "arc:giveaways:v1:records"
        const val HOSTS_KEY = "arc:giveaways:v1:hosts"
        const val PENDING_KEY = "arc:giveaways:v1:pending"
        const val EVENT_CHANNEL = "arc:giveaways:v1:events"
        private const val MAX_RECORD_CHARS = 1_600_000
        private const val MAX_CAS_ATTEMPTS = 12
    }
}
