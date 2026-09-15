package com.pocketllm.companion

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.view.Choreographer
import android.view.SurfaceView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.google.android.filament.Filament
import com.google.android.filament.IndirectLight
import com.google.android.filament.TransformManager
import com.google.android.filament.View
import com.google.android.filament.android.UiHelper
import com.google.android.filament.gltfio.Gltfio
import com.google.android.filament.utils.ModelViewer
import java.io.File
import java.io.FileOutputStream
import com.google.android.filament.utils.Utils
import com.pocketllm.server.ServerLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Bundled VRM avatars. Importing the user's own VRM comes later. */
val VRM_MODELS: List<Pair<String, String>> = listOf(
    "Seed-san.vrm" to "Seed-san",
)

private const val VRM_TAG = "vrm"

/** Where the framed figure ends up; matches ModelViewer's own default target. */
private const val kBodyCentreZ = -4.0

/** Far enough back that the 2-unit-tall framed figure fits the default 28mm FOV. */
private const val kCameraZ = kBodyCentreZ + 3.6

/**
 * Key light direction. ModelViewer's own sun points straight down (0,-1,0); a
 * character facing the camera has side-facing normals, so N.L is near zero and the
 * visible side renders black. This is above, in front of and slightly right of the
 * model, travelling back into the scene.
 */
private const val kSunX = 0.25f
private const val kSunY = -0.80f
private const val kSunZ = -0.55f

/** Ambient fill, in lux. Same order of magnitude as ModelViewer's 100000 lux sun. */
private const val kAmbientIntensity = 30_000f

/**
 * Last VRM diagnostic line, shown on screen as well as written to the log.
 *
 * The Compose overlay renders reliably even when the Filament surface does not,
 * so when the 3D layer is the thing misbehaving this is the only channel that
 * can actually tell us anything.
 */
internal val vrmDiagnostic = mutableStateOf("")

/** Handle on the UiHelper handed to ModelViewer, so isOpaque is inspectable. */
internal var vrmUiHelper: UiHelper? = null

/** Context for the frame dump; set from the Compose factory. */
internal var vrmContext: Context? = null

/**
 * VRM avatar rendered with Filament. VRM is glTF 2.0 plus extensions and the
 * bundled files are single binary glTF containers, so gltfio reads them directly.
 *
 * The idle pose is deliberately crude — a few degrees of sway on the spine, neck
 * and arms, driven off the VRM humanoid bone map. Enough that she is not frozen
 * in T-pose. MToon shading, spring bones and expressions are still to come.
 */
