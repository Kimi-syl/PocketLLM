package com.pocketllm.companion

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin

/**
 * The user's own character art.
 *
 * This is the practical route to a "real character": rather than shipping a
 * Live2D model — the `.moc3` format is proprietary, models can only be authored
 * in Cubism Editor, and redistributing one inside an app carries its own licence
 * — the user supplies an image they have the rights to and this animates it.
 *
 * Two shapes of art are supported:
 *  - a single image, given idle motion so a still picture does not look pasted on
 *  - a sprite sheet, a grid of frames that cycle
 */
private const val TAU_IMAGE = (2.0 * PI).toFloat()
private const val IMAGE_FPS = 30L

/**
 * Decodes the chosen image once per URI.
 *
 * Held in Compose state rather than re-read on every frame — decoding a
 * full-size PNG on the frame loop would be ruinous, and the overlay redraws
 * constantly.
 */
@Composable
fun rememberCharacterImage(uriString: String): ImageBitmap? {
    val context = LocalContext.current
    var bitmap by remember(uriString) { mutableStateOf<ImageBitmap?>(null) }

    LaunchedEffect(uriString) {
        if (uriString.isBlank()) {
            bitmap = null
            return@LaunchedEffect
        }
        bitmap = withContext(Dispatchers.IO) {
            runCatching { decodeBitmap(context, uriString) }.getOrNull()
        }
    }
    return bitmap
}

private fun decodeBitmap(context: Context, uriString: String): ImageBitmap? =
    context.contentResolver.openInputStream(Uri.parse(uriString))?.use { stream ->
        BitmapFactory.decodeStream(stream)?.asImageBitmap()
    }

/**
 * Draws the image, optionally stepping through a sprite sheet.
 *
 * [columns] x [rows] is the frame grid; 1x1 means a still image. The grid is
 * declared rather than detected because a sprite sheet carries no reliable
 * metadata about its frame count.
 */
@Composable
fun AnimatedImageCompanion(
    image: ImageBitmap,
    columns: Int,
    rows: Int,
    fps: Int,
    modifier: Modifier = Modifier,
    animate: Boolean = true,
) {
    var elapsed by remember { mutableFloatStateOf(0f) }

    LaunchedEffect(animate) {
        if (!animate) {
            elapsed = 0f
            return@LaunchedEffect
        }
        var lastNanos = 0L
        var total = 0f
        var gateNanos = 0L
        val frameInterval = 1_000_000_000L / IMAGE_FPS
        while (true) {
            withFrameNanos { now ->
                if (lastNanos != 0L) {
                    total += ((now - lastNanos).coerceAtMost(120_000_000L)) / 1_000_000_000f
                    if (now >= gateNanos) {
                        gateNanos = now + frameInterval
                        elapsed = total
                    }
                }
                lastNanos = now
            }
        }
    }

    Canvas(modifier = modifier) {
        drawImageCharacter(image, columns, rows, fps, elapsed)
    }
}

private fun DrawScope.drawImageCharacter(
    image: ImageBitmap,
    columns: Int,
    rows: Int,
    fps: Int,
    elapsed: Float,
) {
    val cols = columns.coerceAtLeast(1)
    val rowCount = rows.coerceAtLeast(1)
    val frameW = image.width / cols
    val frameH = image.height / rowCount
    if (frameW <= 0 || frameH <= 0) return

    val frameTotal = cols * rowCount
    val index = if (fps <= 0 || frameTotal <= 1) {
        0
    } else {
        (elapsed * fps).toInt() % frameTotal
    }
    val col = index % cols
    val row = index / cols

    // Idle motion so a single still frame still breathes: a slow bob and a
    // slightly faster swell, at periods that do not divide evenly so the loop is
    // not obvious.
    val bob = sin(elapsed * TAU_IMAGE / 3.9f)
    val swell = 1f + 0.014f * sin(elapsed * TAU_IMAGE / 4.7f)

    val fit = min(size.width / frameW, size.height / frameH) * swell
    val drawW = frameW * fit
    val drawH = frameH * fit
    val left = (size.width - drawW) / 2f
    val top = (size.height - drawH) / 2f + bob * size.height * 0.012f

    drawImage(
        image = image,
        srcOffset = IntOffset(col * frameW, row * frameH),
        srcSize = IntSize(frameW, frameH),
        dstOffset = IntOffset(left.toInt(), top.toInt()),
        dstSize = IntSize(drawW.toInt(), drawH.toInt()),
        filterQuality = FilterQuality.Medium,
    )
}
