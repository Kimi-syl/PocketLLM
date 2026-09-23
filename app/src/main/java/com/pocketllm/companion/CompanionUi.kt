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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontFamily
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
    /**
     * Drawn character to show instead of the emoji glyph; null means emoji.
     */
    var character by mutableStateOf<CompanionSpecies?>(CompanionSpecies.Cat)
    /** Her expression, usually derived from the user's recent mood. */
    var expression by mutableStateOf(CompanionExpression.Neutral)
    /** True while she is speaking, which animates the mouth. */
    var talking by mutableStateOf(false)
    /** She is drawn free-standing rather than inside a coloured bubble. */
    var bareCharacter by mutableStateOf(false)
    /** Height of the free-standing character in dp. */
    var characterSizeDp by mutableStateOf(170)
    /**
     * Posture of the free-standing character: true stands her up, false lies her
     * down. She rests by default and gets up when the user moves her.
     */
    var upright by mutableStateOf(false)
    /** Bundled Live2D sample model in use; empty when Live2D is not selected. */
    var live2dModel by mutableStateOf("")
    /** Bundled VRM avatar currently shown; empty when not in use. */
    var vrmModel by mutableStateOf("")
    /** The user's own character art, when they have chosen some. */
    var imageUri by mutableStateOf("")
    var imageColumns by mutableStateOf(1)
    var imageRows by mutableStateOf(1)
    var imageFps by mutableStateOf(9)
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
    /** Panel header dragged; moves the whole popup window. */
    /** Corner grip dragged; resizes the popup window. */

    // --- Collapsible shell --------------------------------------------------
    /**
     * The right-hand rail is showing. Opened by a long press on the character,
     * closed by tapping the empty space or the same icon again.
     */
    var shellOpen by mutableStateOf(false)
    /** Which tool's body is open on the left, or null for none. */
    var activeTool by mutableStateOf<CompanionTool?>(null)
    /** Last tool shown, kept so the body has something to draw mid-slide-out. */
    var lastTool by mutableStateOf<CompanionTool?>(null)
    /** The message box is showing. Toggled from the rail, not by a tap. */
    var inputOpen by mutableStateOf(false)
    /**
     * Where the character sits inside the overlay window. Only non-zero while
     * the shell is open, when the window grows to the whole screen and her own
     * position has to be re-expressed relative to the new origin.
     */
    var characterOffsetX by mutableStateOf(0)
    var characterOffsetY by mutableStateOf(0)
    /** Size of the collapsed window, so the shell can draw her at the same size. */
    var collapsedWidthDp by mutableStateOf(60)
    var collapsedHeightDp by mutableStateOf(108)

    /** A long press on the character opens the rail. */
    var onLongPressCharacter: (() -> Unit)? = null
    /** A rail icon was tapped. Selecting the open one closes it again. */
    var onSelectTool: ((CompanionTool) -> Unit)? = null
    /** Tapping the empty space, or "Close", puts the shell away. */
    var onDismissShell: (() -> Unit)? = null
    /** Open the full app-level settings screen. */
    var onOpenSettings: (() -> Unit)? = null

    // Data the tool bodies read. Supplied by the service, which is the only
    // place that can reach the repositories without the UI owning them.
    var sessionSummaries by mutableStateOf<List<String>>(emptyList())
    var ggufChoices by mutableStateOf<List<String>>(emptyList())
    var loadedModel by mutableStateOf("")
    var vrmChoices by mutableStateOf<List<String>>(emptyList())
    var live2dChoices by mutableStateOf<List<String>>(emptyList())
    var toggles by mutableStateOf<List<CompanionToggle>>(emptyList())

    var onPickModel: ((String) -> Unit)? = null
    var onUnloadModel: (() -> Unit)? = null
    var onPickVrm: ((String) -> Unit)? = null
    var onPickLive2d: ((String) -> Unit)? = null
    var onPickSpecies: ((String) -> Unit)? = null
    var onSetToggle: ((String, Boolean) -> Unit)? = null
}

/** One switch in the shell's settings body. */
data class CompanionToggle(val key: String, val label: String, val value: Boolean)

/** Keys for [CompanionUiState.onSetToggle], mapping a switch onto a setting. */
object CompanionToggleKey {
    const val TTS = "tts"
    const val VOICE_INPUT = "voiceInput"
    const val MEMORY = "memory"
    const val NUDGES = "nudges"
}

