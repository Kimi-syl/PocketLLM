package com.pocketllm.models

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.json.JSONObject

/**
 * Reader for the HuggingFace safetensors format.
 *
 * Layout: 8-byte little-endian header length, a JSON header, then the raw
 * tensor blob. The header maps each tensor to `{dtype, shape, data_offsets}`,
 * plus an optional `__metadata__` map (which is where the source model's
 * architecture and tokenizer hints live).
 *
 * This gives PocketLLM a truthful view of a safetensors checkpoint — tensor
 * count, dtypes, parameter count, architecture metadata — without loading the
 * weights. Running such a model still requires converting it to GGUF (llama.cpp
 * consumes GGUF only), so [Info.needsConversion] is surfaced in the UI rather
 * than pretending the file is loadable.
 */
object SafetensorsReader {

    data class TensorInfo(
        val name: String,
        val dtype: String,
        val shape: List<Long>,
        val byteLength: Long,
    )

    data class Info(
        val tensors: List<TensorInfo>,
        val metadata: Map<String, String>,
        val fileSize: Long,
    ) {
        val parameterCount: Long
            get() = tensors.sumOf { t -> t.shape.fold(1L) { acc, d -> acc * d } }

        val dtypeHistogram: Map<String, Int>
            get() = tensors.groupingBy { it.dtype }.eachCount()

        /** llama.cpp runs GGUF; a raw checkpoint must be converted first. */
        val needsConversion: Boolean get() = true

        fun summary(): String = buildString {
            append("${tensors.size} tensors · ")
            append("${parameterCount / 1_000_000} M params · ")
            append(dtypeHistogram.entries.joinToString(", ") { "${it.key}×${it.value}" })
            metadata["architecture"]?.let { append("\n架構: $it") }
        }
    }

    fun read(file: File): Result<Info> = runCatching {
        RandomAccessFile(file, "r").use { raf ->
            if (raf.length() < 8) throw IllegalArgumentException("檔案太小，不是 safetensors")
            val lenBytes = ByteArray(8)
            raf.readFully(lenBytes)
            val headerLen = ByteBuffer.wrap(lenBytes).order(ByteOrder.LITTLE_ENDIAN).long
            if (headerLen <= 0 || headerLen > 100_000_000 || 8 + headerLen > raf.length()) {
                throw IllegalArgumentException("safetensors 標頭長度不合理：$headerLen")
            }
            val headerBytes = ByteArray(headerLen.toInt())
            raf.readFully(headerBytes)
            val header = JSONObject(String(headerBytes, Charsets.UTF_8))

            val tensors = ArrayList<TensorInfo>(header.length())
            val metadata = LinkedHashMap<String, String>()
            for (key in header.keys()) {
                val obj = header.optJSONObject(key) ?: continue
                if (key == "__metadata__") {
                    for (mk in obj.keys()) metadata[mk] = obj.optString(mk)
                    continue
                }
                val dtype = obj.optString("dtype", "?")
                val shapeArr = obj.optJSONArray("shape")
                val shape = ArrayList<Long>(shapeArr?.length() ?: 0)
                if (shapeArr != null) {
                    for (i in 0 until shapeArr.length()) shape += shapeArr.optLong(i)
                }
                val offsets = obj.optJSONArray("data_offsets")
                val byteLength = if (offsets != null && offsets.length() == 2) {
                    offsets.optLong(1) - offsets.optLong(0)
                } else 0L
                tensors += TensorInfo(key, dtype, shape, byteLength)
            }
            Info(tensors = tensors, metadata = metadata, fileSize = raf.length())
        }
    }

    /** True when the file starts with a plausible safetensors header. */
    fun looksLikeSafetensors(file: File): Boolean = runCatching {
        if (file.length() < 9) return false
        RandomAccessFile(file, "r").use { raf ->
            val lenBytes = ByteArray(8)
            raf.readFully(lenBytes)
            val headerLen = ByteBuffer.wrap(lenBytes).order(ByteOrder.LITTLE_ENDIAN).long
            if (headerLen <= 0 || 8 + headerLen > raf.length() || headerLen > 100_000_000) return false
            val first = ByteArray(1)
            raf.readFully(first)
            first[0] == '{'.code.toByte()
        }
    }.getOrDefault(false)
}
