package ru.ruscrafting.giveaways.paper

import net.kyori.adventure.text.Component
import net.kyori.adventure.title.Title
import org.bukkit.Color
import org.bukkit.FireworkEffect
import org.bukkit.Location
import org.bukkit.Particle
import org.bukkit.entity.Firework
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import ru.ruscrafting.giveaways.config.GiveawayFireworkStyle
import java.util.concurrent.CompletableFuture
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

enum class GiveawayVisualScene {
    AMBIENT,
    COUNTDOWN,
    DRAWING,
    WINNER,
}

data class GiveawaySceneSpec(
    val particleCount: Int,
    val radius: Double,
)

/** Feature-facing presentation boundary; message content and timing stay in GiveawayService. */
interface GiveawayPresentationPort {
    fun effectiveItemName(item: ItemStack): Component

    fun decorateItemHover(name: Component, item: ItemStack): Component

    fun showTitle(player: Player, title: Title)

    fun renderScene(center: Location, scene: GiveawayVisualScene, spec: GiveawaySceneSpec)

    fun launchFirework(center: Location, scene: GiveawayVisualScene, style: GiveawayFireworkStyle, variant: Int)
}

/** Native Paper adapter. Its exact calls can move to Core without changing giveaway behavior. */
object NativeGiveawayPresentationPort : GiveawayPresentationPort {
    override fun effectiveItemName(item: ItemStack): Component = item.effectiveName()

    override fun decorateItemHover(name: Component, item: ItemStack): Component =
        name.hoverEvent(item.asHoverEvent())

    override fun showTitle(player: Player, title: Title) = player.showTitle(title)

    override fun renderScene(center: Location, scene: GiveawayVisualScene, spec: GiveawaySceneSpec) {
        if (spec.particleCount <= 0) return
        when (scene) {
            GiveawayVisualScene.AMBIENT -> ambient(center, spec)
            GiveawayVisualScene.COUNTDOWN -> countdown(center, spec)
            GiveawayVisualScene.DRAWING -> drawing(center, spec)
            GiveawayVisualScene.WINNER -> winner(center, spec)
        }
    }

    override fun launchFirework(
        center: Location,
        scene: GiveawayVisualScene,
        style: GiveawayFireworkStyle,
        variant: Int,
    ) {
        val colors = style.colors.map(::parseColor)
        val fades = style.fadeColors.map(::parseColor)
        val angle = variant * (PI * 2.0 / 5.0)
        val launchRadius = if (scene == GiveawayVisualScene.WINNER) 1.15 else 0.55
        val launch = center.clone().add(cos(angle) * launchRadius, 0.2, sin(angle) * launchRadius)
        val firework = launch.world.spawn(launch, Firework::class.java)
        firework.addScoreboardTag(GiveawayService.VISUAL_FIREWORK_TAG)
        firework.fireworkMeta = firework.fireworkMeta.apply {
            power = style.power
            val types = listOf(FireworkEffect.Type.BALL_LARGE, FireworkEffect.Type.STAR, FireworkEffect.Type.BURST)
            val primary = colors[variant.mod(colors.size)]
            val secondary = colors[(variant + 1).mod(colors.size)]
            addEffect(
                FireworkEffect.builder()
                    .with(types[variant.mod(types.size)])
                    .withColor(listOf(primary, secondary))
                    .withFade(fades)
                    .trail(true)
                    .flicker(variant % 2 == 0)
                    .build(),
            )
        }
    }

    private fun ambient(center: Location, spec: GiveawaySceneSpec) {
        val origin = center.clone().add(0.0, 0.15, 0.0)
        val ringCount = (spec.particleCount * 3 / 4).coerceAtLeast(1)
        dustRing(origin, ringCount, spec.radius, 0.25, Color.fromRGB(0xff7a18), 1.05f, phase(origin))
        val sparks = spec.particleCount - ringCount
        if (sparks > 0) {
            origin.world.spawnParticle(
                Particle.END_ROD,
                origin.clone().add(0.0, 1.1, 0.0),
                sparks,
                spec.radius * 0.55,
                0.85,
                spec.radius * 0.55,
                0.015,
            )
        }
    }

