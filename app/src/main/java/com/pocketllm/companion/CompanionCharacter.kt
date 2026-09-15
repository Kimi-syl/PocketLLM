package com.pocketllm.companion

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/**
 * The companion's drawn character.
 *
 * Deliberately code-drawn rather than a Live2D model or a sprite sheet: it ships
 * at zero bytes, has no art dependency, needs no license, and the parts can be
 * animated independently. A `.moc3` model would look better but is gated on
 * illustration work and a Cubism Editor project that cannot be produced here.
 */
enum class CompanionSpecies(val id: String, val label: String) {
    Cat("cat", "Cat"),
    Bunny("bunny", "Bunny"),
    Fox("fox", "Fox");

    val palette: CharacterPalette
        get() = when (this) {
            Cat -> CharacterPalette(
                fur = Color(0xFFF5C177),
                furDark = Color(0xFFDD9F4E),
                muzzle = Color(0xFFFDF3E3),
                earInner = Color(0xFFF2A9B8),
                pupil = Color(0xFF3A2E2A),
                blush = Color(0xFFF79AA8),
            )
            Bunny -> CharacterPalette(
                fur = Color(0xFFF6F1EA),
                furDark = Color(0xFFD8CEC2),
                muzzle = Color(0xFFFFFFFF),
                earInner = Color(0xFFF3B7C4),
                pupil = Color(0xFF3A2E2A),
                blush = Color(0xFFF7A8B6),
            )
            Fox -> CharacterPalette(
                fur = Color(0xFFE8834A),
                furDark = Color(0xFFC96A33),
                muzzle = Color(0xFFFBF0E4),
                earInner = Color(0xFF4A3226),
                pupil = Color(0xFF2E2622),
                blush = Color(0xFFF08A72),
            )
        }

    companion object {
        /** Null when the id is "off" or unrecognised, which means "draw the emoji". */
        fun byId(id: String?): CompanionSpecies? = entries.firstOrNull { it.id == id }
    }
}

/** Colours for one species, so the drawing code holds no hard-coded palette. */
data class CharacterPalette(
    val fur: Color,
    val furDark: Color,
    val muzzle: Color,
    val earInner: Color,
    val pupil: Color,
    val blush: Color,
    val mouthOpen: Color = Color(0xFF7A3B43),
    val tongue: Color = Color(0xFFEE8A9A),
)

/**
 * How much of the character fits in the window.
 *
 * [Bust] zooms in on the head so a small circular bubble is filled by a face.
 * [Full] fits the whole figure, which is what the free-standing mode uses.
 * One set of drawing code serves both; only the viewport changes.
 */
enum class CharacterFit { Bust, Full }

/**
 * The character's facial expression.
 *
 * Kept separate from mood: mood is how the user says *they* feel, expression is
 * how she looks. "auto" maps the user's recent mood onto her face so she reacts
 * to it, which is the point of her having one.
 */
enum class CompanionExpression(val id: String, val label: String) {
    Neutral("neutral", "Neutral"),
    Happy("happy", "Happy"),
    Cheer("cheer", "Delighted"),
    Sad("sad", "Sympathetic"),
    Thinking("thinking", "Thinking"),
    Sleepy("sleepy", "Sleepy"),
    Surprised("surprised", "Surprised");

    companion object {
        const val AUTO = "auto"

        fun byId(id: String?): CompanionExpression =
            entries.firstOrNull { it.id == id } ?: Neutral

        /** Maps a 1-5 mood score onto a face; null (unknown) stays neutral. */
        fun fromMood(score: Int?): CompanionExpression = when {
            score == null -> Neutral
            score <= 2 -> Sad
            score == 3 -> Neutral
            score == 4 -> Happy
            else -> Cheer
        }
    }
}

/** One frame of animation, computed on the clock and read by the draw pass. */
private data class CompanionPose(
    val breath: Float = 0f,
    val blink: Float = 0f,
    val lookX: Float = 0f,
    val lookY: Float = 0f,
    val mouth: Float = 0f,
    val earTwitch: Float = 0f,
    val tail: Float = 0f,
)

private const val TAU = (2.0 * PI).toFloat()

