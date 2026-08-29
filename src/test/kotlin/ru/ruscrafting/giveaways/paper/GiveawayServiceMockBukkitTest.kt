package ru.ruscrafting.giveaways.paper

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import net.kyori.adventure.title.Title
import org.bukkit.Location
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
import ru.ruscrafting.giveaways.config.GiveawayFireworkStyle
import ru.ruscrafting.giveaways.config.GiveawayLocale
import ru.ruscrafting.giveaways.domain.GiveawayParticipant
import ru.ruscrafting.giveaways.domain.GiveawayRecord
import ru.ruscrafting.giveaways.domain.GiveawayStatus
import ru.ruscrafting.giveaways.domain.ItemPayload
import ru.ruscrafting.giveaways.network.GiveawayBackendDirectory
import ru.ruscrafting.giveaways.network.RedisGiveawayRepository
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
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

    test("particle aura follows the giveaway host after they move") {
        withGiveawayFixture { fixture ->
            val host = fixture.player("Host")
            val record = fixture.openRecord(host)
            fixture.repository.create(record).join() shouldBe true
            fixture.start()
            fixture.await("seeded giveaway to reconcile") { fixture.service.activeRecords().singleOrNull()?.id == record.id }
            fixture.drainParticleLocations()

            host.teleport(host.location.clone().add(12.0, 0.0, -7.0)) shouldBe true
            fixture.paper.performTicks(20)

            val aura = fixture.drainParticleLocations().last()
            aura.world shouldBe host.world
            aura.x shouldBe host.location.x
            aura.y shouldBe host.location.y
            aura.z shouldBe host.location.z
        }
    }

    test("active local giveaway displays the exact escrow item above the moving host without duplication") {
        withGiveawayFixture { fixture ->
            val host = fixture.player("Host")
            val giveawayItem = ItemStack.of(Material.DIAMOND, 7).apply {
                editMeta { it.displayName(Component.text("Семь алмазов")) }
            }
            val record = fixture.openRecord(host, item = giveawayItem)
            fixture.repository.create(record).join() shouldBe true
            fixture.start()
            fixture.await("item display to appear") { fixture.itemDisplays().size == 1 }

            val display = fixture.itemDisplays().single()
            display.item shouldBe giveawayItem
            display.scale shouldBe 1.25f
            val initialPose = display.handle.poses.last()
            initialPose.location.x shouldBe host.location.x
            initialPose.location.y shouldBe host.boundingBox.maxY + 0.65
            initialPose.location.z shouldBe host.location.z

            host.teleport(host.location.clone().add(12.0, 0.0, -7.0)) shouldBe true
            fixture.paper.performTicks(4)

            fixture.itemDisplays().size shouldBe 1
            val movedPose = display.handle.poses.last()
            movedPose.location.x shouldBe host.location.x
            movedPose.location.y shouldBe host.boundingBox.maxY + 0.65
            movedPose.location.z shouldBe host.location.z
            (movedPose.yawDegrees > initialPose.yawDegrees) shouldBe true
        }
    }

    test("reloading item display settings replaces the active display") {
        withGiveawayFixture { fixture ->
            val host = fixture.player("Host")
            val record = fixture.openRecord(host)
            fixture.repository.create(record).join() shouldBe true
            fixture.start()
            fixture.await("initial item display") { fixture.itemDisplays().size == 1 }
            val initial = fixture.itemDisplays().single()

            fixture.reloadItemDisplay(scale = 2.0)

            fixture.await("reloaded item display") { fixture.itemDisplays().size == 2 }
            initial.handle.removed shouldBe true
            fixture.itemDisplays().last().scale shouldBe 2.0f
        }
    }

    test("world spectacle escalates from ambient through countdown and drawing around the live host") {
        withGiveawayFixture { fixture ->
            val host = fixture.player("Host")
            val participant = fixture.player("Participant")
            val record = fixture.openRecord(host, listOf(participant))
            fixture.repository.create(record).join() shouldBe true
            fixture.start()
            fixture.await("ambient scene") {
                fixture.scenes().any { it.scene == GiveawayVisualScene.AMBIENT }
            }

            fixture.nowMs = record.drawAtMs - 5_000L
            fixture.paper.performTicks(20)
            fixture.scenes().any { it.scene == GiveawayVisualScene.COUNTDOWN } shouldBe true

            fixture.nowMs = record.drawAtMs
            fixture.await("drawing phase") { fixture.repository.load(record.id).join()?.status == GiveawayStatus.DRAWING }
            fixture.paper.performTicks(20)

            val drawing = fixture.scenes().last { it.scene == GiveawayVisualScene.DRAWING }
            drawing.location.x shouldBe host.location.x
            drawing.location.y shouldBe host.location.y
            drawing.location.z shouldBe host.location.z
            fixture.fireworks().map { it.scene }.toSet().containsAll(
                setOf(GiveawayVisualScene.AMBIENT, GiveawayVisualScene.COUNTDOWN, GiveawayVisualScene.DRAWING),
            ) shouldBe true
        }
    }

    test("global intensity scales particle density and cadence down from the full show") {
        withGiveawayFixture(intensity = 0.25) { fixture ->
            val host = fixture.player("Host")
            val record = fixture.openRecord(host)
            fixture.repository.create(record).join() shouldBe true
            fixture.start()
            fixture.await("reduced ambient scene") {
                fixture.scenes().any { it.scene == GiveawayVisualScene.AMBIENT }
            }

            val first = fixture.scenes().last { it.scene == GiveawayVisualScene.AMBIENT }
            first.spec.particleCount shouldBe 40
            val initialCount = fixture.scenes().count { it.scene == GiveawayVisualScene.AMBIENT }

            fixture.nowMs += 1_000L
            fixture.paper.performTicks(20)
            fixture.scenes().count { it.scene == GiveawayVisualScene.AMBIENT } shouldBe initialCount

            fixture.nowMs += 3_000L
            fixture.paper.performTicks(60)
            fixture.scenes().count { it.scene == GiveawayVisualScene.AMBIENT } shouldBe initialCount + 1
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
            fixture.await("host and participants to glow") {
                fixture.glowing(host) && fixture.glowing(first) && fixture.glowing(second)
            }

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
            fixture.await("winner spectacle") {
                fixture.scenes().any { it.scene == GiveawayVisualScene.WINNER } &&
                    fixture.fireworks().count { it.scene == GiveawayVisualScene.WINNER } == 18
            }
            fixture.await("giveaway glow to clear") {
                !fixture.glowing(host) && !fixture.glowing(first) && !fixture.glowing(second)
            }
        }
    }

    test("a dead remote backend lease is cancelled and no longer blocks a new giveaway") {
        withGiveawayFixture { fixture ->
            val host = fixture.player("Host")
            fixture.start()
            fixture.paper.performTicks(60)
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

    test("a host quit opens a durable handoff window instead of cancelling the giveaway") {
        withGiveawayFixture { fixture ->
            val host = fixture.player("Host")
            host.inventory.setItemInMainHand(ItemStack.of(Material.DIAMOND, 1))
            fixture.start()
            fixture.service.startGiveaway(host, null)
            fixture.await("giveaway to open") { fixture.service.activeRecords().singleOrNull()?.status == GiveawayStatus.OPEN }
            val id = fixture.service.activeRecords().single().id
            fixture.await("item display to appear") { fixture.itemDisplays().size == 1 }
            val itemDisplay = fixture.itemDisplays().single().handle

            host.disconnect() shouldBe true

            fixture.await("host handoff window") {
                fixture.repository.load(id).join()?.hostHandoffUntilMs != null
            }
            val handingOff = requireNotNull(fixture.repository.load(id).join())
            handingOff.status shouldBe GiveawayStatus.OPEN
            handingOff.hostHandoffStartedAtMs shouldBe fixture.nowMs
            handingOff.hostHandoffUntilMs shouldBe fixture.nowMs + 45_000L
            fixture.repository.hostGiveawayId(host.uniqueId.toString()).join() shouldBe id
            fixture.await("item display to clear during handoff") { itemDisplay.removed }

            fixture.nowMs = requireNotNull(handingOff.hostHandoffUntilMs)
            fixture.await("expired handoff refund") {
                fixture.repository.load(id).join()?.status == GiveawayStatus.AWAITING_REFUND
            }
            requireNotNull(fixture.repository.load(id).join()).terminalReason shouldBe "host_handoff_timeout"
            fixture.await("host claim release") {
                fixture.repository.hostGiveawayId(host.uniqueId.toString()).join() == null
            }
        }
    }

    test("joining another backend adopts the hosted giveaway and gives participants an urgent follow action") {
        withGiveawayFixture { fixture ->
            val host = fixture.player("Host")
            val participant = fixture.player("Guest")
            val remote = fixture.openRecord(host, listOf(participant), serverId = "survival")
                .copy(
                    hostHandoffStartedAtMs = fixture.nowMs,
                    hostHandoffUntilMs = fixture.nowMs + 45_000L,
                )
                .validated()
            fixture.repository.create(remote).join() shouldBe true
            fixture.repository.claimHost(host.uniqueId.toString(), remote.id).join() shouldBe true
            fixture.start()
            fixture.await("remote giveaway to reconcile") { fixture.service.activeRecords().singleOrNull()?.id == remote.id }
            fixture.itemDisplays().isEmpty() shouldBe true
            fixture.drainMessages(participant::nextComponentMessage)

            host.teleport(host.location.clone().add(9.0, 0.0, 4.0)) shouldBe true
            fixture.nowMs += 5_000L
            fixture.service.onPlayerJoin(host)
            fixture.await("hosted giveaway to migrate to spawn") {
                fixture.repository.load(remote.id).join()?.let {
                    it.serverId == "spawn" && it.hostHandoffUntilMs == null
                } == true
            }
            fixture.await("item display to appear on the receiving backend") { fixture.itemDisplays().size == 1 }

            val migrated = requireNotNull(fixture.repository.load(remote.id).join())
            migrated.status shouldBe GiveawayStatus.OPEN
            migrated.anchorX shouldBe host.location.x
            migrated.anchorY shouldBe host.location.y
            migrated.anchorZ shouldBe host.location.z
            migrated.drawAtMs shouldBe remote.drawAtMs + 5_000L
            fixture.itemDisplays().single().item shouldBe ItemStack.of(Material.DIAMOND, 1)
            fixture.itemDisplays().single().handle.poses.last().location.x shouldBe host.location.x
            val urgentMessages = mutableListOf<Component>()
            fixture.await("urgent follow announcement") {
                urgentMessages += fixture.drainComponents(participant::nextComponentMessage)
                urgentMessages.any { "Ведущий Host сменил сервер" in fixture.plain(it) }
            }
            val urgent = urgentMessages.single { "Ведущий Host сменил сервер" in fixture.plain(it) }
            ("[Срочно к ведущему]" in fixture.plain(urgent)) shouldBe true
            urgent.runCommands() shouldBe listOf("/giveaway follow ${remote.id}")
            fixture.titles().any { shown ->
                shown.playerId == participant.uniqueId && "Ведущий сменил сервер" in fixture.plain(shown.title.title())
            } shouldBe true

            participant.teleport(host.location.clone().add(40.0, 0.0, 0.0)) shouldBe true
            fixture.service.follow(participant, remote.id)
            fixture.await("participant to follow the migrated host") {
                participant.location.distanceSquared(host.location) < 0.01
            }
            val followMessages = mutableListOf<String>()
            fixture.await("follow confirmation") {
                followMessages += fixture.drainMessages(participant::nextComponentMessage)
                followMessages.any { "Участие сохранено" in it }
            }
            requireNotNull(fixture.repository.load(remote.id).join()).participants.single().playerId shouldBe
                participant.uniqueId.toString()
        }
    }
})

