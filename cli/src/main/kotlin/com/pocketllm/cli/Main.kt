package com.pocketllm.cli

import com.pocketllm.agent.AgentLoop
import com.pocketllm.agent.CalculateTool
import com.pocketllm.agent.DateTimeTool
import com.pocketllm.agent.RunCodeTool
import com.pocketllm.agent.WriteFileTool
import com.pocketllm.agent.SearchConfig
import com.pocketllm.agent.ToolRegistry
import com.pocketllm.agent.WebFetchTool
import com.pocketllm.agent.WebSearchTool
import com.pocketllm.llm.EngineState
import com.pocketllm.llm.GenParams
import com.pocketllm.llm.LlamaEngine
import com.pocketllm.server.ChatCompletion
import com.pocketllm.server.ChatCompletionRequest
import com.pocketllm.server.ChatMessage
import com.pocketllm.server.Choice
import com.pocketllm.server.Delta
import com.pocketllm.server.ErrorBody
import com.pocketllm.server.ErrorResponse
import com.pocketllm.server.ModelInfo
import com.pocketllm.server.ModelListResponse
import com.pocketllm.server.StreamChoice
import com.pocketllm.server.StreamChunk
import com.pocketllm.server.Usage
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.response.respondTextWriter
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import com.pocketllm.util.WebSearch
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import kotlin.system.exitProcess

private val json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    explicitNulls = false
}

fun main(args: Array<String>) {
    when (args.firstOrNull()) {
        "chat" -> chat(args.drop(1))
        "serve" -> serve(args.drop(1))
        "agent" -> agent(args.drop(1))
        else -> usage(if (args.isEmpty()) 0 else 1)
    }
}

private fun usage(exitCode: Int): Nothing {
    println(
        """
        PocketLLM CLI

        Usage:
          pocketllm chat  --model <path.gguf> [--ctx N] [--threads N] [--temp F]
          pocketllm serve [--port N] [--host H] [--model <path.gguf>]
                          [--api-key <key>] [--tunnel | --tunnel-name <name>]
          pocketllm agent --model <path.gguf> [--ctx N] [--threads N] <question>

        REPL commands in chat: /exit /reset /state

        Serving:
          --api-key      require "Authorization: Bearer <key>" on /v1/*.
                         Generated automatically when --tunnel is used.
          --tunnel       expose the server through a Cloudflare quick tunnel,
                         i.e. a trusted https://<name>.trycloudflare.com URL.
                         Needs cloudflared on PATH.
          --tunnel-name  run an already-configured named tunnel instead
                         (cloudflared tunnel run <name>).

        Environment:
          POCKETLLM_NATIVE_LIB     path to libpocketllm.so (desktop native build)
          POCKETLLM_CLOUDFLARED    path to the cloudflared binary

        Examples:
          pocketllm chat --model ~/.models/qwen2.5-1.5b-q4_k_m.gguf
          pocketllm serve --port 8080 --model ~/.models/qwen2.5-1.5b-q4_k_m.gguf
          pocketllm serve --model m.gguf --tunnel
        """.trimIndent()
    )
    exitProcess(exitCode)
}

private fun parseOpts(args: List<String>): Map<String, String> {
    val out = mutableMapOf<String, String>()
    var i = 0
    while (i < args.size) {
        val a = args[i]
        if (a.startsWith("--")) {
            // Only consume the next token as a value when it isn't another
            // flag: "--model --threads 4" must not swallow --threads.
            val next = args.getOrNull(i + 1)
            if (next != null && !next.startsWith("--")) {
                out[a.removePrefix("--")] = next
                i += 2
            } else {
                out[a.removePrefix("--")] = ""
                i++
            }
        } else {
            i++
        }
    }
    return out
}

