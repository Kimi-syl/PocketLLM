package com.pocketllm.agent

import com.pocketllm.util.WebSearch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

data class SearchConfig(
    val engine: String = "",
    val braveKey: String = "",
    val tavilyKey: String = "",
    val bingKey: String = "",
    val firecrawlKey: String = "",
)

class WebSearchTool(private val settings: () -> SearchConfig) : AgentTool {
    override val name = "web_search"
    override val displayName = "Web search"
    override val description = "Search the web and return titles, snippets, and URLs. Use when you need current information or facts beyond your training data."
    override val parameters = listOf(
        ToolParam(
            name = "query",
            type = "string",
            description = "The search query to run.",
        ),
        ToolParam(
            name = "max_results",
            type = "integer",
            description = "Maximum number of results to return (1-10). Defaults to 5.",
            required = false,
        ),
    )

    override suspend fun execute(args: Map<String, JsonElement>): ToolResult {
        val query = (args["query"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
        if (query.isBlank()) return ToolResult.Error("query must not be empty")

        val maxResults = (args["max_results"] as? JsonPrimitive)?.intOrNull ?: 5
        val snap = settings()
        val key = when (snap.engine) {
            "brave" -> snap.braveKey
            "tavily" -> snap.tavilyKey
            "bing" -> snap.bingKey
            "firecrawl" -> snap.firecrawlKey
            else -> ""
        }

        val results = runCatching {
            WebSearch.search(query, snap.engine, key.ifBlank { null }, maxResults.coerceIn(1, 10))
        }.getOrElse { return ToolResult.Error(it.message ?: "search failed") }

        if (results.isEmpty()) {
            return ToolResult.Search(
                query = query,
                results = emptyList(),
                summary = "No results for \"$query\"",
            )
        }
        val items = results.map { SearchItem(it.title, it.url, it.snippet) }
        return ToolResult.Search(
            query = query,
            results = items,
            summary = "${items.size} results for \"$query\"",
        )
    }
}

class CalculateTool : AgentTool {
    override val name = "calculate"
    override val displayName = "Calculator"
    override val description = "Evaluate a math expression and return the result. Supports +, -, *, /, parentheses, and functions like sqrt, sin, cos, tan, log, pow."
    override val parameters = listOf(
        ToolParam(
            name = "expression",
            type = "string",
            description = "A math expression such as \"2 + 2 * 3\" or \"sqrt(16)\".",
        ),
    )

    override suspend fun execute(args: Map<String, JsonElement>): ToolResult {
        val expr = (args["expression"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
        if (expr.isBlank()) return ToolResult.Error("expression must not be empty")

        val result = runCatching { evaluate(expr) }
            .getOrElse { return ToolResult.Error(it.message ?: "evaluation failed") }
        return ToolResult.Text("$expr = $result")
    }

    private fun evaluate(expr: String): String {
        val cleaned = expr.replace(" ", "")
        val result = shuntingYard(cleaned)
        if (result % 1.0 == 0.0 && abs(result) < 1e15) {
            return result.toLong().toString()
        }
        // Use a format that strips trailing zeros (e.g. "2.500000000" → "2.5").
        val formatted = "%.10g".format(java.util.Locale.US, result)
        return if (formatted.contains('.')) {
            formatted.trimEnd('0').trimEnd('.')
        } else formatted
    }

    private fun abs(x: Double) = if (x < 0) -x else x

    private fun shuntingYard(input: String): Double {
        val output = ArrayDeque<Double>()
        val ops = ArrayDeque<Char>()

        fun applyOp() {
            val op = ops.removeLast()
            val b = output.removeLast()
            val a = output.removeLast()
            val r = when (op) {
                '+' -> a + b
                '-' -> a - b
                '*' -> a * b
                '/' -> a / b
                '^' -> Math.pow(a, b)
                else -> error("unknown op: $op")
            }
            output.addLast(r)
        }

        var i = 0
        while (i < input.length) {
            val c = input[i]
            when {
                c.isDigit() || c == '.' -> {
                    var j = i
                    while (j < input.length && (input[j].isDigit() || input[j] == '.')) j++
                    output.addLast(input.substring(i, j).toDouble())
                    i = j
                }
                c == '(' -> {
                    ops.addLast(c); i++
                }
                c == ')' -> {
                    while (ops.last() != '(') applyOp()
                    ops.removeLast(); i++
                }
                c == '+' || c == '-' || c == '*' || c == '/' || c == '^' -> {
                    // ^ is right-associative: only pop operators with STRICTLY
                    // higher precedence. Other operators are left-associative
                    // and pop with >= precedence.
                    while (ops.isNotEmpty() && ops.last() != '(') {
                        val top = precedence(ops.last())
                        val shouldPop = if (c == '^') top > precedence(c) else top >= precedence(c)
                        if (!shouldPop) break
                        applyOp()
                    }
                    ops.addLast(c); i++
                }
                c.isLetter() -> {
                    var j = i
                    while (j < input.length && input[j].isLetter()) j++
                    val name = input.substring(i, j).lowercase()
                    if (j < input.length && input[j] == '(') {
                        ops.addLast('('); i = j + 1
                        while (i < input.length && input[i] != ')') {
                            val ch = input[i]
                            when {
                                ch.isDigit() || ch == '.' -> {
                                    var k = i
                                    while (k < input.length && (input[k].isDigit() || input[k] == '.')) k++
                                    output.addLast(input.substring(i, k).toDouble()); i = k
                                }
                                ch == ',' -> { i++ }
                                ch == '+' || ch == '-' || ch == '*' || ch == '/' || ch == '^' -> {
                                    while (ops.isNotEmpty() && ops.last() != '(' && precedence(ops.last()) >= precedence(ch)) {
                                        applyOp()
                                    }
                                    ops.addLast(ch); i++
                                }
                                else -> error("unexpected char in args: $ch")
                            }
                        }
                        // Before removing the '(', apply all operators on the
                        // stack so the function arg is the *evaluated* result,
                        // not just the last pushed value.
                        while (ops.isNotEmpty() && ops.last() != '(') applyOp()
                        ops.removeLast() // (
                        if (i >= input.length) error("unclosed function")
                        val arg = output.removeLast()
                        val r = when (name) {
                            "sqrt" -> Math.sqrt(arg)
                            "sin" -> Math.sin(arg)
                            "cos" -> Math.cos(arg)
                            "tan" -> Math.tan(arg)
                            "log" -> Math.log(arg)
                            "log10" -> Math.log10(arg)
                            "exp" -> Math.exp(arg)
                            "abs" -> abs(arg)
                            else -> error("unknown function: $name")
                        }
                        output.addLast(r)
                        i++ // past )
                    } else {
                        val constVal = when (name) {
                            "pi" -> Math.PI
                            "e" -> Math.E
                            else -> error("unknown identifier: $name")
                        }
                        output.addLast(constVal); i = j
                    }
                }
                else -> error("unexpected char: $c")
            }
        }
        while (ops.isNotEmpty()) applyOp()
        return output.last()
    }

    private fun precedence(op: Char) = when (op) {
        '+', '-' -> 1
        '*', '/' -> 2
        '^' -> 3
        else -> 0
    }
}

class DateTimeTool : AgentTool {
    override val name = "datetime"
    override val displayName = "Date & time"
    override val description = "Return the current date/time, or convert between timezones. Useful for time-sensitive questions."
    override val parameters = listOf(
        ToolParam(
            name = "action",
            type = "string",
            description = "One of: now, date, time, day_of_week, year, unix, convert. 'now' returns the full timestamp. 'convert' requires 'from_tz', 'to_tz', and optional 'datetime'.",
        ),
        ToolParam(name = "datetime", type = "string", description = "ISO 8601 datetime to convert. Required for 'convert'.", required = false),
        ToolParam(name = "from_tz", type = "string", description = "Source timezone, e.g. 'UTC' or 'America/Los_Angeles'. Defaults to system zone.", required = false),
        ToolParam(name = "to_tz", type = "string", description = "Target timezone, e.g. 'UTC'. Required for 'convert'.", required = false),
    )

    override suspend fun execute(args: Map<String, JsonElement>): ToolResult {
        val action = (args["action"] as? JsonPrimitive)?.contentOrNull?.lowercase() ?: "now"
        return when (action) {
            "now" -> ToolResult.Text(java.time.OffsetDateTime.now().toString())
            "date" -> ToolResult.Text(java.time.LocalDate.now().toString())
            "time" -> ToolResult.Text(java.time.LocalTime.now().toString())
            "day_of_week" -> ToolResult.Text(java.time.LocalDate.now().dayOfWeek.toString())
            "year" -> ToolResult.Text(java.time.LocalDate.now().year.toString())
            "unix" -> ToolResult.Text(java.time.Instant.now().epochSecond.toString())
            "convert" -> convert(args)
            else -> ToolResult.Error("unknown action: $action")
        }
    }

    private fun convert(args: Map<String, JsonElement>): ToolResult {
        val fromTz = (args["from_tz"] as? JsonPrimitive)?.contentOrNull
            ?: java.time.ZoneId.systemDefault().id
        val toTz = (args["to_tz"] as? JsonPrimitive)?.contentOrNull
            ?: return ToolResult.Error("to_tz required for convert")
        val dtStr = (args["datetime"] as? JsonPrimitive)?.contentOrNull
            ?: return ToolResult.Error("datetime required for convert")
        return runCatching {
            val source = java.time.ZoneId.of(fromTz)
            val target = java.time.ZoneId.of(toTz)
            val odt = if (dtStr.contains('T')) {
                java.time.OffsetDateTime.parse(dtStr).atZoneSameInstant(source)
            } else {
                java.time.LocalDateTime.parse(dtStr).atZone(source)
            }
            val converted = odt.withZoneSameInstant(target)
            ToolResult.Text(converted.toString())
        }.getOrElse { ToolResult.Error(it.message ?: "convert failed") }
    }
}
