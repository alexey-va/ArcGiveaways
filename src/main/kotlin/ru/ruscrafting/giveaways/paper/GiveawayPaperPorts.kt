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
import kotlin.math.sqrt

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

    fun isGlowing(player: Player): Boolean

    fun setGlowing(player: Player, glowing: Boolean)

    fun renderScene(center: Location, scene: GiveawayVisualScene, spec: GiveawaySceneSpec)

    fun launchFirework(center: Location, scene: GiveawayVisualScene, style: GiveawayFireworkStyle, variant: Int)
}

/** Native Paper adapter. Its exact calls can move to Core without changing giveaway behavior. */
object NativeGiveawayPresentationPort : GiveawayPresentationPort {
    override fun effectiveItemName(item: ItemStack): Component = item.effectiveName()

    override fun decorateItemHover(name: Component, item: ItemStack): Component =
        name.hoverEvent(item.asHoverEvent())

    override fun showTitle(player: Player, title: Title) = player.showTitle(title)

    override fun isGlowing(player: Player): Boolean = player.isGlowing

    override fun setGlowing(player: Player, glowing: Boolean) {
        player.isGlowing = glowing
    }

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
            val rotated = List(colors.size) { index -> colors[(index + variant).mod(colors.size)] }
            addEffect(
                FireworkEffect.builder()
                    .with(types[variant.mod(types.size)])
                    .withColor(rotated)
                    .withFade(fades)
                    .trail(true)
                    .flicker(true)
                    .build(),
            )
            addEffect(
                FireworkEffect.builder()
                    .with(types[(variant + 1).mod(types.size)])
                    .withColor(rotated.reversed())
                    .withFade(fades.reversed())
                    .trail(variant % 2 == 0)
                    .flicker(true)
                    .build(),
            )
        }
    }

    private fun ambient(center: Location, spec: GiveawaySceneSpec) {
        val origin = center.clone().add(0.0, 1.35, 0.0)
        val atomBudget = spec.particleCount * 3 / 5
        val perOrbit = (atomBudget / 3).coerceAtLeast(1)
        val phase = phase(origin)
        dustOrbit(origin, perOrbit, spec.radius, OrbitPlane.HORIZONTAL, WARM_COLORS, phase)
        dustOrbit(origin, perOrbit, spec.radius * 0.86, OrbitPlane.VERTICAL_X, WARM_COLORS.reversed(), -phase * 1.2)
        dustOrbit(origin, perOrbit, spec.radius * 0.86, OrbitPlane.VERTICAL_Z, AURORA_COLORS, phase * 1.35)
        val crownBudget = (spec.particleCount / 5).coerceAtLeast(1)
        dustStar(origin.clone().subtract(0.0, 1.05, 0.0), crownBudget, spec.radius * 0.82, 8, WARM_COLORS, -phase)
        val sparks = (spec.particleCount - perOrbit * 3 - crownBudget).coerceAtLeast(0)
        if (sparks > 0) {
            origin.world.spawnParticle(
                Particle.END_ROD,
                origin,
                sparks,
                spec.radius * 0.38,
                1.15,
                spec.radius * 0.38,
                0.025,
            )
        }
    }

    private fun countdown(center: Location, spec: GiveawaySceneSpec) {
        val origin = center.clone().add(0.0, 0.1, 0.0)
        val phase = phase(origin)
        val sparks = (spec.particleCount / 9).coerceAtLeast(1)
        val starBudget = (spec.particleCount * 2 / 5).coerceAtLeast(1)
        dustStar(origin, starBudget, spec.radius, 10, RAINBOW_COLORS, phase)
        val ringsBudget = (spec.particleCount - sparks - starBudget).coerceAtLeast(3)
        val perRing = (ringsBudget / 3).coerceAtLeast(1)
        dustHorizontalRing(origin, perRing, spec.radius * 0.62, 0.55, RAINBOW_COLORS, -phase)
        dustHorizontalRing(origin, perRing, spec.radius * 0.78, 1.35, AURORA_COLORS, phase * 1.4)
        dustHorizontalRing(origin, perRing, spec.radius * 0.48, 2.15, WARM_COLORS, -phase * 1.8)
        origin.world.spawnParticle(
            Particle.FIREWORK,
            origin.clone().add(0.0, 1.15, 0.0),
            sparks,
            spec.radius * 0.32,
            1.25,
            spec.radius * 0.32,
            0.12,
        )
    }

    private fun drawing(center: Location, spec: GiveawaySceneSpec) {
        val origin = center.clone().add(0.0, 1.55, 0.0)
        val phase = phase(origin)
        val sparks = (spec.particleCount / 10).coerceAtLeast(1)
        val satelliteBudget = (spec.particleCount / 4).coerceAtLeast(6)
        val helixBudget = (spec.particleCount - sparks - satelliteBudget).coerceAtLeast(3)
        dustTripleHelix(origin, helixBudget, spec.radius, RAINBOW_COLORS, phase)
        dustSatellites(origin, satelliteBudget, spec.radius * 0.82, AURORA_COLORS, -phase * 1.6)
        origin.world.spawnParticle(
            Particle.END_ROD,
            origin,
            sparks,
            spec.radius * 0.28,
            1.65,
            spec.radius * 0.28,
            0.06,
        )
    }

    private fun winner(center: Location, spec: GiveawaySceneSpec) {
        val origin = center.clone().add(0.0, 2.0, 0.0)
        val phase = phase(origin)
        val ambientBurst = (spec.particleCount / 5).coerceAtLeast(3)
        val geometryBudget = (spec.particleCount - ambientBurst).coerceAtLeast(3)
        val sphereBudget = (geometryBudget * 9 / 20).coerceAtLeast(1)
        val rayBudget = (geometryBudget * 3 / 10).coerceAtLeast(1)
        val ringBudget = (geometryBudget - sphereBudget - rayBudget).coerceAtLeast(3)
        dustSphere(origin, sphereBudget, spec.radius * 0.72, RAINBOW_COLORS, phase)
        dustRays(origin, rayBudget, spec.radius, 18, RAINBOW_COLORS, phase)
        val perRing = (ringBudget / 3).coerceAtLeast(1)
        dustHorizontalRing(origin.clone().subtract(0.0, 1.65, 0.0), perRing, spec.radius * 0.7, 0.0, RAINBOW_COLORS, phase)
        dustOrbit(origin, perRing, spec.radius * 0.58, OrbitPlane.VERTICAL_X, AURORA_COLORS, -phase)
        dustOrbit(origin, perRing, spec.radius * 0.58, OrbitPlane.VERTICAL_Z, WARM_COLORS, phase * 1.4)
        val totems = (ambientBurst * 3 / 5).coerceAtLeast(1)
        val fireworks = (ambientBurst - totems).coerceAtLeast(1)
        origin.world.spawnParticle(
            Particle.TOTEM_OF_UNDYING,
            origin,
            totems,
            spec.radius * 0.52,
            1.8,
            spec.radius * 0.52,
            0.18,
        )
        origin.world.spawnParticle(
            Particle.FIREWORK,
            origin,
            fireworks,
            spec.radius * 0.48,
            1.6,
            spec.radius * 0.48,
            0.2,
        )
        origin.world.spawnParticle(Particle.HAPPY_VILLAGER, origin, (ambientBurst / 6).coerceAtLeast(1), 1.1, 1.5, 1.1, 0.11)
    }

    private fun dustHorizontalRing(
        center: Location,
        count: Int,
        radius: Double,
        height: Double,
        colors: List<Color>,
        phase: Double,
    ) {
        repeat(count.coerceAtLeast(1)) { index ->
            val angle = phase + index * (PI * 2.0 / count.coerceAtLeast(1))
            val point = center.clone().add(cos(angle) * radius, height, sin(angle) * radius)
            dust(center, point, colors[index.mod(colors.size)], 1.25f)
        }
    }

    private fun dustOrbit(
        center: Location,
        count: Int,
        radius: Double,
        plane: OrbitPlane,
        colors: List<Color>,
        phase: Double,
    ) {
        repeat(count.coerceAtLeast(1)) { index ->
            val angle = phase + index * (PI * 2.0 / count.coerceAtLeast(1))
            val a = cos(angle) * radius
            val b = sin(angle) * radius
            val point = when (plane) {
                OrbitPlane.HORIZONTAL -> center.clone().add(a, 0.0, b)
                OrbitPlane.VERTICAL_X -> center.clone().add(a, b, 0.0)
                OrbitPlane.VERTICAL_Z -> center.clone().add(0.0, b, a)
            }
            dust(center, point, colors[index.mod(colors.size)], 1.15f)
        }
    }

    private fun dustStar(
        center: Location,
        count: Int,
        radius: Double,
        arms: Int,
        colors: List<Color>,
        phase: Double,
    ) {
        val vertices = arms * 2
        repeat(count.coerceAtLeast(1)) { index ->
            val path = index.toDouble() / count.coerceAtLeast(1) * vertices
            val vertex = path.toInt().mod(vertices)
            val progress = path - path.toInt()
            fun vertexPoint(offset: Int): Pair<Double, Double> {
                val current = (vertex + offset).mod(vertices)
                val angle = phase + current * PI / arms
                val currentRadius = if (current % 2 == 0) radius else radius * 0.42
                return cos(angle) * currentRadius to sin(angle) * currentRadius
            }
            val from = vertexPoint(0)
            val to = vertexPoint(1)
            val x = from.first + (to.first - from.first) * progress
            val z = from.second + (to.second - from.second) * progress
            dust(center, center.clone().add(x, 0.22, z), colors[index.mod(colors.size)], 1.35f)
        }
    }

    private fun dustTripleHelix(
        center: Location,
        count: Int,
        radius: Double,
        colors: List<Color>,
        phase: Double,
    ) {
        val strands = 3
        val perStrand = (count / strands).coerceAtLeast(1)
        repeat(count.coerceAtLeast(1)) { index ->
            val strand = index.mod(strands)
            val progress = (index / strands).toDouble() / perStrand
            val angle = phase + progress * PI * 8.0 + strand * PI * 2.0 / strands
            val currentRadius = radius * (0.35 + 0.35 * sin(progress * PI))
            val point = center.clone().add(
                cos(angle) * currentRadius,
                -1.5 + progress * 3.4,
                sin(angle) * currentRadius,
            )
            dust(center, point, colors[(index + strand).mod(colors.size)], 1.3f)
        }
    }

    private fun dustSatellites(
        center: Location,
        count: Int,
        orbitRadius: Double,
        colors: List<Color>,
        phase: Double,
    ) {
        val nodes = 6
        val perNode = (count / nodes).coerceAtLeast(1)
        repeat(count.coerceAtLeast(1)) { index ->
            val node = index.mod(nodes)
            val local = index / nodes
            val nodeAngle = phase + node * PI * 2.0 / nodes
            val localAngle = phase * 1.7 + local * PI * 2.0 / perNode
            val nodeCenter = center.clone().add(
                cos(nodeAngle) * orbitRadius,
                -0.9 + (node % 3) * 0.9,
                sin(nodeAngle) * orbitRadius,
            )
            val point = nodeCenter.add(cos(localAngle) * 0.42, sin(localAngle) * 0.42, 0.0)
            dust(center, point, colors[index.mod(colors.size)], 1.4f)
        }
    }

    private fun dustSphere(
        center: Location,
        count: Int,
        radius: Double,
        colors: List<Color>,
        phase: Double,
    ) {
        val goldenAngle = PI * (3.0 - sqrt(5.0))
        repeat(count.coerceAtLeast(1)) { index ->
            val y = 1.0 - 2.0 * (index + 0.5) / count.coerceAtLeast(1)
            val horizontal = sqrt((1.0 - y * y).coerceAtLeast(0.0))
            val angle = phase + goldenAngle * index
            val point = center.clone().add(
                cos(angle) * horizontal * radius,
                y * radius * 0.72,
                sin(angle) * horizontal * radius,
            )
            dust(center, point, colors[index.mod(colors.size)], 1.45f)
        }
    }

    private fun dustRays(
        center: Location,
        count: Int,
        radius: Double,
        rayCount: Int,
        colors: List<Color>,
        phase: Double,
    ) {
        val pointsPerRay = (count / rayCount).coerceAtLeast(1)
        repeat(count.coerceAtLeast(1)) { index ->
            val ray = index.mod(rayCount)
            val step = index / rayCount
            val distance = radius * (step + 1.0) / (pointsPerRay + 1.0)
            val angle = phase + ray * PI * 2.0 / rayCount
            val elevation = when (ray.mod(3)) {
                0 -> -0.28
                1 -> 0.12
                else -> 0.48
            }
            val horizontal = cos(elevation)
            val point = center.clone().add(
                cos(angle) * horizontal * distance,
                sin(elevation) * distance,
                sin(angle) * horizontal * distance,
            )
            dust(center, point, colors[index.mod(colors.size)], 1.55f)
        }
    }

    private fun dust(worldAnchor: Location, point: Location, color: Color, size: Float) {
        worldAnchor.world.spawnParticle(
            Particle.DUST,
            point,
            1,
            0.0,
            0.0,
            0.0,
            0.0,
            Particle.DustOptions(color, size),
        )
    }

    private fun phase(location: Location): Double = (location.world.gameTime % 360L) * PI / 180.0

    private fun parseColor(value: String): Color = Color.fromRGB(value.removePrefix("#").toInt(16))

    private enum class OrbitPlane { HORIZONTAL, VERTICAL_X, VERTICAL_Z }

    private val WARM_COLORS = listOf(
        Color.fromRGB(0xff4d00),
        Color.fromRGB(0xff8a00),
        Color.fromRGB(0xffd166),
        Color.fromRGB(0xfff3b0),
    )
    private val AURORA_COLORS = listOf(
        Color.fromRGB(0x38d9ff),
        Color.fromRGB(0x7c4dff),
        Color.fromRGB(0xff2d95),
        Color.fromRGB(0x7dff84),
    )
    private val RAINBOW_COLORS = listOf(
        Color.fromRGB(0xff3b30),
        Color.fromRGB(0xff9500),
        Color.fromRGB(0xffd60a),
        Color.fromRGB(0x34c759),
        Color.fromRGB(0x32ade6),
        Color.fromRGB(0x5856d6),
        Color.fromRGB(0xaf52de),
        Color.fromRGB(0xff2d55),
    )
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