/**
 * Animation rate.
 *
 * 30 rather than 60 on purpose. This draws inside a foreground overlay service,
 * and the device this runs on kills processes that burn CPU; the motions here are
 * slow enough (breath, blink, sway) that 30fps is indistinguishable, and it halves
 * the work of the always-on case.
 */
private const val FRAME_FPS = 30L
private const val BLINK_SECONDS = 0.13f
private const val EAR_TWITCH_SECONDS = 0.35f

/** How long she takes to get up or settle back down. */
private const val POSTURE_MS = 700

/**
 * The standing figure, in a unit space where -0.5..0.5 spans the whole body.
 * Every feature is placed relative to these, so the proportions cannot drift
 * apart between the standing, lying, and bust viewports.
 */
private const val HEAD_CX = 0f
private const val HEAD_CY = -0.285f
private const val HEAD_R = 0.165f
private const val BODY_HALF_W = 0.185f

private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t

/**
 * The figure's geometry for a given posture.
 *
 * [t] is 1 when standing and 0 when lying down. Every landmark is interpolated
 * rather than switched, so she eases between the two instead of snapping — and
 * because the lying pose is mostly *the same parts moved*, no second drawing
 * routine is needed.
 *
 * Lying is a resting sprawl: the body flattens and widens, the head drops down
 * and off to one side, the arms tuck under, the feet disappear, and the tail
 * curls around the front.
 */
private class Figure(t: Float) {
    val headCx = lerp(0.25f, HEAD_CX, t)
    val headCy = lerp(-0.085f, HEAD_CY, t)
    val headR = lerp(0.150f, HEAD_R, t)

    val bodyCx = lerp(-0.07f, 0f, t)
    val bodyCy = lerp(0.055f, 0.04f, t)
    val bodyHalfW = lerp(0.30f, BODY_HALF_W, t)
    val bodyHalfH = lerp(0.155f, 0.17f, t)

    val armOuterX = lerp(0.26f, 0.20f, t)
    val armTop = lerp(0.05f, -0.07f, t)
    val armH = lerp(0.09f, 0.26f, t)

    /** Feet are tucked away when lying, so they fade out with the posture. */
    val feetAlpha = t

    val tailStartX = lerp(-0.26f, 0.17f, t)
    val tailStartY = lerp(0.15f, 0.10f, t)
    val tailC1X = lerp(-0.45f, 0.36f, t)
    val tailC1Y = lerp(0.20f, 0.12f, t)
    val tailC2X = lerp(-0.50f, 0.46f, t)
    val tailC2Y = lerp(0.00f, 0.06f, t)
    val tailTipX = lerp(-0.40f, 0.42f, t)
    val tailTipY = lerp(-0.16f, -0.02f, t)

    /** Extents, so the viewport can fit whatever posture is being drawn. */
    val halfWidth = lerp(0.42f, 0.46f, t)
    val yMin = lerp(-0.30f, -0.49f, t)
    val yMax = lerp(0.24f, 0.355f, t)
    val midY = (yMin + yMax) / 2f

    /** Drowsy half-lids while she is resting. */
    val drowsy = (1f - t) * 0.34f
}

/**
 * The animated character.
 *
 * [talking] drives the mouth only, and is read through [rememberUpdatedState] so
 * that starting or finishing a reply does not restart the animation and reset the
 * blink — which would make her eyes pop open every time she spoke.
 *
 * [upright] is the posture: true stands her up, false lies her down. The change
 * is animated rather than instant, so being moved looks like her getting up.
 */