@Composable
fun VrmCharacter(
    modelAsset: String,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var viewer by remember(modelAsset) { mutableStateOf<ModelViewer?>(null) }

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            SurfaceView(ctx).apply {
                setZOrderOnTop(true)

                // Filament ships three JNI libraries and loads none of them by
                // itself — Engine, ModelViewer, Manipulator and AssetLoader all skip
                // it, so every call fails with UnsatisfiedLinkError ("No
                // implementation found for ...nCreateBuilder") until these run. Each
                // is a no-op whose only job is to trigger System.loadLibrary.
                Filament.init() // libfilament-jni
                Gltfio.init()   // libgltfio-jni
                Utils.init()    // libfilament-utils-jni

                // UiHelper defaults to OPAQUE. That makes it request
                // EGL_ALPHA_SIZE = 0 (no alpha channel at all) and set the holder
                // format to OPAQUE — so the surface composites as a solid rectangle
                // no matter what we clear to. Going non-opaque is what makes it pass
                // SWAP_CHAIN_CONFIG_TRANSPARENT, request an 8-bit alpha channel, and
                // set the holder to TRANSLUCENT on our behalf.
                val uiHelper = UiHelper(UiHelper.ContextErrorPolicy.DONT_CHECK)
                uiHelper.isOpaque = false
                vrmUiHelper = uiHelper
                vrmContext = ctx

                // manipulator = null: see the camera comment in frameModel().
                val created = ModelViewer(this, uiHelper = uiHelper, manipulator = null)

                // getClearOptions() hands back a cached *Java* object; only
                // setClearOptions() reaches native. Mutating clearColor alone does
                // nothing, which is how the background stayed opaque.
                val renderer = created.renderer
                // ClearOptions.clear defaults to FALSE, and getClearOptions()
                // hands back a freshly-defaulted Java POJO rather than reading
                // native state - so setting clearColor and calling setClearOptions
                // was switching clearing OFF entirely. The swapchain was never
                // cleared, which is why the surface stayed black no matter what
                // colour was asked for. clear must be set true explicitly.
                val options = renderer.clearOptions
                options.clear = true
                // Seed-san is an almost entirely white/pale model - the frame
                // readback reports dark=0% - so over a light background it is
                // invisible. That is what "the character only shows a little bit"
                // looks like: it is drawn, there is simply nothing to see it
                // against. A dark translucent backdrop makes it legible and doubles
                // as proof that the model is really being rendered.
                options.clearColor = doubleArrayOf(0.0, 0.0, 0.0, 0.0)
                renderer.setClearOptions(options)

                // Needed for a transparent background. With the default OPAQUE
                // the View fills every pixel it did not touch with the clear
                // colour and puts it through post-processing (tone-mapping),
                // which is opaque. TRANSLUCENT leaves untouched pixels fully
                // transparent so the window behind shows through.
                created.view.blendMode = View.BlendMode.TRANSLUCENT

                // Re-aim the only light in the scene. Left pointing straight down, a
                // model facing the camera is lit at grazing incidence and its front
                // comes out black - which is why only a sliver of the character was
                // ever visible.
                created.engine.lightManager.setDirection(created.light, kSunX, kSunY, kSunZ)

                // Ambient fill, so surfaces facing away from the sun are not pitch
                // black. Filament's IndirectLight javadoc states that with a single
                // band sh[0] IS the environment's average irradiance, pre-scaled, so
                // [1,1,1] times intensity is a uniform fill and needs no HDR asset.
                created.scene.indirectLight = IndirectLight.Builder()
                    .irradiance(1, floatArrayOf(1f, 1f, 1f))
                    .intensity(kAmbientIntensity)
                    .build(created.engine)

                viewer = created
            }
        },
    )

    LaunchedEffect(modelAsset, viewer) {
        val active = viewer ?: return@LaunchedEffect
        try {
            // Read on IO — these files are ~10 MB.
            val bytes = withContext(Dispatchers.IO) {
                context.assets.open(modelAsset).use { it.readBytes() }
            }
            active.loadModelGlb(ByteBuffer.wrap(bytes))
            // NOTE: no transformToUnitCube() here. The model has no usable bounding
            // box until the render loop has populated its renderables, and scaling by
            // a zero extent produces an infinite scale that hides it forever. Framing
            // is deferred to VrmIdle.frameModel().
            VrmIdle.attach(active, bytes)
        } catch (superseded: CancellationException) {
            // The effect was cancelled — either the viewer was replaced or the
            // composable left the composition. That is not a load failure, and
            // reporting it as one ("failed to load: The coroutine scope left the
            // composition") put a misleading error next to a successful load.
            // Cancellation must always be rethrown.
            throw superseded
        } catch (failure: Throwable) {
            ServerLog.log("$VRM_TAG: '$modelAsset' failed to load: ${failure.message}")
            vrmDiagnostic.value = "load failed:\n${failure.message}"
        }
    }

    DisposableEffect(viewer) {
        onDispose {
            // Deliberately NOT calling viewer.destroy() here. ModelViewer installs
            // its own detach listener (addDetachListener) that destroys it when the
            // SurfaceView leaves the window. Doing it here as well shuts the engine
            // down first, and Filament's detach path then calls
            // Engine::flushAndWait() on the dead engine, aborting the whole process:
            //   PreconditionPanic in flushAndWait:868
            //   "Calling Engine::flushAndWait() after Engine::shutdown()!"
            // So: stop our frame loop and let Filament own its own teardown.
            VrmIdle.detach()
        }
    }
}

private class IdleBone(
    val humanoidName: String,
    val axis: Char,
    val amplitude: Float,
    val phase: Float,
)

/**
 * A few degrees of movement on the spine, neck and arms. Amplitudes are small on
 * purpose — this is meant to read as breathing and shifting weight, not dancing.
 */
private val IDLE_BONES = listOf(
    IdleBone("spine", 'z', 0.030f, 0.0f),
    IdleBone("chest", 'z', 0.022f, 0.5f),
    IdleBone("neck", 'z', 0.018f, 1.0f),
    IdleBone("head", 'y', 0.055f, 0.4f),
    IdleBone("leftUpperArm", 'z', 0.045f, 0.2f),
    IdleBone("rightUpperArm", 'z', 0.045f, 2.9f),
)

