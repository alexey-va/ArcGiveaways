package ru.ruscrafting.giveaways.paper

import io.kotest.core.spec.style.StringSpec
import io.mockk.mockk
import io.mockk.verify
import org.bukkit.entity.Player
import org.bukkit.entity.Projectile
import org.bukkit.event.entity.EntityDamageByEntityEvent

class GiveawayListenerTest : StringSpec({
    "player damage is cancelled when either side has giveaway protection" {
        val service = mockk<GiveawayService>()
        val attacker = mockk<Player>()
        val victim = mockk<Player>()
        val event = mockk<EntityDamageByEntityEvent>(relaxed = true)
        io.mockk.every { event.damager } returns attacker
        io.mockk.every { event.entity } returns victim

        GiveawayListener(service) { actualAttacker, actualVictim ->
            actualAttacker === attacker && actualVictim === victim
        }.onEntityDamageByEntity(event)

        verify(exactly = 1) { event.isCancelled = true }
    }

    "ordinary player damage remains unchanged" {
        val service = mockk<GiveawayService>()
        val attacker = mockk<Player>()
        val victim = mockk<Player>()
        val event = mockk<EntityDamageByEntityEvent>(relaxed = true)
        io.mockk.every { event.damager } returns attacker
        io.mockk.every { event.entity } returns victim

        GiveawayListener(service) { _, _ -> false }.onEntityDamageByEntity(event)

        verify(exactly = 0) { event.isCancelled = true }
    }

    "projectiles fired by a protected player are also cancelled" {
        val service = mockk<GiveawayService>()
        val attacker = mockk<Player>()
        val victim = mockk<Player>()
        val projectile = mockk<Projectile>()
        val event = mockk<EntityDamageByEntityEvent>(relaxed = true)
        io.mockk.every { projectile.shooter } returns attacker
        io.mockk.every { event.damager } returns projectile
        io.mockk.every { event.entity } returns victim

        GiveawayListener(service) { actualAttacker, actualVictim ->
            actualAttacker === attacker && actualVictim === victim
        }.onEntityDamageByEntity(event)

        verify(exactly = 1) { event.isCancelled = true }
    }
})
