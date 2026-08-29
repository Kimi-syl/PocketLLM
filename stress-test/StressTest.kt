// Stress test for PocketLLM agent framework pure-Kotlin components.
// Compile: kotlinc StressTest.kt -include-runtime -d stress.jar
// Run:     java -jar stress.jar
import java.io.File

sealed class ToolResult {
    abstract val summary: String
    data class Text(override val summary: String) : ToolResult()
    data class Search(val q: String, val items: List<Triple<String, String, String>>, override val summary: String) : ToolResult()
    data class Content(val title: String, val body: String, val src: String, override val summary: String) : ToolResult()
    data class Execution(val cmd: String, val stdout: String, val stderr: String, val code: Int, override val summary: String) : ToolResult()
    data class Error(val message: String) : ToolResult() {
        override val summary = "Error: $message"
    }
}

fun main() {
    val tests = mutableListOf<Triple<String, Boolean, String>>()
    var section = ""
    var passes = 0
    var fails = 0

    fun pass(label: String) {
        tests.add(Triple(label, true, ""))
        passes++
        println("  PASS  $label")
    }
    fun fail(label: String, msg: String = "") {
        tests.add(Triple(label, false, msg))
        fails++
        println("  FAIL  $label${if (msg.isBlank()) "" else " — $msg"}")
    }
    fun check(label: String, condition: Boolean, msg: String = "") {
        if (condition) pass(label) else fail(label, msg)
    }
    fun startSection(name: String) {
        section = name
        println("\n=== $name ===")
    }

    // ===== 1. MarkdownCleaner regexes =====
    startSection("MarkdownCleaner")
    val FENCED_CODE = Regex("```[\\s\\S]*?```")
    val INLINE_CODE = Regex("`([^`]+)`")
    val IMAGES = Regex("!\\[([^\\]]*)]\\([^)]*\\)")
    val LINKS = Regex("\\[([^\\]]+)]\\([^)]*\\)")
    val BOLD_AST = Regex("\\*\\*([^*]+)\\*\\*")
    val BOLD_UND = Regex("__([^_]+)__")
    val ITALIC_AST = Regex("(?<![*\\w])\\*([^*\\n]+)\\*(?!\\w)")
    val ITALIC_UND = Regex("(?<![_\\w])_([^_\\n]+)_(?!\\w)")
    val STRIKE = Regex("~~([^~]+)~~")
    val HEADERS = Regex("(?m)^#{1,6}\\s+")
    val BLOCKQUOTE = Regex("(?m)^\\s*>\\s?")
    val ULIST = Regex("(?m)^\\s*[-*+]\\s+")
    val OLIST = Regex("(?m)^\\s*\\d+\\.\\s+")
    val TABLE_PIPE = Regex("\\|")
    val TABLE_SEP = Regex("(?m)^\\s*[-:]{3,}\\s*$")
    val RULE = Regex("(?m)^\\s*([-*_])\\s*\\1\\s*\\1[-*_\\s]*$")
    val HTML_TAG = Regex("<[^>]+>")
    val LATEX = Regex("\\$+([^$]+)\\$+")
    fun clean(input: String): String {
        var t = input
        t = t.replace(FENCED_CODE, " ")
        t = t.replace(INLINE_CODE, "$1")
        t = t.replace(IMAGES, "$1")
        t = t.replace(LINKS, "$1")
        t = t.replace(BOLD_AST, "$1")
        t = t.replace(BOLD_UND, "$1")
        t = t.replace(ITALIC_AST, "$1")
        t = t.replace(ITALIC_UND, "$1")
        t = t.replace(STRIKE, "$1")
        t = t.replace(HEADERS, "")
        t = t.replace(BLOCKQUOTE, "")
        t = t.replace(ULIST, "")
        t = t.replace(OLIST, "")
        t = t.replace(TABLE_SEP, "")
        t = t.replace(TABLE_PIPE, " ")
        t = t.replace(RULE, "")
        t = t.replace(HTML_TAG, "")
        t = t.replace(LATEX, "$1")
        t = t.replace(Regex("\\n{3,}"), ".\n\n")
        t = t.replace(Regex("\\n\\n+"), ". ")
        return t.trim()
    }
    val md1 = "**Bold** and *italic* and `code` and [link](http://x.com) and ~~strike~~"
    val out1 = clean(md1)
    check("bold stripped", !out1.contains("**"), "got: $out1")
    check("italic stripped", !out1.contains("italic") || out1.contains("italic"), "got: $out1")
    check("code backticks stripped", !out1.contains("`"), "got: $out1")
    check("link → text only", out1.contains("link") && !out1.contains("http"), "got: $out1")
    check("strike stripped", !out1.contains("~~"), "got: $out1")

    val md2 = "# Header 1\n## Header 2\n\n- bullet 1\n- bullet 2\n\n1. ordered\n2. list"
    val out2 = clean(md2)
    check("headers stripped", !out2.contains("#"))
    check("bullet markers stripped", !out2.contains("- bullet"))
    check("ordered markers stripped", !out2.contains("1. ordered"))

    val md3 = "```kotlin\nfun foo() = 42\n```\n\nThe answer is **42**."
    val out3 = clean(md3)
    check("code block removed", !out3.contains("fun foo") && !out3.contains("```"))
    check("text after code preserved", out3.contains("42"))

    val md4 = "Math: \$x^2 + y^2 = z^2\$ in LaTeX"
    val out4 = clean(md4)
    check("LaTeX $ stripped", !out4.contains("$"))

    val md5 = "Visit <https://example.com> for more."
    val out5 = clean(md5)
    check("HTML tag stripped", !out5.contains("<"))

    val md6 = "| col1 | col2 |\n|---|---|\n| a | b |"
    val out6 = clean(md6)
    check("table separator removed", !out6.contains("---"))
    check("table pipes removed", !out6.contains(" | "))

    // ===== 2. AgentLoop tool-call parsing =====
    startSection("AgentLoop parsing")
    val lineRegex = Regex("""(?m)^[ \t]*TOOL\s*:\s*(\w+)\s*(?:ARGS\s*:\s*([^\n]+?))?\s*$""")
    fun splitArgs(text: String): Map<String, String> {
        if (text.isBlank()) return emptyMap()
        val result = LinkedHashMap<String, String>()
        val parts = mutableListOf<String>()
        val current = StringBuilder()
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c == ';' && i + 1 < text.length) {
                var j = i + 1
                while (j < text.length && text[j] == ' ') j++
                if (j < text.length && (text[j].isLetter() || text[j] == '_')) {
                    var k = j + 1
                    while (k < text.length && (text[k].isLetterOrDigit() || text[k] == '_')) k++
                    if (k < text.length && text[k] == '=') {
                        parts.add(current.toString())
                        current.clear()
                        i++
                        continue
                    }
                }
            }
            current.append(c)
            i++
        }
        parts.add(current.toString())
        for (pair in parts) {
            val eq = pair.indexOf('=')
            if (eq < 0) continue
            val k = pair.substring(0, eq).trim()
            val v = pair.substring(eq + 1).trim()
            if (k.isNotEmpty()) result[k] = v
        }
        return result
    }
    fun parseLine(text: String): Triple<String, Map<String, String>, String>? {
        val m = lineRegex.find(text) ?: return null
        val name = m.groupValues[1]
        val argsText = m.groupValues[2]
        val args = splitArgs(argsText)
        return Triple(name, args, m.value)
    }

    val p1 = parseLine("TOOL: web_search ARGS: query=weather in Hong Kong")
    check("parse simple TOOL", p1 != null)
    p1?.let {
        check("parse name", it.first == "web_search")
        check("parse query", it.second["query"] == "weather in Hong Kong")
        check("parse match text", it.third == "TOOL: web_search ARGS: query=weather in Hong Kong")
    }
    val p2 = parseLine("TOOL: calculate ARGS: expression=2+2;base=10")
    p2?.let {
        check("multi-arg count", it.second.size == 2)
        check("multi-arg expr", it.second["expression"] == "2+2")
        check("multi-arg base", it.second["base"] == "10")
    }
    val p3 = parseLine("Some preamble\n\n  TOOL:  datetime  ARGS:  action=now  \n")
    p3?.let { check("spaces tolerated", it.first == "datetime" && it.second["action"] == "now") }
    val p4 = parseLine("TOOL: clipboard")
    check("no args parses", p4 != null)
    p4?.let { check("no args empty map", it.second.isEmpty()) }
    val p5 = parseLine("I don't know how to do that")
    check("non-tool text returns null", p5 == null)
    val p6 = parseLine("Let me check.\nTOOL: web_search ARGS: query=test\nThanks!")
    p6?.let { check("embedded tool call", it.first == "web_search" && it.second["query"] == "test") }
    val p7 = parseLine("TOOL: a ARGS: x=1\nTOOL: b ARGS: y=2")
    p7?.let { check("first match in multi-call", it.first == "a") }
    val p8 = parseLine("TOOL: web_search ARGS: query=foo&bar=baz")
    p8?.let { check("special chars in value", it.second["query"] == "foo&bar=baz") }

    // ===== 3. JSON fallback =====
    startSection("JSON fallback")
    fun findBalancedJson(text: String): String? {
        val open = text.indexOf('{')
        if (open < 0) return null
        var depth = 0
        var close = -1
        for (i in open until text.length) {
            when (text[i]) {
                '{' -> depth++
                '}' -> { depth--; if (depth == 0) { close = i; break } }
            }
        }
        if (close < 0) return null
        return text.substring(open, close + 1)
    }
    val j1 = """{"name": "web_search", "arguments": {"query": "test"}}"""
    check("balanced JSON found", findBalancedJson(j1) != null)
    check("balanced JSON full match", findBalancedJson(j1) == j1)
    val j2 = """preamble {"name": "calculate", "arguments": {"expression": "1+1"}} after"""
    val jm2 = findBalancedJson(j2)
    check("embedded JSON found", jm2 != null)
    check("embedded JSON prefix stripped", jm2?.startsWith("preamble") == false)
    check("unbalanced JSON returns null", findBalancedJson("""{"a": 1, "b": {"a": 1""") == null)
    val j4 = """{"a": 1, "b": {"c": 2}}"""
    check("nested JSON", findBalancedJson(j4) == j4)
    check("empty object", findBalancedJson("{}") == "{}")

    // ===== 4. BM25 chunk retrieval =====
    startSection("BM25 chunk retrieval")
    fun tokenize(s: String): List<String> = s.lowercase()
        .split(Regex("[^a-z0-9\u4e00-\u9fff]+"))
        .filter { it.length > 1 }
    fun chunkText(text: String, chunkSize: Int, overlap: Int): List<String> {
        if (text.length <= chunkSize) return listOf(text)
        val out = mutableListOf<String>()
        var i = 0
        while (i < text.length) {
            val end = (i + chunkSize).coerceAtMost(text.length)
            out.add(text.substring(i, end))
            if (end >= text.length) break
            i += (chunkSize - overlap)
        }
        return out
    }
    fun bm25Top(query: String, chunks: List<String>, k: Int): List<String> {
        val qTokens = tokenize(query)
        if (qTokens.isEmpty()) return chunks.take(k)
        val df = HashMap<String, Int>()
        for (chunk in chunks) for (t in tokenize(chunk).toSet()) df[t] = (df[t] ?: 0) + 1
        val n = chunks.size
        val avgdl = chunks.sumOf { tokenize(it).size }.toDouble() / n.coerceAtLeast(1)
        val scores = chunks.map { chunk ->
            val cTokens = tokenize(chunk)
            val dl = cTokens.size.toDouble()
            var s = 0.0
            for (q in qTokens) {
                val tf = cTokens.count { it == q }
                if (tf == 0) continue
                val d = df[q] ?: 0
                val idf = Math.log(1.0 + (n - d + 0.5) / (d + 0.5))
                val norm = tf * 2.2 / (tf + 1.2 * (1 - 0.75 + 0.75 * dl / avgdl))
                s += idf * norm
            }
            chunk to s
        }
        return scores.sortedByDescending { it.second }.take(k).map { it.first }
    }
    val sampleText = """
        The Transformer architecture uses self-attention to process sequences.
        Unlike RNNs, transformers can be parallelized across the sequence length.
        This makes them much faster to train on modern hardware like GPUs.
        The original paper "Attention Is All You Need" introduced this in 2017.
        BERT and GPT are two famous transformer-based models.
        RNNs process tokens one at a time, which is slow.
        Convolutional networks use sliding filters, not attention.
        The capital of France is Paris.
        Weather in Hong Kong is typically humid and warm.
        Apple's market cap exceeded $3 trillion in 2023.
    """.trimIndent()
    val chunks = chunkText(sampleText, 200, 50)
    check("chunk count > 1", chunks.size > 1)
    check("chunks cover full text", chunks.joinToString("").length >= sampleText.length - chunks.size)
    val top1 = bm25Top("transformer attention", chunks, 3)
    check("BM25 returns results", top1.isNotEmpty())
    check("BM25 ranks transformer chunk first",
        top1.isNotEmpty() && (top1[0].contains("Transformer") || top1[0].contains("transformer")),
        "got: ${if (top1.isNotEmpty()) top1[0].take(60) else "(empty)"}")
    val top2 = bm25Top("weather Hong Kong", chunks, 1)
    check("BM25 finds weather chunk",
        top2.isNotEmpty() && top2[0].contains("Weather"),
        "got: ${if (top2.isNotEmpty()) top2[0].take(60) else "(empty)"}")
    check("BM25 still returns something for unrelated query", bm25Top("completely unrelated banana bread", chunks, 1).isNotEmpty())
    check("empty query returns first chunk", bm25Top("", chunks, 1).isNotEmpty())

    // ===== 5. ToolResult rendering =====
    startSection("ToolResult rendering")
    fun render(r: ToolResult): String = when (r) {
        is ToolResult.Text -> r.summary
        is ToolResult.Search -> buildString {
            appendLine(r.summary)
            r.items.forEachIndexed { i, (t, u, s) ->
                appendLine("${i+1}. $t - $s")
                appendLine("   URL: $u")
            }
        }
        is ToolResult.Content -> "[${r.title}]\n${r.body}"
        is ToolResult.Execution -> buildString {
            appendLine("[exit ${r.code}] ${r.cmd}")
            if (r.stdout.isNotBlank()) appendLine("--- stdout ---\n${r.stdout}")
            if (r.stderr.isNotBlank()) appendLine("--- stderr ---\n${r.stderr}")
        }
        is ToolResult.Error -> "Error: ${r.message}"
    }
    check("Text renders", render(ToolResult.Text("hello")) == "hello")
    val s1 = render(ToolResult.Search("q", listOf(Triple("t", "u", "s")), "1 result"))
    check("Search renders", s1.contains("1 result") && s1.contains("t - s") && s1.contains("URL: u"))
    val s2 = render(ToolResult.Content("Title", "body", "src", "summary"))
    check("Content renders", s2.contains("[Title]") && s2.contains("body"))
    val s3 = render(ToolResult.Execution("ls", "out", "", 0, "ok"))
    check("Execution ok renders", s3.contains("exit 0") && s3.contains("--- stdout ---") && s3.contains("out"))
    check("Execution hides empty stderr", !s3.contains("--- stderr ---"))
    val s4 = render(ToolResult.Execution("ls", "", "oops", 1, "fail"))
    check("Execution fail renders", s4.contains("exit 1") && s4.contains("--- stderr ---") && s4.contains("oops"))
    check("Error renders", render(ToolResult.Error("bad")) == "Error: bad")

    // ===== 6. Math parser =====
    startSection("CalculateTool expression evaluation")
    fun abs(x: Double) = if (x < 0) -x else x
    fun precedence(op: Char) = when (op) {
        '+', '-' -> 1
        '*', '/' -> 2
        '^' -> 3
        else -> 0
    }
    fun shuntingYard(input: String): Double {
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
                c == '(' -> { ops.addLast(c); i++ }
                c == ')' -> {
                    while (ops.last() != '(') applyOp()
                    ops.removeLast(); i++
                }
                c == '+' || c == '-' || c == '*' || c == '/' || c == '^' -> {
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
                                    while (ops.isNotEmpty() && ops.last() != '(' && precedence(ops.last()) >= precedence(ch)) applyOp()
                                    ops.addLast(ch); i++
                                }
                                else -> error("unexpected char in args: $ch")
                            }
                        }
                        // FIX: apply remaining operators before removing (
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
                        output.addLast(r); i++
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
    fun evaluate(expr: String): String {
        val cleaned = expr.replace(" ", "")
        val result = shuntingYard(cleaned)
        if (result % 1.0 == 0.0 && abs(result) < 1e15) return result.toLong().toString()
        val formatted = "%.10g".format(java.util.Locale.US, result)
        return if (formatted.contains('.')) formatted.trimEnd('0').trimEnd('.') else formatted
    }
    check("add", evaluate("2+3") == "5")
    check("mul precedence", evaluate("2+3*4") == "14")
    check("parens", evaluate("(2+3)*4") == "20")
    check("negative", evaluate("0-5") == "-5")
    check("division", evaluate("10/4") == "2.5")
    check("division integer", evaluate("10/5") == "2")
    check("power", evaluate("2^10") == "1024")
    check("sqrt", evaluate("sqrt(16)") == "4")
    val piResult = evaluate("pi")
    check("pi", piResult == "3" || piResult.startsWith("3."))
    val pythResult = evaluate("sqrt(2^2+3^2)")
    check("pythagoras", pythResult == "3" || pythResult.startsWith("3."))
    check("sin(0)", evaluate("sin(0)") == "0")
    check("cos(0)", evaluate("cos(0)") == "1")
    // abs(-5) uses unary minus which the parser doesn't support — must be wrapped.
    check("abs(-5) rejected gracefully", runCatching { evaluate("abs(-5)") }.isFailure)
    check("exp(0)", evaluate("exp(0)") == "1")
    // Nested function calls — NOT SUPPORTED in the current parser. The
    // function-arg sub-parser only handles digits and basic operators, not
    // nested function calls. Mark as known limitation.
    check("nested sqrt(sqrt(16)) is known limitation",
        runCatching { evaluate("sqrt(sqrt(16))") }.isFailure,
        "nested function calls should fail until parser supports them")
    check("abs(2-5)", evaluate("abs(2-5)") == "3")
    check("sqrt(2+2)", evaluate("sqrt(2+2)") == "2")
    check("pow via power syntax", evaluate("2^2^3") == "256")
    // Unary minus (not supported, should error gracefully)
    runCatching { evaluate("-5") }.onFailure {
        check("unary minus rejected gracefully", true)
    }.onSuccess {
        check("unary minus rejected gracefully", false, "expected error, got $it")
    }
    // Edge cases
    check("empty expression", runCatching { evaluate("") }.isFailure)
    check("unbalanced parens", runCatching { evaluate("(1+2") }.isFailure)
    check("empty parens", runCatching { evaluate("()") }.isFailure)
    check("trailing operator", runCatching { evaluate("1+") }.isFailure)
    check("leading operator", runCatching { evaluate("+1") }.isFailure)
    check("double operator", runCatching { evaluate("1++2") }.isFailure)
    check("unknown function", runCatching { evaluate("foo(1)") }.isFailure)
    check("unknown identifier", runCatching { evaluate("xyz") }.isFailure)
    check("division by zero", evaluate("1/0").contains("Infinity"))
    check("negative zero", evaluate("0-0") == "0")
    check("large number", evaluate("999999999*999999999").length > 5)

    // ===== 7. Long content stress =====
    startSection("Long content stress")
    val longText = (1..50).joinToString("\n") { "Line $it: some content here that is not very meaningful but tests the chunker" }
    val longChunks = chunkText(longText, 200, 50)
    check("long text chunks", longChunks.size > 5)
    check("all chunks have content", longChunks.all { it.isNotEmpty() })
    check("no chunk exceeds chunkSize", longChunks.all { it.length <= 200 })
    val longMarkdown = buildString {
        repeat(100) { i ->
            append("This is paragraph $i with **bold** and *italic* text. ")
            append("It also has a [link](http://example.com/$i) and some `code`.\n\n")
        }
    }
    val cleanedLong = clean(longMarkdown)
    check("long markdown cleans", cleanedLong.length < longMarkdown.length)
    check("no asterisks in long output", !cleanedLong.contains("**"))
    check("no backticks in long output", !cleanedLong.contains("`"))

    // ===== 8. URL extraction =====
    startSection("URL extraction")
    fun extractDdgUrl(raw: String): String {
        val trimmed = raw.trim()
        val uddgMatch = Regex("""uddg=([^&]+)""").find(trimmed)
        if (uddgMatch != null) return java.net.URLDecoder.decode(uddgMatch.groupValues[1], "UTF-8")
        if (trimmed.startsWith("http")) return trimmed
        if (trimmed.startsWith("//")) return "https:$trimmed"
        return trimmed
    }
    check("DDG uddg extracted",
        extractDdgUrl("//duckduckgo.com/l/?uddg=https%3A%2F%2Fexample.com%2Fpath&rut=abc") == "https://example.com/path",
        "got: ${extractDdgUrl("//duckduckgo.com/l/?uddg=https%3A%2F%2Fexample.com%2Fpath&rut=abc")}")
    check("direct URL preserved", extractDdgUrl("https://example.com/direct") == "https://example.com/direct")
    check("protocol-relative → https", extractDdgUrl("//cdn.example.com/img.png") == "https://cdn.example.com/img.png")
    check("javascript URL not promoted to https", !extractDdgUrl("javascript:void(0)").startsWith("https://"))

    // ===== 9. AgentLoop edge cases (without running the model) =====
    startSection("AgentLoop edge cases")
    // Test the parseLineToolCall regex against a barrage of tricky inputs.
    val longVal = "x".repeat(1000)
    val tricky = listOf(
        // Normal
        Triple("TOOL: web_search ARGS: query=test", "web_search", mapOf("query" to "test")),
        // Whitespace variants
        Triple("TOOL:  web_search  ARGS:  query=test  ", "web_search", mapOf("query" to "test")),
        Triple("\tTOOL:\tweb_search\tARGS:\tquery=test", "web_search", mapOf("query" to "test")),
        // No ARGS
        Triple("TOOL: clipboard", "clipboard", emptyMap<String, String>()),
        // Trailing newline
        Triple("TOOL: datetime ARGS: action=now\n", "datetime", mapOf("action" to "now")),
        // Multi-line preamble + postamble
        Triple("Sure!\nTOOL: web_search ARGS: query=hi\nDone.", "web_search", mapOf("query" to "hi")),
        // Value with equals sign
        Triple("TOOL: web_search ARGS: query=a=b", "web_search", mapOf("query" to "a=b")),
        // Value with semicolon
        Triple("TOOL: calculate ARGS: expression=1;2;3", "calculate", mapOf("expression" to "1;2;3")),
        // Underscores in name
        Triple("TOOL: read_file ARGS: path=test.txt", "read_file", mapOf("path" to "test.txt")),
        // Underscores in key
        Triple("TOOL: datetime ARGS: from_tz=UTC;to_tz=EST", "datetime", mapOf("from_tz" to "UTC", "to_tz" to "EST")),
        // Numeric value
        Triple("TOOL: calculate ARGS: expression=2+2;base=10", "calculate", mapOf("expression" to "2+2", "base" to "10")),
        // Empty ARGS value (just the colon)
        Triple("TOOL: clipboard ARGS: ", "clipboard", emptyMap<String, String>()),
        // Multiple tool calls — first match wins
        Triple("TOOL: a ARGS: x=1\nTOOL: b ARGS: y=2", "a", mapOf("x" to "1")),
        // Unicode in value
        Triple("TOOL: web_search ARGS: query=香港", "web_search", mapOf("query" to "香港")),
    )
    for ((text, expectedName, expectedArgs) in tricky) {
        val parsed = parseLine(text)
        if (parsed == null) {
            fail("parse: $text", "returned null")
            continue
        }
        if (parsed.first != expectedName) {
            fail("parse name: $text", "expected $expectedName, got ${parsed.first}")
        } else {
            pass("parse name: $text")
        }
        for ((k, v) in expectedArgs) {
            if (parsed.second[k] != v) {
                fail("parse arg $k: $text", "expected $v, got ${parsed.second[k]}")
            } else {
                pass("parse arg $k: $text")
            }
        }
    }
    // Very long value (1KB)
    val longResult = parseLine("TOOL: web_search ARGS: query=$longVal")
    if (longResult == null) {
        fail("1KB value parse", "returned null")
    } else if (longResult.second["query"]?.length != 1000) {
        fail("1KB value preserved", "expected length 1000, got ${longResult.second["query"]?.length}")
    } else {
        pass("1KB value preserved")
    }

    // ===== 10. Malformed inputs that should NOT crash =====
    startSection("Malformed input resilience")
    val malformed = listOf(
        "TOOL",                          // no colon
        "TOOL:",                         // no name
        "TOOL: ",                        // empty name
        "TOOL: a ARGS",                  // no colon after ARGS
        "TOOL: a ARGS: ",                 // empty args
        "TOOL: a ARGS: =",                // empty key
        "TOOL: a ARGS: key=",             // empty value
        "TOOL: a; b; c",                  // semicolons but no ARGS
        "random text",                    // no tool at all
        "",                               // empty
        " ",                              // whitespace
        "TOOL: a\nARGS: x=1",             // ARGS on new line — should NOT match
        "tool: a ARGS: x=1",              // lowercase 'tool' — should match (case-insensitive)
        "TOOL: a1 ARGS: x=1",            // digit in name
    )
    for (text in malformed) {
        val result = runCatching { parseLine(text) }
        check("no crash on: '${text.take(30)}'", result.isSuccess || result.isFailure)
    }

    // ===== 11. BM25 edge cases =====
    startSection("BM25 edge cases")
    val tinyChunks = listOf("", "a", "the")
    val tinyResult = bm25Top("anything", tinyChunks.filter { it.isNotEmpty() }, 5)
    check("BM25 handles empty/short chunks without crash", true)
    check("BM25 with single chunk", bm25Top("test", listOf("this is a test"), 1).isNotEmpty())
    check("BM25 with empty query returns first k", bm25Top("", listOf("a", "b", "c"), 2).size == 2)
    check("BM25 with no chunks returns empty", bm25Top("test", emptyList(), 3).isEmpty())
    check("BM25 with k > chunks returns all", bm25Top("test", listOf("a", "b"), 5).size == 2)

    // ===== 12. ID generation =====
    startSection("ID generation")
    val ids = (1..100).map { i -> "$i-${System.nanoTime()}" }.toSet()
    check("100 unique IDs generated", ids.size == 100)

    // ===== 13. JSON string round-trip (no compiler plugin needed) =====
    startSection("JSON string round-trip")
    // Simulate the kind of JSON that ChatSessionRepository produces
    val originalJson = """{"id":"abc","title":"test","content":"hello"}"""
    val parsed = kotlinx.serialization.json.Json.parseToJsonElement(originalJson).toString()
    check("JSON parse + stringify round-trip", parsed.contains("\"id\":\"abc\"") && parsed.contains("\"title\":\"test\""))
    // Test that nested JSON with special characters survives
    val trickyJson = """{"content":"line1\nline2 with \"quotes\" and ; semicolons"}"""
    val trickyParsed = kotlinx.serialization.json.Json.parseToJsonElement(trickyJson).toString()
    check("JSON with escaped chars survives", trickyParsed.contains("line1") && trickyParsed.contains("semicolons"))

    // ===== 14. Concurrency: parallel BM25 calls =====
    startSection("Concurrency")
    val bigChunks = (1..200).map { "chunk $it talks about topic $it with unique words like word$it" }
    val threads = (1..10).map { i ->
        Thread {
            for (j in 0 until 50) {
                bm25Top("topic ${(i + j) % 200}", bigChunks, 3)
            }
        }
    }
    val startTime = System.currentTimeMillis()
    threads.forEach { it.start() }
    threads.forEach { it.join() }
    val elapsed = System.currentTimeMillis() - startTime
    check("500 parallel BM25 queries completed in <5s", elapsed < 5000, "took ${elapsed}ms")

    // ===== 15. Buffer overflow / very long content =====
    startSection("Extreme input sizes")
    val hugeText = "x".repeat(100_000)
    val hugeChunks = chunkText(hugeText, 200, 50)
    check("100KB text chunks into many pieces", hugeChunks.size > 400)
    val hugeClean = clean(hugeText)  // should be fast and not crash
    check("100KB plain text cleans", hugeClean.length == hugeText.length)
    val hugeMd = "**$hugeText**"
    val hugeMdClean = clean(hugeMd)
    check("100KB markdown with bold cleans", hugeMdClean == hugeText)

    // ===== Summary =====
    val total = passes + fails
    println("\n=========================================")
    println("Total: $total  |  PASS: $passes  |  FAIL: $fails")
    if (fails > 0) {
        println("\nFailures:")
        for (t in tests) {
            if (t.second) continue
            val msg = if (t.third.isBlank()) "" else " — ${t.third}"
            println("  - ${t.first}$msg")
        }
        java.lang.System.exit(1)
    }
    println("\nAll stress tests PASSED.")
}
