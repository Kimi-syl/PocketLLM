package com.pocketllm.companion

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pocketllm.ui.theme.PocketLLMTheme
import kotlinx.coroutines.delay

/** One line in the companion conversation. */
data class CompanionMsg(val fromUser: Boolean, val text: String)

/**
 * Observable state shared between [CompanionOverlayService] and the composables
 * below. Snapshot state means a plain mutation from the service recomposes the
 * overlay — no extra plumbing needed.
 */
class CompanionUiState {
    var expanded by mutableStateOf(false)
    var status by mutableStateOf("")
    var busy by mutableStateOf(false)
    var input by mutableStateOf("")
    val messages = mutableStateListOf<CompanionMsg>()

    /** Microphone is on and the recognizer is capturing. */
    var listening by mutableStateOf(false)
    /** Whether the mic button should be offered at all. */
    var voiceInputEnabled by mutableStateOf(false)
    /**
     * She has asked how they are and is waiting for one of the mood faces.
     * While true the action chips are replaced by the mood row.
     */
    var awaitingMood by mutableStateOf(false)
    /** Face tapped for a mood check-in. */
    var onMoodPicked: ((Int) -> Unit)? = null

    /** Identity / appearance, mirrored from settings so the overlay reflects them live. */
    var name by mutableStateOf("Momo")
    var glyph by mutableStateOf("\uD83D\uDC31")
    var bubbleSizeDp by mutableStateOf(60)
    var bubbleAlpha by mutableStateOf(1f)
    /** Explicit bubble tint; null follows the app theme. */
    var bubbleColor by mutableStateOf<Color?>(null)
    /** Outline of the bubble. */
    var bubbleShape by mutableStateOf(BubbleShape.Circle)
    var bubbleBorderWidthDp by mutableStateOf(0)
    /** Explicit outline colour; null derives one from the fill. */
    var bubbleBorderColor by mutableStateOf<Color?>(null)
    /** Glyph size as a percentage of the bubble. */
    var glyphScale by mutableStateOf(42)
    /**
     * Multiplier applied to the bubble's opacity while it is idle, so she can
     * recede without disappearing. 1f disables the dimming.
     */
    var bubbleIdleFactor by mutableStateOf(0.65f)
    /** True briefly after a touch, so the bubble shows at full strength. */
    var bubbleAwake by mutableStateOf(true)
    /** What the bubble gestures do; see [BubbleGesture]. */
    var doubleTapAction by mutableStateOf(BubbleGesture.Off)
    var longPressAction by mutableStateOf(BubbleGesture.Expand)
    /** Panel text scale, 1.0 being the designed size. */
    var panelFontScale by mutableStateOf(1f)

    var onSend: ((String) -> Unit)? = null
    var onSummarize: (() -> Unit)? = null
    /** Explain the current page in plain language. */
    var onExplain: (() -> Unit)? = null
    /** One-tap emotional support opener. */
    var onCheer: (() -> Unit)? = null
    /** Search the web and answer from the results. */
    var onLookUp: (() -> Unit)? = null
    /** Ask how they are and offer the mood faces. */
    var onMoodCheckIn: (() -> Unit)? = null
    /** Start a fresh conversation and forget the stored transcript. */
    var onNewChat: (() -> Unit)? = null
    /** Bubble tapped: open the chat panel. */
    var onExpand: (() -> Unit)? = null
    /** A double-tap or long-press fired; carries what it should do. */
    var onBubbleGesture: ((BubbleGesture) -> Unit)? = null
    /** The bubble was touched, so it should show at full strength. */
    var onWake: (() -> Unit)? = null
    /** Panel "Hide" tapped: shrink back to the bubble. */
    var onCollapse: (() -> Unit)? = null
    /** Microphone button tapped: start or stop listening. */
    var onMicToggle: (() -> Unit)? = null
    /** Stop button tapped while she is mid-reply. */
    var onStop: (() -> Unit)? = null
    /** Translate the current page. */
    var onTranslate: (() -> Unit)? = null
    /** Guided breathing is running and drawn over the panel. */
    var breathing by mutableStateOf(false)
    /** Spoken cue for each breathing phase. */
    var onBreathCue: ((String) -> Unit)? = null
    /** The breathing exercise's Done button. */
    var onBreathDone: (() -> Unit)? = null
    var onDrag: ((Float, Float) -> Unit)? = null
    var onDragEnd: (() -> Unit)? = null
}

