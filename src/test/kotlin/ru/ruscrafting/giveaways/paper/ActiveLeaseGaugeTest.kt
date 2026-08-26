package ru.ruscrafting.giveaways.paper

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class ActiveLeaseGaugeTest : StringSpec({
    "first inactive observation and duplicate transitions never underflow or drift" {
        val gauge = ActiveLeaseGauge()

        gauge.transition(previousActive = false, currentActive = false)
        gauge.count() shouldBe 0

        gauge.transition(previousActive = false, currentActive = true)
        gauge.transition(previousActive = true, currentActive = true)
        gauge.count() shouldBe 1

        gauge.transition(previousActive = true, currentActive = false)
        gauge.transition(previousActive = true, currentActive = false)
        gauge.count() shouldBe 0
    }
})
