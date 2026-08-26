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
    SERVER_SPAWN("server-spawn"), SERVER_SURVIVAL("server-survival"), SERVER_PARKOUR("server-parkour"),
    SERVER_OTHER("server-other"),
    TRANSFERRING("transferring"), NOT_FOUND("not-found"), NOT_OPEN("not-open"), TOO_FAR("too-far"),
    WRONG_WORLD("wrong-world"), JOINED("joined"), ALREADY_JOINED("already-joined"), FULL("full"),
    HOST_CANNOT_JOIN("host-cannot-join"), STATUS_EMPTY("status-empty"), STATUS_ENTRY("status-entry"),
    COUNTDOWN_TITLE("countdown-title"), COUNTDOWN_SUBTITLE("countdown-subtitle"), DRAWING_TITLE("drawing-title"),
    DRAWING_SUBTITLE("drawing-subtitle"), NOT_ENOUGH("not-enough"), CANCELLED("cancelled"),
    CANCEL_DENIED("cancel-denied"), WINNER_NETWORK("winner-network"), WINNER_TITLE("winner-title"),
    WINNER_SUBTITLE("winner-subtitle"), DELIVERY_PENDING("delivery-pending"), DELIVERED("delivered"),
    CLAIM_TRANSFERRING("claim-transferring"),
    NOTHING_TO_CLAIM("nothing-to-claim"), CLAIM_RETRYING("claim-retrying"), RELOAD_OK("reload-ok"),
    RELOAD_FAILED("reload-failed"), TELEPORT_FAILED("teleport-failed"), PVP_PROTECTED("pvp-protected"),
    GENERIC_ERROR("generic-error"),
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
        fun validateFiles(dataRoot: Path) {
            listOf("ru", "en").forEach { language ->
                val config = Config(dataRoot, "lang/$language.yml")
                MessageKey.entries.forEach { key ->
                    require(config.stringOrNull(key.path)?.isNotBlank() == true) { "Locale $language is missing ${key.path}" }
                }
            }
        }
    }
}