/** Fallback bubble face when the user clears the glyph field. */
private const val BUBBLE_GLYPH = "\uD83D\uDC31" // 🐱

@Composable
fun CompanionRoot(
    state: CompanionUiState,
    themeMode: String,
) {
    PocketLLMTheme(themeMode = themeMode, dynamicColor = false) {
        // The shell replaces the old bubble/panel split. The panel is kept for
        // the breathing exercise, which is a focused overlay rather than a
        // surface competing with the character.
        if (state.breathing) {
            val base = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(base.density, base.fontScale * state.panelFontScale),
            ) {
                BreathingOverlay(state)
            }
        } else {
            CompanionShell(state)
        }
    }
}

/** The breathing exercise, drawn over everything. */
@Composable
private fun BreathingOverlay(state: CompanionUiState) {
    Box(modifier = Modifier.fillMaxSize()) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
        ) {
            BreathingGuide(state)
        }
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
    character: CompanionSpecies? = null,
    expression: CompanionExpression = CompanionExpression.Neutral,
    talking: Boolean = false,
    animateCharacter: Boolean = true,
    bare: Boolean = false,
    upright: Boolean = true,
    image: ImageBitmap? = null,
    imageColumns: Int = 1,
    imageRows: Int = 1,
    imageFps: Int = 9,
    live2dModel: String = "",
    vrmModel: String = "",
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
    val shownAlpha = if (busy) opacity * pulseAlpha else opacity

    // The user's own art wins over the drawn character; it is the only way to get
    // a real illustrated character without shipping a licensed model.
    val content: @Composable () -> Unit = {
        when {
            vrmModel.isNotEmpty() -> Box(Modifier.fillMaxSize()) {
                VrmCharacter(
                    modelAsset = vrmModel,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            live2dModel.isNotEmpty() -> Live2DCharacter(
                modelName = live2dModel,
                modifier = Modifier.fillMaxSize(),
                // If the Cubism framework cannot start, draw the vector character
                // rather than leaving an empty hole where she should be.
                fallback = {
                    AnimatedCompanion(
                        species = CompanionSpecies.Cat,
                        expression = expression,
                        talking = talking,
                        animate = animateCharacter,
                        upright = upright,
                        fit = if (bare) CharacterFit.Full else CharacterFit.Bust,
                        modifier = Modifier.fillMaxSize(),
                    )
                },
            )
            image != null -> AnimatedImageCompanion(
                image = image,
                columns = imageColumns,
                rows = imageRows,
                fps = imageFps,
                animate = animateCharacter,
                modifier = Modifier.fillMaxSize(),
            )
            character != null -> AnimatedCompanion(
                species = character,
                expression = expression,
                talking = talking,
                animate = animateCharacter,
                upright = upright,
                fit = if (bare) CharacterFit.Full else CharacterFit.Bust,
                modifier = Modifier.fillMaxSize(),
            )
            else -> Text(
                text = glyph,
                // Scaled by the user's preference so a large bubble is not a big
                // circle with a tiny dot in the middle, and vice versa.
                fontSize = (sizeDp * (glyphScale / 100f)).sp,
            )
        }
    }

    // Bare: no fill, no outline, no clipping — just her, standing on the screen.
    // Only meaningful with something drawn; an emoji would float with nothing
    // behind it, so that case keeps the wrapped bubble.
    if (bare && (live2dModel.isNotEmpty() || vrmModel.isNotEmpty() || image != null || character != null)) {
        Box(modifier = modifier, contentAlignment = Alignment.Center) {
            Box(modifier = Modifier.fillMaxSize().alpha(shownAlpha)) { content() }
        }
        return
    }

    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .alpha(shownAlpha)
                .background(fill, shapeSpec)
                .then(
                    if (borderWidthDp > 0) {
                        Modifier.border(borderWidthDp.dp, borderColor, shapeSpec)
                    } else Modifier
                )
                // The character is drawn as a bust that intentionally runs past
                // the bubble's edge, so it has to be clipped to the shape or it
                // would paint into the square corners of the layout box.
                .clip(shapeSpec),
            contentAlignment = Alignment.Center,
        ) {
            content()
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
internal fun MessageBubble(message: CompanionMsg) {
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
