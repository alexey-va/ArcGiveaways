package ru.ruscrafting.giveaways.paper

import net.luckperms.api.LuckPerms
import org.bukkit.entity.Player
import ru.arc.paper.menu.PaperDialogScreen

enum class GiveawayEscapeMode {
    CLOSE,
    BACK;

    companion object {
        const val META_KEY = "arc-menu-escape"

        fun parse(raw: String?): GiveawayEscapeMode =
            when (raw?.trim()?.lowercase()) {
                "back" -> BACK
                else -> CLOSE
            }
    }
}

/** Reads the shared preference without loading or mutating LuckPerms users. */
internal class LuckPermsGiveawayEscapePreference(private val luckPerms: LuckPerms?) {
    fun mode(player: Player): GiveawayEscapeMode =
        GiveawayEscapeMode.parse(
            luckPerms?.userManager?.getUser(player.uniqueId)
                ?.cachedData?.metaData?.getMetaValue(GiveawayEscapeMode.META_KEY),
        )
}

/**
 * In CLOSE mode the Back action stays visible, but is not installed as the
 * native exit_action. This makes Escape close the flattened dialog stack.
 * BACK keeps exit_action so native Escape dispatches the same route as Back.
 */
internal fun PaperDialogScreen.forEscapeMode(mode: GiveawayEscapeMode): PaperDialogScreen {
    if (mode == GiveawayEscapeMode.BACK) return this
    val exit = exitButton ?: return this
    require(buttons.none { it.id == exit.id }) { "Dialog already contains its exit action" }
    return copy(buttons = buttons + exit, exitButton = null)
}
