package com.pocketllm.util

import android.content.Context
import android.media.MediaPlayer
import com.pocketllm.server.ServerLog
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Zero-shot voice cloning through Fish Audio's TTS API.
 *
 * The reference voice is a model id owned by the account (a cloned or public
 * voice on fish.audio); generation returns audio bytes that are cached to a
 * temp file and played in-process. This is the "cloud" half of the voice
 * cloning feature — a purely local GPT-SoVITS port would need the ONNX model
 * plus a runtime, which is why the API path is the one wired up here.
 *
 * All failures are returned as [Result]; a missing key or voice id is reported
 * to the caller rather than silently doing nothing (the settings row shows the
 * real state).
 */
class FishAudioTts(private val context: Context) {

    private var player: MediaPlayer? = null
    private var currentFile: File? = null

    /** True while audio is playing. */
    @Volatile
    var isSpeaking: Boolean = false
        private set

    suspend fun speak(
        text: String,
        apiKey: String,
        referenceId: String,
        model: String = "s1",
    ): Result<Unit> = withContext(Dispatchers.IO) {
        if (text.isBlank()) return@withContext Result.success(Unit)
        if (apiKey.isBlank()) {
            return@withContext Result.failure(IllegalStateException("Fish Audio API key is not set"))
        }
        if (referenceId.isBlank()) {
            return@withContext Result.failure(IllegalStateException("Fish Audio voice id is not set"))
        }

        val payload = JSONObject()
            .put("text", text)
            .put("reference_id", referenceId)
            .put("format", "mp3")
            .put("model", model.ifBlank { "s1" })
            .toString()

        var conn: HttpURLConnection? = null
        try {
            conn = (URL(ENDPOINT).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 20_000
                readTimeout = 120_000
                doOutput = true
                setRequestProperty("Authorization", "Bearer $apiKey")
                setRequestProperty("Content-Type", "application/json")
            }
            conn.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }

            val code = conn.responseCode
            if (code !in 200..299) {
                val err = conn.errorStream?.bufferedReader()?.readText().orEmpty()
                ServerLog.log("FishAudio TTS failed: HTTP $code ${err.take(200)}")
                return@withContext Result.failure(IOException("Fish Audio HTTP $code"))
            }

            val out = File(context.cacheDir, "fish_tts.mp3")
            conn.inputStream.use { input -> out.outputStream().use { input.copyTo(it) } }
            play(out)
            Result.success(Unit)
        } catch (t: Throwable) {
            ServerLog.error("FishAudioTts", t)
            Result.failure(t)
        } finally {
            conn?.disconnect()
        }
    }

    private suspend fun play(file: File) = withContext(Dispatchers.Main) {
        stop()
        val mp = MediaPlayer()
        mp.setDataSource(file.absolutePath)
        mp.setOnCompletionListener {
            it.release()
            if (player === it) player = null
            isSpeaking = false
        }
        mp.setOnErrorListener { p, _, _ ->
            p.release()
            if (player === p) player = null
            isSpeaking = false
            true
        }
        mp.prepare()
        mp.start()
        player = mp
        currentFile = file
        isSpeaking = true
    }

    fun stop() {
        player?.runCatching { if (isPlaying) stop() }
        player?.runCatching { release() }
        player = null
        isSpeaking = false
    }

    private companion object {
        const val ENDPOINT = "https://api.fish.audio/v1/tts"
    }
}
