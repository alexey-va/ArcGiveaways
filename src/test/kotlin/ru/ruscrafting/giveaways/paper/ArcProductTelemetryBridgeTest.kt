package ru.ruscrafting.giveaways.paper

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import ru.arc.paper.testing.MockBukkitTestRuntime
import java.net.URLClassLoader
import java.util.UUID

class ArcProductTelemetryBridgeTest : StringSpec({
    "fails closed when ARC is absent" {
        MockBukkitTestRuntime.open().use { runtime ->
            runtime.server.pluginManager.isPluginEnabled("ARC") shouldBe false
            ArcProductTelemetryBridge.itemGranted(UUID.randomUUID(), "giveaway:missing:player") shouldBe false
        }
    }

    "does not load the optional ARC API when ARC is absent" {
        MockBukkitTestRuntime.open().use { runtime ->
            runtime.server.pluginManager.isPluginEnabled("ARC") shouldBe false
            val targetName = "ru.ruscrafting.giveaways.paper.ArcProductTelemetryBridge"
            val source = ArcProductTelemetryBridge::class.java.protectionDomain.codeSource.location
            var apiLoads = 0
            object : URLClassLoader(arrayOf(source), ArcProductTelemetryBridge::class.java.classLoader) {
                override fun loadClass(name: String, resolve: Boolean): Class<*> {
                    if (name.startsWith("ru.arc.paper.api.")) {
                        apiLoads++
                        throw ClassNotFoundException(name)
                    }
                    if (name.startsWith(targetName)) {
                        return (findLoadedClass(name) ?: findClass(name)).also {
                            if (resolve) resolveClass(it)
                        }
                    }
                    return super.loadClass(name, resolve)
                }
            }.use { isolated ->
                val type = isolated.loadClass(targetName)
                val instance = type.getField("INSTANCE").get(null)
                type.getMethod("itemGranted", UUID::class.java, String::class.java)
                    .invoke(instance, UUID.randomUUID(), "giveaway:missing:api") shouldBe false
                apiLoads shouldBe 0
            }
        }
    }

    "keeps the grant event contract and stable operation id" {
        val playerId = UUID.randomUUID()
        val calls = mutableListOf<List<Any>>()
        ArcProductTelemetryBridge.recordWith(
            gateway = { id, source, event, operationId ->
                calls += listOf(id, source, event, operationId)
                true
            },
            playerId,
            "giveaway:1:$playerId",
        ) shouldBe true
        calls shouldContainExactly listOf(listOf(playerId, "arcgiveaways", "giveaway_item_granted", "giveaway:1:$playerId"))
    }

    "fails closed when the optional gateway throws" {
        ArcProductTelemetryBridge.recordWith({ _, _, _, _ -> error("ARC unavailable") }, UUID.randomUUID(), "giveaway:throw") shouldBe false
    }
})
