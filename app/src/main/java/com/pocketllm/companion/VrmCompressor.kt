package com.pocketllm.companion

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.json.JSONArray
import org.json.JSONObject

/**
 * Compresses a VRM by pruning its skeletons down to the humanoid bones.
 *
 * Only the skin is touched. A vertex weighted to a pruned joint is re-attached to
 * the nearest kept ancestor, so hair follows the head and a skirt follows the
 * hips; prop bones hanging off the rig root (weapons, cameras) are pinned to the
 * humanoid root. Textures and materials pass through at their original size.
 *
 * The reason this exists is Filament's skinned-draw budget: this device runs at
 * feature level 1, and a VRoid export carries far more joints than a draw can
 * there. The symptom of exceeding it is a model that animates normally and never
 * receives its textures - a black figure with a clean log.
 */
object VrmCompressor {

    /** Effort presets. Bones is a ceiling on humanoid joints kept. */
    data class Preset(val label: String, val keepBones: Int)

    val PRESETS = listOf(
        Preset("Low", 160),
        Preset("Medium", 100),
        Preset("High", 64),
        Preset("Max", 40),
    )

    /** Above this, a skin cannot be drawn by this device and needs compressing. */
    const val THRESHOLD = 200

    data class Stats(val largestSkin: Int, val humanoidBones: Int)

    fun stats(bytes: ByteArray): Stats? = runCatching {
        val root = parse(bytes) ?: return@runCatching null
        val skins = root.optJSONArray("skins")
        var largest = 0
        if (skins != null) {
            for (i in 0 until skins.length()) {
                val n = skins.optJSONObject(i)?.optJSONArray("joints")?.length() ?: 0
                if (n > largest) largest = n
            }
        }
        Stats(largest, humanoidNodes(root).size)
    }.getOrNull()

    fun needsCompression(bytes: ByteArray): Boolean =
        (stats(bytes)?.largestSkin ?: 0) > THRESHOLD

    /**
     * Prunes every skin over [keepBones] joints. Returns the input unchanged when
     * the file is not a readable GLB, has no skins, or nothing needed pruning -
     * compare [stats] on the result to see which.
     */
    fun compress(
        bytes: ByteArray,
        keepBones: Int,
        shrinkTextures: Boolean = false,
    ): ByteArray {
        // Materials are always normalised - that is correction, not compression -
        // but textures stay at their original size unless asked for. The two files
        // that have ever rendered on this GPU are the two whose textures were at
        // most 1024, so the option exists for when a model comes out black; it is
        // simply not the default, because re-encoding loses detail.
        val cleaned = normalizeGltf(bytes)
        val prepared = if (shrinkTextures) downscaleTextures(cleaned) else cleaned

        val root = parse(prepared) ?: return prepared
        val bin = binChunk(prepared, root) ?: return prepared
        val skins = root.optJSONArray("skins") ?: return prepared
        if (skins.length() == 0) return prepared

        val humanoid = humanoidNodes(root)
        if (humanoid.isEmpty()) return prepared

        val rewrite = HashMap<Int, ByteArray>()
        var prunedAny = false
        for (s in 0 until skins.length()) {
            val skin = skins.optJSONObject(s) ?: continue
            val joints = skin.optJSONArray("joints") ?: continue
            if (joints.length() <= keepBones) continue
            if (pruneSkin(root, bin, skin, humanoid, keepBones, rewrite) != null) {
                prunedAny = true
            }
        }
        if (!prunedAny) return prepared
        return repack(root, bin, rewrite)
    }

    // ------------------------------------------------------------------ parsing

    private fun parse(bytes: ByteArray): JSONObject? = runCatching {
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        if (buf.getInt(0) != MAGIC) return@runCatching null
        if (buf.getInt(16) != JSON_CHUNK) return@runCatching null
        JSONObject(String(bytes, 20, buf.getInt(12), Charsets.UTF_8))
    }.getOrNull()

