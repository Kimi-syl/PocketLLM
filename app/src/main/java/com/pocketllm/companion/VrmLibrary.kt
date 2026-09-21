package com.pocketllm.companion

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.os.Environment
import android.provider.OpenableColumns
import com.pocketllm.server.ServerLog
import java.io.File

/**
 * User-imported VRM avatars.
 *
 * VRM is deliberately not treated like Live2D. Live2D's licence makes importing a
 * model the thing that triggers an Expandable Application contract, so that is not
 * supported; VRM avatars are covered by the VRM Public License, which allows
 * running a model you obtained yourself. So importing is offered here.
 *
 * Files are copied into the app's own storage rather than referenced by content
 * URI, because the overlay service has to keep reading them long after the
 * picker's one-shot grant would have lapsed, and across restarts.
 */
object VrmLibrary {
    private const val TAG = "vrm"
    private const val DIR = "vrm"

    /** A .vrm is a GLB, so the same loader handles all of these. */
    private val EXTENSIONS = listOf("vrm", "glb", "gltf")

    private fun dir(context: Context): File = File(context.filesDir, DIR).apply { mkdirs() }

    /** Imported avatars, newest first. */
    fun list(context: Context): List<File> =
        dir(context).listFiles()
            ?.filter { it.isFile && it.extension.lowercase() in EXTENSIONS }
            ?.sortedByDescending { it.lastModified() }
            ?: emptyList()

    fun find(context: Context, name: String): File? =
        list(context).firstOrNull { it.name == name }

    fun delete(file: File) {
        runCatching { file.delete() }
            .onSuccess { ServerLog.log("$TAG: deleted imported avatar '${file.name}'") }
    }

    /**
     * Reads an avatar by name: an imported file if one matches, otherwise the
     * bundled asset of that name. Returns null when neither exists.
     */
    fun read(context: Context, name: String): ByteArray? {
        find(context, name)?.let { file ->
            return runCatching { file.readBytes() }
                .onFailure { ServerLog.log("$TAG: could not read '${file.name}': ${it.message}") }
                .getOrNull()
        }
        return runCatching { context.assets.open(name).use { it.readBytes() } }
            .onFailure { ServerLog.log("$TAG: no bundled asset '$name': ${it.message}") }
            .getOrNull()
    }

    /**
     * Why an import was refused, or the file that was stored.
     *
     * Filament aborts the process outright when a skin has more than 256 joints, so
     * an unsuitable avatar has to be rejected at import rather than discovered at
     * load time - by then the app is already gone.
     */
    data class ImportResult(val file: File?, val error: String?)

    /** Validates then copies [uri] into app storage. */
    fun import(context: Context, uri: Uri): ImportResult = runCatching {
        val picked = displayName(context, uri)
        val rawExt = picked.substringAfterLast('.', "").lowercase()
        // Some providers hand back no usable name; fall back rather than refuse.
        val ext = rawExt.takeIf { it in EXTENSIONS } ?: "vrm"
        val base = picked.substringBeforeLast('.')
            .ifBlank { "avatar" }
            .replace(Regex("[^A-Za-z0-9._-]"), "_")
            .take(48)

        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: return@runCatching ImportResult(null, "That file could not be read.")

        val joints = largestSkinJointCount(bytes)
        if (joints == null) {
            return@runCatching ImportResult(
                null,
                "That does not look like a .vrm or .glb file.",
            )
        }
        if (joints > kMaxSkinJoints) {
            return@runCatching ImportResult(
                null,
                "This avatar has $joints bones in a single skin and the renderer " +
                    "supports $kMaxSkinJoints. VRoid exports pick up a bone for every " +
                    "hair and clothing strand, which is usually what pushes them over.",
            )
        }

        // Processing here rather than on every load: normalising the materials and
        // re-encoding seven 2048x2048 textures costs seconds each spawn if done
        // per launch, and the result never varies for a given file. The stored
        // copy is the processed one, so loading it is as fast as the bundled one.
        // Blocks the caller, as the stream read above already does. Import is a
        // one-off user action, so a short wait here buys a spawn that needs no
        // processing at all afterwards.
        val processed = downscaleTextures(normalizeGltf(bytes))
        val target = uniqueTarget(context, base, ext)
        target.outputStream().use { it.write(processed) }
        exportProcessed(context, processed, "import")
        ServerLog.log(
            "$TAG: imported '${target.name}' (${target.length()} bytes, $joints bones)",
        )
        ImportResult(target, null)
    }.getOrElse { ImportResult(null, "Import failed: ${it.message}") }

    /**
     * Stores already-processed bytes under a fresh name. Used by the compression
     * dialog, whose input has had its skeleton pruned before it gets here.
     */
    fun importProcessed(
        context: Context,
        bytes: ByteArray,
        base: String,
        ext: String,
        note: String,
    ): String? =
        runCatching {
            val safeBase = base.ifBlank { "avatar" }
                .replace(Regex("[^A-Za-z0-9._-]"), "_")
                .take(48)
            val target = uniqueTarget(context, safeBase, ext.ifBlank { "vrm" })
            target.outputStream().use { it.write(bytes) }
            // The compressed copy goes to Download as well, under its own label -
            // the in-app copy is what the avatar picker reads, but Download is
            // where the user can see, back up, and re-import the file.
            exportProcessed(context, bytes, note.replace(" ", "-").take(24))
            ServerLog.log("$TAG: stored '${target.name}' ($note, ${target.length()} bytes)")
            target.name
        }.getOrElse {
            ServerLog.log("$TAG: could not store compressed avatar: ${it.message}")
            null
        }

    /**
     * Writes the exact bytes a load would use to /sdcard/Download, bypassing the
     * in-app sandbox. When a processed avatar misbehaves, this file - not the
     * internal copy - is what gets validated off-device, so the analysis sees
     * precisely what gltfio was handed.
     */
    fun exportProcessed(context: Context, bytes: ByteArray, label: String) {
        runCatching {
            val name = "pocketllm-$label.vrm"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, name)
                    put(MediaStore.Downloads.MIME_TYPE, "model/gltf-binary")
                }
                val uri = context.contentResolver.insert(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI, values,
                ) ?: return
                context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
            } else {
                val dir = Environment.getExternalStoragePublicDirectory(
                    Environment.DIRECTORY_DOWNLOADS,
                )
                File(dir, name).writeBytes(bytes)
            }
            ServerLog.log("$TAG: exported processed bytes as $name")
        }
    }

    private fun uniqueTarget(context: Context, base: String, ext: String): File {
        val dir = dir(context)
        var candidate = File(dir, "$base.$ext")
        var n = 2
        while (candidate.exists()) {
            candidate = File(dir, "${base}_$n.$ext")
            n++
        }
        return candidate
    }

    private fun displayName(context: Context, uri: Uri): String {
        runCatching {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (column >= 0 && cursor.moveToFirst()) {
                    return cursor.getString(column) ?: ""
                }
            }
        }
        return uri.lastPathSegment.orEmpty()
    }
}