@Composable
fun AnimatedCompanion(
    species: CompanionSpecies,
    expression: CompanionExpression,
    talking: Boolean,
    modifier: Modifier = Modifier,
    fit: CharacterFit = CharacterFit.Bust,
    animate: Boolean = true,
    upright: Boolean = true,
) {
    var pose by remember { mutableStateOf(CompanionPose()) }
    val talkingNow by rememberUpdatedState(talking)

    // A bust has no room to lie down, so the posture is only meaningful in the
    // free-standing viewport; holding it at standing keeps the bubble unchanged.
    val posture = remember(fit) {
        Animatable(if (fit == CharacterFit.Full && !upright) 0f else 1f)
    }
    LaunchedEffect(upright, fit) {
        val target = if (fit == CharacterFit.Full && !upright) 0f else 1f
        if (posture.value != target) posture.animateTo(target, tween(POSTURE_MS))
    }

    LaunchedEffect(species, animate) {
        if (!animate) {
            pose = CompanionPose()
            return@LaunchedEffect
        }
        var lastNanos = 0L
        var elapsed = 0f
        var blinkIn = 1.2f + Random.nextFloat() * 2.6f
        var blinkAt = -1f
        var twitchIn = 5f + Random.nextFloat() * 7f
        var twitchAt = -1f
        var mouthPhase = 0f
        var gateNanos = 0L
        val frameInterval = 1_000_000_000L / FRAME_FPS

        while (true) {
            withFrameNanos { now ->
                if (lastNanos != 0L) {
                    // Clamped so a stalled frame (device waking, GC pause) does not
                    // jump the animation forward by the whole gap.
                    val dt = ((now - lastNanos).coerceAtMost(120_000_000L)) / 1_000_000_000f
                    elapsed += dt

                    if (blinkAt >= 0f) {
                        blinkAt += dt
                        if (blinkAt >= BLINK_SECONDS) {
                            blinkAt = -1f
                            blinkIn = 1.6f + Random.nextFloat() * 3.4f
                        }
                    } else {
                        blinkIn -= dt
                        if (blinkIn <= 0f) blinkAt = 0f
                    }

                    if (twitchAt >= 0f) {
                        twitchAt += dt
                        if (twitchAt >= EAR_TWITCH_SECONDS) {
                            twitchAt = -1f
                            twitchIn = 6f + Random.nextFloat() * 9f
                        }
                    } else {
                        twitchIn -= dt
                        if (twitchIn <= 0f) twitchAt = 0f
                    }

                    mouthPhase += dt

                    // Only publish a new pose on the frame we intend to draw, so the
                    // state write (and the redraw it triggers) happens at most at
                    // FRAME_FPS rather than at the display's rate.
                    if (now >= gateNanos) {
                        gateNanos = now + frameInterval
                        pose = CompanionPose(
                            breath = sin(elapsed * TAU / 3.8f),
                            blink = if (blinkAt < 0f) 0f
                            else sin((blinkAt / BLINK_SECONDS) * PI).toFloat(),
                            lookX = sin(elapsed * TAU / 5.3f) * 0.6f +
                                sin(elapsed * TAU / 13f) * 0.4f,
                            lookY = cos(elapsed * TAU / 6.7f) * 0.5f,
                            mouth = if (talkingNow) {
                                (sin(mouthPhase * TAU / 0.34f) + 1f) / 2f
                            } else 0f,
                            earTwitch = if (twitchAt < 0f) 0f
                            else sin((twitchAt / EAR_TWITCH_SECONDS) * PI).toFloat(),
                            tail = sin(elapsed * TAU / 4.2f),
                        )
                    }
                }
                lastNanos = now
            }
        }
    }

    Canvas(modifier = modifier) {
        drawCompanion(species, expression, pose, talkingNow, fit, posture.value)
    }
}

/** Maps the unit design space onto the canvas. +y points down. */
private class Geo(private val originX: Float, private val originY: Float, private val s: Float) {
    fun x(u: Float) = originX + u * s
    fun y(v: Float) = originY + v * s
    fun len(u: Float) = u * s
}

/**
 * Head features, expressed in multiples of the head radius.
 *
 * Everything about the face is relative to the radius rather than to the canvas,
 * so the identical proportions hold whether she is a 30dp avatar or a 300dp
 * free-standing character.
 */
private class Head(val cx: Float, val cy: Float, val r: Float) {
    fun x(u: Float) = cx + u * r
    fun y(v: Float) = cy + v * r
    fun len(u: Float) = u * r
}

