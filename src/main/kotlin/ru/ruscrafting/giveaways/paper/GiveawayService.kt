package ru.ruscrafting.giveaways.paper

import net.kyori.adventure.bossbar.BossBar
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.event.ClickEvent
import net.kyori.adventure.text.event.HoverEvent
import net.kyori.adventure.title.Title
import org.bukkit.Bukkit
import org.bukkit.Color
import org.bukkit.FireworkEffect
import org.bukkit.Location
import org.bukkit.entity.Firework
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.plugin.java.JavaPlugin
import ru.arc.core.LifecycleTaskScope
import ru.arc.core.whenCompleteSync
import ru.arc.network.BackendServerId
import ru.arc.observability.StructuredDebugLine
import ru.arc.paper.network.BackendTransfer
import ru.arc.paper.network.BackendTransferResult
import ru.arc.persistence.DurableAcknowledgementOutcome
import ru.arc.persistence.DurableRecoveryCompletion
import ru.arc.persistence.DurableRecoveryWorkflow
import ru.ruscrafting.giveaways.config.GiveawayConfig
import ru.ruscrafting.giveaways.config.GiveawayLocale
import ru.ruscrafting.giveaways.config.MessageKey
import ru.ruscrafting.giveaways.domain.GiveawayEngine
import ru.ruscrafting.giveaways.domain.GiveawayParticipant
import ru.ruscrafting.giveaways.domain.GiveawayRecord
import ru.ruscrafting.giveaways.domain.GiveawayStatus
import ru.ruscrafting.giveaways.domain.ItemPayload
import ru.ruscrafting.giveaways.network.GiveawayEventType
import ru.ruscrafting.giveaways.network.GiveawayBackendDirectory
import ru.ruscrafting.giveaways.network.GiveawayHostLeaseCoordinator
import ru.ruscrafting.giveaways.network.HostClaimOutcome
import ru.ruscrafting.giveaways.network.PendingJoin
import ru.ruscrafting.giveaways.network.RedisGiveawayRepository
import ru.ruscrafting.giveaways.network.RepositoryUpdate
import java.security.SecureRandom
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.ceil

