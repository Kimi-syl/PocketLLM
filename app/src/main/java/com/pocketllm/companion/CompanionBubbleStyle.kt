package com.pocketllm.companion

import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp

/**
 * The outline of the floating bubble.
 *
 * A list rather than an enum of lambdas because the id round-trips through
 * settings as a plain string; the shape itself is built on demand so nothing
 * holds a Compose object at class-load time.
 */
enum class BubbleShape(val id: String, val label: String) {
    Circle("circle", "Circle"),
    Squircle("squircle", "Squircle"),
    Rounded("rounded", "Rounded"),
    Square("square", "Square");

    /** Corner radius as a fraction of the bubble's size; 0.5 is a circle. */
    val cornerFraction: Float
        get() = when (this) {
            Circle -> 0.5f
            Squircle -> 0.3f
            Rounded -> 0.18f
            Square -> 0f
        }

    fun shapeFor(sizeDp: Int): Shape =
        if (this == Circle) CircleShape else RoundedCornerShape((sizeDp * cornerFraction).dp)

    companion object {
        fun byId(id: String?): BubbleShape =
            entries.firstOrNull { it.id == id } ?: Circle
    }
}

/**
 * What a bubble gesture does.
 *
 * "Off" is a real option on purpose: someone who keeps triggering a gesture by
 * accident should be able to turn it off rather than being told to be careful.
 */
enum class BubbleGesture(val id: String, val label: String) {
    Off("off", "Nothing"),
    Expand("expand", "Open the chat"),
    Summarize("summarize", "Summarize this page"),
    Explain("explain", "Explain this page"),
    Cheer("cheer", "Cheer me up"),
    LookUp("lookup", "Look up the last question"),
    NewChat("new_chat", "Start a new chat"),
    Hide("hide", "Hide the bubble");

    companion object {
        fun byId(id: String?): BubbleGesture =
            entries.firstOrNull { it.id == id } ?: Off
    }
}

/**
 * Faces offered as one-tap choices.
 *
 * The glyph field stays free-text for anything else, but typing an emoji means
 * finding it in the keyboard's picker, which is enough friction that most people
 * would never change the default.
 */
val BUBBLE_GLYPH_CHOICES: List<String> = listOf(
    "\uD83D\uDC31", // cat
    "\uD83D\uDC36", // dog
    "\uD83E\uDD8A", // fox
    "\uD83D\uDC3C", // panda
    "\uD83D\uDC30", // rabbit
    "\uD83D\uDC27", // bird
    "\uD83E\uDD16", // robot
    "\u2B50",       // star
    "\uD83C\uDF3F", // herb
    "\uD83D\uDCAB", // sparkle
    "\uD83C\uDF19", // moon
    "\uD83D\uDD25", // fire
)

/**
 * A one-tap bundle of bubble appearance settings.
 *
 * Exists because the individual controls are only useful once you already know
 * what you want; a preset is how someone gets a good-looking bubble without
 * touching six separate sliders.
 */
data class BubblePreset(
    val id: String,
    val label: String,
    val description: String,
    val size: Int,
    val shapeId: String,
    val borderWidth: Int,
    val glyphScale: Int,
    val idleAlpha: Int,
)

val BUBBLE_PRESETS: List<BubblePreset> = listOf(
    BubblePreset(
        id = "classic",
        label = "Classic",
        description = "Round, solid, always visible",
        size = 60,
        shapeId = BubbleShape.Circle.id,
        borderWidth = 0,
        glyphScale = 42,
        idleAlpha = 100,
    ),
    BubblePreset(
        id = "subtle",
        label = "Subtle",
        description = "Smaller, fades back when you ignore her",
        size = 52,
        shapeId = BubbleShape.Circle.id,
        borderWidth = 0,
        glyphScale = 46,
        idleAlpha = 55,
    ),
    BubblePreset(
        id = "bold",
        label = "Bold",
        description = "Big squircle with an outline",
        size = 74,
        shapeId = BubbleShape.Squircle.id,
        borderWidth = 3,
        glyphScale = 40,
        idleAlpha = 100,
    ),
    BubblePreset(
        id = "ghost",
        label = "Ghost",
        description = "Faint until touched",
        size = 56,
        shapeId = BubbleShape.Circle.id,
        borderWidth = 0,
        glyphScale = 44,
        idleAlpha = 30,
    ),
    BubblePreset(
        id = "tag",
        label = "Tag",
        description = "Rounded with a light outline",
        size = 58,
        shapeId = BubbleShape.Rounded.id,
        borderWidth = 2,
        glyphScale = 44,
        idleAlpha = 80,
    ),
)
