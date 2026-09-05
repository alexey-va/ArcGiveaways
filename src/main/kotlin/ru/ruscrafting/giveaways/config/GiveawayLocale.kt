package ru.ruscrafting.giveaways.config

import net.kyori.adventure.text.Component
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import ru.arc.config.Config
import ru.arc.config.ConfigManager
import ru.arc.text.ConfigLocaleCatalog
import ru.arc.text.LocalizedMiniMessage
import java.nio.file.Path

enum class MessageKey(val path: String) {
    PREFIX("prefix"), HELP("help"), PLAYER_ONLY("player-only"), NO_PERMISSION("no-permission"),
    REDIS_UNAVAILABLE("redis-unavailable"), EMPTY_HAND("empty-hand"), BAD_AMOUNT("bad-amount"),
    BUSY("busy"), COOLDOWN("cooldown"), STARTING("starting"), STARTED("started"), START_FAILED("start-failed"),
    ANNOUNCEMENT("announcement"),
    JOIN_BUTTON("join-button"), JOIN_HOVER_LOCAL("join-hover-local"), JOIN_HOVER_TRANSFER("join-hover-transfer"),
    JOINED_NETWORK("joined-network"), BOSSBAR_OPEN("bossbar-open"), BOSSBAR_DRAWING("bossbar-drawing"),
    HOST_MOVED("host-moved"), FOLLOW_BUTTON("follow-button"), FOLLOW_HOVER("follow-hover"),
    HOST_MOVED_ACTIONBAR("host-moved-actionbar"), HOST_MOVED_TITLE("host-moved-title"),
    HOST_MOVED_SUBTITLE("host-moved-subtitle"), FOLLOW_ARRIVED("follow-arrived"), FOLLOW_DENIED("follow-denied"),
    SERVER_SPAWN("server-spawn"), SERVER_SURVIVAL("server-survival"), SERVER_PARKOUR("server-parkour"),
    SERVER_OTHER("server-other"),
    TRANSFERRING("transferring"), NOT_FOUND("not-found"), NOT_OPEN("not-open"), TOO_FAR("too-far"),
    WRONG_WORLD("wrong-world"), JOINED("joined"), ALREADY_JOINED("already-joined"), FULL("full"),
    HOST_CANNOT_JOIN("host-cannot-join"), STATUS_EMPTY("status-empty"), STATUS_ENTRY("status-entry"),
    COUNTDOWN_ACTIONBAR("countdown-actionbar"), COUNTDOWN_TITLE("countdown-title"),
    COUNTDOWN_SUBTITLE("countdown-subtitle"), DRAWING_TITLE("drawing-title"),
    DRAWING_SUBTITLE("drawing-subtitle"), NOT_ENOUGH("not-enough"), CANCELLED("cancelled"),
    CANCEL_DENIED("cancel-denied"), WINNER_NETWORK("winner-network"), WINNER_TITLE("winner-title"),
    WINNER_SUBTITLE("winner-subtitle"), DELIVERY_PENDING("delivery-pending"), DELIVERED("delivered"),
    CLAIM_TRANSFERRING("claim-transferring"),
    NOTHING_TO_CLAIM("nothing-to-claim"), CLAIM_RETRYING("claim-retrying"), RELOAD_OK("reload-ok"),
    RELOAD_FAILED("reload-failed"), TELEPORT_FAILED("teleport-failed"), PVP_PROTECTED("pvp-protected"),
    GENERIC_ERROR("generic-error"),
    MENU_TITLE("menu-title"), MENU_BODY("menu-body"), MENU_BODY_EMPTY("menu-body-empty"),
    MENU_ACTIVE_TOOLTIP("menu-active-tooltip"), MENU_JOIN_LABEL("menu-join-label"), MENU_FOLLOW_LABEL("menu-follow-label"),
    MENU_START_LABEL("menu-start-label"), MENU_START_TOOLTIP("menu-start-tooltip"), MENU_REFRESH_LABEL("menu-refresh-label"),
    MENU_HELP_LABEL("menu-help-label"), MENU_START_TITLE("menu-start-title"), MENU_START_BODY("menu-start-body"),
    MENU_AMOUNT_LABEL("menu-amount-label"), MENU_PREVIEW_LABEL("menu-preview-label"), MENU_CONFIRM_TITLE("menu-confirm-title"),
    MENU_CONFIRM_BODY("menu-confirm-body"), MENU_CONFIRM_LABEL("menu-confirm-label"), MENU_BACK_LABEL("menu-back-label"),
    MENU_NEXT_LABEL("menu-next-label"), MENU_PREVIOUS_LABEL("menu-previous-label"), MENU_STATUS_OPEN("menu-status-open"),
    MENU_STATUS_DRAWING("menu-status-drawing"), MENU_ITEM_CHANGED("menu-item-changed"),
    MENU_HELD_ITEM("menu-held-item"), MENU_HELD_ITEM_EMPTY("menu-held-item-empty"),
    MENU_ERROR_BUSY("menu-error-busy"), MENU_ERROR_COOLDOWN("menu-error-cooldown"),
    MENU_ERROR_EMPTY_HAND("menu-error-empty-hand"), MENU_ERROR_BAD_AMOUNT("menu-error-bad-amount"),
    MENU_ERROR_ITEM_CHANGED("menu-error-item-changed"),
}

