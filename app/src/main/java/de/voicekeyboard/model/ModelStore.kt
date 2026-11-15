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

/** V0.1.51 (PLAN_V0151 §D): source of a LOCAL model-set import — one stream
 *  per RELATIVE set path (e.g. "shared/shared_weights.bin"; exactly the
 *  [ModelFile.storeName] spelling of the pinned manifest). Deliberately
 *  narrow and Android-free: tests fake it with a map, the app adapts a
 *  SAF document tree ([ACTION_OPEN_DOCUMENT_TREE]) — the store core stays
 *  JVM-pure (no ContentResolver, no network).
 *
 *  `null` = the file does not exist in the source (→ [ImportResult.Missing]).
 */
fun interface ImportSource {
    /** Opens [relativePath] inside the chosen import root, or null if absent. */
    fun open(relativePath: String): java.io.InputStream?
}

/** V0.1.51 (PLAN_V0151 §D, P1): typed result of [ModelStore.importSet] —
 *  user-relevant failures come back as VALUES (no throw), so the UI can
 *  name the offending file. [relPath] is always the manifest-relative name
 *  the user must find in the folder (e.g. "tokenizer/tokenizer.json"). */
sealed class ImportResult {
    /** Every file verified (size + sha256) and atomically activated — READY. */
    object Ready : ImportResult()

    /** The folder does not contain this file. */
    data class Missing(val relPath: String) : ImportResult()

    /** File present, but its sha256 does not match the pinned manifest. */
    data class HashMismatch(val relPath: String) : ImportResult()

    /** File present, but its byte size does not match the pinned manifest. */
    data class SizeMismatch(val relPath: String) : ImportResult()

    /** Reading the source or writing the store failed (aborted, revoked,
     *  unreadable). Detail is technical, for logs only — the UI shows the
     *  generic [ImportResult.IoError] message. */
    data class IoError(val detail: String) : ImportResult()
}

/** Status of the model state (IMPLEMENTATION_PLAN §B.1). */
enum class ModelStatus { ABSENT, DOWNLOADING, VERIFYING, READY, BROKEN }

