package ru.ruscrafting.giveaways.paper

import net.kyori.adventure.bossbar.BossBar
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.event.ClickEvent
import net.kyori.adventure.text.event.HoverEvent
import net.kyori.adventure.title.Title
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.plugin.java.JavaPlugin
import ru.arc.core.LifecycleTaskScope
import ru.arc.core.BoundedAsyncExecutor
import ru.arc.core.BudgetedTaskQueue
import ru.arc.network.BackendServerId
import ru.arc.observability.StructuredDebugLine
import ru.arc.paper.api.ArcNativeCleanupService
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
import java.util.TreeSet
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.function.BooleanSupplier
import kotlin.math.ceil
import kotlin.math.roundToInt

class GiveawayService(
    private val plugin: JavaPlugin,
    @Volatile private var settings: GiveawayConfig,
    private val locale: GiveawayLocale,
    private val repository: RedisGiveawayRepository,
    private val journalStore: InventoryJournalRepository,
    private val itemNames: RussianItemNames,
    private val transfer: BackendTransfer,
    private val backendDirectory: GiveawayBackendDirectory,
    private val storage: BoundedAsyncExecutor,
    private val nativeWork: BudgetedTaskQueue,
    private val nativeCleanup: ArcNativeCleanupService,
    private val clockMs: () -> Long = System::currentTimeMillis,
    private val presentation: GiveawayPresentationPort = NativeGiveawayPresentationPort,
    private val travel: GiveawayTravelPort = NativeGiveawayTravelPort,
    private val playerData: GiveawayPlayerDataPersistence = NativeGiveawayPlayerDataPersistence,
) : AutoCloseable {
    data class MenuStartPreview(
        val amount: Int,
        val maximum: Int,
        val itemKey: String,
        val cooldownSeconds: Int = 0,
        val block: MenuStartBlock? = null,
    ) {
        val valid: Boolean get() = block == null
    }

    enum class MenuStartBlock(val messageKey: MessageKey, val menuMessageKey: MessageKey) {
        BUSY(MessageKey.BUSY, MessageKey.MENU_ERROR_BUSY),
        COOLDOWN(MessageKey.COOLDOWN, MessageKey.MENU_ERROR_COOLDOWN),
        EMPTY_HAND(MessageKey.EMPTY_HAND, MessageKey.MENU_ERROR_EMPTY_HAND),
        BAD_AMOUNT(MessageKey.BAD_AMOUNT, MessageKey.MENU_ERROR_BAD_AMOUNT),
        ITEM_CHANGED(MessageKey.MENU_ITEM_CHANGED, MessageKey.MENU_ERROR_ITEM_CHANGED),
    }

    @Volatile private var engine = newEngine(settings)
    private val lifecycleTasks = LifecycleTaskScope()
    private val closed = AtomicBoolean()
    private val journalOperations = ConcurrentHashMap.newKeySet<String>()
    private val drawsInFlight = ConcurrentHashMap.newKeySet<String>()
    @Volatile private var eventBus: AutoCloseable? = null
    private val cache = ConcurrentHashMap<String, GiveawayRecord>()
    private val recordIds = TreeSet<String>()
    private val hostedIds = mutableMapOf<String, MutableSet<String>>()
    private val pendingIds = mutableMapOf<String, MutableSet<String>>()
    private val protectedPlayers = mutableMapOf<String, Int>()
    private val participantsGauge = AtomicInteger()
    private val cacheVersion = AtomicLong()
    private val snapshotRefresh = AtomicReference<CompletableFuture<Unit>?>()
    @Volatile private var publishedSnapshotVersion = 0L
    @Volatile private var activeSnapshot: List<GiveawayRecord> = emptyList()
    @Volatile private var inventoryLocks = ConcurrentHashMap.newKeySet<UUID>()
    @Volatile private var startsInFlight = ConcurrentHashMap.newKeySet<String>()
    private val cooldowns = ConcurrentHashMap<UUID, Long>()
    private val journals = ConcurrentHashMap<String, InventoryJournalRecord>()
    private val cacheMutationLock = Any()
    private val activeLeaseGauge = ActiveLeaseGauge()
    private var bossBars = mutableMapOf<String, BossBar>()
    private var bossViewers = mutableMapOf<String, MutableSet<UUID>>()
    private val announced = mutableSetOf<String>()
    private val winnerAnnounced = mutableSetOf<String>()
    private val countdownSecond = mutableMapOf<String, Int>()
    private val visualScene = mutableMapOf<String, GiveawayVisualScene>()
    private val visualPulseAt = mutableMapOf<String, Long>()
    private val visualFireworkAt = mutableMapOf<String, Long>()
    private val visualFireworkVariant = mutableMapOf<String, Int>()
    private var itemDisplays = ConcurrentHashMap<String, GiveawayItemDisplayHandle>()
    private var itemDisplayYaw = mutableMapOf<String, Float>()
    private var originalGlow = ConcurrentHashMap<UUID, Boolean>()
    private var tickPending = false
    private var displayUpdatePending = false
    private var visualGeneration = 0L
    private val sceneWork = mutableMapOf<String, Pair<GiveawayVisualScene, CompletableFuture<Unit>>>()
    @Volatile private var reconcilePending = false
    @Volatile private var glowPending = false
    private val bossSyncPending = mutableSetOf<String>()
    private val pendingVisualRetirements = ConcurrentHashMap.newKeySet<CompletableFuture<Void>>()
    private val visualRetirementFailure = AtomicReference<Throwable?>()
    private var closeCompletion: CompletableFuture<Void>? = null
    private val claimNoticeAt = mutableMapOf<UUID, Long>()
    private val pvpNoticeAt = mutableMapOf<UUID, Long>()
    @Volatile private var recoveryIncidents = InventoryRecoveryIncidentTracker()
    private val presenceFailureLogged = AtomicBoolean()
    private val secureRandom = SecureRandom()
    private val qaSummary = StructuredDebugLine("ARCGIVEAWAYS_QA")
    private val qaRecord = StructuredDebugLine("ARCGIVEAWAYS_QA_RECORD")
    private val qaJournal = StructuredDebugLine("ARCGIVEAWAYS_QA_JOURNAL")
    private val hostLeases = GiveawayHostLeaseCoordinator(
        repository = repository,
        availability = backendDirectory::probe,
        cancelUnavailable = { record -> engine.cancel(record, clockMs(), "owner_backend_unavailable") },
    )
    private val inventoryRecovery = DurableRecoveryWorkflow<InventoryJournalRecord, InventoryJournalRecord>(
        commit = { candidate -> storage.submit { journalStore.write(candidate) } },
        sameContent = { candidate, committed -> candidate == committed },
        acknowledge = { committed, _ -> storage.submit { journalStore.acknowledgeExactly(committed) } },
    )

    /** Reads and validates durable bytes on storage, then checks native item payloads one slot per step. */
    fun start(): CompletableFuture<Unit> = storage.submit {
        val loaded = journalStore.loadAll()
        eventBus = repository.openEvents(originAllowed = settings.allowedOrigins::contains) { event, _ -> refresh(event) }
        loaded
    }.thenCompose { loaded ->
        val records = loaded.iterator()
        var current: InventoryJournalRecord? = null
        var changes: Iterator<SlotChange> = emptyList<SlotChange>().iterator()
        nativeWork.call({ !closed.get() }) {
            nativeWork.submit({ !closed.get() }) {
                if (changes.hasNext()) {
                    InventoryJournalRecord.validateNative(changes.next())
                    false
                } else {
                    current?.let { journals[journalKey(it.giveawayId, it.kind)] = it }
                    current = null
                    if (!records.hasNext()) true else {
                        current = records.next()
                        changes = requireNotNull(current).changes.iterator()
                        false
                    }
                }
            }
        }.thenCompose { it }
    }.thenCompose {
        nativeWork.call({ !closed.get() }) {
            reconcile()
            heartbeatPresence()
            lifecycleTasks.runTimer(20, 20, ::tick)
            lifecycleTasks.runTimer(1, ITEM_DISPLAY_UPDATE_TICKS, ::updateItemDisplays)
            lifecycleTasks.runTimer(40, 100, ::reconcile)
            lifecycleTasks.runTimer(
                settings.presenceHeartbeatSeconds * TICKS_PER_SECOND,
                settings.presenceHeartbeatSeconds * TICKS_PER_SECOND,
                ::heartbeatPresence,
            )
            Unit
        }
    }

    fun reload(updated: GiveawayConfig) {
        settings = updated.validated()
        engine = newEngine(settings)
        visualGeneration++
        clearAllItemDisplays()
    }

    /** Seal immediately; the host queue retires detached visuals after this consumer stops. */
    fun closeAsync(): CompletableFuture<Void> {
        check(Bukkit.isPrimaryThread()) { "Giveaway visuals must close on the server thread" }
        closeCompletion?.let { return it.copy() }
        val completion = CompletableFuture<Void>()
        closeCompletion = completion
        closed.set(true)
        snapshotRefresh.getAndSet(null)?.completeExceptionally(java.util.concurrent.CancellationException("Giveaway service is closed"))
        lifecycleTasks.close()
        val bars = bossBars.iterator()
        val viewers = bossViewers
        val displays = itemDisplays.values.iterator()
        val glows = originalGlow.iterator()
        val server = plugin.server
        val nativePresentation = presentation
        bossBars = mutableMapOf(); bossViewers = mutableMapOf()
        itemDisplays = ConcurrentHashMap(); itemDisplayYaw = mutableMapOf()
        originalGlow = ConcurrentHashMap()
        val steps = sequence {
            for ((id, bar) in bars) {
                for (viewer in viewers[id].orEmpty()) {
                    attemptVisualCleanup { server.getPlayer(viewer)?.hideBossBar(bar) }
                    yield(Unit)
                }
                yield(Unit)
            }
            for (display in displays) { attemptVisualCleanup(display::remove); yield(Unit) }
            for ((id, original) in glows) {
                attemptVisualCleanup { server.getPlayer(id)?.let { nativePresentation.setGlowing(it, original) } }
                yield(Unit)
            }
            while (pendingVisualRetirements.isNotEmpty()) yield(Unit)
            visualRetirementFailure.get()?.let { throw it }
        }.iterator()
        try {
            nativeCleanup.submit("giveaway-visuals", BooleanSupplier {
                if (steps.hasNext()) { steps.next(); false } else true
            }).whenComplete { _, failure ->
                if (failure == null) completion.complete(null) else completion.completeExceptionally(failure)
            }
        } catch (failure: Throwable) { completion.completeExceptionally(failure) }
        inventoryLocks = ConcurrentHashMap.newKeySet()
        startsInFlight = ConcurrentHashMap.newKeySet()
        recoveryIncidents = InventoryRecoveryIncidentTracker()
        return completion.copy()
    }

    /** Dispatch-only; the composition root includes [closeAsync] in its generation drain. */
    override fun close() { closeAsync() }

    private fun attemptVisualCleanup(operation: () -> Unit) {
        try { operation() } catch (failure: Throwable) { visualRetirementFailure.compareAndSet(null, failure) }
    }

    private fun retireVisuals(steps: Iterator<Unit>) {
        val result = try {
            nativeCleanup.submit("giveaway-visuals", BooleanSupplier {
                if (steps.hasNext()) { steps.next(); false } else true
            }).toCompletableFuture()
        } catch (failure: Throwable) { CompletableFuture.failedFuture<Void>(failure) }
        pendingVisualRetirements.add(result)
        result.whenComplete { _, failure ->
            if (failure != null) {
                visualRetirementFailure.compareAndSet(null, failure)
                plugin.logger.warning("Giveaway visual cleanup failed: ${failure.message}")
            }
            pendingVisualRetirements.remove(result)
        }
    }

    /** Called by the composition root's reserved storage finalizer after accepted journal writes. */
    fun closePersistence() {
        try { eventBus?.close() } finally { backendDirectory.close() }
    }

    fun isInventoryLocked(playerId: UUID): Boolean = playerId in inventoryLocks

    fun shouldCancelPvp(attacker: Player, victim: Player): Boolean {
        val protected = protectedPlayers.containsKey(attacker.uniqueId.toString()) ||
            protectedPlayers.containsKey(victim.uniqueId.toString())
        if (protected) {
            val now = clockMs()
            if (now - (pvpNoticeAt[attacker.uniqueId] ?: 0L) >= 2_000L) {
                pvpNoticeAt[attacker.uniqueId] = now
                attacker.sendActionBar(locale.render(MessageKey.PVP_PROTECTED, attacker))
            }
        }
        return protected
    }

    fun activeRecords(): List<GiveawayRecord> = activeSnapshot

    fun menuStartPreview(player: Player, requestedAmount: Int?, expectedItem: ItemStack? = null): MenuStartPreview {
        val held = player.inventory.itemInMainHand.takeUnless { it.type.isAir }
        val maximum = minOf(held?.amount ?: 0, settings.maximumItemAmount)
        val amount = requestedAmount ?: 0
        val cooldownMillis = ((cooldowns[player.uniqueId] ?: 0L) - clockMs()).coerceAtLeast(0L)
        val cooldownSeconds = ((cooldownMillis + 999L) / 1000L).toInt()
        val block = when {
            inventoryLocks.contains(player.uniqueId) -> MenuStartBlock.BUSY
            cooldownSeconds > 0 -> MenuStartBlock.COOLDOWN
            expectedItem != null && (held == null || !held.isSimilar(expectedItem) || held.amount < expectedItem.amount) -> MenuStartBlock.ITEM_CHANGED
            held == null -> MenuStartBlock.EMPTY_HAND
            amount !in 1..maximum -> MenuStartBlock.BAD_AMOUNT
            else -> null
        }
        return MenuStartPreview(amount, maximum, held?.type?.key?.toString() ?: "minecraft:air", cooldownSeconds, block)
    }

    fun menuRadius(): Double = settings.radius

    fun menuOpenSeconds(): Int = settings.openSeconds

    fun menuRemainingSeconds(record: GiveawayRecord): Int = ceil((record.drawAtMs - clockMs()).coerceAtLeast(0L) / 1000.0).toInt()

    fun menuItemName(item: ItemStack): Component = itemNames.displayName(item)

    /** Constant-time in-memory gauge safe for runtime health sampling. */
    fun recoveryBacklog(): Int = journals.size

    /** Constant-time count of locally observed active host claims. */
    fun activeLeaseCount(): Int = activeLeaseGauge.count()

    fun backendLeaseCount(): Int = backendDirectory.activeLeaseCount()

    fun participantCount(): Int = participantsGauge.get()

    private data class QaSelection(
        val query: String?,
        val found: Boolean,
        val settings: GiveawayConfig,
        val records: List<GiveawayRecord>,
        val journals: List<InventoryJournalRecord>,
        val backendLeases: Int,
    )

    private data class QaJournalObservation(val journal: InventoryJournalRecord, val online: Boolean, val state: String)
    private data class QaObservation(
        val selection: QaSelection,
        val eligibleCounts: Map<String, Int>,
        val journals: List<QaJournalObservation>,
    )

    fun sendQaReport(sender: CommandSender, idOrPrefix: String?): CompletableFuture<Unit> = qaReport(idOrPrefix)
        .thenCompose { lines -> nativeWork.call({ !closed.get() }) {
            val pending = lines.iterator()
            nativeWork.submit({ !closed.get() }) {
                if (!pending.hasNext()) true else { sender.sendMessage(pending.next()); false }
            }
        }.thenCompose { it } }.whenComplete { _, failure ->
            if (failure != null && !closed.get()) plugin.logger.warning("Could not prepare giveaway QA report: ${failure.message}")
        }

    fun qaReport(idOrPrefix: String?): CompletableFuture<List<String>> = storage.submit {
        selectQa(idOrPrefix)
    }.thenCompose { selected -> nativeWork.call({ !closed.get() }) {
        observeQa(selected)
    }.thenCompose { it } }.thenCompose { observed -> storage.submit { formatQa(observed) } }

    private fun selectQa(idOrPrefix: String?): QaSelection {
        val allIds = (cache.keys + journals.values.map { it.giveawayId }).distinct().sorted()
        val selectedId = idOrPrefix?.let { value ->
            val normalized = value.lowercase()
            allIds.filter { it == normalized || it.startsWith(normalized) }.singleOrNull()
        }
        if (idOrPrefix != null && selectedId == null) {
            return QaSelection(idOrPrefix, false, settings, emptyList(), emptyList(), backendDirectory.activeLeaseCount())
        }
        val selectedRecords = if (selectedId == null) cache.values.sortedBy { it.createdAtMs } else listOfNotNull(cache[selectedId])
        val selectedJournals = journals.values
            .filter { selectedId == null || it.giveawayId == selectedId }
            .sortedWith(compareBy(InventoryJournalRecord::giveawayId, { it.kind.name }))
        return QaSelection(idOrPrefix, true, settings, selectedRecords, selectedJournals, backendDirectory.activeLeaseCount())
    }

    private fun observeQa(selected: QaSelection): CompletableFuture<QaObservation> {
        val records = selected.records.iterator()
        val journals = selected.journals.iterator()
        val eligible = mutableMapOf<String, Int>()
        val observedJournals = mutableListOf<QaJournalObservation>()
        var record: GiveawayRecord? = null
        var center: Location? = null
        var participants = emptyList<GiveawayParticipant>().iterator()
        var eligibleCount = 0
        var journal: InventoryJournalRecord? = null
        var journalPlayer: Player? = null
        var inspection: InventoryPlanObservation? = null
        return nativeWork.submit({ !closed.get() }) {
            val currentRecord = record
            val currentJournal = journal
            when {
                currentRecord != null -> {
                    if (participants.hasNext()) {
                        val participant = participants.next()
                        val player = Bukkit.getPlayer(UUID.fromString(participant.playerId))?.takeIf(Player::isOnline)
                        val location = center
                        if (player != null && location != null && player.world == location.world &&
                            player.location.distanceSquared(location) <= currentRecord.radius * currentRecord.radius) eligibleCount++
                    } else {
                        eligible[currentRecord.id] = eligibleCount
                        record = null
                    }
                    false
                }
                records.hasNext() -> {
                    val next = records.next()
                    if (next.serverId != selected.settings.serverId) eligible[next.id] = -1
                    else {
                        val host = Bukkit.getPlayer(UUID.fromString(next.hostId))?.takeIf(Player::isOnline)
                        center = host?.location?.clone()
                        eligibleCount = 0
                        participants = if (host == null) emptyList<GiveawayParticipant>().iterator() else next.participants.iterator()
                        record = next
                    }
                    false
                }
                currentJournal != null -> {
                    if (journalPlayer?.isOnline != true) {
                        observedJournals += QaJournalObservation(currentJournal, false, "OFFLINE")
                        journal = null
                    } else if (requireNotNull(inspection).step()) {
                        observedJournals += QaJournalObservation(currentJournal, true, requireNotNull(inspection).state().name)
                        journal = null
                    }
                    false
                }
                journals.hasNext() -> {
                    val next = journals.next()
                    val player = runCatching { Bukkit.getPlayer(UUID.fromString(next.playerId)) }.getOrNull()?.takeIf(Player::isOnline)
                    if (player == null) observedJournals += QaJournalObservation(next, false, "OFFLINE")
                    else {
                        journal = next
                        journalPlayer = player
                        inspection = InventoryPlan.from(next.changes).observation(player)
                    }
                    false
                }
                else -> true
            }
        }.thenApply { QaObservation(selected, eligible, observedJournals) }
    }

    private fun formatQa(observed: QaObservation): List<String> {
        val selected = observed.selection
        if (!selected.found) return listOf(qaSummary.line("status" to "not_found", "query" to selected.query))
        val lines = mutableListOf(
            qaSummary.line(
                "status" to "ok",
                "server" to selected.settings.serverId,
                "records" to selected.records.size,
                "active" to selected.records.count(GiveawayRecord::isActive),
                "journals" to selected.journals.size,
                "backend_leases" to selected.backendLeases,
            ),
        )
        selected.records.forEach { record ->
            lines += qaRecord.line(
                "id" to record.displayId(),
                "status" to record.status.name,
                "owner" to record.serverId,
                "participants" to record.participants.size,
                "eligible" to observed.eligibleCounts.getValue(record.id),
                "winner" to (record.winner != null),
                "host_reserved" to record.reservesHost(),
            )
        }
        observed.journals.forEach { observation ->
            val journal = observation.journal
            lines += qaJournal.line(
                "id" to journal.giveawayId.take(8),
                "kind" to journal.kind.name,
                "status" to journal.status.name,
                "player" to if (observation.online) "ONLINE" else "OFFLINE",
                "state" to observation.state,
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
        val entries = records.iterator()
        nativeWork.submit({ !closed.get() && player.isOnline }) {
            if (!entries.hasNext()) return@submit true
            val record = entries.next()
            player.sendMessage(locale.render(MessageKey.STATUS_ENTRY, player, values(
                "host", record.hostName,
                "item", itemComponent(record),
                "seconds", ceil((record.drawAtMs - now).coerceAtLeast(0L) / 1000.0).toInt(),
                "count", record.participants.size,
            )))
            false
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
        val capture = InventoryPlan.removal(player, player.inventory.heldItemSlot, amount)
        if (capture == null) {
            inventoryLocks.remove(player.uniqueId)
            player.sendMessage(locale.render(MessageKey.START_FAILED, player))
            return
        }
        val giveawayItem = held.clone().also { it.amount = amount }
        val materialKey = giveawayItem.type.key.toString()
        val nativeBytes = giveawayItem.serializeAsBytes()
        val id = UUID.randomUUID().toString()
        val playerId = player.uniqueId.toString()
        val playerName = player.name
        val location = player.location
        val worldName = location.world.name
        val anchorX = location.x
        val anchorY = location.y
        val anchorZ = location.z
        val currentSettings = settings
        startsInFlight += id
        player.sendMessage(locale.render(MessageKey.STARTING, player))

        capture.capture(nativeWork, { !closed.get() && player.isOnline }).thenCompose { slots -> storage.submit {
            val plan = InventoryPlan.prepare(slots)
            val payload = ItemPayload.capture(materialKey, amount, nativeBytes)
            val journal = InventoryJournalRecord(
                giveawayId = id, playerId = playerId, kind = JournalKind.ESCROW,
                status = JournalStatus.PREPARED, item = payload, changes = plan.changes, createdAtMs = now,
            )
            val record = GiveawayRecord(
                id = id, revision = 0, status = GiveawayStatus.PREPARING,
                hostId = playerId, hostName = playerName, serverId = currentSettings.serverId,
                worldName = worldName, anchorX = anchorX, anchorY = anchorY, anchorZ = anchorZ,
                radius = currentSettings.radius, item = payload, createdAtMs = now, opensAtMs = now,
                drawAtMs = now + currentSettings.openSeconds * 1000L,
            ).validated(currentSettings.maximumParticipants)
            Triple(record, journalStore.write(journal), plan)
        } }.thenCompose { (record, journal, plan) ->
            nativeWork.call({ !closed.get() }) {
                journals[journalKey(id, JournalKind.ESCROW)] = journal
                check(player.isOnline && player.hasPermission("arcgiveaways.use") &&
                    player.hasPermission("arcgiveaways.start")) { "Giveaway host is no longer eligible" }
            }.thenCompose {
                hostLeases.claim(record.hostId, record.id).thenCompose { outcome ->
                    if (outcome != HostClaimOutcome.Claimed) CompletableFuture.completedFuture(false)
                    else repository.create(record)
                }
            }.thenApply { created -> Triple(record, journal, plan) to created }
        }.onMain(
            success = { (prepared, created) ->
                val (record, journal, plan) = prepared
                if (!created) {
                    startsInFlight.remove(record.id)
                    cleanupRejectedStart(record, player)
                    if (player.isOnline) player.sendMessage(locale.render(MessageKey.BUSY, player))
                    return@onMain
                }
                continueConfirmedStart(record, journal, plan, player, now)
            },
            failure = { failure ->
                startsInFlight.remove(id)
                inventoryLocks.remove(player.uniqueId)
                plugin.logger.warning("Could not create giveaway $id: ${failure.message}")
                if (player.isOnline) player.sendMessage(locale.render(MessageKey.REDIS_UNAVAILABLE, player))
                reconcileUnknownStart(id)
            },
            lockedPlayer = player.uniqueId,
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
        persistThenApply(journal, plan, player).onMain(
            success = { applied ->
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
                            } else cooldowns.remove(player.uniqueId)
                            acceptRecord(opened)
                            refreshActiveSnapshot().onMain(
                                success = {
                                    // The success acknowledgement must expose the same live record to
                                    // status and tab completion. A later cancellation must win this race.
                                    val current = cache[opened.id]
                                    if (player.isOnline && current != null && current.revision >= opened.revision &&
                                        current.status == GiveawayStatus.OPEN) {
                                        player.sendMessage(locale.render(MessageKey.STARTED, player, values("id", opened.displayId())))
                                    }
                                },
                                failure = { failure ->
                                    plugin.logger.warning("Could not publish opened giveaway ${opened.id}: ${failure.message}")
                                    if (player.isOnline) player.sendMessage(locale.render(MessageKey.GENERIC_ERROR, player))
                                },
                            )
                        } else {
                            if (player.isOnline) player.sendMessage(locale.render(MessageKey.GENERIC_ERROR, player))
                            reconcileUnknownStart(record.id)
                        }
                    },
                    failure = { failure ->
                        inventoryLocks.remove(player.uniqueId)
                        plugin.logger.severe("Giveaway ${record.id} remained PREPARING after escrow: ${failure.message}")
                        if (player.isOnline) player.sendMessage(locale.render(MessageKey.GENERIC_ERROR, player))
                        reconcileUnknownStart(record.id)
                    },
                    lockedPlayer = player.uniqueId,
                )
            },
            failure = { failure ->
                startsInFlight.remove(record.id)
                inventoryLocks.remove(player.uniqueId)
                plugin.logger.severe("Could not persist applied escrow for ${record.id}: ${failure.message}")
                if (player.isOnline) player.sendMessage(locale.render(MessageKey.GENERIC_ERROR, player))
                reconcileUnknownStart(record.id)
            },
            lockedPlayer = player.uniqueId,
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
            lockedPlayer = player.uniqueId,
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
            lockedPlayer = player.uniqueId,
        )
    }

    fun onPlayerJoin(player: Player) {
        lifecycleTasks.runLater(40) {
            adoptHostedGiveaway(player)
            repository.consumePendingJoin(player.uniqueId.toString(), clockMs()).onMain(
                success = { pending -> if (pending != null) joinTransferred(player, pending.giveawayId) },
                failure = { plugin.logger.warning("Could not consume pending giveaway join for ${player.uniqueId}") },
                lockedPlayer = player.uniqueId,
            )
            recoverFor(player)
        }
    }

    fun onPlayerQuit(player: Player) {
        pvpNoticeAt.remove(player.uniqueId)
        claimNoticeAt.remove(player.uniqueId)
        releaseGlow(player)
        val owned = hostedIds[player.uniqueId.toString()].orEmpty().iterator()
        nativeWork.submit({ !closed.get() }) {
            if (!owned.hasNext()) return@submit true
            val record = cache[owned.next()] ?: return@submit false
            if (record.serverId != settings.serverId || record.status !in setOf(GiveawayStatus.OPEN, GiveawayStatus.DRAWING)) return@submit false
            repository.update(record.id) { current ->
                if (current.serverId == settings.serverId &&
                    (current.status == GiveawayStatus.OPEN || current.status == GiveawayStatus.DRAWING)
                ) {
                    val handoffStartedAtMs = clockMs()
                    current.copy(
                        hostHandoffStartedAtMs = handoffStartedAtMs,
                        hostHandoffUntilMs = handoffStartedAtMs + settings.hostHandoffSeconds * 1_000L,
                    )
                } else null
            }.onMain(
                success = { result ->
                    when (result) {
                        is RepositoryUpdate.Changed -> result.after
                        is RepositoryUpdate.Rejected -> result.current
                        else -> null
                    }?.let(::acceptRecord)
                },
                failure = { plugin.logger.warning("Could not begin host handoff for giveaway ${record.id}: ${it.message}") },
                lockedPlayer = player.uniqueId,
            )
            false
        }
    }

    private fun adoptHostedGiveaway(player: Player) {
        val playerId = player.uniqueId.toString()
        repository.hostGiveawayId(playerId).onMain(
            success = { giveawayId ->
                if (giveawayId == null || !player.isOnline) return@onMain
                val destination = player.location.clone()
                val destinationWorld = destination.world.name
                val destinationX = destination.x
                val destinationY = destination.y
                val destinationZ = destination.z
                val arrivedAtMs = clockMs()
                repository.update(giveawayId) { current ->
                    if (current.hostId != playerId || !current.reservesHost()) return@update null
                    if (current.serverId == settings.serverId && current.hostHandoffUntilMs == null) return@update null
                    val pausedMillis = current.hostHandoffStartedAtMs
                        ?.let { startedAtMs -> (arrivedAtMs - startedAtMs).coerceAtLeast(0L) }
                        ?: 0L
                    current.copy(
                        serverId = settings.serverId,
                        worldName = destinationWorld,
                        anchorX = destinationX,
                        anchorY = destinationY,
                        anchorZ = destinationZ,
                        drawAtMs = current.drawAtMs + pausedMillis,
                        drawingEndsAtMs = current.drawingEndsAtMs?.plus(pausedMillis),
                        hostHandoffStartedAtMs = null,
                        hostHandoffUntilMs = null,
                    )
                }.onMain(
                    success = { result ->
                        when (result) {
                            is RepositoryUpdate.Changed -> result.after
                            is RepositoryUpdate.Rejected -> result.current
                            else -> null
                        }?.let(::acceptRecord)
                    },
                    failure = {
                        plugin.logger.warning("Could not adopt hosted giveaway $giveawayId on ${settings.serverId}: ${it.message}")
                    },
                )
            },
            failure = { plugin.logger.warning("Could not inspect hosted giveaway for $playerId: ${it.message}") },
        )
    }

    fun follow(player: Player, idOrPrefix: String) {
        val id = resolveId(idOrPrefix)
        if (id == null) {
            player.sendMessage(locale.render(MessageKey.NOT_FOUND, player))
            return
        }
        repository.load(id).onMain(
            success = { record ->
                if (record == null || record.status !in setOf(GiveawayStatus.OPEN, GiveawayStatus.DRAWING)) {
                    player.sendMessage(locale.render(if (record == null) MessageKey.NOT_FOUND else MessageKey.NOT_OPEN, player))
                    return@onMain
                }
                if (record.participants.none { it.playerId == player.uniqueId.toString() }) {
                    player.sendMessage(locale.render(MessageKey.FOLLOW_DENIED, player))
                    return@onMain
                }
                if (record.serverId != settings.serverId) transferForJoin(player, record)
                else teleportAndJoin(player, record)
            },
            failure = { player.sendMessage(locale.render(MessageKey.REDIS_UNAVAILABLE, player)) },
        )
    }

    fun cancel(player: Player, idOrPrefix: String?) {
        val record = if (idOrPrefix == null) hostedIds[player.uniqueId.toString()]?.firstOrNull()?.let(cache::get)
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
        val ids = pendingIds[player.uniqueId.toString()] ?: run {
            player.sendMessage(locale.render(MessageKey.NOTHING_TO_CLAIM, player))
            return
        }
        val candidates = ids.iterator()
        val local = mutableListOf<GiveawayRecord>()
        var remote: GiveawayRecord? = null
        nativeWork.submit({ !closed.get() && player.isOnline }) {
            if (!candidates.hasNext()) true else {
                val record = cache[candidates.next()]
                if (record != null && record.status in setOf(GiveawayStatus.AWAITING_DELIVERY, GiveawayStatus.AWAITING_REFUND)) {
                    if (record.serverId == settings.serverId) local += record else if (remote == null) remote = record
                }
                false
            }
        }.thenCompose { nativeWork.call({ !closed.get() && player.isOnline }) {
            val destination = remote
            when {
                destination != null -> {
                    player.sendMessage(locale.render(MessageKey.CLAIM_TRANSFERRING, player, values(
                        "server", locale.serverName(destination.serverId, player))))
                    sendToServer(player, destination.serverId)
                }
                local.isEmpty() -> player.sendMessage(locale.render(MessageKey.NOTHING_TO_CLAIM, player))
                else -> {
                    player.sendMessage(locale.render(MessageKey.CLAIM_RETRYING, player))
                    val deliveries = local.iterator()
                    nativeWork.submit({ !closed.get() && player.isOnline }) {
                        if (!deliveries.hasNext()) true else { attemptPending(deliveries.next()); false }
                    }
                }
            }
        } }
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
        if (hostLocation.world != player.world) {
            player.sendMessage(locale.render(MessageKey.WRONG_WORLD, player, values("world", host.world.name)))
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
                val alreadyJoined = record?.participants?.any { it.playerId == player.uniqueId.toString() } == true
                val canArrive = record?.status == GiveawayStatus.OPEN ||
                    (record?.status == GiveawayStatus.DRAWING && alreadyJoined)
                if (record == null || !canArrive || record.serverId != settings.serverId) {
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
            ?.takeIf(Player::isOnline)
        if (host == null) {
            player.sendMessage(locale.render(MessageKey.NOT_OPEN, player))
            return
        }
        travel.teleport(player, travel.arrivalNear(host)).onMain(
            success = { teleported ->
                if (teleported && player.isOnline) {
                    if (record.participants.any { it.playerId == player.uniqueId.toString() }) {
                        player.sendMessage(locale.render(MessageKey.FOLLOW_ARRIVED, player))
                    } else {
                        joinLocal(player, record)
                    }
                }
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
        if (publishedSnapshotVersion != cacheVersion.get()) refreshActiveSnapshot()
        if (tickPending) return
        tickPending = true
        val records = cache.values.iterator()
        nativeWork.submit({ !closed.get() }) {
            if (!records.hasNext()) return@submit true
            val record = records.next()
            val now = clockMs()
            when {
                record.serverId == settings.serverId && record.status == GiveawayStatus.PREPARING -> recoverPreparing(record)
                record.serverId == settings.serverId && record.hostHandoffUntilMs != null && now >= record.hostHandoffUntilMs -> expireHostHandoff(record)
                record.serverId == settings.serverId && record.status == GiveawayStatus.OPEN &&
                    record.hostHandoffUntilMs == null && now >= record.drawAtMs -> beginDraw(record)
                record.serverId == settings.serverId && record.status == GiveawayStatus.DRAWING &&
                    record.hostHandoffUntilMs == null && now >= (record.drawingEndsAtMs ?: Long.MAX_VALUE) -> selectWinner(record)
                record.serverId == settings.serverId && record.status in setOf(GiveawayStatus.AWAITING_DELIVERY, GiveawayStatus.AWAITING_REFUND) -> attemptPending(record)
            }
            if (record.status in setOf(GiveawayStatus.OPEN, GiveawayStatus.DRAWING)) {
                updateEffects(record, now)
            } else {
                removeBossBar(record.id)
                clearVisualRuntime(record.id)
            }
            if (record.status == GiveawayStatus.COMPLETED || record.status == GiveawayStatus.CANCELLED) retireTerminalDelivery(record)
            cleanupTerminalRecord(record, now)
            false
        }.whenComplete { _, _ -> tickPending = false }
        reconcileGlow()
    }

    private fun beginDraw(record: GiveawayRecord) {
        if (!drawsInFlight.add(record.id)) return
        eligibleParticipantsAsync(record).thenCompose { eligible -> repository.update(record.id) { current ->
            if (current.status == GiveawayStatus.OPEN && current.hostHandoffUntilMs == null && clockMs() >= current.drawAtMs) {
                engine.beginDraw(current, eligible, clockMs(), settings.drawingSeconds * 1000L)
            } else null
        } }.whenComplete { _, _ -> drawsInFlight.remove(record.id) }.onMain(
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

    private fun expireHostHandoff(record: GiveawayRecord) {
        repository.update(record.id) { current ->
            val deadline = current.hostHandoffUntilMs
            if (current.serverId == settings.serverId && deadline != null && clockMs() >= deadline &&
                (current.status == GiveawayStatus.OPEN || current.status == GiveawayStatus.DRAWING)
            ) {
                engine.cancel(current, clockMs(), "host_handoff_timeout")
            } else null
        }.onMain(
            success = { result ->
                val changed = (result as? RepositoryUpdate.Changed)?.after ?: return@onMain
                acceptRecord(changed)
                attemptPending(changed)
            },
            failure = { plugin.logger.warning("Could not expire host handoff for giveaway ${record.id}: ${it.message}") },
        )
    }

    private fun selectWinner(record: GiveawayRecord) {
        repository.update(record.id) { current ->
            if (current.status == GiveawayStatus.DRAWING && current.hostHandoffUntilMs == null &&
                clockMs() >= (current.drawingEndsAtMs ?: Long.MAX_VALUE)
            ) engine.selectWinner(current) else null
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
        if (!inventoryLocks.add(uuid)) return
        val existing = journals[key]
        val prepared = if (existing != null) CompletableFuture.completedFuture(existing) else {
            val item = restoreItem(record.item)
            val capture = item?.let { InventoryPlan.delivery(player, it) }
            if (capture == null) {
                inventoryLocks.remove(uuid)
                if (item != null) notifyClaimPending(player)
                return
            }
            val preparedAt = clockMs()
            capture.capture(nativeWork, { !closed.get() && player.isOnline }).thenCompose { slots -> storage.submit {
                val plan = InventoryPlan.prepare(slots)
                journalStore.write(InventoryJournalRecord(
                    giveawayId = record.id, playerId = uuid.toString(), kind = kind,
                    status = JournalStatus.PREPARED, item = record.item, changes = plan.changes,
                    createdAtMs = preparedAt,
                ))
            } }
        }
        prepared.thenCompose { committed ->
            nativeWork.call({ !closed.get() }) {
                journals[key] = committed
                convergeJournal(committed, player)
            }.thenCompose { it }
        }.onMain(
            success = { applied ->
                journals[key] = applied
                finalizeDelivery(record, player, kind, applied)
            },
            failure = { failure ->
                inventoryLocks.remove(uuid)
                plugin.logger.severe("Could not converge $kind journal for ${record.id}: ${failure.message}")
            },
            lockedPlayer = player.uniqueId,
        )
    }

    /** Caller holds the player's inventory fence until all native and durable stages settle. */
    private fun convergeJournal(journal: InventoryJournalRecord, player: Player): CompletableFuture<InventoryJournalRecord> {
        check(player.isOnline) { "Journal owner is offline" }
        val key = journalKey(journal.giveawayId, journal.kind)
        val plan = InventoryPlan.from(journal.changes)
        return when (plan.state(player)) {
            PlanState.BEFORE -> {
                recoveryIncidents.clear(key)
                persistThenApply(journal, plan, player)
            }
            PlanState.AFTER -> {
                recoveryIncidents.clear(key)
                if (journal.status == JournalStatus.APPLIED) CompletableFuture.completedFuture(journal)
                else storage.submit { journalStore.write(journal.copy(status = JournalStatus.APPLIED)) }
            }
            PlanState.AMBIGUOUS -> {
                if (recoveryIncidents.markAmbiguous(key)) {
                    plugin.logger.severe("Ambiguous ${journal.kind} inventory journal for giveaway ${journal.giveawayId}; refusing blind retry")
                }
                CompletableFuture.failedFuture(IllegalStateException("Inventory journal does not match before or after state"))
            }
        }
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
                val terminal = when (result) {
                    is RepositoryUpdate.Changed -> result.after
                    is RepositoryUpdate.Rejected -> result.current
                    else -> null
                }
                if (terminal == null || (kind == JournalKind.PRIZE && terminal.status != GiveawayStatus.COMPLETED) ||
                    (kind == JournalKind.REFUND && terminal.status != GiveawayStatus.CANCELLED)) {
                    inventoryLocks.remove(player.uniqueId)
                    return@onMain
                }
                acceptRecord(terminal)
                retireJournal(appliedJournal).onMain(
                    success = { retired ->
                        inventoryLocks.remove(player.uniqueId)
                        if (!retired) return@onMain
                        val key = journalKey(record.id, kind)
                        journals.remove(key, appliedJournal)
                        recoveryIncidents.clear(key)
                        retireTerminalEscrow(terminal)
                        repository.releaseHost(record.hostId, record.id)
                        // A retry of a terminal Redis result retires the journal but never celebrates twice.
                        if (kind == JournalKind.PRIZE && result is RepositoryUpdate.Changed && player.isOnline) {
                            ArcProductTelemetryBridge.itemGranted(player.uniqueId, "giveaway:${record.id}:${player.uniqueId}")
                            player.sendMessage(locale.render(MessageKey.DELIVERED, player))
                            celebrateWinner(player, record)
                        }
                    },
                    failure = { failure ->
                        inventoryLocks.remove(player.uniqueId)
                        plugin.logger.warning("Could not retire $kind for giveaway ${record.id}: ${failure.message}")
                    },
                    lockedPlayer = player.uniqueId,
                )
            },
            failure = { failure ->
                inventoryLocks.remove(player.uniqueId)
                plugin.logger.warning("Could not finalize $kind for giveaway ${record.id}: ${failure.message}")
            },
            lockedPlayer = player.uniqueId,
        )
    }

    private fun recoverPreparing(record: GiveawayRecord) {
        if (record.id in startsInFlight) return
        val host = Bukkit.getPlayer(UUID.fromString(record.hostId))?.takeIf { it.isOnline } ?: return
        val key = journalKey(record.id, JournalKind.ESCROW)
        val journal = journals[key] ?: return
        if (!inventoryLocks.add(host.uniqueId)) return
        convergeJournal(journal, host).onMain(
            success = { applied ->
                journals[key] = applied
                repository.update(record.id) { current ->
                    if (current.status == GiveawayStatus.PREPARING) engine.open(current) else null
                }.onMain(
                    success = { result ->
                        inventoryLocks.remove(host.uniqueId)
                        when (result) {
                            is RepositoryUpdate.Changed -> result.after
                            is RepositoryUpdate.Rejected -> result.current
                            else -> null
                        }?.let(::acceptRecord)
                    },
                    failure = { failure ->
                        inventoryLocks.remove(host.uniqueId)
                        plugin.logger.warning("Could not recover PREPARING giveaway ${record.id}: ${failure.message}")
                    },
                    lockedPlayer = host.uniqueId,
                )
            },
            failure = { failure ->
                inventoryLocks.remove(host.uniqueId)
                plugin.logger.severe("Could not converge escrow journal for ${record.id}: ${failure.message}")
            },
            lockedPlayer = host.uniqueId,
        )
    }

    private fun recoverFor(player: Player) {
        val records = pendingIds[player.uniqueId.toString()].orEmpty().iterator()
        nativeWork.submit({ !closed.get() && player.isOnline }) {
            if (!records.hasNext()) true else {
                cache[records.next()]?.let { record ->
                    if (record.status == GiveawayStatus.PREPARING) recoverPreparing(record) else attemptPending(record)
                }
                false
            }
        }
    }

    private fun eligibleParticipantsAsync(record: GiveawayRecord): CompletableFuture<List<GiveawayParticipant>> {
        val host = Bukkit.getPlayer(UUID.fromString(record.hostId))?.takeIf(Player::isOnline)
            ?: return CompletableFuture.completedFuture(emptyList())
        val center = host.location.clone()
        val participants = record.participants.iterator()
        val eligible = mutableListOf<GiveawayParticipant>()
        return nativeWork.submit({ !closed.get() && cache[record.id]?.revision == record.revision }) {
            if (!participants.hasNext()) true else {
                val participant = participants.next()
                val player = Bukkit.getPlayer(UUID.fromString(participant.playerId))?.takeIf(Player::isOnline)
                if (player != null && player.world == center.world && player.location.distanceSquared(center) <= record.radius * record.radius) {
                    eligible += participant
                }
                false
            }
        }.thenApply { eligible }
    }

    private fun updateEffects(record: GiveawayRecord, now: Long) {
        val participants = if (record.status == GiveawayStatus.DRAWING) record.drawingCandidates else record.participants
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
            syncBossViewers(record.id, bar)
        } else {
            removeBossBar(record.id)
        }
        if (record.status == GiveawayStatus.OPEN) {
            val seconds = ceil((record.drawAtMs - now).coerceAtLeast(0L) / 1000.0).toInt()
            if (countdownSecond.put(record.id, seconds) != seconds) {
                forParticipantViewer(record, participants) { player ->
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
        } else if (record.status == GiveawayStatus.DRAWING && record.drawingCandidates.isNotEmpty()) {
            val candidate = record.drawingCandidates[(now / 250L % record.drawingCandidates.size).toInt()].playerName
            forParticipantViewer(record, participants) { player ->
                if (settings.titlesEnabled) presentation.showTitle(player, Title.title(
                    locale.render(MessageKey.DRAWING_TITLE, player),
                    locale.render(MessageKey.DRAWING_SUBTITLE, player, values("candidate", candidate)),
                    Title.Times.times(Duration.ZERO, Duration.ofMillis(350), Duration.ZERO),
                ))
                play(player, "minecraft:block.note_block.hat", 0.7f, 1.2f)
            }
        }
        updateWorldScene(record, now)
    }

    private fun updateWorldScene(record: GiveawayRecord, now: Long) {
        if (record.serverId != settings.serverId || record.hostHandoffUntilMs != null) {
            clearVisualRuntime(record.id)
            return
        }
        val host = runCatching { Bukkit.getPlayer(UUID.fromString(record.hostId)) }.getOrNull()
            ?.takeIf(Player::isOnline)
            ?: return
        val effects = settings.visualEffects
        val remainingSeconds = ceil((record.drawAtMs - now).coerceAtLeast(0L) / 1000.0).toInt()
        val (scene, stage) = when {
            record.status == GiveawayStatus.DRAWING -> GiveawayVisualScene.DRAWING to effects.drawing
            remainingSeconds <= effects.countdownThresholdSeconds -> GiveawayVisualScene.COUNTDOWN to effects.countdown
            else -> GiveawayVisualScene.AMBIENT to effects.ambient
        }
        val sceneChanged = visualScene.put(record.id, scene) != scene
        if (!stage.enabled) return

        val center = host.location.clone()
        val pulseIntervalMs = scaledInterval(stage.intervalSeconds * 1_000L, effects.intensity)
        val lastPulse = visualPulseAt[record.id]
        if (settings.particlesEnabled && stage.particleCount > 0 &&
            (sceneChanged || lastPulse == null || now - lastPulse >= pulseIntervalMs)
        ) {
            visualPulseAt[record.id] = now
            renderScene(
                record.id,
                center,
                scene,
                GiveawaySceneSpec(scaledCount(stage.particleCount, effects.intensity), stage.radius),
            ) { cache.containsKey(record.id) && visualScene[record.id] == scene && host.isOnline && host.world === center.world }
        }

        val fireworkIntervalMs = scaledInterval(stage.fireworkIntervalSeconds * 1_000L, effects.intensity)
        val lastFirework = visualFireworkAt[record.id]
        if (settings.fireworksEnabled && stage.fireworkIntervalSeconds > 0 &&
            (sceneChanged || lastFirework == null || now - lastFirework >= fireworkIntervalMs)
        ) {
            visualFireworkAt[record.id] = now
            val variant = visualFireworkVariant.getOrDefault(record.id, 0)
            visualFireworkVariant[record.id] = variant + 1
            presentation.launchFirework(center, scene, effects.fireworkStyle, variant)
        }
    }

    private fun updateItemDisplays() {
        if (displayUpdatePending) return
        displayUpdatePending = true
        val generation = visualGeneration
        val records = cache.values.iterator()
        val desiredIds = mutableSetOf<String>()
        var stale: Iterator<String>? = null
        nativeWork.submit({ !closed.get() && generation == visualGeneration }) {
            val displaySettings = settings.visualEffects.itemDisplay
            if (records.hasNext() && displaySettings.enabled) {
                val record = records.next()
            if (record.serverId != settings.serverId ||
                record.status !in setOf(GiveawayStatus.OPEN, GiveawayStatus.DRAWING) ||
                record.hostHandoffUntilMs != null
            ) return@submit false
            val host = runCatching { Bukkit.getPlayer(UUID.fromString(record.hostId)) }.getOrNull()
                ?.takeIf(Player::isOnline)
                ?: return@submit false
            desiredIds += record.id
            val yaw = itemDisplayYaw.getOrDefault(record.id, 0f)
            val pose = GiveawayItemDisplayPose(
                location = host.location.clone().apply {
                    y = host.boundingBox.maxY + displaySettings.heightAboveHead
                    setYaw(0f)
                    setPitch(0f)
                },
                yawDegrees = yaw,
            )
            val current = itemDisplays[record.id]
            if (current == null) {
                val item = restoreItem(record.item) ?: return@submit false
                itemDisplays[record.id] = presentation.createItemDisplay(item, pose, displaySettings.scale.toFloat())
            } else if (!current.update(pose)) {
                removeItemDisplay(record.id)
            }
            itemDisplayYaw[record.id] =
                (yaw + 360f * ITEM_DISPLAY_UPDATE_TICKS.toFloat() / displaySettings.rotationPeriodTicks).mod(360f)
                false
            } else {
                // The display map may change while this job yields; traverse a stable concurrent key view.
                val removals = stale ?: itemDisplays.keys.iterator().also { stale = it }
                if (!removals.hasNext()) true else {
                    val id = removals.next()
                    if (id !in desiredIds) removeItemDisplay(id)
                    false
                }
            }
        }.whenComplete { _, _ -> displayUpdatePending = false }
    }

    private fun reconcileGlow() {
        if (glowPending) return
        glowPending = true
        val generation = visualGeneration
        val glow = settings.visualEffects.glow
        storage.submit {
            val desired = mutableSetOf<UUID>()
            if (glow.enabled) cache.values.forEach { record ->
                if (record.status == GiveawayStatus.OPEN || record.status == GiveawayStatus.DRAWING) {
                    if (glow.host) runCatching { UUID.fromString(record.hostId) }.getOrNull()?.let(desired::add)
                    if (glow.participants) {
                        val participants = if (record.status == GiveawayStatus.DRAWING) record.drawingCandidates else record.participants
                        participants.mapNotNullTo(desired) { runCatching { UUID.fromString(it.playerId) }.getOrNull() }
                    }
                }
            }
            desired
        }.thenCompose { desired ->
            nativeWork.call({ !closed.get() && generation == visualGeneration }) {
                val additions = desired.iterator()
                val removals = originalGlow.keys.iterator()
                nativeWork.submit({ !closed.get() && generation == visualGeneration }) {
                    if (additions.hasNext()) {
                        val id = additions.next()
                        val player = Bukkit.getPlayer(id)?.takeIf(Player::isOnline)
                        if (player != null) {
                            originalGlow.putIfAbsent(id, presentation.isGlowing(player))
                            presentation.setGlowing(player, true)
                        }
                        false
                    } else if (removals.hasNext()) {
                        val id = removals.next()
                        if (id !in desired) Bukkit.getPlayer(id)?.takeIf(Player::isOnline)?.let(::releaseGlow)
                            ?: originalGlow.remove(id)
                        false
                    } else true
                }
            }.thenCompose { it }
        }.whenComplete { _, _ -> glowPending = false }
    }

    private fun releaseGlow(player: Player) {
        val original = originalGlow.remove(player.uniqueId) ?: return
        presentation.setGlowing(player, original)
    }

    private fun acceptRecord(record: GiveawayRecord) {
        val previous = synchronized(cacheMutationLock) {
            val current = cache[record.id]
            if (current != null && current.revision >= record.revision) return
            val previous = cache.put(record.id, record)
            activeLeaseGauge.transition(previous?.isActive() == true, record.isActive())
            updateIndexes(previous, record)
            previous
        }
        refreshActiveSnapshot()
        if (record.status == GiveawayStatus.OPEN && record.id !in announced) {
            announced += record.id
            announce(record)
        }
        if (previous != null && previous.serverId != record.serverId && record.status == GiveawayStatus.OPEN) {
            announceHostMoved(record)
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
            clearVisualRuntime(record.id)
        }
    }

    private fun announce(record: GiveawayRecord) {
        forOnlineViewer { player ->
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
        forOnlineViewer { player ->
            player.sendMessage(locale.render(MessageKey.JOINED_NETWORK, player, values(
                "player", participant.playerName,
                "host", record.hostName,
                "item", itemComponent(record),
                "count", record.participants.size,
            )))
            play(player, "minecraft:block.note_block.chime", 0.45f, 1.35f)
        }
    }

    private fun announceHostMoved(record: GiveawayRecord) {
        forParticipantViewer(record, record.participants) { player ->
            val replacements = values(
                "host", record.hostName,
                "server", locale.serverName(record.serverId, player),
            )
            val body = locale.render(MessageKey.HOST_MOVED, player, replacements)
            val button = locale.render(MessageKey.FOLLOW_BUTTON, player)
                .clickEvent(ClickEvent.runCommand("/giveaway follow ${record.id}"))
                .hoverEvent(HoverEvent.showText(locale.render(MessageKey.FOLLOW_HOVER, player, replacements)))
            player.sendMessage(GiveawayPresentation.chatAnnouncement(body, button))
            if (settings.actionBarEnabled) {
                player.sendActionBar(locale.render(MessageKey.HOST_MOVED_ACTIONBAR, player, replacements))
            }
            if (settings.titlesEnabled) {
                presentation.showTitle(player, Title.title(
                    locale.render(MessageKey.HOST_MOVED_TITLE, player, replacements),
                    locale.render(MessageKey.HOST_MOVED_SUBTITLE, player, replacements),
                    Title.Times.times(Duration.ofMillis(150), Duration.ofSeconds(2), Duration.ofMillis(350)),
                ))
            }
            play(player, "minecraft:block.note_block.bell", 1f, 1.25f)
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
        forOnlineViewer { it.sendMessage(locale.render(key, it, replacements)) }
    }

    private fun renderScene(id: String, center: Location, scene: GiveawayVisualScene, spec: GiveawaySceneSpec, current: () -> Boolean) {
        val previous = sceneWork[id]
        if (previous != null && !previous.second.isDone && previous.first == scene) return
        previous?.second?.cancel(false)
        val generation = visualGeneration
        val steps = presentation.sceneSteps(center, scene, spec)
        val future = nativeWork.submit({ !closed.get() && generation == visualGeneration && current() }) {
            if (steps.hasNext()) { steps.next(); false } else true
        }
        sceneWork[id] = scene to future
        future.whenComplete { _, failure ->
            if (sceneWork[id]?.second === future) sceneWork.remove(id)
            if (failure != null && failure !is java.util.concurrent.CancellationException) {
                plugin.logger.log(java.util.logging.Level.WARNING, "Giveaway $scene presentation failed", failure)
            }
        }
    }

    private fun celebrateWinner(player: Player, record: GiveawayRecord) {
        if (settings.titlesEnabled) presentation.showTitle(player, Title.title(
            locale.render(MessageKey.WINNER_TITLE, player),
            locale.render(MessageKey.WINNER_SUBTITLE, player, values("item", itemComponent(record))),
            Title.Times.times(Duration.ofMillis(200), Duration.ofSeconds(3), Duration.ofMillis(500)),
        ))
        play(player, "minecraft:ui.toast.challenge_complete", 1f, 1f)
        val effects = settings.visualEffects
        if (!effects.winner.enabled) return
        if (settings.particlesEnabled && effects.winner.particleCount > 0) {
            renderScene(
                record.id,
                player.location.clone(),
                GiveawayVisualScene.WINNER,
                GiveawaySceneSpec(scaledCount(effects.winner.particleCount, effects.intensity), effects.winner.radius),
            ) { player.isOnline }
        }
        if (settings.fireworksEnabled) {
            val fireworkCount = scaledCount(effects.winner.fireworkCount, effects.intensity)
            val fireworkInterval = scaledInterval(effects.winner.fireworkIntervalTicks.toLong(), effects.intensity)
            repeat(fireworkCount) { index ->
                lifecycleTasks.runLater(index * fireworkInterval) firework@{
                    if (!player.isOnline) return@firework
                    presentation.launchFirework(
                        player.location.clone(),
                        GiveawayVisualScene.WINNER,
                        effects.fireworkStyle,
                        index,
                    )
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
        if (reconcilePending) return
        reconcilePending = true
        repository.loadAll().thenApply { records -> records to records.mapTo(mutableSetOf()) { it.id } }
            .thenCompose { (records, ids) ->
                nativeWork.call({ !closed.get() }) {
                    val additions = records.iterator()
                    val removals = cache.keys.iterator()
                    val recoveries = journals.values.iterator()
                    nativeWork.submit({ !closed.get() }) {
                        when {
                            additions.hasNext() -> acceptRecord(additions.next())
                            removals.hasNext() -> {
                                val id = removals.next()
                                if (id !in ids) { removeCachedRecord(id); removeBossBar(id) }
                            }
                            recoveries.hasNext() -> {
                                val journal = recoveries.next()
                                if (journal.kind == JournalKind.ESCROW && journal.giveawayId !in ids) reconcileOrphanEscrow(journal)
                            }
                            else -> return@submit true
                        }
                        false
                    }
                }.thenCompose { it }
            }.whenComplete { _, failure ->
                reconcilePending = false
                if (failure != null && !closed.get()) plugin.logger.warning("Giveaway Redis reconciliation failed: ${failure.message}")
            }
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

    private fun cleanupTerminalRecord(record: GiveawayRecord, now: Long) {
        val retention = settings.terminalRetentionMinutes * 60_000L
        if (record.isActive() || record.terminalAtMs == null || now - record.terminalAtMs <= retention) return
        repository.delete(record).onMain(
            success = { deleted -> if (deleted) { removeCachedRecord(record.id); removeBossBar(record.id) } },
            failure = { plugin.logger.warning("Could not delete giveaway ${record.id}: ${it.message}") },
        )
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
        if (!inventoryLocks.add(player.uniqueId)) return
        if (InventoryPlan.from(journal.changes).state(player) != PlanState.BEFORE) {
            inventoryLocks.remove(player.uniqueId)
            return
        }
        val key = journalKey(journal.giveawayId, journal.kind)
        storage.submit { journalStore.acknowledgeExactly(journal) }.onMain(
            success = { outcome ->
                inventoryLocks.remove(player.uniqueId)
                if (outcome != DurableAcknowledgementOutcome.CONTENT_MISMATCH) {
                    journals.remove(key, journal)
                    recoveryIncidents.clear(key)
                }
            },
            failure = { failure ->
                inventoryLocks.remove(player.uniqueId)
                plugin.logger.warning("Could not retire orphan escrow ${journal.giveawayId}: ${failure.message}")
            },
            lockedPlayer = player.uniqueId,
        )
    }

    private fun resolveId(value: String): String? {
        val normalized = value.lowercase()
        val first = recordIds.ceiling(normalized)
        if (first != null && first.startsWith(normalized)) {
            val second = recordIds.higher(first)
            if (second == null || !second.startsWith(normalized)) return first
        }
        return runCatching { UUID.fromString(value).toString() }.getOrNull()
    }

    private fun removeCachedRecord(id: String): GiveawayRecord? {
        val removed = synchronized(cacheMutationLock) {
            cache.remove(id).also { previous ->
                activeLeaseGauge.transition(previous?.isActive() == true, currentActive = false)
                updateIndexes(previous, null)
                announced.remove(id)
                winnerAnnounced.remove(id)
                countdownSecond.remove(id)
            }
        }
        clearVisualRuntime(id)
        refreshActiveSnapshot()
        return removed
    }

    private fun updateIndexes(before: GiveawayRecord?, after: GiveawayRecord?) {
        fun owner(record: GiveawayRecord): String? = when (record.status) {
            GiveawayStatus.PREPARING, GiveawayStatus.AWAITING_REFUND -> record.hostId
            GiveawayStatus.AWAITING_DELIVERY -> record.winner?.playerId
            else -> null
        }
        fun membership(record: GiveawayRecord, delta: Int) {
            if (record.serverId != settings.serverId || record.status !in setOf(GiveawayStatus.OPEN, GiveawayStatus.DRAWING)) return
            fun count(id: String) {
                val updated = (protectedPlayers[id] ?: 0) + delta
                if (updated == 0) protectedPlayers.remove(id) else protectedPlayers[id] = updated
            }
            count(record.hostId)
            record.participants.forEach { count(it.playerId) }
        }
        before?.let { record ->
            recordIds.remove(record.id)
            hostedIds[record.hostId]?.let { ids -> ids.remove(record.id); if (ids.isEmpty()) hostedIds.remove(record.hostId) }
            owner(record)?.let { player -> pendingIds[player]?.let { ids -> ids.remove(record.id); if (ids.isEmpty()) pendingIds.remove(player) } }
            participantsGauge.addAndGet(-record.participants.size)
            membership(record, -1)
        }
        after?.let { record ->
            recordIds.add(record.id)
            if (record.isActive()) hostedIds.getOrPut(record.hostId) { ConcurrentHashMap.newKeySet() }.add(record.id)
            owner(record)?.let { player -> pendingIds.getOrPut(player) { ConcurrentHashMap.newKeySet() }.add(record.id) }
            participantsGauge.addAndGet(record.participants.size)
            membership(record, 1)
        }
        cacheVersion.incrementAndGet()
    }

    private fun refreshActiveSnapshot(): CompletableFuture<Unit> {
        snapshotRefresh.get()?.let { return it.copy() }
        if (closed.get()) return CompletableFuture.failedFuture(java.util.concurrent.CancellationException("Giveaway service is closed"))
        if (publishedSnapshotVersion == cacheVersion.get()) return CompletableFuture.completedFuture(Unit)
        val completion = CompletableFuture<Unit>()
        if (!snapshotRefresh.compareAndSet(null, completion)) return refreshActiveSnapshot()
        continueSnapshotRefresh(completion)
        return completion.copy()
    }

    /** One coalesced worker snapshot; stale candidates retry outside the native queue. */
    private fun continueSnapshotRefresh(completion: CompletableFuture<Unit>) {
        if (completion.isDone) return
        val version = cacheVersion.get()
        storage.submit {
            cache.values.filter { it.status == GiveawayStatus.OPEN || it.status == GiveawayStatus.DRAWING }
                .sortedBy { it.drawAtMs }
        }.thenCompose { candidate -> nativeWork.call({ !closed.get() && !completion.isDone }) {
            if (cacheVersion.get() != version) false else {
                activeSnapshot = candidate
                publishedSnapshotVersion = version
                true
            }
        } }.whenComplete { published, failure ->
            if (failure != null) {
                snapshotRefresh.compareAndSet(completion, null)
                completion.completeExceptionally(failure)
            } else if (published == true) {
                snapshotRefresh.compareAndSet(completion, null)
                completion.complete(Unit)
            } else continueSnapshotRefresh(completion)
        }
    }

    private fun clearVisualRuntime(id: String) {
        visualScene.remove(id)
        visualPulseAt.remove(id)
        visualFireworkAt.remove(id)
        visualFireworkVariant.remove(id)
        removeItemDisplay(id)
    }

    private fun removeItemDisplay(id: String) {
        itemDisplays.remove(id)?.let { handle -> runCatching(handle::remove) }
        itemDisplayYaw.remove(id)
    }

    private fun clearAllItemDisplays() {
        val detached = itemDisplays.values.iterator()
        itemDisplays = ConcurrentHashMap()
        itemDisplayYaw = mutableMapOf()
        retireVisuals(sequence {
            for (display in detached) { display.remove(); yield(Unit) }
        }.iterator())
    }

    private fun scaledCount(base: Int, intensity: Double): Int = when {
        base <= 0 -> 0
        else -> (base * intensity).roundToInt().coerceAtLeast(1)
    }

    private fun scaledInterval(base: Long, intensity: Double): Long = when {
        base <= 0L -> 0L
        else -> ceil(base / intensity).toLong().coerceAtLeast(1L)
    }


    private fun forOnlineViewer(operation: (Player) -> Unit) {
        val players = Bukkit.getOnlinePlayers().iterator()
        nativeWork.submit({ !closed.get() }) {
            if (!players.hasNext()) true else {
                val player = players.next()
                if (player.isOnline) operation(player)
                false
            }
        }
    }

    private fun forParticipantViewer(record: GiveawayRecord, participants: List<GiveawayParticipant>, operation: (Player) -> Unit) {
        val viewers = participants.iterator()
        nativeWork.submit({ !closed.get() && cache[record.id]?.revision == record.revision }) {
            if (!viewers.hasNext()) true else {
                val participant = viewers.next()
                Bukkit.getPlayer(UUID.fromString(participant.playerId))?.takeIf(Player::isOnline)?.let(operation)
                false
            }
        }
    }

    private fun syncBossViewers(id: String, bar: BossBar) {
        if (!bossSyncPending.add(id)) return
        val players = Bukkit.getOnlinePlayers().iterator()
        val desired = mutableSetOf<UUID>()
        val current = bossViewers.getOrPut(id) { ConcurrentHashMap.newKeySet() }
        var stale: Iterator<UUID>? = null
        nativeWork.submit({ !closed.get() && bossBars[id] === bar }) {
            if (players.hasNext()) {
                val player = players.next()
                if (player.isOnline) {
                    desired += player.uniqueId
                    if (current.add(player.uniqueId)) player.showBossBar(bar)
                }
                false
            } else {
                val removals = stale ?: current.iterator().also { stale = it }
                if (!removals.hasNext()) true else {
                    val uuid = removals.next()
                    if (uuid !in desired && current.remove(uuid)) Bukkit.getPlayer(uuid)?.hideBossBar(bar)
                    false
                }
            }
        }.whenComplete { _, _ -> bossSyncPending.remove(id) }
    }

    private fun removeBossBar(id: String) {
        val bar = bossBars.remove(id) ?: return
        val viewers = bossViewers.remove(id).orEmpty().iterator()
        val server = plugin.server
        // A normal record completion can immediately precede disable. Retain its cleanup too.
        retireVisuals(sequence {
            for (viewer in viewers) { server.getPlayer(viewer)?.hideBossBar(bar); yield(Unit) }
        }.iterator())
    }

    private fun itemComponent(record: GiveawayRecord): Component {
        val item = restoreItem(record.item)
        val base = item?.let(itemNames::displayName) ?: Component.text(record.item.materialKey)
        val withAmount = if (record.item.amount > 1) base.append(Component.text(" ×${record.item.amount}")) else base
        return if (item == null) withAmount else presentation.decorateItemHover(withAmount, item)
    }

    private fun restoreItem(payload: ItemPayload): ItemStack? =
        runCatching { ItemStack.deserializeBytes(payload.nativeBytes()).also { require(it.amount == payload.amount) } }
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
    ): CompletableFuture<InventoryJournalRecord> = inventoryRecovery.commitThenMutate(journal) { committed ->
        nativeWork.call({ !closed.get() }) {
            check(player.isOnline) { "Journal owner is offline" }
            check(plan.state(player) == PlanState.BEFORE) { "Inventory changed before committed journal mutation" }
            // At most 36 slots form one inventory transaction. saveData is a native indivisible durability boundary.
            check(plan.apply(player, playerData)) { "Committed inventory journal mutation did not verify" }
            committed.copy(status = JournalStatus.APPLIED)
        }.thenCompose { applied -> storage.submit { journalStore.write(applied) } }
    }.thenApply { it.mutation }

    private fun retireJournal(journal: InventoryJournalRecord): CompletableFuture<Boolean> =
        inventoryRecovery.restoreThenAcknowledge(journal) { committed ->
            if (committed.status == JournalStatus.APPLIED) CompletableFuture.completedFuture(committed)
            else CompletableFuture.failedFuture(IllegalStateException("Only an applied inventory journal may be retired"))
        }.thenApply { completion ->
            if (completion is DurableRecoveryCompletion.ContentMismatch) {
                plugin.logger.severe("Inventory journal ${journal.giveawayId}:${journal.kind} changed before acknowledgement")
                false
            } else true
        }

    private fun retireTerminalEscrow(record: GiveawayRecord) = retireTerminalJournal(record, JournalKind.ESCROW, allowPrepared = true)

    private fun retireTerminalDelivery(record: GiveawayRecord) {
        val kind = when (record.status) {
            GiveawayStatus.COMPLETED -> JournalKind.PRIZE
            GiveawayStatus.CANCELLED -> JournalKind.REFUND
            else -> return
        }
        retireTerminalJournal(record, kind, allowPrepared = false)
    }

    private fun retireTerminalJournal(record: GiveawayRecord, kind: JournalKind, allowPrepared: Boolean) {
        val key = journalKey(record.id, kind)
        val journal = journals[key] ?: return
        if (!allowPrepared && journal.status != JournalStatus.APPLIED) return
        if (!journalOperations.add(key)) return
        val retired = if (journal.status == JournalStatus.APPLIED) retireJournal(journal)
            else storage.submit { journalStore.acknowledgeExactly(journal) != DurableAcknowledgementOutcome.CONTENT_MISMATCH }
        retired.whenComplete { _, _ -> journalOperations.remove(key) }.onMain(
            success = { removed ->
                journalOperations.remove(key)
                if (removed) {
                    journals.remove(key, journal)
                    recoveryIncidents.clear(key)
                }
            },
            failure = { failure ->
                journalOperations.remove(key)
                plugin.logger.warning("Could not retire terminal $kind journal for ${record.id}: ${failure.message}")
            },
        )
    }

    private fun newEngine(config: GiveawayConfig): GiveawayEngine =
        GiveawayEngine(config.maximumParticipants, config.minimumParticipants) { bound -> secureRandom.nextInt(bound) }

    companion object {
        const val VISUAL_FIREWORK_TAG = "arcgiveaways_visual"
        const val VISUAL_ITEM_DISPLAY_TAG = "arcgiveaways_item_display"
        private const val TICKS_PER_SECOND = 20L
        private const val ITEM_DISPLAY_UPDATE_TICKS = 2L
    }

    private fun <T> CompletableFuture<T>.onMain(
        success: (T) -> Unit,
        failure: (Throwable) -> Unit,
        lockedPlayer: UUID? = null,
    ) {
        whenComplete { value, error ->
            nativeWork.call({ !closed.get() }) {
                if (error == null) success(value) else failure(error.cause ?: error)
            }.whenComplete { _, schedulingFailure ->
                if (schedulingFailure != null) {
                    lockedPlayer?.let(inventoryLocks::remove)
                    if (!closed.get()) plugin.logger.warning("Giveaway native continuation failed: ${schedulingFailure.message}")
                }
            }
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
