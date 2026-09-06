package ru.ruscrafting.giveaways.paper

import net.luckperms.api.LuckPerms
import org.bukkit.entity.Player

enum class GiveawayEscapeMode {
    CLOSE,
    BACK;

    companion object {
        const val META_KEY = "arc-menu-escape"

        fun parse(raw: String?): GiveawayEscapeMode =
            when (raw?.trim()?.lowercase()) {
                "close" -> CLOSE
                else -> BACK
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
