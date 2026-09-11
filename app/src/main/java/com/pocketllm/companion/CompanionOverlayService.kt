package com.pocketllm.companion

import android.Manifest
import android.animation.ValueAnimator
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.RemoteInput
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.core.content.ContextCompat
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.pocketllm.MainActivity
import com.pocketllm.R
import com.pocketllm.agent.CalculateTool
import com.pocketllm.agent.SearchConfig
import com.pocketllm.agent.ToolResult
import com.pocketllm.agent.WebSearchTool
import com.pocketllm.llm.LlamaEngine
import com.pocketllm.server.ServerLog
import com.pocketllm.settings.AppSettings
import com.pocketllm.settings.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive

/**
 * Floating companion bubble, hosted in an overlay window owned by a foreground
 * service. Living in a service (rather than the Activity) is what lets the
 * bubble outlive the app UI; the ongoing notification is what stops Android
 * from killing the process — which also keeps the loaded GGUF model resident,
 * so the companion can answer locally with no reload.
 */
class CompanionOverlayService : Service() {

    private lateinit var windowManager: WindowManager
    private lateinit var brain: CompanionBrain
    private lateinit var store: CompanionStore
    private lateinit var memory: CompanionMemory
    private var rootView: ComposeView? = null
    private var owner: OverlayLifecycleOwner? = null
    private var tts: TextToSpeech? = null

    private val ui = CompanionUiState()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val history = ArrayDeque<Pair<String, String>>()

    private var bubbleX = 0
    private var bubbleY = 0
    /** When the bubble was last dragged, so a settings refresh cannot fight it. */
    private var lastDragAt = 0L
    /** Epoch millis of the last user interaction, for the proactive check-in. */
    private var lastInteractionAt = 0L
    /** Turns since the last memory-extraction pass. */
    private var turnsSinceExtraction = 0
    /** Page summaries are not conversation, so they are excluded from memory. */
    private var lastTurnWasPageTask = false
    /** In-flight background memory pass, so a real reply can pre-empt it. */
    private var extractJob: Job? = null
    /** Timer for proactive check-ins. */
    private var checkInJob: Job? = null
    /** Countdown that dims the bubble once it has been left alone. */
    private var idleJob: Job? = null
    /** Active dictation session, if any. */
    private var recognizer: SpeechRecognizer? = null
    /**
     * The page she just read, kept so follow-up questions about it work.
     * Cleared on a new chat, replaced when another page is captured, and
     * dropped after a few turns so it cannot linger indefinitely.
     */
    private var heldPage: CapturedPage? = null
    private var heldPageTurns = 0
    /** Subject awaiting a time, after "remind me to X" with no time given. */
    private var pendingReminderSubject: String? = null
    /** Reminders she is holding, so they can be listed and cancelled. */
    private lateinit var reminders: CompanionReminderStore
    /** Explicit mood check-ins, used to be a little more careful with them. */
    private lateinit var mood: MoodJournalStore
    /**
     * Set when the user asks her to stop, and cleared at the start of each
     * turn. Used as the guard for the stop safety net below.
     */
    private var stopRequested = false

    private val density: Float get() = resources.displayMetrics.density

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        // All stores first: the brain closes over them, and a lateinit read
        // before assignment would be a crash waiting for a code reorder.
        memory = CompanionMemory(applicationContext)
        store = CompanionStore(applicationContext)
        reminders = CompanionReminderStore(applicationContext)
        mood = MoodJournalStore(applicationContext)
        brain = CompanionBrain(
            settings = { settings() },
            memory = { memory },
            mood = { mood },
        )
        // Forget reminders that fired long ago so the list stays meaningful.
        scope.launch(Dispatchers.IO) { runCatching { reminders.prune() } }
        restoreTranscript()
        ui.status = brain.backendLabel()
        ui.onExpand = { expand() }
        ui.onBubbleGesture = { gesture -> handleBubbleGesture(gesture) }
        ui.onWake = { wakeBubble() }
        ui.onCollapse = { collapse() }
        ui.onSend = { text -> submit(text) }
        ui.onSummarize = { summarizePage() }
        ui.onExplain = { explainPage() }
        ui.onTranslate = { translatePage() }
        ui.onCheer = { cheerUp() }
        ui.onLookUp = { lookUp() }
        ui.onMoodCheckIn = { beginMoodCheckIn() }
        ui.onMoodPicked = { score -> recordMood(score) }
        ui.onNewChat = { resetConversation() }
        ui.onMicToggle = { toggleListening() }
        ui.onStop = { stopGeneration() }
        // The breathing exercise runs in the panel; the service only supplies
        // the voice, so the cues stay in step with the animation.
        ui.onBreathCue = { cue -> speak(cue) }
        ui.onBreathDone = { ui.breathing = false }
        ui.onDrag = { dx, dy -> moveBy(dx, dy) }
        ui.onDragEnd = { persistPosition() }

        val s = settings()
        applyAppearance(s)
        bubbleX = if (s.companionBubbleX >= 0) s.companionBubbleX else defaultBubbleX()
        bubbleY = s.companionBubbleY

        tts = TextToSpeech(applicationContext) { status ->
            // The voice is only usable once initialised, so the saved rate and
            // pitch are applied from the callback rather than here.
            if (status == TextToSpeech.SUCCESS) applySpeechSettings(settings())
        }

