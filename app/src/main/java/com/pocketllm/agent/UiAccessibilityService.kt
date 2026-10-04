package com.pocketllm.agent

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicReference

/**
 * The agent's "eyes": captures a filtered snapshot of the active window's
 * node tree and publishes it atomically so [UiAgentLoop] / [UiAgentExecutor]
 * can read it from a worker thread while this service republishes on its own
 * main thread.
 *
 * Filter rule (per spec): keep a node iff it is *clickable* OR *editable* OR
 * carries *text* OR a *content description*; drop inert Layout / ViewGroup
 * nodes (a clickable container is still actionable, so it is kept — along with
 * its children).
 *
 * Output format (compressed JSON array, one line per node):
 *   [{"id":1,"text":"搜尋","class":"Button"},{"id":2,"text":"阿媽","class":"ChatItem"}]
 *
 * Memory notes:
 *  - Hard caps on node count (256) and tree depth (32) bound prompt size.
 *  - On API 33+ `AccessibilityNodeInfo.recycle()` is deprecated and the
 *    framework manages pooling, so we do NOT recycle; on <33 we recycle the
 *    refs we created once the snapshot is replaced. The root node returned by
 *    `rootInActiveWindow` is owned by the framework and never recycled.
 */
class UiAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "UiAccessibility"
        private const val MAX_NODES = 256   // hard cap → bounds prompt size
        private const val MAX_DEPTH = 32    // guard against pathological trees
        private const val NULL_ROOT_STREAK_LIMIT = 20  // events without a window → zombie

        @Volatile
        private var instance: UiAccessibilityService? = null

        /**
         * Coordinate-gesture fallback for clicks that fail via ACTION_CLICK
         * (including on ancestors). Synthesises a short tap at (x, y) in screen
         * coordinates via [AccessibilityService.dispatchGesture], which works
         * even when the target app rejects the accessibility click. Returns
         * true if the gesture was accepted for dispatch.
         */
        fun dispatchTap(x: Int, y: Int): Boolean {
            val svc = instance ?: return false
            return svc.dispatchTapImpl(x, y)
        }

        @Volatile
        var isConnected: Boolean = false
            private set

        /**
         * Set when the health check finds a wedged instance (connected according
         * to the framework, but unable to read any window). The settings screen
         * surfaces this so the user knows to re-enable the service.
         */
        @Volatile
        var zombieDetected: Boolean = false

        /** True when the service is connected and has published at least one snapshot. */
        val isRunning: Boolean
            get() = isConnected && snapshot.get().nodesById.isNotEmpty()

        /** Latest published tree snapshot; read by the loop from any thread. */
        val snapshot = AtomicReference(NodeSnapshot.empty())
    }

    /** Immutable, atomically-swappable view of one tree capture. */
    data class NodeSnapshot(
        val nodesById: Map<Int, AccessibilityNodeInfo>,
        val json: String,
        val capturedAtMs: Long,
        val windowTitle: String,
    ) {
        fun isEmpty(): Boolean = nodesById.isEmpty()
        companion object {
            fun empty() = NodeSnapshot(emptyMap(), "[]", 0L, "")
        }
    }

    private data class UiNode(
        val id: Int,
        val text: String,
        val className: String,
        val node: AccessibilityNodeInfo,
    )

    override fun onServiceConnected() {
        super.onServiceConnected()
        // Broaden the flags at runtime on top of the static config: report view
        // ids and keep non-important views so the agent sees more of the screen.
        serviceInfo = serviceInfo.apply {
            flags = flags or
                AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS or
                AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
        isConnected = true
        instance = this
        zombieDetected = false
        nullRootStreak = 0

        // MIUI (and some Teclast builds) fire a burst of events right after the
        // service is bound; reading the tree during that window can yield null.
        // Wait for things to settle before judging the instance healthy.
        Handler(Looper.getMainLooper()).postDelayed({ verifyHealthyOrDisableSelf() }, 1500)
    }

    /**
     * A "zombie" instance is one the framework still reports as connected while
     * it can no longer read any window - observed on MIUI and the Teclast P20HD
     * after the app is closed and reopened. The tree stays empty until the user
     * toggles the service off and on by hand.
     *
     * We detect it here and call [disableSelf] so the framework tears the dead
     * instance down instead of leaving it wedged.
     */
    private fun verifyHealthyOrDisableSelf() {
        if (!isConnected) return
        val root = rootInActiveWindow
        if (root != null) {
            // Publish an initial snapshot so isRunning flips true immediately
            // rather than waiting for the first content-changed event.
            publishSnapshot(root, root.packageName?.toString())
            return
        }
        if (snapshot.get().isEmpty()) {
            zombieDetected = true
            runCatching { disableSelf() }
        }
    }

    override fun onInterrupt() { /* no-op */ }

    override fun onDestroy() {
        isConnected = false
        if (instance === this) instance = null
        super.onDestroy()
    }

    /** Consecutive events where the framework handed us no active window. */
    private var nullRootStreak = 0

    /** Short tap at a screen coordinate (used as the ACTION_CLICK fallback). */
    private fun dispatchTapImpl(x: Int, y: Int): Boolean {
        val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 60))
            .build()
        return dispatchGesture(gesture, null, Handler(Looper.getMainLooper()))
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        // Cheap gate: only refresh on window transitions or content changes.
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            AccessibilityEvent.TYPE_WINDOWS_CHANGED -> { /* proceed */ }
            else -> return
        }

        val root = rootInActiveWindow
        if (root == null) {
            // Connected, yet the framework hands us no window. Brief gaps happen
            // during transitions; a long streak means the instance is wedged.
            if (++nullRootStreak >= NULL_ROOT_STREAK_LIMIT) {
                zombieDetected = true
                runCatching { disableSelf() }
            }
            return
        }
        nullRootStreak = 0
        publishSnapshot(root, event.packageName?.toString())
    }

    /** Builds a snapshot from [root] and swaps it into [snapshot]. */
    private fun publishSnapshot(root: AccessibilityNodeInfo, pkg: String?) {
        val nodes = ArrayList<UiNode>(64)
        val visited = ArrayList<AccessibilityNodeInfo>(64)
        parseNodeTree(root, nodes, visited, depth = 0)

        val capped = if (nodes.size > MAX_NODES) nodes.subList(0, MAX_NODES) else nodes
        val byId = HashMap<Int, AccessibilityNodeInfo>(capped.size)
        for (n in capped) byId[n.id] = n.node

        val json = toJson(capped)
        val old = snapshot.getAndSet(
            NodeSnapshot(
                nodesById = byId,
                json = json,
                capturedAtMs = System.currentTimeMillis(),
                windowTitle = pkg ?: "",
            )
        )

        // Only reclaim on pre-33 where recycle() is meaningful.
        @Suppress("DEPRECATION")
        if (Build.VERSION.SDK_INT < 33) {
            val retained = capped.map { it.node }.toHashSet()
            for (v in visited) if (v !in retained) v.recycle() // refs we created, not kept
            old.nodesById.values.forEach { it.recycle() }      // replaced snapshot's nodes
        }
    }

    /**
     * Recursive tree walk. `visited` collects every child ref we created so we
     * can recycle the non-retained ones later. Retained nodes live in the
     * snapshot and are recycled only when that snapshot is replaced.
     */
    private fun parseNodeTree(
        node: AccessibilityNodeInfo,
        out: MutableList<UiNode>,
        visited: MutableList<AccessibilityNodeInfo>,
        depth: Int,
    ) {
        if (depth > MAX_DEPTH) return
        if (shouldKeep(node)) {
            out += UiNode(out.size + 1, nodeText(node), nodeClass(node), node)
        }
        val childCount = node.childCount
        for (i in 0 until childCount) {
            val child = node.getChild(i) ?: continue
            visited += child
            parseNodeTree(child, out, visited, depth + 1)
        }
    }

    private fun shouldKeep(node: AccessibilityNodeInfo): Boolean {
        val cls = nodeClass(node)
        if (cls.endsWith("Layout") || cls.endsWith("ViewGroup")) {
            if (!node.isClickable) return false // inert container → drop
        }
        return node.isClickable || node.isEditable ||
            node.text != null || node.contentDescription != null
    }

    private fun nodeText(node: AccessibilityNodeInfo): String {
        val t = node.text?.toString()?.trim().orEmpty()
        if (t.isNotEmpty()) return t
        return node.contentDescription?.toString()?.trim().orEmpty()
    }

    private fun nodeClass(node: AccessibilityNodeInfo): String =
        node.className?.toString()?.substringAfterLast('.') ?: ""

    /** `[{"id":1,"text":"搜尋","class":"Button"}, ...]` — compressed, no pretty print. */
    private fun toJson(nodes: List<UiNode>): String {
        val arr = JSONArray()
        for (n in nodes) {
            arr.put(
                JSONObject()
                    .put("id", n.id)
                    .put("text", n.text)
                    .put("class", n.className)
            )
        }
        return arr.toString()
    }
}