class GiveawayService(
    private val plugin: JavaPlugin,
    private var settings: GiveawayConfig,
    private val locale: GiveawayLocale,
    private val repository: RedisGiveawayRepository,
    private val journalStore: InventoryJournalRepository,
    private val itemNames: RussianItemNames,
    private val transfer: BackendTransfer,
    private val backendDirectory: GiveawayBackendDirectory,
    private val clockMs: () -> Long = System::currentTimeMillis,
    private val presentation: GiveawayPresentationPort = NativeGiveawayPresentationPort,
    private val travel: GiveawayTravelPort = NativeGiveawayTravelPort,
    private val playerData: GiveawayPlayerDataPersistence = NativeGiveawayPlayerDataPersistence,
) : AutoCloseable {
    private var engine = newEngine(settings)
    private val lifecycleTasks = LifecycleTaskScope()
    private val cache = ConcurrentHashMap<String, GiveawayRecord>()
    private val inventoryLocks = ConcurrentHashMap.newKeySet<UUID>()
    private val startsInFlight = ConcurrentHashMap.newKeySet<String>()
    private val cooldowns = ConcurrentHashMap<UUID, Long>()
    private val journals = ConcurrentHashMap<String, InventoryJournalRecord>()
    private val cacheMutationLock = Any()
    private val activeLeaseGauge = ActiveLeaseGauge()
    private val bossBars = mutableMapOf<String, BossBar>()
    private val bossViewers = mutableMapOf<String, MutableSet<UUID>>()
    private val announced = mutableSetOf<String>()
    private val winnerAnnounced = mutableSetOf<String>()
    private val countdownSecond = mutableMapOf<String, Int>()
    private val claimNoticeAt = mutableMapOf<UUID, Long>()
    private val pvpNoticeAt = mutableMapOf<UUID, Long>()
    private val recoveryIncidents = InventoryRecoveryIncidentTracker()
    private val presenceFailureLogged = AtomicBoolean()
    private val secureRandom = SecureRandom()
    private val qaSummary = StructuredDebugLine("ARCGIVEAWAYS_QA")
    private val qaRecord = StructuredDebugLine("ARCGIVEAWAYS_QA_RECORD")
    private val qaJournal = StructuredDebugLine("ARCGIVEAWAYS_QA_JOURNAL")
    private val eventBus = repository.openEvents(
        originAllowed = settings.allowedOrigins::contains,
    ) { event, _ -> refresh(event) }
    private val hostLeases = GiveawayHostLeaseCoordinator(
        repository = repository,
        availability = backendDirectory::probe,
        cancelUnavailable = { record -> engine.cancel(record, clockMs(), "owner_backend_unavailable") },
    )
    private val inventoryRecovery = DurableRecoveryWorkflow<InventoryJournalRecord, InventoryJournalRecord>(
        commit = { candidate -> completed { journalStore.write(candidate) } },
        sameContent = { candidate, committed -> candidate == committed },
        acknowledge = { committed, _ -> completed { journalStore.acknowledgeExactly(committed) } },
    )

    fun start() {
        journalStore.loadAll().forEach { journals[journalKey(it.giveawayId, it.kind)] = it }
        reconcile()
        heartbeatPresence()
        lifecycleTasks.runTimer(20, 20, ::tick)
        lifecycleTasks.runTimer(40, 100, ::reconcile)
        lifecycleTasks.runTimer(
            settings.presenceHeartbeatSeconds * TICKS_PER_SECOND,
            settings.presenceHeartbeatSeconds * TICKS_PER_SECOND,
            ::heartbeatPresence,
        )
    }

    fun reload(updated: GiveawayConfig) {
        settings = updated.validated()
        engine = newEngine(settings)
    }

    override fun close() {
        lifecycleTasks.close()
        eventBus.close()
        backendDirectory.close()
        bossBars.keys.toList().forEach(::removeBossBar)
        inventoryLocks.clear()
        startsInFlight.clear()
        recoveryIncidents.clearAll()
    }

    fun isInventoryLocked(playerId: UUID): Boolean = playerId in inventoryLocks

    fun shouldCancelPvp(attacker: Player, victim: Player): Boolean {
        val protected = GiveawayPvpProtection.isProtected(cache.values, settings.serverId, attacker.uniqueId) ||
            GiveawayPvpProtection.isProtected(cache.values, settings.serverId, victim.uniqueId)
        if (protected) {
            val now = clockMs()
            if (now - (pvpNoticeAt[attacker.uniqueId] ?: 0L) >= 2_000L) {
                pvpNoticeAt[attacker.uniqueId] = now
                attacker.sendActionBar(locale.render(MessageKey.PVP_PROTECTED, attacker))
            }
        }
        return protected
    }

    fun activeRecords(): List<GiveawayRecord> =
        cache.values.filter { it.status == GiveawayStatus.OPEN || it.status == GiveawayStatus.DRAWING }
            .sortedBy { it.drawAtMs }

    /** Constant-time in-memory gauge safe for runtime health sampling. */
    fun recoveryBacklog(): Int = journals.size

    /** Constant-time count of locally observed active host claims. */
    fun activeLeaseCount(): Int = activeLeaseGauge.count()

    fun backendLeaseCount(): Int = backendDirectory.activeLeaseCount()

    fun participantCount(): Int = cache.values.sumOf { it.participants.size }

    fun qaReport(idOrPrefix: String?): List<String> {
        val allIds = (cache.keys + journals.values.map { it.giveawayId }).distinct().sorted()
        val selectedId = idOrPrefix?.let { value ->
            val normalized = value.lowercase()
            allIds.filter { it == normalized || it.startsWith(normalized) }.singleOrNull()
        }
        if (idOrPrefix != null && selectedId == null) {
            return listOf(qaSummary.line("status" to "not_found", "query" to idOrPrefix))
        }
        val selectedRecords = if (selectedId == null) cache.values.sortedBy { it.createdAtMs } else listOfNotNull(cache[selectedId])
        val selectedJournals = journals.values
            .filter { selectedId == null || it.giveawayId == selectedId }
            .sortedWith(compareBy(InventoryJournalRecord::giveawayId, { it.kind.name }))
        val lines = mutableListOf(
            qaSummary.line(
                "status" to "ok",
                "server" to settings.serverId,
                "records" to selectedRecords.size,
                "active" to selectedRecords.count(GiveawayRecord::isActive),
                "journals" to selectedJournals.size,
                "backend_leases" to backendDirectory.activeLeaseCount(),
            ),
        )
        selectedRecords.forEach { record ->
            lines += qaRecord.line(
                "id" to record.displayId(),
                "status" to record.status.name,
                "owner" to record.serverId,
                "participants" to record.participants.size,
                "eligible" to if (record.serverId == settings.serverId) eligibleParticipants(record).size else -1,
                "winner" to (record.winner != null),
                "host_reserved" to record.reservesHost(),
            )
        }
        selectedJournals.forEach { journal ->
            val player = runCatching { Bukkit.getPlayer(UUID.fromString(journal.playerId)) }.getOrNull()?.takeIf(Player::isOnline)
            val state = player?.let { InventoryPlan.from(journal.changes).state(it).name } ?: "OFFLINE"
            lines += qaJournal.line(
                "id" to journal.giveawayId.take(8),
                "kind" to journal.kind.name,
                "status" to journal.status.name,
                "player" to if (player == null) "OFFLINE" else "ONLINE",
                "state" to state,
                "changes" to journal.changes.size,
            )
        }
        return lines
    }

    fun sendStatus(player: Player) {
        val records = activeRecords()
        if (records.isEmpty()) {
            player.sendMessage(locale.render(MessageKey.STATUS_EMPTY, player))
            return
        }
        val now = clockMs()
        records.forEach { record ->
            player.sendMessage(locale.render(MessageKey.STATUS_ENTRY, player, values(
                "host", record.hostName,
                "item", itemComponent(record),
                "seconds", ceil((record.drawAtMs - now).coerceAtLeast(0L) / 1000.0).toInt(),
                "count", record.participants.size,
            )))
        }
    }

    fun startGiveaway(player: Player, requestedAmount: Int?) {
        if (!inventoryLocks.add(player.uniqueId)) {
            player.sendMessage(locale.render(MessageKey.BUSY, player))
            return
        }
        val now = clockMs()
        val cooldown = cooldowns[player.uniqueId] ?: 0L
        if (cooldown > now) {
            inventoryLocks.remove(player.uniqueId)
            player.sendMessage(locale.render(MessageKey.COOLDOWN, player, values("seconds", ceil((cooldown - now) / 1000.0).toInt())))
            return
        }
        val held = player.inventory.itemInMainHand.takeUnless { it.type.isAir }
        if (held == null) {
            inventoryLocks.remove(player.uniqueId)
            player.sendMessage(locale.render(MessageKey.EMPTY_HAND, player))
            return
        }
        val amount = requestedAmount ?: held.amount
        if (amount !in 1..minOf(held.amount, settings.maximumItemAmount)) {
            inventoryLocks.remove(player.uniqueId)
            player.sendMessage(locale.render(MessageKey.BAD_AMOUNT, player, values("maximum", minOf(held.amount, settings.maximumItemAmount))))
            return
        }
        val plan = InventoryPlan.removal(player, player.inventory.heldItemSlot, amount)
        if (plan == null) {
            inventoryLocks.remove(player.uniqueId)
            player.sendMessage(locale.render(MessageKey.START_FAILED, player))
            return
        }
        val giveawayItem = held.clone().also { it.amount = amount }
        val payload = ItemPayload.capture(giveawayItem.type.key.toString(), amount, giveawayItem.serializeAsBytes())
        val id = UUID.randomUUID().toString()
        val journal = InventoryJournalRecord(
            giveawayId = id,
            playerId = player.uniqueId.toString(),
            kind = JournalKind.ESCROW,
            status = JournalStatus.PREPARED,
            item = payload,
            changes = plan.changes,
            createdAtMs = now,
        )
        runCatching { journalStore.write(journal) }.onFailure {
            inventoryLocks.remove(player.uniqueId)
            plugin.logger.severe("Could not persist giveaway escrow journal $id: ${it.message}")
            player.sendMessage(locale.render(MessageKey.START_FAILED, player))
            return
        }
        journals[journalKey(id, JournalKind.ESCROW)] = journal
        startsInFlight += id
        val location = player.location
        val record = GiveawayRecord(
            id = id,
            revision = 0,
            status = GiveawayStatus.PREPARING,
            hostId = player.uniqueId.toString(),
            hostName = player.name,
            serverId = settings.serverId,
            worldName = location.world.name,
            anchorX = location.x,
            anchorY = location.y,
            anchorZ = location.z,
            radius = settings.radius,
            item = payload,
            createdAtMs = now,
            opensAtMs = now,
            drawAtMs = now + settings.openSeconds * 1000L,
        ).validated(settings.maximumParticipants)
        player.sendMessage(locale.render(MessageKey.STARTING, player))

        hostLeases.claim(record.hostId, record.id).thenCompose { outcome ->
            if (outcome != HostClaimOutcome.Claimed) CompletableFuture.completedFuture(false)
            else repository.create(record)
        }.onMain(
            success = { created ->
                if (!created) {
                    startsInFlight.remove(record.id)
                    cleanupRejectedStart(record, player)
                    player.sendMessage(locale.render(MessageKey.BUSY, player))
                    return@onMain
                }
                continueConfirmedStart(record, journal, plan, player, now)
            },
            failure = { failure ->
                startsInFlight.remove(record.id)
                inventoryLocks.remove(player.uniqueId)
                plugin.logger.warning("Could not create giveaway $id: ${failure.message}")
                player.sendMessage(locale.render(MessageKey.REDIS_UNAVAILABLE, player))
                reconcileUnknownStart(record.id)
            },
        )
    }

    private fun continueConfirmedStart(
        record: GiveawayRecord,
        journal: InventoryJournalRecord,
        plan: InventoryPlan,
        player: Player,
        startedAtMs: Long,
    ) {
        if (!player.isOnline || plan.state(player) != PlanState.BEFORE) {
            cancelUnmutatedStart(record, player)
            return
        }
        val applied = runCatching { persistThenApply(journal, plan, player) }
            .onFailure { failure ->
                startsInFlight.remove(record.id)
                inventoryLocks.remove(player.uniqueId)
                plugin.logger.severe("Could not persist applied escrow for ${record.id}: ${failure.message}")
                player.sendMessage(locale.render(MessageKey.GENERIC_ERROR, player))
                reconcileUnknownStart(record.id)
            }
            .getOrNull() ?: return
        startsInFlight.remove(record.id)
        journals[journalKey(record.id, JournalKind.ESCROW)] = applied
        repository.update(record.id) { current ->
            if (current.status == GiveawayStatus.PREPARING) engine.open(current) else null
        }.onMain(
            success = { result ->
                inventoryLocks.remove(player.uniqueId)
                val opened = when (result) {
                    is RepositoryUpdate.Changed -> result.after
                    is RepositoryUpdate.Rejected -> result.current
                    else -> null
                }?.takeIf { it.status == GiveawayStatus.OPEN }
                if (opened != null) {
                    if (settings.hostCooldownSeconds > 0) {
                        cooldowns[player.uniqueId] = startedAtMs + settings.hostCooldownSeconds * 1_000L
                    } else {
                        cooldowns.remove(player.uniqueId)
                    }
                    acceptRecord(opened)
                    player.sendMessage(locale.render(MessageKey.STARTED, player, values("id", opened.displayId())))
                } else {
                    player.sendMessage(locale.render(MessageKey.GENERIC_ERROR, player))
                    reconcileUnknownStart(record.id)
                }
            },
            failure = { failure ->
                inventoryLocks.remove(player.uniqueId)
                plugin.logger.severe("Giveaway ${record.id} remained PREPARING after escrow: ${failure.message}")
                player.sendMessage(locale.render(MessageKey.GENERIC_ERROR, player))
                reconcileUnknownStart(record.id)
            },
        )
    }

    private fun cancelUnmutatedStart(record: GiveawayRecord, player: Player) {
        startsInFlight.remove(record.id)
        repository.update(record.id) { current ->
            if (current.status == GiveawayStatus.PREPARING) current.copy(
                status = GiveawayStatus.CANCELLED,
                terminalAtMs = clockMs(),
                terminalReason = "escrow_not_removed",
            ) else null
        }.onMain(
            success = { result ->
                inventoryLocks.remove(player.uniqueId)
                val current = when (result) {
                    is RepositoryUpdate.Changed -> result.after
                    is RepositoryUpdate.Rejected -> result.current
                    else -> null
                }
                if (current != null) acceptRecord(current)
                player.sendMessage(locale.render(MessageKey.START_FAILED, player))
            },
            failure = { failure ->
                inventoryLocks.remove(player.uniqueId)
                plugin.logger.warning("Could not cancel unmutated giveaway ${record.id}: ${failure.message}")
                player.sendMessage(locale.render(MessageKey.REDIS_UNAVAILABLE, player))
                reconcileUnknownStart(record.id)
            },
        )
    }

    fun join(player: Player, idOrPrefix: String) {
        val id = resolveId(idOrPrefix)
        if (id == null) {
            player.sendMessage(locale.render(MessageKey.NOT_FOUND, player))
            return
        }
        repository.load(id).onMain(
            success = { record ->
                if (record == null || record.status != GiveawayStatus.OPEN) {
                    player.sendMessage(locale.render(if (record == null) MessageKey.NOT_FOUND else MessageKey.NOT_OPEN, player))
                    return@onMain
                }
                if (record.serverId != settings.serverId) {
                    transferForJoin(player, record)
                } else {
                    teleportAndJoin(player, record)
                }
            },
            failure = { player.sendMessage(locale.render(MessageKey.REDIS_UNAVAILABLE, player)) },
        )
    }

    fun onPlayerJoin(player: Player) {
        lifecycleTasks.runLater(40) {
            repository.consumePendingJoin(player.uniqueId.toString(), clockMs()).onMain(
                success = { pending -> if (pending != null) joinTransferred(player, pending.giveawayId) },
                failure = { plugin.logger.warning("Could not consume pending giveaway join for ${player.uniqueId}") },
            )
            recoverFor(player)
        }
    }

    fun onPlayerQuit(player: Player) {
        pvpNoticeAt.remove(player.uniqueId)
        claimNoticeAt.remove(player.uniqueId)
        cache.values.filter {
            it.serverId == settings.serverId && it.hostId == player.uniqueId.toString() &&
                (it.status == GiveawayStatus.OPEN || it.status == GiveawayStatus.DRAWING)
        }.forEach { record ->
            repository.update(record.id) { current ->
                if (current.status == GiveawayStatus.OPEN || current.status == GiveawayStatus.DRAWING) {
                    engine.cancel(current, clockMs(), "host_disconnected")
                } else null
            }
        }
    }

    fun cancel(player: Player, idOrPrefix: String?) {
        val record = if (idOrPrefix == null) cache.values.firstOrNull { it.hostId == player.uniqueId.toString() && it.isActive() }
        else resolveId(idOrPrefix)?.let(cache::get)
        if (record == null) {
            player.sendMessage(locale.render(MessageKey.NOT_FOUND, player))
            return
        }
        if (record.hostId != player.uniqueId.toString() && !player.hasPermission("arcgiveaways.admin")) {
            player.sendMessage(locale.render(MessageKey.CANCEL_DENIED, player))
            return
        }
        repository.update(record.id) { current ->
            if (current.status == GiveawayStatus.OPEN || current.status == GiveawayStatus.DRAWING) {
                engine.cancel(current, clockMs(), "cancelled_by_host")
            } else null
        }.onMain(
            success = { result ->
                val changed = (result as? RepositoryUpdate.Changed)?.after
                if (changed != null) {
                    acceptRecord(changed)
                    player.sendMessage(locale.render(MessageKey.CANCELLED, player, values("id", changed.displayId())))
                    attemptPending(changed)
                } else player.sendMessage(locale.render(MessageKey.NOT_OPEN, player))
            },
            failure = { player.sendMessage(locale.render(MessageKey.REDIS_UNAVAILABLE, player)) },
        )
    }

    fun claim(player: Player) {
        val pending = cache.values.filter {
            (it.status == GiveawayStatus.AWAITING_DELIVERY && it.winner?.playerId == player.uniqueId.toString()) ||
                (it.status == GiveawayStatus.AWAITING_REFUND && it.hostId == player.uniqueId.toString())
        }
        if (pending.isEmpty()) {
            player.sendMessage(locale.render(MessageKey.NOTHING_TO_CLAIM, player))
            return
        }
        val remote = pending.firstOrNull { it.serverId != settings.serverId }
        if (remote != null) {
            player.sendMessage(locale.render(MessageKey.CLAIM_TRANSFERRING, player, values(
                "server", locale.serverName(remote.serverId, player),
            )))
            sendToServer(player, remote.serverId)
            return
        }
        player.sendMessage(locale.render(MessageKey.CLAIM_RETRYING, player))
        pending.forEach(::attemptPending)
    }

    private fun joinLocal(player: Player, record: GiveawayRecord) {
        if (record.hostId == player.uniqueId.toString()) {
            player.sendMessage(locale.render(MessageKey.HOST_CANNOT_JOIN, player))
            return
        }
        val host = Bukkit.getPlayer(UUID.fromString(record.hostId))?.takeIf { it.isOnline }
        if (host == null) {
            player.sendMessage(locale.render(MessageKey.NOT_OPEN, player))
            return
        }
        val hostLocation = host.location
        if (player.world.name != record.worldName || hostLocation.world != player.world) {
            player.sendMessage(locale.render(MessageKey.WRONG_WORLD, player, values("world", record.worldName)))
            return
        }
        if (player.location.distanceSquared(hostLocation) > record.radius * record.radius) {
            player.sendMessage(locale.render(MessageKey.TOO_FAR, player, values("radius", record.radius.toInt())))
            return
        }
        val participant = GiveawayParticipant(player.uniqueId.toString(), player.name, clockMs())
        repository.update(record.id) { current ->
            if (current.status != GiveawayStatus.OPEN || current.hostId == participant.playerId ||
                current.participants.any { it.playerId == participant.playerId } ||
                current.participants.size >= settings.maximumParticipants
            ) null else current.copy(participants = current.participants + participant)
        }.onMain(
            success = { result ->
                when (result) {
                    is RepositoryUpdate.Changed -> {
                        acceptRecord(result.after)
                        player.sendMessage(locale.render(MessageKey.JOINED, player, values("id", result.after.displayId(), "count", result.after.participants.size)))
                        play(player, "minecraft:entity.experience_orb.pickup", 1f, 1.25f)
                    }
                    is RepositoryUpdate.Rejected -> {
                        val current = result.current
                        val key = when {
                            current.status != GiveawayStatus.OPEN -> MessageKey.NOT_OPEN
                            current.hostId == participant.playerId -> MessageKey.HOST_CANNOT_JOIN
                            current.participants.any { it.playerId == participant.playerId } -> MessageKey.ALREADY_JOINED
                            current.participants.size >= settings.maximumParticipants -> MessageKey.FULL
                            else -> MessageKey.GENERIC_ERROR
                        }
                        player.sendMessage(locale.render(key, player))
                    }
                    else -> player.sendMessage(locale.render(MessageKey.GENERIC_ERROR, player))
                }
            },
            failure = { player.sendMessage(locale.render(MessageKey.REDIS_UNAVAILABLE, player)) },
        )
    }

    private fun joinTransferred(player: Player, giveawayId: String) {
        repository.load(giveawayId).onMain(
            success = { record ->
                if (record == null || record.status != GiveawayStatus.OPEN || record.serverId != settings.serverId) {
                    player.sendMessage(locale.render(if (record == null) MessageKey.NOT_FOUND else MessageKey.NOT_OPEN, player))
                    return@onMain
                }
                teleportAndJoin(player, record)
            },
            failure = { player.sendMessage(locale.render(MessageKey.REDIS_UNAVAILABLE, player)) },
        )
    }

    private fun teleportAndJoin(player: Player, record: GiveawayRecord) {
        if (record.hostId == player.uniqueId.toString()) {
            player.sendMessage(locale.render(MessageKey.HOST_CANNOT_JOIN, player))
            return
        }
        val host = runCatching { Bukkit.getPlayer(UUID.fromString(record.hostId)) }.getOrNull()
            ?.takeIf { it.isOnline && it.world.name == record.worldName }
        if (host == null) {
            player.sendMessage(locale.render(MessageKey.NOT_OPEN, player))
            return
        }
        travel.teleport(player, travel.arrivalNear(host)).onMain(
            success = { teleported ->
                if (teleported && player.isOnline) joinLocal(player, record)
                else player.sendMessage(locale.render(MessageKey.TELEPORT_FAILED, player))
            },
            failure = {
                plugin.logger.warning("Could not teleport ${player.uniqueId} to giveaway ${record.id}: ${it.message}")
                player.sendMessage(locale.render(MessageKey.TELEPORT_FAILED, player))
            },
        )
    }

    private fun transferForJoin(player: Player, record: GiveawayRecord) {
        if (!settings.crossServerEnabled || !settings.transferOnClick) {
            player.sendMessage(locale.render(MessageKey.NOT_OPEN, player))
            return
        }
        val pending = PendingJoin(record.id, clockMs() + settings.pendingJoinSeconds * 1000L)
        repository.savePendingJoin(player.uniqueId.toString(), pending).onMain(
            success = {
                player.sendMessage(locale.render(MessageKey.TRANSFERRING, player, values(
                    "server", locale.serverName(record.serverId, player),
                    "radius", record.radius.toInt(),
                )))
                sendToServer(player, record.serverId)
            },
            failure = { player.sendMessage(locale.render(MessageKey.REDIS_UNAVAILABLE, player)) },
        )
    }

    private fun tick() {
        val now = clockMs()
        cache.values.toList().forEach { record ->
            when {
                record.serverId == settings.serverId && record.status == GiveawayStatus.PREPARING -> recoverPreparing(record)
                record.serverId == settings.serverId && record.status == GiveawayStatus.OPEN && now >= record.drawAtMs -> beginDraw(record)
                record.serverId == settings.serverId && record.status == GiveawayStatus.DRAWING && now >= (record.drawingEndsAtMs ?: Long.MAX_VALUE) -> selectWinner(record)
                record.serverId == settings.serverId && record.status in setOf(GiveawayStatus.AWAITING_DELIVERY, GiveawayStatus.AWAITING_REFUND) -> attemptPending(record)
            }
            if (record.status in setOf(GiveawayStatus.OPEN, GiveawayStatus.DRAWING)) {
                updateEffects(record, now)
            } else {
                removeBossBar(record.id)
            }
        }
        cleanupTerminal(now)
    }

    private fun beginDraw(record: GiveawayRecord) {
        val eligible = eligibleParticipants(record)
        repository.update(record.id) { current ->
            if (current.status == GiveawayStatus.OPEN && clockMs() >= current.drawAtMs) {
                engine.beginDraw(current, eligible, clockMs(), settings.drawingSeconds * 1000L)
            } else null
        }.onMain(
            success = { result ->
                val changed = (result as? RepositoryUpdate.Changed)?.after ?: return@onMain
                acceptRecord(changed)
                if (changed.status == GiveawayStatus.AWAITING_REFUND) {
                    broadcast(MessageKey.NOT_ENOUGH, changed, values("id", changed.displayId(), "minimum", settings.minimumParticipants))
                    attemptPending(changed)
                }
            },
            failure = { plugin.logger.warning("Could not begin giveaway draw ${record.id}: ${it.message}") },
        )
    }

    private fun selectWinner(record: GiveawayRecord) {
        repository.update(record.id) { current ->
            if (current.status == GiveawayStatus.DRAWING && clockMs() >= (current.drawingEndsAtMs ?: Long.MAX_VALUE)) engine.selectWinner(current) else null
        }.onMain(
            success = { result -> (result as? RepositoryUpdate.Changed)?.after?.let { acceptRecord(it); attemptPending(it) } },
            failure = { plugin.logger.warning("Could not select giveaway winner ${record.id}: ${it.message}") },
        )
    }

    private fun attemptPending(record: GiveawayRecord) {
        if (record.serverId != settings.serverId) return
        val (playerId, kind) = when (record.status) {
            GiveawayStatus.AWAITING_DELIVERY -> record.winner?.playerId to JournalKind.PRIZE
            GiveawayStatus.AWAITING_REFUND -> record.hostId to JournalKind.REFUND
            else -> return
        }
        val uuid = runCatching { UUID.fromString(playerId) }.getOrNull() ?: return
        val player = Bukkit.getPlayer(uuid)?.takeIf { it.isOnline } ?: return
        val key = journalKey(record.id, kind)
        var journal = journals[key]
        if (journal == null) {
            val item = restoreItem(record.item) ?: return
            val plan = InventoryPlan.delivery(player, item)
            if (plan == null) {
                notifyClaimPending(player)
                return
            }
            journal = InventoryJournalRecord(
                giveawayId = record.id,
                playerId = uuid.toString(),
                kind = kind,
                status = JournalStatus.PREPARED,
                item = record.item,
                changes = plan.changes,
                createdAtMs = clockMs(),
            )
            runCatching { journalStore.write(journal) }.onFailure {
                plugin.logger.severe("Could not persist $kind journal for ${record.id}: ${it.message}")
                return
            }
            journals[key] = journal
        }
        val plan = InventoryPlan.from(journal.changes)
        when (plan.state(player)) {
            PlanState.BEFORE -> {
                recoveryIncidents.clear(key)
                journal = runCatching { persistThenApply(journal, plan, player) }
                    .onFailure { plugin.logger.severe("Could not converge $kind journal for ${record.id}: ${it.message}") }
                    .getOrNull() ?: return
                journals[key] = journal
            }
            PlanState.AFTER -> {
                recoveryIncidents.clear(key)
                if (journal.status != JournalStatus.APPLIED) {
                    journal = journal.copy(status = JournalStatus.APPLIED)
                    journalStore.write(journal)
                    journals[key] = journal
                }
            }
            PlanState.AMBIGUOUS -> {
                if (recoveryIncidents.markAmbiguous(key)) {
                    plugin.logger.severe("Ambiguous $kind inventory journal for giveaway ${record.id}; refusing blind retry")
                }
                return
            }
        }
        finalizeDelivery(record, player, kind, journal)
    }

    private fun finalizeDelivery(
        record: GiveawayRecord,
        player: Player,
        kind: JournalKind,
        appliedJournal: InventoryJournalRecord,
    ) {
        repository.update(record.id) { current ->
            when {
                kind == JournalKind.PRIZE && current.status == GiveawayStatus.AWAITING_DELIVERY -> engine.complete(current, clockMs())
                kind == JournalKind.REFUND && current.status == GiveawayStatus.AWAITING_REFUND -> engine.refunded(current, clockMs())
                else -> null
            }
        }.onMain(
            success = { result ->
                val terminal = (result as? RepositoryUpdate.Changed)?.after ?: return@onMain
                acceptRecord(terminal)
                if (!retireJournal(appliedJournal)) return@onMain
                journals.remove(journalKey(record.id, kind))
                recoveryIncidents.clear(journalKey(record.id, kind))
                val escrowKey = journalKey(record.id, JournalKind.ESCROW)
                journals[escrowKey]?.let { escrowJournal ->
                    if (retireJournal(escrowJournal)) journals.remove(escrowKey)
                }
                repository.releaseHost(record.hostId, record.id)
                if (kind == JournalKind.PRIZE) {
                    player.sendMessage(locale.render(MessageKey.DELIVERED, player))
                    celebrateWinner(player, record)
                }
            },
            failure = { plugin.logger.warning("Could not finalize $kind for giveaway ${record.id}: ${it.message}") },
        )
    }

    private fun recoverPreparing(record: GiveawayRecord) {
        val host = Bukkit.getPlayer(UUID.fromString(record.hostId))?.takeIf { it.isOnline } ?: return
        val key = journalKey(record.id, JournalKind.ESCROW)
        val journal = journals[key] ?: return
        val plan = InventoryPlan.from(journal.changes)
        val appliedJournal = when (plan.state(host)) {
            PlanState.BEFORE -> {
                recoveryIncidents.clear(key)
                val applied = runCatching { persistThenApply(journal, plan, host) }
                    .onFailure { plugin.logger.severe("Could not converge escrow journal for ${record.id}: ${it.message}") }
                    .getOrNull() ?: return
                journals[key] = applied
                applied
            }
            PlanState.AFTER -> {
                recoveryIncidents.clear(key)
                if (journal.status == JournalStatus.APPLIED) {
                    journal
                } else {
                    val applied = journal.copy(status = JournalStatus.APPLIED)
                    runCatching { journalStore.write(applied) }
                        .onFailure { plugin.logger.severe("Could not persist recovered escrow for ${record.id}: ${it.message}") }
                        .getOrNull() ?: return
                    journals[key] = applied
                    applied
                }
            }
            PlanState.AMBIGUOUS -> {
                if (recoveryIncidents.markAmbiguous(key)) {
                    plugin.logger.severe("Ambiguous escrow journal for giveaway ${record.id}; refusing blind recovery")
                }
                return
            }
        }
        check(appliedJournal.status == JournalStatus.APPLIED)
        inventoryLocks.remove(host.uniqueId)
        repository.update(record.id) { current -> if (current.status == GiveawayStatus.PREPARING) engine.open(current) else null }
            .onMain(
                success = { result ->
                    inventoryLocks.remove(host.uniqueId)
                    when (result) {
                        is RepositoryUpdate.Changed -> result.after
                        is RepositoryUpdate.Rejected -> result.current
                        else -> null
                    }?.let(::acceptRecord)
                },
                failure = { plugin.logger.warning("Could not recover PREPARING giveaway ${record.id}: ${it.message}") },
            )
    }

    private fun recoverFor(player: Player) {
        cache.values.filter {
            (it.status == GiveawayStatus.PREPARING && it.hostId == player.uniqueId.toString()) ||
                (it.status == GiveawayStatus.AWAITING_DELIVERY && it.winner?.playerId == player.uniqueId.toString()) ||
                (it.status == GiveawayStatus.AWAITING_REFUND && it.hostId == player.uniqueId.toString())
        }.forEach { record -> if (record.status == GiveawayStatus.PREPARING) recoverPreparing(record) else attemptPending(record) }
    }

    private fun eligibleParticipants(record: GiveawayRecord): List<GiveawayParticipant> {
        val host = Bukkit.getPlayer(UUID.fromString(record.hostId))?.takeIf { it.isOnline } ?: return emptyList()
        val center = host.location
        return record.participants.filter { participant ->
            val player = Bukkit.getPlayer(UUID.fromString(participant.playerId))
            player != null && player.isOnline && player.world == center.world && player.location.distanceSquared(center) <= record.radius * record.radius
        }
    }

    private fun updateEffects(record: GiveawayRecord, now: Long) {
        val allViewers = Bukkit.getOnlinePlayers().toList()
        val participantViewers = participantPlayers(
            if (record.status == GiveawayStatus.DRAWING) record.drawingCandidates else record.participants,
        )
        if (settings.bossBarEnabled) {
            val drawing = record.status == GiveawayStatus.DRAWING
            val remaining = if (drawing) {
                ((record.drawingEndsAtMs ?: now) - now).coerceAtLeast(0L)
            } else {
                (record.drawAtMs - now).coerceAtLeast(0L)
            }
            val total = if (drawing) settings.drawingSeconds * 1_000L else (record.drawAtMs - record.opensAtMs).coerceAtLeast(1L)
            val progress = (remaining.toDouble() / total).toFloat().coerceIn(0f, 1f)
            val name = locale.render(
                if (drawing) MessageKey.BOSSBAR_DRAWING else MessageKey.BOSSBAR_OPEN,
                values = values(
                    "item", itemComponent(record),
                    "seconds", ceil(remaining / 1000.0).toInt(),
                    "host", record.hostName,
                    "count", record.participants.size,
                ),
            )
            val bar = bossBars.getOrPut(record.id) { BossBar.bossBar(name, progress, BossBar.Color.YELLOW, BossBar.Overlay.PROGRESS) }
            bar.name(name)
            bar.progress(progress)
            bar.color(if (drawing) BossBar.Color.RED else BossBar.Color.YELLOW)
            syncBossViewers(record.id, bar, allViewers)
        } else {
            removeBossBar(record.id)
        }
        if (record.status == GiveawayStatus.OPEN) {
            val seconds = ceil((record.drawAtMs - now).coerceAtLeast(0L) / 1000.0).toInt()
            if (countdownSecond.put(record.id, seconds) != seconds) {
                participantViewers.forEach { player ->
                    if (settings.actionBarEnabled) {
                        player.sendActionBar(locale.render(MessageKey.COUNTDOWN_ACTIONBAR, player, values(
                            "item", itemComponent(record),
                            "seconds", seconds,
                            "count", record.participants.size,
                        )))
                    }
                    if (settings.titlesEnabled && seconds in 1..5) {
                        presentation.showTitle(player, Title.title(
                            locale.render(MessageKey.COUNTDOWN_TITLE, player, values("seconds", seconds)),
                            locale.render(MessageKey.COUNTDOWN_SUBTITLE, player, values("item", itemComponent(record))),
                            Title.Times.times(Duration.ofMillis(100), Duration.ofMillis(700), Duration.ofMillis(150)),
                        ))
                        play(player, "minecraft:block.note_block.pling", 1f, 0.8f + (5 - seconds) * 0.12f)
                    }
                }
            }
            if (record.serverId == settings.serverId && settings.particlesEnabled && now % 2000L < 1000L) {
                presentation.spawnHostAura(hostCenter(record))
            }
        } else if (record.status == GiveawayStatus.DRAWING && record.drawingCandidates.isNotEmpty()) {
            val candidate = record.drawingCandidates[(now / 250L % record.drawingCandidates.size).toInt()].playerName
            participantViewers.forEach { player ->
                if (settings.titlesEnabled) presentation.showTitle(player, Title.title(
                    locale.render(MessageKey.DRAWING_TITLE, player),
                    locale.render(MessageKey.DRAWING_SUBTITLE, player, values("candidate", candidate)),
                    Title.Times.times(Duration.ZERO, Duration.ofMillis(350), Duration.ZERO),
                ))
                play(player, "minecraft:block.note_block.hat", 0.7f, 1.2f)
            }
        }
    }

    private fun acceptRecord(record: GiveawayRecord) {
        val previous = synchronized(cacheMutationLock) {
            val current = cache[record.id]
            if (current != null && current.revision >= record.revision) return
            val previous = cache.put(record.id, record)
            activeLeaseGauge.transition(previous?.isActive() == true, record.isActive())
            previous
        }
        if (record.status == GiveawayStatus.OPEN && record.id !in announced) {
            announced += record.id
            announce(record)
        }
        if (record.status == GiveawayStatus.AWAITING_DELIVERY && record.winner != null && record.id !in winnerAnnounced) {
            winnerAnnounced += record.id
            announceWinner(record)
        }
        if (!record.reservesHost()) {
            repository.releaseHost(record.hostId, record.id).whenComplete { _, failure ->
                if (failure != null) plugin.logger.warning("Could not release host lease for giveaway ${record.id}: ${failure.message}")
            }
        }
        if (record.status == GiveawayStatus.COMPLETED || record.status == GiveawayStatus.CANCELLED) {
            retireTerminalEscrow(record)
        }
        if (record.status !in setOf(GiveawayStatus.OPEN, GiveawayStatus.DRAWING)) {
            countdownSecond.remove(record.id)
            removeBossBar(record.id)
        }
    }

    private fun announce(record: GiveawayRecord) {
        Bukkit.getOnlinePlayers().forEach { player ->
            val body = locale.render(MessageKey.ANNOUNCEMENT, player, values(
                "host", record.hostName,
                "item", itemComponent(record),
                "seconds", settings.openSeconds,
            ))
            val hoverKey = if (record.serverId == settings.serverId) MessageKey.JOIN_HOVER_LOCAL else MessageKey.JOIN_HOVER_TRANSFER
            val button = locale.render(MessageKey.JOIN_BUTTON, player)
                // Standard RUN_COMMAND survives Velocity/ViaVersion backend routing. Paper's
                // custom callback packet is currently decoded as chat on the public proxy path.
                .clickEvent(ClickEvent.runCommand("/giveaway join ${record.id}"))
                .hoverEvent(HoverEvent.showText(locale.render(hoverKey, player, values(
                    "radius", record.radius.toInt(),
                    "server", locale.serverName(record.serverId, player),
                ))))
            player.sendMessage(GiveawayPresentation.chatAnnouncement(body, button))
            play(player, "minecraft:entity.firework_rocket.launch", 0.65f, 1.1f)
        }
    }

    private fun announceJoined(record: GiveawayRecord, participant: GiveawayParticipant) {
        Bukkit.getOnlinePlayers().forEach { player ->
            player.sendMessage(locale.render(MessageKey.JOINED_NETWORK, player, values(
                "player", participant.playerName,
                "host", record.hostName,
                "item", itemComponent(record),
                "count", record.participants.size,
            )))
            play(player, "minecraft:block.note_block.chime", 0.45f, 1.35f)
        }
    }

    private fun announceWinner(record: GiveawayRecord) {
        broadcast(MessageKey.WINNER_NETWORK, record, values(
            "winner", record.winner?.playerName.orEmpty(),
            "host", record.hostName,
            "item", itemComponent(record),
        ))
    }

    private fun broadcast(key: MessageKey, record: GiveawayRecord, replacements: Map<String, Component>) {
        Bukkit.getOnlinePlayers().forEach { it.sendMessage(locale.render(key, it, replacements)) }
    }

    private fun celebrateWinner(player: Player, record: GiveawayRecord) {
        if (settings.titlesEnabled) presentation.showTitle(player, Title.title(
            locale.render(MessageKey.WINNER_TITLE, player),
            locale.render(MessageKey.WINNER_SUBTITLE, player, values("item", itemComponent(record))),
            Title.Times.times(Duration.ofMillis(200), Duration.ofSeconds(3), Duration.ofMillis(500)),
        ))
        play(player, "minecraft:ui.toast.challenge_complete", 1f, 1f)
        if (settings.fireworksEnabled) {
            repeat(3) { index ->
                lifecycleTasks.runLater(index * 8L) firework@{
                    if (!player.isOnline) return@firework
                    val firework = player.world.spawn(player.location, Firework::class.java)
                    firework.addScoreboardTag(VISUAL_FIREWORK_TAG)
                    firework.fireworkMeta = firework.fireworkMeta.apply {
                        power = 1
                        addEffect(FireworkEffect.builder().with(FireworkEffect.Type.BALL_LARGE).withColor(Color.ORANGE, Color.YELLOW).withFade(Color.WHITE).trail(true).flicker(true).build())
                    }
                }
            }
        }
    }

    private fun refresh(event: ru.ruscrafting.giveaways.network.GiveawayWireEvent) {
        val id = event.giveawayId
        if (event.type == GiveawayEventType.DELETED) {
            lifecycleTasks.runSync {
                removeCachedRecord(id)
                removeBossBar(id)
            }
            return
        }
        repository.load(id).onMain(
            success = { record ->
                if (record != null) {
                    acceptRecord(record)
                    if (event.type == GiveawayEventType.JOINED) {
                        event.participant?.let { participant -> announceJoined(record, participant) }
                    }
                }
            },
            failure = { plugin.logger.warning("Could not refresh giveaway $id: ${it.message}") },
        )
    }

    private fun reconcile() {
        repository.loadAll().onMain(
            success = { records ->
                val ids = records.map { it.id }.toSet()
                records.forEach(::acceptRecord)
                cache.keys.filter { it !in ids }.forEach { removeCachedRecord(it); removeBossBar(it) }
                journals.values.filter { it.kind == JournalKind.ESCROW && it.giveawayId !in ids }
                    .forEach(::reconcileOrphanEscrow)
            },
            failure = { plugin.logger.warning("Giveaway Redis reconciliation failed: ${it.message}") },
        )
    }

    private fun heartbeatPresence() {
        backendDirectory.heartbeat().whenComplete { _, failure ->
            if (failure == null) {
                presenceFailureLogged.set(false)
            } else if (presenceFailureLogged.compareAndSet(false, true)) {
                plugin.logger.warning("Giveaway backend presence refresh failed: ${failure.message}")
            }
        }
    }

    private fun cleanupTerminal(now: Long) {
        val retention = settings.terminalRetentionMinutes * 60_000L
        cache.values.filter { !it.isActive() && it.terminalAtMs != null && now - it.terminalAtMs > retention }.forEach { record ->
            repository.delete(record).thenAccept { deleted ->
                if (deleted) {
                    removeCachedRecord(record.id)
                }
            }
        }
    }

    private fun cleanupRejectedStart(record: GiveawayRecord, player: Player) {
        inventoryLocks.remove(player.uniqueId)
        val key = journalKey(record.id, JournalKind.ESCROW)
        journals[key]?.let(::retireUnmutatedOrphan)
        repository.releaseHost(record.hostId, record.id).whenComplete { _, failure ->
            if (failure != null) plugin.logger.warning("Could not release rejected host claim ${record.id}: ${failure.message}")
        }
    }

    private fun reconcileUnknownStart(giveawayId: String) {
        repository.load(giveawayId).onMain(
            success = { record ->
                if (record != null) {
                    acceptRecord(record)
                    if (record.status == GiveawayStatus.PREPARING) recoverPreparing(record)
                } else {
                    journals[journalKey(giveawayId, JournalKind.ESCROW)]?.let(::reconcileOrphanEscrow)
                }
            },
            failure = { plugin.logger.warning("Could not reconcile unknown giveaway start $giveawayId: ${it.message}") },
        )
    }

    private fun reconcileOrphanEscrow(journal: InventoryJournalRecord) {
        if (journal.kind != JournalKind.ESCROW || journal.giveawayId in startsInFlight) return
        repository.load(journal.giveawayId).onMain(
            success = { record ->
                if (record != null) {
                    acceptRecord(record)
                    if (record.status == GiveawayStatus.PREPARING) recoverPreparing(record)
                    return@onMain
                }
                inspectOrphanHostClaim(journal)
            },
            failure = { plugin.logger.warning("Could not confirm orphan escrow ${journal.giveawayId}: ${it.message}") },
        )
    }

    private fun inspectOrphanHostClaim(journal: InventoryJournalRecord) {
        repository.hostGiveawayId(journal.playerId).onMain(
            success = { claimedId ->
                if (journal.giveawayId in startsInFlight) return@onMain
                if (claimedId != journal.giveawayId) {
                    retireUnmutatedOrphan(journal)
                    return@onMain
                }
                repository.load(journal.giveawayId).onMain(
                    success = { record ->
                        if (record != null) {
                            acceptRecord(record)
                            if (record.status == GiveawayStatus.PREPARING) recoverPreparing(record)
                        } else if (journal.giveawayId !in startsInFlight) {
                            repository.releaseHost(journal.playerId, journal.giveawayId).onMain(
                                success = { released -> if (released) retireUnmutatedOrphan(journal) },
                                failure = { plugin.logger.warning("Could not release orphan host claim ${journal.giveawayId}: ${it.message}") },
                            )
                        }
                    },
                    failure = { plugin.logger.warning("Could not recheck orphan giveaway ${journal.giveawayId}: ${it.message}") },
                )
            },
            failure = { plugin.logger.warning("Could not inspect orphan escrow ${journal.giveawayId}: ${it.message}") },
        )
    }

    private fun retireUnmutatedOrphan(journal: InventoryJournalRecord) {
        if (journal.status != JournalStatus.PREPARED) return
        val player = runCatching { Bukkit.getPlayer(UUID.fromString(journal.playerId)) }.getOrNull()
            ?.takeIf(Player::isOnline) ?: return
        if (InventoryPlan.from(journal.changes).state(player) != PlanState.BEFORE) return
        val key = journalKey(journal.giveawayId, journal.kind)
        runCatching { journalStore.acknowledgeExactly(journal) }
            .onSuccess { outcome ->
                if (outcome != DurableAcknowledgementOutcome.CONTENT_MISMATCH) {
                    journals.remove(key)
                    recoveryIncidents.clear(key)
                }
            }
            .onFailure { plugin.logger.warning("Could not retire orphan escrow ${journal.giveawayId}: ${it.message}") }
    }

    private fun resolveId(value: String): String? {
        val normalized = value.lowercase()
        return cache.keys.filter { it == normalized || it.startsWith(normalized) }.singleOrNull()
            ?: runCatching { UUID.fromString(value).toString() }.getOrNull()
    }

    private fun removeCachedRecord(id: String): GiveawayRecord? = synchronized(cacheMutationLock) {
        cache.remove(id).also { removed ->
            activeLeaseGauge.transition(removed?.isActive() == true, currentActive = false)
            announced.remove(id)
            winnerAnnounced.remove(id)
            countdownSecond.remove(id)
        }
    }

    private fun center(record: GiveawayRecord): Location {
        val world = Bukkit.getWorld(record.worldName) ?: Bukkit.getWorlds().first()
        return Location(world, record.anchorX, record.anchorY, record.anchorZ)
    }

    private fun hostCenter(record: GiveawayRecord): Location =
        runCatching { Bukkit.getPlayer(UUID.fromString(record.hostId)) }.getOrNull()
            ?.takeIf(Player::isOnline)
            ?.location
            ?.clone()
            ?: center(record)

    private fun participantPlayers(participants: List<GiveawayParticipant>): List<Player> = participants.mapNotNull { participant ->
        runCatching { Bukkit.getPlayer(UUID.fromString(participant.playerId)) }.getOrNull()?.takeIf(Player::isOnline)
    }

    private fun syncBossViewers(id: String, bar: BossBar, players: List<Player>) {
        val desired = players.map { it.uniqueId }.toSet()
        val current = bossViewers.getOrPut(id) { mutableSetOf() }
        (current - desired).forEach { uuid -> Bukkit.getPlayer(uuid)?.hideBossBar(bar); current.remove(uuid) }
        (desired - current).forEach { uuid -> Bukkit.getPlayer(uuid)?.showBossBar(bar); current.add(uuid) }
    }

    private fun removeBossBar(id: String) {
        val bar = bossBars.remove(id) ?: return
        bossViewers.remove(id).orEmpty().forEach { uuid -> Bukkit.getPlayer(uuid)?.hideBossBar(bar) }
    }

    private fun itemComponent(record: GiveawayRecord): Component {
        val item = restoreItem(record.item)
        val base = item?.let(itemNames::displayName) ?: Component.text(record.item.materialKey)
        val withAmount = if (record.item.amount > 1) base.append(Component.text(" ×${record.item.amount}")) else base
        return if (item == null) withAmount else presentation.decorateItemHover(withAmount, item)
    }

    private fun restoreItem(payload: ItemPayload): ItemStack? =
        runCatching { ItemStack.deserializeBytes(payload.decodedBytes()).also { require(it.amount == payload.amount) } }
            .onFailure { plugin.logger.severe("Rejected invalid giveaway item payload: ${it.message}") }
            .getOrNull()

    private fun notifyClaimPending(player: Player) {
        val now = clockMs()
        if (now - (claimNoticeAt[player.uniqueId] ?: 0L) < 10_000L) return
        claimNoticeAt[player.uniqueId] = now
        player.sendMessage(locale.render(MessageKey.DELIVERY_PENDING, player))
    }

    private fun play(player: Player, sound: String, volume: Float, pitch: Float) {
        if (settings.soundsEnabled) player.playSound(player.location, sound, volume, pitch)
    }

    private fun sendToServer(player: Player, serverId: String) {
        if (transfer.connect(player, BackendServerId.of(serverId)) != BackendTransferResult.SENT) {
            player.sendMessage(locale.render(MessageKey.TELEPORT_FAILED, player))
        }
    }

    private fun values(vararg entries: Any?): Map<String, Component> {
        require(entries.size % 2 == 0) { "Giveaway message replacements must be key/value pairs" }
        return entries.asList().chunked(2).associate { (rawKey, value) ->
            val key = rawKey as? String ?: error("Giveaway message replacement key must be a string")
            key to if (value is Component) value else locale.text(value)
        }
    }

    private fun journalKey(id: String, kind: JournalKind): String = "$id:${kind.name}"

    private fun persistThenApply(
        journal: InventoryJournalRecord,
        plan: InventoryPlan,
        player: Player,
    ): InventoryJournalRecord = inventoryRecovery.commitThenMutate(journal) { committed ->
        completed {
            check(plan.state(player) == PlanState.BEFORE) { "Inventory changed before committed journal mutation" }
            check(plan.apply(player, playerData)) { "Committed inventory journal mutation did not verify" }
            journalStore.write(committed.copy(status = JournalStatus.APPLIED))
        }
    }.join().mutation

    private fun retireJournal(journal: InventoryJournalRecord): Boolean {
        val completion = inventoryRecovery.restoreThenAcknowledge(journal) { committed ->
            completed {
                check(committed.status == JournalStatus.APPLIED) { "Only an applied inventory journal may be retired" }
                committed
            }
        }.join()
        if (completion is DurableRecoveryCompletion.ContentMismatch) {
            plugin.logger.severe("Inventory journal ${journal.giveawayId}:${journal.kind} changed before acknowledgement")
            return false
        }
        return true
    }

    private fun retireTerminalEscrow(record: GiveawayRecord) {
        val key = journalKey(record.id, JournalKind.ESCROW)
        val journal = journals[key] ?: return
        runCatching {
            val retired = if (journal.status == JournalStatus.APPLIED) {
                retireJournal(journal)
            } else {
                journalStore.acknowledgeExactly(journal) != DurableAcknowledgementOutcome.CONTENT_MISMATCH
            }
            if (retired) {
                journals.remove(key)
                recoveryIncidents.clear(key)
            }
        }.onFailure { failure ->
            plugin.logger.warning("Could not retire terminal escrow journal for ${record.id}: ${failure.message}")
        }
    }

    private fun <T : Any> completed(operation: () -> T): CompletableFuture<T> =
        runCatching(operation).fold(CompletableFuture<T>::completedFuture, CompletableFuture<T>::failedFuture)

    private fun newEngine(config: GiveawayConfig): GiveawayEngine =
        GiveawayEngine(config.maximumParticipants, config.minimumParticipants) { bound -> secureRandom.nextInt(bound) }

    companion object {
        const val VISUAL_FIREWORK_TAG = "arcgiveaways_visual"
        private const val TICKS_PER_SECOND = 20L
    }

    private fun <T> CompletableFuture<T>.onMain(success: (T) -> Unit, failure: (Throwable) -> Unit) {
        val token = runCatching(lifecycleTasks::token).getOrNull() ?: return
        whenCompleteSync(lifecycleTasks, token) { value, error ->
            @Suppress("UNCHECKED_CAST")
            if (error == null) success(value as T) else failure(error.cause ?: error)
        }
    }
}

/** Thread-safe transition gauge that cannot underflow on first observation of an inactive record. */
internal class ActiveLeaseGauge {
    private val value = AtomicInteger()

    fun transition(
        previousActive: Boolean,
        currentActive: Boolean,
    ) {
        when {
            !previousActive && currentActive -> value.incrementAndGet()
            previousActive && !currentActive -> value.updateAndGet { current -> (current - 1).coerceAtLeast(0) }
        }
    }

    fun count(): Int = value.get()
}
