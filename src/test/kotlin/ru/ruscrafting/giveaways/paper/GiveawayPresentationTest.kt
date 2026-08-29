package ru.ruscrafting.giveaways.paper

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.Location
import org.bukkit.World
import org.bukkit.entity.Display
import org.bukkit.entity.ItemDisplay
import org.bukkit.inventory.ItemStack
import org.bukkit.util.Transformation
import java.util.function.Consumer

class GiveawayPresentationTest : StringSpec({
    "chat announcement is isolated by a blank line above and below" {
        val rendered = GiveawayPresentation.chatAnnouncement(Component.text("body"), Component.text("join"))

        PlainTextComponentSerializer.plainText().serialize(rendered) shouldBe "\nbody\njoin\n"
    }

    "native item display preserves the escrow stack and owns its entity lifecycle" {
        val world = mockk<World>()
        val display = mockk<ItemDisplay>(relaxed = true)
        every { display.isValid } returns true
        every { display.teleport(any<Location>()) } returns true
        every {
            world.spawn(any<Location>(), ItemDisplay::class.java, any<Consumer<in ItemDisplay>>())
        } answers {
            thirdArg<Consumer<ItemDisplay>>().accept(display)
            display
        }
        val item = mockk<ItemStack>()
        val clonedItem = mockk<ItemStack>()
        every { item.clone() } returns clonedItem
        val initial = GiveawayItemDisplayPose(Location(world, 2.0, 70.5, -3.0), 45f)

        val handle = NativeGiveawayPresentationPort.createItemDisplay(item, initial, 1.25f)

        verify { display.setItemStack(clonedItem) }
        verify { display.itemDisplayTransform = ItemDisplay.ItemDisplayTransform.FIXED }
        verify { display.billboard = Display.Billboard.FIXED }
        verify { display.transformation = match<Transformation> { it.scale.x == 1.25f } }
        verify { display.isPersistent = false }
        verify { display.addScoreboardTag(GiveawayService.VISUAL_ITEM_DISPLAY_TAG) }

        handle.update(GiveawayItemDisplayPose(Location(world, 5.0, 71.0, 4.0), 90f)) shouldBe true
        verify {
            display.teleport(match<Location> {
                it.x == 5.0 && it.y == 71.0 && it.z == 4.0 && it.yaw == 90f
            })
        }

        handle.remove()
        verify { display.remove() }
    }
})