        startInForeground()
        if (!canDrawOverlays()) {
            ServerLog.log("companion: overlay permission missing; stopping")
            stopSelf()
            return
        }
        attachOverlay()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_EXPAND -> expand()
            ACTION_REPLY -> {
                remoteReplyText(intent)?.let { handleNotificationReply(it) }
            }
        }
        refreshFromSettings()
        return START_STICKY
    }

    /**
     * Re-read settings and apply them to the live overlay. Public so the app's
     * ViewModel can push changes straight in: the service and the UI share a
     * process, so a direct call beats restarting the service (which is what a
     * slider drag would otherwise do on every tick).
     */
    fun refreshFromSettings() {
        if (!::brain.isInitialized) return
        applyAppearance(settings())
        applySpeechSettings(settings())
        ui.status = brain.backendLabel()
        restartCheckInLoop()
    }

    /**
     * Push the user's chosen rate and pitch onto the system voice.
     *
     * Clamped rather than trusted: a stored value outside TextToSpeech's range
     * would be silently ignored, which looks like the setting doing nothing.
     */
    private fun applySpeechSettings(s: AppSettings) {
        val engine = tts ?: return
        runCatching { engine.setSpeechRate(s.companionSpeechRate.coerceIn(0.5f, 2f)) }
        runCatching { engine.setPitch(s.companionSpeechPitch.coerceIn(0.5f, 2f)) }
    }

    // --- Proactive check-ins ------------------------------------------------

    /**
     * She reaches out on her own.
     *
     * Scheduled inside the service rather than with WorkManager or an alarm:
     * the service is already alive whenever the user has the bubble switched
     * on (and deliberately not when they have switched it off), so a plain
     * timer is both simpler and a more honest reflection of the toggle.
     */
    private fun restartCheckInLoop() {
        checkInJob?.cancel()
        checkInJob = null
        if (!settings().companionEnabled || !settings().companionProactiveNudges) return
        checkInJob = scope.launch {
            while (isActive) {
                val minutes = settings().companionNudgeIntervalMinutes.coerceIn(15, 24 * 60)
                delay(minutes * 60_000L)
                runCatching { maybeSendCheckIn() }
            }
        }
    }

    private fun maybeSendCheckIn() {
        val s = settings()
        if (!s.companionEnabled || !s.companionProactiveNudges) return
        // Don't interrupt: they are looking at her, or mid-conversation.
        if (ui.expanded || ui.busy) return
        if (CompanionNudges.isQuietNow(s.companionQuietStartHour, s.companionQuietEndHour)) return
        val idleFor = System.currentTimeMillis() - lastInteractionAt
        if (idleFor < NUDGE_MIN_IDLE_MS) return

        val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        val line = CompanionNudges.next(hour)
        postCheckInNotification(line)
        ServerLog.log("companion: check-in sent")
    }

    private fun postCheckInNotification(line: String) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(NUDGE_CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(
                    NUDGE_CHANNEL_ID,
                    "Check-ins",
                    NotificationManager.IMPORTANCE_DEFAULT,
                ).apply { description = "When your companion reaches out first" }
            )
        }
        val open = PendingIntent.getActivity(
            this, 2, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val expand = PendingIntent.getService(
            this, 3,
            Intent(this, CompanionOverlayService::class.java).setAction(ACTION_EXPAND),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = Notification.Builder(this, NUDGE_CHANNEL_ID)
            .setContentTitle(settings().companionName.ifBlank { "Momo" })
            .setContentText(line)
            .setStyle(Notification.BigTextStyle().bigText(line))
            .setSmallIcon(R.drawable.ic_companion)
            .setAutoCancel(true)
            .setContentIntent(if (canDrawOverlays()) expand else open)
            .addAction(replyAction())
            .build()
        // Distinct id: never overwrite the ongoing bubble notification.
        runCatching { manager.notify(NUDGE_NOTIFICATION_ID, notification) }
    }

    // --- Replying from a notification ---------------------------------------

    /**
     * The notification's inline reply box.
     *
     * MUTABLE on purpose: the system writes the typed text into the intent
     * before starting the service, and an immutable intent refuses that, so the
     * reply silently disappears. This is the documented exception to the
     * immutable-PendingIntent rule.
     */
    private fun replyAction(): Notification.Action {
        val reply = PendingIntent.getService(
            this, 4,
            Intent(this, CompanionOverlayService::class.java).setAction(ACTION_REPLY),
            PendingIntent.FLAG_MUTABLE,
        )
        val input = RemoteInput.Builder(REPLY_KEY)
            .setLabel("Reply…")
            .build()
        return Notification.Action.Builder(
            Icon.createWithResource(this, R.drawable.ic_companion),
            "Reply",
            reply,
        ).addRemoteInput(input).build()
    }

    /**
     * Text typed into the check-in notification's reply box, or null.
     *
     * A remote reply needs a *mutable* PendingIntent — the system, not we, fills
     * in the results. That is the documented exception to the immutable rule,
     * and using an immutable intent here silently produces nothing.
     */
    private fun remoteReplyText(intent: Intent): String? =
        RemoteInput.getResultsFromIntent(intent)
            ?.getCharSequence(REPLY_KEY)
            ?.toString()
            ?.trim()
            ?.takeIf { it.isNotEmpty() }

    /**
     * Answer her from the notification itself.
     *
     * The panel is opened first so the reply is visible: answering into a
     * conversation that happens entirely off-screen would be worse than not
     * offering the box at all. The greeting is suppressed for the same reason —
     * a "how's your day going?" would otherwise land above the answer to it.
     */
    private fun handleNotificationReply(text: String) {
        if (!::brain.isInitialized) return
        runCatching {
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .cancel(NUDGE_NOTIFICATION_ID)
        }
        if (!ui.expanded) expand(greet = false)
        submit(text)
    }

    // --- Voice input --------------------------------------------------------

    /**
     * Dictation, so she can be talked to rather than typed at.
     *
     * The microphone belongs to the system recognition service, not to us —
     * we only ask it to listen and hand back text. That keeps recording out of
     * our process entirely; RECORD_AUDIO is what lets the recognizer accept
     * our request at all.
     */
    private fun toggleListening() {
        if (ui.listening) {
            stopListening()
            ui.status = brain.backendLabel()
        } else {
            startListening()
        }
    }

    private fun startListening() {
        if (!hasMicPermission()) {
            ui.status = "Microphone permission needed — turn it on in Settings"
            return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            ui.status = "No speech recognition available on this device"
            return
        }
        // A previous recognizer must be torn down before a new one is created;
        // the platform allows only one listening session per app.
        releaseRecognizer()
        val recognizer = SpeechRecognizer.createSpeechRecognizer(this)
        recognizer.setRecognitionListener(recognitionListener)
        this.recognizer = recognizer

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
            )
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }
        ui.listening = true
        ui.status = "listening…"
        runCatching { recognizer.startListening(intent) }.onFailure {
            ui.listening = false
            ui.status = "Couldn't start listening"
            releaseRecognizer()
        }
    }

    private fun stopListening() {
        runCatching { recognizer?.stopListening() }
        releaseRecognizer()
        ui.listening = false
    }

    private fun releaseRecognizer() {
        runCatching { recognizer?.destroy() }
        recognizer = null
    }

    private fun hasMicPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    /** Errors are surfaced in the panel rather than thrown — dictation failing
     *  should never interrupt the conversation. */
    private fun recognitionErrorText(error: Int): String = when (error) {
        SpeechRecognizer.ERROR_NO_MATCH,
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Didn't catch that"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission needed"
        SpeechRecognizer.ERROR_NETWORK,
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Speech needs a network connection"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Recognizer is busy — try again"
        SpeechRecognizer.ERROR_AUDIO -> "Microphone error"
        else -> "Couldn't hear you"
    }

    private val recognitionListener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            ui.status = "listening…"
        }

        override fun onBeginningOfSpeech() = Unit
        override fun onRmsChanged(rmsdB: Float) = Unit
        override fun onBufferReceived(buffer: ByteArray?) = Unit

        override fun onEndOfSpeech() {
            ui.status = "…"
        }

        override fun onError(error: Int) {
            ui.listening = false
            ui.status = recognitionErrorText(error)
            releaseRecognizer()
        }

        override fun onResults(results: Bundle?) {
            val text = results
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()
                .orEmpty()
                .trim()
            ui.listening = false
            ui.status = brain.backendLabel()
            releaseRecognizer()
            // Speaking is a complete turn, so send it rather than leaving it in
            // the box for another tap.
            if (text.isNotEmpty()) submit(text)
        }

        override fun onPartialResults(partialResults: Bundle?) {
            val text = partialResults
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()
            if (!text.isNullOrBlank()) ui.input = text
        }

        override fun onEvent(eventType: Int, params: Bundle?) = Unit
    }

    // --- Looking things up --------------------------------------------------

    /**
     * Answer a question with the web behind it.
     *
     * Deliberately search-then-answer rather than a full tool-calling loop:
     * the search runs first and completes quickly, the grounding is injected
     * for this turn only, and it works identically on the local model and on
     * the cloud entry. A multi-turn tool loop would be slower on a phone and
     * only available locally.
     */
    private fun lookUp() {
        if (ui.busy) return
        // Fall back to the last thing they asked, so "she didn't know" can be
        // followed by a single tap without retyping.
        val query = ui.input.trim().ifBlank {
            ui.messages.lastOrNull { it.fromUser }?.text.orEmpty().trim()
        }
        if (query.isBlank()) {
            ui.status = "Type a question first"
            return
        }
        ui.input = ""
        stopRequested = false
        ui.messages.add(CompanionMsg(fromUser = true, text = query))
        ui.messages.add(CompanionMsg(fromUser = false, text = ""))
        ui.busy = true
        ui.status = "searching…"
        lastInteractionAt = System.currentTimeMillis()

        scope.launch {
            val grounding = runCatching { searchGrounding(query) }.getOrElse { error ->
                ServerLog.log("companion: search failed — ${error.message}")
                null
            }
            if (grounding == null) {
                finishTurn(
                    query,
                    "I couldn't reach the web just now. Ask me again in a bit, " +
                        "or tell me what you already know and we'll work from that.",
                )
                return@launch
            }
            ui.status = "reading…"
            val result = brain.respond(history.toList(), query, grounding) { delta ->
                val index = ui.messages.lastIndex
                if (index >= 0) {
                    ui.messages[index] = ui.messages[index].copy(text = ui.messages[index].text + delta)
                }
            }
            result.onSuccess { reply ->
                val index = ui.messages.lastIndex
                if (index >= 0) ui.messages[index] = CompanionMsg(false, reply)
                history.addLast("user" to query)
                history.addLast("assistant" to reply)
                while (history.size > MAX_HISTORY) history.removeFirst()
                speak(reply)
            }.onFailure { error ->
                val index = ui.messages.lastIndex
                if (index >= 0) {
                    ui.messages[index] = CompanionMsg(
                        false,
                        error.message ?: "Something went wrong.",
                    )
                }
            }
            ui.busy = false
            ui.status = brain.backendLabel()
            saveChat()
        }
    }

    /** Replace the in-flight reply with a fixed line and settle the turn. */
    private fun finishTurn(userText: String, reply: String) {
        val index = ui.messages.lastIndex
        if (index >= 0) ui.messages[index] = CompanionMsg(false, reply)
        history.addLast("user" to userText)
        history.addLast("assistant" to reply)
        while (history.size > MAX_HISTORY) history.removeFirst()
        ui.busy = false
        ui.status = brain.backendLabel()
        saveChat()
    }

    /**
     * Stop an in-flight reply.
     *
     * The local model honours the request and returns its partial answer, which
     * is left on screen — a half-finished reply the user chose to cut short is
     * still worth keeping. A cloud stream has no such control, so a short
     * safety net settles the turn regardless; otherwise a stopped cloud request
     * would leave the panel stuck on "thinking" forever.
     */
    private fun stopGeneration() {
        if (!ui.busy) return
        stopRequested = true
        ui.status = "stopping…"
        brain.stop()
        scope.launch {
            delay(STOP_SETTLE_MS)
            if (stopRequested && ui.busy) {
                ui.busy = false
                ui.status = brain.backendLabel()
                saveChat()
            }
        }
    }

    /** Run a web search and format the hits as prompt grounding. */
    private suspend fun searchGrounding(query: String): String? {
        val s = settings()
        val tool = WebSearchTool {
            SearchConfig(
                engine = s.searchEngine,
                braveKey = s.braveKey,
                tavilyKey = s.tavilyKey,
                bingKey = s.bingKey,
                firecrawlKey = s.firecrawlKey,
            )
        }
        val result = tool.execute(mapOf("query" to JsonPrimitive(query)))
        val hits = (result as? ToolResult.Search)?.results.orEmpty()
        if (hits.isEmpty()) return null
        return buildString {
            appendLine("Web results for \"$query\":")
            hits.take(LOOKUP_MAX_RESULTS).forEachIndexed { i, hit ->
                appendLine("${i + 1}. ${hit.title}")
                appendLine("   ${hit.url}")
                if (hit.snippet.isNotBlank()) appendLine("   ${hit.snippet}")
            }
            appendLine()
            appendLine(
                "Answer their question using these results. They may be incomplete " +
                    "or wrong — if they don't actually contain the answer, say so plainly " +
                    "instead of guessing. Mention the source only if it matters."
            )
        }
    }

    override fun onDestroy() {
        runCatching { rootView?.let { windowManager.removeView(it) } }
        owner?.stop()
        rootView = null
        owner = null
        extractJob?.cancel()
        extractJob = null
        checkInJob?.cancel()
        checkInJob = null
        idleJob?.cancel()
        idleJob = null
        // Never leave the microphone open behind us.
        stopListening()
        tts?.shutdown()
        tts = null
        scope.cancel()
        if (instance === this) instance = null
        super.onDestroy()
    }

    // --- Bubble idle state and gestures -------------------------------------

    /**
     * Bring the bubble back to full strength and restart the idle countdown.
     *
     * Called on every touch, so the bubble is only ever dimmed while genuinely
     * untouched — the dimming is a resting state, never something the user has
     * to aim at.
     */
    private fun wakeBubble() {
        ui.bubbleAwake = true
        idleJob?.cancel()
        idleJob = null
        // Nothing to schedule when dimming is switched off.
        if (ui.bubbleIdleFactor >= 0.99f) return
        idleJob = scope.launch {
            delay(IDLE_DIM_MS)
            // Never while the panel is open: she is being looked at.
            if (!ui.expanded) ui.bubbleAwake = false
        }
    }

    /**
     * Run whatever the user chose for a bubble gesture.
     *
     * Actions that need a page or a question check for one first, so a gesture
     * configured for something unavailable says so instead of doing nothing.
     */
    private fun handleBubbleGesture(gesture: BubbleGesture) {
        when (gesture) {
            BubbleGesture.Off -> Unit
            BubbleGesture.Expand -> expand()
            BubbleGesture.Summarize -> summarizePage()
            BubbleGesture.Explain -> explainPage()
            BubbleGesture.Cheer -> cheerUp()
            BubbleGesture.LookUp -> lookUp()
            BubbleGesture.NewChat -> resetConversation()
            BubbleGesture.Hide -> {
                // "Hide" leaves the bubble off until it is switched back on, so
                // the gesture has to actually turn the setting off — otherwise
                // it would reappear the next time the app starts.
                scope.launch {
                    runCatching { settingsRepo().update { it.copy(companionEnabled = false) } }
                    CompanionOverlayService.stop(this@CompanionOverlayService)
                }
            }
        }
    }

    // --- Overlay window -----------------------------------------------------

    /**
     * Push identity/appearance settings into the UI state and, when the bubble
     * is visible, resize the window to match. Called on start and whenever the
     * user changes something in Settings while the service is alive.
     */
    private fun applyAppearance(s: AppSettings) {
        ui.name = s.companionName.ifBlank { "Momo" }
        ui.glyph = s.companionGlyph.ifBlank { "\uD83D\uDC31" }
        ui.bubbleSizeDp = s.companionBubbleSize.coerceIn(36, 120)
        ui.bubbleAlpha = (s.companionBubbleAlpha.coerceIn(20, 100)) / 100f
        // 0 is the "follow the theme" sentinel, so only a real tint overrides.
        ui.bubbleColor = if (s.companionBubbleColor != 0L) Color(s.companionBubbleColor) else null
        ui.bubbleShape = BubbleShape.byId(s.companionBubbleShape)
        ui.bubbleBorderWidthDp = s.companionBubbleBorderWidth.coerceIn(0, 8)
        ui.bubbleBorderColor = if (s.companionBubbleBorderColor != 0L) Color(s.companionBubbleBorderColor) else null
        ui.glyphScale = s.companionGlyphScale.coerceIn(20, 80)
        ui.bubbleIdleFactor = (s.companionBubbleIdleAlpha.coerceIn(20, 100)) / 100f
        ui.doubleTapAction = BubbleGesture.byId(s.companionBubbleDoubleTap)
        ui.longPressAction = BubbleGesture.byId(s.companionBubbleLongPress)
        ui.panelFontScale = (s.companionPanelFontScale.coerceIn(80, 140)) / 100f
        ui.voiceInputEnabled = s.companionVoiceInput

        if (!ui.expanded) {
            syncBubblePosition(s)
            val view = rootView ?: return
            runCatching { windowManager.updateViewLayout(view, layoutParams(expanded = false)) }
        }
    }

    /**
     * Adopt the stored position, unless the user just moved her by hand.
     *
     * Settings writes are asynchronous, so re-reading the position on every
     * settings change can pick up a value from before a drag that has not been
     * saved yet — which would make the bubble snap back under the finger. The
     * short window after a drag is skipped for exactly that reason.
     */
    private fun syncBubblePosition(s: AppSettings) {
        if (System.currentTimeMillis() - lastDragAt < DRAG_SETTLE_MS) return
        bubbleX = if (s.companionBubbleX >= 0) s.companionBubbleX else defaultBubbleX()
        bubbleY = s.companionBubbleY.coerceAtLeast(0)
    }

    private fun attachOverlay() {
        val lifecycleOwner = OverlayLifecycleOwner().also { it.start() }
        val view = ComposeView(this).apply {
            setViewTreeLifecycleOwner(lifecycleOwner)
            setViewTreeViewModelStoreOwner(lifecycleOwner)
            setViewTreeSavedStateRegistryOwner(lifecycleOwner)
            setContent {
                CompanionRoot(state = ui, themeMode = settings().themeMode)
            }
        }
        owner = lifecycleOwner
        rootView = view
        runCatching { windowManager.addView(view, layoutParams(expanded = false)) }
            .onFailure {
                ServerLog.log("companion: addView failed: ${it.message}")
                stopSelf()
            }
            .onSuccess {
                // Start the idle countdown from the moment she appears.
                wakeBubble()
            }
    }

    private fun layoutParams(expanded: Boolean): WindowManager.LayoutParams {
        val metrics = resources.displayMetrics
        val width: Int
        val height: Int
        if (expanded) {
            width = minOf(metrics.widthPixels - dp(24), dp(360))
            height = minOf(dp(470), metrics.heightPixels - dp(160))
        } else {
            // Sized from the user's bubble-size setting, not a fixed 64dp.
            val side = dp(ui.bubbleSizeDp)
            width = side
            height = side
        }
        return WindowManager.LayoutParams(
            width,
            height,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            if (expanded) 0 else WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = clampX(bubbleX, width)
            y = clampY(bubbleY, height)
            if (expanded) {
                softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
            }
        }
    }

    private fun expand(greet: Boolean = true) {
        if (ui.expanded) return
        ui.expanded = true
        ui.status = brain.backendLabel()
        val view = rootView ?: return
        val metrics = resources.displayMetrics
        val params = layoutParams(expanded = true).apply {
            // Center the panel, but never write this back to bubbleX/bubbleY:
            // the panel is wider than the bubble, so its clamped x is a
            // different coordinate. Overwriting the bubble's position here is
            // what used to drag the collapsed bubble to the middle of the screen.
            x = ((metrics.widthPixels - width) / 2).coerceAtLeast(0)
            y = dp(48)
        }
        if (greet) maybeGreet()
        runCatching { windowManager.updateViewLayout(view, params) }
    }

    private fun collapse() {
        if (!ui.expanded) return
        // Hiding the panel should also close the microphone; leaving it
        // recording behind a hidden panel would be indefensible.
        if (ui.listening) {
            stopListening()
            ui.status = brain.backendLabel()
        }
        // Same reasoning for the breathing exercise: its cues would otherwise
        // keep speaking with nothing on screen to follow.
        ui.breathing = false
        // The panel was being looked at, so the bubble starts awake and its
        // idle countdown begins from the moment it is revealed again.
        wakeBubble()
        ui.expanded = false
        val view = rootView ?: return
        // bubbleX/bubbleY were left untouched while expanded, so this restores
        // the bubble exactly where the user parked it.
        val params = layoutParams(expanded = false)
        runCatching { windowManager.updateViewLayout(view, params) }
    }

    private fun moveBy(dx: Float, dy: Float) {
        // The window can only be moved while collapsed; dragging the panel by
        // its body would fight the message list.
        if (ui.expanded) return
        val view = rootView ?: return
        val params = (view.layoutParams as? WindowManager.LayoutParams) ?: return
        params.x = clampX(params.x + dx.toInt(), params.width)
        params.y = clampY(params.y + dy.toInt(), params.height)
        bubbleX = params.x
        bubbleY = params.y
        lastDragAt = System.currentTimeMillis()
        runCatching { windowManager.updateViewLayout(view, params) }
    }

    private fun clampX(x: Int, width: Int): Int =
        x.coerceIn(0, maxOf(0, resources.displayMetrics.widthPixels - width))

    private fun clampY(y: Int, height: Int): Int =
        y.coerceIn(0, maxOf(0, resources.displayMetrics.heightPixels - height))

    private fun defaultBubbleX(): Int =
        (resources.displayMetrics.widthPixels - dp(ui.bubbleSizeDp) - dp(12)).coerceAtLeast(0)

    private fun persistPosition() {
        // Only the collapsed bubble position is meaningful.
        if (ui.expanded) return
        if (settings().companionSnapToEdge) snapToNearestEdge()
        scope.launch {
            runCatching { settingsRepo().update { it.copy(companionBubbleX = bubbleX, companionBubbleY = bubbleY) } }
        }
    }

    /**
     * Slide the bubble to whichever side it was dropped nearest.
     *
     * Only the horizontal position moves: the height the user chose is theirs,
     * and adjusting it on their behalf would be taking a liberty. `bubbleX` is
     * set before animating so the persisted value is the resting place, not
     * wherever the finger happened to lift.
     */
    private fun snapToNearestEdge() {
        val view = rootView ?: return
        val params = (view.layoutParams as? WindowManager.LayoutParams) ?: return
        val metrics = resources.displayMetrics
        val target = if (params.x + params.width / 2 <= metrics.widthPixels / 2) {
            0
        } else {
            (metrics.widthPixels - params.width).coerceAtLeast(0)
        }
        bubbleX = target
        if (target == params.x) return
        ValueAnimator.ofInt(params.x, target).apply {
            duration = 180
            addUpdateListener { anim ->
                params.x = anim.animatedValue as Int
                runCatching { windowManager.updateViewLayout(view, params) }
            }
            start()
        }
    }

    // --- Conversation -------------------------------------------------------

    /** Rebuild the visible transcript and the model's short-term memory. */
    private fun restoreTranscript() {
        val turns = store.load()
        if (turns.isEmpty()) return
        ui.messages.addAll(turns.map { CompanionMsg(it.fromUser, it.text) })
        // Only the tail seeds the model; the file may hold far more than fits
        // a phone-sized context.
        for (turn in turns.takeLast(MAX_HISTORY)) {
            history.addLast((if (turn.fromUser) "user" else "assistant") to turn.text)
        }
        lastInteractionAt = System.currentTimeMillis()
    }

    private fun saveChat() {
        val turns = ui.messages.map { CompanionTurn(it.fromUser, it.text) }
        scope.launch(Dispatchers.IO) { store.save(turns) }
    }

    private fun resetConversation() {
        ui.messages.clear()
        history.clear()
        clearHeldPage()
        pendingReminderSubject = null
        scope.launch(Dispatchers.IO) { store.clear() }
        ui.messages.add(CompanionMsg(false, "Fresh start. What's on your mind?"))
        // Reset the clock too, otherwise the absence greeting would fire the
        // moment they reopen the panel.
        lastInteractionAt = System.currentTimeMillis()
    }

    /**
     * Reaches out first — the whole point of a companion rather than an
     * assistant. Only when the panel has been closed for a while, so it never
     * interrupts an active conversation.
     */
    private fun maybeGreet() {
        val now = System.currentTimeMillis()
        val quietFor = now - lastInteractionAt
        val isFirstOpen = ui.messages.isEmpty()
        if (!isFirstOpen && quietFor < PROACTIVE_QUIET_MS) return
        if (ui.busy) return
        val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        val line = when {
            isFirstOpen -> openerFor(hour)
            quietFor > LONG_ABSENCE_MS -> "It's been a while. I'm still here if you feel like talking."
            else -> return
        }
        ui.messages.add(CompanionMsg(fromUser = false, text = line))
        lastInteractionAt = now
        saveChat()
    }

    private fun openerFor(hour: Int): String = when {
        hour < 5 -> "You're up late. I'm here if you want some company."
        hour < 12 -> "Morning. How are you feeling today?"
        hour < 18 -> "Hey — how's your day going?"
        else -> "Evening. Want to talk about anything?"
    }

    private fun cheerUp() {
        submit(
            "I'm feeling a bit low right now. Please say something warm and steadying.",
            display = "Cheer me up",
        )
    }

    // --- Exactly-answerable requests ----------------------------------------

    /**
     * Maths and the current date/time, answered without the model.
     *
     * A model asked "what's 15% of 240" will often answer confidently and
     * wrongly; the same for today's date. These have exact answers, so they are
     * computed. It also means they work with no model loaded.
     *
     * @return true when the message was fully handled here.
     */
    private fun handleQuickToolsIfAny(text: String, display: String): Boolean {
        // Date/time is pure formatting, so it can be answered immediately.
        CompanionQuickTools.dateTimeAnswer(text)?.let { answer ->
            replyWithoutModel(display, answer)
            return true
        }
        val expression = CompanionQuickTools.mathExpression(text) ?: return false
        calculateAndReply(expression, display)
        return true
    }

    /** Runs the calculator and settles the turn, mirroring the look-up flow. */
    private fun calculateAndReply(expression: String, display: String) {
        stopRequested = false
        ui.messages.add(CompanionMsg(fromUser = true, text = display))
        ui.messages.add(CompanionMsg(fromUser = false, text = ""))
        ui.busy = true
        ui.status = "calculating…"
        lastInteractionAt = System.currentTimeMillis()
        scope.launch {
            val result = runCatching {
                CalculateTool().execute(mapOf("expression" to JsonPrimitive(expression)))
            }.getOrNull()
            // "12*8 = 96" -> "96"
            val answer = (result as? ToolResult.Text)?.summary?.substringAfterLast(" = ")?.trim()
            val reply = when {
                answer.isNullOrBlank() -> "I couldn't work that one out."
                // 1/0 evaluates to Infinity; that is not an answer.
                !answer.matches(Regex("^-?[0-9.]+(?:[eE][+-]?\\d+)?$")) ->
                    "That one doesn't have a normal answer — check the numbers."
                else -> answer
            }
            finishTurn(display, reply)
        }
    }

    // --- Mood check-ins -----------------------------------------------------

    /**
     * "How am I?" — she asks, they tap a face, and that is the whole record.
     *
     * A tap rather than free text so the answer is unambiguous and the journal
     * is a real signal rather than a guess at sentiment.
     */
    private fun beginMoodCheckIn() {
        if (ui.busy) return
        ui.awaitingMood = true
        ui.messages.add(CompanionMsg(true, "How am I doing?"))
        ui.messages.add(CompanionMsg(false, "How are you doing right now? No wrong answer."))
        ui.status = "waiting for you"
        saveChat()
    }

    private fun recordMood(score: Int) {
        if (!ui.awaitingMood) return
        ui.awaitingMood = false
        val reply = buildString {
            append("Thanks for telling me. ")
            append(moodAcknowledgement(score))
            // Only mention a pattern once there is one, and gently.
            mood.averageOver(7)?.let { avg ->
                if (avg <= 2.4 && score <= 2) {
                    append(" That's a few low days in a row now — I'm not going anywhere.")
                }
            }
        }
        ui.messages.add(CompanionMsg(false, reply))
        ui.status = brain.backendLabel()
        scope.launch(Dispatchers.IO) {
            runCatching { mood.add(score) }
        }
        speak(reply)
        saveChat()
        lastInteractionAt = System.currentTimeMillis()
    }

    private fun moodAcknowledgement(score: Int): String = when {
        score <= 1 -> "Rough days are heavy. I'm glad you said so."
        score == 2 -> "Low is still worth saying out loud."
        score == 3 -> "Okay is a perfectly good place to be."
        score == 4 -> "Good to hear. I'll take it."
        else -> "That's lovely to hear."
    }

    // --- Reminders ----------------------------------------------------------

    /**
     * Handles "remind me to ..." without going near the model.
     *
     * A reminder that lands at the wrong time is worse than no reminder, so
     * this is deterministic: it recognises the phrasings it can be sure about,
     * and when it can't find a time it asks rather than guessing.
     *
     * @return true when the message was fully handled here.
     */
    private fun handleReminderIfAny(text: String, display: String): Boolean {
        // Waiting on the answer to "when?".
        val waitingFor = pendingReminderSubject
        if (waitingFor != null) {
            pendingReminderSubject = null
            val at = ReminderParser.parseTime(text)
            if (at != null) {
                createReminder(waitingFor, at, display)
                return true
            }
            // Not a time — fall through and treat it as an ordinary message.
        }

        if (!ReminderParser.looksLikeReminder(text)) return false

        val subject = ReminderParser.parseSubject(text)
        val at = ReminderParser.parseTime(text)
        when {
            at != null -> {
                createReminder(subject.ifBlank { "your reminder" }, at, display)
                return true
            }
            subject.isNotBlank() -> {
                pendingReminderSubject = subject
                replyWithoutModel(display, "Sure — when should I remind you?")
                return true
            }
            // "remind me" with no subject and no time: not enough to act on.
            else -> return false
        }
    }

    private fun createReminder(subject: String, triggerAt: Long, display: String) {
        val reminder = CompanionReminder(subject = subject, triggerAt = triggerAt)
        scope.launch(Dispatchers.IO) {
            val saved = reminders.add(reminder)
            ReminderScheduler.schedule(this@CompanionOverlayService, saved)
        }
        replyWithoutModel(
            display,
            "Okay — I'll remind you about ${subject.trimEnd('.')} " +
                "${ReminderScheduler.describe(triggerAt)}.",
        )
    }

    /** A reply that needs no model: reminders are confirmed instantly. */
    private fun replyWithoutModel(display: String, reply: String) {
        ui.messages.add(CompanionMsg(fromUser = true, text = display))
        ui.messages.add(CompanionMsg(fromUser = false, text = reply))
        history.addLast("user" to display)
        history.addLast("assistant" to reply)
        while (history.size > MAX_HISTORY) history.removeFirst()
        lastInteractionAt = System.currentTimeMillis()
        speak(reply)
        saveChat()
    }

    private fun submit(
        text: String,
        display: String = text,
        grounding: String? = null,
    ) {
        if (text.isBlank() || ui.busy) return
        // Any earlier stop belongs to the previous turn; leaving it set would
        // let its safety net cancel this one.
        stopRequested = false
        // Reminders are handled before anything else: they must be exact, and
        // they don't need a model at all.
        if (grounding == null && handleReminderIfAny(text, display)) return
        // Maths and the date have exact answers; never let the model guess them.
        if (grounding == null && handleQuickToolsIfAny(text, display)) return
        ui.messages.add(CompanionMsg(fromUser = true, text = display))
        ui.messages.add(CompanionMsg(fromUser = false, text = ""))
        ui.busy = true
        ui.status = "thinking…"
        lastInteractionAt = System.currentTimeMillis()
        lastTurnWasPageTask = isPageTask(display)

        // A queued memory pass must not delay the reply the user is waiting for.
        abortBackgroundExtraction()

        // Obvious facts land instantly with no model call.
        if (settings().companionMemoryEnabled && !lastTurnWasPageTask) {
            runCatching { memory.captureFromUserText(display) }
        }

        // An explicit grounding (a freshly captured page) wins; otherwise this
        // is an ordinary message, which may still be a follow-up about the page
        // she last read.
        val prompt = grounding ?: if (lastTurnWasPageTask) null else consumeHeldPage()

        scope.launch {
            val result = brain.respond(history.toList(), text, prompt) { delta ->
                val index = ui.messages.lastIndex
                if (index >= 0) {
                    ui.messages[index] = ui.messages[index].copy(text = ui.messages[index].text + delta)
                }
            }
            result.onSuccess { reply ->
                val index = ui.messages.lastIndex
                if (index >= 0) {
                    ui.messages[index] = CompanionMsg(fromUser = false, text = reply)
                }
                history.addLast("user" to display)
                history.addLast("assistant" to reply)
                while (history.size > MAX_HISTORY) history.removeFirst()
                speak(reply)
            }.onFailure { error ->
                val index = ui.messages.lastIndex
                val message = error.message ?: "Something went wrong."
                if (index >= 0) {
                    ui.messages[index] = CompanionMsg(fromUser = false, text = message)
                }
            }
            ui.busy = false
            // Let them know follow-ups will work — otherwise the page she just
            // read is invisible and the capability goes unused.
            ui.status = if (lastTurnWasPageTask && heldPage != null) {
                "ask me anything about this page"
            } else {
                brain.backendLabel()
            }
            saveChat()
            if (!lastTurnWasPageTask) maybeExtractMemory()
        }
    }

    /** Page text is not something to remember about a person. */
    private fun isPageTask(display: String): Boolean =
        display == "Summarize this page" || display == "Explain this"

    /**
     * Every few turns, ask the model to pull durable facts out of the recent
     * exchange.
     *
     * This is a second generation pass, and on a local model it can take many
     * seconds, so it waits for an idle moment and yields immediately if the
     * user starts talking again — a real reply must never queue behind
     * bookkeeping.
     */
    private fun maybeExtractMemory() {
        val s = settings()
        if (!s.companionMemoryEnabled || !s.companionMemoryExtraction) return
        if (!brain.anyBackendReady()) return
        turnsSinceExtraction++
        if (turnsSinceExtraction < EXTRACT_EVERY_TURNS) return
        turnsSinceExtraction = 0

        val exchange = history.takeLast(6).joinToString("\n") { (role, text) -> "$role: $text" }
        if (exchange.isBlank()) return
        val idleAt = lastInteractionAt

        extractJob = scope.launch {
            delay(EXTRACT_IDLE_DELAY_MS)
            // Something happened while we waited: leave it for another time.
            if (ui.busy || lastInteractionAt != idleAt) return@launch
            val added = runCatching {
                // Told what is already stored, so a limited output budget is
                // spent on genuinely new facts rather than restatements.
                val facts = brain.extractFacts(exchange, memory.all().map { it.text })
                if (facts.isEmpty()) 0 else memory.mergeExtracted(facts)
            }.getOrDefault(0)
            if (added > 0) {
                ServerLog.log("companion: learned $added new fact(s)")
                // The quietest possible signal: the status line, not the chat.
                ui.status = "noted something to remember"
            }
        }
    }

    /**
     * Stop a background memory pass so the user's next message gets the engine
     * immediately. Safe to call when nothing is running.
     */
    private fun abortBackgroundExtraction() {
        val job = extractJob ?: return
        if (job.isActive) {
            // The generation runs natively and cannot be cancelled directly;
            // requestStop() makes it end cooperatively at the next token.
            LlamaEngine.requestStop()
            job.cancel()
        }
        extractJob = null
    }

    /**
     * Reads the foreground screen through the accessibility service and asks
     * for a plain-language summary. Page text is capped much lower for the
     * local model: a phone-sized context cannot hold a whole article.
     */
    private fun summarizePage() = pageTask(
        opener = "Please summarize what I'm looking at right now.",
        display = "Summarize this page",
    )

    private fun explainPage() = pageTask(
        opener = "Please explain what I'm looking at, in simple plain language, as if I'm new to it.",
        display = "Explain this",
    )

    /**
     * Translate the page into the phone's language.
     *
     * The target comes from the device locale rather than its own setting: the
     * language someone reads their phone in is the one they want a page in, and
     * another control for it would be one more thing to configure.
     */
    private fun translatePage() {
        val locale = java.util.Locale.getDefault()
        val language = locale.getDisplayLanguage(locale)
        pageTask(
            opener = "Please translate what I'm looking at into $language. " +
                "Keep the meaning faithful and the result easy to read.",
            display = "Translate this",
        )
    }

    private fun pageTask(opener: String, display: String) {
        val accessibility = CompanionAccessibilityService.instance
        if (accessibility == null) {
            ui.messages.add(
                CompanionMsg(false, "Turn on \"Companion screen access\" in Settings and I can read the page for you.")
            )
            return
        }
        val page = accessibility.capture()
        if (page == null || !page.hasContent) {
            ui.messages.add(CompanionMsg(false, "I couldn't read anything on this screen."))
            return
        }
        // Hold the page so the follow-up questions people naturally want to ask
        // ("what about the bit on X?") actually work.
        holdPage(page)
        submit(opener, display = display, grounding = pageGrounding(page))
    }

    /** Remember a page for the next few turns, capped so it can't grow unbounded. */
    private fun holdPage(page: CapturedPage) {
        heldPage = page.copy(text = page.text.take(CLOUD_PAGE_CHARS))
        heldPageTurns = 0
    }

    private fun clearHeldPage() {
        heldPage = null
        heldPageTurns = 0
    }

    /**
     * The held page as prompt grounding, truncated to what the active backend
     * can afford. Built per turn because the backend can change between turns.
     */
    private fun pageGrounding(page: CapturedPage): String {
        val budget = if (brain.localReady()) HELD_PAGE_CHARS_LOCAL else HELD_PAGE_CHARS_CLOUD
        return buildString {
            appendLine("The page they are looking at on screen right now:")
            page.url?.takeIf { it.isNotBlank() }?.let { appendLine("URL: $it") }
            appendLine()
            appendLine(page.text.take(budget))
        }
    }

    /**
     * Grounding for an ordinary message: the held page, if there is one.
     * Returns null once the page has been referenced for a few turns.
     */
    private fun consumeHeldPage(): String? {
        val page = heldPage ?: return null
        heldPageTurns++
        if (heldPageTurns > MAX_HELD_PAGE_TURNS) {
            clearHeldPage()
            return null
        }
        return pageGrounding(page)
    }

    private fun speak(text: String) {
        if (!settings().companionTts || text.isBlank()) return
        val engine = tts ?: return
        runCatching { engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, "companion") }
    }

    // --- Service plumbing ---------------------------------------------------

    private fun startInForeground() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Companion", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Keeps the floating companion available"
                    setShowBadge(false)
                }
            )
        }
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, CompanionOverlayService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Companion is nearby")
            .setContentText("Tap to open PocketLLM")
            .setSmallIcon(R.drawable.ic_companion)
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, "Hide bubble", stop).build())
            .build()
    }

    private fun canDrawOverlays(): Boolean = android.provider.Settings.canDrawOverlays(this)

    /**
     * Fresh repository per read: the service shares a process with the UI, and
     * [SettingsRepository] caches per instance — a long-lived one here would
     * never see settings the user changed in the app.
     */
    private fun settingsRepo(): SettingsRepository = SettingsRepository(applicationContext)

    private fun settings(): AppSettings = settingsRepo().current()

    private fun dp(value: Int): Int = (value * density).toInt()

    companion object {
        private const val CHANNEL_ID = "pocketllm_companion"
        private const val NUDGE_CHANNEL_ID = "pocketllm_companion_checkins"
        private const val NOTIFICATION_ID = 4711
        private const val NUDGE_NOTIFICATION_ID = 4712
        private const val ACTION_STOP = "com.pocketllm.companion.STOP"
        private const val ACTION_EXPAND = "com.pocketllm.companion.EXPAND"
        private const val ACTION_REPLY = "com.pocketllm.companion.REPLY"
        /** Key the notification's reply text arrives under. */
        private const val REPLY_KEY = "companion_reply"
        /** Don't reach out if they were here more recently than this. */
        private const val NUDGE_MIN_IDLE_MS = 45 * 60 * 1000L
        /** Search hits fed to the model; more than this just crowds the prompt. */
        private const val LOOKUP_MAX_RESULTS = 5
        private const val MAX_HISTORY = 12
        /**
         * How much of a held page is fed back on a follow-up. Smaller than the
         * first read because the conversation itself now needs room too.
         */
        private const val HELD_PAGE_CHARS_LOCAL = 1600
        private const val HELD_PAGE_CHARS_CLOUD = 6000
        /** Follow-up turns a held page survives before being dropped. */
        private const val MAX_HELD_PAGE_TURNS = 4
        private const val CLOUD_PAGE_CHARS = 8000
        /** Don't greet if the user was here more recently than this. */
        private const val PROACTIVE_QUIET_MS = 20 * 60 * 1000L
        /** Above this, the greeting acknowledges the gap instead of saying hello. */
        private const val LONG_ABSENCE_MS = 6 * 60 * 60 * 1000L
        /** How often to run the fact-extraction pass, in user turns. */
        private const val EXTRACT_EVERY_TURNS = 4
        /** Wait this long after a turn before spending the engine on memory. */
        private const val EXTRACT_IDLE_DELAY_MS = 20_000L
        /** How long to wait for a backend to honour a stop before settling anyway. */
        private const val STOP_SETTLE_MS = 2_000L
        /** Untouched for this long and the bubble dims to its idle opacity. */
        private const val IDLE_DIM_MS = 4_000L
        /** Grace period after a drag before settings may reposition the bubble. */
        private const val DRAG_SETTLE_MS = 2_000L

        fun start(context: Context) {
            val intent = Intent(context, CompanionOverlayService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, CompanionOverlayService::class.java))
        }

        /** The running service, if any. Same process as the UI, so this is safe. */
        @Volatile
        private var instance: CompanionOverlayService? = null

        /**
         * Apply settings changes to the live bubble. If the service is not
         * running but the user has it switched on, start it. Falls back to a
         * cold start only when there is something to show.
         */
        fun sync(context: Context) {
            val running = instance
            if (running != null) {
                // updateViewLayout must run on the main thread; callers are
                // Compose/ViewModel so this is already main, but be explicit.
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    runCatching { running.refreshFromSettings() }
                }
                return
            }
            if (SettingsRepository(context).current().companionEnabled &&
                android.provider.Settings.canDrawOverlays(context)
            ) {
                start(context)
            }
        }
    }
}
