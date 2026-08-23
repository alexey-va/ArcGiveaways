package ru.ruscrafting.giveaways.paper

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.bukkit.inventory.ItemStack

class InventoryStackMatcherTest : StringSpec({
    "logically identical items match even when their serialized bytes differ" {
        val actual = mockk<ItemStack>()
        val expected = mockk<ItemStack>()
        every { actual.amount } returns 1
        every { expected.amount } returns 1
        every { actual.isSimilar(expected) } returns true
        every { actual.serializeAsBytes() } returns byteArrayOf(1, 2, 3)
        every { expected.serializeAsBytes() } returns byteArrayOf(4, 5, 6)

        actual.serializeAsBytes().contentEquals(expected.serializeAsBytes()) shouldBe false
        InventoryStackMatcher.matches(actual, expected) shouldBe true
    }

    "an identical item with a different amount does not match" {
        val actual = mockk<ItemStack>()
        val expected = mockk<ItemStack>()
        every { actual.amount } returns 2
        every { expected.amount } returns 1

        InventoryStackMatcher.matches(actual, expected) shouldBe false
    }

    "different item metadata does not match" {
        val actual = mockk<ItemStack>()
        val expected = mockk<ItemStack>()
        every { actual.amount } returns 1
        every { expected.amount } returns 1
        every { actual.isSimilar(expected) } returns false

        InventoryStackMatcher.matches(actual, expected) shouldBe false
    }

    "two empty journal slots are equivalent" {
        InventoryStackMatcher.matches(null, null) shouldBe true
    }

    "an item and an empty journal slot are not equivalent" {
        InventoryStackMatcher.matches(mockk(), null) shouldBe false
    }
})
