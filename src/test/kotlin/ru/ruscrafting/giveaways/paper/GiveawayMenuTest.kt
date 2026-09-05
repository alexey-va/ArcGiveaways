package ru.ruscrafting.giveaways.paper

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import net.kyori.adventure.text.Component
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

    "preview rejects malformed input without mutating the held item" {
        val stack = ItemStack(Material.DIAMOND, 5)
        val (player, inventory) = playerWith(stack)
        val service = mockk<GiveawayService>(relaxed = true)
        val locale = locale()
        val screens = mutableListOf<PaperDialogScreen>()
        val menu = GiveawayMenu(service, locale) { _, screen -> screens += screen }
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
        val menu = GiveawayMenu(service, locale) { _, screen -> screens += screen }
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
    }

    "confirmation refuses a permission lost after opening the screen" {
        val stack = ItemStack(Material.DIAMOND, 5)
        val (player, _) = playerWith(stack)
        val service = mockk<GiveawayService>(relaxed = true)
        val screens = mutableListOf<PaperDialogScreen>()
        val menu = GiveawayMenu(service, locale()) { _, screen -> screens += screen }
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
