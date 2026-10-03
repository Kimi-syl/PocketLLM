package com.pocketllm.voice

import android.app.assist.AssistStructure
import android.content.Intent
import android.os.Bundle
import android.service.voice.VoiceInteractionService
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import android.util.Log

/**
 * Dynamic-assistant role plumbing.
 *
 * Being the system assistant is what unlocks AssistStructure: the platform
 * hands the assistant a semantic snapshot of whatever is on screen. We hold the
 * role only to capture that context — the UI automation still goes through the
 * accessibility service.
 */
class PocketAssistService : VoiceInteractionService() {
    override fun onReady() {
        super.onReady()
        AssistContextHolder.serviceReady = true
        Log.i(TAG, "assistant service ready")
    }

    override fun onCreate() {
        super.onCreate()
        AssistContextHolder.serviceReady = true
    }

    companion object {
        private const val TAG = "PocketAssist"
    }
}

class PocketAssistSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession =
        PocketAssistSession(this)
}

class PocketAssistSession(context: android.content.Context) : VoiceInteractionSession(context) {

    @Suppress("DEPRECATION")
    override fun onHandleAssist(
        data: Bundle?,
        structure: AssistStructure?,
        content: android.app.assist.AssistContent?,
    ) {
        super.onHandleAssist(data, structure, content)
        capture(structure)
    }

    private fun capture(structure: AssistStructure?) {
        val text = AssistStructureParser.parse(structure)
        val pkg = structure?.activityComponent?.packageName
        AssistContextHolder.update(pkg, text)
        Log.i(TAG, "captured assist context: ${text.length} chars from $pkg")
    }

    companion object {
        private const val TAG = "PocketAssist"
    }
}

/** Minimal recognition service — the role requires one to be declared. */
class PocketAssistRecognitionService : android.speech.RecognitionService() {
    override fun onStartListening(recognizerIntent: Intent, listener: Callback) = Unit
    override fun onCancel(listener: Callback) = Unit
    override fun onStopListening(listener: Callback) = Unit
    override fun onCheckRecognitionSupport(
        recognizerIntent: Intent,
        supportCallback: SupportCallback,
    ) = supportCallback.onSupportResult(
        android.speech.RecognitionSupport.Builder().build()
    )
}