private fun withGiveawayFixture(intensity: Double = 1.0, block: (GiveawayPaperFixture) -> Unit) {
    try {
        GiveawayPaperFixture(intensity).use(block)
    } catch (failure: TestAbortedException) {
        throw AssertionError("MockBukkit scenario was aborted instead of executed", failure)
    }
}

private class GiveawayPaperFixture(private val intensity: Double) : AutoCloseable {
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
        writeFixtureConfig(dataRoot, intensity)
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
        item: ItemStack = ItemStack.of(Material.DIAMOND, 1),
    ): GiveawayRecord {
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
            item = ItemPayload.capture(item.type.key.toString(), item.amount, item.serializeAsBytes()),
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

    fun drainComponents(nextMessage: () -> Component?): List<Component> = buildList {
        while (true) {
            val message = nextMessage() ?: break
            add(message)
        }
    }

    fun plain(component: Component): String = serializer.serialize(component)

    fun titles(): List<ShownTitle> = presentation.titles()

    fun drainParticleLocations(): List<Location> = presentation.drainParticleLocations()

    fun scenes(): List<ShownScene> = presentation.scenes()

    fun fireworks(): List<ShownFirework> = presentation.fireworks()

    fun glowing(player: Player): Boolean = presentation.isGlowing(player)

    fun itemDisplays(): List<ShownItemDisplay> = presentation.itemDisplays()

    fun reloadItemDisplay(scale: Double) {
        writeFixtureConfig(dataRoot, intensity = intensity, itemDisplayScale = scale)
        ConfigManager.clear()
        service.reload(GiveawayConfig.load(dataRoot))
    }

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
private data class ShownScene(val location: Location, val scene: GiveawayVisualScene, val spec: GiveawaySceneSpec)
private data class ShownItemDisplay(
    val item: ItemStack,
    val scale: Float,
    val handle: RecordingGiveawayItemDisplayHandle,
)
private data class ShownFirework(
    val location: Location,
    val scene: GiveawayVisualScene,
    val style: GiveawayFireworkStyle,
    val variant: Int,
)

private class RecordingGiveawayPresentationPort : GiveawayPresentationPort {
    private val shownTitles = mutableListOf<ShownTitle>()
    private val shownScenes = mutableListOf<ShownScene>()
    private val shownFireworks = mutableListOf<ShownFirework>()
    private val shownItemDisplays = mutableListOf<ShownItemDisplay>()
    private val glowing = mutableMapOf<UUID, Boolean>()

    override fun effectiveItemName(item: ItemStack): Component = Component.translatable(item.translationKey())

    override fun decorateItemHover(name: Component, item: ItemStack): Component = name

    override fun showTitle(player: Player, title: Title) {
        shownTitles += ShownTitle(player.uniqueId, title)
    }

    override fun isGlowing(player: Player): Boolean = glowing.getOrDefault(player.uniqueId, false)

    override fun setGlowing(player: Player, glowing: Boolean) {
        this.glowing[player.uniqueId] = glowing
    }

    override fun createItemDisplay(
        item: ItemStack,
        pose: GiveawayItemDisplayPose,
        scale: Float,
    ): GiveawayItemDisplayHandle = RecordingGiveawayItemDisplayHandle(pose).also { handle ->
        shownItemDisplays += ShownItemDisplay(item.clone(), scale, handle)
    }

    override fun renderScene(center: Location, scene: GiveawayVisualScene, spec: GiveawaySceneSpec) {
        shownScenes += ShownScene(center.clone(), scene, spec)
    }

    override fun launchFirework(
        center: Location,
        scene: GiveawayVisualScene,
        style: GiveawayFireworkStyle,
        variant: Int,
    ) {
        shownFireworks += ShownFirework(center.clone(), scene, style, variant)
    }

    fun titles(): List<ShownTitle> = shownTitles.toList()

    fun scenes(): List<ShownScene> = shownScenes.toList()

    fun fireworks(): List<ShownFirework> = shownFireworks.toList()

    fun itemDisplays(): List<ShownItemDisplay> = shownItemDisplays.toList()

    fun drainParticleLocations(): List<Location> = shownScenes.map(ShownScene::location).also { shownScenes.clear() }
}

private class RecordingGiveawayItemDisplayHandle(initialPose: GiveawayItemDisplayPose) : GiveawayItemDisplayHandle {
    val poses = mutableListOf(initialPose.copy(location = initialPose.location.clone()))
    var removed = false

    override fun update(pose: GiveawayItemDisplayPose): Boolean {
        poses += pose.copy(location = pose.location.clone())
        return !removed
    }

    override fun remove() {
        removed = true
    }
}

private object ImmediateGiveawayTravelPort : GiveawayTravelPort {
    override fun arrivalNear(host: Player) = host.location.clone()

    override fun teleport(player: Player, destination: org.bukkit.Location): CompletableFuture<Boolean> =
        CompletableFuture.completedFuture(player.teleport(destination))
}

@Suppress("DEPRECATION")
private fun Component.runCommands(): List<String> = buildList {
    clickEvent()?.takeIf { it.action() == net.kyori.adventure.text.event.ClickEvent.Action.RUN_COMMAND }
        ?.let { add(it.value()) }
    children().forEach { addAll(it.runCommands()) }
}

private fun writeFixtureConfig(root: Path, intensity: Double, itemDisplayScale: Double = 1.25) {
    Files.createDirectories(root.resolve("lang"))
    listOf("ru", "en").forEach { language ->
        requireNotNull(GiveawayServiceMockBukkitTest::class.java.getResourceAsStream("/lang/$language.yml")).use { source ->
            Files.copy(source, root.resolve("lang/$language.yml"), StandardCopyOption.REPLACE_EXISTING)
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
          host-handoff-seconds: 45
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
          particles: true
          fireworks: true
          intensity: $intensity
          item-display:
            enabled: true
            height-above-head: 0.65
            scale: $itemDisplayScale
            rotation-period-ticks: 80
        locale:
          default: ru
          use-client-locale: false
        """.trimIndent(),
    )
}
