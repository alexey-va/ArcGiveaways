package ru.ruscrafting.giveaways.network

import com.google.gson.Gson
import ru.arc.network.BackendServerId
import ru.arc.redis.RedisOperations
import ru.arc.redis.network.RedisPresenceDirectory
import ru.arc.redis.network.RedisPresenceRefresh
import ru.arc.redis.safety.BoundedJsonCodec
import ru.arc.redis.safety.JsonObjectContract
import ru.arc.redis.safety.JsonResourceBounds
import java.util.concurrent.CompletableFuture

data class GiveawayBackendPresence(
    val protocolVersion: Int = PROTOCOL_VERSION,
    val serverId: String,
    val heartbeatAtMs: Long,
) {
    fun validated(): GiveawayBackendPresence {
        require(protocolVersion == PROTOCOL_VERSION) { "Unsupported giveaway presence protocol" }
        BackendServerId.of(serverId)
        require(heartbeatAtMs > 0L) { "Invalid giveaway presence timestamp" }
        return this
    }

    companion object {
        const val PROTOCOL_VERSION = 1
    }
}

enum class BackendAvailability { LIVE, UNKNOWN, UNAVAILABLE }

/**
 * Strict leased view of ArcGiveaways backends. Absence is considered authoritative only after
 * this instance has refreshed successfully for a complete lease window.
 */
class GiveawayBackendDirectory(
    redis: RedisOperations,
    private val localServerId: String,
    allowedOrigins: Set<String>,
    private val leaseMillis: Long,
    private val clockMs: () -> Long = System::currentTimeMillis,
    gson: Gson = Gson(),
) : AutoCloseable {
    private val observationLock = Any()
    private val allowedServerIds = allowedOrigins.toSet()
    private val absentSinceMs = mutableMapOf<String, Long>()
    private val lastObservedHeartbeatMs = mutableMapOf<String, Long>()
    private var lastSuccessfulRefreshAtMs = 0L
    private val codec = BoundedJsonCodec(
        gson = gson,
        type = GiveawayBackendPresence::class.java,
        rootContract = JsonObjectContract(PRESENCE_FIELDS),
        bounds = JsonResourceBounds(512, maxContainerEntries = 8, maxTotalNodes = 16, maxStringCharacters = 128),
        validate = GiveawayBackendPresence::validated,
    )
    private val presence = RedisPresenceDirectory(
        redis = redis,
        hashKey = PRESENCE_KEY,
        codec = codec,
        entryId = GiveawayBackendPresence::serverId,
        origin = GiveawayBackendPresence::serverId,
        observedAtMillis = GiveawayBackendPresence::heartbeatAtMs,
        originAllowed = allowedOrigins::contains,
        entryAllowed = { it.heartbeatAtMs <= clockMs() + MAX_FUTURE_SKEW_MS },
        leaseMillis = leaseMillis,
        maxEntries = MAX_BACKENDS,
        clockMillis = clockMs,
    )

    init {
        BackendServerId.of(localServerId)
        require(localServerId in allowedOrigins) { "Giveaway presence origins must include the local server" }
        require(leaseMillis in 5_000L..120_000L) { "Giveaway presence lease must be between 5 and 120 seconds" }
    }

    fun heartbeat(): CompletableFuture<RedisPresenceRefresh<GiveawayBackendPresence>> {
        val heartbeat = GiveawayBackendPresence(serverId = localServerId, heartbeatAtMs = clockMs()).validated()
        return presence.publish(heartbeat).thenCompose { refresh() }.whenComplete { _, failure ->
            if (failure != null) invalidateAbsenceProof()
        }
    }

    fun refresh(): CompletableFuture<RedisPresenceRefresh<GiveawayBackendPresence>> =
        presence.refresh().thenApply { result ->
            recordSuccessfulRefresh(result)
            result
        }.whenComplete { _, failure ->
            if (failure != null) invalidateAbsenceProof()
        }

    /** Cached presence is diagnostic only and can never prove destructive unavailability. */
    fun availability(serverId: String): BackendAvailability {
        BackendServerId.of(serverId)
        return if (serverId == localServerId || presence.snapshot().any { it.serverId == serverId }) {
            BackendAvailability.LIVE
        } else {
            BackendAvailability.UNKNOWN
        }
    }

    /**
     * Performs the fresh Redis observation required before cancelling another backend's giveaway.
     * A failed probe invalidates all accumulated absence evidence and completes exceptionally.
     */
    fun probe(serverId: String): CompletableFuture<BackendAvailability> {
        BackendServerId.of(serverId)
        if (serverId == localServerId) return CompletableFuture.completedFuture(BackendAvailability.LIVE)
        return refresh().thenApply { result ->
            if (result.values.any { it.serverId == serverId }) {
                return@thenApply BackendAvailability.LIVE
            }
            synchronized(observationLock) {
                val absentSince = absentSinceMs[serverId]
                if (absentSince != null && clockMs() - absentSince >= leaseMillis) {
                    BackendAvailability.UNAVAILABLE
                } else {
                    BackendAvailability.UNKNOWN
                }
            }
        }
    }

    private fun recordSuccessfulRefresh(result: RedisPresenceRefresh<GiveawayBackendPresence>) {
        val now = clockMs()
        val live = result.values.mapTo(mutableSetOf(), GiveawayBackendPresence::serverId)
        synchronized(observationLock) {
            if (lastSuccessfulRefreshAtMs == 0L || now - lastSuccessfulRefreshAtMs > leaseMillis) {
                absentSinceMs.clear()
                lastObservedHeartbeatMs.clear()
            }
            lastSuccessfulRefreshAtMs = now
            result.values.forEach { value -> lastObservedHeartbeatMs[value.serverId] = value.heartbeatAtMs }
            allowedServerIds.asSequence()
                .filter { it != localServerId }
                .forEach { serverId ->
                    if (serverId in live) absentSinceMs.remove(serverId)
                    else absentSinceMs.putIfAbsent(serverId, lastObservedHeartbeatMs[serverId] ?: now)
                }
        }
    }

    private fun invalidateAbsenceProof() = synchronized(observationLock) {
        lastSuccessfulRefreshAtMs = 0L
        absentSinceMs.clear()
        lastObservedHeartbeatMs.clear()
    }

    fun activeLeaseCount(): Int = presence.activeLeaseCount()

    override fun close() = presence.close()

    companion object {
        const val PRESENCE_KEY = "arc:giveaways:v1:backends"
        private const val MAX_BACKENDS = 64
        private const val MAX_FUTURE_SKEW_MS = 5_000L
        private val PRESENCE_FIELDS = setOf("protocolVersion", "serverId", "heartbeatAtMs")
    }
}
