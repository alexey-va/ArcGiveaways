package ru.ruscrafting.giveaways.paper

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import net.kyori.adventure.title.Title
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.event.player.PlayerSwapHandItemsEvent
import org.bukkit.inventory.ItemStack
import org.opentest4j.TestAbortedException
import ru.arc.config.ConfigManager
import ru.arc.core.PaperArcRuntime
import ru.arc.core.Tasks
import ru.arc.paper.network.BackendTransfer
import ru.arc.paper.network.BackendTransferResult
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.arc.redis.InMemoryRedis
import ru.arc.redis.RedisOperations
import ru.arc.redis.ServerIdentity
import ru.arc.persistence.DurableAcknowledgementOutcome
import ru.ruscrafting.giveaways.config.GiveawayConfig
import ru.ruscrafting.giveaways.config.GiveawayLocale
import ru.ruscrafting.giveaways.domain.GiveawayParticipant
import ru.ruscrafting.giveaways.domain.GiveawayRecord
import ru.ruscrafting.giveaways.domain.GiveawayStatus
import ru.ruscrafting.giveaways.domain.ItemPayload
import ru.ruscrafting.giveaways.network.GiveawayBackendDirectory
import ru.ruscrafting.giveaways.network.RedisGiveawayRepository
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CompletableFuture

