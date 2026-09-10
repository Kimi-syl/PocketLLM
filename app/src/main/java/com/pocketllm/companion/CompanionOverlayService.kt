package com.pocketllm.companion

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.speech.tts.TextToSpeech
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.pocketllm.MainActivity
import com.pocketllm.R
import com.pocketllm.server.ServerLog
import com.pocketllm.settings.AppSettings
import com.pocketllm.settings.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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
    private var rootView: ComposeView? = null
    private var owner: OverlayLifecycleOwner? = null
    private var tts: TextToSpeech? = null

    private val ui = CompanionUiState()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val history = ArrayDeque<Pair<String, String>>()

    private var bubbleX = 0
    private var bubbleY = 0
    /** Where the bubble sat before the panel was opened, restored on collapse. */
    private var bubbleYBeforeExpand = 0

    private val density: Float get() = resources.displayMetrics.density

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        brain = CompanionBrain { settings() }
        ui.status = brain.backendLabel()
        ui.onExpand = { expand() }
        ui.onCollapse = { collapse() }
        ui.onSend = { text -> submit(text) }
        ui.onSummarize = { summarizePage() }
        ui.onDrag = { dx, dy -> moveBy(dx, dy) }
        ui.onDragEnd = { persistPosition() }

        val s = settings()
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
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onDestroy() {
        runCatching { rootView?.let { windowManager.removeView(it) } }
        owner?.stop()
        rootView = null
        owner = null
        tts?.shutdown()
        tts = null
        scope.cancel()
        super.onDestroy()
    }

    // --- Overlay window -----------------------------------------------------

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
            width = dp(64)
            height = dp(64)
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
        // Lift the panel toward the top: an overlay window low on the screen
        // would have its input row covered by the soft keyboard.
        bubbleYBeforeExpand = bubbleY
        bubbleY = dp(48)
        val params = layoutParams(expanded = true)
        bubbleX = params.x
        runCatching { windowManager.updateViewLayout(view, params) }
    }

    private fun collapse() {
        if (!ui.expanded) return
        ui.expanded = false
        val view = rootView ?: return
        bubbleY = bubbleYBeforeExpand
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
        (resources.displayMetrics.widthPixels - dp(64) - dp(12)).coerceAtLeast(0)

    private fun persistPosition() {
        // Only the collapsed bubble position is meaningful.
        if (ui.expanded) return
        scope.launch {
            runCatching { settingsRepo().update { it.copy(companionBubbleX = bubbleX, companionBubbleY = bubbleY) } }
        }
    }

    // --- Conversation -------------------------------------------------------

    private fun submit(text: String, display: String = text) {
        if (text.isBlank() || ui.busy) return
        ui.messages.add(CompanionMsg(fromUser = true, text = display))
        ui.messages.add(CompanionMsg(fromUser = false, text = ""))
        ui.busy = true
        ui.status = "thinking…"

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
        }
    }

    /**
     * Reads the foreground screen through the accessibility service and asks
     * for a plain-language summary. Page text is capped much lower for the
     * local model: a phone-sized context cannot hold a whole article.
     */
    private fun summarizePage() {
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
            appendLine("Please summarize what I'm looking at right now.")
            page.url?.let { appendLine("URL: $it") }
            appendLine()
            appendLine(body)
        }
        submit(prompt, display = "Summarize this page")
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
        private const val NOTIFICATION_ID = 4711
        private const val ACTION_STOP = "com.pocketllm.companion.STOP"
        private const val MAX_HISTORY = 12
        private const val LOCAL_PAGE_CHARS = 2500
        private const val CLOUD_PAGE_CHARS = 8000

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
    }
}
