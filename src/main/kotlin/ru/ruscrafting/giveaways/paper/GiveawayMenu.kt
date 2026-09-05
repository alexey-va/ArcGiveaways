package ru.ruscrafting.giveaways.paper

import net.kyori.adventure.text.Component
import org.bukkit.Material
import org.bukkit.entity.Player
import ru.arc.paper.menu.PaperDialogActionId
import ru.arc.paper.menu.PaperDialogBody
import ru.arc.paper.menu.PaperDialogButton
import ru.arc.paper.menu.PaperDialogClickContext
import ru.arc.paper.menu.PaperDialogInputId
import ru.arc.paper.menu.PaperDialogScreen
import ru.arc.paper.menu.PaperDialogTextInput
import ru.ruscrafting.giveaways.config.GiveawayLocale
import ru.ruscrafting.giveaways.config.MessageKey
import ru.ruscrafting.giveaways.domain.GiveawayRecord
import ru.ruscrafting.giveaways.domain.GiveawayStatus

class GiveawayMenu(
    private val service: GiveawayService,
    private val locale: GiveawayLocale,
    private val escapeMode: (Player) -> GiveawayEscapeMode = { GiveawayEscapeMode.CLOSE },
    private val openDialog: (Player, PaperDialogScreen) -> Unit,
) {
    fun open(player: Player, requestedPage: Int = 0) {
        val records = service.activeRecords()
        val pageCount = (records.size + PAGE_SIZE - 1) / PAGE_SIZE
        val page = requestedPage.coerceIn(0, (pageCount - 1).coerceAtLeast(0))
        val pageRecords = records.drop(page * PAGE_SIZE).take(PAGE_SIZE)
        val buttons = pageRecords.mapIndexed { index, record -> activeButton(player, index, record) }.toMutableList()
        if (player.hasPermission("arcgiveaways.start")) {
            buttons += button("start", text(player, MessageKey.MENU_START_LABEL), text(player, MessageKey.MENU_START_TOOLTIP)) {
                openStart(player)
            }
        }
        if (page > 0) buttons += button("previous", text(player, MessageKey.MENU_PREVIOUS_LABEL)) { open(player, page - 1) }
        if (page + 1 < pageCount) buttons += button("next", text(player, MessageKey.MENU_NEXT_LABEL)) { open(player, page + 1) }
        buttons += button("refresh", text(player, MessageKey.MENU_REFRESH_LABEL)) { open(player, page) }
        buttons += closingButton("help", text(player, MessageKey.MENU_HELP_LABEL)) { player.performCommand("giveaway help") }
        show(
            player,
            PaperDialogScreen(
                id = "arcgiveaways.menu",
                title = text(player, MessageKey.MENU_TITLE),
                body = listOf(
                    PaperDialogBody(
                        textWithComponents(
                            player,
                            if (records.isEmpty()) MessageKey.MENU_BODY_EMPTY else MessageKey.MENU_BODY,
                            mapOf(
                                "active" to Component.text(records.size.toString()),
                                "radius" to Component.text(service.menuRadius().toString()),
                                "duration" to Component.text(service.menuOpenSeconds().toString()),
                                "held" to heldItemSummary(player),
                            ),
                        ),
                        width = 500,
                    ),
                ),
                buttons = buttons,
                exitButton = button("back", text(player, MessageKey.MENU_BACK_LABEL)) {
                    player.performCommand("arc help activities")
                },
                columns = 2,
            ),
        )
    }

    private fun activeButton(player: Player, index: Int, record: GiveawayRecord): PaperDialogButton {
        val joined = record.participants.any { it.playerId == player.uniqueId.toString() }
        return closingButton(
            "active_$index",
            text(player, if (joined) MessageKey.MENU_FOLLOW_LABEL else MessageKey.MENU_JOIN_LABEL, "host" to record.hostName),
            textWithComponents(
                player,
                MessageKey.MENU_ACTIVE_TOOLTIP,
                mapOf(
                    "host" to Component.text(record.hostName),
                    "item" to activeItem(record.item.materialKey),
                    "amount" to Component.text(record.item.amount.toString()),
                    "participants" to Component.text(record.participants.size.toString()),
                    "status" to statusText(player, record.status),
                    "seconds" to Component.text(service.menuRemainingSeconds(record).toString()),
                    "radius" to Component.text(record.radius.toInt().toString()),
                ),
            ),
            width = 230,
        ) {
            if (joined) service.follow(player, record.displayId()) else service.join(player, record.displayId())
        }
    }

    private fun openStart(player: Player, error: GiveawayService.MenuStartPreview? = null) {
        val held = player.inventory.itemInMainHand.takeUnless { it.type.isAir }
        val body = buildList {
            add(PaperDialogBody(
                textWithComponents(
                    player,
                    MessageKey.MENU_START_BODY,
                    mapOf(
                        "maximum" to Component.text((held?.amount ?: 0).toString()),
                        "held" to heldItemSummary(player),
                    ),
                ),
                500,
            ))
            error?.let { preview ->
                if (preview.block != null) add(PaperDialogBody(menuBlockedMessage(player, preview), 500))
            }
        }
        show(
            player,
            PaperDialogScreen(
                id = "arcgiveaways.start",
                title = text(player, MessageKey.MENU_START_TITLE),
                body = body,
                inputs = listOf(
                    PaperDialogTextInput(
                        AMOUNT_INPUT,
                        text(player, MessageKey.MENU_AMOUNT_LABEL),
                        initial = held?.amount?.toString() ?: "1",
                        maxLength = 4,
                    ),
                ),
                buttons = listOf(
                    contextButton("preview", text(player, MessageKey.MENU_PREVIEW_LABEL), width = 230) { context ->
                        val amount = context.text(AMOUNT_INPUT).orEmpty().trim().toIntOrNull()
                        val preview = service.menuStartPreview(player, amount)
                        if (!preview.valid) {
                            openStart(player, preview)
                        } else {
                            val selected = player.inventory.itemInMainHand.clone().also { it.amount = preview.amount }
                            openConfirm(player, preview.amount, selected)
                        }
                    },
                ),
                exitButton = backButton(player) { open(player) },
            ),
        )
    }

    private fun openConfirm(player: Player, amount: Int, expectedItem: org.bukkit.inventory.ItemStack) {
        val preview = service.menuStartPreview(player, amount, expectedItem)
        if (!preview.valid) {
            return openStart(player, preview)
        }
        show(
            player,
            PaperDialogScreen(
                id = "arcgiveaways.start-confirm",
                title = text(player, MessageKey.MENU_CONFIRM_TITLE),
                body = listOf(
                    PaperDialogBody(
                        textWithComponents(
                            player,
                            MessageKey.MENU_CONFIRM_BODY,
                            mapOf("item" to service.menuItemName(expectedItem), "amount" to Component.text(amount.toString())),
                        ),
                        width = 500,
                    ),
                ),
                buttons = listOf(
                    button("confirm", text(player, MessageKey.MENU_CONFIRM_LABEL)) {
                        if (!player.hasPermission("arcgiveaways.use") || !player.hasPermission("arcgiveaways.start")) {
                            player.sendMessage(locale.render(MessageKey.NO_PERMISSION, player))
                        } else {
                            val latest = service.menuStartPreview(player, amount, expectedItem)
                            if (!latest.valid) {
                                openStart(player, latest)
                            } else {
                                player.closeDialog()
                                service.startGiveaway(player, latest.amount)
                            }
                        }
                    },
                ),
                exitButton = backButton(player) { openStart(player) },
            ),
        )
    }

    private fun backButton(player: Player, action: () -> Unit): PaperDialogButton =
        button("back", text(player, MessageKey.MENU_BACK_LABEL), action = action)

    private fun contextButton(id: String, label: Component, action: (PaperDialogClickContext) -> Unit): PaperDialogButton =
        contextButton(id, label, 150, action)

    private fun contextButton(
        id: String,
        label: Component,
        width: Int,
        action: (PaperDialogClickContext) -> Unit,
    ): PaperDialogButton = PaperDialogButton(PaperDialogActionId.of(id), label, width = width, onClick = action)

    private fun button(
        id: String,
        label: Component,
        tooltip: Component = Component.empty(),
        width: Int = 150,
        action: () -> Unit,
    ): PaperDialogButton = PaperDialogButton(PaperDialogActionId.of(id), label, tooltip, width = width, onClick = { action() })

    private fun closingButton(
        id: String,
        label: Component,
        tooltip: Component = Component.empty(),
        width: Int = 150,
        action: () -> Unit,
    ): PaperDialogButton = button(id, label, tooltip, width, action).copy(closeDialogBeforeAction = true)

    private fun text(player: Player, key: MessageKey, vararg values: Pair<String, String>): Component =
        locale.render(key, player, values.associate { (name, value) -> name to Component.text(value) })

    private fun textWithComponents(player: Player, key: MessageKey, values: Map<String, Component>): Component =
        locale.render(key, player, values)

    private fun heldItemSummary(player: Player): Component {
        val held = player.inventory.itemInMainHand.takeUnless { it.type.isAir }
            ?: return locale.render(MessageKey.MENU_HELD_ITEM_EMPTY, player)
        return locale.render(
            MessageKey.MENU_HELD_ITEM,
            player,
            mapOf("item" to service.menuItemName(held), "amount" to Component.text(held.amount.toString())),
        )
    }

    private fun activeItem(materialKey: String): Component =
        Material.matchMaterial(materialKey)?.let { Component.translatable(it.translationKey()) }
            ?: Component.text(materialKey.substringAfter(':').replace('_', ' '))

    private fun statusText(player: Player, status: GiveawayStatus): Component =
        locale.render(if (status == GiveawayStatus.DRAWING) MessageKey.MENU_STATUS_DRAWING else MessageKey.MENU_STATUS_OPEN, player)

    private fun menuBlockedMessage(player: Player, preview: GiveawayService.MenuStartPreview): Component =
        locale.render(
            preview.block!!.menuMessageKey,
            player,
            mapOf(
                "maximum" to Component.text(preview.maximum.toString()),
                "seconds" to Component.text(preview.cooldownSeconds.toString()),
            ),
        )

    private fun show(player: Player, screen: PaperDialogScreen) =
        openDialog(player, screen.forEscapeMode(escapeMode(player)))

    private companion object {
        const val PAGE_SIZE = 6
        val AMOUNT_INPUT = PaperDialogInputId.of("amount")
    }
}
