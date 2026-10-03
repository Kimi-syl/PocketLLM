package com.pocketllm.mcp

import com.pocketllm.server.ServerLog
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Minimal Model Context Protocol client (streamable HTTP transport).
 *
 * Speaks JSON-RPC 2.0 to an MCP server: `initialize` (capturing the session
 * id the server hands back), `tools/list` and `tools/call`. A server may answer
 * either with a plain JSON body or with an SSE stream (`text/event-stream`);
 * both are handled, because the spec allows either per request.
 *
 * The client is deliberately small: PocketLLM only needs tools, and the tool
 * list is offered to the local model as callable actions. Every failure is a
 * [Result] so the UI can show the real reason (unreachable, 401, bad JSON).
 */
class McpClient(
    private val endpoint: String,
    private val token: String = "",
) {

    data class Tool(
        val name: String,
        val description: String,
        val inputSchema: String,
    )

    private var nextId = 1
    private var sessionId: String? = null
    private var initialized = false

    /** Connects and lists the server's tools; also validates the endpoint. */
    suspend fun tools(): Result<List<Tool>> = withContext(Dispatchers.IO) {
        try {
            ensureInitialized()
            val result = rpc("tools/list", JSONObject())
            val arr = result.optJSONArray("tools") ?: JSONArray()
            val out = ArrayList<Tool>(arr.length())
            for (i in 0 until arr.length()) {
                val t = arr.optJSONObject(i) ?: continue
                out += Tool(
                    name = t.optString("name"),
                    description = t.optString("description"),
                    inputSchema = t.optJSONObject("inputSchema")?.toString() ?: "{}",
                )
            }
            Result.success(out)
        } catch (t: Throwable) {
            ServerLog.error("McpClient", t)
            Result.failure(t)
        }
    }

    /** Invokes a tool and returns the concatenated text content. */
    suspend fun call(name: String, arguments: JSONObject): Result<String> =
        withContext(Dispatchers.IO) {
            try {
                ensureInitialized()
                val params = JSONObject()
                    .put("name", name)
                    .put("arguments", arguments)
                val result = rpc("tools/call", params)
                val content = result.optJSONArray("content")
                if (content == null) {
                    // Some servers return a bare object; fall back to raw text.
                    return@withContext Result.success(result.toString())
                }
                val sb = StringBuilder()
                for (i in 0 until content.length()) {
                    val part = content.optJSONObject(i) ?: continue
                    if (part.optString("type") == "text") {
                        if (sb.isNotEmpty()) sb.append('\n')
                        sb.append(part.optString("text"))
                    }
                }
                Result.success(sb.toString().ifBlank { result.toString() })
            } catch (t: Throwable) {
                ServerLog.error("McpClient", t)
                Result.failure(t)
            }
        }

    // --- JSON-RPC plumbing ---------------------------------------------------

    private fun ensureInitialized() {
        if (initialized) return
        val params = JSONObject()
            .put("protocolVersion", PROTOCOL_VERSION)
            .put("capabilities", JSONObject())
            .put(
                "clientInfo",
                JSONObject().put("name", "PocketLLM").put("version", "1.0"),
            )
        rpc("initialize", params)
        // Best-effort notification; servers must not require it, so a failure
        // here is not fatal.
        runCatching { notify("notifications/initialized", JSONObject()) }
        initialized = true
    }

    private fun rpc(method: String, params: JSONObject): JSONObject {
        val id = nextId++
        val request = JSONObject()
            .put("jsonrpc", "2.0")
            .put("id", id)
            .put("method", method)
            .put("params", params)
        val body = post(request.toString())
        val json = extractJson(body, id)
            ?: throw IllegalStateException("No JSON-RPC response for $method")
        json.optJSONObject("error")?.let {
            throw IllegalStateException("${it.optInt("code")}: ${it.optString("message")}")
        }
        return json.optJSONObject("result") ?: JSONObject()
    }

    private fun notify(method: String, params: JSONObject) {
        val request = JSONObject()
            .put("jsonrpc", "2.0")
            .put("method", method)
            .put("params", params)
        post(request.toString())
    }

    private fun post(payload: String): String {
        require(endpoint.isNotBlank()) { "MCP server URL is not set" }
        val url = URL(endpoint)
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = 60_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "application/json, text/event-stream")
            if (token.isNotBlank()) setRequestProperty("Authorization", "Bearer $token")
            sessionId?.let { setRequestProperty("Mcp-Session-Id", it) }
        }
        return try {
            conn.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }
            conn.getHeaderField("Mcp-Session-Id")?.let { sessionId = it }
            val code = conn.responseCode
            if (code !in 200..299) {
                val err = conn.errorStream?.bufferedReader()?.readText().orEmpty()
                throw IllegalStateException("MCP HTTP $code${if (err.isNotBlank()) ": ${err.take(200)}" else ""}")
            }
            val contentType = conn.getHeaderField("Content-Type").orEmpty()
            val stream = conn.inputStream
            if (contentType.contains("text/event-stream", ignoreCase = true)) {
                readSse(stream)
            } else {
                BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { it.readText() }
            }
        } finally {
            conn.disconnect()
        }
    }

    /** Collects `data:` payloads from an SSE response into one JSON text. */
    private fun readSse(stream: java.io.InputStream): String {
        val sb = StringBuilder()
        BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { reader ->
            while (true) {
                val line = reader.readLine() ?: break
                if (line.startsWith("data:")) {
                    val data = line.removePrefix("data:").trim()
                    if (data.isNotEmpty() && data != "[DONE]") {
                        if (sb.isNotEmpty()) sb.append('\n')
                        sb.append(data)
                    }
                }
            }
        }
        return sb.toString()
    }

    /**
     * A response may be one JSON object, an SSE-joined stream (several lines),
     * or a batch; pick the object whose id matches the request (notifications
     * have none).
     */
    private fun extractJson(body: String, id: Int): JSONObject? {
        val trimmed = body.trim()
        if (trimmed.isEmpty()) return null
        runCatching { JSONObject(trimmed) }.getOrNull()?.let { return it }
        runCatching { JSONArray(trimmed) }.getOrNull()?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                if (o.optInt("id", -1) == id) return o
            }
        }
        // SSE-joined payloads: one JSON object per line.
        trimmed.lineSequence().forEach { line ->
            runCatching { JSONObject(line) }.getOrNull()?.let { o ->
                if (o.optInt("id", -1) == id) return o
            }
        }
        return null
    }

    private companion object {
        const val PROTOCOL_VERSION = "2024-11-05"
    }
}
