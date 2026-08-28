package com.pocketllm.util

import android.content.Context
import android.speech.tts.TextToSpeech
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.Locale

class TtsManager(context: Context) {

    private var systemTts: TextToSpeech? = null
    val piperTts = SherpaTtsEngine(context)

    private val _ready = MutableStateFlow(false)
    val ready: StateFlow<Boolean> = _ready

    private val _speaking = MutableStateFlow(false)
    val speaking: StateFlow<Boolean> = _speaking

    private val _piperReady = MutableStateFlow(false)
    val piperReady: StateFlow<Boolean> = _piperReady

    val piperState: StateFlow<SherpaTtsEngine.State> = piperTts.state
    val piperProgress: StateFlow<Float> = piperTts.progress
    val piperStatus: StateFlow<String> = piperTts.status

    private val _activeEngine = MutableStateFlow("system")
    val activeEngine: StateFlow<String> = _activeEngine

    init {
        systemTts = TextToSpeech(context.applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                systemTts?.language = Locale.getDefault()
                _ready.value = true
            }
        }
        systemTts?.setOnUtteranceProgressListener(object : android.speech.tts.UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) { _speaking.value = true }
            override fun onDone(utteranceId: String?) { _speaking.value = false }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) { _speaking.value = false }
        })
    }

    fun setEngine(engine: String) {
        _activeEngine.value = engine
    }

    suspend fun ensurePiper(): Boolean {
        val result = piperTts.ensureModel()
        _piperReady.value = result
        return result
    }

    suspend fun retryPiper(): Boolean {
        val result = piperTts.retry()
        _piperReady.value = result
        return result
    }

    fun speak(text: String) {
        if (text.isBlank()) return
        stop()

        val cleanText = MarkdownCleaner.clean(text)
        if (cleanText.isBlank()) return

        when (_activeEngine.value) {
            "piper" -> {
                if (piperTts.isReady) {
                    piperTts.speak(cleanText)
                    _speaking.value = true
                } else {
                    speakSystem(cleanText)
                }
            }
            else -> speakSystem(cleanText)
        }
    }

    private fun speakSystem(text: String) {
        val engine = systemTts ?: return
        if (!_ready.value) return
        engine.stop()
        engine.speak(text.take(4000), TextToSpeech.QUEUE_FLUSH, null, "pocketllm-${System.currentTimeMillis()}")
    }

    fun stop() {
        systemTts?.stop()
        piperTts.stop()
        _speaking.value = false
    }

    fun shutdown() {
        stop()
        systemTts?.shutdown()
        systemTts = null
        piperTts.shutdown()
        _ready.value = false
        _piperReady.value = false
    }
}
