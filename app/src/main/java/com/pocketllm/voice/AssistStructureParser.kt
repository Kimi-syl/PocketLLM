package com.pocketllm.voice

import android.app.assist.AssistStructure
import android.view.View
import android.view.autofill.AutofillId

/**
 * Turns an [AssistStructure] (the semantic snapshot Android hands to the
 * system assistant) into a compact, line-per-node text tree the model can
 * reason over.
 *
 * This is the "screen context" of the dynamic-assistant role: unlike the
 * accessibility tree it is delivered by the platform on demand, includes
 * autofill ids, and does not require the accessibility service to be enabled.
 */
object AssistStructureParser {

    private const val MAX_NODES = 256
    private const val MAX_DEPTH = 32

    fun parse(structure: AssistStructure?): String {
        if (structure == null) return ""
        val sb = StringBuilder()
        var emitted = 0
        for (w in 0 until structure.windowNodeCount) {
            val root = structure.getWindowNodeAt(w).rootViewNode ?: continue
            walk(root, sb, 0) { emitted++ }
            if (emitted >= MAX_NODES) break
        }
        return sb.toString()
    }

    private fun walk(
        node: AssistStructure.ViewNode,
        sb: StringBuilder,
        depth: Int,
        onEmit: () -> Unit,
    ) {
        if (depth > MAX_DEPTH) return
        val text = node.text?.toString()?.trim().orEmpty()
        val hint = node.hint?.trim().orEmpty()
        val desc = node.contentDescription?.toString()?.trim().orEmpty()
        val id = shortId(node.autofillId)
        val clickable = (node.isClickable || node.isFocusable)

        if (text.isNotEmpty() || desc.isNotEmpty() || hint.isNotEmpty() || clickable) {
            val cls = node.className?.substringAfterLast('.') ?: "View"
            sb.append("  ".repeat(depth))
                .append("<").append(cls)
            if (id.isNotEmpty()) sb.append(" #").append(id)
            if (clickable) sb.append(" [tap]")
            sb.append("> ")
            when {
                text.isNotEmpty() -> sb.append(text)
                desc.isNotEmpty() -> sb.append(desc)
                else -> sb.append("(").append(hint).append(")")
            }
            sb.append('\n')
            onEmit()
        }

        for (i in 0 until node.childCount) {
            walk(node.getChildAt(i), sb, depth + 1, onEmit)
        }
    }

    private fun shortId(id: AutofillId?): String =
        id?.toString()?.substringAfterLast('/')?.takeLast(10).orEmpty()

    fun viewFlags(v: View): String = if (v.isClickable) "clickable" else ""
}
