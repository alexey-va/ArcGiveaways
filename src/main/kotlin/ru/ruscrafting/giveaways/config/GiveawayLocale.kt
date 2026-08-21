package ru.ruscrafting.giveaways.config

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.minimessage.MiniMessage
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import ru.arc.config.Config
import ru.arc.config.ConfigManager
import java.nio.file.Path

enum class MessageKey(val path: String) {
    PREFIX("prefix"), HELP("help"), PLAYER_ONLY("player-only"), NO_PERMISSION("no-permission"),
    REDIS_UNAVAILABLE("redis-unavailable"), EMPTY_HAND("empty-hand"), BAD_AMOUNT("bad-amount"),
    BUSY("busy"), COOLDOWN("cooldown"), STARTING("starting"), STARTED("started"), START_FAILED("start-failed"),
    ANNOUNCEMENT_LINE("announcement-line"), ANNOUNCEMENT("announcement"), ANNOUNCEMENT_HOVER("announcement-hover"),
    JOIN_BUTTON("join-button"), JOIN_HOVER_LOCAL("join-hover-local"), JOIN_HOVER_TRANSFER("join-hover-transfer"),
    TRANSFERRING("transferring"), NOT_FOUND("not-found"), NOT_OPEN("not-open"), TOO_FAR("too-far"),
    WRONG_WORLD("wrong-world"), JOINED("joined"), ALREADY_JOINED("already-joined"), FULL("full"),
    HOST_CANNOT_JOIN("host-cannot-join"), STATUS_EMPTY("status-empty"), STATUS_ENTRY("status-entry"),
    COUNTDOWN_TITLE("countdown-title"), COUNTDOWN_SUBTITLE("countdown-subtitle"), DRAWING_TITLE("drawing-title"),
    DRAWING_SUBTITLE("drawing-subtitle"), NOT_ENOUGH("not-enough"), CANCELLED("cancelled"),
    CANCEL_DENIED("cancel-denied"), WINNER_NETWORK("winner-network"), WINNER_TITLE("winner-title"),
    WINNER_SUBTITLE("winner-subtitle"), DELIVERY_PENDING("delivery-pending"), DELIVERED("delivered"),
    NOTHING_TO_CLAIM("nothing-to-claim"), CLAIM_RETRYING("claim-retrying"), RELOAD_OK("reload-ok"),
    RELOAD_FAILED("reload-failed"), GENERIC_ERROR("generic-error"),
}

class GiveawayLocale(
    dataRoot: Path,
    private val settings: () -> GiveawayConfig,
) {
    private val russian: Config = ConfigManager.of(dataRoot, "lang/ru.yml")
    private val english: Config = ConfigManager.of(dataRoot, "lang/en.yml")
    private val mini = MiniMessage.miniMessage()

    fun render(
        key: MessageKey,
        audience: CommandSender? = null,
        values: Map<String, Component> = emptyMap(),
    ): Component {
        val config = select(audience)
        val fallback = if (settings().defaultLocale == "en") english else russian
        val raw = config.stringOrNull(key.path)?.takeIf(String::isNotBlank)
            ?: fallback.stringOrNull(key.path)?.takeIf(String::isNotBlank)
            ?: key.path
        val prefixRaw = config.stringOrNull(MessageKey.PREFIX.path)?.takeIf(String::isNotBlank)
            ?: fallback.string(MessageKey.PREFIX.path, "<gray>[Giveaway]</gray>")
        val builder = TagResolver.builder().resolver(Placeholder.component("prefix", mini.deserialize(prefixRaw)))
        values.forEach { (name, value) -> builder.resolver(Placeholder.component(name, value)) }
        return mini.deserialize(raw, builder.build())
    }

    fun text(value: Any?): Component = Component.text(value?.toString().orEmpty())

    private fun select(audience: CommandSender?): Config {
        if (!settings().useClientLocale || audience !is Player) return if (settings().defaultLocale == "en") english else russian
        return if (audience.locale().language.equals("ru", ignoreCase = true)) russian else english
    }

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
