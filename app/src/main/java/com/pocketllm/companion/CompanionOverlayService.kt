package com.pocketllm.companion

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
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
    /** Active dictation session, if any. */
    private var recognizer: SpeechRecognizer? = null

    private val density: Float get() = resources.displayMetrics.density

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        memory = CompanionMemory(applicationContext)
        brain = CompanionBrain(settings = { settings() }, memory = { memory })
        store = CompanionStore(applicationContext)
        restoreTranscript()
        ui.status = brain.backendLabel()
        ui.onExpand = { expand() }
        ui.onCollapse = { collapse() }
        ui.onSend = { text -> submit(text) }
        ui.onSummarize = { summarizePage() }
        ui.onExplain = { explainPage() }
        ui.onCheer = { cheerUp() }
        ui.onNewChat = { resetConversation() }
        ui.onMicToggle = { toggleListening() }
        ui.onDrag = { dx, dy -> moveBy(dx, dy) }
        ui.onDragEnd = { persistPosition() }

        val s = settings()
        applyAppearance(s)
        bubbleX = if (s.companionBubbleX >= 0) s.companionBubbleX else defaultBubbleX()
        bubbleY = s.companionBubbleY

        tts = TextToSpeech(applicationContext) { /* speak() is a no-op until ready */ }

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
        ui.status = brain.backendLabel()
        restartCheckInLoop()
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
            .build()
        // Distinct id: never overwrite the ongoing bubble notification.
        runCatching { manager.notify(NUDGE_NOTIFICATION_ID, notification) }
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

    override fun onDestroy() {
        runCatching { rootView?.let { windowManager.removeView(it) } }
        owner?.stop()
        rootView = null
        owner = null
        extractJob?.cancel()
        extractJob = null
        checkInJob?.cancel()
        checkInJob = null
        // Never leave the microphone open behind us.
        stopListening()
        tts?.shutdown()
        tts = null
        scope.cancel()
        if (instance === this) instance = null
        super.onDestroy()
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
        ui.voiceInputEnabled = s.companionVoiceInput

        if (!ui.expanded) {
            val view = rootView ?: return
            runCatching { windowManager.updateViewLayout(view, layoutParams(expanded = false)) }
        }
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

    private fun expand() {
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
        maybeGreet()
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
        scope.launch {
            runCatching { settingsRepo().update { it.copy(companionBubbleX = bubbleX, companionBubbleY = bubbleY) } }
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

    private fun submit(text: String, display: String = text) {
        if (text.isBlank() || ui.busy) return
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

        scope.launch {
            val result = brain.respond(history.toList(), text) { delta ->
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
            ui.status = brain.backendLabel()
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
                val facts = brain.extractFacts(exchange)
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
        val budget = if (brain.localReady()) LOCAL_PAGE_CHARS else CLOUD_PAGE_CHARS
        val body = page.text.take(budget)
        val prompt = buildString {
            appendLine(opener)
            page.url?.let { appendLine("URL: $it") }
            appendLine()
            appendLine(body)
        }
        submit(prompt, display = display)
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
        /** Don't reach out if they were here more recently than this. */
        private const val NUDGE_MIN_IDLE_MS = 45 * 60 * 1000L
        private const val MAX_HISTORY = 12
        private const val LOCAL_PAGE_CHARS = 2500
        private const val CLOUD_PAGE_CHARS = 8000
        /** Don't greet if the user was here more recently than this. */
        private const val PROACTIVE_QUIET_MS = 20 * 60 * 1000L
        /** Above this, the greeting acknowledges the gap instead of saying hello. */
        private const val LONG_ABSENCE_MS = 6 * 60 * 60 * 1000L
        /** How often to run the fact-extraction pass, in user turns. */
        private const val EXTRACT_EVERY_TURNS = 4
        /** Wait this long after a turn before spending the engine on memory. */
        private const val EXTRACT_IDLE_DELAY_MS = 20_000L

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
