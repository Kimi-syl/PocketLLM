package com.pocketllm.img

import android.content.Context
import android.graphics.Bitmap
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Model files and generation defaults; persisted through the settings store. */
data class ImageGenSettings(
    val modelPath: String = "",
    val clipL: String = "",
    val clipG: String = "",
    val clipV: String = "",
    val t5xxl: String = "",
    /** Qwen-Image text encoder (Qwen2.5-VL LLM). */
    val llmPath: String = "",
    /** LoRAs as "path|multiplier;path|multiplier". */
    val loras: String = "",
    val diffusion: String = "",
    val vae: String = "",
    val taesd: String = "",
    val backend: String = "cpu",
    val threads: Int = 0,
    val width: Int = 512,
    val height: Int = 512,
    val steps: Int = 20,
    val cfg: Float = 7f,
    val distilled: Float = 0f,
    val seed: Long = -1L,
    val batch: Int = 1,
    val sampleMethod: Int = 0,
    val scheduler: Int = 0,
    val clipSkip: Int = 1,
)

data class GeneratedImage(val file: File, val width: Int, val height: Int)

sealed interface ImageGenState {
    data object Idle : ImageGenState
    data class Loading(val message: String) : ImageGenState
    data class Running(val step: Int, val steps: Int) : ImageGenState
    data class Done(val images: List<GeneratedImage>) : ImageGenState
    data class Failed(val message: String) : ImageGenState
}

/**
 * Owns the Stable Diffusion context and generation lifecycle.
 *
 * The context is created lazily and reused until the model paths change, so
 * repeated generations do not pay the model-load cost each time.
 */
