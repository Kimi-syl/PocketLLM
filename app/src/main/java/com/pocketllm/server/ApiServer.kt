package com.pocketllm.server

import com.pocketllm.keys.ApiKeyRepository
import com.pocketllm.llm.EngineState
import com.pocketllm.llm.GenParams
import com.pocketllm.llm.LlamaEngine
import com.pocketllm.settings.SettingsRepository
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.origin
import io.ktor.server.request.receiveNullable
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.response.respondTextWriter
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID

private val json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    explicitNulls = false
}

class ApiServer(
    private val settings: SettingsRepository,
    private val apiKeys: ApiKeyRepository,
) {

    private val llama get() = LlamaEngine


    private var server: EmbeddedServer<*, *>? = null

    val isRunning: Boolean get() = server != null

    fun start() {
        if (server != null) return
        val port = settings.current().port
        server = embeddedServer(CIO, port = port, host = "0.0.0.0") {
            install(ContentNegotiation) { json(json) }
            routing {
                get("/health") {
                    call.respondText("""{"status":"ok"}""", ContentType.Application.Json)
                }
                get("/v1/models") {
                    if (!call.authorized()) return@get
                    val ready = llama.state.value as? EngineState.Ready
                    call.respond(
                        ModelListResponse(
                            data = listOfNotNull(ready?.let { ModelInfo(id = it.modelName) }),
                        )
                    )
                }
                post("/v1/chat/completions") { handleChat(call) }
                post("/v1/completions") { handleCompletion(call) }
            }
        }.start(wait = false)
        ServerLog.log("API listening on 0.0.0.0:$port")
    }

    fun stop() {
        val current = server ?: return
        current.stop(500L, 1000L)
        server = null
        ServerLog.log("API stopped")
    }

    private suspend fun ApplicationCall.authorized(): Boolean {
        if (!settings.current().requireApiKey) return true
        val token = request.headers[HttpHeaders.Authorization]?.removePrefix("Bearer ")?.trim()
        val entry = apiKeys.validate(token)
        if (entry == null) {
            ServerLog.log("401 from ${request.origin.remoteHost}")
            respond(HttpStatusCode.Unauthorized, ErrorResponse(ErrorBody("Invalid or missing API key", "authentication_error")))
            return false
        }
        return true
    }

    private fun readyModelOrNull(): EngineState.Ready? =
        llama.state.value as? EngineState.Ready

    private suspend fun handleChat(call: ApplicationCall) {
        if (!call.authorized()) return
        val req = call.receiveNullable<ChatCompletionRequest>()
        if (req == null || req.messages.isEmpty()) {
            call.respondError(HttpStatusCode.BadRequest, "messages is required")
            return
        }
        val ready = readyModelOrNull() ?: run {
            call.respondError(HttpStatusCode.ServiceUnavailable, "No model loaded", "service_unavailable")
            return
        }
        val prompt = llama.chatPrompt(req.messages.map { it.role to it.content })
        if (prompt == null) {
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
        ServerLog.log("chat model=$modelId stream=${req.stream} msgs=${req.messages.size}")

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
                val result = llama.generate(prompt, params) { token ->
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
                ServerLog.log("chat done tokens=${result?.generatedTokens ?: 0} chars=${collected.length}")
            }
        } else {
            val collected = StringBuilder()
            val result = llama.generate(prompt, params) { token -> collected.append(token) }
            if (result == null) {
                call.respondError(HttpStatusCode.ServiceUnavailable, "Engine busy", "service_unavailable")
                return
            }
            val finishReason =
                if (result.stoppedByUser || result.generatedTokens >= params.maxTokens) "length" else "stop"
            call.respond(
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
                    usage = Usage(result.promptTokens, result.generatedTokens, result.promptTokens + result.generatedTokens),
                )
            )
        }
    }

    private suspend fun handleCompletion(call: ApplicationCall) {
        if (!call.authorized()) return
        val req = call.receiveNullable<CompletionRequest>()
        if (req == null || req.prompt.isBlank()) {
            call.respondError(HttpStatusCode.BadRequest, "prompt is required")
            return
        }
        val ready = readyModelOrNull() ?: run {
            call.respondError(HttpStatusCode.ServiceUnavailable, "No model loaded", "service_unavailable")
            return
        }
        val params = GenParams(
            maxTokens = req.maxTokens ?: 256,
            temperature = req.temperature?.toFloat() ?: 0.8f,
            topP = req.topP?.toFloat() ?: 0.95f,
            topK = req.topK ?: 40,
        )
        val completionId = "cmpl-" + UUID.randomUUID().toString().replace("-", "").take(24)
        val created = System.currentTimeMillis() / 1000L
        val modelId = ready.modelName
        ServerLog.log("completions model=$modelId stream=${req.stream}")

        if (req.stream) {
            call.respondTextWriter(contentType = ContentType.parse("text/event-stream")) {
                fun send(text: String?, finishReason: String?) {
                    val payload = json.encodeToString(
                        StreamChunk(
                            id = completionId,
                            objectType = "chat.completion.chunk",
                            created = created,
                            model = modelId,
                            choices = listOf(StreamChoice(index = 0, delta = Delta(content = text), finishReason = finishReason)),
                        )
                    )
                    write("data: $payload\n\n")
                    flush()
                }
                send("", null)
                val collected = StringBuilder()
                val result = llama.generate(req.prompt, params) { token ->
                    collected.append(token)
                    send(token, null)
                }
                val finishReason = when {
                    result == null -> "error"
                    result.stoppedByUser || result.generatedTokens >= params.maxTokens -> "length"
                    else -> "stop"
                }
                send(null, finishReason)
                write("data: [DONE]\n\n")
                flush()
            }
        } else {
            val collected = StringBuilder()
            val result = llama.generate(req.prompt, params) { token -> collected.append(token) }
            if (result == null) {
                call.respondError(HttpStatusCode.ServiceUnavailable, "Engine busy", "service_unavailable")
                return
            }
            val finishReason =
                if (result.stoppedByUser || result.generatedTokens >= params.maxTokens) "length" else "stop"
            call.respond(
                TextCompletion(
                    id = completionId,
                    created = created,
                    model = modelId,
                    choices = listOf(TextChoice(index = 0, text = collected.toString(), finishReason = finishReason)),
                    usage = Usage(result.promptTokens, result.generatedTokens, result.promptTokens + result.generatedTokens),
                )
            )
        }
    }

    private suspend fun ApplicationCall.respondError(
        status: HttpStatusCode,
        message: String,
        type: String = "invalid_request_error",
    ) {
        respond(status, ErrorResponse(ErrorBody(message, type)))
    }
}
