package de.voicekeyboard.model

import java.io.File
import java.io.IOException
import java.security.MessageDigest

/**
 * SHA-256 helper functions (JVM/Android shared, no Android types).
 * Lower-case hex, as in docs/MODEL_SHA256.txt.
 */
object Sha256 {

    fun ofFile(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return hex(md.digest())
    }

    fun ofBytes(bytes: ByteArray): String =
        hex(MessageDigest.getInstance("SHA-256").digest(bytes))

    fun hex(digest: ByteArray): String = digest.joinToString("") { "%02x".format(it) }

    /** Constant-time comparison (no early abort on a prefix deviation). */
    fun constantTimeEquals(a: String, b: String): Boolean {
        if (a.length != b.length) return false
        var diff = 0
        for (i in a.indices) diff = diff or (a[i].code xor b[i].code)
        return diff == 0
    }
}

/** Status of the model state (IMPLEMENTATION_PLAN §B.1). */
enum class ModelStatus { ABSENT, DOWNLOADING, VERIFYING, READY, BROKEN }

/**
 * File system store for model states (IMPLEMENTATION_PLAN M7, DESIGN „atomare
 * Aktivierung, keine Mischung alter und neuer Graphen“).
 *
 * Layout below [root]:
 * ```
 * root/
 *   active.txt            # pointer to the active version (atomic via tmp+rename)
 *   versions/v-<id>/      # per version: all 4 model files, hash-checked
 *   downloads/<name>.part # resume files of the current download
 *   downloads/<name>.etag # associated server ETag (resume guard)
 * ```
 *
 * Activation = a single pointer rename — there is never a mixed state;
 * old versions are deleted after the swap.
 *
 * Security: EVERY file access runs through [safeChild]/[safePart] — file names
 * are checked against the manifest whitelist and canonically anchored in the tree
 * (path traversal protection). No writing outside the passed tree.
 */
