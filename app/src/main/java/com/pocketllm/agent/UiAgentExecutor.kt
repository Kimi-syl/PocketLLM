package com.pocketllm.agent

import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONObject

/**
 * The agent's "hands": turns a compact action JSON into a real action on the
 * device.
 *
 * Two families of actions:
 *  1. System-intent routing (preferred — no blind clicking): open an app via
 *     PackageManager, open the dialer, or open a URL in the browser.
 *     {"action":"open_app","package":"com.example.app"|"<app name>"}
 *     {"action":"dial","number":"<phone number>"}
 *     {"action":"open_url","url":"https://..."}
 *  2. UI-tree actions (fallback when the model has to drive an in-app control):
 *     {"action":"click","id":1}
 *     {"action":"type","id":2,"text":"hi"}          → focus + ACTION_SET_TEXT
 *     {"action":"scroll_up"} / {"action":"scroll_down"}
 *     {"action":"focus","id":3}
 *
 * Click robustness (for tree actions):
 *  - If ACTION_CLICK fails on the node, walk up to an `isClickable` ancestor
 *    and try there (some widgets expose only the container as clickable).
 *  - If that still fails, fall back to `dispatchGesture` — a synthetic tap at
 *    the node's on-screen centre, which does not depend on the target app
 *    honouring the accessibility click.
 *
 * Recycle-race safety: the accessibility service swaps the snapshot and (on
 * API 26–32) recycles nodes it replaced while we may still hold a reference.
 * Every node operation is therefore guarded so a recycled / stale node turns
 * into a retryable error instead of an IllegalStateException crash.
 */