    private fun countdown(center: Location, spec: GiveawaySceneSpec) {
        val origin = center.clone().add(0.0, 0.1, 0.0)
        val sparks = (spec.particleCount / 8).coerceAtLeast(1)
        val dust = (spec.particleCount - sparks).coerceAtLeast(1)
        val outer = (dust * 2 / 3).coerceAtLeast(1)
        dustRing(origin, outer, spec.radius, 0.35, Color.fromRGB(0xff5f1f), 1.35f, phase(origin))
        dustRing(
            origin,
            (dust - outer).coerceAtLeast(1),
            spec.radius * 0.62,
            1.45,
            Color.fromRGB(0xffd166),
            1.15f,
            -phase(origin),
        )
        origin.world.spawnParticle(Particle.FIREWORK, origin.clone().add(0.0, 1.0, 0.0), sparks, 0.45, 0.8, 0.45, 0.08)
    }

    private fun drawing(center: Location, spec: GiveawaySceneSpec) {
        val origin = center.clone().add(0.0, 0.15, 0.0)
        val phase = phase(origin)
        val sparks = (spec.particleCount / 7).coerceAtLeast(1)
        val helixCount = (spec.particleCount - sparks).coerceAtLeast(1)
        repeat(helixCount) { index ->
            val progress = index.toDouble() / helixCount
            val angle = phase + progress * PI * 6.0
            val radius = spec.radius * (0.35 + progress * 0.45)
            val point = origin.clone().add(cos(angle) * radius, 0.2 + progress * 2.7, sin(angle) * radius)
            val color = if (index % 2 == 0) Color.fromRGB(0xffd166) else Color.fromRGB(0xb86cff)
            origin.world.spawnParticle(Particle.DUST, point, 1, 0.0, 0.0, 0.0, 0.0, Particle.DustOptions(color, 1.15f))
        }
        origin.world.spawnParticle(Particle.END_ROD, origin.clone().add(0.0, 1.2, 0.0), sparks, 0.35, 1.1, 0.35, 0.035)
    }

    private fun winner(center: Location, spec: GiveawaySceneSpec) {
        val origin = center.clone().add(0.0, 0.2, 0.0)
        val villagers = (spec.particleCount / 14).coerceAtLeast(1)
        val sceneBudget = (spec.particleCount - villagers).coerceAtLeast(1)
        val ringParticles = (sceneBudget * 3 / 5).coerceAtLeast(3)
        val perRing = (ringParticles / 3).coerceAtLeast(1)
        val colors = listOf(Color.fromRGB(0xff6b00), Color.fromRGB(0xffd166), Color.fromRGB(0xff2d95))
        colors.forEachIndexed { index, color ->
            dustRing(
                origin,
                perRing,
                spec.radius * (0.55 + index * 0.2),
                0.55 + index * 0.8,
                color,
                1.45f,
                phase(origin) + index * PI / 3.0,
            )
        }
        val burstParticles = (sceneBudget - perRing * 3).coerceAtLeast(1)
        val totems = (burstParticles * 2 / 3).coerceAtLeast(1)
        origin.world.spawnParticle(
            Particle.TOTEM_OF_UNDYING,
            origin.clone().add(0.0, 1.2, 0.0),
            totems,
            spec.radius * 0.55,
            1.35,
            spec.radius * 0.55,
            0.12,
        )
        origin.world.spawnParticle(
            Particle.FIREWORK,
            origin.clone().add(0.0, 1.1, 0.0),
            (burstParticles - totems).coerceAtLeast(1),
            spec.radius * 0.45,
            1.0,
            spec.radius * 0.45,
            0.14,
        )
        origin.world.spawnParticle(Particle.HAPPY_VILLAGER, origin.clone().add(0.0, 1.0, 0.0), villagers, 0.8, 1.0, 0.8, 0.08)
    }

    private fun dustRing(
        center: Location,
        count: Int,
        radius: Double,
        height: Double,
        color: Color,
        size: Float,
        phase: Double,
    ) {
        repeat(count.coerceAtLeast(1)) { index ->
            val angle = phase + index * (PI * 2.0 / count.coerceAtLeast(1))
            val point = center.clone().add(cos(angle) * radius, height, sin(angle) * radius)
            center.world.spawnParticle(Particle.DUST, point, 1, 0.0, 0.0, 0.0, 0.0, Particle.DustOptions(color, size))
        }
    }

    private fun phase(location: Location): Double = (location.world.gameTime % 360L) * PI / 180.0

    private fun parseColor(value: String): Color = Color.fromRGB(value.removePrefix("#").toInt(16))
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