class ModelStore(
    private val root: File,
    private val manifest: List<ModelFile> = ModelManifest.DEFAULT,
) {

    private val versionsDir get() = File(root, "versions")
    private val downloadsDir get() = File(root, "downloads")

    /** V1: pointer per variant — aktive-<variante>.txt; an old active.txt counts as A. */
    private fun pointerFile(variant: ModelVariant = ModelVariant.ONNX_CPU): File =
        if (variant == ModelVariant.ONNX_CPU) {
            // Downward compatibility: old pointer active.txt stays variant A
            val legacy = File(root, "active.txt")
            val v1 = File(root, "active-onnx-cpu.txt")
            if (v1.isFile) v1 else legacy
        } else {
            File(root, "active-${variant.id}.txt")
        }

    /** V1: manifest per variant (parameter wins — compatibility with mini manifests in tests). */
    private fun manifestFor(variant: ModelVariant): List<ModelFile> =
        if (manifest !== ModelManifest.DEFAULT) manifest else ModelManifest.forVariant(variant)

    init {
        // Only create the own structures; touch nothing outside root.
        versionsDir.mkdirs()
        downloadsDir.mkdirs()
    }

    // ---- queries ----

    /** Active model directory (possibly not present yet — caller checks isReady). */
    fun modelDir(): File = modelDir(ModelVariant.ONNX_CPU)

    /** V1: model directory of the active version per variant. */
    fun modelDir(variant: ModelVariant): File {
        val version = activeVersion(variant) ?: return File(versionsDir, "v-none-${variant.id}")
        return File(versionsDir, version)
    }

    fun activeVersion(): String? = activeVersion(ModelVariant.ONNX_CPU)

    /** V1: active version ID per variant (old pointer ⇒ variant A). */
    fun activeVersion(variant: ModelVariant): String? =
        if (pointerFile(variant).isFile) {
            pointerFile(variant).readText().trim().takeIf { it.isNotEmpty() }
        } else {
            null
        }

    /** True if all files of the active version are present (without a hash check). */
    fun isPresent(): Boolean = isPresent(ModelVariant.ONNX_CPU)

    fun isPresent(variant: ModelVariant): Boolean {
        val dir = modelDir(variant)
        val m = manifestFor(variant)
        return activeVersion(variant) != null && m.all { safeChild(dir, it.storeName, variant).isFile }
    }

    /** Full check: present + every file hash-correct (expensive, setup/maintenance only). */
    fun verifyInstalled(): Boolean = verifyInstalled(ModelVariant.ONNX_CPU)

    fun verifyInstalled(variant: ModelVariant): Boolean {
        if (!isPresent(variant)) return false
        val dir = modelDir(variant)
        val m = manifestFor(variant)
        val ok = m.all { entry ->
            val f = safeChild(dir, entry.storeName, variant)
            f.length() == entry.sizeBytes &&
                Sha256.constantTimeEquals(Sha256.ofFile(f), entry.sha256)
        }
        if (ok) verifiedThisProcess[variant.name] = true
        return ok
    }

    /**
     * Variants fully verified at least once per process.
     * Serves only to avoid running the full check over ~670 MB
     * again on every interactive query.
     */
    private val verifiedThisProcess = java.util.concurrent.ConcurrentHashMap<String, Boolean>()

    /**
     * READY for interactive paths (Mic key, 🐞 export): without hashing.
     *
     * Reason (measured 2026-10-30, <device>): `isReady()` = full check over
     * ~670 MB. On the IME main thread the keyboard was no longer responsive
     * after 5 000 ms — `am_anr: InputMethod is not responding. Waited 5000ms for
     * MotionEvent` exactly on the Mic tap — and the user impression was „Eingaben
     * werden ignoriert“. Integrity is still checked hard: on every
     * activation (`activate`, ModelStore:216) and in `verifyInstalled()`.
     */
    fun isReadyQuick(variant: ModelVariant = ModelVariant.ONNX_CPU): Boolean =
        isPresent(variant) &&
            (verifiedThisProcess[variant.name] == true || sizesMatch(variant))

    /** Like [status], but without a full hash — for main-thread callers. */
    fun statusQuick(variant: ModelVariant = ModelVariant.ONNX_CPU): ModelStatus = when {
        marker(VERIFY_MARKER) -> ModelStatus.VERIFYING
        marker(DOWNLOAD_MARKER) -> ModelStatus.DOWNLOADING
        activeVersion(variant) == null -> absentOrPartial()
        else -> if (isReadyQuick(variant)) ModelStatus.READY else ModelStatus.BROKEN
    }

    /** Existence + sizes per manifest entry, no hash. */
    private fun sizesMatch(variant: ModelVariant): Boolean {
        if (!isPresent(variant)) return false
        val dir = modelDir(variant)
        return manifestFor(variant).all { entry ->
            safeChild(dir, entry.storeName, variant).length() == entry.sizeBytes
        }
    }

    fun isReady(): Boolean = isReady(ModelVariant.ONNX_CPU)

    fun isReady(variant: ModelVariant): Boolean = verifyInstalled(variant)

    fun status(): ModelStatus = status(ModelVariant.ONNX_CPU)

    /** V1: status per variant. */
    fun status(variant: ModelVariant): ModelStatus = when {
        marker(VERIFY_MARKER) -> ModelStatus.VERIFYING
        marker(DOWNLOAD_MARKER) -> ModelStatus.DOWNLOADING
        activeVersion(variant) == null -> absentOrPartial()
        else -> if (verifyInstalled(variant)) ModelStatus.READY else ModelStatus.BROKEN
    }

    private fun absentOrPartial(): ModelStatus =
        // Pointer missing: ABSENT, except download remnants exist → keep the DOWNLOADING trail
        if (downloadsDir.listFiles()?.any { it.name.endsWith(PART_SUFFIX) } == true) {
            ModelStatus.DOWNLOADING
        } else {
            ModelStatus.ABSENT
        }

    // ---- downloads / resume ----

    /** Part file (downloads/<name>.part) for resuming — whitelisted names only. */
    fun partFile(name: String): File = safePart(name)

    /** ETag sidecar for the resume guard; null if none was stored. */
    fun etagFor(name: String): String? {
        val f = File(downloadsDir, safeName(name) + ETAG_SUFFIX)
        return if (f.isFile) f.readText().trim().takeIf { it.isNotEmpty() } else null
    }

    fun storeEtag(name: String, etag: String?) {
        val f = File(downloadsDir, safeName(name) + ETAG_SUFFIX)
        if (etag == null) f.delete() else f.writeText(etag)
    }

    /** New, empty staging version. */
    fun newStagingDir(): File {
        val dir = File(versionsDir, "v-${System.nanoTime()}")
        check(dir.mkdirs() || dir.isDirectory) { "Staging nicht anlegbar: ${dir.path}" }
        return dir
    }

    /**
     * Moves a finished, hash-checked part file via ATOMIC RENAME
     * (File.renameTo on the same file system) into the staging version.
     */
    fun movePartToStaging(name: String, stagingDir: File): Boolean =
        movePartToStaging(name, stagingDir, ModelVariant.ONNX_CPU)

    /** V5: the variant is passed through — B names are allowed only for B. */
    fun movePartToStaging(name: String, stagingDir: File, variant: ModelVariant): Boolean {
        val src = safePart(name, variant)
        val dst = safeChild(stagingDir, name, variant)
        if (!src.isFile) return false
        if (dst.exists()) dst.delete()
        return if (src.renameTo(dst)) {
            File(downloadsDir, safeName(name, variant) + ETAG_SUFFIX).delete()
            true
        } else {
            false
        }
    }

    /**
     * Activates a staging version: checks all files (present +
     * size + SHA-256), writes the pointer atomically (tmp+rename) and deletes
     * all NON-active versions. On failure the previously active
     * version stays untouched (DESIGN: „aktive Version bleibt intakt“).
     */
    fun activate(stagingDir: File): Boolean = activate(stagingDir, ModelVariant.ONNX_CPU)

    /** V1: activation per variant (checked against the variant manifest). */
    fun activate(stagingDir: File, variant: ModelVariant): Boolean {
        if (!stagingDir.isDirectory) return false
        val stagingName = stagingDir.name
        for (entry in manifestFor(variant)) {
            val f = safeChild(stagingDir, entry.storeName, variant)
            if (!f.isFile || f.length() != entry.sizeBytes ||
                !Sha256.constantTimeEquals(Sha256.ofFile(f), entry.sha256)
            ) {
                return false
            }
        }
        // Atomic pointer swap: write tmp, no fsync (the rename is the barrier)
        val target = pointerFile(variant)
        val tmp = File(root, target.name + ".tmp")
        tmp.writeText(stagingName)
        if (!tmp.renameTo(target)) {
            tmp.delete()
            return false
        }
        sweepStaleVersions()
        clearMarkers()
        // F11 P2: the version just hashed counts as checked in this process —
        // interactive paths (Mic tap, 🐞 export) never have to run over ~670 MB again.
        verifiedThisProcess[variant.name] = true
        return true
    }

    /** Deletes all versions except the active one (reclaim space). */
    fun sweepStaleVersions() {
        val active = activeVersion() ?: return
        versionsDir.listFiles()?.forEach { dir ->
            if (dir.isDirectory && dir.name != active) dir.deleteRecursively()
        }
    }

    /** Discards all download remnants (.part/.etag) and markers. */
    fun clearDownloads() {
        downloadsDir.listFiles()?.forEach { it.delete() }
        clearMarkers()
    }

    // ---- markers (DOWNLOADING/VERIFYING for the status display) ----

    fun markDownloading() = writeMarker(DOWNLOAD_MARKER)
    fun markVerifying() = writeMarker(VERIFY_MARKER)
    fun clearMarkers() {
        File(root, DOWNLOAD_MARKER).delete()
        File(root, VERIFY_MARKER).delete()
    }

    private fun writeMarker(name: String) {
        File(root, name).writeText("1")
    }

    private fun marker(name: String): Boolean = File(root, name).isFile

    // ---- path traversal protection ----

    /** Allowed base name from the manifest — no '/', '..' etc. */
    private fun safeName(name: String): String = safeName(name, ModelVariant.ONNX_CPU)

    /** V5: whitelist check against the VARIANT (store knows its own via manifestFor). */
    private fun safeName(name: String, variant: ModelVariant): String {
        require(isAllowedName(name, variant)) { "Dateiname nicht whitelistet: $name" }
        return name
    }

    private fun isAllowedName(name: String, variant: ModelVariant): Boolean =
        name.isNotEmpty() &&
            '/' !in name && '\\' !in name && name != "." && name != ".." &&
            manifestFor(variant).any { it.storeName == name }

    /**
     * Child path with double protection: (1) the name (possibly with ".part" suffix)
     * must match the manifest whitelist, (2) the canonical path must lie directly in
     * the canonical parent directory (traversal).
     */
    private fun safeChild(dir: File, name: String): File = safeChild(dir, name, ModelVariant.ONNX_CPU)

    private fun safeChild(dir: File, name: String, variant: ModelVariant): File {
        val base = name.removeSuffix(PART_SUFFIX)
        require(isAllowedName(base, variant)) { "Dateiname nicht whitelistet: $name" }
        require('/' !in name && '\\' !in name && name != "." && name != "..") {
            "Pfad-Traversal verweigert: $name"
        }
        val child = File(dir, name)
        val canonicalParent = dir.canonicalFile
        val canonicalChild = child.canonicalFile
        require(canonicalChild.parentFile == canonicalParent) {
            "Pfad-Traversal verweigert: ${child.path}"
        }
        return canonicalChild
    }

    private fun safePart(name: String): File = safePart(name, ModelVariant.ONNX_CPU)

    private fun safePart(name: String, variant: ModelVariant): File =
        safeChild(downloadsDir, safeName(name, variant) + PART_SUFFIX, variant)

    companion object {
        private const val PART_SUFFIX = ".part"
        private const val ETAG_SUFFIX = ".etag"
        private const val DOWNLOAD_MARKER = ".downloading"
        private const val VERIFY_MARKER = ".verifying"
    }
}