class GiveawayServiceMockBukkitTest : FunSpec({
    test("starting a giveaway locks Bukkit inventory events and broadcasts an isolated bright announcement") {
        withGiveawayFixture { fixture ->
            val host = fixture.player("Host")
            val observer = fixture.player("Observer")
            host.inventory.setItemInMainHand(ItemStack.of(Material.DIAMOND, 3))
            fixture.start()
            fixture.redis.saveDelay = 75L

            fixture.service.startGiveaway(host, 2)

            fixture.service.isInventoryLocked(host.uniqueId) shouldBe true
            val swap = PlayerSwapHandItemsEvent(
                host,
                host.inventory.itemInMainHand,
                host.inventory.itemInOffHand,
            )
            fixture.paper.callEvent(swap)
            swap.isCancelled shouldBe true
            fixture.redis.saveDelay = 0L
            fixture.await("giveaway to open") { fixture.service.activeRecords().singleOrNull()?.status == GiveawayStatus.OPEN }

            host.inventory.itemInMainHand shouldBe ItemStack.of(Material.DIAMOND, 1)
            val opened = fixture.service.activeRecords().single()
            fixture.repository.hostGiveawayId(host.uniqueId.toString()).join() shouldBe opened.id

            val announcement = fixture.drainMessages(observer::nextComponentMessage).single { "Раздача от Host" in it }
            announcement.startsWith("\n") shouldBe true
            announcement.endsWith("\n") shouldBe true
            ("Приз: Алмаз ×2" in announcement) shouldBe true
            ("[Участвовать]" in announcement) shouldBe true
        }
    }

    test("bossbar is global while actionbar and five-second title are participant-only") {
        withGiveawayFixture { fixture ->
            val host = fixture.player("Host")
            val participant = fixture.player("Participant")
            val observer = fixture.player("Observer")
            val record = fixture.openRecord(host, listOf(participant))
            fixture.repository.create(record).join() shouldBe true
            fixture.start()
            fixture.await("seeded giveaway to reconcile") { fixture.service.activeRecords().singleOrNull()?.id == record.id }
            listOf(host, participant, observer).forEach { fixture.drainMessages(it::nextComponentMessage) }

            fixture.paper.performTicks(20)

            listOf(host, participant, observer).forEach { viewer -> viewer.bossBars.size shouldBe 1 }
            val bossBar = observer.bossBars.single()
            val bossText = fixture.plain(bossBar.name())
            ("Раздача от Host" in bossText) shouldBe true
            ("Алмаз" in bossText) shouldBe true
            participant.nextActionBar()?.let(fixture::plain)?.contains("10 сек.") shouldBe true
            observer.nextActionBar() shouldBe null
            host.nextActionBar() shouldBe null

            fixture.nowMs += 5_000L
            fixture.paper.performTicks(20)

            participant.nextActionBar()?.let(fixture::plain)?.contains("5 сек.") shouldBe true
            fixture.await("participant countdown title") {
                fixture.titles().any { shown ->
                    shown.playerId == participant.uniqueId && fixture.plain(shown.title.title()) == "5"
                }
            }
            fixture.titles().none { it.playerId == observer.uniqueId || it.playerId == host.uniqueId } shouldBe true
            (bossBar.progress() in 0.49f..0.51f) shouldBe true
        }
    }

    test("joining through the public service teleports the player and announces participation to everyone") {
        withGiveawayFixture { fixture ->
            val host = fixture.player("Host")
            val participant = fixture.player("Guest")
            val observer = fixture.player("Observer")
            val record = fixture.openRecord(host)
            fixture.repository.create(record).join() shouldBe true
            fixture.start()
            fixture.await("seeded giveaway to reconcile") { fixture.service.activeRecords().singleOrNull()?.id == record.id }
            listOf(host, participant, observer).forEach { fixture.drainMessages(it::nextComponentMessage) }

            fixture.service.join(participant, record.id)

            fixture.await("participant to be persisted") {
                fixture.repository.load(record.id).join()?.participants?.singleOrNull()?.playerId == participant.uniqueId.toString()
            }
            val updated = requireNotNull(fixture.repository.load(record.id).join())
            updated.participants.single().playerName shouldBe "Guest"
            participant.location.world shouldBe host.location.world
            (participant.location.distanceSquared(host.location) <= record.radius * record.radius) shouldBe true

            val observerMessages = mutableListOf<String>()
            fixture.await("global joined announcement") {
                observerMessages += fixture.drainMessages(observer::nextComponentMessage)
                observerMessages.any { "Guest присоединяется к раздаче" in it && "оп-оп" in it }
            }
            fixture.drainMessages(participant::nextComponentMessage).any { "Вы участвуете" in it } shouldBe true
        }
    }

    test("two real MockBukkit participants complete the draw and exactly one receives the escrowed prize") {
        withGiveawayFixture { fixture ->
            val host = fixture.player("Host")
            val first = fixture.player("First")
            val second = fixture.player("Second")
            host.inventory.setItemInMainHand(ItemStack.of(Material.DIAMOND, 2))
            fixture.start()

            fixture.service.startGiveaway(host, null)
            fixture.await("giveaway to open") { fixture.service.activeRecords().singleOrNull()?.status == GiveawayStatus.OPEN }
            val id = fixture.service.activeRecords().single().id
            fixture.service.join(first, id)
            fixture.await("first participant") { fixture.repository.load(id).join()?.participants?.size == 1 }
            fixture.service.join(second, id)
            fixture.await("second participant") { fixture.repository.load(id).join()?.participants?.size == 2 }

            fixture.nowMs = requireNotNull(fixture.repository.load(id).join()).drawAtMs
            fixture.await("drawing phase") { fixture.repository.load(id).join()?.status == GiveawayStatus.DRAWING }
            val drawing = requireNotNull(fixture.repository.load(id).join())
            fixture.nowMs = requireNotNull(drawing.drawingEndsAtMs)
            fixture.await("completed delivery and journal retirement") {
                fixture.repository.load(id).join()?.status == GiveawayStatus.COMPLETED &&
                    fixture.service.recoveryBacklog() == 0
            }

            val completed = requireNotNull(fixture.repository.load(id).join())
            val winner = requireNotNull(completed.winner)
            val prizes = listOf(first, second).associate { player ->
                player.uniqueId.toString() to player.inventory.contents.filterNotNull()
                    .filter { it.type == Material.DIAMOND }
                    .sumOf(ItemStack::getAmount)
            }
            prizes.values.sorted() shouldBe listOf(0, 2)
            prizes.getValue(winner.playerId) shouldBe 2
            host.inventory.itemInMainHand.type.isAir shouldBe true
            fixture.service.recoveryBacklog() shouldBe 0
            fixture.repository.hostGiveawayId(host.uniqueId.toString()).join() shouldBe null
            fixture.titles().any { shown ->
                shown.playerId.toString() == winner.playerId && "Вы победили" in fixture.plain(shown.title.title())
            } shouldBe true
        }
    }

    test("a dead remote backend lease is cancelled and no longer blocks a new giveaway") {
        withGiveawayFixture { fixture ->
            val host = fixture.player("Host")
            val stale = fixture.openRecord(host, serverId = "survival")
            fixture.repository.create(stale).join() shouldBe true
            fixture.repository.claimHost(host.uniqueId.toString(), stale.id).join() shouldBe true
            val remoteDirectory = GiveawayBackendDirectory(
                redis = fixture.redisFaults,
                localServerId = "survival",
                allowedOrigins = setOf("spawn", "survival", "parkour"),
                leaseMillis = 6_000L,
                clockMs = { fixture.nowMs },
            )
            remoteDirectory.heartbeat().join()
            fixture.start()
            fixture.paper.performTicks(20)
            repeat(4) {
                fixture.nowMs += 2_000L
                fixture.paper.performTicks(40)
            }
            host.inventory.setItemInMainHand(ItemStack.of(Material.EMERALD, 1))

            fixture.service.startGiveaway(host, null)

            fixture.await("replacement giveaway to open") {
                fixture.service.activeRecords().any { it.id != stale.id && it.status == GiveawayStatus.OPEN }
            }
            val replacement = fixture.service.activeRecords().single { it.id != stale.id }
            requireNotNull(fixture.repository.load(stale.id).join()).let { cancelled ->
                cancelled.status shouldBe GiveawayStatus.AWAITING_REFUND
                cancelled.terminalReason shouldBe "owner_backend_unavailable"
            }
            fixture.repository.hostGiveawayId(host.uniqueId.toString()).join() shouldBe replacement.id
            fixture.drainMessages(host::nextComponentMessage).none { "уже есть активная раздача" in it } shouldBe true
            remoteDirectory.close()
        }
    }

    test("an unknown Redis create outcome preserves escrow and converges the committed PREPARING record") {
        withGiveawayFixture { fixture ->
            val host = fixture.player("Host")
            host.inventory.setItemInMainHand(ItemStack.of(Material.DIAMOND, 2))
            fixture.start()
            fixture.redisFaults.failNextRecordCasAfterCommit = true

            fixture.service.startGiveaway(host, null)

            fixture.await("unknown create outcome to expose the committed PREPARING record") {
                fixture.redis.getHash(RedisGiveawayRepository.RECORDS_KEY).isNotEmpty()
            }
            val committedId = fixture.redis.getHash(RedisGiveawayRepository.RECORDS_KEY).keys.single()
            fixture.repository.load(committedId).join()?.status shouldBe GiveawayStatus.PREPARING
            fixture.service.recoveryBacklog() shouldBe 1
            fixture.repository.hostGiveawayId(host.uniqueId.toString()).join() shouldBe committedId

            fixture.await("preserved escrow to recover and open") {
                fixture.repository.load(committedId).join()?.status == GiveawayStatus.OPEN
            }
            fixture.service.recoveryBacklog() shouldBe 1
            fixture.service.isInventoryLocked(host.uniqueId) shouldBe false
            host.inventory.itemInMainHand.type.isAir shouldBe true
        }
    }

    test("an APPLIED journal write failure releases the transient lock and recovery opens safely") {
        withGiveawayFixture { fixture ->
            val host = fixture.player("Host")
            host.inventory.setItemInMainHand(ItemStack.of(Material.EMERALD, 1))
            fixture.journalFaults.failNextAppliedWrite = true
            fixture.start()

            fixture.service.startGiveaway(host, null)

            fixture.await("inventory mutation after the injected journal failure") {
                host.inventory.itemInMainHand.type.isAir
            }
            fixture.service.isInventoryLocked(host.uniqueId) shouldBe false
            fixture.await("PREPARING recovery after the injected journal failure") {
                fixture.service.activeRecords().singleOrNull()?.status == GiveawayStatus.OPEN
            }
            fixture.journalFaults.appliedWriteFailures shouldBe 1
            fixture.service.isInventoryLocked(host.uniqueId) shouldBe false
        }
    }

    test("a real PlayerQuitEvent cancels the host giveaway and releases its network claim") {
        withGiveawayFixture { fixture ->
            val host = fixture.player("Host")
            host.inventory.setItemInMainHand(ItemStack.of(Material.DIAMOND, 1))
            fixture.start()
            fixture.service.startGiveaway(host, null)
            fixture.await("giveaway to open") { fixture.service.activeRecords().singleOrNull()?.status == GiveawayStatus.OPEN }
            val id = fixture.service.activeRecords().single().id

            host.disconnect() shouldBe true

            fixture.await("quit cancellation") {
                fixture.repository.load(id).join()?.status == GiveawayStatus.AWAITING_REFUND
            }
            requireNotNull(fixture.repository.load(id).join()).terminalReason shouldBe "host_disconnected"
            fixture.await("host claim release") {
                fixture.repository.hostGiveawayId(host.uniqueId.toString()).join() == null
            }
        }
    }
})