/**
 * Bone animation is switched OFF while we isolate the native crash.
 *
 * The tombstone pointed straight at Animator.updateBoneMatrices() being called
 * from a Choreographer callback. ModelViewer only does that itself when the model
 * has animation clips, and Seed-san has none, so the call is ours to make — but
 * doing it outside Filament's own frame and before async loading settles is what
 * faults. With this off the model still loads and draws in its bind pose, which
 * separates "rendering is broken" from "animation is broken" in one test.
 */
private const val IDLE_POSE_ENABLED = false

private const val TAU = (2.0 * PI).toFloat()

/**
 * The pose is updated at ~30 Hz rather than at display refresh. This overlay is
 * always on, Filament renders continuously underneath, and this device is known
 * to kill processes that burn CPU — so halving the bone-matrix rebuild and upload
 * is worth more than the smoothness it costs.
 */
private const val STEP_INTERVAL_NANOS = 33_000_000L

/**
 * Drives the idle pose off Filament's frame callback. Kept as an object rather
 * than composition state so the callback can be started and stopped without
 * recomposing every frame.
 */
private object VrmIdle {
    private var viewer: ModelViewer? = null
    private var bones: Map<String, Int> = emptyMap()
    private var running = false
    private var lastStepNanos = 0L
    private var framed = false

    private var renderedFrames = 0

