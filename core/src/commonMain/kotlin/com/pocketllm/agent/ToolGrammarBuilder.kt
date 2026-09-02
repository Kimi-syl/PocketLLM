package com.pocketllm.agent

/**
 * Builds a GBNF grammar that constrains generation to exactly one valid tool
 * call line:
 *
 *   TOOL: <name> ARGS: <key>=<value>;<key>=<value>
 *
 * Only the enabled tools (and their required params) appear as alternatives,
 * so even a very small model cannot emit an unknown tool name or a missing
 * required argument. The produced line is parsed by AgentLoop's existing
 * TOOL:-line parser, so the app and CLI share one format.
 */
object ToolGrammarBuilder {

    fun build(registry: ToolRegistry, onlyIf: Set<String>? = null): String? {
        val tools = registry.all().filter { onlyIf == null || it.name in onlyIf }
        if (tools.isEmpty()) return null

        val sp = "sp"
        val sb = StringBuilder()
        sb.append("root ::= \"TOOL:\" $sp tcall sp1\n")
        sb.append("$sp ::= [ \\t]*\n")
        sb.append("sp1 ::= [ \\t\\n]*\n")
        sb.append("tcall ::= ")
        sb.append(
            tools.mapIndexed { i, _ -> "t$i" }.joinToString(" | ")
        )
        sb.append("\n")
        sb.append("value ::= [^;\\n]+\n")

        for ((i, tool) in tools.withIndex()) {
            val required = tool.parameters.filter { it.required }
            val body = if (required.isEmpty()) {
                "\"${tool.name}\""
            } else {
                val pairs = required.joinToString(" \";\" $sp ") { p ->
                    "\"${p.name}\" $sp \"=\" $sp value"
                }
                "\"${tool.name}\" $sp \"ARGS:\" $sp $pairs"
            }
            sb.append("t$i ::= $body\n")
        }
        return sb.toString()
    }
}