private fun withGiveawayFixture(block: (GiveawayPaperFixture) -> Unit) {
    try {
        GiveawayPaperFixture().use(block)
    } catch (failure: TestAbortedException) {
        throw AssertionError("MockBukkit scenario was aborted instead of executed", failure)
    }
}

private class GiveawayPaperFixture : AutoCloseable {
    val paper: MockBukkitTestRuntime = MockBukkitTestRuntime.open()
    private val plugin = paper.createSimplePlugin("ArcGiveawaysTest")
    private val dataRoot: Path = Files.createTempDirectory("arcgiveaways-paper-test-")
    val world = paper.addSimpleWorld("giveaways")
    var nowMs: Long = 1_787_730_000_000L
    val redis = InMemoryRedis(ServerIdentity { "spawn" })
    val redisFaults = UnknownOutcomeRedis(redis)
    val repository = RedisGiveawayRepository(redisFaults)
    val journalFaults = FailingInventoryJournalRepository(InventoryJournalStore(dataRoot))
    val backendDirectory: GiveawayBackendDirectory
    val service: GiveawayService
    private val presentation = RecordingGiveawayPresentationPort()
    private val serializer = PlainTextComponentSerializer.plainText()

    init {
        PaperArcRuntime.installScheduling(plugin)
        writeFixtureConfig(dataRoot)
        ConfigManager.clear()
        val settings = GiveawayConfig.load(dataRoot)
        val locale = GiveawayLocale(dataRoot) { settings }
        val catalog = dataRoot.resolve("items-ru.json")
        Files.writeString(catalog, """{"item.minecraft.diamond":"Алмаз","item.minecraft.emerald":"Изумруд"}""")
        backendDirectory = GiveawayBackendDirectory(
            redis = redisFaults,
            localServerId = settings.serverId,
            allowedOrigins = settings.allowedOrigins,
            leaseMillis = settings.presenceLeaseSeconds * 1_000L,
            clockMs = { nowMs },
        )
        service = GiveawayService(
            plugin = plugin,
            settings = settings,
            locale = locale,
            repository = repository,
            journalStore = journalFaults,
            itemNames = RussianItemNames(catalog, plugin.logger, presentation),
            transfer = BackendTransfer { _, _ -> BackendTransferResult.SENT },
            backendDirectory = backendDirectory,
            clockMs = { nowMs },
            presentation = presentation,
            travel = ImmediateGiveawayTravelPort,
            playerData = GiveawayPlayerDataPersistence {},
        )
        paper.server.pluginManager.registerEvents(GiveawayListener(service), plugin)
    }

