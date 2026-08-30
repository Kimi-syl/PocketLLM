package com.pocketllm.agent

import com.pocketllm.llm.GenParams
import com.pocketllm.llm.LlamaEngine
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

/**
 * The model emits tool calls in one of these forms:
 *
 *   TOOL: <name> ARGS: key=value;key=value
 *
 *   ```json
 *   {"name": "<tool_name>", "arguments": {"key": "value"}}
 *   ```
 *
 * The primary form is the single-line version — even 1-3B models follow it
 * reliably. The JSON form is accepted as a fallback for models that prefer it.
 *
 * The loop drives a max of [maxTurns] model+tool cycles, surfaces each
 * intermediate tool result to the UI, and re-prompts the model with the
 * tool's output until the model either emits no tool call or hits the cap.
 *
 * Reliability features added after a first review pass:
 *  - "Thinking" / "tool running" status callbacks for the UI.
 *  - Per-tool timeout (default 30s) to keep a slow network from hanging the loop.
 *  - Argument validation before execution, with the error message returned to the model.
 *  - Up to [maxRetries] retries when the model outputs a malformed tool call
 *    (the error is re-injected so the model can self-correct).
 *  - Graceful degradation: if the model never emits a tool call but the user
 *    asked a question that clearly needs one, we let the direct answer through.
 */