private fun loadModelOrExit(path: String, opts: Map<String, String>) {
    runBlocking {
        try {
        LlamaEngine.load(
            File(path),
            contextSize = opts["ctx"]?.toIntOrNull() ?: 4096,
            threads = opts["threads"]?.toIntOrNull() ?: 4,
        )
        when (val s = LlamaEngine.state.value) {
            is EngineState.Ready -> println("model loaded: ${s.modelName} (ctx=${s.contextSize})")
            is EngineState.Error -> {
                System.err.println("load failed: ${s.message}")
                exitProcess(1)
            }
            else -> Unit
        }
        } catch (e: Throwable) {
            System.err.println("native library failed to load: ${e.message}")
            System.err.println("set POCKETLLM_NATIVE_LIB to your libpocketllm.so (see build-native-desktop.sh)")
            exitProcess(1)
        }
    }
}

private fun chat(args: List<String>) {
    val opts = parseOpts(args)
    val modelPath = opts["model"] ?: run {
        System.err.println("chat: --model <path.gguf> is required")
        exitProcess(1)
    }
    val temp = opts["temp"]?.toFloatOrNull() ?: 0.8f

    runBlocking {
    loadModelOrExit(modelPath, opts)
    println("type /exit to quit")

    val history = mutableListOf<ChatMessage>()
    while (true) {
        print("you> ")
        val line = readLine() ?: break
        when (val trimmed = line.trim()) {
            "" -> continue
            "/exit", "/quit" -> break
            "/reset" -> {
                history.clear()
                println("(history cleared)")
                continue
            }
            "/state" -> {
                println(LlamaEngine.state.value)
                continue
            }
            else -> history += ChatMessage(role = "user", content = trimmed)
        }

        val prompt = LlamaEngine.chatPrompt(history.map { it.role to it.content })
        if (prompt == null) {
            System.err.println("failed to build prompt")
            continue
        }

        print("assistant> ")
        val out = StringBuilder()
        LlamaEngine.generate(prompt, GenParams(temperature = temp)) { token ->
            out.append(token)
            print(token)
            System.out.flush()
        }
        println()
        history += ChatMessage(role = "assistant", content = out.toString())
    }
    }
}

private fun agent(args: List<String>) {
    val opts = parseOpts(args)
    val modelPath = opts["model"] ?: run {
        System.err.println("agent: --model <path.gguf> is required")
        exitProcess(1)
    }
    val question = run {
        var i = 0
        var q: String? = null
        while (i < args.size) {
            val a = args[i]
            if (a.startsWith("--")) {
                i += 2
            } else {
                q = a
                break
            }
        }
        q
    } ?: run {
        System.err.println("agent: no question given")
        exitProcess(1)
    }

    runBlocking {
        loadModelOrExit(modelPath, opts)

        val sandbox = java.io.File(System.getProperty("user.home"), ".pocketllm-sandbox").apply { mkdirs() }
        val registry = ToolRegistry().apply {
            register(WebSearchTool { SearchConfig(engine = "duckduckgo") })
            register(CalculateTool())
            register(DateTimeTool())
            register(WebFetchTool { q ->
                WebSearch.search(q, "duckduckgo", null, 1).firstOrNull()?.url
            })
            register(WriteFileTool(sandbox))
            register(RunCodeTool(sandbox))
        }
        val loop = AgentLoop(LlamaEngine, registry, maxTurns = 3)

        val outcome = loop.run(
            systemPrompt = "You are PocketLLM, a concise helpful assistant running fully offline on the user's device.",
            history = emptyList(),
            userMessage = question,
            onPartialReply = { print(it); System.out.flush() },
            onToolInvoked = { ui ->
                println()
                println("[tool] ${ui.displayName} ${ui.arguments.entries.joinToString { "${it.key}=${it.value}" }}")
                println("  -> ${ui.resultSummary}")
            },
        )
        if (outcome.finalText.isNullOrBlank()) {
            println("(no final answer; tool calls: ${outcome.toolCalls.size})")
        } else {
            println()
        }
    }
}

