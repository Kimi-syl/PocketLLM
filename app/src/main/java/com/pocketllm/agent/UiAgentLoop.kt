package com.pocketllm.agent

import com.pocketllm.llm.ChatEngine
import com.pocketllm.llm.EngineState
import com.pocketllm.llm.GenParams
import com.pocketllm.server.PLog
import kotlinx.coroutines.delay
import org.json.JSONObject

/**
 * A compact ReAct loop for the accessibility agent.
 *
 * Per-step flow:
 *   fetch UI tree → build prompt (goal + filtered tree JSON) → call the main
 *   model (NO GBNF; regex + balanced-brace extraction as the fallback) →
 *   execute the action → wait 500ms → refetch tree → repeat, until the model
 *   emits a final answer (no JSON action) or [maxSteps] is reached.
 *
 * Robustness for a user-supplied model without grammar:
 *  - Strips `<think>` blocks, markdown fences and trailing chatter first.
 *  - Non-greedy regex `\{[^{}]*\}` (per spec) first, then a balanced-brace
 *    extractor so "好的，我幫你點擊：`{...}`" still yields the object.
 *  - Up to [maxRetries] re-prompts on a parse / execution failure, with the
 *    error message injected so the model can self-correct.
 *
 * Memory: this loop reuses the single loaded LlamaEngine handle (one model at
 * a time — the engine's mutex serializes inference), and refuses to run while
 * the engine is not Ready, so no second large model can be pulled in.
 */
