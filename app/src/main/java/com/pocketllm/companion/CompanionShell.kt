package com.pocketllm.companion

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Chat
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The tool shown in the left panel. Each entry is one icon on the right rail.
 *
 * Kept as an enum rather than a free-form id so the rail and the panel body
 * cannot drift apart: adding a tool here forces a branch in [CompanionToolPanel].
 */
enum class CompanionTool(val label: String, val icon: ImageVector) {
    SESSIONS("Chat sessions", Icons.Outlined.Chat),
    BRAIN("Model", Icons.Outlined.Memory),
    CHARACTER("Character", Icons.Outlined.Person),
    SETTINGS("Settings", Icons.Outlined.Settings),
}

/**
 * The overlay's whole layout when the character is on screen.
 *
 * Deliberately one composable rather than a window per surface. Android gives an
 * overlay window no way to pass a touch through a transparent pixel, so a
 * full-screen window would swallow every tap meant for the app underneath. The
 * host therefore keeps the collapsed window figure-sized and only grows it — to
 * the whole screen — while the shell is open, which is also what makes "tap the
 * empty space to put it away" work: when nothing is open there *is* no empty
 * space to tap.
 *
 * Nothing here touches the renderer. The character is drawn exactly as before;
 * only the furniture around her is new.
 */
@Composable
fun CompanionShell(state: CompanionUiState) {
    Box(modifier = Modifier.fillMaxSize()) {
        // Dim the world rather than the character so the panels read as a layer
        // over the page, and give a single obvious way out: tap the gaps.
        AnimatedVisibility(
            visible = state.shellOpen,
            enter = fadeIn(tween(140)),
            exit = fadeOut(tween(140)),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.28f))
                    .clickable { state.onDismissShell?.invoke() },
            )
        }

        // The character. Offset only while the shell is open, where the window
        // origin is the screen corner rather than her own top-left.
        CharacterLayer(state)

        AnimatedVisibility(
            visible = state.shellOpen,
            enter = slideInHorizontally(tween(180)) { it } + fadeIn(tween(180)),
            exit = slideOutHorizontally(tween(160)) { it } + fadeOut(tween(160)),
            modifier = Modifier.align(Alignment.CenterEnd),
        ) {
            ToolRail(state)
        }

        AnimatedVisibility(
            visible = state.activeTool != null,
            enter = slideInHorizontally(tween(200)) { -it } + fadeIn(tween(200)),
            exit = slideOutHorizontally(tween(180)) { -it } + fadeOut(tween(180)),
            modifier = Modifier.align(Alignment.CenterStart),
        ) {
            // Held after the tool clears, so the panel's contents stay put while
            // it slides away instead of flashing empty.
            val shown = state.activeTool ?: state.lastTool ?: CompanionTool.SESSIONS
            CompanionToolPanel(state, shown)
        }

        AnimatedVisibility(
            visible = state.inputOpen,
            enter = slideInVertically(tween(180)) { it } + fadeIn(tween(180)),
            exit = slideOutVertically(tween(160)) { it } + fadeOut(tween(160)),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            CompanionInputBar(state)
        }
    }
}

/** The character, drawn at her screen position. */
@Composable
private fun CharacterLayer(state: CompanionUiState) {
    Box(
        modifier = Modifier
            .offset(state.characterOffsetX.dp, state.characterOffsetY.dp)
            .size(state.collapsedWidthDp.dp, state.collapsedHeightDp.dp)
            // Dragging only means anything while the window is figure-sized: once
            // the shell is open the window is the whole screen, so a drag would
            // be moving her inside a frame that cannot move.
            .then(
                if (state.shellOpen) Modifier else Modifier.pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = { state.onWake?.invoke() },
                        onDrag = { change, drag ->
                            change.consume()
                            state.onDrag?.invoke(drag.x, drag.y)
                        },
                        onDragEnd = { state.onDragEnd?.invoke() },
                        onDragCancel = { state.onDragEnd?.invoke() },
                    )
                },
            ),
    ) {
        CompanionCharacterView(state)
    }
}

/**
 * The character herself.
 *
 * Split out so the settings preview and the overlay share one drawing path, the
 * same reason [BubbleVisual] exists. A tap only wakes her — the popup that used
 * to open here has been removed on purpose; the shell is reached by long-press.
 */