/**
 * File system store for model states (IMPLEMENTATION_PLAN M7; DESIGN principle,
 * quoted from the German spec: “atomic activation, no mixing of old and new
 * graphs”).
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

    /** T20b (PLAN_V0145): stable id of the model SET — sha256 over the manifest
     *  entries (store names + expected file hashes). No disk access; changes
     *  exactly when the model set changes. Persistence key of the one-time
     *  network-revoke invite (NetworkInvitePolicy, Settings key
     *  "net_invite_shown_for").
     *  T22c (B4): per variant — each set gets its own invite id; callers without
     *  an argument keep variant A, so existing persisted ids stay valid. */
    fun manifestSetId(variant: ModelVariant = ModelVariant.ONNX_CPU): String =
        Sha256.ofBytes(
            manifestFor(variant).joinToString("\n") { "${it.storeName}:${it.sha256}" }.toByteArray(),
        )

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
     * MotionEvent` exactly on the Mic tap — and the user impression was “input
     * is ignored” (quoted from the German report). Integrity is still checked hard: on every
     * activation (`activate`, ModelStore:216) and in `verifyInstalled()`.
     */
    fun isReadyQuick(variant: ModelVariant = ModelVariant.ONNX_CPU): Boolean =
        isPresent(variant) &&
            (verifiedThisProcess[variant.name] == true || sizesMatch(variant))

    /** Like [status], but without a full hash — for main-thread callers. */
    fun statusQuick(variant: ModelVariant = ModelVariant.ONNX_CPU): ModelStatus = when {
        marker(VERIFY_MARKER, variant) -> ModelStatus.VERIFYING
        marker(DOWNLOAD_MARKER, variant) -> ModelStatus.DOWNLOADING
        activeVersion(variant) == null -> absentOrPartial(variant)
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
        marker(VERIFY_MARKER, variant) -> ModelStatus.VERIFYING
        marker(DOWNLOAD_MARKER, variant) -> ModelStatus.DOWNLOADING
        activeVersion(variant) == null -> absentOrPartial(variant)
        else -> if (verifyInstalled(variant)) ModelStatus.READY else ModelStatus.BROKEN
    }

    /** T22c (B3): only remnants of the OWN set count — a .part of another
     *  variant must not fake a DOWNLOADING trail here (name checked against
     *  the variant whitelist). */
    private fun absentOrPartial(variant: ModelVariant): ModelStatus =
        // Pointer missing: ABSENT, except download remnants exist → keep the DOWNLOADING trail
        if (downloadsDir.listFiles()?.any {
                it.name.endsWith(PART_SUFFIX) &&
                    isAllowedName(unescapeStoreName(it.name.removeSuffix(PART_SUFFIX)), variant)
            } == true
        ) {
            ModelStatus.DOWNLOADING
        } else {
            ModelStatus.ABSENT
        }

    // ---- downloads / resume ----

    /** Part file (downloads/<name>.part) for resuming — whitelisted names only.
     *  T22c: per variant (name whitelist of the own set); default A = old callers. */
    fun partFile(name: String, variant: ModelVariant = ModelVariant.ONNX_CPU): File = safePart(name, variant)

    /** ETag sidecar for the resume guard; null if none was stored.
     *  VK-1 (REVIEW_V0146): per variant — the name whitelist of the OWN set;
     *  default A keeps every existing caller. */
    fun etagFor(name: String, variant: ModelVariant = ModelVariant.ONNX_CPU): String? {
        val f = File(downloadsDir, escapeStoreName(safeName(name, variant)) + ETAG_SUFFIX)
        return if (f.isFile) f.readText().trim().takeIf { it.isNotEmpty() } else null
    }

    fun storeEtag(name: String, etag: String?, variant: ModelVariant = ModelVariant.ONNX_CPU) {
        val f = File(downloadsDir, escapeStoreName(safeName(name, variant)) + ETAG_SUFFIX)
        if (etag == null) f.delete() else f.writeText(etag)
    }

    /** New, empty staging version. */
    fun newStagingDir(): File {
        val dir = File(versionsDir, "v-${System.nanoTime()}")
        check(dir.mkdirs() || dir.isDirectory) { "cannot create staging: ${dir.path}" }
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
        // T24b (B2): multi-segment targets live in subdirectories — the rename
        // needs the parent chain of the set tree inside the staging dir.
        dst.parentFile?.mkdirs()
        return if (src.renameTo(dst)) {
            File(downloadsDir, escapeStoreName(name) + ETAG_SUFFIX).delete()
            true
        } else {
            false
        }
    }

    /**
     * Activates a staging version: checks all files (present +
     * size + SHA-256), writes the pointer atomically (tmp+rename) and deletes
     * all NON-active versions. On failure the previously active
     * version stays untouched (DESIGN, quoted from the German spec: “the active
     * version stays intact”).
     */
    fun activate(stagingDir: File): Boolean = activate(stagingDir, ModelVariant.ONNX_CPU)

    /** V1: activation per variant (checked against the variant manifest). */
    fun activate(stagingDir: File, variant: ModelVariant): Boolean {
        if (!stagingDir.isDirectory) return false
        // T22d: a set WITHOUT a pinned manifest must never activate — an empty
        // list would otherwise pass vacuously and activate ANY staging dir
        // (e.g. a future set before its contract is pinned).
        if (manifestFor(variant).isEmpty()) return false
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
        clearMarkers(variant)
        // F11 P2: the version just hashed counts as checked in this process —
        // interactive paths (Mic tap, 🐞 export) never have to run over ~670 MB again.
        verifiedThisProcess[variant.name] = true
        return true
    }

    /** Deletes all versions that are NOT active in ANY variant (space reclaim).
     *  T22c (B1): previously only activeVersion() of variant A survived —
     *  activating set B deleted the version dir of set A. */
    fun sweepStaleVersions() {
        val active = ModelVariant.entries.mapNotNull { activeVersion(it) }.toSet()
        if (active.isEmpty()) return
        versionsDir.listFiles()?.forEach { dir ->
            if (dir.isDirectory && dir.name !in active) dir.deleteRecursively()
        }
    }

    /** T22c (PLAN_V0146 §2 "Delete other model"): deletes the COMPLETE model set
     *  of a variant — version dir, pointer, download remnants (.part/.etag) and
     *  markers. The ACTIVE variant (the one chosen in the prefs — the store has
     *  no prefs access, so the caller passes it) is refused WITHOUT deleting:
     *  deleting under the running engine would rip away its model dir.
     *  Returns true if the set was (or already is) gone, false on refusal. */
    fun deleteSet(variant: ModelVariant, activeVariant: ModelVariant): Boolean {
        if (variant == activeVariant) return false
        activeVersion(variant)?.let { File(versionsDir, it).deleteRecursively() }
        pointerFile(variant).delete()
        // variant A: a stale legacy pointer (active.txt) must go too
        if (variant == ModelVariant.ONNX_CPU) File(root, "active.txt").delete()
        downloadsDir.listFiles()?.forEach { f ->
            val base = unescapeStoreName(f.name.removeSuffix(PART_SUFFIX).removeSuffix(ETAG_SUFFIX))
            if (isAllowedName(base, variant)) f.delete()
        }
        clearMarkers(variant)
        verifiedThisProcess.remove(variant.name)
        return true
    }

    /** Discards all download remnants (.part/.etag) and markers (T22c: of every
     *  variant — a maintenance reset must not leave foreign set markers behind). */
    fun clearDownloads() {
        downloadsDir.listFiles()?.forEach { it.delete() }
        ModelVariant.entries.forEach { clearMarkers(it) }
    }

    // ---- V0.1.51 (PLAN_V0151 §D, P1): local import (USB/cloud folder) ----

    /**
     * Imports a model set from a LOCAL source (folder on the device — USB
     * transfer, cloud drive; NO network, no downloader, `BASE_URL_*` stays
     * untouched). Reuses the download store pipeline instead of inventing a
     * second one: every file is staged as a `.part` in [downloadsDir] (same
     * resume/whitelist semantics as [ModelDownloader]), verified per file
     * (size, then sha256 against the PINNED manifest of the variant —
     * multi-segment store names included) and moved into a staging version;
     * only a FULLY verified staging is activated ([activate] re-checks every
     * hash and swaps the pointer atomically).
     *
     * Failure contract (PLAN §D P1): staging fully removed, the `.part` files
     * written by THIS import deleted, and an EXISTING set stays byte-for-byte
     * untouched — the pointer flips only after full verification, so there is
     * never an intermediate state in which the set is missing or mixed.
     * User-relevant failures are returned as typed [ImportResult] values;
     * nothing is thrown to the caller.
     */
    fun importSet(variant: ModelVariant, source: ImportSource): ImportResult {
        val files = manifestFor(variant)
        if (files.isEmpty()) return ImportResult.IoError("no pinned manifest for variant ${variant.id}")
        val staging = newStagingDir()
        val touchedParts = mutableListOf<String>()
        var activated = false
        try {
            for (entry in files) {
                val input = try {
                    source.open(entry.storeName)
                } catch (e: Exception) {
                    return ImportResult.IoError("open ${entry.storeName}: ${e.message ?: e.javaClass.simpleName}")
                } ?: return ImportResult.Missing(entry.storeName)
                touchedParts.add(entry.storeName)
                val part = partFile(entry.storeName, variant)
                part.parentFile?.mkdirs()
                val verdict = try {
                    copyVerifying(input, part, entry)
                } catch (e: Exception) {
                    return ImportResult.IoError("copy ${entry.storeName}: ${e.message ?: e.javaClass.simpleName}")
                }
                verdict?.let { return it } // SizeMismatch / HashMismatch — file named
                if (!movePartToStaging(entry.storeName, staging, variant)) {
                    return ImportResult.IoError("move into staging failed: ${entry.storeName}")
                }
            }
            activated = activate(staging, variant) // re-hashes everything, atomically flips the pointer
            return if (activated) {
                ImportResult.Ready
            } else {
                ImportResult.IoError("activation refused for variant ${variant.id}")
            }
        } finally {
            if (!activated) {
                staging.deleteRecursively()
                touchedParts.forEach { partFile(it, variant).delete() }
            }
        }
    }

    /** Copies [input] into [part] while hashing; null = size AND hash correct. */
    private fun copyVerifying(input: java.io.InputStream, part: File, entry: ModelFile): ImportResult? {
        val digest = MessageDigest.getInstance("SHA-256")
        var written = 0L
        input.use { stream ->
            java.io.FileOutputStream(part).use { out ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = stream.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    digest.update(buf, 0, n)
                    written += n
                    if (written > entry.sizeBytes) return ImportResult.SizeMismatch(entry.storeName)
                }
            }
        }
        if (written != entry.sizeBytes) return ImportResult.SizeMismatch(entry.storeName)
        if (!Sha256.constantTimeEquals(Sha256.hex(digest.digest()), entry.sha256)) {
            return ImportResult.HashMismatch(entry.storeName)
        }
        return null
    }

    // ---- markers (DOWNLOADING/VERIFYING for the status display) ----

    /** T22c (B2): markers per variant. Variant A keeps the LEGACY global names
     *  (.downloading/.verifying) — existing installations and callers without an
     *  argument behave exactly as before; other variants get own files
     *  (.downloading-<id>). */
    fun markDownloading(variant: ModelVariant = ModelVariant.ONNX_CPU) =
        writeMarker(DOWNLOAD_MARKER, variant)

    fun markVerifying(variant: ModelVariant = ModelVariant.ONNX_CPU) =
        writeMarker(VERIFY_MARKER, variant)

    fun clearMarkers(variant: ModelVariant = ModelVariant.ONNX_CPU) {
        markerFile(DOWNLOAD_MARKER, variant).delete()
        markerFile(VERIFY_MARKER, variant).delete()
    }

    private fun writeMarker(name: String, variant: ModelVariant) {
        markerFile(name, variant).writeText("1")
    }

    private fun marker(name: String, variant: ModelVariant): Boolean =
        markerFile(name, variant).isFile

    private fun markerFile(name: String, variant: ModelVariant): File =
        if (variant == ModelVariant.ONNX_CPU) File(root, name)
        else File(root, "$name-${variant.id}")

    // ---- path traversal protection ----

    /** Allowed base name from the manifest — no '/', '..' etc. */
    private fun safeName(name: String): String = safeName(name, ModelVariant.ONNX_CPU)
    /** V5: whitelist check against the VARIANT (store knows its own via manifestFor).
     *  T24b (Gleis 3 B2): MULTI-SEGMENT names — the store name is the CHECKED
     *  manifest name (e.g. "shared/shared_weights.bin"); the FLAT downloads
     *  artifacts use [escapeStoreName] instead. */
    private fun safeName(name: String, variant: ModelVariant): String {
        require(isAllowedName(name, variant)) { "file name not whitelisted: $name" }
        return name
    }

    /**
     * T24b (Gleis 3 B2): per-segment whitelist — a store name may contain '/'
     * (U2 merged sets reference "shared/shared_weights.bin" RELATIVELY inside
     * the set dir), but EVERY segment is validated individually: no empty
     * segment, no "."/"..", no backslash, no absolute path. Flat names behave
     * BYTE-IDENTICALLY to the old single-segment rule (single segment).
     */
    private fun isAllowedName(name: String, variant: ModelVariant): Boolean {
        if (name.isEmpty()) return false
        if ('\\' in name) return false
        val segments = name.split('/')
        if (segments.any { it.isEmpty() || it == "." || it == ".." }) return false
        return manifestFor(variant).any { it.storeName == name }
    }

    /** T24b (B2): flat artifact name for the downloads dir — '/' is escaped to
     *  %2F so parts/etags stay FLAT (no subdirectories in downloads; the
     *  resume scan stays a plain listFiles). Identity for flat names. */
    private fun escapeStoreName(name: String): String = name.replace("/", "%2F")

    private fun unescapeStoreName(name: String): String = name.replace("%2F", "/")

    /**
     * Child path with double protection: (1) the name (possibly with ".part" suffix)
     * must match the manifest whitelist, (2) the canonical path must lie directly in
     * the canonical parent directory (traversal).
     */
    private fun safeChild(dir: File, name: String): File = safeChild(dir, name, ModelVariant.ONNX_CPU)

    private fun safeChild(dir: File, name: String, variant: ModelVariant): File {
        val base = name.removeSuffix(PART_SUFFIX)
        require(isAllowedName(base, variant)) { "file name not whitelisted: $name" }
        // T24b (B2): the canonical anchor now covers the WHOLE subtree below
        // dir (multi-segment names resolve INTO subdirectories of dir);
        // equality with dir itself is rejected. Traversal (../, absolute) dies
        // either at the per-segment whitelist or here at the canonical check.
        val child = File(dir, name)
        val canonicalParent = dir.canonicalFile
        val canonicalChild = child.canonicalFile
        require(canonicalChild != canonicalParent &&
            canonicalChild.path.startsWith(canonicalParent.path + File.separator)) {
            "path traversal denied: ${child.path}"
        }
        return canonicalChild
    }

    private fun safePart(name: String, variant: ModelVariant): File {
        // T24b (B2): parts stay FLAT in downloadsDir — '/' escaped to %2F
        // (no subdirectories in downloads; the resume scan stays listFiles).
        safeName(name, variant) // whitelist check with the UNESCAPED name
        return File(downloadsDir, escapeStoreName(name) + PART_SUFFIX)
    }

    companion object {
        private const val PART_SUFFIX = ".part"
        private const val ETAG_SUFFIX = ".etag"
        private const val DOWNLOAD_MARKER = ".downloading"
        private const val VERIFY_MARKER = ".verifying"
    }
}