    fun start() = service.start()

    fun player(name: String) = paper.addPlayer(name).also { it.teleport(world.spawnLocation) }

    fun openRecord(
        host: Player,
        participants: List<Player> = emptyList(),
        serverId: String = "spawn",
    ): GiveawayRecord {
        val stack = ItemStack.of(Material.DIAMOND, 1)
        return GiveawayRecord(
            id = UUID.randomUUID().toString(),
            revision = 0,
            status = GiveawayStatus.OPEN,
            hostId = host.uniqueId.toString(),
            hostName = host.name,
            serverId = serverId,
            worldName = world.name,
            anchorX = host.location.x,
            anchorY = host.location.y,
            anchorZ = host.location.z,
            radius = 100.0,
            item = ItemPayload.capture(stack.type.key.toString(), stack.amount, stack.serializeAsBytes()),
            createdAtMs = nowMs,
            opensAtMs = nowMs,
            drawAtMs = nowMs + 10_000L,
            participants = participants.map { player ->
                GiveawayParticipant(player.uniqueId.toString(), player.name, nowMs)
            },
        ).validated()
    }

    fun await(description: String, timeout: Duration = Duration.ofSeconds(8), condition: () -> Boolean) {
        val deadline = System.nanoTime() + timeout.toNanos()
        while (System.nanoTime() < deadline) {
            paper.performTicks(1)
            if (condition()) return
            Thread.sleep(2L)
        }
        error("Timed out waiting for $description")
    }

