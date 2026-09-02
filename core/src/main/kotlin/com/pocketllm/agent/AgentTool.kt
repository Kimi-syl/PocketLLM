package com.pocketllm.agent

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Defines a callable tool the agent can invoke.
 * Mirrors the OpenAI function-calling schema so prompts stay portable.
 */
interface AgentTool {
    val name: String
    val description: String
    val parameters: List<ToolParam>
    val displayName: String get() = name

    /** Return a friendly one-liner describing the call, e.g. "search: weather today" */
    fun summarize(args: Map<String, JsonElement>): String = "$name(${args.entries.joinToString { (k, v) -> "$k=${(v as? JsonPrimitive)?.contentOrNull ?: v}" }})"

    /** Validate args before execution. Throw [IllegalArgumentException] for bad input. */
    fun validate(args: Map<String, JsonElement>) {
        for (p in parameters) {
            if (p.required && (args[p.name] == null || (args[p.name] as? JsonPrimitive)?.contentOrNull.isNullOrBlank())) {
                error("missing required argument: ${p.name}")
            }
        }
    }

    suspend fun execute(args: Map<String, JsonElement>): ToolResult
}

@Serializable
data class ToolParam(
    val name: String,
    val type: String,
    val description: String,
    val required: Boolean = true,
)

sealed interface ToolResult {
    val summary: String

    data class Text(override val summary: String) : ToolResult
    data class Search(
        val query: String,
        val results: List<SearchItem>,
        override val summary: String,
    ) : ToolResult

    /** Long-form content (web page, file contents, code output) shown in the card's expanded view. */
    data class Content(
        val title: String,
        val body: String,
        val source: String = "",
        override val summary: String,
    ) : ToolResult

    /** Command execution result. */
    data class Execution(
        val command: String,
        val stdout: String,
        val stderr: String,
        val exitCode: Int,
        override val summary: String,
    ) : ToolResult

    data class Error(val message: String) : ToolResult {
        override val summary = "Error: $message"
    }
}

@Serializable
data class SearchItem(
    val title: String,
    val url: String,
    val snippet: String,
)

data class ToolCall(
    val toolName: String,
    val arguments: Map<String, JsonElement>,
    val displayName: String = toolName,
) {
    /** Flattened string map for UI display (e.g. arguments in tool call cards). */
    fun stringArgs(): Map<String, String> = arguments.mapValues { (_, v) ->
        (v as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull ?: v.toString()
    }
}

data class ToolCallRecord(
    val call: ToolCall,
    val result: ToolResult,
)

/**
 * A tool that the model has already invoked during a chat turn.
 * Stored in the chat message list so the UI can render a collapsible card.
 */
data class ToolCallUi(
    val id: String,
    val name: String,
    val displayName: String,
    val arguments: Map<String, String>,
    val resultSummary: String,
    val resultDetail: String,
)

/**
 * Registry of available tools. Keeps the agent loop decoupled from concrete
 * implementations — new tools are added by calling [register].
 */
class ToolRegistry {
    private val tools = mutableMapOf<String, AgentTool>()

    fun register(tool: AgentTool) {
        tools[tool.name] = tool
    }

    fun get(name: String): AgentTool? = tools[name]
    fun all(): List<AgentTool> = tools.values.toList()
    fun names(): Set<String> = tools.keys.toSet()

    /**
     * Build a prompt-friendly block describing every available tool.
     * The format is intentionally simple: one line per tool, with a clear
     * invocation syntax. Small local models have a much easier time emitting
     * this than JSON inside XML tags.
     *
     * @param onlyIf if provided, only tools whose name is in this set are
     *   included in the prompt. The loop passes the user's enabled set here
     *   so the model only sees tools it can actually call.
     */
    fun promptBlock(onlyIf: Set<String>? = null, withExamples: Boolean = true): String = buildString {
        val visible = tools.values.filter { onlyIf == null || it.name in onlyIf }
        appendLine("You may use these tools when the user asks a question that needs fresh or external information.")
        appendLine("IMPORTANT: Only use a tool when the question clearly requires it. For casual conversation, just answer normally.")
        appendLine()
        appendLine("To call a tool, output EXACTLY ONE LINE in this format on its own line:")
        appendLine("  TOOL: <name> ARGS: <key>=<value>;<key>=<value>")
        appendLine("Then stop. Do not write any other text. You will be given the tool's result and can then answer the user.")
        appendLine()
        appendLine("Always use the actual values from the user's question — never reuse values from an example.")
        if (withExamples) {
            appendLine("Worked examples:")
            appendLine("  User: What's the weather in Hong Kong right now?")
            appendLine("  Assistant: TOOL: web_search ARGS: query=weather in Hong Kong right now")
            appendLine()
            appendLine("  User: What's 2+2*3?")
            appendLine("  Assistant: TOOL: calculate ARGS: expression=2+2*3")
            appendLine()
            appendLine("  User: What day is it?")
            appendLine("  Assistant: TOOL: datetime ARGS: action=now")
            appendLine()
            appendLine("  User: Read https://example.com/article and summarize it.")
            appendLine("  Assistant: TOOL: read_url ARGS: url_or_query=https://example.com/article")
            appendLine()
            appendLine("  User: List files in my sandbox.")
            appendLine("  Assistant: TOOL: run_code ARGS: command=ls -la")
            appendLine()
        }
        appendLine("When you have the tool's result, answer the user in plain language. If you don't need a tool, just answer without using the TOOL: prefix.")
        appendLine()
        appendLine("Available tools:")
        for (tool in visible) {
            appendLine("- ${tool.name} (${tool.displayName}): ${tool.description}")
            for (param in tool.parameters) {
                val req = if (param.required) "required" else "optional"
                appendLine("    ${param.name} (${param.type}, $req): ${param.description}")
            }
        }
    }
}