private fun DrawScope.drawCompanion(
    species: CompanionSpecies,
    expression: CompanionExpression,
    pose: CompanionPose,
    talking: Boolean,
    fit: CharacterFit,
    posture: Float,
) {
    val figure = Figure(posture)
    val scaleUnit = when (fit) {
        CharacterFit.Bust -> size.minDimension * 1.9f
        // Fit the posture's real extents, so lying down fills the window instead
        // of leaving her small in the corner of a box sized for a standing figure.
        CharacterFit.Full -> min(
            size.width / (figure.halfWidth * 2f),
            size.height / (figure.yMax - figure.yMin),
        )
    }
    val originX = size.width / 2f
    val originY = when (fit) {
        CharacterFit.Bust -> size.height / 2f - HEAD_CY * scaleUnit
        CharacterFit.Full -> size.height / 2f - figure.midY * scaleUnit
    }
    val geo = Geo(originX, originY, scaleUnit)
    val palette = species.palette

    // Breathing, applied to the whole figure as a slight vertical squash about
    // the base, so the body and head move together the way a single mass would.
    val squash = 1f + 0.022f * pose.breath

    scale(1f, squash, pivot = Offset(geo.x(0f), geo.y(0.30f))) {
        drawTail(geo, figure, pose, palette)
        drawFeet(geo, figure, palette)
        drawBody(geo, figure, palette)
        drawArms(geo, figure, palette)

        val head = Head(
            cx = geo.x(figure.headCx),
            // The head lags the body a fraction, which is what makes it read as a
            // bob rather than the whole character scaling.
            cy = geo.y(figure.headCy) + geo.len(0.011f * pose.breath),
            r = geo.len(figure.headR),
        )
        drawEars(head, species, pose, palette, upright = posture)
        drawHead(head, species, palette)
        drawFace(head, species, expression, pose, talking, palette, figure.drowsy)
    }
}

/** Torso. Drawn before the head so the head overlaps it. */
private fun DrawScope.drawBody(geo: Geo, figure: Figure, palette: CharacterPalette) {
    drawOval(
        color = palette.fur,
        topLeft = Offset(
            geo.x(figure.bodyCx - figure.bodyHalfW),
            geo.y(figure.bodyCy - figure.bodyHalfH),
        ),
        size = Size(geo.len(figure.bodyHalfW * 2f), geo.len(figure.bodyHalfH * 2f)),
    )
    // Lighter chest, so the torso is not one flat mass.
    drawOval(
        color = palette.muzzle,
        topLeft = Offset(
            geo.x(figure.bodyCx - figure.bodyHalfW * 0.55f),
            geo.y(figure.bodyCy - figure.bodyHalfH * 0.72f),
        ),
        size = Size(geo.len(figure.bodyHalfW * 1.10f), geo.len(figure.bodyHalfH * 1.44f)),
    )
}

private fun DrawScope.drawArms(geo: Geo, figure: Figure, palette: CharacterPalette) {
    for (side in intArrayOf(-1, 1)) {
        val cx = figure.bodyCx + side * figure.armOuterX
        drawOval(
            color = palette.fur,
            topLeft = Offset(
                geo.x(cx - 0.05f),
                geo.y(figure.armTop),
            ),
            size = Size(geo.len(0.10f), geo.len(figure.armH)),
        )
    }
}

private fun DrawScope.drawFeet(geo: Geo, figure: Figure, palette: CharacterPalette) {
    if (figure.feetAlpha <= 0.01f) return
    for (side in intArrayOf(-1, 1)) {
        drawOval(
            color = palette.fur.copy(alpha = figure.feetAlpha),
            topLeft = Offset(geo.x(side * 0.085f) - geo.len(0.075f), geo.y(0.245f)),
            size = Size(geo.len(0.15f), geo.len(0.11f)),
        )
    }
}

/** Sways gently; the slowest motion on screen, which sells "alive" cheaply. */
private fun DrawScope.drawTail(
    geo: Geo,
    figure: Figure,
    pose: CompanionPose,
    palette: CharacterPalette,
) {
    val sway = pose.tail * 0.04f
    val path = Path().apply {
        moveTo(geo.x(figure.tailStartX), geo.y(figure.tailStartY))
        cubicTo(
            geo.x(figure.tailC1X), geo.y(figure.tailC1Y),
            geo.x(figure.tailC2X), geo.y(figure.tailC2Y + sway),
            geo.x(figure.tailTipX), geo.y(figure.tailTipY + sway),
        )
    }
    drawPath(path, palette.furDark, style = Stroke(width = geo.len(0.045f), cap = StrokeCap.Round))
}

