package com.pocketllm

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.io.FileOutputStream

/**
 * Represents a file the user attached to a chat message. We keep the original
 * URI (so we can read it again later) plus a cached local copy under
 * [filesDir]/attachments for fast access.
 *
 * For text files we eagerly read the content (up to a size cap) so the model
 * can see it in the prompt. For binary files we just note the path — the
 * model would need to call `read_file` to access it.
 */
data class AttachedFile(
    val displayName: String,
    val sizeBytes: Long,
    val mimeType: String,
    val localFile: File,
    val textPreview: String? = null,
) {
    val isText: Boolean get() = textPreview != null

    companion object {
        private const val TEXT_PREVIEW_CAP = 16_000

        fun fromUri(context: Context, uri: Uri): AttachedFile? {
            val resolver = context.contentResolver
            val name = queryName(resolver, uri) ?: "attachment"
            val size = querySize(resolver, uri) ?: 0L
            val mime = resolver.getType(uri) ?: "application/octet-stream"

            val dir = File(context.filesDir, "attachments").also { it.mkdirs() }
            val safeName = name.replace(Regex("[^A-Za-z0-9._-]"), "_")
            val local = File(dir, "${System.currentTimeMillis()}_$safeName")
            val input = resolver.openInputStream(uri) ?: return null
            input.use { ins ->
                FileOutputStream(local).use { out -> ins.copyTo(out) }
            }

            val isText = mime.startsWith("text/") ||
                name.endsWith(".md", true) || name.endsWith(".txt", true) ||
                name.endsWith(".json", true) || name.endsWith(".csv", true) ||
                name.endsWith(".kt", true) || name.endsWith(".java", true) ||
                name.endsWith(".py", true) || name.endsWith(".js", true) ||
                name.endsWith(".ts", true) || name.endsWith(".html", true) ||
                name.endsWith(".xml", true) || name.endsWith(".yml", true) ||
                name.endsWith(".yaml", true) || name.endsWith(".md", true) ||
                name.endsWith(".sh", true) || name.endsWith(".log", true)

            val preview = if (isText && local.length() <= TEXT_PREVIEW_CAP) {
                runCatching { local.readText(Charsets.UTF_8) }.getOrNull()
            } else null

            return AttachedFile(
                displayName = name,
                sizeBytes = local.length(),
                mimeType = mime,
                localFile = local,
                textPreview = preview,
            )
        }

        private fun queryName(resolver: android.content.ContentResolver, uri: Uri): String? {
            return resolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { c ->
                    if (c.moveToFirst()) c.getString(0) else null
                }
        }

        private fun querySize(resolver: android.content.ContentResolver, uri: Uri): Long? {
            return resolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)
                ?.use { c ->
                    if (c.moveToFirst()) c.getLong(0) else null
                }
        }
    }
}