/** Fallback bubble face when the user clears the glyph field. */
private const val BUBBLE_GLYPH = "\uD83D\uDC31" // 🐱

@Composable
fun CompanionRoot(
    state: CompanionUiState,
    themeMode: String,
) {
    PocketLLMTheme(themeMode = themeMode, dynamicColor = false) {
        if (state.expanded) {
            // Scale text only, by overriding the font scale rather than the
            // density: that leaves paddings and the panel's own dp sizes alone,
            // so a large text setting doesn't also inflate the whole layout.
            val base = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(base.density, base.fontScale * state.panelFontScale),
            ) {
                CompanionPanel(state)
            }
        } else {
            CompanionBubble(state)
        }
    }
}

@Composable
private fun CompanionBubble(state: CompanionUiState) {
    val fill = state.bubbleColor ?: MaterialTheme.colorScheme.primary
    // An outlined bubble needs an edge that reads against the fill; when the
    // user has not chosen one, the surface colour is picked, which contrasts
    // with a tinted bubble and disappears harmlessly on an untinted one.
    val border = state.bubbleBorderColor ?: MaterialTheme.colorScheme.surface

    // She recedes when nothing is happening but comes fully back the moment the
    // finger lands — the dimming is a resting state, never a state you have to
    // fight to interact through.
    val opacity = state.bubbleAlpha * if (state.bubbleAwake) 1f else state.bubbleIdleFactor

    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { state.onWake?.invoke() },
                    onDrag = { change, drag ->
                        change.consume()
                        state.onDrag?.invoke(drag.x, drag.y)
                    },
                    onDragEnd = { state.onDragEnd?.invoke() },
                    onDragCancel = { state.onDragEnd?.invoke() },
                )
            }
            .padding(4.dp),
        contentAlignment = Alignment.Center,
    ) {
        BubbleVisual(
            sizeDp = state.bubbleSizeDp,
            shape = state.bubbleShape,
            fill = fill,
            borderWidthDp = state.bubbleBorderWidthDp,
            borderColor = border,
            glyph = state.glyph.ifBlank { BUBBLE_GLYPH },
            glyphScale = state.glyphScale,
            opacity = opacity,
            // The only outward sign that she is working; without it a long local
            // generation looks like nothing happened.
            busy = state.busy,
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(state.doubleTapAction, state.longPressAction) {
                    detectTapGestures(
                        onTap = {
                            state.onWake?.invoke()
                            state.onExpand?.invoke()
                        },
                        onDoubleTap = state.doubleTapAction
                            .takeIf { it != BubbleGesture.Off }
                            ?.let { gesture -> { _: Offset -> state.onBubbleGesture?.invoke(gesture) } },
                        onLongPress = state.longPressAction
                            .takeIf { it != BubbleGesture.Off }
                            ?.let { gesture -> { _: Offset -> state.onBubbleGesture?.invoke(gesture) } },
                    )
                },
        )
    }
}

/**
 * The bubble itself, with no interaction and no dependency on the service.
 *
 * Separate from [CompanionBubble] so the Settings preview can render exactly
 * what the overlay will draw. Styling six controls against no preview is how
 * someone ends up with a bubble they did not want and cannot picture.
 */
@Composable
fun BubbleVisual(
    sizeDp: Int,
    shape: BubbleShape,
    fill: Color,
    borderWidthDp: Int,
    borderColor: Color,
    glyph: String,
    glyphScale: Int,
    opacity: Float,
    busy: Boolean,
    modifier: Modifier = Modifier,
) {
    val shapeSpec = shape.shapeFor(sizeDp)

    // A slow pulse while she is working. Animated here rather than by the
    // service so it costs nothing when idle.
    val pulse = rememberInfiniteTransition(label = "busy")
    val pulseAlpha by pulse.animateFloat(
        initialValue = 1f,
        targetValue = 0.45f,
        animationSpec = infiniteRepeatable(
            animation = tween(900, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "busyAlpha",
    )

    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .alpha(if (busy) opacity * pulseAlpha else opacity)
                .background(fill, shapeSpec)
                .then(
                    if (borderWidthDp > 0) {
                        Modifier.border(borderWidthDp.dp, borderColor, shapeSpec)
                    } else Modifier
                ),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = glyph,
                // Scaled by the user's preference so a large bubble is not a big
                // circle with a tiny dot in the middle, and vice versa.
                fontSize = (sizeDp * (glyphScale / 100f)).sp,
            )
        }
    }
}

