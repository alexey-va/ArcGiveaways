package ru.ruscrafting.giveaways.paper

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class InventoryRecoveryIncidentTrackerTest : StringSpec({
    "an ambiguous journal is reported once until its state is cleared" {
        val tracker = InventoryRecoveryIncidentTracker()

        tracker.markAmbiguous("giveaway:refund") shouldBe true
        tracker.markAmbiguous("giveaway:refund") shouldBe false

        tracker.clear("giveaway:refund")

        tracker.markAmbiguous("giveaway:refund") shouldBe true
    }
})
