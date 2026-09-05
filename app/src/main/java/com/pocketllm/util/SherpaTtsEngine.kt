package com.pocketllm.util

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.apache.commons.compress.archivers.ArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

/**
 * Piper TTS engine backed by sherpa-onnx.
 * Downloads the pre-bundled sherpa-onnx Piper model (which already includes
 * espeak-ng-data) on first use, extracts it, then loads the model.
 */
class SherpaTtsEngine(private val context: Context) {

    companion object {
        private const val TAG = "SherpaTts"
        // sherpa-onnx release tarball includes the .onnx, tokens.txt, AND espeak-ng-data
        private const val MODEL_URL = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/vits-piper-en_US-amy-medium.tar.bz2"
    }

    sealed interface State {
        data object NotReady : State
        data class Downloading(val file: String, val receivedBytes: Long, val totalBytes: Long) : State
        data class Extracting(val file: String) : State
        data object Ready : State
        data class Error(val message: String) : State
    }

    private val _state = MutableStateFlow<State>(State.NotReady)
    val state: StateFlow<State> = _state

    private val _progress = MutableStateFlow(0f)
    val progress: StateFlow<Float> = _progress

    private val _status = MutableStateFlow("")
    val status: StateFlow<String> = _status

    private var tts: com.k2fsa.sherpa.onnx.OfflineTts? = null
    private var audioTrack: AudioTrack? = null
    private val baseDir: File = File(context.filesDir, "tts/piper")
    // After extraction: baseDir/vits-piper-en_US-amy-medium/{model.onnx, tokens.txt, espeak-ng-data/}
    private val modelDir: File = File(baseDir, "vits-piper-en_US-amy-medium")

    val isReady: Boolean get() = _state.value is State.Ready

    /** True when the model files are already on disk (downloaded previously). */
    val hasModelFiles: Boolean
        get() = File(modelDir, "en_US-amy-medium.onnx").exists() &&
            File(modelDir, "tokens.txt").exists() &&
            File(modelDir, "espeak-ng-data/phondata").exists()
    val isSpeaking: Boolean get() = audioTrack?.playState == AudioTrack.PLAYSTATE_PLAYING

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(300, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .followRedirects(true)
        .build()

    suspend fun ensureModel(downloadIfMissing: Boolean = true): Boolean = withContext(Dispatchers.IO) {
        if (_state.value is State.Ready) return@withContext true

        val onnxFile = File(modelDir, "en_US-amy-medium.onnx")
        val tokensFile = File(modelDir, "tokens.txt")
        val phondata = File(modelDir, "espeak-ng-data/phondata")

        if (onnxFile.exists() && tokensFile.exists() && phondata.exists()) {
            return@withContext loadModel()
        }

        if (!downloadIfMissing) {
            // Load-only pass: do not start a download from here. Used at
            // startup and on engine switch so an already-downloaded model
            // is picked up without any "downloading" indication.
            _status.value = "Piper model not downloaded yet"
            return@withContext false
        }

        try {
            // Clean any partial extraction
            if (baseDir.exists()) baseDir.deleteRecursively()
            baseDir.mkdirs()

            _state.value = State.Downloading("Piper model", 0, 0)
            _status.value = "Downloading Piper model (~65 MB)…"
            downloadAndExtract(MODEL_URL, baseDir.absolutePath, "Piper model")
            Log.d(TAG, "Extracted to ${baseDir.absolutePath}, contents: ${baseDir.listFiles()?.map { it.name }}")

            loadModel()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to init TTS", e)
            val msg = e.message ?: e.javaClass.simpleName
            _state.value = State.Error(msg)
            _status.value = "Download failed: $msg"
            false
        }
    }

    /**
     * Retry the download. Useful when the first attempt hit a network error.
     */
    suspend fun retry(): Boolean {
        if (baseDir.exists()) baseDir.deleteRecursively()
        _state.value = State.NotReady
        _progress.value = 0f
        _status.value = ""
        return ensureModel()
    }

    private fun downloadAndExtract(url: String, targetDir: String, label: String) {
        val targetDirFile = File(targetDir)
        targetDirFile.mkdirs()
        val tmpFile = File(targetDirFile, "download.tmp")
        try {
            Log.d(TAG, "Downloading $label from $url")
            val request = Request.Builder().url(url).build()
            client.newCall(request).execute().use { response ->
                Log.d(TAG, "HTTP ${response.code} ${response.message}, content-length=${response.body?.contentLength()}")
                if (!response.isSuccessful) error("HTTP ${response.code} ${response.message}")
                val body = response.body ?: error("Empty body")
                val total = body.contentLength()
                var received = 0L
                body.byteStream().use { input ->
                    FileOutputStream(tmpFile).use { output ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            output.write(buf, 0, n)
                            received += n
                            if (total > 0) {
                                val frac = (received.toDouble() / total).toFloat()
                                _progress.value = frac
                                _state.value = State.Downloading(label, received, total)
                            } else if (received % (512 * 1024) == 0L) {
                                _state.value = State.Downloading(label, received, 0)
                            }
                            if (received % (5 * 1024 * 1024) == 0L) {
                                Log.d(TAG, "Downloaded ${received / 1024 / 1024} MB / ${if (total > 0) "${total / 1024 / 1024} MB" else "?"}")
                            }
                        }
                    }
                }
                Log.d(TAG, "Download complete: $received bytes")
            }
            _state.value = State.Extracting(label)
            _status.value = "Extracting $label…"
            extractTarBz2(tmpFile, targetDirFile)
            Log.d(TAG, "Extraction complete")
        } finally {
            tmpFile.delete()
        }
    }