private fun serve(args: List<String>) {
    val opts = parseOpts(args)
    val port = opts["port"]?.toIntOrNull() ?: 8080
    val host = opts["host"] ?: "127.0.0.1"

    val namedTunnel = opts["tunnel-name"]?.takeIf { it.isNotBlank() }
    val wantsTunnel = opts.containsKey("tunnel") || namedTunnel != null

    // A tunnel turns a loopback-only server into a public one, so it must never
    // run without auth. With a tunnel and no explicit key, mint one and print it.
    val explicitKey = opts["api-key"]?.takeIf { it.isNotBlank() }
    val apiKey = explicitKey ?: if (wantsTunnel) generateApiKey() else null
    if (wantsTunnel && explicitKey == null) {
        println("Generated API key for the tunnel: $apiKey")
        println("  (pass --api-key to reuse a fixed one)")
    }

    opts["model"]?.let { loadModelOrExit(it, opts) }

    val server = embeddedServer(Netty, port = port, host = host) { routingApi(apiKey) }

    if (!wantsTunnel) {
        val authNote = if (apiKey != null) "api key required" else "no auth, local only"
        println("PocketLLM serving on http://$host:$port ($authNote)")
        server.start(wait = true)
        return
    }

    server.start(wait = false)
    println("PocketLLM serving on http://$host:$port (local)")

    val tunnel = try {
        CloudflareTunnel.start("http://127.0.0.1:$port", namedTunnel) { println("  cloudflared | $it") }
    } catch (e: Exception) {
        System.err.println("Tunnel failed: ${e.message}")
        System.err.println("Exiting. Run without --tunnel to serve on the local network only.")
        server.stop(0, 0)
        exitProcess(1)
    }

    println()
    println("Public endpoint: ${tunnel.url}/v1")
    println("  curl ${tunnel.url}/v1/chat/completions \\")
    println("    -H \"Authorization: Bearer $apiKey\" \\")
    println("    -H \"Content-Type: application/json\" \\")
    println("    -d '{\"model\":\"m\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}'")
    if (namedTunnel == null) {
        println()
        println("Note: a quick tunnel is a public, randomly named, ephemeral URL. Anyone")
        println("who learns the hostname reaches this server, so keep the API key on and")
        println("stop the process when you are done.")
    }

    val stop = CountDownLatch(1)
    Runtime.getRuntime().addShutdownHook(
        Thread {
            runCatching { tunnel.close() }
            runCatching { server.stop(1_000, 3_000) }
            stop.countDown()
        }
    )
    stop.await()
}

/** 32 hex chars from a CSPRNG — same shape as the keys the Android app issues. */
private fun generateApiKey(): String {
    val bytes = ByteArray(16).also { java.security.SecureRandom().nextBytes(it) }
    return "sk-pocketllm-" + bytes.joinToString("") { "%02x".format(it) }
}

private fun Application.routingApi(apiKey: String?) {
    routing {
        // /health stays open: it is what a tunnel or uptime probe checks, and it
        // leaks nothing beyond liveness.
        get("/health") {
            call.respondText("""{"status":"ok"}""", ContentType.Application.Json)
        }
        get("/v1/models") {
            if (!call.authorized(apiKey)) return@get call.unauthorized()
            val modelId = (LlamaEngine.state.value as? EngineState.Ready)?.modelName
            val data = if (modelId != null) listOf(ModelInfo(id = modelId)) else emptyList()
            call.respondText(json.encodeToString(ModelListResponse(data = data)), ContentType.Application.Json)
        }
        post("/v1/chat/completions") {
            if (!call.authorized(apiKey)) return@post call.unauthorized()
            handleChat(call)
        }
    }
}

/** True when [apiKey] is null (auth off) or the request carries it as a Bearer token. */
private fun ApplicationCall.authorized(apiKey: String?): Boolean {
    if (apiKey == null) return true
    val presented = request.headers["Authorization"]
        ?.removePrefix("Bearer ")
        ?.trim()
        ?: return false
    return constantTimeEquals(presented, apiKey)
}

private suspend fun ApplicationCall.unauthorized() {
    response.headers.append("WWW-Authenticate", "Bearer")
    respondText(
        json.encodeToString(ErrorResponse(error = ErrorBody(message = "invalid api key"))),
        ContentType.Application.Json,
        HttpStatusCode.Unauthorized,
    )
}

/**
 * Compares without an early exit, so a wrong key cannot be recovered byte by
 * byte from response timing. Length still leaks, which is fine: it is fixed.
 */