class UiAgentLoop(
    private val engine: ChatEngine,
    private val executor: UiAgentExecutor,
    private val uiTree: () -> UiAccessibilityService.NodeSnapshot =
        { UiAccessibilityService.snapshot.get() },
    private val maxSteps: Int = 8,
    private val maxRetries: Int = 3,
    private val settleMs: Long = 500L,
    private val maxGenerateTokens: Int = 160,
    private val temperature: Float = 0.2f,
    /**
     * Constrain generation to a single JSON value (llama.cpp GBNF). Off by
     * default: the regex/balanced-brace fallback is the BYOM-safe path. On, the
     * model physically cannot emit the "好的，我幫你點擊：" preface — every token
     * must belong to valid JSON — which removes most parse retries on small
     * models. Toggled from Settings → Advanced.
     */
    private val useGrammar: Boolean = false,
    /**
     * Pre-rendered list of MCP tools ("name — description" lines). Empty when
     * MCP is off, so the prompt is unchanged by default.
     */
    private val mcpToolsBlock: String = "",
) {

    companion object {
        /**
         * The JSON grammar shipped with llama.cpp, trimmed to the value types
         * an action object needs. Kept close to upstream so the syntax is
         * exactly what llama_sampler_init_grammar accepts.
         */
        val JSON_GRAMMAR: String = """
            root   ::= ws value ws
            value  ::= object | array | string | number | ("true" | "false" | "null") ws
            object ::= "{" ws ( string ":" ws value ("," ws string ":" ws value)* )? "}" ws
            array  ::= "[" ws ( value ("," ws value)* )? "]" ws
            string ::= "\"" ( [^"\\\x7F\x00-\x1F] | "\\" (["\\/bfnrt] | "u" [0-9a-fA-F] [0-9a-fA-F] [0-9a-fA-F] [0-9a-fA-F]) )* "\"" ws
            number ::= ("-"? ([0-9] | [1-9] [0-9]*)) ("." [0-9]+)? ([eE] [-+]? [0-9]+)? ws
            ws ::= | " " | "\n" [ \t]{0,20}
        """.trimIndent()
    }

    sealed interface UiResult {
        data class Done(val answer: String?) : UiResult
        data class Error(val message: String) : UiResult
    }

    suspend fun run(userGoal: String, onLog: suspend (String) -> Unit = {}): UiResult {
        if (engine.state.value !is EngineState.Ready) {
            return UiResult.Error("Model not loaded — nothing to reason with")
        }

        var lastTree = uiTree()
        var feedback: String? = null

        for (step in 0 until maxSteps) {
            // Give the screen a beat to settle after the previous action.
            if (step > 0) {
                delay(settleMs)
                lastTree = uiTree()
            }
            if (lastTree.isEmpty()) {
                onLog("step $step: empty UI tree — nothing to act on, asking model to answer")
            }

            // 1. Build the prompt for this step.
            val prompt = buildPrompt(userGoal, lastTree.json, feedback)

            // 2. Call the model, with up to maxRetries re-prompts on failure.
            var executed = false
            for (attempt in 0..maxRetries) {
                val raw = generate(prompt)
                if (raw == null) return UiResult.Error("Model generation failed")

                val jsonText = extractJson(raw)
                if (jsonText == null) {
                    // Two cases:
                    //  (a) the model emitted no action at all → that's its final answer;
                    //  (b) it clearly TRIED to emit an action but the JSON is broken →
                    //      retry with feedback (per spec: max_retries on parse failure).
                    if (looksLikeJsonAttempt(raw)) {
                        feedback = "Your previous output looked like a JSON action but could not be parsed. Reply with exactly one valid JSON object, no markdown, no extra text."
                        if (attempt < maxRetries) continue
                        return UiResult.Error("Model kept emitting unparseable JSON")
                    }
                    val answer = stripThink(raw).trim()
                    onLog("step $step: no action emitted — final answer")
                    return UiResult.Done(answer.ifBlank { null })
                }

                val actionObj = try {
                    JSONObject(jsonText)
                } catch (e: Exception) {
                    null
                }
                if (actionObj == null) {
                    feedback = "Your previous output contained unparseable JSON. Reply with exactly one JSON object, no markdown, no extra text."
                    if (attempt < maxRetries) continue
                    return UiResult.Error("Model kept emitting unparseable JSON")
                }

                // Terminal action: model says the goal is done.
                if (actionObj.optString("action").equals("finish", ignoreCase = true)) {
                    val answer = actionObj.optString("text").ifBlank { null }
                    onLog("step $step: finish action")
                    return UiResult.Done(answer)
                }

                // 3. Execute the action on the real screen.
                val result = executor.executeAction(actionObj.toString())
                onLog("step $step: ${resultToString(result)}")
                executed = true
                when (result) {
                    is UiAgentExecutor.Result.Ok -> {
                        feedback = null
                        break // move to next step (refetch + settle)
                    }
                    is UiAgentExecutor.Result.Err -> {
                        // Action failed (stale id, not visible...). Feed it back
                        // and let the model re-read the (soon refreshed) tree.
                        feedback = "Action failed: ${result.message}. The UI tree will refresh; pick a valid id from it."
                        if (attempt < maxRetries) continue
                        return UiResult.Error(result.message)
                    }
                }
            }

            if (!executed) {
                // Exhausted retries without a successful action.
                return UiResult.Error("Could not execute a valid action after $maxRetries retries")
            }
        }
        // Hit maxSteps without the model finishing — surface where we stopped.
        return UiResult.Done(null)
    }

    // --- model call -------------------------------------------------------

    private suspend fun generate(prompt: String): String? {
        val sb = StringBuilder()
        val result = engine.generate(
            prompt,
            GenParams(
                maxTokens = maxGenerateTokens,
                temperature = temperature,
                topP = 0.9f,
                topK = 40,
                seed = -1,
                grammar = if (useGrammar) JSON_GRAMMAR else null,
            ),
        ) { sb.append(it) }
        return if (result == null) null else sb.toString()
    }

    private fun buildPrompt(goal: String, treeJson: String, feedback: String?): String = buildString {
        appendLine("You are an Android automation agent. You control the phone by issuing ONE JSON action per turn.")
        appendLine()
        appendLine("PREFER system actions: to open an app, dial, or open a URL, use the intent actions below instead of clicking blind UI. Use id-based actions only to drive controls inside the currently open app.")
        appendLine()
        appendLine("System actions (no id needed):")
        appendLine("""{"action":"open_app","package":"<package name OR app label, e.g. com.google.android.apps.maps or Maps>"}""")
        appendLine("""{"action":"dial","number":"<phone number>"}""")
        appendLine("""{"action":"open_url","url":"<full url>"}""")
        appendLine()
        appendLine("The current screen (filtered UI tree) is a JSON array of interactive nodes:")
        appendLine(treeJson.ifBlank { "[]" })
        appendLine()
        appendLine("User goal: $goal")
        feedback?.let {
            appendLine()
            appendLine("Feedback from last attempt: $it")
            appendLine("Read the CURRENT tree above and choose a valid action. Do not repeat the failed action.")
        }
        appendLine()
        appendLine("Respond with ONLY ONE JSON object, one of:")
        appendLine("""{"action":"open_app","package":"..."}  or  {"action":"dial","number":"..."}  or  {"action":"open_url","url":"..."}""")
        appendLine("""{"action":"click","id":<id>}  or  {"action":"type","id":<id>,"text":"<text>"}""")
        appendLine("""{"action":"scroll_down","id":<id>}  or  {"action":"scroll_up","id":<id>}  or  {"action":"focus","id":<id>}""")
        appendLine("""{"action":"finish","text":"<final answer if the goal is achieved or impossible>"}""")
        if (mcpToolsBlock.isNotBlank()) {
            appendLine()
            appendLine("Remote MCP tools you may call when the goal needs them:")
            appendLine(mcpToolsBlock)
            appendLine("""Call one with: {"action":"mcp","tool":"<name>","args":{...}}""")
        }
        appendLine()
        appendLine("If the goal is already achieved, or no action can achieve it, respond with the finish action. No markdown. No extra commentary.")
    }

    // --- robust JSON extraction -------------------------------------------

    /**
     * Per spec, starts with the non-greedy regex `\{[^{}]*\}`; falls back to a
     * balanced-brace scanner that is string-aware, so model chatter around the
     * object ("好的，我幫你點擊：`{...}`") still yields the JSON.
     */
    private fun extractJson(raw: String): String? {
        var t = stripThink(raw).trim()
        // Strip ```json ... ``` / ``` fences.
        t = Regex("```[a-zA-Z]*\\s*([\\s\\S]*?)```").replace(t, "$1").trim()
        if (t.isEmpty()) return null

        // 1) Fast path: regex for a flat object (per spec).
        Regex("""\{[^{}]*\}""").findAll(t).forEach { m ->
            val candidate = m.value
            if (isJsonObject(candidate)) return candidate
        }

        // 2) Fallback: balanced scan, first valid object wins.
        var i = 0
        while (i < t.length) {
            val open = t.indexOf('{', i)
            if (open < 0) break
            val close = matchBrace(t, open) ?: break
            val candidate = t.substring(open, close + 1)
            if (isJsonObject(candidate)) return candidate
            i = open + 1
        }
        return null
    }

    private fun matchBrace(s: String, open: Int): Int? {
        var depth = 0
        var inString = false
        var esc = false
        for (i in open until s.length) {
            val c = s[i]
            when {
                inString -> {
                    when {
                        esc -> esc = false
                        c == '\\' -> esc = true
                        c == '"' -> inString = false
                    }
                }
                c == '"' -> inString = true
                c == '{' -> depth++
                c == '}' -> {
                    depth--
                    if (depth == 0) return i
                }
            }
        }
        return null
    }

    private fun isJsonObject(s: String): Boolean = try {
        JSONObject(s); true
    } catch (_: Exception) {
        false
    }

    private fun stripThink(text: String): String =
        Regex("(?s)<think>.*?</think>\\s*").replace(text, "").trim()

    /** True when the model was clearly attempting an action but the JSON broke. */
    private fun looksLikeJsonAttempt(text: String): Boolean {
        val t = stripThink(text)
        return t.contains('{') || t.contains("```json") || t.contains("\"action\"")
    }

    private fun resultToString(r: UiAgentExecutor.Result): String = when (r) {
        is UiAgentExecutor.Result.Ok -> r.summary
        is UiAgentExecutor.Result.Err -> "ERROR: ${r.message}"
    }
}