class AgentLoop(
    private val engine: LlamaEngine,
    private val registry: ToolRegistry,
    private val enabledTools: () -> Set<String> = { registry.names() },
    private val maxTurns: Int = 2,
    private val maxRetries: Int = 1,
    private val toolTimeoutMs: Long = 30_000L,
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    sealed interface Status {
        data object Thinking : Status
        data class RunningTool(val tool: String, val args: String) : Status
    }

    suspend fun run(
        systemPrompt: String,
        history: List<Pair<String, String>>,
        userMessage: String,
        onPartialReply: suspend (String) -> Unit,
        onToolInvoked: suspend (ToolCallUi) -> Unit,
        onStatus: suspend (Status) -> Unit = {},
    ): Outcome {
        val calls = mutableListOf<ToolCallRecord>()
        val messages = history.toMutableList()

        for (turn in 0 until maxTurns) {
            // Safety: bail out if the prompt has grown to an absurd size.
            // This prevents the retry loop from accumulating the entire
            // (possibly garbage) model output until the context is full.
            val promptSize = messages.sumOf { it.first.length + it.second.length }
            if (promptSize > 500_000) {
                com.pocketllm.server.ServerLog.log("AgentLoop.run: prompt too large ($promptSize chars), bailing out")
                return Outcome("Error: prompt grew too large, agent loop aborted", calls)
            }
            // Build the prompt with tool instructions — only the enabled ones.
            val enrichedSystem = if (systemPrompt.isBlank()) {
                registry.promptBlock(onlyIf = enabledTools())
            } else {
                systemPrompt + "\n\n" + registry.promptBlock(onlyIf = enabledTools())
            }
            if (messages.firstOrNull()?.first != "system") {
                messages.add(0, "system" to enrichedSystem)
            } else {
                messages[0] = "system" to enrichedSystem
            }

            // Generate a model response, with up to maxRetries attempts if it
            // produces something we can't parse. Each step is wrapped so any
            // unexpected exception is logged but doesn't kill the app.
            onStatus(Status.Thinking)
            val raw = try {
                generateWithRetries(messages, onPartialReply)
            } catch (e: Exception) {
                com.pocketllm.server.ServerLog.error("AgentLoop.generateWithRetries", e)
                return Outcome("Error: ${e.message ?: e.javaClass.simpleName}", calls)
            } ?: return Outcome(null, calls)
            if (raw.isBlank()) return Outcome(null, calls)

            val parsed = try {
                parseToolCall(raw)
            } catch (e: Exception) {
                com.pocketllm.server.ServerLog.error("AgentLoop.parseToolCall", e)
                null
            }
            if (parsed == null) {
                // No tool call — that's the model's final answer.
                try { onPartialReply(raw) } catch (_: Exception) {}
                return Outcome(raw, calls)
            }

            // Show the user the text that appeared before the tool call.
            val visibleBefore = raw.substringBefore(parsed.match)
            if (visibleBefore.isNotBlank()) onPartialReply(visibleBefore)

            // Execute the tool with a timeout and per-tool status.
            val tool = registry.get(parsed.call.toolName)
            val argsSummary = tool?.summarize(parsed.call.arguments) ?: parsed.call.toolName
            onStatus(Status.RunningTool(parsed.call.displayName, argsSummary))
            val toolResult = executeTool(parsed.call)

            calls += ToolCallRecord(parsed.call, toolResult)
            onToolInvoked(buildUi(parsed.call, toolResult))

            val toolResultText = renderToolResult(parsed.call.toolName, toolResult)
            messages.add("assistant" to "[called ${parsed.call.toolName} with $argsSummary]")
            messages.add("user" to "TOOL_RESULT: $toolResultText\n\nNow answer the original question using this result. If the result answers the question, give a concise final answer. Do not call another tool unless absolutely needed.")
        }
        return Outcome(null, calls)
    }

    data class Outcome(
        val finalText: String?,
        val toolCalls: List<ToolCallRecord>,
    )

    // --- generation with retry on malformed tool calls ---

    private suspend fun generateWithRetries(
        messages: MutableList<Pair<String, String>>,
        onPartialReply: suspend (String) -> Unit,
    ): String? {
        for (attempt in 0..maxRetries) {
            val prompt = engine.chatPrompt(messages) ?: return null
            val sb = StringBuilder()
            val result = engine.generate(prompt, GenParams(maxTokens = 512)) { token ->
                sb.append(token)
            }
            if (result == null) return null
            val raw = sb.toString()

            // If we got a tool call, the model succeeded — return it.
            if (parseToolCall(raw) != null) return raw

            // If we got a final answer, return it. This is the success case.
            // We only retry when the model said something but it looks broken
            // (e.g. truncated mid-JSON, garbage, or partial TOOL:).
            if (looksLikePartialToolCall(raw)) {
                if (attempt < maxRetries) {
                    val reason = diagnoseMalformed(raw)
                    messages.add("assistant" to raw)
                    messages.add("user" to "Your previous response was not a valid tool call ($reason). Please either: (1) emit a single line 'TOOL: <name> ARGS: key=value;key=value', or (2) answer the user directly without the TOOL: prefix.")
                    continue
                }
            }
            return raw
        }
        return null
    }

    private fun looksLikePartialToolCall(text: String): Boolean {
        // The model clearly tried to use a tool but failed.
        return text.contains("TOOL:", ignoreCase = true) ||
            text.contains("tool_call", ignoreCase = true) ||
            text.contains("```json")
    }

    private fun diagnoseMalformed(text: String): String {
        return when {
            text.contains("```json") -> "JSON inside a code block was not parseable"
            text.contains("tool_call", ignoreCase = true) -> "tool_call block was not parseable"
            text.contains("TOOL:", ignoreCase = true) -> "TOOL: line was missing the ARGS: part"
            else -> "format not recognized"
        }
    }

    // --- tool execution with timeout and validation ---

    private suspend fun executeTool(call: ToolCall): ToolResult {
        val tool = registry.get(call.toolName)
            ?: return ToolResult.Error("Unknown tool: ${call.toolName}")

        // Filter: only execute tools the user has enabled.
        if (call.toolName !in enabledTools()) {
            return ToolResult.Error("Tool ${call.toolName} is currently disabled. Ask the user to enable it in the agent tools menu.")
        }

        // Validate first — fail fast with a clear message.
        val validation = runCatching { tool.validate(call.arguments) }
        if (validation.isFailure) {
            return ToolResult.Error("Invalid arguments: ${validation.exceptionOrNull()?.message}")
        }

        return runCatching {
            withTimeout(toolTimeoutMs) { tool.execute(call.arguments) }
        }.getOrElse { e ->
            if (e is TimeoutCancellationException) {
                ToolResult.Error("Tool ${call.toolName} timed out after ${toolTimeoutMs / 1000}s")
            } else {
                ToolResult.Error(e.message ?: e.javaClass.simpleName)
            }
        }
    }

    // --- parsing ---

    private data class ParsedCall(val call: ToolCall, val match: String)

    private fun parseToolCall(text: String): ParsedCall? {
        // Form 1: TOOL: <name> ARGS: key=value;key=value
        parseLineToolCall(text)?.let { return it }
        // Form 2: bare JSON object with name+arguments (when output_mode permits)
        parseJsonToolCall(text)?.let { return it }
        return null
    }

    private fun parseLineToolCall(text: String): ParsedCall? {
        val regex = Regex("""(?m)^[ \t]*TOOL\s*:\s*(\w+)\s*(?:ARGS\s*:\s*([^\n]+?))?\s*$""")
        val match = regex.find(text) ?: return null
        val name = match.groupValues[1]
        val argsText = match.groupValues[2]
        val args = parseArgs(argsText)
        val displayName = registry.get(name)?.displayName ?: name
        val tool = ToolCall(name, args, displayName)
        return ParsedCall(tool, match.value)
    }

    private fun parseJsonToolCall(text: String): ParsedCall? {
        // Find first { ... } that contains "name" and "arguments"
        val open = text.indexOf('{')
        if (open < 0) return null
        // Find matching close
        var depth = 0
        var close = -1
        for (i in open until text.length) {
            when (text[i]) {
                '{' -> depth++
                '}' -> { depth--; if (depth == 0) { close = i; break } }
            }
        }
        if (close < 0) return null
        val candidate = text.substring(open, close + 1)
        val obj = runCatching { json.parseToJsonElement(candidate).jsonObject }.getOrNull() ?: return null
        val name = (obj["name"] as? JsonPrimitive)?.contentOrNull ?: return null
        val argsObj = (obj["arguments"] as? JsonObject) ?: JsonObject(emptyMap())
        if (registry.get(name) == null) return null
        val displayName = registry.get(name)?.displayName ?: name
        val tool = ToolCall(name, argsObj, displayName)
        return ParsedCall(tool, text.substring(open, close + 1))
    }

    private fun parseArgs(text: String): Map<String, JsonElement> {
        if (text.isBlank()) return JsonObject(emptyMap())
        val result = LinkedHashMap<String, JsonElement>()
        // Split on ';' but only at top-level separators. A ';' inside a value
        // is preserved. We detect a separator by checking that the char after
        // the ';' starts a valid `key=` pattern (letter or underscore, then '=').
        val parts = splitOnTopLevelSemicolons(text)
        for (pair in parts) {
            val eq = pair.indexOf('=')
            if (eq < 0) continue
            val k = pair.substring(0, eq).trim()
            val v = pair.substring(eq + 1).trim()
            if (k.isNotEmpty()) result[k] = JsonPrimitive(v)
        }
        return JsonObject(result)
    }

    /**
     * Split [text] on `;` characters that are followed by what looks like a
     * new `key=` (a letter/underscore then `=`). A `;` followed by a digit or
     * other character is kept as part of the current value. This lets values
     * like `1;2;3` survive while still separating `key1=val1;key2=val2`.
     */
    private fun splitOnTopLevelSemicolons(text: String): List<String> {
        val out = mutableListOf<String>()
        val current = StringBuilder()
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c == ';' && i + 1 < text.length) {
                // Look ahead: is the next non-space char a key-start followed by '='?
                var j = i + 1
                while (j < text.length && text[j] == ' ') j++
                if (j < text.length && (text[j].isLetter() || text[j] == '_')) {
                    var k = j + 1
                    while (k < text.length && (text[k].isLetterOrDigit() || text[k] == '_')) k++
                    if (k < text.length && text[k] == '=') {
                        // This is a real separator
                        out.add(current.toString())
                        current.clear()
                        i++
                        continue
                    }
                }
            }
            current.append(c)
            i++
        }
        out.add(current.toString())
        return out
    }

    // --- result rendering ---

    private fun renderToolResult(name: String, result: ToolResult): String = when (result) {
        is ToolResult.Text -> result.summary
        is ToolResult.Search -> buildString {
            appendLine(result.summary)
            for ((i, item) in result.results.withIndex()) {
                appendLine("${i + 1}. ${item.title} - ${item.snippet}")
                appendLine("   URL: ${item.url}")
            }
        }
        is ToolResult.Content -> "[${result.title}]\n${result.body}"
        is ToolResult.Execution -> buildString {
            appendLine("[exit ${result.exitCode}] ${result.command}")
            if (result.stdout.isNotBlank()) appendLine("--- stdout ---\n${result.stdout}")
            if (result.stderr.isNotBlank()) appendLine("--- stderr ---\n${result.stderr}")
        }
        is ToolResult.Error -> "Error: ${result.message}"
    }

    private fun buildUi(call: ToolCall, result: ToolResult): ToolCallUi = ToolCallUi(
        id = "${call.toolName}-${System.nanoTime()}",
        name = call.toolName,
        displayName = call.displayName,
        arguments = call.stringArgs(),
        resultSummary = result.summary,
        resultDetail = renderToolResult(call.toolName, result),
    )
}
