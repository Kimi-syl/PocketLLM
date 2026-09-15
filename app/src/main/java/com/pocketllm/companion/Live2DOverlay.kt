package com.pocketllm.companion

import android.content.Context
import android.graphics.PixelFormat
import android.opengl.GLSurfaceView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.live2d.demo.minimum.LAppMinimumDelegate
import com.live2d.demo.minimum.LAppMinimumLive2DManager
import com.pocketllm.server.ServerLog
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * Live2D, rendered by the official Cubism SDK for Java.
 *
 * Chosen over the Web SDK because the Java SDK renders in this process: no
 * Chromium instance in an always-on overlay, no separate renderer process that
 * Android can kill out from under us, and direct parameter access so the mouth
 * can follow speech without a JS bridge in the loop.
 *
 * The models are the official Live2D samples, bundled in the APK. Users cannot
 * import their own — that is precisely what would make this an "Expandable
 * Application" under Live2D's publication licence, which requires a contract.
 */
private const val TAG = "live2d"

/**
 * Sample models bundled in the APK.
 *
 * All are official Live2D samples, and each ships with motions, physics and
 * expressions, so they animate and react without any extra authoring.
 */
val LIVE2D_MODELS: List<Pair<String, String>> = listOf(
    "Hiyori" to "Hiyori",
    "Haru" to "Haru",
    "Mao" to "Mao",
    "Natori" to "Natori",
    "Rice" to "Rice",
    "Ren" to "Ren",
    "Mark" to "Mark",
    "Wanko" to "Wanko",
)

/** Which model the GL thread currently has loaded. */
private var loadedModel: String? = null

/**
 * Aspect ratio (width / height) of the loaded model's canvas, or null before one
 * is loaded.
 *
 * The overlay window is sized to match this, because the renderer fits the
 * model's canvas to the window: with a mismatched aspect the model is letterboxed
 * and the character floats in the middle of a much larger transparent rectangle.
 * That padding is what stopped her short of the screen edge when dragged.
 */
var live2DCanvasAspect: Float? = null
    private set

/**
 * Called once a model has loaded, so the window can be re-sized to its canvas.
 *
 * Fired from the GL thread; the receiver is responsible for hopping to its own.
 */
var onLive2DModelLoaded: (() -> Unit)? = null

/**
 * Prepares the framework and stores the context the asset loader needs.
 *
 * Deliberately does *not* load the model: the moc reader refuses to work until
 * `CubismFramework.initialize()` has run, and that happens on the GL thread in
 * the surface callback. Loading here is what made the character never appear.
 */
private fun prepareLive2D(context: Context): Boolean = runCatching {
    LAppMinimumDelegate.getInstance().onStart(context)
    true
}.getOrElse { error ->
    ServerLog.log("$TAG: setup failed: ${error.message}")
    false
}

/** Loads the model, which must happen after the framework is initialised. */
private fun loadModel(name: String) {
    val manager = LAppMinimumLive2DManager.getInstance()
    if (loadedModel == name && manager.hasModel()) return
    runCatching { manager.loadModelByName(name) }
        .onSuccess {
            loadedModel = name
            // The canvas aspect is only knowable once the moc is parsed, which is
            // why the window is re-fitted here rather than up front.
            runCatching {
                val canvasWidth = manager.getModel(0)?.model?.canvasWidth ?: 0f
                val canvasHeight = manager.getModel(0)?.model?.canvasHeight ?: 0f
                if (canvasWidth > 0f && canvasHeight > 0f) {
                    live2DCanvasAspect = canvasWidth / canvasHeight
                }
            }
            onLive2DModelLoaded?.invoke()
        }
        .onFailure { error ->
            loadedModel = null
            live2DCanvasAspect = null
            ServerLog.log("$TAG: model '$name' failed to load: ${error.message}")
        }
}

/**
 * Drives the sample's lifecycle, loading the model once the framework is up.
 *
 * The sample loaded its model in the manager's constructor, which happened to
 * run *after* `CubismFramework.initialize()`. Loading has to be moved here to
 * keep that ordering when the model is chosen from settings instead.
 */
private class Live2DRenderer(private val modelName: String) : GLSurfaceView.Renderer {
    override fun onSurfaceCreated(unused: GL10?, config: EGLConfig?) {
        LAppMinimumDelegate.getInstance().onSurfaceCreated()
        loadModel(modelName)
    }

    override fun onSurfaceChanged(unused: GL10?, width: Int, height: Int) {
        LAppMinimumDelegate.getInstance().onSurfaceChanged(width, height)
    }

    override fun onDrawFrame(unused: GL10?) {
        LAppMinimumDelegate.getInstance().run()
    }
}

/**
 * The model, drawn over a transparent surface.
 *
 * [fallback] is drawn when the framework could not be started at all, so a
 * failure shows the drawn character rather than an empty hole on the screen.
 */
@Composable
fun Live2DCharacter(
    modelName: String,
    modifier: Modifier = Modifier,
    fallback: @Composable () -> Unit = {},
) {
    val context = LocalContext.current
    val ready = remember(modelName) { prepareLive2D(context) }
    if (!ready) {
        fallback()
        return
    }

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            GLSurfaceView(ctx).apply {
                setEGLContextClientVersion(2)
                // An explicit RGBA config with an alpha channel is required: the
                // default config has no alpha, so clearing to alpha 0 still
                // composites as an opaque rectangle.
                setEGLConfigChooser(8, 8, 8, 8, 16, 0)
                setZOrderOnTop(true)
                holder.setFormat(PixelFormat.TRANSLUCENT)
                // The overlay window is moved by dragging the character, so this
                // surface must not swallow touches; the Compose gesture layer
                // above it handles them instead.
                isClickable = false
                isFocusable = false
                setRenderer(Live2DRenderer(modelName))
                renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
            }
        },
    )
}

/**
 * Stops rendering and releases the framework.
 *
 * Called when the overlay is torn down: leaving a GL thread running after the
 * service stops would keep the device awake with nothing on screen to show.
 */
fun releaseLive2D() {
    loadedModel = null
    runCatching { LAppMinimumDelegate.getInstance().onDestroy() }
        .onFailure { ServerLog.log("$TAG: release failed: ${it.message}") }
}