@Composable
fun CompanionCharacterView(state: CompanionUiState, modifier: Modifier = Modifier) {
    val characterImage = rememberCharacterImage(state.imageUri)
    val fill = state.bubbleColor ?: MaterialTheme.colorScheme.primary
    val border = state.bubbleBorderColor ?: MaterialTheme.colorScheme.surface
    val opacity = state.bubbleAlpha * if (state.bubbleAwake) 1f else state.bubbleIdleFactor

    BubbleVisual(
        sizeDp = state.bubbleSizeDp,
        shape = state.bubbleShape,
        fill = fill,
        borderWidthDp = state.bubbleBorderWidthDp,
        borderColor = border,
        glyph = state.glyph.ifBlank { BUBBLE_GLYPH_FALLBACK },
        glyphScale = state.glyphScale,
        opacity = opacity,
        busy = state.busy,
        character = state.character,
        expression = state.expression,
        talking = state.talking,
        bare = state.bareCharacter,
        upright = state.upright,
        image = characterImage,
        imageColumns = state.imageColumns,
        imageRows = state.imageRows,
        imageFps = state.imageFps,
        live2dModel = state.live2dModel,
        vrmModel = state.vrmModel,
        modifier = modifier
            .fillMaxSize()
            .pointerInput(state.doubleTapAction, state.longPressAction) {
                detectTapGestures(
                    onTap = { state.onWake?.invoke() },
                    onDoubleTap = state.doubleTapAction
                        .takeIf { it != BubbleGesture.Off }
                        ?.let { gesture -> { _: Offset -> state.onBubbleGesture?.invoke(gesture) } },
                    onLongPress = {
                        state.onWake?.invoke()
                        state.onLongPressCharacter?.invoke()
                    },
                )
            },
    )
}

/**
 * The right rail.
 *
 * Collapsed it is a bare strip of icons; tapping one opens its body in the left
 * panel. Tapping the icon already showing closes the body, so the rail alone
 * remains — which is the "collapsible" behaviour the shell promises.
 */
@Composable
private fun ToolRail(state: CompanionUiState) {
    FrostedCard(
        shape = RoundedCornerShape(topStart = 18.dp, bottomStart = 18.dp),
        modifier = Modifier
            .padding(vertical = 24.dp)
            .fillMaxHeight(0.82f)
            .width(56.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Top,
        ) {
            CompanionTool.entries.forEach { tool ->
                val active = state.activeTool == tool
                Box(
                    modifier = Modifier
                        .padding(vertical = 4.dp)
                        .size(42.dp)
                        .clip(CircleShape)
                        .background(
                            if (active) MaterialTheme.colorScheme.primary
                            else Color.Transparent,
                        )
                        .clickable { state.onSelectTool?.invoke(tool) },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = tool.icon,
                        contentDescription = tool.label,
                        tint = if (active) MaterialTheme.colorScheme.onPrimary
                        else MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(22.dp),
                    )
                }
            }
        }
    }
}

/** Whatever tool is open, in the left panel. */
@Composable
private fun CompanionToolPanel(state: CompanionUiState, tool: CompanionTool) {
    FrostedCard(
        shape = RoundedCornerShape(topEnd = 18.dp, bottomEnd = 18.dp),
        modifier = Modifier
            .padding(vertical = 24.dp)
            .fillMaxHeight(0.82f)
            .fillMaxWidth(0.72f),
    ) {
        Column(modifier = Modifier.fillMaxSize().padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = tool.label,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = "Close",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clickable { state.onDismissShell?.invoke() },
                )
            }
            Spacer(Modifier.height(10.dp))
            when (tool) {
                CompanionTool.SESSIONS -> SessionsPanel(state)
                CompanionTool.BRAIN -> BrainPanel(state)
                CompanionTool.CHARACTER -> CharacterPanel(state)
                CompanionTool.SETTINGS -> SettingsPanel(state)
            }
        }
    }
}