private fun DrawScope.drawEars(
    head: Head,
    species: CompanionSpecies,
    pose: CompanionPose,
    palette: CharacterPalette,
    upright: Float,
) {
    for (side in intArrayOf(-1, 1)) {
        // The twitch moves only the tip, so the ear pivots at its base rather
        // than sliding sideways off the head. Resting ears also sit lower, which
        // is most of what makes a lying animal read as relaxed.
        val droop = (1f - upright) * 0.22f
        val twitch = pose.earTwitch * 0.12f * side
        val outer = Path()
        val inner = Path()

        when (species) {
            CompanionSpecies.Bunny -> {
                outer.moveTo(head.x(side * 0.18f), head.y(-0.42f))
                outer.cubicTo(
                    head.x(side * 0.14f), head.y(-0.85f + droop),
                    head.x(side * (0.30f + twitch)), head.y(-1.22f + droop * 2f),
                    head.x(side * (0.42f + twitch)), head.y(-1.20f + droop * 2f),
                )
                outer.cubicTo(
                    head.x(side * 0.56f), head.y(-1.17f + droop * 2f),
                    head.x(side * 0.60f), head.y(-0.80f + droop),
                    head.x(side * 0.55f), head.y(-0.42f),
                )
                outer.close()

                inner.moveTo(head.x(side * 0.27f), head.y(-0.47f))
                inner.cubicTo(
                    head.x(side * 0.24f), head.y(-0.79f + droop),
                    head.x(side * (0.34f + twitch)), head.y(-1.09f + droop * 2f),
                    head.x(side * (0.42f + twitch)), head.y(-1.07f + droop * 2f),
                )
                inner.cubicTo(
                    head.x(side * 0.51f), head.y(-1.05f + droop * 2f),
                    head.x(side * 0.53f), head.y(-0.78f + droop),
                    head.x(side * 0.50f), head.y(-0.47f),
                )
                inner.close()
            }
            else -> {
                // A triangle, then the same triangle shrunk toward its centroid
                // for the inner ear, so the two can never fall out of register.
                val points = if (species == CompanionSpecies.Fox) {
                    listOf(
                        Pair(0.86f, -0.30f + droop),
                        Pair(0.14f, -0.56f + droop),
                        Pair(0.72f + twitch, -1.20f + droop * 2f),
                    )
                } else {
                    listOf(
                        Pair(0.84f, -0.26f + droop),
                        Pair(0.16f, -0.60f + droop),
                        Pair(0.66f + twitch, -1.25f + droop * 2f),
                    )
                }
                outer.moveTo(head.x(side * points[0].first), head.y(points[0].second))
                outer.lineTo(head.x(side * points[1].first), head.y(points[1].second))
                outer.lineTo(head.x(side * points[2].first), head.y(points[2].second))
                outer.close()

                val cxAvg = points.map { it.first }.average().toFloat()
                val cyAvg = points.map { it.second }.average().toFloat()
                val shrink = 0.5f
                fun ix(u: Float) = u + (u - cxAvg) * shrink
                fun iy(v: Float) = v + (v - cyAvg) * shrink
                inner.moveTo(head.x(side * ix(points[0].first)), head.y(iy(points[0].second)))
                inner.lineTo(head.x(side * ix(points[1].first)), head.y(iy(points[1].second)))
                inner.lineTo(head.x(side * ix(points[2].first)), head.y(iy(points[2].second)))
                inner.close()
            }
        }

        drawPath(outer, palette.fur)
        drawPath(inner, palette.earInner)
    }
}

private fun DrawScope.drawHead(head: Head, species: CompanionSpecies, palette: CharacterPalette) {
    drawCircle(palette.fur, radius = head.r, center = Offset(head.cx, head.cy))

    // Two soft stripes on the brow for the cats and foxes, so the head is not a
    // plain circle. Skipped for the bunny, whose long ears already break it up.
    if (species != CompanionSpecies.Bunny) {
        for (side in intArrayOf(-1, 1)) {
            for (i in 0..1) {
                val u = side * (0.24f + i * 0.18f)
                drawLine(
                    color = palette.furDark.copy(alpha = 0.5f),
                    start = Offset(head.x(u), head.y(-0.86f)),
                    end = Offset(head.x(u * 0.92f), head.y(-0.53f)),
                    strokeWidth = head.len(0.07f),
                    cap = StrokeCap.Round,
                )
            }
        }
    }
}