    /** Last pixel readback of a rendered frame, shown in the on-screen readout. */
    private var lastFrameReport: String? = null

    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!running) return
            val active = viewer
            if (active != null) {
                // ModelViewer does NOT drive its own render loop. Its SurfaceCallback
                // only reacts to surface lifecycle; the host has to pump render() at
                // vsync, which is what Filament's sample-gltf-viewer does from its
                // Activity. Without this the camera is never positioned (the eye
                // stays at 0,0,0) and the scene is never populated, so the surface
                // clears and nothing else ever appears.
                val rendered = runCatching { active.render(frameTimeNanos) }.getOrDefault(false)
                if (rendered) {
                    renderedFrames++
                    if (renderedFrames % 300 == 30) captureFrame(active)
                    if (renderedFrames % 60 == 0) publishDiagnostic(active)
                }
                // Guards: destroyModel() clears the asset, and the bounding box only
                // exists once gltfio has populated the renderables.
                if (active.asset != null && !framed) frameModel(active)
                if (framed && IDLE_POSE_ENABLED &&
                    frameTimeNanos - lastStepNanos >= STEP_INTERVAL_NANOS
                ) {
                    lastStepNanos = frameTimeNanos
                    step(frameTimeNanos)
                }
            }
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    /**
     * Republish the on-screen readout from the live camera. Called every ~60
     * rendered frames, so it reflects a camera that has actually been positioned
     * rather than the untouched identity transform.
     */
    /**
     * Read a rendered frame back and report what is really in it.
     *
     * ModelViewer exposes debugGetNextFrameCallback(), which does a
     * renderer.readPixels() on the next rendered frame. Everything else tried here
     * has been inference from the outside; this is the framebuffer itself. The
     * background colour it reports settles whether the clear colour reaches the
     * renderer at all, and the count of differing pixels settles whether the model
     * draws.
     */
    private fun captureFrame(active: ModelViewer) {
        active.debugGetNextFrameCallback { bitmap ->
            val report = runCatching {
                val w = bitmap.width
                val h = bitmap.height
                val bg = bitmap.getPixel(0, 0)
                var nonBg = 0
                var total = 0
                // Splitting the frame by luminance separates "the model is not being
                // drawn" from "the model is drawn but unlit, so it is black on a
                // transparent background".
                var clear = 0
                var dark = 0
                var lit = 0
                var minX = w
                var minY = h
                var maxX = -1
                var maxY = -1
                var y = 0
                while (y < h) {
                    var x = 0
                    while (x < w) {
                        total++
                        val p = bitmap.getPixel(x, y)
                        if (android.graphics.Color.alpha(p) < 8) {
                            clear++
                        } else if (android.graphics.Color.red(p) < 24 &&
                            android.graphics.Color.green(p) < 24 &&
                            android.graphics.Color.blue(p) < 24
                        ) {
                            dark++
                        } else {
                            lit++
                        }
                        if (p != bg) {
                            nonBg++
                            if (x < minX) minX = x
                            if (x > maxX) maxX = x
                            if (y < minY) minY = y
                            if (y > maxY) maxY = y
                        }
                        x += 2
                    }
                    y += 2
                }
                val pct = { n: Int -> if (total == 0) 0 else 100 * n / total }
                val box =
                    if (maxX >= 0) "box=($minX,$minY)-($maxX,$maxY)" else "box=empty"
                "bg=#%08X nonBg=%d%% %s\npx clear=%d%% dark=%d%% lit=%d%%".format(
                    bg, pct(nonBg), box, pct(clear), pct(dark), pct(lit),
                )
            }.getOrElse { "readback failed: $it" }
            ServerLog.log("$VRM_TAG: frame $report")
            lastFrameReport = report
            saveFrame(bitmap)
        }
    }

    /**
     * Write the readback somewhere it can actually be looked at. The shared
     * Download folder is tried first because that is what can be pulled off the
     * device; the app's own external dir needs no permission and is the fallback.
     */
    private fun saveFrame(bitmap: Bitmap) {
        // Mirrors ServerLog.exportToDownloads, which is the route the log is known
        // to arrive through. The earlier version added IS_PENDING and RELATIVE_PATH
        // on top of the same insert and always reported false; this is the bare,
        // proven form. The failure reason is logged rather than swallowed, and
        // there is deliberately no private-app-directory fallback.
        val ctx = vrmContext ?: return
        val result = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    // Unique per capture: a fixed name makes MediaStore throw
                    // "Failed to build unique file".
                    put(
                        MediaStore.Downloads.DISPLAY_NAME,
                        "vrm_frame_${System.currentTimeMillis()}.png",
                    )
                    put(MediaStore.Downloads.MIME_TYPE, "image/png")
                }
                val uri = ctx.contentResolver.insert(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI, values,
                ) ?: return@runCatching "insert returned null"
                val stream = ctx.contentResolver.openOutputStream(uri)
                    ?: return@runCatching "openOutputStream returned null"
                stream.use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                "Download/vrm_frame.png"
            } else {
                val dir = Environment.getExternalStoragePublicDirectory(
                    Environment.DIRECTORY_DOWNLOADS,
                )
                val file = File(dir, "vrm_frame.png")
                file.parentFile?.mkdirs()
                FileOutputStream(file).use {
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                }
                file.absolutePath
            }
        }
        ServerLog.log("$VRM_TAG: frame png -> ${result.getOrElse { "FAILED: $it" }}")
    }

    private fun publishDiagnostic(active: ModelViewer) {
        val viewport = active.view.viewport
        // Camera.getModelMatrix(float[]) fills the array; there is no getter property.
        val eye = FloatArray(16)
        active.camera.getModelMatrix(eye)
        val renderables = runCatching { active.asset?.renderableEntities?.size ?: -1 }
            .getOrDefault(-1)
        val sceneCount = runCatching { active.scene.renderableCount }.getOrDefault(-1)
        val message = "rendered=$renderedFrames scene=$sceneCount progress=${active.progress}\n" +
            "renderables=$renderables vp=${viewport.width}x${viewport.height}\n" +
            "eye=(${eye[12]},${eye[13]},${eye[14]}) opaque=${vrmUiHelper?.isOpaque}" +
            (lastFrameReport?.let { "\n$it" } ?: "")
        vrmDiagnostic.value = message
    }

    /**
     * Fits the model into the camera's view — once, and only when it has real bounds.
     *
     * transformToUnitCube() scales by 2/extent, so calling it while the bounding box
     * is still empty — exactly the case immediately after loadModelGlb(), before the
     * render loop has populated any renderables — scales the model by 2/0. That is
     * infinity, and the model can then never be seen. This is why nothing rendered:
     * the surface cleared fine, there was simply nothing inside it.
     */
    private fun frameModel(active: ModelViewer) {
        val asset = active.asset ?: return
        val half = asset.boundingBox.halfExtent
        val maxExtent = 2f * maxOf(half[0], half[1], half[2])
        // Renderables not populated yet — try again next frame rather than poison
        // the root transform with an infinite scale.
        if (maxExtent <= 0.0001f) return
        val tm = active.engine.transformManager
        val hips = bones["hips"]
        val head = bones["head"]
        val rootInstance = runCatching { tm.getInstance(asset.root) }.getOrNull()

        val framedOnBody = hips != null && head != null && rootInstance != null &&
            applyBodyFraming(tm, rootInstance, hips, head)
        if (!framedOnBody) {
            // Fall back to the library's fit if the humanoid bones are missing.
            active.transformToUnitCube()
        }
        framed = true

        // Deterministic camera. ModelViewer's default manipulator is built inside
        // its constructor, which runs from the Compose factory before the view has
        // been laid out - so its viewport is 0x0 and the eye position it derives is
        // degenerate, parking the camera at or inside the model. A model seen from
        // inside fills the viewport with a dark mass, which is what the black
        // rectangle was. With manipulator = null nothing overwrites this.
        active.camera.lookAt(
            0.0, 0.0, kCameraZ,
            0.0, 0.0, kBodyCentreZ,
            0.0, 1.0, 0.0,
        )

        // One diagnostic line that separates the remaining candidates:
        //  * renderables=0     -> gltfio never handed anything to the scene
        //  * viewport=0x0      -> the surface has no size, so nothing can be drawn
        //  * eye == the target -> the camera is sitting inside the model
        ServerLog.log("$VRM_TAG: framed onBody=$framedOnBody extent=$maxExtent")
        vrmDiagnostic.value = "framed onBody=$framedOnBody\nstarting render loop…"
    }

    /**
     * Frames the character instead of the whole asset.
     *
     * ModelViewer.transformToUnitCube() fits the model's *bounding box*, and this
     * avatar's mechanical arms stretch about a body-length to one side — so that box
     * is mostly arm. Fitting it shrinks the figure and drags it toward the arms,
     * which is why nothing was recognisable on screen.
     *
     * Using the humanoid hips and head instead frames the body: the torso length
     * gives a scale, and the hips give the centre.
     */
    private fun applyBodyFraming(
        tm: TransformManager,
        rootInstance: Int,
        hips: Int,
        head: Int,
    ): Boolean {
        val hipsWorld = FloatArray(16)
        val headWorld = FloatArray(16)
        runCatching {
            tm.getWorldTransform(tm.getInstance(hips), hipsWorld)
            tm.getWorldTransform(tm.getInstance(head), headWorld)
        }.onFailure { return false }

        val torso = headWorld[13] - hipsWorld[13]
        if (torso <= 0.0001f) return false

        // Head top sits a little above the head bone; the feet roughly 1.6 torso
        // lengths below the hips.
        val top = headWorld[13] + torso * 0.55f
        val bottom = hipsWorld[13] - torso * 1.60f
        val height = top - bottom
        if (height <= 0.0001f) return false

        val scale = 2f / height
        val centreX = hipsWorld[12]
        val centreY = (top + bottom) / 2f
        val centreZ = hipsWorld[14]

        // Column-major: uniform scale with the body centre mapped to the camera's
        // default target of (0, 0, -4).
        tm.setTransform(
            rootInstance,
            floatArrayOf(
                scale, 0f, 0f, 0f,
                0f, scale, 0f, 0f,
                0f, 0f, scale, 0f,
                -scale * centreX, -scale * centreY, -4f - scale * centreZ, 1f,
            ),
        )
        return true
    }

    fun attach(target: ModelViewer, glb: ByteArray) {
        detach()
        viewer = target

        val names = parseHumanoidBones(glb)
        val asset = target.asset
        if (names.isEmpty() || asset == null) {
            ServerLog.log("$VRM_TAG: no VRMC_vrm humanoid bones found; idle pose disabled")
            return
        }

        val resolved = HashMap<String, Int>()
        for ((humanoidName, nodeName) in names) {
            val entities = asset.getEntitiesByName(nodeName)
            if (entities.isNotEmpty()) resolved[humanoidName] = entities[0]
        }
        bones = resolved
        ServerLog.log("$VRM_TAG: humanoid bones ${resolved.size}/${names.size} resolved " +
            "(${resolved.keys.sorted().joinToString(",")})")

        if (!IDLE_POSE_ENABLED) {
            ServerLog.log("$VRM_TAG: idle pose disabled; rendering bind pose only")
        }
        vrmDiagnostic.value =
            "model loaded\nbones ${resolved.size}/${names.size}\nwaiting for first frames…"

        // The loop runs even with the idle pose off: framing only happens once gltfio
        // has populated the model's renderables, and this is where we poll for that.
        running = true
        framed = false
        Choreographer.getInstance().postFrameCallback(frameCallback)
    }

    fun detach() {
        running = false
        framed = false
        Choreographer.getInstance().removeFrameCallback(frameCallback)
        viewer = null
        bones = emptyMap()
    }

    private fun step(frameTimeNanos: Long) {
        val active = viewer ?: return
        // Belt and braces: the frame callback checks this too, but reaching
        // updateBoneMatrices() with a nulled asset segfaults the process.
        if (active.asset == null) return
        if (bones.isEmpty()) return
        val engine = active.engine
        val tm = engine.transformManager
        val seconds = frameTimeNanos / 1_000_000_000f

        for (bone in IDLE_BONES) {
            val entity = bones[bone.humanoidName] ?: continue
            val angle = sin(seconds * TAU / 4.2f + bone.phase) * bone.amplitude
            val local = FloatArray(16)
            val instance = tm.getInstance(entity)
            tm.getTransform(instance, local)
            tm.setTransform(instance, multiply(local, rotation(bone.axis, angle)))
        }
        // gltfio keeps its own bone-matrix buffer for skinning, so the change only
        // reaches the mesh if it is told to rebuild it.
        runCatching { active.animator?.updateBoneMatrices() }
    }
}

