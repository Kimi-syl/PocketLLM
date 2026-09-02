package com.pocketllm.cli

import com.pocketllm.agent.AgentLoop
import com.pocketllm.agent.CalculateTool
import com.pocketllm.agent.DateTimeTool
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
          pocketllm agent --model <path.gguf> [--ctx N] [--threads N] <question>

        REPL commands in chat: /exit /reset /state

        Environment:
          POCKETLLM_NATIVE_LIB  path to libpocketllm.so (desktop native build)

        Examples:
          pocketllm chat --model ~/.models/qwen2.5-1.5b-q4_k_m.gguf
          pocketllm serve --port 8080 --model ~/.models/qwen2.5-1.5b-q4_k_m.gguf
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
            out[a.removePrefix("--")] = args.getOrNull(i + 1) ?: ""
            i += 2
        } else {
            i++
        }
    }
    return out
}

private fun loadModelOrExit(path: String, opts: Map<String, String>) {
    runBlocking {
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

        val registry = ToolRegistry().apply {
            register(WebSearchTool { SearchConfig(engine = "duckduckgo") })
            register(CalculateTool())
            register(DateTimeTool())
            register(WebFetchTool { q ->
                WebSearch.search(q, "duckduckgo", null, 1).firstOrNull()?.url
            })
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

    opts["model"]?.let { loadModelOrExit(it, opts) }

    val server = embeddedServer(Netty, port = port, host = host) { routingApi() }
    println("PocketLLM serving on http://$host:$port (no auth, local only)")
    server.start(wait = true)
}

private fun Application.routingApi() {
    routing {
        get("/health") {
            call.respondText("""{"status":"ok"}""", ContentType.Application.Json)
        }
        get("/v1/models") {
            val modelId = (LlamaEngine.state.value as? EngineState.Ready)?.modelName
            val data = if (modelId != null) listOf(ModelInfo(id = modelId)) else emptyList()
            call.respondText(json.encodeToString(ModelListResponse(data = data)), ContentType.Application.Json)
        }
        post("/v1/chat/completions") { handleChat(call) }
    }
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
            fun send(delta: Delta, finishReason: String?) {
                val payload = json.encodeToString(
                    StreamChunk(
                        id = completionId,
                        created = created,
                        model = modelId,
                        choices = listOf(StreamChoice(index = 0, delta = delta, finishReason = finishReason)),
                    )
                )
                write("data: $payload\n\n")
                flush()
            }
            send(Delta(role = "assistant", content = ""), null)
            val collected = StringBuilder()
            val result = LlamaEngine.generate(prompt, params) { token ->
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