    private fun extractTarBz2(archive: File, targetDir: File) {
        FileInputStream(archive).use { fis ->
            BufferedInputStream(fis).use { bis ->
                BZip2CompressorInputStream(bis).use { bzis ->
                    TarArchiveInputStream(bzis).use { tis ->
                        var entry: ArchiveEntry? = tis.nextEntry
                        while (entry != null) {
                            val outFile = File(targetDir, entry.name)
                            if (entry.isDirectory) {
                                outFile.mkdirs()
                            } else {
                                outFile.parentFile?.mkdirs()
                                FileOutputStream(outFile).use { out -> tis.copyTo(out) }
                            }
                            entry = tis.nextEntry
                        }
                    }
                }
            }
        }
    }

    private fun loadModel(): Boolean {
        return try {
            val onnxFile = File(modelDir, "en_US-amy-medium.onnx")
            val tokensFile = File(modelDir, "tokens.txt")
            val espeakDir = File(modelDir, "espeak-ng-data")
            if (!onnxFile.exists()) error("Model file missing: ${onnxFile.absolutePath}")
            if (!tokensFile.exists()) error("Tokens file missing: ${tokensFile.absolutePath}")
            if (!espeakDir.exists()) error("espeak-ng-data missing: ${espeakDir.absolutePath}")
            Log.d(TAG, "Loading model from ${modelDir.absolutePath}")
            Log.d(TAG, "  onnx: ${onnxFile.length()} bytes")
            Log.d(TAG, "  tokens: ${tokensFile.length()} bytes")
            Log.d(TAG, "  espeak-ng-data: ${espeakDir.listFiles()?.size} files")

            val config = com.k2fsa.sherpa.onnx.OfflineTtsConfig(
                model = com.k2fsa.sherpa.onnx.OfflineTtsModelConfig(
                    vits = com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig(
                        model = onnxFile.absolutePath,
                        tokens = tokensFile.absolutePath,
                        dataDir = espeakDir.absolutePath,
                    ),
                    numThreads = 2,
                ),
            )
            tts = com.k2fsa.sherpa.onnx.OfflineTts(config = config)
            _state.value = State.Ready
            _status.value = "Piper TTS ready"
            _progress.value = 1f
            Log.d(TAG, "Piper TTS loaded successfully")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load TTS model", e)
            _state.value = State.Error(e.message ?: "Model load failed")
            _status.value = "Load failed: ${e.message}"
            false
        }
    }

    fun speak(text: String, speed: Float = 1.0f) {
        val engine = tts ?: return
        if (text.isBlank()) return
        stop()

        Thread {
            try {
                val audio = engine.generate(text, sid = 0, speed = speed)
                val pcmData = ShortArray(audio.samples.size) { i ->
                    (audio.samples[i] * Short.MAX_VALUE).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
                }
                playPcm(pcmData, audio.sampleRate)
            } catch (e: Exception) {
                Log.e(TAG, "TTS generation failed", e)
            }
        }.start()
    }

    private fun playPcm(pcmData: ShortArray, sampleRate: Int) {
        val bufferSize = maxOf(
            AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT),
            pcmData.size * 2
        )

        audioTrack = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(bufferSize)
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()

        audioTrack?.write(pcmData, 0, pcmData.size)
        audioTrack?.play()
    }

    fun stop() {
        try {
            audioTrack?.stop()
        } catch (_: Exception) {}
        audioTrack?.release()
        audioTrack = null
    }

    fun shutdown() {
        stop()
        tts?.release()
        tts = null
        _state.value = State.NotReady
        _status.value = ""
    }
}