    private fun binChunk(bytes: ByteArray, root: JSONObject): ByteArray? = runCatching {
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val jsonLength = buf.getInt(12)
        val pad = (4 - jsonLength % 4) % 4
        bytes.copyOfRange(20 + jsonLength + pad + 8, buf.getInt(8))
    }.getOrNull()

    private fun humanoidNodes(root: JSONObject): Set<Int> {
        val out = HashSet<Int>()
        val ext = root.optJSONObject("extensions") ?: return out
        ext.optJSONObject("VRMC_vrm")?.optJSONObject("humanoid")
            ?.optJSONObject("humanBones")?.let { bones ->
                for (name in bones.keys()) {
                    val idx = bones.optJSONObject(name)?.optInt("node", -1) ?: -1
                    if (idx >= 0) out.add(idx)
                }
            }
        if (out.isEmpty()) {
            ext.optJSONObject("VRM")?.optJSONObject("humanoid")
                ?.optJSONArray("humanBones")?.let { list ->
                    for (i in 0 until list.length()) {
                        val idx = list.optJSONObject(i)?.optInt("node", -1) ?: -1
                        if (idx >= 0) out.add(idx)
                    }
                }
        }
        return out
    }

    private fun parentMap(nodes: JSONArray): HashMap<Int, Int> {
        val out = HashMap<Int, Int>()
        for (i in 0 until nodes.length()) {
            val children = nodes.optJSONObject(i)?.optJSONArray("children") ?: continue
            for (c in 0 until children.length()) out[children.getInt(c)] = i
        }
        return out
    }

    private fun hipsNode(root: JSONObject): Int {
        val ext = root.optJSONObject("extensions") ?: return 0
        ext.optJSONObject("VRMC_vrm")?.optJSONObject("humanoid")
            ?.optJSONObject("humanBones")?.let {
                return it.optJSONObject("hips")?.optInt("node", 0) ?: 0
            }
        ext.optJSONObject("VRM")?.optJSONObject("humanoid")
            ?.optJSONArray("humanBones")?.let { list ->
                for (i in 0 until list.length()) {
                    val e = list.optJSONObject(i) ?: continue
                    if (e.optString("bone") == "hips") return e.optInt("node", 0)
                }
            }
        return 0
    }

    // ------------------------------------------------------------------ pruning

    /**
     * Humanoid joints in a fixed importance order - torso and head first, then
     * limbs, then facial bones, then fingers. The cap is applied to this order at
     * the call site, so a tighter budget drops fingers before arms.
     */
    private fun priorityOrder(humanoid: Set<Int>, nodes: JSONArray): List<Int> {
        val lower = { node: Int ->
            (nodes.optJSONObject(node)?.optString("name") ?: "").lowercase()
        }
        val groups = listOf(
            listOf("hips", "spine", "chest", "upperchest", "neck", "head"),
            listOf("shoulder", "upperarm", "lowerarm", "hand"),
            listOf("upperleg", "lowerleg", "foot", "toes"),
            listOf("eye", "jaw"),
            listOf("thumb", "index", "middle", "ring", "little"),
        )
        val chosen = ArrayList<Int>(humanoid.size)
        val taken = HashSet<Int>()
        for (group in groups) {
            for (n in humanoid) {
                if (taken.contains(n)) continue
                if (group.any { lower(n).contains(it) }) {
                    chosen.add(n)
                    taken.add(n)
                }
            }
        }
        humanoid.filter { !taken.contains(it) }.forEach { chosen.add(it) }
        return chosen
    }

