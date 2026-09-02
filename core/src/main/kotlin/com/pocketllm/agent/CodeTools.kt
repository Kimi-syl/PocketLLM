package com.pocketllm.agent

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.io.File
import java.io.IOException

// ---------------------------------------------------------------------------
// write_file: create or overwrite a sandbox file. Will not escape the sandbox.
// ---------------------------------------------------------------------------

class WriteFileTool(private val sandboxDir: File) : AgentTool {
    override val name = "write_file"
    override val displayName = "Write file"
    override val description = "Create or overwrite a file in the sandbox. Useful for saving notes, code, or any text the model produces."
    override val parameters = listOf(
        ToolParam(
            name = "path",
            type = "string",
            description = "Path relative to the sandbox, e.g. 'notes.md' or 'scripts/hello.py'.",
        ),
        ToolParam(
            name = "content",
            type = "string",
            description = "The full file contents to write.",
        ),
    )

    override suspend fun execute(args: Map<String, JsonElement>): ToolResult = withContext(Dispatchers.IO) {
        val relPath = (args["path"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
        if (relPath.isBlank()) return@withContext ToolResult.Error("path must not be empty")
        if (relPath.contains("..")) return@withContext ToolResult.Error("path may not contain '..'")
        val content = (args["content"] as? JsonPrimitive)?.contentOrNull.orEmpty()

        try {
            val file = File(sandboxDir, relPath)
            file.parentFile?.mkdirs()
            file.writeText(content, Charsets.UTF_8)
            ToolResult.Text("Wrote ${content.length} bytes to $relPath")
        } catch (e: IOException) {
            ToolResult.Error("Write failed: ${e.message}")
        }
    }

    override fun summarize(args: Map<String, JsonElement>): String {
        val path = (args["path"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
        return "write: $path"
    }
}

// ---------------------------------------------------------------------------
// run_code: execute a shell or Python command in the sandbox. Everything the
// model does is captured in the chat history (the [Execution] ToolResult).
// ---------------------------------------------------------------------------

class RunCodeTool(private val sandboxDir: File) : AgentTool {
    override val name = "run_code"
    override val displayName = "Run code"
    override val description = "Execute a shell or Python command in the sandbox directory. Output and exit code are returned. Sandbox is a private directory under the app — no external storage access."
    override val parameters = listOf(
        ToolParam(
            name = "command",
            type = "string",
            description = "Shell command to run (e.g. 'ls -la', 'python3 script.py').",
        ),
        ToolParam(
            name = "language",
            type = "string",
            description = "One of: 'sh' (default) or 'python'. If 'python', the command is treated as a Python expression/statement run via python3 -c.",
            required = false,
        ),
        ToolParam(
            name = "timeout_s",
            type = "integer",
            description = "Max seconds to wait before killing the process (default 30, max 120).",
            required = false,
        ),
    )

    override suspend fun execute(args: Map<String, JsonElement>): ToolResult = withContext(Dispatchers.IO) {
        val command = (args["command"] as? JsonPrimitive)?.contentOrNull.orEmpty()
        if (command.isBlank()) return@withContext ToolResult.Error("command must not be empty")
        val language = (args["language"] as? JsonPrimitive)?.contentOrNull?.lowercase() ?: "sh"
        val timeoutS = ((args["timeout_s"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull() ?: 30)
            .coerceIn(1, 120)

        val fullCommand = if (language == "python") "python3 -c ${shellQuote(command)}" else command
        val pb = ProcessBuilder("/bin/sh", "-c", fullCommand)
            .directory(sandboxDir)
            .redirectErrorStream(false)
        val env = pb.environment()
        env["PYTHONUNBUFFERED"] = "1"
        val proc = pb.start()
        val finished = proc.waitFor(timeoutS.toLong(), java.util.concurrent.TimeUnit.SECONDS)
        if (!finished) {
            proc.destroyForcibly()
            return@withContext ToolResult.Execution(
                command = fullCommand,
                stdout = proc.inputStream.bufferedReader().readText().take(4000),
                stderr = "Timed out after ${timeoutS}s",
                exitCode = -1,
                summary = "Timed out after ${timeoutS}s",
            )
        }
        val stdout = proc.inputStream.bufferedReader().readText()
        val stderr = proc.errorStream.bufferedReader().readText()
        val out = if (stdout.length > 12000) stdout.substring(0, 12000) + "\n[truncated]" else stdout
        val err = if (stderr.length > 4000) stderr.substring(0, 4000) + "\n[truncated]" else stderr
        ToolResult.Execution(
            command = fullCommand,
            stdout = out,
            stderr = err,
            exitCode = proc.exitValue(),
            summary = "exit ${proc.exitValue()} • ${out.length} chars out${if (err.isNotBlank()) " • ${err.length} err" else ""}",
        )
    }

    override fun summarize(args: Map<String, JsonElement>): String {
        val cmd = (args["command"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
        val oneline = cmd.lineSequence().firstOrNull()?.take(60) ?: ""
        return "run: $oneline"
    }

    private fun shellQuote(s: String): String {
        // Single-quote and escape embedded single quotes for `sh -c`.
        return "'" + s.replace("'", "'\\''") + "'"
    }
}