private fun constantTimeEquals(a: String, b: String): Boolean {
    val ab = a.toByteArray()
    val bb = b.toByteArray()
    if (ab.size != bb.size) return false
    var diff = 0
    for (i in ab.indices) diff = diff or (ab[i].toInt() xor bb[i].toInt())
    return diff == 0
}

private suspend fun ApplicationCall.respondError(status: HttpStatusCode, message: String) {
    respondText(
        json.encodeToString(ErrorResponse(error = ErrorBody(message = message))),
        ContentType.Application.Json,
        status,
    )
}

private suspend fun handleChat(call: ApplicationCall) {
    val req = runCatching {
        json.decodeFromString<ChatCompletionRequest>(call.receiveText())
    }.getOrElse { e ->
        call.respondError(HttpStatusCode.BadRequest, "bad json: ${e.message}")
        return
    }
    if (req.messages.isEmpty()) {
        call.respondError(HttpStatusCode.BadRequest, "messages is required")
        return
    }
    val ready = LlamaEngine.state.value as? EngineState.Ready ?: run {
        call.respondError(HttpStatusCode.ServiceUnavailable, "No model loaded")
        return
    }
    val prompt = LlamaEngine.chatPrompt(req.messages.map { it.role to it.content }) ?: run {
        call.respondError(HttpStatusCode.InternalServerError, "Failed to build prompt")
        return
    }
    val params = GenParams(
        maxTokens = req.maxTokens ?: 512,
        temperature = req.temperature?.toFloat() ?: 0.8f,
        topP = req.topP?.toFloat() ?: 0.95f,
        topK = req.topK ?: 40,
    )
    val completionId = "chatcmpl-" + UUID.randomUUID().toString().replace("-", "").take(24)
    val created = System.currentTimeMillis() / 1000L
    val modelId = ready.modelName

    if (req.stream) {
        call.respondTextWriter(contentType = ContentType.parse("text/event-stream")) {
            // Write to a closed client socket throws IOException (broken pipe);
            // track it so the generation loop can stop instead of burning
            // maxTokens into a dead connection.
            val clientGone = java.util.concurrent.atomic.AtomicBoolean(false)
            fun send(delta: Delta, finishReason: String?) {
                if (clientGone.get()) return
                val payload = json.encodeToString(
                    StreamChunk(
                        id = completionId,
                        created = created,
                        model = modelId,
                        choices = listOf(StreamChoice(index = 0, delta = delta, finishReason = finishReason)),
                    )
                )
                try {
                    write("data: $payload\n\n")
                    flush()
                } catch (e: java.io.IOException) {
                    clientGone.set(true)
                }
            }
            send(Delta(role = "assistant", content = ""), null)
            val collected = StringBuilder()
            val result = LlamaEngine.generate(prompt, params) { token ->
                if (clientGone.get()) {
                    LlamaEngine.requestStop()
                    return@generate
                }
                collected.append(token)
                send(Delta(content = token), null)
            }
            val finishReason = when {
                result == null -> "error"
                result.stoppedByUser || result.generatedTokens >= params.maxTokens -> "length"
                else -> "stop"
            }
            send(Delta(), finishReason)
            write("data: [DONE]\n\n")
            flush()
        }
    } else {
        val collected = StringBuilder()
        val result = LlamaEngine.generate(prompt, params) { token -> collected.append(token) }
        if (result == null) {
            call.respondError(HttpStatusCode.ServiceUnavailable, "Engine busy")
            return
        }
        val finishReason =
            if (result.stoppedByUser || result.generatedTokens >= params.maxTokens) "length" else "stop"
        call.respondText(
            json.encodeToString(
                ChatCompletion(
                    id = completionId,
                    created = created,
                    model = modelId,
                    choices = listOf(
                        Choice(
                            index = 0,
                            message = Delta(role = "assistant", content = collected.toString()),
                            finishReason = finishReason,
                        )
                    ),
                    usage = Usage(
                        result.promptTokens,
                        result.generatedTokens,
                        result.promptTokens + result.generatedTokens,
                    ),
                )
            ),
            ContentType.Application.Json,
        )
    }
}