    fun drainMessages(nextMessage: () -> Component?): List<String> = buildList {
        while (true) {
            val message = nextMessage() ?: break
            add(plain(message))
        }
    }

    fun plain(component: Component): String = serializer.serialize(component)

    fun titles(): List<ShownTitle> = presentation.titles()

    override fun close() {
        runCatching(service::close)
        Tasks.reset()
        ConfigManager.clear()
        runCatching(paper::close)
        dataRoot.toFile().deleteRecursively()
    }
}

private class UnknownOutcomeRedis(
    private val delegate: RedisOperations,
) : RedisOperations by delegate {
    var failNextRecordCasAfterCommit: Boolean = false

    override fun compareAndSetMapEntry(
        key: String,
        mapKey: String,
        expectedValue: String?,
        replacementValue: String?,
    ): CompletableFuture<Boolean> {
        val result = delegate.compareAndSetMapEntry(key, mapKey, expectedValue, replacementValue)
        if (key != RedisGiveawayRepository.RECORDS_KEY || !failNextRecordCasAfterCommit) return result
        failNextRecordCasAfterCommit = false
        return result.thenCompose { CompletableFuture.failedFuture(IllegalStateException("unknown create outcome")) }
    }
}

private class FailingInventoryJournalRepository(
    private val delegate: InventoryJournalRepository,
) : InventoryJournalRepository {
    var failNextAppliedWrite: Boolean = false
    var appliedWriteFailures: Int = 0

    override fun write(record: InventoryJournalRecord): InventoryJournalRecord {
        if (record.status == JournalStatus.APPLIED && failNextAppliedWrite) {
            failNextAppliedWrite = false
            appliedWriteFailures += 1
            throw IllegalStateException("injected APPLIED journal failure")
        }
        return delegate.write(record)
    }

    override fun acknowledgeExactly(record: InventoryJournalRecord): DurableAcknowledgementOutcome =
        delegate.acknowledgeExactly(record)

    override fun loadAll(): List<InventoryJournalRecord> = delegate.loadAll()
}

private data class ShownTitle(val playerId: UUID, val title: Title)

private class RecordingGiveawayPresentationPort : GiveawayPresentationPort {
    private val shownTitles = mutableListOf<ShownTitle>()

    override fun effectiveItemName(item: ItemStack): Component = Component.translatable(item.translationKey())

    override fun decorateItemHover(name: Component, item: ItemStack): Component = name

    override fun showTitle(player: Player, title: Title) {
        shownTitles += ShownTitle(player.uniqueId, title)
    }

    fun titles(): List<ShownTitle> = shownTitles.toList()
}

private object ImmediateGiveawayTravelPort : GiveawayTravelPort {
    override fun arrivalNear(host: Player) = host.location.clone()

    override fun teleport(player: Player, destination: org.bukkit.Location): CompletableFuture<Boolean> =
        CompletableFuture.completedFuture(player.teleport(destination))
}

private fun writeFixtureConfig(root: Path) {
    Files.createDirectories(root.resolve("lang"))
    listOf("ru", "en").forEach { language ->
        requireNotNull(GiveawayServiceMockBukkitTest::class.java.getResourceAsStream("/lang/$language.yml")).use { source ->
            Files.copy(source, root.resolve("lang/$language.yml"))
        }
    }
    Files.writeString(
        root.resolve("config.yml"),
        """
        server-id: spawn
        giveaway:
          radius-blocks: 100.0
          open-seconds: 10
          drawing-seconds: 1
          minimum-participants: 1
          maximum-participants: 10
          maximum-item-amount: 64
          host-cooldown-seconds: 0
          terminal-retention-minutes: 30
        cross-server:
          enabled: true
          transfer-on-click: true
          pending-join-seconds: 10
          presence-heartbeat-seconds: 2
          presence-lease-seconds: 6
          allowed-origins: [spawn, survival, parkour]
        redis:
          import-arc-credentials: false
        effects:
          bossbar: true
          actionbar: true
          titles: true
          sounds: false
          particles: false
          fireworks: false
        locale:
          default: ru
          use-client-locale: false
        """.trimIndent(),
    )
}