class UiAgentExecutor(
    private val context: Context,
    private val resolveNode: (Int) -> AccessibilityNodeInfo? = { id ->
        UiAccessibilityService.snapshot.get().nodesById[id]
    },
    /**
     * Optional bridge to an MCP server: (toolName, argsJson) -> text result.
     * Supplied by the ViewModel when MCP is enabled, so the model can call
     * remote tools with the {"action":"mcp","tool":…,"args":{…}} form.
     */
    private val mcpCall: (suspend (String, String) -> String)? = null,
) {

    sealed interface Result {
        data class Ok(val summary: String) : Result
        data class Err(val message: String) : Result
    }

    /** Entry point: `executeAction(jsonString)` — parses, routes, acts. */
    suspend fun executeAction(actionJson: String): Result {
        val obj = try {
            JSONObject(actionJson)
        } catch (e: Exception) {
            return Result.Err("Invalid action JSON: ${e.message}")
        }

        val action = obj.optString("action").lowercase().trim()
        // System-intent actions need no UI node — prefer these over blind clicks.
        return when (action) {
            "open_app", "launch_app" -> openApp(obj.optString("package").trim())
            "dial", "call", "phone" -> dial(obj.optString("number").trim())
            "open_url", "browse", "web" -> openUrl(obj.optString("url").trim())
            "mcp", "mcp_call" -> mcpAction(obj)
            else -> executeNodeAction(action, obj)
        }
    }

    /** Remote tool call through the MCP bridge. */
    private suspend fun mcpAction(obj: JSONObject): Result {
        val handler = mcpCall ?: return Result.Err("MCP is not enabled in Settings")
        val tool = obj.optString("tool").ifBlank { obj.optString("name") }.trim()
        if (tool.isBlank()) return Result.Err("mcp action needs a 'tool' name")
        val args = obj.optJSONObject("args")?.toString()
            ?: obj.optString("arguments", "{}").ifBlank { "{}" }
        val out = handler(tool, args)
        return Result.Ok("mcp:$tool → ${out.take(500)}")
    }

    // --- system intent routing ---------------------------------------------

    private fun openApp(query: String): Result {
        if (query.isBlank()) return Result.Err("open_app needs a 'package' (or app name)")
        val pm = context.packageManager

        // 1) Direct package hit. On Android 11+ the launcher enumeration below is
        //    filtered by package visibility, but an explicit package declared in
        //    <queries> (or otherwise visible) still resolves here. This is the
        //    fallback the bug report asked for.
        runCatching { pm.getLaunchIntentForPackage(query) }.getOrNull()?.let { intent ->
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            return launch(intent, "launch $query")
        }

        // 2) Otherwise match the visible launcher entries by package or label.
        val launchables = runCatching {
            pm.queryIntentActivities(
                Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0
            )
        }.getOrElse { emptyList() }

        val pkg = launchables.firstOrNull { it.activityInfo.packageName.equals(query, true) }
            ?.activityInfo?.packageName
            ?: launchables.firstOrNull {
                it.loadLabel(pm).toString().lowercase().contains(query.lowercase())
            }?.activityInfo?.packageName
            ?: return Result.Err(
                "No launchable app matches '$query' " +
                    "(Android 11+ hides apps unless their package is declared in <queries>)."
            )

        val intent = pm.getLaunchIntentForPackage(pkg)
            ?: return Result.Err("No launcher intent for $pkg")
        return launch(intent, "launch $pkg")
    }

    private fun dial(number: String): Result {
        if (number.isBlank()) return Result.Err("dial needs a 'number'")
        // fromParts keeps '+' and percent-encodes the rest; raw concat would
        // mangle '+' (Uri.parse/encode turns it into %2B) and break dialing.
        return launch(
            Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", number, null)),
            "dial $number"
        )
    }

    private fun openUrl(url: String): Result {
        if (url.isBlank()) return Result.Err("open_url needs a 'url'")
        val normalized = if (url.startsWith("http://") || url.startsWith("https://")) url
            else "https://$url"
        return launch(
            Intent(Intent.ACTION_VIEW, Uri.parse(normalized)),
            "open $normalized"
        )
    }

    private fun launch(intent: Intent, label: String): Result {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(intent)
            Result.Ok(label)
        } catch (e: Exception) {
            Result.Err("Failed to $label: ${e.message}")
        }
    }

    // --- UI-tree actions ---------------------------------------------------

    private fun executeNodeAction(action: String, obj: JSONObject): Result {
        val id = obj.optInt("id", -1)
        if (id <= 0) return Result.Err("Missing or invalid node id for action '$action'")
        val node = resolveNode(id)
            ?: return Result.Err("No node cached with id=$id — the UI tree changed; re-prompt with the fresh tree")

        return when (action) {
            "click", "tap" -> click(node, id)
            "type", "input", "set_text" -> type(node, id, obj.optString("text"))
            "focus" -> perform(node, id, AccessibilityNodeInfo.ACTION_FOCUS, "focus")
            "clear" -> clear(node, id)
            "scroll_up" -> perform(node, id, AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD, "scroll_up")
            "scroll_down" -> perform(node, id, AccessibilityNodeInfo.ACTION_SCROLL_FORWARD, "scroll_down")
            else -> Result.Err("Unknown action '$action' (supported: click|type|focus|clear|scroll_up|scroll_down|open_app|dial|open_url)")
        }
    }

    // --- concrete actions --------------------------------------------------

    private fun click(node: AccessibilityNodeInfo, id: Int): Result {
        return try {
            if (!node.isVisibleToUser) return Result.Err("Node #$id is off-screen / not visible")
            // 1) Direct ACTION_CLICK on the node itself.
            if (node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                Result.Ok("click on #$id")
            } else {
                // 2) Walk up to a clickable ancestor — many controls only make the
                //    container clickable.
                if (clickClickableAncestor(node)) {
                    Result.Ok("click via clickable ancestor of #$id")
                } else {
                    // 3) Coordinate gesture fallback — synthetic tap that bypasses
                    //    the target's accessibility handling.
                    val b = Rect()
                    node.getBoundsInScreen(b)
                    val cx = b.exactCenterX().toInt()
                    val cy = b.exactCenterY().toInt()
                    if (UiAccessibilityService.dispatchTap(cx, cy)) {
                        Result.Ok("tap #$id at ($cx,$cy)")
                    } else {
                        Result.Err("click failed on #$id (ACTION_CLICK + ancestors + gesture all failed)")
                    }
                }
            }
        } catch (e: Exception) {
            if (e is IllegalStateException) {
                // Node recycled by the service between capture and use (API 26–32).
                Result.Err("Node #$id became stale/recycled — the UI tree will refresh; retry")
            } else {
                Result.Err("click failed on #$id: ${e.message ?: e::class.java.simpleName}")
            }
        }
    }

    /**
     * Try ACTION_CLICK on each clickable ancestor up the parent chain. Returns
     * true if any succeeded. Ancestor refs we obtained are recycled on API<33
     * unless the live snapshot still owns them.
     */
    private fun clickClickableAncestor(node: AccessibilityNodeInfo): Boolean {
        val chain = ArrayList<AccessibilityNodeInfo>(8)
        var p: AccessibilityNodeInfo? = node.parent
        var guard = 0
        while (p != null && guard < 8) {
            chain.add(p)
            p = p.parent
            guard++
        }
        var clicked = false
        try {
            for (anc in chain) {
                if (anc.isClickable && anc.isVisibleToUser) {
                    if (anc.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                        clicked = true
                        break
                    }
                }
            }
        } finally {
            if (Build.VERSION.SDK_INT < 33) {
                // Recycle the refs we created, but never ones the live snapshot
                // owns (the service recycles those itself on replacement).
                val owned = UiAccessibilityService.snapshot.get().nodesById.values.toHashSet()
                for (anc in chain) if (anc !in owned) anc.recycle()
            }
        }
        return clicked
    }

    private fun type(node: AccessibilityNodeInfo, id: Int, text: String): Result {
        return try {
            if (text.isEmpty()) return Result.Err("type action needs a non-empty 'text'")
            if (!node.isEditable && !node.isFocusable) {
                return Result.Err("Node #$id is not an editable/focusable field")
            }
            val args = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
            }
            // Focus first so the field is the active input target, then replace text.
            node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            val ok = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
            if (ok) Result.Ok("typed ${text.length} chars into #$id")
            else Result.Err("ACTION_SET_TEXT failed on #$id")
        } catch (e: Exception) {
            if (e is IllegalStateException) {
                Result.Err("Node #$id became stale/recycled — refresh the tree and retry")
            } else {
                Result.Err("type failed on #$id: ${e.message ?: e::class.java.simpleName}")
            }
        }
    }

    private fun clear(node: AccessibilityNodeInfo, id: Int): Result = try {
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "")
        }
        val ok = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        if (ok) Result.Ok("cleared #$id") else Result.Err("clear failed on #$id")
    } catch (e: Exception) {
        Result.Err("clear failed on #$id: ${e.message ?: e::class.java.simpleName}")
    }

    private fun perform(node: AccessibilityNodeInfo, id: Int, action: Int, label: String): Result {
        return try {
            if (!node.isVisibleToUser) return Result.Err("Node #$id is off-screen / not visible")
            val ok = node.performAction(action)
            if (ok) Result.Ok("$label on #$id")
            else Result.Err("$label failed on #$id (action=$action)")
        } catch (e: Exception) {
            if (e is IllegalStateException) {
                Result.Err("Node #$id became stale/recycled — refresh the tree and retry")
            } else {
                Result.Err("$label failed on #$id: ${e.message ?: e::class.java.simpleName}")
            }
        }
    }
}