/** Saved app conversations, newest first, with the live transcript below. */
@Composable
private fun SessionsPanel(state: CompanionUiState) {
    Column(modifier = Modifier.fillMaxSize()) {
        PanelLabel("Recent chats")
        LazyColumn(modifier = Modifier.fillMaxWidth().height(160.dp)) {
            itemsIndexed(state.sessionSummaries) { _, line ->
                Text(
                    text = line,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 6.dp),
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        PanelLabel("This conversation")
        // Reuses the panel's own transcript rather than reloading from disk, so
        // what is shown is exactly what she remembers.
        val listState = rememberLazyListState()
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxWidth().weight(1f),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            items(state.messages.size) { index ->
                MessageBubble(state.messages[index])
            }
        }
    }
}

/** Model picker: the loaded GGUF, plus every one on disk. */
@Composable
private fun BrainPanel(state: CompanionUiState) {
    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        PanelLabel("Backend: ${state.status.ifBlank { "unknown" }}")
        Spacer(Modifier.height(8.dp))
        PanelLabel("Load a model")
        if (state.ggufChoices.isEmpty()) {
            Text(
                text = "No models on disk yet. Download one from the app's Models tab.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        state.ggufChoices.forEach { name ->
            val active = state.loadedModel == name
            PanelRow(
                text = name,
                selected = active,
                onClick = { state.onPickModel?.invoke(name) },
            )
        }
        Spacer(Modifier.height(10.dp))
        PanelAction("Unload model") { state.onUnloadModel?.invoke() }
    }
}

/** Avatar picker: bundled and imported VRM, bundled Live2D, drawn species. */
@Composable
private fun CharacterPanel(state: CompanionUiState) {
    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        PanelLabel("VRM avatars")
        state.vrmChoices.forEach { name ->
            PanelRow(
                text = name,
                selected = state.vrmModel == name,
                onClick = { state.onPickVrm?.invoke(name) },
            )
        }
        if (state.vrmChoices.isEmpty()) {
            Text(
                text = "None imported.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(10.dp))
        PanelLabel("Live2D")
        state.live2dChoices.forEach { name ->
            PanelRow(
                text = name,
                selected = state.live2dModel == name,
                onClick = { state.onPickLive2d?.invoke(name) },
            )
        }
        Spacer(Modifier.height(10.dp))
        PanelLabel("Drawn character")
        CompanionSpecies.entries.forEach { species ->
            PanelRow(
                text = species.label,
                selected = state.character == species,
                onClick = { state.onPickSpecies?.invoke(species.id) },
            )
        }
    }
}

/** The switches that matter while she is on screen, plus the way into the app. */
@Composable
private fun SettingsPanel(state: CompanionUiState) {
    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        state.toggles.forEach { toggle ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = toggle.label,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f),
                )
                Switch(
                    checked = toggle.value,
                    onCheckedChange = { state.onSetToggle?.invoke(toggle.key, it) },
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        PanelAction("Open app settings") { state.onOpenSettings?.invoke() }
    }
}

/**
 * The message box, as its own collapsible surface.
 *
 * The character no longer lives behind a popup, so the box has to be able to
 * appear on its own — otherwise there would be nowhere to type without covering
 * her with a window.
 */
@Composable
private fun CompanionInputBar(state: CompanionUiState) {
    FrostedCard(
        shape = RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
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
            // Busy swaps the send arrow for a stop square: mid-generation "send"
            // is meaningless and "stop" is what people reach for.
            if (state.busy) {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.errorContainer,
                    modifier = Modifier.size(38.dp).clickable { state.onStop?.invoke() },
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            text = "\u25A0",
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
                            text = "\u2191",
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

/** A selectable row used by the picker panels. */
@Composable
private fun PanelRow(text: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = if (selected) MaterialTheme.colorScheme.primaryContainer
        else Color.Transparent,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .clickable { onClick() },
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
            else MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
        )
    }
}

/** Small heading inside a panel. */
@Composable
private fun PanelLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(vertical = 2.dp),
    )
}

@Composable
private fun PanelAction(label: String, onClick: () -> Unit) {
    Text(
        text = label,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Medium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .padding(vertical = 6.dp)
            .clickable { onClick() },
    )
}

/**
 * A translucent, blur-backed card.
 *
 * The real blur is API 31+ and silently a no-op below it, so the tint and the
 * outline are what actually carry the "frosted" look everywhere — the blur is
 * the finishing touch, not the mechanism.
 */
@Composable
fun FrostedCard(
    shape: RoundedCornerShape,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Surface(
        shape = shape,
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.72f),
        modifier = modifier.blur(18.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.18f), shape),
        ) {
            content()
        }
    }
}

/** Fallback bubble face, mirroring the one in CompanionUi. */
private const val BUBBLE_GLYPH_FALLBACK = "\uD83D\uDC31"
