package com.pocketllm.agent

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.BatteryManager
import android.os.Environment
import android.os.StatFs
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.util.Log
import com.pocketllm.util.WebSearch
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

// ---------------------------------------------------------------------------
// read_file: read a sandbox file. Supports plain text + PDF (with BM25
// chunk retrieval against a query).
// ---------------------------------------------------------------------------

class ReadFileTool(private val sandboxDir: File) : AgentTool {
    override val name = "read_file"
    override val displayName = "Read file"
    override val description = "Read a file from the sandbox directory. Supports plain text and PDF. For large files (especially PDFs), pass a query to retrieve only the most relevant chunks."
    override val parameters = listOf(
        ToolParam(
            name = "path",
            type = "string",
            description = "Path relative to the sandbox directory, e.g. 'notes.md' or 'papers/transformers.pdf'.",
        ),
        ToolParam(
            name = "query",
            type = "string",
            description = "Optional. For large files or PDFs, retrieve the chunks most relevant to this query. Omit to return the whole file.",
            required = false,
        ),
        ToolParam(
            name = "max_chars",
            type = "integer",
            description = "Maximum characters to return (default 12000).",
            required = false,
        ),
    )

    override suspend fun execute(args: Map<String, JsonElement>): ToolResult = withContext(Dispatchers.IO) {
        val relPath = (args["path"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
        if (relPath.isBlank()) return@withContext ToolResult.Error("path must not be empty")
        if (relPath.contains("..")) return@withContext ToolResult.Error("path may not contain '..'")

        val maxChars = ((args["max_chars"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull() ?: 12_000)
            .coerceIn(500, 60_000)
        val query = (args["query"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()

        val file = File(sandboxDir, relPath)
        if (!file.exists()) return@withContext ToolResult.Error("File not found: $relPath")
        if (!file.isFile) return@withContext ToolResult.Error("Not a regular file: $relPath")

        val isPdf = file.name.lowercase().endsWith(".pdf")
        val rawText = if (isPdf) extractPdfText(file) else file.readText(Charsets.UTF_8)
        if (rawText.isBlank()) return@withContext ToolResult.Error("File is empty or unreadable")

        val body = if (query.isNotBlank() && rawText.length > 4000) {
            // BM25 retrieval: split into overlapping chunks, return top 3.
            val chunks = chunkText(rawText, chunkSize = 2000, overlap = 200)
            val top = bm25Top(query, chunks, k = 3)
            top.joinToString("\n\n---\n\n").let {
                if (it.length > maxChars) it.substring(0, maxChars) + "\n[truncated]" else it
            }
        } else {
            if (rawText.length > maxChars) rawText.substring(0, maxChars) + "\n[truncated]" else rawText
        }

        ToolResult.Content(
            title = relPath,
            body = body,
            source = relPath,
            summary = "${relPath} — ${body.length} chars${if (query.isNotBlank()) " (relevant to: $query)" else ""}",
        )
    }

    override fun summarize(args: Map<String, JsonElement>): String {
        val path = (args["path"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
        val q = (args["query"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
        return if (q.isNotBlank()) "read: $path (q: $q)" else "read: $path"
    }

    private fun extractPdfText(file: File): String = runCatching {
        // pdfbox-android needs the resource loader called once per process
        PDFBoxResourceLoader.init(applicationContext())
        PDDocument.load(file).use { doc ->
            PDFTextStripper().getText(doc)
        }
    }.getOrElse { e ->
        Log.e("ReadFileTool", "PDF extract failed", e)
        ""
    }

    // Application context is needed for PDFBox. Set once at construction.
    private var appCtx: Context? = null
    fun setContext(ctx: Context) { appCtx = ctx }
    private fun applicationContext(): Context =
        appCtx ?: throw IllegalStateException("ReadFileTool context not set")

    companion object {
        internal fun chunkText(text: String, chunkSize: Int, overlap: Int): List<String> {
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

        internal fun bm25Top(query: String, chunks: List<String>, k: Int): List<String> {
            val qTokens = tokenize(query)
            if (qTokens.isEmpty()) return chunks.take(k)
            val df = HashMap<String, Int>()
            for (chunk in chunks) {
                val seen = tokenize(chunk).toSet()
                for (t in seen) df[t] = (df[t] ?: 0) + 1
            }
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
                    val norm = tf * (1.2 + 1) / (tf + 1.2 * (1 - 0.75 + 0.75 * dl / avgdl))
                    s += idf * norm
                }
                chunk to s
            }
            return scores.sortedByDescending { it.second }.take(k).map { it.first }
        }

        private fun tokenize(s: String): List<String> = s.lowercase()
            .split(Regex("[^a-z0-9\u4e00-\u9fff]+"))
            .filter { it.length > 1 }
    }
}

// ---------------------------------------------------------------------------
// clipboard: read-only. Returns whatever's currently on the clipboard.
// ---------------------------------------------------------------------------

class ClipboardReadTool(private val context: Context) : AgentTool {
    override val name = "clipboard"
    override val displayName = "Clipboard"
    override val description = "Read the current contents of the system clipboard. Read-only."
    override val parameters = emptyList<ToolParam>()

    override suspend fun execute(args: Map<String, JsonElement>): ToolResult {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip: ClipData? = cm.primaryClip
        if (clip == null || clip.itemCount == 0) return ToolResult.Text("Clipboard is empty")
        val text = clip.getItemAt(0).coerceToText(context).toString()
        return if (text.isBlank()) ToolResult.Text("Clipboard is empty")
        else ToolResult.Text(text.take(8000))
    }

    override fun summarize(args: Map<String, JsonElement>): String = "clipboard read"
}

// ---------------------------------------------------------------------------
// device_info: minimal status snapshot.
// ---------------------------------------------------------------------------

class DeviceInfoTool(private val context: Context) : AgentTool {
    override val name = "device_info"
    override val displayName = "Device info"
    override val description = "Return a one-line snapshot of battery %, free storage, network type, and locale."
    override val parameters = emptyList<ToolParam>()

    override suspend fun execute(args: Map<String, JsonElement>): ToolResult = withContext(Dispatchers.IO) {
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val batteryLevel = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val stats = runCatching { StatFs(Environment.getDataDirectory().path) }.getOrNull()
        val freeStorage = stats?.let { (it.availableBlocksLong * it.blockSizeLong) / (1024L * 1024L) } ?: -1L
        val net = (context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager)?.activeNetwork
        val caps = net?.let { (context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager).getNetworkCapabilities(it) }
        val netType = when {
            caps == null -> "offline"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
            else -> "other"
        }
        val locale = java.util.Locale.getDefault().toLanguageTag()
        val summary = "Battery ${batteryLevel}% • ${freeStorage}MB free • $netType • $locale"
        ToolResult.Text(summary)
    }

    override fun summarize(args: Map<String, JsonElement>): String = "device info"
}