class ImageGenManager(private val context: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _state = MutableStateFlow<ImageGenState>(ImageGenState.Idle)
    val state: StateFlow<ImageGenState> = _state

    private val prefs = context.getSharedPreferences("image_gen", Context.MODE_PRIVATE)
    private val _settings = MutableStateFlow(loadSettings())
    val settings: StateFlow<ImageGenSettings> = _settings

    /** Applies [transform] and persists the result, so it survives restarts. */
    /**
     * Multi-file models (Qwen-Image, Flux, SD3) load their transformer through
     * diffusion_model_path, not model_path. Single-file SD checkpoints go in
     * model_path. Route by filename so picking a downloaded model puts it in the
     * slot the engine actually reads.
     */
    fun pickModelPath(name: String, absolutePath: String): ImageGenSettings {
        val n = name.lowercase()
        // Order matters. Text encoder is tested FIRST because an encoder name
        // can also look like a diffusion one: "qwen3-0.6b-base" is Anima's text
        // encoder, but a bare "qwen" would otherwise be claimed by the diffusion
        // rule below and land the encoder in the model slot - which is what
        // makes the Anima conditioner abort on a tensor shape it never expected.
        val isTextEncoder = n.contains("text_encoder") || n.contains("llm") ||
            n.contains("t5") || n.contains("clip") || n.contains("embed") ||
            n.contains("qwen3") || n.contains("qwen2") || n.contains("qwen_")
        val isVae = n.contains("vae") && !n.contains("taesd")
        // Architecture names, not just generic ones: anima, minit2i and the rest
        // name the family rather than the role, and a bare filename used to fall
        // through to model_path.
        val isDiffusion = !isTextEncoder && !isVae &&
            (n.contains("diffusion") || n.contains("transformer") ||
                n.contains("unet") || n.contains("flux") || n.contains("sd3") ||
                n.contains("mmdit") || n.contains("qwen") || n.contains("anima") ||
                n.contains("minit2i") || n.contains("sensenova") ||
                n.contains("z-image") || n.contains("z_image") ||
                n.contains("ming"))
        return _settings.value.let {
            when {
                isDiffusion -> it.copy(diffusion = absolutePath)
                isTextEncoder -> it.copy(llmPath = absolutePath)
                isVae -> it.copy(vae = absolutePath)
                else -> it.copy(modelPath = absolutePath)
            }
        }
    }

    fun updateSettings(transform: (ImageGenSettings) -> ImageGenSettings) {
        val next = transform(_settings.value)
        _settings.value = next
        prefs.edit()
            .putString("model_path", next.modelPath)
            .putString("clip_l", next.clipL)
            .putString("clip_g", next.clipG)
            .putString("clip_v", next.clipV)
            .putString("t5xxl", next.t5xxl)
            .putString("llm_path", next.llmPath)
            .putString("loras", next.loras)
            .putString("diffusion", next.diffusion)
            .putString("vae", next.vae)
            .putString("taesd", next.taesd)
            .putString("backend", next.backend)
            .putInt("threads", next.threads)
            .putInt("width", next.width)
            .putInt("height", next.height)
            .putInt("steps", next.steps)
            .putFloat("cfg", next.cfg)
            .putFloat("distilled", next.distilled)
            .putLong("seed", next.seed)
            .putInt("batch", next.batch)
            .putInt("sample_method", next.sampleMethod)
            .putInt("scheduler", next.scheduler)
            .putInt("clip_skip", next.clipSkip)
            .apply()
    }

    private fun loadSettings(): ImageGenSettings = ImageGenSettings(
        modelPath = prefs.getString("model_path", "") ?: "",
        clipL = prefs.getString("clip_l", "") ?: "",
        clipG = prefs.getString("clip_g", "") ?: "",
        clipV = prefs.getString("clip_v", "") ?: "",
        t5xxl = prefs.getString("t5xxl", "") ?: "",
        llmPath = prefs.getString("llm_path", "") ?: "",
        loras = prefs.getString("loras", "") ?: "",
        diffusion = prefs.getString("diffusion", "") ?: "",
        vae = prefs.getString("vae", "") ?: "",
        taesd = prefs.getString("taesd", "") ?: "",
        backend = prefs.getString("backend", "cpu") ?: "cpu",
        threads = prefs.getInt("threads", 0),
        width = prefs.getInt("width", 512),
        height = prefs.getInt("height", 512),
        steps = prefs.getInt("steps", 20),
        cfg = prefs.getFloat("cfg", 7f),
        distilled = prefs.getFloat("distilled", 0f),
        seed = prefs.getLong("seed", -1L),
        batch = prefs.getInt("batch", 1),
        sampleMethod = prefs.getInt("sample_method", 0),
        scheduler = prefs.getInt("scheduler", 0),
        clipSkip = prefs.getInt("clip_skip", 1),
    )

    @Volatile
    private var ctx: Long = 0
    private var loadedKey: String = ""
    private var pollJob: Job? = null

    val isAvailable: Boolean
        get() = SdBridge.isAvailable

    fun sampleMethodNames(): Array<String> =
        if (SdBridge.isAvailable) runCatching { SdBridge.nativeSampleMethods() }.getOrDefault(emptyArray())
        else emptyArray()

    fun schedulerNames(): Array<String> =
        if (SdBridge.isAvailable) runCatching { SdBridge.nativeSchedulers() }.getOrDefault(emptyArray())
        else emptyArray()

    fun cancel() {
        if (ctx != 0L) runCatching { SdBridge.nativeCancel(ctx) }
    }

    fun unload() {
        pollJob?.cancel()
        if (ctx != 0L) {
            runCatching { SdBridge.nativeFree(ctx) }
            ctx = 0
            loadedKey = ""
        }
        _state.value = ImageGenState.Idle
    }

    fun generate(settings: ImageGenSettings, prompt: String, negative: String) {
        if (!SdBridge.isAvailable) {
            _state.value = ImageGenState.Failed(
                "圖片引擎無法載入：" + (SdBridge.loadError?.message ?: "libsdcpp.so 未找到")
            )
            return
        }
        if (prompt.isBlank()) {
            _state.value = ImageGenState.Failed("請輸入提示詞")
            return
        }
        scope.launch {
            try {
                if (!ensureContext(settings)) return@launch

                _state.value = ImageGenState.Running(0, settings.steps)
                pollJob?.cancel()
                pollJob = launch {
                    while (isActive) {
                        val p = runCatching { SdBridge.nativeProgress() }.getOrNull()
                        if (p != null && p.size == 2 && p[1] > 0) {
                            _state.value = ImageGenState.Running(p[0], p[1])
                        }
                        delay(300)
                    }
                }

                val packed = SdBridge.nativeGenerate(
                    ctx, prompt, negative.ifBlank { null },
                    settings.width, settings.height, settings.steps,
                    settings.cfg, settings.distilled, settings.seed, settings.batch,
                    settings.sampleMethod, settings.scheduler, settings.clipSkip,
                    settings.loras.ifBlank { null },
                )
                pollJob?.cancel()

                if (packed == null || packed.size < 16) {
                    _state.value = ImageGenState.Failed("生成失敗或已被取消")
                    return@launch
                }

                val images = unpack(packed)
                if (images.isEmpty()) {
                    _state.value = ImageGenState.Failed("生成結果為空")
                } else {
                    _state.value = ImageGenState.Done(images)
                }
            } catch (t: Throwable) {
                pollJob?.cancel()
                _state.value = ImageGenState.Failed(t.message ?: t.toString())
            }
        }
    }

    private fun ensureContext(s: ImageGenSettings): Boolean {
        val key = listOf(
            s.modelPath, s.clipL, s.clipG, s.clipV,
            s.t5xxl, s.llmPath, s.diffusion, s.vae, s.taesd, s.backend,
        ).joinToString("|")
        if (ctx != 0L && key == loadedKey) return true

        unload()
        _state.value = ImageGenState.Loading("載入模型中…")

        val threads = if (s.threads > 0) s.threads
        else com.pocketllm.llm.CpuInfo.recommendedThreads()
        val handle = SdBridge.nativeCreate(
            s.modelPath.ifBlank { null }, s.clipL.ifBlank { null },
            s.clipG.ifBlank { null }, s.clipV.ifBlank { null },
            s.t5xxl.ifBlank { null }, s.llmPath.ifBlank { null }, s.diffusion.ifBlank { null },
            s.vae.ifBlank { null }, s.taesd.ifBlank { null },
            s.backend.ifBlank { null }, threads, -1,  // -1 = engine default
        )
        if (handle == 0L) {
            _state.value = ImageGenState.Failed("模型載入失敗，請確認檔案路徑與格式")
            return false
        }
        ctx = handle
        loadedKey = key
        return true
    }

    private fun unpack(packed: ByteArray): List<GeneratedImage> {
        fun u32(off: Int): Int =
            (packed[off].toInt() and 0xff) or
                ((packed[off + 1].toInt() and 0xff) shl 8) or
                ((packed[off + 2].toInt() and 0xff) shl 16) or
                ((packed[off + 3].toInt() and 0xff) shl 24)

        val w = u32(0); val h = u32(4); val channel = u32(8); val count = u32(12)
        if (w <= 0 || h <= 0 || channel < 3 || count <= 0) return emptyList()

        val perImage = w * h * channel
        val outDir = File(context.getExternalFilesDir(null), "generated").apply { mkdirs() }
        val result = ArrayList<GeneratedImage>(count)

        for (i in 0 until count) {
            val base = 16 + i * perImage
            if (base + perImage > packed.size) break

            val pixels = IntArray(w * h)
            for (p in 0 until w * h) {
                val o = base + p * channel
                val r = packed[o].toInt() and 0xff
                val g = packed[o + 1].toInt() and 0xff
                val b = packed[o + 2].toInt() and 0xff
                val a = if (channel >= 4) packed[o + 3].toInt() and 0xff else 0xff
                pixels[p] = (a shl 24) or (r shl 16) or (g shl 8) or b
            }
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            bmp.setPixels(pixels, 0, w, 0, 0, w, h)

            val file = File(outDir, "img_${System.currentTimeMillis()}_$i.png")
            file.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bmp.recycle()
            result += GeneratedImage(file, w, h)
        }
        return result
    }
}
