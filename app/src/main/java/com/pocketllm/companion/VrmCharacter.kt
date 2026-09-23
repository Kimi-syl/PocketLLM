package com.pocketllm.companion

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
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
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Bundled VRM avatars. Imported ones are listed from VrmLibrary. */
val VRM_MODELS: List<Pair<String, String>> = listOf(
    "Seed-san.vrm" to "Seed-san",
)

private const val VRM_TAG = "vrm"

/**
 * Filament's hard ceiling on joints per skin. Exceeding it is not a warning: the
 * native layer raises a precondition panic and aborts the process, so it has to be
 * checked before the asset is handed over. VRoid Studio exports routinely exceed
 * it, because every hair and clothing bone counts.
 */
internal const val kMaxSkinJoints = 256

/**
 * Largest texture edge this device is asked to upload.
 *
 * VRoid Studio exports every texture at 2048x2048, where the bundled avatar tops
 * out at 1024. Seven 2048x2048 RGBA maps is 112 MB decoded, and the P20HD's
 * GPU cannot hold them: the upload fails, gltfio leaves a black placeholder in
 * place of the texture, and the model comes out solid black with a perfectly
 * healthy log. 1024 keeps the look - these are flat colour maps - and matches
 * the known-good configuration.
 */
internal const val kMaxTextureSize = 1024

/**
 * Serialises a glTF root for a GLB chunk, with org.json's forward-slash escaping
 * undone.
 *
 * org.json writes every `/` as `\/`. That is valid JSON, and JSONObject reads it
 * back correctly, so the value never looks wrong in Kotlin. But gltfio does not
 * decode escapes at all: cgltf's cgltf_parse_json_string copies the token's raw
 * bytes with strncpy and never touches `\/`, so `"image\/png"` reaches
 * ResourceLoader as the literal eleven characters `image\/png`. It then looks
 * that up in a map keyed by `image/png`, misses, logs "Missing texture provider"
 * and returns no texture - the model loads, animates, and draws solid black.
 *
 * The original VRoid files never carry an escaped slash, which is why only
 * files this app rewrote showed the fault.
 */
internal fun gltfJsonBytes(root: JSONObject): ByteArray =
    root.toString().replace("\\/", "/").toByteArray(Charsets.UTF_8)

/** "glTF" as a little-endian int, the magic at the head of every GLB. */
private const val kGlbMagic = 0x46546C67

/** "JSON" chunk type in a GLB. */
private const val kGlbJsonChunk = 0x4E4F534A

/**
 * Byte alignment for every bufferView in a BIN this app rewrites.
 *
 * Held over from a disproved theory: 4-byte offsets were suspected of blackening
 * Android-re-encoded textures, but a controlled file at 4-byte alignment with the
 * slash escaping fixed rendered in colour, so alignment was never the cause (the
 * escaping in gltfJsonBytes was). Kept because wider alignment is harmless and
 * costs only a few bytes of padding.
 */
