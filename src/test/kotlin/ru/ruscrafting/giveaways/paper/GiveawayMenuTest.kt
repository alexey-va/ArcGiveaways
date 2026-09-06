package ru.ruscrafting.giveaways.paper

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.PlayerInventory
import ru.arc.paper.menu.PaperDialogClickContext
import ru.arc.paper.menu.PaperDialogInputId
import ru.arc.paper.menu.PaperDialogScreen
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.ruscrafting.giveaways.config.GiveawayLocale
import ru.ruscrafting.giveaways.config.MessageKey
import java.util.UUID

class GiveawayMenuTest : StringSpec({
    lateinit var paper: MockBukkitTestRuntime
    beforeSpec { paper = MockBukkitTestRuntime.open() }
    afterSpec { paper.close() }

    "start menu shows an empty-hand error instead of only sending chat" {
        val stack = ItemStack(Material.DIAMOND, 5)
        val (player, inventory) = playerWith(stack)
        var held: ItemStack? = stack
        every { inventory.itemInMainHand } answers { held ?: ItemStack(Material.AIR) }
        val service = mockk<GiveawayService>(relaxed = true)
        val locale = locale()
        val screens = mutableListOf<PaperDialogScreen>()
        val menu = GiveawayMenu(service, locale) { _, screen, _, _ -> screens += screen }
        every { service.activeRecords() } returns emptyList()
        every { service.menuStartPreview(player, 1) } returns GiveawayService.MenuStartPreview(
            amount = 1, maximum = 0, itemKey = "minecraft:air", block = GiveawayService.MenuStartBlock.EMPTY_HAND,
        )
        every { locale.render(MessageKey.MENU_HELD_ITEM_EMPTY, player) } returns Component.text("In hand: empty")
        every { locale.render(MessageKey.MENU_START_BODY, player, any()) } returns Component.text("start")
        every { locale.render(MessageKey.MENU_ERROR_EMPTY_HAND, player, any()) } returns Component.text("Error: empty hand")
        every { player.hasPermission("arcgiveaways.start") } returns true

        menu.open(player)
        click(screens.last().buttons.single { it.id.value == "start" }, player)
        held = null
        click(screens.last().buttons.single { it.id.value == "preview" }, player, mapOf("amount" to "1"))

        PlainTextComponentSerializer.plainText().serialize(screens.last().body.last().text) shouldBe "Error: empty hand"
        verify(exactly = 0) { service.startGiveaway(any(), any()) }
    }

    "start menu shows the held item and amount" {
        val stack = ItemStack(Material.DIAMOND, 5)
        val (player, _) = playerWith(stack)
        val service = mockk<GiveawayService>(relaxed = true)
        val locale = locale()
        val screens = mutableListOf<PaperDialogScreen>()
        val menu = GiveawayMenu(service, locale) { _, screen, _, _ -> screens += screen }
        val heldValues = io.mockk.slot<Map<String, Component>>()
        val bodyValues = io.mockk.slot<Map<String, Component>>()
        every { service.activeRecords() } returns emptyList()
        every { service.menuItemName(stack) } returns Component.text("Diamond")
        every { locale.render(MessageKey.MENU_HELD_ITEM, player, capture(heldValues)) } returns Component.text("held")
        every { locale.render(MessageKey.MENU_START_BODY, player, capture(bodyValues)) } returns Component.text("start")
        every { player.hasPermission("arcgiveaways.start") } returns true

        menu.open(player)
        click(screens.last().buttons.single { it.id.value == "start" }, player)

        screens.last().inputs.single().initial shouldBe "5"
        bodyValues.captured["held"]?.let { PlainTextComponentSerializer.plainText().serialize(it) } shouldBe "held"
        PlainTextComponentSerializer.plainText().serialize(heldValues.captured.getValue("item")) shouldBe "Diamond"
        PlainTextComponentSerializer.plainText().serialize(heldValues.captured.getValue("amount")) shouldBe "5"
    }

    "preview rejects malformed input without mutating the held item" {
        val stack = ItemStack(Material.DIAMOND, 5)
        val (player, inventory) = playerWith(stack)
        val service = mockk<GiveawayService>(relaxed = true)
        val locale = locale()
        val screens = mutableListOf<PaperDialogScreen>()
        val menu = GiveawayMenu(service, locale) { _, screen, _, _ -> screens += screen }
        every { service.activeRecords() } returns emptyList()
        every { service.menuStartPreview(player, null, any()) } returns GiveawayService.MenuStartPreview(
            amount = 0,
            maximum = 5,
            itemKey = "minecraft:diamond",
            block = GiveawayService.MenuStartBlock.BAD_AMOUNT,
        )
        every { player.hasPermission("arcgiveaways.start") } returns true
        every { player.hasPermission("arcgiveaways.use") } returns true

        menu.open(player)
        click(screens.last().buttons.single { it.id.value == "start" }, player)
        val start = screens.last()
        start.buttons.single { it.id.value == "preview" }.closeDialogBeforeAction shouldBe false
        click(start.buttons.single { it.id.value == "preview" }, player, mapOf("amount" to "oops"))

        screens.last().inputs.single().initial shouldBe "oops"
        stack.amount shouldBe 5
        verify(exactly = 1) { service.menuStartPreview(player, null, any()) }
        verify(exactly = 0) { service.startGiveaway(any(), any()) }
    }

    "confirmation rechecks the snapshot and permission before starting" {
        val stack = ItemStack(Material.DIAMOND, 5)
        val (player, _) = playerWith(stack)
        val service = mockk<GiveawayService>(relaxed = true)
        val locale = locale()
        val screens = mutableListOf<PaperDialogScreen>()
        val menu = GiveawayMenu(service, locale) { _, screen, _, _ -> screens += screen }
        val valid = GiveawayService.MenuStartPreview(2, 5, "minecraft:diamond")
        val changed = valid.copy(block = GiveawayService.MenuStartBlock.ITEM_CHANGED)
        every { service.activeRecords() } returns emptyList()
        every { service.menuStartPreview(player, any()) } returns valid
        every { service.menuStartPreview(player, null) } returns valid
        var confirmationChecks = 0
        every { service.menuStartPreview(player, 2, any()) } answers {
            confirmationChecks += 1
            if (confirmationChecks == 1) valid else changed
        }
        every { service.menuItemName(any()) } returns Component.text("Diamond")
        every { locale.render(MessageKey.MENU_ERROR_ITEM_CHANGED, player, any()) } returns Component.text("Error: item changed")
        every { player.hasPermission("arcgiveaways.start") } returns true
        every { player.hasPermission("arcgiveaways.use") } returns true

        menu.open(player)
        click(screens.last().buttons.single { it.id.value == "start" }, player)
        val openConfirm = GiveawayMenu::class.java.getDeclaredMethod("openConfirm", Player::class.java, Int::class.javaPrimitiveType, ItemStack::class.java)
            .apply { isAccessible = true }
        openConfirm.invoke(menu, player, 2, stack.clone().also { it.amount = 2 })
        val confirm = screens.last()
        confirm.buttons.single { it.id.value == "confirm" }.closeDialogBeforeAction shouldBe false
        click(confirm.buttons.single { it.id.value == "confirm" }, player)

        verify(exactly = 0) { service.startGiveaway(any(), any()) }
        verify(exactly = 2) { service.menuStartPreview(player, 2, any()) }
        PlainTextComponentSerializer.plainText().serialize(screens.last().body.last().text) shouldBe "Error: item changed"
    }

    "history refreshes root data and keeps form drafts under Core ownership" {
        val (player, _) = playerWith(ItemStack(Material.DIAMOND, 5))
        val service = mockk<GiveawayService>(relaxed = true)
        every { service.activeRecords() } returns emptyList()
        every { player.hasPermission("arcgiveaways.start") } returns true
        val screens = mutableListOf<PaperDialogScreen>()
        val reopeners = mutableListOf<(() -> Unit)?>()
        val menu = GiveawayMenu(service, locale()) { _, screen, reopen, _ ->
            screens += screen; reopeners += reopen
        }
        menu.open(player)
        val restore = requireNotNull(reopeners.last())
        click(screens.last().buttons.single { it.id.value == "start" }, player)
        reopeners.last() shouldBe null
        restore()
        verify(exactly = 2) { service.activeRecords() }
        screens.last().id shouldBe "arcgiveaways.menu"
        screens.forEach {
            it.exitButton!!.width shouldBe 200
            it.buttons.any { button -> button.id.value in setOf("refresh", "back", "close") } shouldBe false
        }
    }

    "confirmation refuses a permission lost after opening the screen" {
        val stack = ItemStack(Material.DIAMOND, 5)
        val (player, _) = playerWith(stack)
        val service = mockk<GiveawayService>(relaxed = true)
        val screens = mutableListOf<PaperDialogScreen>()
        val menu = GiveawayMenu(service, locale()) { _, screen, _, _ -> screens += screen }
        every { service.activeRecords() } returns emptyList()
        every { service.menuStartPreview(player, 2, any()) } returns GiveawayService.MenuStartPreview(2, 5, "minecraft:diamond")
        every { service.menuItemName(any()) } returns Component.text("Diamond")
        every { player.hasPermission("arcgiveaways.start") } returns true
        every { player.hasPermission("arcgiveaways.use") } returns true
        menu.open(player)
        val openConfirm = GiveawayMenu::class.java.getDeclaredMethod("openConfirm", Player::class.java, Int::class.javaPrimitiveType, ItemStack::class.java)
            .apply { isAccessible = true }
        openConfirm.invoke(menu, player, 2, stack.clone().also { it.amount = 2 })
        every { player.hasPermission("arcgiveaways.start") } returns false
        click(screens.last().buttons.single { it.id.value == "confirm" }, player)
        verify(exactly = 0) { service.startGiveaway(any(), any()) }
    }
})

private fun playerWith(stack: ItemStack): Pair<Player, PlayerInventory> {
    val inventory = mockk<PlayerInventory>()
    val player = mockk<Player>(relaxed = true)
    every { player.inventory } returns inventory
    every { inventory.itemInMainHand } returns stack
    every { player.uniqueId } returns UUID.randomUUID()
    return player to inventory
}

private fun locale(): GiveawayLocale {
    val locale = mockk<GiveawayLocale>(relaxed = true)
    every { locale.render(any<MessageKey>(), any(), any()) } returns Component.empty()
    every { locale.render(any<MessageKey>(), any()) } returns Component.empty()
    return locale
}

private fun click(
    button: ru.arc.paper.menu.PaperDialogButton,
    player: Player,
    values: Map<String, String> = emptyMap(),
) {
    val constructor = PaperDialogClickContext::class.java.declaredConstructors.first { it.parameterTypes.size >= 2 }.apply { isAccessible = true }
    val resolver: (PaperDialogInputId) -> String? = { values.values.firstOrNull() }
    val context = constructor.newInstance(player, resolver)
    button.onClick.handle(context as PaperDialogClickContext)
}
