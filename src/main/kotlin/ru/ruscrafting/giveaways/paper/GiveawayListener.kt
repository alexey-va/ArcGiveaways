package ru.ruscrafting.giveaways.paper

import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryDragEvent
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.entity.Firework
import org.bukkit.event.player.PlayerDropItemEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerItemConsumeEvent
import org.bukkit.event.player.PlayerItemHeldEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.event.player.PlayerSwapHandItemsEvent

class GiveawayListener(private val service: GiveawayService) : Listener {
    @EventHandler fun onJoin(event: PlayerJoinEvent) = service.onPlayerJoin(event.player)
    @EventHandler fun onQuit(event: PlayerQuitEvent) = service.onPlayerQuit(event.player)

    @EventHandler fun onInventoryClick(event: InventoryClickEvent) {
        if (service.isInventoryLocked(event.whoClicked.uniqueId)) event.isCancelled = true
    }

    @EventHandler fun onInventoryDrag(event: InventoryDragEvent) {
        if (service.isInventoryLocked(event.whoClicked.uniqueId)) event.isCancelled = true
    }

    @EventHandler fun onDrop(event: PlayerDropItemEvent) {
        if (service.isInventoryLocked(event.player.uniqueId)) event.isCancelled = true
    }

    @EventHandler fun onSwap(event: PlayerSwapHandItemsEvent) {
        if (service.isInventoryLocked(event.player.uniqueId)) event.isCancelled = true
    }

    @EventHandler fun onHeld(event: PlayerItemHeldEvent) {
        if (service.isInventoryLocked(event.player.uniqueId)) event.isCancelled = true
    }

    @EventHandler fun onConsume(event: PlayerItemConsumeEvent) {
        if (service.isInventoryLocked(event.player.uniqueId)) event.isCancelled = true
    }

    @EventHandler fun onInteract(event: PlayerInteractEvent) {
        if (service.isInventoryLocked(event.player.uniqueId)) event.isCancelled = true
    }

    @EventHandler fun onVisualFireworkDamage(event: EntityDamageByEntityEvent) {
        val firework = event.damager as? Firework ?: return
        if (GiveawayService.VISUAL_FIREWORK_TAG in firework.scoreboardTags) event.isCancelled = true
    }
}