@Composable
private fun CompanionPanel(state: CompanionUiState) {
    val listState = rememberLazyListState()

    // Keep the newest message visible while tokens stream in.
    LaunchedEffect(state.messages.size, state.messages.lastOrNull()?.text?.length) {
        if (state.messages.isNotEmpty()) {
            listState.scrollToItem(state.messages.lastIndex)
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 10.dp,
    ) {
        Column(modifier = Modifier.fillMaxSize().padding(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(state.glyph.ifBlank { BUBBLE_GLYPH }, fontSize = 16.sp)
                Spacer(Modifier.width(6.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = state.name.ifBlank { "Companion" },
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    if (state.status.isNotBlank()) {
                        Text(
                            text = state.status,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                        )
                    }
                }
                PanelAction("New", enabled = !state.busy) { state.onNewChat?.invoke() }
                PanelAction("Hide") { state.onCollapse?.invoke() }
            }

            Spacer(Modifier.height(6.dp))

            // While she is waiting on a mood answer, the mood faces replace the
            // action chips entirely — the question should have one obvious
            // answer, not compete with four other buttons.
            if (state.awaitingMood) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    MoodScale.choices.forEach { choice ->
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            modifier = Modifier
                                .weight(1f)
                                .clickable { state.onMoodPicked?.invoke(choice.score) },
                        ) {
                            Box(
                                modifier = Modifier.padding(vertical = 6.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(choice.face, fontSize = 18.sp)
                            }
                        }
                    }
                }
            } else {
                // One-tap actions: the common asks shouldn't require typing, and
                // "Cheer me up" is the emotional-support entry point. Two rows,
                // so adding actions can't overflow a narrow panel.
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    QuickChip("Cheer me up", !state.busy, Modifier.weight(1f)) { state.onCheer?.invoke() }
                    QuickChip("Breathe", !state.busy, Modifier.weight(1f)) { state.breathing = true }
                    QuickChip("How am I?", !state.busy, Modifier.weight(1f)) { state.onMoodCheckIn?.invoke() }
                }
                Spacer(Modifier.height(4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    QuickChip("Summary", !state.busy, Modifier.weight(1f)) { state.onSummarize?.invoke() }
                    QuickChip("Explain", !state.busy, Modifier.weight(1f)) { state.onExplain?.invoke() }
                    QuickChip("Translate", !state.busy, Modifier.weight(1f)) { state.onTranslate?.invoke() }
                }
                Spacer(Modifier.height(4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    QuickChip("Look up", !state.busy, Modifier.weight(1f)) { state.onLookUp?.invoke() }
                }
            }

            Spacer(Modifier.height(6.dp))

            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(state.messages) { message ->
                    MessageBubble(message)
                }
            }

            Spacer(Modifier.height(6.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                if (state.voiceInputEnabled) {
                    val micColor = if (state.listening) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.surfaceVariant
                    val micTint = if (state.listening) MaterialTheme.colorScheme.onError
                    else MaterialTheme.colorScheme.onSurfaceVariant
                    Surface(
                        shape = CircleShape,
                        color = micColor,
                        modifier = Modifier
                            .size(38.dp)
                            .clickable(enabled = !state.busy) { state.onMicToggle?.invoke() },
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(text = "\uD83C\uDFA4", fontSize = 16.sp, color = micTint)
                        }
                    }
                    Spacer(Modifier.width(6.dp))
                }
                Surface(
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(18.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                ) {
                    BasicTextField(
                        value = state.input,
                        onValueChange = { state.input = it },
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodyMedium.copy(
                            color = MaterialTheme.colorScheme.onSurface,
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        decorationBox = { inner ->
                            if (state.input.isEmpty()) {
                                Text(
                                    text = if (state.listening) "listening…" else "talk to me…",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            inner()
                        },
                    )
                }
                Spacer(Modifier.width(6.dp))
                // While she is replying the same slot becomes a stop button:
                // mid-generation "send" is meaningless and "stop" is the thing
                // people reach for.
                if (state.busy) {
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.errorContainer,
                        modifier = Modifier
                            .size(38.dp)
                            .clickable { state.onStop?.invoke() },
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(
                                text = "\u25A0", // ■
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                fontSize = 13.sp,
                            )
                        }
                    }
                } else {
                    val canSend = state.input.isNotBlank()
                    Surface(
                        shape = CircleShape,
                        color = if (canSend) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier
                            .size(38.dp)
                            .clickable(enabled = canSend) {
                                val text = state.input.trim()
                                state.input = ""
                                state.onSend?.invoke(text)
                            },
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(
                                text = "\u2191", // ↑
                                color = if (canSend) MaterialTheme.colorScheme.onPrimary
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 16.sp,
                            )
                        }
                    }
                }
            }
        }
    }
        // Drawn over the whole panel rather than swapped in for the content, so
        // the conversation is still there when the exercise ends.
        if (state.breathing) {
            Surface(
                modifier = Modifier.fillMaxSize(),
                shape = RoundedCornerShape(18.dp),
                color = MaterialTheme.colorScheme.surface,
            ) {
                BreathingGuide(state)
            }
        }
    }
}