    /** Prunes [skin] in place, registering rewritten binary blobs. Null = no change. */
    private fun pruneSkin(
        root: JSONObject,
        bin: ByteArray,
        skin: JSONObject,
        humanoid: Set<Int>,
        keepBones: Int,
        rewrite: MutableMap<Int, ByteArray>,
    ): List<Int>? {
        val oldJoints = skin.optJSONArray("joints") ?: return null
        val count = oldJoints.length()
        val slotNode = IntArray(count) { oldJoints.getInt(it) }

        val nodes = root.optJSONArray("nodes") ?: return null
        val keepNodes = LinkedHashSet<Int>()
        for (n in priorityOrder(humanoid, nodes)) {
            if (keepNodes.size >= keepBones) break
            if (humanoid.contains(n)) keepNodes.add(n)
        }
        if (keepNodes.size >= count) return null

        val nodeToNew = HashMap<Int, Int>()
        val newNodes = ArrayList<Int>()
        for (node in slotNode) {
            if (keepNodes.contains(node) && !nodeToNew.containsKey(node)) {
                nodeToNew[node] = newNodes.size
                newNodes.add(node)
            }
        }

        // Pruned joints ride the nearest kept ancestor; anything with no kept
        // ancestor (props off the rig root) lands on the humanoid root, so a
        // weapon still tracks the body.
        val parent = parentMap(nodes)
        val hipsNew = nodeToNew[hipsNode(root)] ?: 0
        val slotRemap = IntArray(count) { slot ->
            val node = slotNode[slot]
            nodeToNew[node] ?: run {
                var cur = node
                var found = -1
                var guard = 0
                while (cur >= 0 && guard < 64) {
                    cur = parent[cur] ?: -1
                    if (cur < 0) break
                    val hit = nodeToNew[cur]
                    if (hit != null) {
                        found = hit
                        break
                    }
                    guard++
                }
                if (found < 0) hipsNew else found
            }
        }

        val accessors = root.optJSONArray("accessors") ?: return null
        val views = root.optJSONArray("bufferViews") ?: return null

        // Fresh inverse-bind matrices, in pruned order, copied from the originals.
        val ibmAcc = accessors.optJSONObject(skin.getInt("inverseBindMatrices"))
            ?: return null
        val ibmBv = views.optJSONObject(ibmAcc.getInt("bufferView")) ?: return null
        val ibmOff = ibmBv.optInt("byteOffset", 0) + ibmAcc.optInt("byteOffset", 0)
        val ibmBlob = ByteArray(newNodes.size * 64)
        for ((new, node) in newNodes.withIndex()) {
            val slot = slotNode.indexOf(node)
            if (slot >= 0) System.arraycopy(bin, ibmOff + slot * 64, ibmBlob, new * 64, 64)
        }

        // Re-encode every JOINTS_0 accessor this skin's meshes use.
        val meshes = root.optJSONArray("meshes") ?: return null
        val seen = HashSet<Int>()
        for (m in 0 until meshes.length()) {
            val mesh = meshes.optJSONObject(m) ?: continue
            val prims = mesh.optJSONArray("primitives") ?: continue
            for (p in 0 until prims.length()) {
                val attrs = prims.optJSONObject(p)?.optJSONObject("attributes") ?: continue
                val ja = attrs.optInt("JOINTS_0", -1)
                if (ja < 0 || !seen.add(ja)) continue
                val acc = accessors.optJSONObject(ja) ?: continue
                val twoByte = acc.optInt("componentType", 5123) == 5123
                val bv = views.optJSONObject(acc.getInt("bufferView")) ?: continue
                val off = bv.optInt("byteOffset", 0) + acc.optInt("byteOffset", 0)
                val vertexCount = acc.optInt("count", 0)
                val blob = bin.copyOfRange(off, off + vertexCount * 4 * (if (twoByte) 2 else 1))
                val out = ByteArray(blob.size)
                val src = ByteBuffer.wrap(blob).order(ByteOrder.LITTLE_ENDIAN)
                val dst = ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN)
                for (v in 0 until vertexCount) {
                    for (c in 0 until 4) {
                        val at = v * 4 * (if (twoByte) 2 else 1) + c * (if (twoByte) 2 else 1)
                        val old = if (twoByte) src.getShort(at).toInt() and 0xFFFF
                        else blob[at].toInt() and 0xFF
                        val new = if (old < count) slotRemap[old] else 0
                        if (twoByte) dst.putShort(at, new.toShort()) else dst.put(at, new.toByte())
                    }
                }
                rewrite[acc.getInt("bufferView")] = out
            }
        }

