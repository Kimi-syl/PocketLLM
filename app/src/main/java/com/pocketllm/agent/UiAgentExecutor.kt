package com.pocketllm.agent

import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONObject

/**
 * The agent's "hands": turns a compact action JSON into a real
 * `performAction` call on a node looked up by id from the current snapshot.
 *
 * Supported actions (per spec):
 *   {"action":"click","id":1}
 *   {"action":"type","id":2,"text":"hi"}          → focus + ACTION_SET_TEXT
 *   {"action":"scroll_up"} / {"action":"scroll_down"}
 *   {"action":"focus","id":3}
 *
 * Every call re-validates the node against the *latest* snapshot at execution
 * time: ids in the model's reply can go stale after a 500ms settle, so a miss
 * is reported as a retryable error instead of crashing.
 */
class UiAgentExecutor(
    private val resolveNode: (Int) -> AccessibilityNodeInfo? = { id ->
        UiAccessibilityService.snapshot.get().nodesById[id]
    },
) {

    sealed interface Result {
        data class Ok(val summary: String) : Result
        data class Err(val message: String) : Result
    }

    /** Entry point: `executeAction(jsonString)` — parses, resolves, acts. */
    fun executeAction(actionJson: String): Result {
        val obj = try {
            JSONObject(actionJson)
        } catch (e: Exception) {
            return Result.Err("Invalid action JSON: ${e.message}")
        }

        val action = obj.optString("action").lowercase().trim()
        val id = obj.optInt("id", -1)

        if (id <= 0) return Result.Err("Missing or invalid node id for action '$action'")
        val node = resolveNode(id)
            ?: return Result.Err("No node cached with id=$id — the UI tree changed; re-prompt with the fresh tree")

        return when (action) {
            "click" -> click(node, id)
            "type", "input", "set_text" -> type(node, id, obj.optString("text"))
            "focus" -> perform(node, id, AccessibilityNodeInfo.ACTION_FOCUS, "focus")
            "clear" -> clear(node, id)
            "scroll_up" -> perform(node, id, AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD, "scroll_up")
            "scroll_down" -> perform(node, id, AccessibilityNodeInfo.ACTION_SCROLL_FORWARD, "scroll_down")
            else -> Result.Err("Unknown action '$action' (supported: click|type|focus|clear|scroll_up|scroll_down)")
        }
    }

    // --- concrete actions -------------------------------------------------

    private fun click(node: AccessibilityNodeInfo, id: Int): Result {
        if (!node.isVisibleToUser) return Result.Err("Node #$id is off-screen / not visible")
        return perform(node, id, AccessibilityNodeInfo.ACTION_CLICK, "click")
    }

    private fun type(node: AccessibilityNodeInfo, id: Int, text: String): Result {
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
        return if (ok) Result.Ok("typed ${text.length} chars into #$id")
        else Result.Err("ACTION_SET_TEXT failed on #$id")
    }

    private fun clear(node: AccessibilityNodeInfo, id: Int): Result {
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "")
        }
        val ok = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        return if (ok) Result.Ok("cleared #$id") else Result.Err("clear failed on #$id")
    }

    private fun perform(node: AccessibilityNodeInfo, id: Int, action: Int, label: String): Result {
        if (!node.isVisibleToUser) return Result.Err("Node #$id is off-screen / not visible")
        val ok = node.performAction(action)
        return if (ok) Result.Ok("$label on #$id")
        else Result.Err("$label failed on #$id (action=$action)")
    }
}