private fun DrawScope.drawFace(
    head: Head,
    species: CompanionSpecies,
    expression: CompanionExpression,
    pose: CompanionPose,
    talking: Boolean,
    palette: CharacterPalette,
    drowsy: Float,
) {
    val sleepy = expression == CompanionExpression.Sleepy
    // A sleepy character sits half-lidded rather than blinking more often; a
    // resting one is half-lidded for the same reason.
    val base = if (sleepy) 0.55f else drowsy
    val blink = (base + pose.blink * (1f - base)).coerceAtMost(1f)
    val squint = expression == CompanionExpression.Happy || expression == CompanionExpression.Cheer
    val eyeRadius = head.len(0.20f) *
        if (expression == CompanionExpression.Surprised) 1.25f else 1f
    val lookX = if (expression == CompanionExpression.Thinking) -0.8f else pose.lookX
    val lookY = if (expression == CompanionExpression.Thinking) -0.9f else pose.lookY

    for (side in intArrayOf(-1, 1)) {
        drawEye(
            cx = head.x(side * 0.34f),
            cy = head.y(-0.11f),
            radius = eyeRadius,
            blink = blink,
            lookX = lookX,
            lookY = lookY,
            palette = palette,
            squint = squint,
        )
    }

    // Blush: strongest when delighted, absent when sympathetic — a sad character
    // with rosy cheeks reads as embarrassed instead.
    val blushAlpha = when (expression) {
        CompanionExpression.Cheer -> 0.85f
        CompanionExpression.Happy -> 0.55f
        CompanionExpression.Surprised -> 0.4f
        CompanionExpression.Sad, CompanionExpression.Sleepy -> 0f
        else -> 0.28f
    }
    if (blushAlpha > 0f) {
        for (side in intArrayOf(-1, 1)) {
            drawCircle(
                color = palette.blush.copy(alpha = blushAlpha),
                radius = head.len(0.155f),
                center = Offset(head.x(side * 0.53f), head.y(0.16f)),
            )
        }
    }

    // Muzzle and nose.
    drawOval(
        color = palette.muzzle,
        topLeft = Offset(head.x(-0.37f), head.y(0.06f)),
        size = Size(head.len(0.74f), head.len(0.48f)),
    )
    val nose = Path().apply {
        moveTo(head.x(-0.09f), head.y(0.10f))
        lineTo(head.x(0.09f), head.y(0.10f))
        lineTo(head.x(0f), head.y(0.22f))
        close()
    }
    drawPath(nose, palette.pupil.copy(alpha = 0.82f))

    drawMouth(
        cx = head.x(0f),
        cy = head.y(0.25f),
        width = head.len(0.97f),
        expression = expression,
        talking = talking,
        mouth = pose.mouth,
        palette = palette,
    )

    // Whiskers, only for the species that have them.
    if (species != CompanionSpecies.Bunny) {
        val lineWidth = head.len(0.035f)
        for (side in intArrayOf(-1, 1)) {
            val pairs = listOf(
                Triple(0.34f, 0.05f, -0.18f),
                Triple(0.36f, 0.18f, 0.04f),
                Triple(0.34f, 0.31f, 0.25f),
            )
            for ((startX, startY, endY) in pairs) {
                drawLine(
                    color = palette.furDark.copy(alpha = 0.75f),
                    start = Offset(head.x(side * startX), head.y(startY)),
                    end = Offset(head.x(side * 0.97f), head.y(endY)),
                    strokeWidth = lineWidth,
                    cap = StrokeCap.Round,
                )
            }
        }
    }
}