        // Point the skin at the pruned list and the fresh matrices. Both new
        // accessors' bufferViews get their content registered below, and repack
        // assigns their byteOffsets.
        val jointsOut = JSONArray()
        newNodes.forEach { jointsOut.put(it) }
        skin.put("joints", jointsOut)

        views.put(JSONObject().apply {
            put("buffer", 0)
            put("byteLength", ibmBlob.size)
        })
        accessors.put(JSONObject().apply {
            put("buffer", 0)
            put("bufferView", views.length() - 1)
            put("componentType", ibmAcc.optInt("componentType", 5126))
            put("count", newNodes.size)
            put("type", "MAT4")
        })
        skin.put("inverseBindMatrices", accessors.length() - 1)
        rewrite[views.length() - 1] = ibmBlob

        return newNodes
    }

    // ------------------------------------------------------------------ repack

    /**
     * Rebuilds the GLB. Rewritten bufferViews are re-laid at 4-byte alignment with
     * fresh offsets; untouched views are copied verbatim, original offsets
     * preserved. buffers[0].byteLength is updated to the true size - leaving the
     * stale value makes gltfio refuse to bind textures, which is the black-model
     * failure this whole object exists to avoid.
     */
    private fun repack(
        root: JSONObject,
        bin: ByteArray,
        rewrite: Map<Int, ByteArray>,
    ): ByteArray {
        val views = root.optJSONArray("bufferViews") ?: return bin
        val out = ByteArrayOutputStream()
        var cursor = 0

        // Source offsets are captured up front: every view's byteOffset is
        // rewritten below to where its bytes actually land, so reading the field
        // inside the loop would see the new value for later views. Not updating
        // them leaves image views pointing past the end of the rebuilt buffer,
        // which is the failure that makes a compressed avatar vanish.
        val sourceOffsets = IntArray(views.length()) { i ->
            views.optJSONObject(i)?.optInt("byteOffset", 0) ?: 0
        }

        for (i in 0 until views.length()) {
            val bv = views.optJSONObject(i) ?: continue
            val blob = rewrite[i]
            // Align to 16, not 4.
            //
            // The alignment theory was disproved (the escaping in gltfJsonBytes
            // was the real cause), but wider alignment is harmless and kept.
            val pad = (kBufferAlignment - cursor % kBufferAlignment) % kBufferAlignment
            repeat(pad) { out.write(0) }
            cursor += pad
            bv.put("byteOffset", cursor)
            if (blob != null) {
                bv.put("byteLength", blob.size)
                out.write(blob)
                cursor += blob.size
            } else {
                val off = sourceOffsets[i]
                val len = bv.optInt("byteLength", 0)
                if (off + len <= bin.size) {
                    out.write(bin, off, len)
                    cursor += len
                }
            }
        }

        root.optJSONArray("buffers")?.optJSONObject(0)?.put("byteLength", cursor)
        val binBytes = out.toByteArray()
        val binPad = (4 - binBytes.size % 4) % 4

        val json = gltfJsonBytes(root)
        val jsonPad = (4 - json.size % 4) % 4

        val result = ByteArray(12 + 8 + json.size + jsonPad + 8 + binBytes.size + binPad)
        val w = ByteBuffer.wrap(result).order(ByteOrder.LITTLE_ENDIAN)
        w.putInt(MAGIC)
        w.putInt(2)
        w.putInt(result.size)
        w.putInt(json.size + jsonPad)
        w.putInt(JSON_CHUNK)
        w.put(json)
        repeat(jsonPad) { w.put(0x20) }
        w.putInt(binBytes.size + binPad)
        w.putInt(BIN_CHUNK)
        w.put(binBytes)
        repeat(binPad) { w.put(0) }
        return result
    }

    private const val MAGIC = 0x46546C67
    private const val JSON_CHUNK = 0x4E4F534A
    private const val BIN_CHUNK = 0x004E4942
}