class GiveawayLocale(
    dataRoot: Path,
    private val settings: () -> GiveawayConfig,
) {
    private val russian: Config = ConfigManager.of(dataRoot, "lang/ru.yml")
    private val english: Config = ConfigManager.of(dataRoot, "lang/en.yml")
    private val renderer = LocalizedMiniMessage(
        catalogs = mapOf("ru" to ConfigLocaleCatalog(russian), "en" to ConfigLocaleCatalog(english)),
        defaultLocale = { settings().defaultLocale },
    )

    fun render(
        key: MessageKey,
        audience: CommandSender? = null,
        values: Map<String, Component> = emptyMap(),
    ): Component {
        return renderer.render(key.path, localeTag(audience), values)
    }

    fun text(value: Any?): Component = renderer.literal(value)

    fun serverName(serverId: String, audience: CommandSender? = null): Component = render(
        when (serverId.lowercase()) {
            "spawn" -> MessageKey.SERVER_SPAWN
            "survival" -> MessageKey.SERVER_SURVIVAL
            "parkour" -> MessageKey.SERVER_PARKOUR
            else -> MessageKey.SERVER_OTHER
        },
        audience,
    )

    private fun localeTag(audience: CommandSender?): String =
        if (settings().useClientLocale && audience is Player) audience.locale().toLanguageTag()
        else settings().defaultLocale

    companion object {
        /** Upgrades only untouched bundled visual strings; operator-customized text is preserved. */
        fun upgradeBundledVisuals(dataRoot: Path) {
            VISUAL_MIGRATIONS.forEach { (language, replacements) ->
                val config = ConfigManager.of(dataRoot, "lang/$language.yml")
                var changed = false
                replacements.forEach { (path, migration) ->
                    val current = config.stringOrNull(path)
                    if (current == migration.legacy || current == migration.legacyNormalized) {
                        config.setString(path, migration.replacement)
                        changed = true
                    }
                }
                if (changed) config.saveStrict()
            }
        }

        fun validateFiles(dataRoot: Path) {
            listOf("ru", "en").forEach { language ->
                val config = Config(dataRoot, "lang/$language.yml")
                MessageKey.entries.forEach { key ->
                    require(config.stringOrNull(key.path)?.isNotBlank() == true) { "Locale $language is missing ${key.path}" }
                }
            }
        }

        private data class VisualMigration(val legacy: String, val replacement: String) {
            val legacyNormalized: String = legacy.replace(HEX_SHORTHAND) { match -> "<color:#${match.groupValues[1]}>" }
        }

        private val HEX_SHORTHAND = Regex("<#([0-9a-fA-F]{6})>")
        private val VISUAL_MIGRATIONS = mapOf(
            "ru" to mapOf(
                "announcement" to VisualMigration(
                    "<#92bed8>Раздача</color> <#666666>•</color> <#e6fff3><host> разыгрывает</color><newline><#666666>•</color> <item> <#8c8c8c>·</color> <#969696><seconds> сек.</color>",
                    "<gradient:#ff7a18:#ffd166><bold>✦ Раздача от <host> ✦</bold></gradient><newline><#fff3c4>Приз:</color> <item> <#ffcf70>• <seconds> сек. до розыгрыша</color>",
                ),
                "join-button" to VisualMigration(
                    "<#666666>•</color> <#2bba43><bold>[Присоединиться]</bold></color>",
                    "<gradient:#34d058:#7ee787><bold>▶ [Участвовать]</bold></gradient> <#ffe7a3>нажмите, чтобы залететь</color>",
                ),
                "countdown-title" to VisualMigration("<#92bed8><seconds></color>", "<gradient:#ff5f1f:#ffd166><bold><seconds></bold></gradient>"),
                "countdown-subtitle" to VisualMigration("<#969696>До розыгрыша</color>", "<#fff3c4>До розыгрыша <item> — приготовьтесь!</color>"),
                "drawing-title" to VisualMigration("<#92bed8>Выбираем победителя</color>", "<gradient:#ff5f1f:#ffd166><bold>Выбираем победителя</bold></gradient>"),
                "drawing-subtitle" to VisualMigration("<#e6fff3><candidate></color>", "<#fff3c4>✦ <candidate> ✦</color>"),
                "winner-network" to VisualMigration(
                    "<prefix> <#2bba43>Победитель: <winner></color><newline><#666666>•</color> <#969696>Приз:</color> <item><newline><#666666>•</color> <#969696>Ведущий: <host></color>",
                    "<gradient:#34d058:#ffd166><bold>✦ Победитель — <winner> ✦</bold></gradient><newline><#fff3c4>Приз:</color> <item><newline><#ffcf70>Раздачу устроил <host></color>",
                ),
                "winner-title" to VisualMigration("<#2bba43>Вы победили</color>", "<gradient:#34d058:#ffd166><bold>Вы победили!</bold></gradient>"),
            ),
            "en" to mapOf(
                "announcement" to VisualMigration(
                    "<#92bed8>Giveaway</color> <#666666>•</color> <#e6fff3><host> is giving away</color><newline><#666666>•</color> <item> <#8c8c8c>·</color> <#969696><seconds> sec.</color>",
                    "<gradient:#ff7a18:#ffd166><bold>✦ Giveaway by <host> ✦</bold></gradient><newline><#fff3c4>Prize:</color> <item> <#ffcf70>• draw in <seconds> sec.</color>",
                ),
                "join-button" to VisualMigration(
                    "<#666666>•</color> <#2bba43><bold>[Join]</bold></color>",
                    "<gradient:#34d058:#7ee787><bold>▶ [Join now]</bold></gradient> <#ffe7a3>click to jump in</color>",
                ),
                "countdown-title" to VisualMigration("<#92bed8><seconds></color>", "<gradient:#ff5f1f:#ffd166><bold><seconds></bold></gradient>"),
                "countdown-subtitle" to VisualMigration("<#969696>Until the draw</color>", "<#fff3c4><item> is almost yours — get ready!</color>"),
                "drawing-title" to VisualMigration("<#92bed8>Selecting the winner</color>", "<gradient:#ff5f1f:#ffd166><bold>Picking the winner</bold></gradient>"),
                "drawing-subtitle" to VisualMigration("<#e6fff3><candidate></color>", "<#fff3c4>✦ <candidate> ✦</color>"),
                "winner-network" to VisualMigration(
                    "<prefix> <#2bba43>Winner: <winner></color><newline><#666666>•</color> <#969696>Prize:</color> <item><newline><#666666>•</color> <#969696>Host: <host></color>",
                    "<gradient:#34d058:#ffd166><bold>✦ Winner — <winner> ✦</bold></gradient><newline><#fff3c4>Prize:</color> <item><newline><#ffcf70>Hosted by <host></color>",
                ),
                "winner-title" to VisualMigration("<#2bba43>You won</color>", "<gradient:#34d058:#ffd166><bold>You won!</bold></gradient>"),
            ),
        )
    }
}