internal const val kBufferAlignment = 16

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
    var viewer by remember { mutableStateOf<ModelViewer?>(null) }

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
            // Read on IO — these files are ~10 MB. VrmLibrary checks imported
            // avatars before the bundled assets, so an import of the same name
            // takes precedence.
            val raw = withContext(Dispatchers.IO) {
                VrmLibrary.read(context, modelAsset)
            } ?: return@LaunchedEffect

            // Filament enforces this with a hard precondition that aborts the whole
            // process, so it has to be checked before the asset reaches it:
            //   PreconditionPanic in build:495, "bone count > 256"
            val joints = largestSkinJointCount(raw)
            if (joints != null && joints > kMaxSkinJoints) {
                ServerLog.log(
                    "$VRM_TAG: refusing '$modelAsset' - its largest skin has " +
                        "$joints bones and Filament supports $kMaxSkinJoints",
                )
                return@LaunchedEffect
            }

            // Shrink first, so the bytes handed to gltfio are already at a size this
            // GPU can upload. Done on IO with the read, since decoding seven 2048x2048
            // bitmaps on the main thread would stall the UI for seconds.
            val bytes = withContext(Dispatchers.IO) {
                downscaleTextures(normalizeGltf(raw))
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

/**
 * A bone the stance aims: rotate [humanoidName] until the direction toward
 * [towardChild] matches [target], a world-space direction.
 *
 * Aiming in world space deliberately avoids assuming a local axis convention. The
 * bind pose is a T-pose laid out along +/-X, so a hand-authored local rotation
 * needs the correct axis and the correct sign, and either one being wrong leaves
 * the arm somewhere worse than the T-pose it started in. Direction-to-direction
 * needs neither: it reads where the bone points and rotates the short way.
 */
private class AimBone(
    val humanoidName: String,
    val towardChild: String,
    val target: FloatArray,
    val phase: Float,
)

/** Breathing and weight shift on the spine chain, applied on top of the stance. */
private class DriftBone(
    val humanoidName: String,
    val axis: Char,
    val amplitude: Float,
    val phase: Float,
    val periodSeconds: Float,
)

private val DRIFT_BONES = listOf(
    // Weight shift: the whole body leans onto one leg and settles back.
    DriftBone("hips", 'y', 0.100f, 0.00f, 6.5f),
    DriftBone("spine", 'y', 0.110f, 0.35f, 6.5f),
    DriftBone("chest", 'y', 0.080f, 0.70f, 6.5f),
    DriftBone("neck", 'y', 0.080f, 1.00f, 6.5f),
    // Breathing, on its own faster cycle.
    DriftBone("spine", 'x', 0.050f, 0.00f, 3.8f),
    DriftBone("chest", 'x', 0.035f, 0.20f, 3.8f),
    DriftBone("neck", 'x', 0.025f, 0.40f, 3.8f),
    // Looking around, deliberately on a much slower cycle than either of the
    // above so the three never peak together.
    DriftBone("head", 'y', 0.280f, 0.40f, 9.5f),
    DriftBone("head", 'x', 0.100f, 1.70f, 7.5f),
)

/**
 * Arms down at the sides, not perfectly vertical: a straight-down arm reads as
 * stiff, and a little outward angle keeps the hands clear of the body. Elbows are
 * given a slightly more forward target than the upper arm so the arm hangs with a
 * natural bend rather than as one rigid line.
 */
private fun buildStance(active: ModelViewer, bones: Map<String, Int>): List<AimBone> {
    val tm = active.engine.transformManager

    fun worldX(name: String): Float? {
        val entity = bones[name] ?: return null
        val out = FloatArray(16)
        return runCatching {
            tm.getWorldTransform(tm.getInstance(entity), out)
            out[12]
        }.getOrNull()
    }

    // Which way is "out" for this rig, rather than trusting the +X convention.
    val hipsX = worldX("hips")
    val leftX = worldX("leftUpperArm")
    val leftSide = if (hipsX != null && leftX != null && leftX < hipsX) -1f else 1f

    fun arm(bone: String, child: String, side: Float, out: Float, down: Float, fwd: Float, phase: Float) =
        AimBone(bone, child, normalize3(side * out, down, fwd), phase)

    return listOf(
        arm("leftUpperArm", "leftLowerArm", leftSide, 0.20f, -0.97f, 0.05f, 0.0f),
        arm("leftLowerArm", "leftHand", leftSide, 0.08f, -0.99f, 0.14f, 0.7f),
        arm("rightUpperArm", "rightLowerArm", -leftSide, 0.20f, -0.97f, 0.05f, 2.1f),
        arm("rightLowerArm", "rightHand", -leftSide, 0.08f, -0.99f, 0.14f, 2.8f),
    )
}

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
    private var rest: Map<String, FloatArray> = emptyMap()
    private var stance: List<AimBone> = emptyList()

    /** Legacy avatars need half a turn to face the camera. */
    private var faceFlip = false
    private var running = false
    private var lastStepNanos = 0L
    private var framed = false

    private var renderedFrames = 0

    /** So the load report is emitted once, not every frame. */
    private var reportedLoaded = false

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
                    reportResourceLoading(active)
                }
                // Guards: destroyModel() clears the asset, and the bounding box only
                // exists once gltfio has populated the renderables.
                if (active.asset != null && !framed) frameModel(active)
                if (framed && frameTimeNanos - lastStepNanos >= STEP_INTERVAL_NANOS) {
                    lastStepNanos = frameTimeNanos
                    step(frameTimeNanos)
                }
            }
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    /**
     * Log-backed probe while gltfio's asynchronous load is still in flight.
     *
     * The on-screen readout was removed once the render path worked, but a model
     * whose textures never finish arriving draws with placeholder maps - which is
     * what a solid black figure looks like. This says so in the log without putting
     * anything back on screen.
     *
     * Note there is deliberately no re-framing on completion. An earlier attempt
     * called frameModel() again here to undo gltfio's own node transforms, but
     * applyBodyFraming reads the hips and head *world* transforms - which already
     * include the scale it wrote on the first pass. Measuring the torso a second
     * time therefore returned an already-shrunk length and derived a smaller scale
     * from it, compounding the shrink and leaving the figure far too small.
     */
    private fun reportResourceLoading(active: ModelViewer) {
        val progress = runCatching { active.progress }.getOrDefault(1f)
        if (progress >= 1f) {
            if (!reportedLoaded) {
                reportedLoaded = true
                val renderables = runCatching {
                    active.asset?.renderableEntities?.size ?: -1
                }.getOrDefault(-1)
                ServerLog.log("$VRM_TAG: resources loaded, renderables=$renderables")
            }
            return
        }
        if (renderedFrames % 150 == 0) {
            ServerLog.log("$VRM_TAG: resources still loading, progress=$progress")
        }
    }

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
            if (faceFlip && rootInstance != null) flipRootAboutY(tm, rootInstance)
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
        // default target of (0, 0, -4). A legacy avatar additionally gets a half
        // turn about Y, which negates the X and Z basis and moves the body centre
        // accordingly.
        val scaleX = if (faceFlip) -scale else scale
        val scaleZ = if (faceFlip) -scale else scale
        val translateX = if (faceFlip) scale * centreX else -scale * centreX
        val translateZ = if (faceFlip) -4f + scale * centreZ else -4f - scale * centreZ
        tm.setTransform(
            rootInstance,
            floatArrayOf(
                scaleX, 0f, 0f, 0f,
                0f, scale, 0f, 0f,
                0f, 0f, scaleZ, 0f,
                translateX, -scale * centreY, translateZ, 1f,
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

        // Bind pose, captured before anything is posed. Each frame rebuilds the
        // pose from this, so successive frames cannot accumulate on each other -
        // the previous version multiplied the live transform, which drifts.
        val posed = (buildStance(target, resolved).map { it.humanoidName } +
            DRIFT_BONES.map { it.humanoidName }).toSet()
        rest = resolved.filterKeys { it in posed }.mapValues { (_, entity) ->
            val local = FloatArray(16)
            runCatching { target.engine.transformManager.getTransform(
                target.engine.transformManager.getInstance(entity), local,
            ) }
            local
        }
        stance = buildStance(target, resolved)
        faceFlip = isLegacyVrm(glb)
        ServerLog.log(
            "$VRM_TAG: stance built over ${rest.size} bones, legacy=$faceFlip",
        )
        ServerLog.log("$VRM_TAG: stance built over ${rest.size} bones")
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
        reportedLoaded = false
        renderedFrames = 0
        faceFlip = false
        rest = emptyMap()
        stance = emptyList()
    }

    private fun step(frameTimeNanos: Long) {
        val active = viewer ?: return
        // Belt and braces: the frame callback checks this too, but reaching
        // updateBoneMatrices() with a nulled asset segfaults the process.
        if (active.asset == null) return
        // gltfio rebuilds the skinned mesh from this buffer, and calling it while
        // the asynchronous load is still in flight is what faulted natively. Wait
        // until every resource it needs is actually resident.
        if (active.progress < 1f) return
        if (bones.isEmpty() || stance.isEmpty()) return

        val tm = active.engine.transformManager
        val seconds = frameTimeNanos / 1_000_000_000f

        // 1. Back to the bind pose, so the pose below is absolute rather than
        //    layered onto last frame's result.
        for ((name, bind) in rest) {
            val entity = bones[name] ?: continue
            runCatching { tm.setTransform(tm.getInstance(entity), bind) }
        }

        // 2. Aim the arms. Chain order matters - the forearm has to be aimed after
        //    the upper arm has moved, or it is aimed from a stale direction.
        for (limb in stance) {
            val bone = bones[limb.humanoidName] ?: continue
            val child = bones[limb.towardChild] ?: continue
            // A real forward/back swing, with a little vertical ride on top of it.
            // The previous 0.03 was barely a twitch.
            val sway = sin(seconds * TAU / 6.0f + limb.phase) * 0.18f
            val target = normalize3(
                limb.target[0],
                limb.target[1] + sway * 0.35f,
                limb.target[2] + sway,
            )
            val delta = aimDelta(tm, bone, child, target) ?: continue
            val instance = tm.getInstance(bone)
            val local = FloatArray(16)
            tm.getTransform(instance, local)
            tm.setTransform(instance, multiply(local, delta))
        }

        // 3. Breathing and weight shift on the spine chain, on top of the stance.
        for (bone in DRIFT_BONES) {
            val entity = bones[bone.humanoidName] ?: continue
            val angle = sin(seconds * TAU / bone.periodSeconds + bone.phase) * bone.amplitude
            val instance = tm.getInstance(entity)
            val local = FloatArray(16)
            tm.getTransform(instance, local)
            tm.setTransform(instance, multiply(local, rotation(bone.axis, angle)))
        }

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

    val extensions = json.optJSONObject("extensions") ?: return emptyMap()
    val nodes = json.optJSONArray("nodes") ?: return emptyMap()

    // Two generations, two shapes, and both are common enough that supporting only
    // one of them looks like "this model has no bones":
    //   VRM 1.0  VRMC_vrm.humanoid.humanBones  -> object keyed by bone name
    //   VRM 0.x  VRM.humanoid.humanBones       -> array of { bone, node }
    val pairs = ArrayList<Pair<String, Int>>()

    extensions.optJSONObject("VRMC_vrm")
        ?.optJSONObject("humanoid")
        ?.optJSONObject("humanBones")
        ?.let { bones ->
            for (name in bones.keys()) {
                val index = bones.optJSONObject(name)?.optInt("node", -1) ?: -1
                if (index >= 0) pairs.add(name to index)
            }
        }

    if (pairs.isEmpty()) {
        val list = extensions.optJSONObject("VRM")
            ?.optJSONObject("humanoid")
            ?.optJSONArray("humanBones")
        if (list != null) {
            for (i in 0 until list.length()) {
                val entry = list.optJSONObject(i) ?: continue
                val name = entry.optString("bone").orEmpty()
                val index = entry.optInt("node", -1)
                if (name.isNotEmpty() && index >= 0) pairs.add(name to index)
            }
        }
    }

    val out = HashMap<String, String>()
    for ((name, index) in pairs) {
        val nodeName = nodes.optJSONObject(index)?.optString("name").orEmpty()
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


private fun normalize3(x: Float, y: Float, z: Float): FloatArray {
    val len = sqrt(x * x + y * y + z * z).takeIf { it > 1e-6f } ?: 1f
    return floatArrayOf(x / len, y / len, z / len)
}

/** Column-major 4x4 rotation of [angle] about an arbitrary unit [axis]. */
private fun rotationAboutAxis(axis: FloatArray, angle: Float): FloatArray {
    val x = axis[0]
    val y = axis[1]
    val z = axis[2]
    val c = cos(angle)
    val sn = sin(angle)
    val t = 1f - c
    return floatArrayOf(
        t * x * x + c, t * x * y + sn * z, t * x * z - sn * y, 0f,
        t * x * y - sn * z, t * y * y + c, t * y * z + sn * x, 0f,
        t * x * z + sn * y, t * y * z - sn * x, t * z * z + c, 0f,
        0f, 0f, 0f, 1f,
    )
}

/**
 * Local-space rotation that swings [bone] so the direction it points toward
 * [child] matches [target] in world space.
 *
 * The world axis comes out of the cross product; it is moved into the bone's own
 * frame with the transpose of the world basis, which is the inverse for a
 * rotation. Uniform scale cancels once the axis is renormalised, so this does not
 * care what scale the rig is authored at.
 */
private fun aimDelta(
    tm: TransformManager,
    bone: Int,
    child: Int,
    target: FloatArray,
): FloatArray? {
    val selfWorld = FloatArray(16)
    val childWorld = FloatArray(16)
    runCatching {
        tm.getWorldTransform(tm.getInstance(bone), selfWorld)
        tm.getWorldTransform(tm.getInstance(child), childWorld)
    }.onFailure { return null }

    val dx = childWorld[12] - selfWorld[12]
    val dy = childWorld[13] - selfWorld[13]
    val dz = childWorld[14] - selfWorld[14]
    val len = sqrt(dx * dx + dy * dy + dz * dz)
    if (len < 1e-6f) return null
    val ux = dx / len
    val uy = dy / len
    val uz = dz / len

    val dotted = (ux * target[0] + uy * target[1] + uz * target[2]).coerceIn(-1f, 1f)
    val angle = acos(dotted)
    if (angle < 1e-3f) return null

    val ax = uy * target[2] - uz * target[1]
    val ay = uz * target[0] - ux * target[2]
    val az = ux * target[1] - uy * target[0]
    val axisLen = sqrt(ax * ax + ay * ay + az * az)
    if (axisLen < 1e-6f) return null
    val nx = ax / axisLen
    val ny = ay / axisLen
    val nz = az / axisLen

    // World -> bone local: each column of the world basis is a local axis.
    val localAxis = floatArrayOf(
        selfWorld[0] * nx + selfWorld[1] * ny + selfWorld[2] * nz,
        selfWorld[4] * nx + selfWorld[5] * ny + selfWorld[6] * nz,
        selfWorld[8] * nx + selfWorld[9] * ny + selfWorld[10] * nz,
    )
    return rotationAboutAxis(normalize3(localAxis[0], localAxis[1], localAxis[2]), angle)
}

/**
 * Joints in the file's largest skin, or null when the bytes are not a readable GLB.
 */
/**
 * True for VRM 0.x avatars, which are authored facing -Z.
 *
 * VRM 1.0 changed the facing direction to +Z, so a 0.x model presented to a camera
 * on the +Z side shows its back. Backface culling then removes those surfaces and
 * what remains on screen is the inside of the mesh, lit from behind: a solid black
 * figure rather than a dark one. Measured on the imported VRoid files, whose mean
 * vertex normal is -Z, against +Z for the bundled 1.0 avatar.
 */
internal fun isLegacyVrm(glb: ByteArray): Boolean = runCatching {
    val buf = ByteBuffer.wrap(glb).order(ByteOrder.LITTLE_ENDIAN)
    if (buf.getInt(0) != kGlbMagic) return@runCatching false
    val jsonLength = buf.getInt(12)
    if (buf.getInt(16) != kGlbJsonChunk) return@runCatching false
    val root = JSONObject(String(glb, 20, jsonLength, Charsets.UTF_8))
    val extensions = root.optJSONObject("extensions") ?: return@runCatching false
    extensions.has("VRM") && !extensions.has("VRMC_vrm")
}.getOrDefault(false)

internal fun largestSkinJointCount(bytes: ByteArray): Int? = runCatching {
    val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
    if (buf.getInt(0) != kGlbMagic) return@runCatching null
    val jsonLength = buf.getInt(12)
    if (buf.getInt(16) != kGlbJsonChunk) return@runCatching null
    val root = JSONObject(String(bytes, 20, jsonLength, Charsets.UTF_8))
    val skins = root.optJSONArray("skins") ?: return@runCatching 0
    var largest = 0
    for (i in 0 until skins.length()) {
        val joints = skins.optJSONObject(i)?.optJSONArray("joints") ?: continue
        if (joints.length() > largest) largest = joints.length()
    }
    largest
}.getOrNull()

private fun stripExtension(obj: JSONObject, name: String): Boolean {
    val extensions = obj.optJSONObject("extensions") ?: return false
    if (!extensions.has(name)) return false
    extensions.remove(name)
    if (extensions.length() == 0) obj.remove("extensions")
    return true
}

private fun stripTextureTransform(info: JSONObject?): Boolean {
    if (info == null) return false
    return stripExtension(info, "KHR_texture_transform")
}

/**
 * Rewrites a GLB into something gltfio draws correctly, in place of shipping
 * pre-processed assets.
 *
 * Three separate things go wrong with VRM exports and each one alone produces a
 * flat white figure:
 *
 *  - `KHR_materials_unlit` puts the material on the unlit path, so it has no
 *    shading at all.
 *  - `KHR_texture_transform` carries a UV scale/offset that gltfio does not
 *    implement. Ignoring it samples the wrong region of the atlas.
 *  - `metallicFactor` defaults to 1 when absent. MToon has no metallic concept, so
 *    exporters leave the default behind, and a fully metallic surface has no
 *    diffuse term and renders black without a reflection cubemap.
 *
 * Returns the original array when nothing needed changing.
 */
internal fun normalizeGltf(bytes: ByteArray): ByteArray = runCatching {
    val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
    if (buf.getInt(0) != kGlbMagic) return@runCatching bytes
    val declared = buf.getInt(8)
    val jsonLength = buf.getInt(12)
    if (buf.getInt(16) != kGlbJsonChunk) return@runCatching bytes

    val root = JSONObject(String(bytes, 20, jsonLength, Charsets.UTF_8))
    var changed = false
    var unlitStripped = 0
    var transformsStripped = 0
    var metallicForced = 0


    val materials = root.optJSONArray("materials")
    if (materials != null) {
        for (i in 0 until materials.length()) {
            val material = materials.optJSONObject(i) ?: continue
            if (stripExtension(material, "KHR_materials_unlit")) {
                changed = true
                unlitStripped++
            }
            stripExtension(material, "VRMC_materials_mtoon")

            val pbr = material.optJSONObject("pbrMetallicRoughness")
            if (pbr != null) {
                if (pbr.optDouble("metallicFactor", 1.0) != 0.0) {
                    pbr.put("metallicFactor", 0.0)
                    changed = true
                    metallicForced++
                }
                if (pbr.has("metallicRoughnessTexture")) {
                    pbr.remove("metallicRoughnessTexture")
                    changed = true
                }
                if (stripTextureTransform(pbr.optJSONObject("baseColorTexture"))) {
                    changed = true
                    transformsStripped++
                }
            }
            for (key in listOf("normalTexture", "occlusionTexture", "emissiveTexture")) {
                if (stripTextureTransform(material.optJSONObject(key))) changed = true
            }
        }
    }

    ServerLog.log(
        "$VRM_TAG: normalise -> ${if (changed) "rewritten" else "nothing to do"}" +
            " (unlit=$unlitStripped transforms=$transformsStripped metallic=$metallicForced)",
    )
    if (!changed) return@runCatching bytes

    val used = root.optJSONArray("extensionsUsed")
    if (used != null) {
        val keep = ArrayList<String>()
        for (i in 0 until used.length()) {
            val name = used.optString(i)
            if (name != "KHR_materials_unlit" && name != "KHR_texture_transform") keep.add(name)
        }
        val rebuilt = org.json.JSONArray()
        for (name in keep) rebuilt.put(name)
        root.put("extensionsUsed", rebuilt)
    }

    val json = gltfJsonBytes(root)
    val pad = (4 - json.size % 4) % 4
    val padded = json.size + pad

    // Everything from the end of the JSON chunk onward is the BIN chunk, verbatim.
    val tailStart = 20 + jsonLength
    val tailEnd = if (declared in 1..bytes.size) declared else bytes.size
    val tail = bytes.copyOfRange(tailStart, tailEnd)

    val out = ByteArray(12 + 8 + padded + tail.size)
    val writer = ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN)
    writer.putInt(kGlbMagic)
    writer.putInt(2)
    writer.putInt(out.size)
    writer.putInt(padded)
    writer.putInt(kGlbJsonChunk)
    writer.put(json)
    repeat(pad) { writer.put(0x20) }
    writer.put(tail)
    out
}.getOrElse {
    ServerLog.log("$VRM_TAG: normalisation failed, loading as-is: ${it.message}")
    bytes
}

/**
 * Rotates an existing root transform 180 degrees about Y, in place.
 *
 * Premultiplying by that rotation only touches the X and Z rows, so the whole
 * operation is four negations rather than a matrix multiply.
 */
private fun flipRootAboutY(tm: TransformManager, instance: Int) {
    val m = FloatArray(16)
    runCatching { tm.getTransform(instance, m) }.onFailure { return }
    for (col in 0..3) {
        m[col * 4] = -m[col * 4]
        m[col * 4 + 2] = -m[col * 4 + 2]
    }
    runCatching { tm.setTransform(instance, m) }
}

/**
 * Re-encodes any embedded texture whose edge exceeds [kMaxTextureSize].
 *
 * VRoid Studio exports every map at 2048x2048, where the bundled avatar tops out
 * at 1024. Seven 2048x2048 RGBA maps is 112 MB decoded and this device's GPU
 * cannot hold them: the upload fails, gltfio leaves a black placeholder where the
 * texture should be, and the model renders solid black with a healthy-looking log.
 * These maps are flat colour, so half an edge loses nothing.
 */
/**
 * Rewrites a PNG keeping only its structural chunks: IHDR, every IDAT, IEND.
 *
 * Ancillary chunks - sRGB, sBIT, gAMA, iCCP and friends - alter how a decoder
 * interprets the colour data. Android's encoder emits a combination the original
 * VRoid files do not carry, and that difference is the one remaining distinction
 * between app-produced files (black) and files produced elsewhere (colour) with
 * byte-identical texture content. Dropping them makes the encoded form match the
 * known-good form exactly.
 */
private fun stripAncillaryPngChunks(png: ByteArray): ByteArray {
    if (png.size < 8) return png
    val out = java.io.ByteArrayOutputStream(png.size)
    out.write(png, 0, 8)
    var pos = 8
    while (pos + 8 <= png.size) {
        val length = ((png[pos].toInt() and 0xFF) shl 24) or
            ((png[pos + 1].toInt() and 0xFF) shl 16) or
            ((png[pos + 2].toInt() and 0xFF) shl 8) or
            (png[pos + 3].toInt() and 0xFF)
        val type = String(png, pos + 4, 4, Charsets.US_ASCII)
        val end = pos + 12 + length
        if (end > png.size) return png
        when (type) {
            "IHDR", "IDAT", "IEND" -> out.write(png, pos, 12 + length)
        }
        if (type == "IEND") break
        pos = end
    }
    return out.toByteArray()
}

internal fun downscaleTextures(bytes: ByteArray): ByteArray = runCatching {
    val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
    if (buf.getInt(0) != kGlbMagic) return@runCatching bytes
    val declared = buf.getInt(8)
    if (declared != bytes.size) return@runCatching bytes
    val jsonLength = buf.getInt(12)
    if (buf.getInt(16) != kGlbJsonChunk) return@runCatching bytes
    val root = JSONObject(String(bytes, 20, jsonLength, Charsets.UTF_8))
    val images = root.optJSONArray("images") ?: return@runCatching bytes
    val bufferViews = root.optJSONArray("bufferViews") ?: return@runCatching bytes

    val jsonPadding = (4 - jsonLength % 4) % 4
    // bufferView byteOffsets are relative to the start of the BIN chunk's *data*,
    // which sits after the chunk's own 8-byte header (length + "BIN\0"). Missing
    // those 8 bytes shifts every read, the PNG signature check then fails, and the
    // whole pass silently no-ops.
    val binOffset = 20 + jsonLength + jsonPadding + 8
    val binData = bytes.copyOfRange(binOffset, declared)
    val rebuilt = java.io.ByteArrayOutputStream().apply { write(binData) }

    var shrunk = 0
    for (i in 0 until images.length()) {
        val image = images.optJSONObject(i) ?: continue
        val bvIndex = image.optInt("bufferView", -1)
        if (bvIndex < 0 || bvIndex >= bufferViews.length()) continue
        val bv = bufferViews.optJSONObject(bvIndex) ?: continue
        val offset = bv.optInt("byteOffset", 0)
        val length = bv.optInt("byteLength", 0)
        if (offset < 0 || length <= 0 || offset + length > binData.size) continue
        val png = binData.copyOfRange(offset, offset + length)
        val scaled = shrinkBitmap(png)
        if (scaled == png) {
            ServerLog.log("$VRM_TAG: image $i left at original size")
            continue
        }
        // Pad to kBufferAlignment before writing. Appending straight after the
        // previous image leaves it at an arbitrary offset; wider alignment is
        // harmless and kept from an earlier, disproved theory.
        val alignPad = (kBufferAlignment - rebuilt.size() % kBufferAlignment) % kBufferAlignment
        repeat(alignPad) { rebuilt.write(0) }
        bv.put("byteOffset", rebuilt.size())
        bv.put("byteLength", scaled.size)
        rebuilt.write(scaled)
        shrunk++
        ServerLog.log(
            "$VRM_TAG: image $i shrunk " +
                "${length / 1024}KB -> ${scaled.size / 1024}KB",
        )
    }
    ServerLog.log(
        "$VRM_TAG: downscale scanned ${images.length()} image(s), shrunk $shrunk",
    )
    if (shrunk == 0) return@runCatching bytes

    val binBytes = rebuilt.toByteArray()
    val binPad = (4 - binBytes.size % 4) % 4

    // buffers[0].byteLength describes the BIN chunk, and it has just changed
    // size. Leaving the original value makes every bufferView look like it
    // overruns the buffer, which gltfio answers by leaving textures unbound -
    // the model loads, animates, and draws solid black.
    //
    // This must happen BEFORE the JSON is serialised. It used to sit after, so
    // the stale length was what actually got written and only the in-memory
    // object was corrected - which is why shrinking textures blackened even a
    // model that rendered perfectly, and why the fix looked present in the
    // source while never reaching a file.
    root.optJSONArray("buffers")?.optJSONObject(0)
        ?.put("byteLength", binBytes.size)

    val json = gltfJsonBytes(root)
    val jsonPad = (4 - json.size % 4) % 4

    val out = ByteArray(12 + 8 + json.size + jsonPad + 8 + binBytes.size + binPad)
    val w = ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN)
    w.putInt(kGlbMagic)
    w.putInt(2)
    w.putInt(out.size)
    w.putInt(json.size + jsonPad)
    w.putInt(kGlbJsonChunk)
    w.put(json)
    repeat(jsonPad) { w.put(0x20) }
    w.putInt(binBytes.size + binPad)
    w.putInt(0x004E4942) // 'B','I','N','\0' little-endian
    w.put(binBytes)
    repeat(binPad) { w.put(0x00) }
    out
}.getOrElse { bytes }

/**
 * Decodes [png], and if an edge exceeds the budget, scales it down and re-encodes.
 * Returns the input unchanged when it already fits or cannot be decoded.
 */
private fun shrinkBitmap(input: ByteArray): ByteArray = try {
    shrinkBitmapThrowing(input)
} catch (failure: Throwable) {
    // An OutOfMemoryError here previously aborted the whole pass through the
    // caller's runCatching, which returned the file unshrunk and silent - the
    // avatar then shipped with 2048-wide textures and rendered black. One
    // texture failing must not silently undo the others.
    ServerLog.log("$VRM_TAG: texture shrink failed, kept original: ${failure.javaClass.simpleName}")
    input
}

private fun shrinkBitmapThrowing(png: ByteArray): ByteArray {
    if (png.size < 8 || !png.copyOfRange(0, 8).contentEquals(
            byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
        )
    ) return png

    // Bounds first: the alternative - decoding at full size to read the
    // dimensions - allocates 16 MB per 2048x2048 map, and seven of those is
    // what pushed this app past its heap limit. inSampleSize decodes straight
    // to the target size, a quarter of the memory, and for these exact
    // power-of-two exports it is also the exact target edge.
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(png, 0, png.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
        ServerLog.log("$VRM_TAG: texture decode failed for a ${png.size}-byte PNG")
        return png
    }
    val longest = maxOf(bounds.outWidth, bounds.outHeight)
    if (longest <= kMaxTextureSize) return png

    var sample = 1
    while (longest / (sample * 2) >= kMaxTextureSize) sample *= 2

    val options = BitmapFactory.Options().apply {
        inPreferredConfig = Bitmap.Config.ARGB_8888
        // Bitmap keeps RGB premultiplied by alpha by default. A quarter of these
        // maps' pixels have alpha 0 with bright RGB underneath (hair/cards over
        // opaque body paint), and premultiplying drags that RGB to black - which
        // then bakes into the re-encoded texture and shades the whole surface
        // dark.
        inPremultiplied = false
        inSampleSize = sample
    }
    val bmp = BitmapFactory.decodeByteArray(png, 0, png.size, options)
    if (bmp == null) {
        ServerLog.log("$VRM_TAG: texture decode failed at sample $sample")
        return png
    }

    val result = try {
        val w = bmp.width
        val h = bmp.height
        val needsScale = maxOf(w, h) > kMaxTextureSize
        val finalBitmap = if (needsScale) {
            val scale = kMaxTextureSize.toFloat() / maxOf(w, h)
            Bitmap.createScaledBitmap(
                bmp,
                maxOf(1, (w * scale).toInt()),
                maxOf(1, (h * scale).toInt()),
                true,
            )
        } else {
            bmp
        }
        val out = java.io.ByteArrayOutputStream()
        val ok = finalBitmap.compress(Bitmap.CompressFormat.PNG, 90, out)
        if (needsScale && finalBitmap !== bmp) bmp.recycle()
        if (!ok) {
            ServerLog.log("$VRM_TAG: PNG encode failed for a $w x $h texture")
            png
        } else {
            // Android's encoder adds sRGB and sBIT chunks the source never had.
            // Every file that renders on this GPU has the bare IHDR/IDAT/IEND
            // layout; every Android-encoded one so far has come out black. The
            // colour data is identical either way, so the ancillary chunks are
            // the only difference and are stripped here.
            stripAncillaryPngChunks(out.toByteArray())
        }
    } finally {
        bmp.recycle()
    }
    return result
}