/**
 * A paced breathing exercise: four counts in, hold, four out, hold.
 *
 * Box breathing is the pattern used because every phase is the same length —
 * easy to follow without counting, and easy to narrate.
 */
@Composable
private fun BreathingGuide(state: CompanionUiState) {
    // Driven imperatively rather than by a target value: animateFloatAsState
    // starts *at* its first target, which would make the opening "breathe in"
    // a circle that never grows — the one moment the user is watching.
    val scale = remember { Animatable(SMALL) }
    var label by remember { mutableStateOf("Breathe in") }

    LaunchedEffect(Unit) {
        while (true) {
            label = "Breathe in"
            state.onBreathCue?.invoke(label)
            scale.animateTo(LARGE, tween(BREATH_MS, easing = LinearEasing))

            label = "Hold"
            state.onBreathCue?.invoke(label)
            delay(BREATH_MS.toLong())

            label = "Breathe out"
            state.onBreathCue?.invoke(label)
            scale.animateTo(SMALL, tween(BREATH_MS, easing = LinearEasing))

            label = "Hold"
            state.onBreathCue?.invoke(label)
            delay(BREATH_MS.toLong())
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            modifier = Modifier.size(140.dp),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier
                    .size((96 * scale.value).dp)
                    .alpha(0.35f + 0.5f * scale.value)
                    .background(MaterialTheme.colorScheme.primary, CircleShape),
            )
        }
        Spacer(Modifier.height(20.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = "Follow the circle. Let your shoulders drop.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(18.dp))
        PanelAction("Done") { state.onBreathDone?.invoke() }
    }
}

/** Breathing pace and the extremes the guide circle moves between. */
private const val BREATH_MS = 4_000
private const val SMALL = 0.55f
private const val LARGE = 1f

@Composable
private fun MessageBubble(message: CompanionMsg) {
    val alignment = if (message.fromUser) Alignment.CenterEnd else Alignment.CenterStart
    val bg = if (message.fromUser) MaterialTheme.colorScheme.primaryContainer
    else MaterialTheme.colorScheme.surfaceVariant
    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = alignment) {
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = bg,
            modifier = Modifier.fillMaxWidth(0.88f),
        ) {
            Text(
                text = message.text.ifBlank { "…" },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
            )
        }
    }
}

@Composable
private fun QuickChip(
    label: String,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
        modifier = modifier.clickable(enabled = enabled) { onClick() },
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 5.dp),
        )
    }
}

@Composable
private fun PanelAction(label: String, enabled: Boolean = true, onClick: () -> Unit) {
    Text(
        text = label,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Medium,
        color = if (enabled) MaterialTheme.colorScheme.primary
        else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .padding(horizontal = 6.dp)
            .clickable(enabled = enabled) { onClick() },
    )
}
