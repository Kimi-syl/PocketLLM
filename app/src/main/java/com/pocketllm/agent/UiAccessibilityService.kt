package com.pocketllm.agent

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.os.Build
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

        @Volatile
        var isConnected: Boolean = false
            private set

        /** True when the service is connected and has published at least one snapshot. */
        val isRunning: Boolean
            get() = isConnected && snapshot.get().let { !it.isEmpty() && it.nodesById.isNotEmpty() }

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
    }

    override fun onInterrupt() { /* no-op */ }

    override fun onDestroy() {
        isConnected = false
        super.onDestroy()
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

        val root = rootInActiveWindow ?: return
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
                windowTitle = event.packageName?.toString() ?: "",
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
