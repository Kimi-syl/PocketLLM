package com.pocketllm.models

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

data class GgufModel(val name: String, val sizeBytes: Long)

class ModelRepository(
    context: Context,
    private val tokenProvider: () -> String? = { null },
) {

    @Serializable
    private data class SegmentMeta(val index: Int, val start: Long, val end: Long, val done: Long)

    @Serializable
    private data class DownloadMeta(
        val url: String,
        val etag: String?,
        val totalBytes: Long,
        val segments: List<SegmentMeta>,
    )

    private class Segment(val index: Int, val start: Long, val endInclusive: Long, var done: Long) {
        val length: Long get() = endInclusive - start + 1
        val remaining: Long get() = length - done
    }

    private class HeadInfo(val contentLength: Long, val acceptRanges: Boolean, val etag: String?)

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private val json = Json { ignoreUnknownKeys = true }

    val dir: File = File(context.getExternalFilesDir(null) ?: context.filesDir, "models").apply { mkdirs() }

    fun list(): List<GgufModel> =
        dir.listFiles { f -> f.isFile && f.extension == "gguf" }
            ?.map { GgufModel(it.name, it.length()) }
            ?.sortedBy { it.name }
            ?: emptyList()

    private fun safeName(name: String): String? =
        name.takeIf {
            it.isNotEmpty() && !it.contains('/') && !it.contains('\\') && !it.contains("..") && it.endsWith(".gguf")
        }

    fun file(name: String): File? = safeName(name)?.let { File(dir, it) }

    fun delete(name: String): Boolean {
        val target = file(name) ?: return false
        File(dir, "$name.meta.json").delete()
        File(dir, "$name.part").delete()
        return target.delete()
    }

    suspend fun download(url: String, cancelled: AtomicBoolean, onProgress: (Float) -> Unit): Result<File> =
        withContext(Dispatchers.IO) {
            runCatching {
                val rawName = HuggingFaceNormalizer.fileNameFromUrl(url)
                val name = rawName?.takeIf { safeName(it) != null }
                    ?: "downloaded-${System.currentTimeMillis()}.gguf"
                val dest = File(dir, name)
                val part = File(dir, "$name.part")
                val metaFile = File(dir, "$name.meta.json")

                val head = headInfo(url)
                val total = head.contentLength
                require(total > 0) { "Server did not report file size; cannot download reliably" }

                val segments = resumeOrPlan(metaFile, url, head, total, part)

                val downloaded = AtomicLong(segments.sumOf { it.done })
                var lastReportTime = 0L
                var lastReportedValue = 0f
                fun report(force: Boolean = false) {
                    val now = System.currentTimeMillis()
                    val value = (downloaded.get().toDouble() / total).toFloat().coerceIn(0f, 1f)
                    if (force || now - lastReportTime > 500 || value - lastReportedValue > 0.01f) {
                        lastReportTime = now
                        lastReportedValue = value
                        onProgress(value)
                    }
                }

                if (!part.exists() || part.length() != total) {
                    RandomAccessFile(part, "rw").use { it.setLength(total) }
                }

                report(force = true)

                try {
                    coroutineScope {
                        val persister = async {
                            while (!cancelled.get() && downloaded.get() < total) {
                                delay(2000)
                                persistMeta(metaFile, url, head.etag, total, segments)
                            }
                        }
                        segments.filter { it.remaining > 0 }
                            .map { seg ->
                                async {
                                    runSegment(url, part, seg, downloaded, cancelled)
                                }
                            }
                            .awaitAll()
                        persister.cancel()
                    }
                } catch (e: Exception) {
                    persistMeta(metaFile, url, head.etag, total, segments)
                    throw e
                }

                check(downloaded.get() >= total) { "Download incomplete" }
                onProgress(1f)
                metaFile.delete()
                check(part.renameTo(dest)) { "Could not finalize download" }
                dest
            }
        }

    private suspend fun runSegment(
        url: String,
        part: File,
        segment: Segment,
        downloaded: AtomicLong,
        cancelled: AtomicBoolean,
    ) {
        var attempts = 0
        while (true) {
            attempts++
            try {
                val position = segment.start + segment.done
                if (position > segment.endInclusive) return
                val request = authorize(Request.Builder().url(url))
                    .header("Range", "bytes=$position-${segment.endInclusive}")
                    .build()
                client.newCall(request).execute().use { response ->
                    val ranged = response.code == 206
                    val fullFromStart = response.code == 200 && position == 0L
                    check(ranged || fullFromStart) { "Unexpected HTTP ${response.code} for range request" }
                    val body = response.body ?: error("Empty response body")
                    val cap = segment.remaining
                    var writtenThisAttempt = 0L
                    body.byteStream().use { input ->
                        RandomAccessFile(part, "rw").use { output ->
                            output.seek(position)
                            val buffer = ByteArray(BUFFER_SIZE)
                            while (true) {
                                if (cancelled.get()) throw IOException("Cancelled by user")
                                val read = input.read(buffer)
                                if (read == -1) break
                                val toWrite = minOf(read.toLong(), cap - writtenThisAttempt).toInt()
                                if (toWrite > 0) {
                                    output.write(buffer, 0, toWrite)
                                    segment.done += toWrite
                                    downloaded.addAndGet(toWrite.toLong())
                                    writtenThisAttempt += toWrite
                                }
                                if (writtenThisAttempt >= cap) break
                            }
                        }
                    }
                }
                check(segment.done >= segment.length) { "Segment ${segment.index} ended early" }
                return
            } catch (e: IOException) {
                if (cancelled.get() || attempts >= MAX_RETRIES) throw e
                delay(ATTEMPT_BACKOFF_MS * attempts)
            } catch (e: IllegalStateException) {
                if (attempts >= MAX_RETRIES) throw IOException(e.message, e)
                delay(ATTEMPT_BACKOFF_MS * attempts)
            }
        }
    }

    private fun resumeOrPlan(metaFile: File, url: String, head: HeadInfo, total: Long, part: File): List<Segment> {
        val saved = runCatching {
            json.decodeFromString<DownloadMeta>(metaFile.readText())
        }.getOrNull()

        val reusable = saved != null &&
            saved.url == url &&
            saved.totalBytes == total &&
            (head.etag == null || saved.etag == null || saved.etag == head.etag) &&
            part.isFile

        if (reusable && saved != null) {
            return saved.segments.map { Segment(it.index, it.start, it.end, it.done.coerceAtLeast(0)) }
        }

        val planned = planSegments(total, head.acceptRanges)
        persistMeta(metaFile, url, head.etag, total, planned)
        return planned
    }

    private fun planSegments(total: Long, rangesSupported: Boolean): List<Segment> {
        if (!rangesSupported || total < MIN_SEGMENT_SIZE) {
            return listOf(Segment(0, 0, total - 1, 0))
        }
        var count = ((total + TARGET_CHUNK_SIZE - 1) / TARGET_CHUNK_SIZE).toInt()
        count = count.coerceIn(1, MAX_SEGMENTS)
        val baseChunk = total / count
        return (0 until count).map { i ->
            val start = i.toLong() * baseChunk
            val end = if (i == count - 1) total - 1 else (i + 1).toLong() * baseChunk - 1
            Segment(i, start, end, 0)
        }
    }

    private fun persistMeta(metaFile: File, url: String, etag: String?, total: Long, segments: List<Segment>) {
        runCatching {
            val meta = DownloadMeta(
                url = url,
                etag = etag,
                totalBytes = total,
                segments = segments.map { SegmentMeta(it.index, it.start, it.endInclusive, it.done) },
            )
            metaFile.writeText(json.encodeToString(meta))
        }
    }

    private fun headInfo(url: String): HeadInfo {
        val request = authorize(Request.Builder().url(url)).head().build()
        client.newCall(request).execute().use { response ->
            check(response.isSuccessful) { "Could not reach $url (HTTP ${response.code})" }
            val length = response.header("Content-Length")?.toLongOrNull()
                ?: response.header("x-linked-size")?.toLongOrNull()
                ?: -1L
            val acceptRanges = response.header("Accept-Ranges")?.equals("bytes", ignoreCase = true) == true
            return HeadInfo(length, acceptRanges, response.header("ETag"))
        }
    }

    private fun authorize(builder: Request.Builder): Request.Builder {
        tokenProvider()?.takeIf { it.isNotBlank() }?.let { builder.header("Authorization", "Bearer $it") }
        return builder
    }

    private object HuggingFaceNormalizer {
        fun fileNameFromUrl(url: String): String? =
            url.substringBefore('?').substringAfterLast('/').takeIf { it.isNotBlank() }
    }

    companion object {
        private const val BUFFER_SIZE = 256 * 1024
        private const val MAX_SEGMENTS = 4
        private const val MAX_RETRIES = 5
        private const val ATTEMPT_BACKOFF_MS = 1500L
        private const val TARGET_CHUNK_SIZE = 96L * 1024 * 1024
        private const val MIN_SEGMENT_SIZE = 48L * 1024 * 1024
    }
}