/**
 * Pulls the humanoid bone map out of the VRM extension, returning
 * humanoid bone name -> glTF node name.
 *
 * gltfio does not look inside VRMC_vrm, so the GLB's JSON chunk is read directly.
 * Going via node *names* is what makes this work: FilamentAsset can look entities
 * up by name, but not by glTF node index.
 */
private fun parseHumanoidBones(glb: ByteArray): Map<String, String> = runCatching {
    val buf = ByteBuffer.wrap(glb).order(ByteOrder.LITTLE_ENDIAN)
    if (glb.size < 20) return emptyMap()
    buf.position(12)
    val jsonLength = buf.int
    buf.int // chunk type: 'JSON'
    if (jsonLength <= 0 || jsonLength > buf.remaining()) return emptyMap()

    val jsonBytes = ByteArray(jsonLength)
    buf.get(jsonBytes)
    val json = JSONObject(String(jsonBytes, Charsets.UTF_8))

    val humanBones = json.optJSONObject("extensions")
        ?.optJSONObject("VRMC_vrm")
        ?.optJSONObject("humanoid")
        ?.optJSONObject("humanBones")
        ?: return emptyMap()
    val nodes = json.optJSONArray("nodes") ?: return emptyMap()

    val out = HashMap<String, String>()
    for (name in humanBones.keys()) {
        val nodeIndex = humanBones.optJSONObject(name)?.optInt("node", -1) ?: -1
        if (nodeIndex < 0) continue
        val nodeName = nodes.optJSONObject(nodeIndex)?.optString("name").orEmpty()
        if (nodeName.isNotEmpty()) out[name] = nodeName
    }
    out
}.getOrElse {
    ServerLog.log("$VRM_TAG: could not read VRMC_vrm humanoid map: ${it.message}")
    emptyMap()
}

/** Column-major 4x4 rotation about one axis, matching Filament's layout. */
private fun rotation(axis: Char, angle: Float): FloatArray {
    val c = cos(angle)
    val s = sin(angle)
    return when (axis) {
        'x' -> floatArrayOf(1f, 0f, 0f, 0f, 0f, c, s, 0f, 0f, -s, c, 0f, 0f, 0f, 0f, 1f)
        'y' -> floatArrayOf(c, 0f, -s, 0f, 0f, 1f, 0f, 0f, s, 0f, c, 0f, 0f, 0f, 0f, 1f)
        else -> floatArrayOf(c, s, 0f, 0f, -s, c, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f)
    }
}

/** Column-major 4x4 multiply: a * b. */
private fun multiply(a: FloatArray, b: FloatArray): FloatArray {
    val out = FloatArray(16)
    for (col in 0..3) {
        for (row in 0..3) {
            var sum = 0f
            for (k in 0..3) sum += a[k * 4 + row] * b[col * 4 + k]
            out[col * 4 + row] = sum
        }
    }
    return out
}
