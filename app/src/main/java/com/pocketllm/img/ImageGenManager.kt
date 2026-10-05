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
    fun updateSettings(transform: (ImageGenSettings) -> ImageGenSettings) {
        val next = transform(_settings.value)
        _settings.value = next
        prefs.edit()
            .putString("model_path", next.modelPath)
            .putString("clip_l", next.clipL)
            .putString("clip_g", next.clipG)
            .putString("clip_v", next.clipV)
            .putString("t5xxl", next.t5xxl)
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
            s.t5xxl, s.diffusion, s.vae, s.taesd, s.backend,
        ).joinToString("|")
        if (ctx != 0L && key == loadedKey) return true

        unload()
        _state.value = ImageGenState.Loading("載入模型中…")

        val threads = if (s.threads > 0) s.threads
        else com.pocketllm.llm.CpuInfo.recommendedThreads()
        val handle = SdBridge.nativeCreate(
            s.modelPath.ifBlank { null }, s.clipL.ifBlank { null },
            s.clipG.ifBlank { null }, s.clipV.ifBlank { null },
            s.t5xxl.ifBlank { null }, s.diffusion.ifBlank { null },
            s.vae.ifBlank { null }, s.taesd.ifBlank { null },
            s.backend.ifBlank { null }, threads, 1,  // 1 = SD_TYPE_F16
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