private fun DrawScope.drawEye(
    cx: Float,
    cy: Float,
    radius: Float,
    blink: Float,
    lookX: Float,
    lookY: Float,
    palette: CharacterPalette,
    squint: Boolean,
) {
    val stroke = radius * 0.42f

    // A happy squint is an upward arc rather than a closed eye; drawing it as a
    // full blink would make delight look like sleep.
    if (squint) {
        val path = Path().apply {
            moveTo(cx - radius, cy + radius * 0.34f)
            quadraticTo(cx, cy - radius * 0.98f, cx + radius, cy + radius * 0.34f)
        }
        drawPath(path, palette.pupil, style = Stroke(width = stroke, cap = StrokeCap.Round))
        return
    }

    val lid = blink.coerceIn(0f, 1f)
    val openHeight = radius * 2f * (1f - lid * 0.95f)
    drawOval(
        color = Color.White,
        topLeft = Offset(cx - radius, cy - openHeight / 2f),
        size = Size(radius * 2f, openHeight),
    )

    if (lid > 0.7f) {
        // Nearly shut: a lid line reads better than a sliver of white.
        drawLine(
            color = palette.pupil,
            start = Offset(cx - radius * 0.8f, cy),
            end = Offset(cx + radius * 0.8f, cy),
            strokeWidth = stroke * 0.75f,
            cap = StrokeCap.Round,
        )
        return
    }

    val pupilRadius = radius * 0.5f
    val px = cx + lookX * radius * 0.30f
    val py = cy + lookY * radius * 0.22f
    drawCircle(palette.pupil, pupilRadius, Offset(px, py))
    // Catchlight; without it the eyes look dead.
    drawCircle(
        color = Color.White.copy(alpha = 0.92f),
        radius = pupilRadius * 0.34f,
        center = Offset(px - pupilRadius * 0.34f, py - pupilRadius * 0.38f),
    )
}

private fun DrawScope.drawMouth(
    cx: Float,
    cy: Float,
    width: Float,
    expression: CompanionExpression,
    talking: Boolean,
    mouth: Float,
    palette: CharacterPalette,
) {
    val lineWidth = width * 0.10f

    // Talking wins over the expression, so she still animates while speaking a
    // happy or sad reply.
    if (talking && mouth > 0.02f) {
        val halfHeight = width * (0.05f + mouth * 0.17f)
        drawOval(
            color = palette.mouthOpen,
            topLeft = Offset(cx - width * 0.30f, cy),
            size = Size(width * 0.60f, halfHeight * 2f),
        )
        drawOval(
            color = palette.tongue,
            topLeft = Offset(cx - width * 0.16f, cy + halfHeight * 0.72f),
            size = Size(width * 0.32f, halfHeight * 0.9f),
        )
        return
    }

    when (expression) {
        CompanionExpression.Surprised -> {
            drawOval(
                color = palette.mouthOpen,
                topLeft = Offset(cx - width * 0.17f, cy - width * 0.04f),
                size = Size(width * 0.34f, width * 0.38f),
            )
        }
        CompanionExpression.Happy, CompanionExpression.Cheer -> {
            val r = Rect(cx - width * 0.44f, cy - width * 0.42f, cx + width * 0.44f, cy + width * 0.30f)
            drawArc(
                color = palette.pupil,
                startAngle = 20f,
                sweepAngle = 140f,
                useCenter = false,
                topLeft = Offset(r.left, r.top),
                size = Size(r.width, r.height),
                style = Stroke(width = lineWidth, cap = StrokeCap.Round),
            )
        }
        CompanionExpression.Sad -> {
            val r = Rect(cx - width * 0.38f, cy - width * 0.02f, cx + width * 0.38f, cy + width * 0.62f)
            drawArc(
                color = palette.pupil,
                startAngle = 200f,
                sweepAngle = 140f,
                useCenter = false,
                topLeft = Offset(r.left, r.top),
                size = Size(r.width, r.height),
                style = Stroke(width = lineWidth, cap = StrokeCap.Round),
            )
        }
        else -> {
            // A small "w", the resting mouth.
            val path = Path().apply {
                moveTo(cx, cy)
                quadraticTo(cx - width * 0.17f, cy + width * 0.24f, cx - width * 0.33f, cy + width * 0.02f)
                moveTo(cx, cy)
                quadraticTo(cx + width * 0.17f, cy + width * 0.24f, cx + width * 0.33f, cy + width * 0.02f)
            }
            drawPath(path, palette.pupil, style = Stroke(lineWidth, cap = StrokeCap.Round))
        }
    }
}
