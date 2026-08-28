package ru.ruscrafting.giveaways.paper

import net.kyori.adventure.text.Component
import net.kyori.adventure.title.Title
import org.bukkit.Location
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import java.util.concurrent.CompletableFuture

/** Feature-facing presentation boundary; message content and timing stay in GiveawayService. */
interface GiveawayPresentationPort {
    fun effectiveItemName(item: ItemStack): Component

    fun decorateItemHover(name: Component, item: ItemStack): Component

    fun showTitle(player: Player, title: Title)
}

/** Native Paper adapter. Its exact calls can move to Core without changing giveaway behavior. */
object NativeGiveawayPresentationPort : GiveawayPresentationPort {
    override fun effectiveItemName(item: ItemStack): Component = item.effectiveName()

    override fun decorateItemHover(name: Component, item: ItemStack): Component =
        name.hoverEvent(item.asHoverEvent())

    override fun showTitle(player: Player, title: Title) = player.showTitle(title)
}

/** Owns only giveaway arrival selection and the local Paper teleport request. */
interface GiveawayTravelPort {
    fun arrivalNear(host: Player): Location

    fun teleport(player: Player, destination: Location): CompletableFuture<Boolean>
}

object NativeGiveawayTravelPort : GiveawayTravelPort {
    override fun arrivalNear(host: Player): Location {
        val base = host.location
        val candidates = listOf(
            1.5 to 0.0,
            -1.5 to 0.0,
            0.0 to 1.5,
            0.0 to -1.5,
        ).map { (x, z) -> base.clone().add(x, 0.0, z) }
        return candidates.firstOrNull { location ->
            location.block.isPassable &&
                location.clone().add(0.0, 1.0, 0.0).block.isPassable &&
                location.clone().subtract(0.0, 1.0, 0.0).block.type.isSolid
        } ?: base.clone()
    }

    override fun teleport(player: Player, destination: Location): CompletableFuture<Boolean> =
        player.teleportAsync(destination)
}

/** Exact persistence seam used only after an inventory mutation verifies. */
fun interface GiveawayPlayerDataPersistence {
    fun persist(player: Player)
}

object NativeGiveawayPlayerDataPersistence : GiveawayPlayerDataPersistence {
    override fun persist(player: Player) = player.saveData()
}
