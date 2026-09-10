package com.pocketllm.companion

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/** Snapshot of whatever the user is currently looking at. */
data class CapturedPage(
    val url: String?,
    val text: String,
    val packageName: String,
) {
    val hasContent: Boolean get() = !url.isNullOrBlank() || text.isNotBlank()
}

/**
 * Passive accessibility service whose only job is to read the foreground
 * screen when the user explicitly taps "Summarize". It never dispatches
 * gestures or clicks, so the granted capability stays as narrow as possible.
 */
class CompanionAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    /** Passive: the companion reads state on demand rather than reacting to events. */
    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    /** Address bar (when a browser is in front) plus the visible text on screen. */
    fun capture(maxChars: Int = MAX_TEXT): CapturedPage? {
        val root = runCatching { rootInActiveWindow }.getOrNull() ?: return null
        val pkg = root.packageName?.toString().orEmpty()
        val url = findAddressBar(root)
        val sb = StringBuilder()
        collectText(root, sb, maxChars)
        return CapturedPage(url, sb.toString().trim(), pkg)
    }

    private fun findAddressBar(root: AccessibilityNodeInfo): String? {
        var found: String? = null
        walk(root) { node ->
            val id = node.viewIdResourceName?.lowercase().orEmpty()
            val text = node.text?.toString()?.trim().orEmpty()
            val looksLikeUrl = text.startsWith("http://") || text.startsWith("https://") ||
                (text.length in 4..300 && text.contains('.') && !text.contains(' '))
            val looksLikeField = id.contains("url") || id.contains("address") ||
                id.contains("location") || id.contains("omnibox")
            if (looksLikeUrl && looksLikeField) found = text
            true
        }
        return found
    }

    private fun collectText(root: AccessibilityNodeInfo, out: StringBuilder, maxChars: Int) {
        walk(root) { node ->
            if (out.length < maxChars) {
                node.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let {
                    out.append(it).append('\n')
                }
            }
            if (out.length < maxChars) {
                node.contentDescription?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let {
                    out.append(it).append('\n')
                }
            }
            out.length < maxChars
        }
    }

    /**
     * Breadth-first scan with a hard node cap: some apps expose enormous trees
     * and an unbounded walk would jank the whole device.
     */
    private inline fun walk(root: AccessibilityNodeInfo, visit: (AccessibilityNodeInfo) -> Boolean) {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var visited = 0
        while (queue.isNotEmpty() && visited++ < MAX_NODES) {
            val node = queue.removeFirst()
            if (!visit(node)) return
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it) }
            }
        }
    }

    companion object {
        @Volatile
        var instance: CompanionAccessibilityService? = null
            private set

        /** True when the user has switched the service on in system settings. */
        fun isConnected(): Boolean = instance != null

        private const val MAX_NODES = 6000
        private const val MAX_TEXT = 12000
    }
}
