package ru.ruscrafting.giveaways.paper

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import net.kyori.adventure.text.Component
import org.bukkit.inventory.ItemStack
import java.nio.file.Files
import java.nio.file.Path
import java.util.logging.Logger

/** Renders vanilla item names on the server so an English client cannot leak English into Russian chat. */
class RussianItemNames(
    private val catalogPath: Path,
    private val logger: Logger,
) {
    @Volatile
    private var translations: Map<String, String> = emptyMap()

    init {
        reload()
    }

    fun reload() {
        translations = runCatching {
            require(Files.isRegularFile(catalogPath)) { "catalog is missing: $catalogPath" }
            Files.newBufferedReader(catalogPath).use { reader ->
                val type = object : TypeToken<Map<String, String>>() {}.type
                Gson().fromJson<Map<String, String>>(reader, type).orEmpty()
            }
        }.onFailure {
            logger.warning("Could not load the Russian item-name catalog; client translations will be used: ${it.message}")
        }.getOrDefault(emptyMap())
    }

    fun displayName(item: ItemStack): Component {
        val effectiveName = item.effectiveName()
        val meta = item.itemMeta
        return localize(
            effectiveName = effectiveName,
            translationKey = item.translationKey(),
            customName = meta.hasDisplayName() || meta.hasItemName(),
        )
    }

    internal fun localize(effectiveName: Component, translationKey: String, customName: Boolean = false): Component {
        if (customName) return effectiveName
        val translated = translations[translationKey]?.takeIf(String::isNotBlank) ?: return effectiveName
        return Component.text(translated)
            .style(effectiveName.style())
    }
}
