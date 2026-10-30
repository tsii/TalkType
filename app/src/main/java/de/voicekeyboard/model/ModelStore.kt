package de.voicekeyboard.model

import java.io.File
import java.io.IOException
import java.security.MessageDigest

/**
 * SHA-256-Hilfsfunktionen (JVM/Android gemeinsam, keine Android-Typen).
 * Hex-Kleinbuchstaben, wie in docs/MODEL_SHA256.txt.
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

    /** Konstante-Zeit-Vergleich (kein vorzeitiger Abbruch bei Präfix-Abweichung). */
    fun constantTimeEquals(a: String, b: String): Boolean {
        if (a.length != b.length) return false
        var diff = 0
        for (i in a.indices) diff = diff or (a[i].code xor b[i].code)
        return diff == 0
    }
}

/** Status des Modellstands (IMPLEMENTATION_PLAN §B.1). */
enum class ModelStatus { ABSENT, DOWNLOADING, VERIFYING, READY, BROKEN }

/**
 * Dateisystem-Store für Modellstände (IMPLEMENTATION_PLAN M7, DESIGN „atomare
 * Aktivierung, keine Mischung alter und neuer Graphen").
 *
 * Layout unter [root]:
 * ```
 * root/
 *   active.txt            # Zeiger auf aktive Version (atomar via tmp+rename)
 *   versions/v-<id>/      # je Version: alle 4 Modelldateien, hash-geprüft
 *   downloads/<name>.part # Resume-Dateien des aktuellen Downloads
 *   downloads/<name>.etag # zugehöriger Server-ETag (Resume-Guard)
 * ```
 *
 * Aktivierung = einzelner Zeiger-Rename — es gibt nie einen Mischzustand;
 * alte Versionen werden nach dem Swap gelöscht.
 *
 * Sicherheit: JEDER Dateizugriff läuft über [safeChild]/[safePart] — Dateinamen
 * werden gegen die Manifest-Whitelist geprüft und kanonisch im Baum verankert
 * (Pfad-Traversal-Schutz). Kein Schreiben außerhalb des übergebenen Baums.
 */
class ModelStore(
    private val root: File,
    private val manifest: List<ModelFile> = ModelManifest.DEFAULT,
) {

    private val versionsDir get() = File(root, "versions")
    private val downloadsDir get() = File(root, "downloads")

    /** V1: Zeiger je Variante — aktive-<variante>.txt; alter active.txt gilt als A. */
    private fun pointerFile(variant: ModelVariant = ModelVariant.ONNX_CPU): File =
        if (variant == ModelVariant.ONNX_CPU) {
            // Abwärtsskompatibilität: alter Zeiger active.txt bleibt Variante A
            val legacy = File(root, "active.txt")
            val v1 = File(root, "active-onnx-cpu.txt")
            if (v1.isFile) v1 else legacy
        } else {
            File(root, "active-${variant.id}.txt")
        }

    /** V1: Manifest je Variante (Parameter gewinnt — Kompatibilität zu Mini-Manifesten in Tests). */
    private fun manifestFor(variant: ModelVariant): List<ModelFile> =
        if (manifest !== ModelManifest.DEFAULT) manifest else ModelManifest.forVariant(variant)

    init {
        // Nur die eigenen Strukturen anlegen; nichts außerhalb von root berühren.
        versionsDir.mkdirs()
        downloadsDir.mkdirs()
    }

    // ---- Abfragen ----

    /** Aktives Modellverzeichnis (evtl. noch nicht vorhanden — Aufrufer prüft isReady). */
    fun modelDir(): File = modelDir(ModelVariant.ONNX_CPU)

    /** V1: Modellverzeichnis der aktiven Version je Variante. */
    fun modelDir(variant: ModelVariant): File {
        val version = activeVersion(variant) ?: return File(versionsDir, "v-none-${variant.id}")
        return File(versionsDir, version)
    }

    fun activeVersion(): String? = activeVersion(ModelVariant.ONNX_CPU)

    /** V1: aktive Versions-ID je Variante (alter Zeiger ⇒ Variante A). */
    fun activeVersion(variant: ModelVariant): String? =
        if (pointerFile(variant).isFile) {
            pointerFile(variant).readText().trim().takeIf { it.isNotEmpty() }
        } else {
            null
        }

    /** True, wenn alle Dateien der aktiven Version vorhanden sind (ohne Hashprüfung). */
    fun isPresent(): Boolean = isPresent(ModelVariant.ONNX_CPU)

    fun isPresent(variant: ModelVariant): Boolean {
        val dir = modelDir(variant)
        val m = manifestFor(variant)
        return activeVersion(variant) != null && m.all { safeChild(dir, it.storeName, variant).isFile }
    }

    /** Vollprüfung: vorhanden + jede Datei hash-korrekt (teuer, nur Setup/Wartung). */
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
     * Pro Prozess mindestens einmal vollständig verifizierte Varianten.
     * Dient nur dazu, die Vollprüfung nicht bei jeder interaktiven Abfrage
     * erneut über ~670 MB laufen zu lassen.
     */
    private val verifiedThisProcess = java.util.concurrent.ConcurrentHashMap<String, Boolean>()

    /**
     * BEREIT für interaktive Pfade (Mic-Taste, 🐞-Export): ohne zu hashen.
     *
     * Grund (Gemessen 2026-10-30, <device>): `isReady()` = Vollprüfung über
     * ~670 MB. Auf dem Main-Thread der IME war die Tastatur nach 5 000 ms nicht
     * mehr ansprechbar — `am_anr: InputMethod is not responding. Waited 5000ms for
     * MotionEvent` genau auf den Mic-Tap — und der Nutzererindruck war „Eingaben
     * werden ignoriert“. Integritaet wird weiterhin hart geprueft: bei jeder
     * Aktivierung (`activate`, ModelStore:216) und bei `verifyInstalled()`.
     */
    fun isReadyQuick(variant: ModelVariant = ModelVariant.ONNX_CPU): Boolean =
        isPresent(variant) &&
            (verifiedThisProcess[variant.name] == true || sizesMatch(variant))

    /** Wie [status], aber ohne Voll-Hash — fuer Hauptthread-Aufrufe. */
    fun statusQuick(variant: ModelVariant = ModelVariant.ONNX_CPU): ModelStatus = when {
        marker(VERIFY_MARKER) -> ModelStatus.VERIFYING
        marker(DOWNLOAD_MARKER) -> ModelStatus.DOWNLOADING
        activeVersion(variant) == null -> absentOrPartial()
        else -> if (isReadyQuick(variant)) ModelStatus.READY else ModelStatus.BROKEN
    }

    /** Existenz + Groessen je Manifesteintrag, kein Hash. */
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

    /** V1: Status je Variante. */
    fun status(variant: ModelVariant): ModelStatus = when {
        marker(VERIFY_MARKER) -> ModelStatus.VERIFYING
        marker(DOWNLOAD_MARKER) -> ModelStatus.DOWNLOADING
        activeVersion(variant) == null -> absentOrPartial()
        else -> if (verifyInstalled(variant)) ModelStatus.READY else ModelStatus.BROKEN
    }

    private fun absentOrPartial(): ModelStatus =
        // Zeiger fehlt: ABSENT, außer es liegen Downloadreste → weiterhin DOWNLOADING-Spur
        if (downloadsDir.listFiles()?.any { it.name.endsWith(PART_SUFFIX) } == true) {
            ModelStatus.DOWNLOADING
        } else {
            ModelStatus.ABSENT
        }

    // ---- Downloads / Resume ----

    /** Part-Datei (downloads/<name>.part) für Resume — nur whitelisted Namen. */
    fun partFile(name: String): File = safePart(name)

    /** ETag-Sidecar zum Resume-Guard; null, wenn keines gespeichert wurde. */
    fun etagFor(name: String): String? {
        val f = File(downloadsDir, safeName(name) + ETAG_SUFFIX)
        return if (f.isFile) f.readText().trim().takeIf { it.isNotEmpty() } else null
    }

    fun storeEtag(name: String, etag: String?) {
        val f = File(downloadsDir, safeName(name) + ETAG_SUFFIX)
        if (etag == null) f.delete() else f.writeText(etag)
    }

    /** Neue, leere Staging-Version. */
    fun newStagingDir(): File {
        val dir = File(versionsDir, "v-${System.nanoTime()}")
        check(dir.mkdirs() || dir.isDirectory) { "Staging nicht anlegbar: ${dir.path}" }
        return dir
    }

    /**
     * Verschiebt eine fertige, hash-geprüfte Part-Datei per ATOMEM RENAME
     * (File.renameTo auf demselben Dateisystem) in die Staging-Version.
     */
    fun movePartToStaging(name: String, stagingDir: File): Boolean =
        movePartToStaging(name, stagingDir, ModelVariant.ONNX_CPU)

    /** V5: Variante wird durchgereicht — B-Namen sind nur fuer B erlaubt. */
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
     * Aktiviert eine Staging-Version: prüft alle Dateien (Vorhanden +
     * Größe + SHA-256), schreibt den Zeiger atomar (tmp+rename) und löscht
     * alle NICHT aktiven Versionen. Bei Misserfolg bleibt die bisher aktive
     * Version unberührt (DESIGN: „aktive Version bleibt intakt").
     */
    fun activate(stagingDir: File): Boolean = activate(stagingDir, ModelVariant.ONNX_CPU)

    /** V1: Aktivierung je Variante (Pruefung gegen das Varianten-Manifest). */
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
        // Atomarer Zeiger-Wechsel: tmp schreiben, fsync-los (Rename ist die Barriere)
        val target = pointerFile(variant)
        val tmp = File(root, target.name + ".tmp")
        tmp.writeText(stagingName)
        if (!tmp.renameTo(target)) {
            tmp.delete()
            return false
        }
        sweepStaleVersions()
        clearMarkers()
        // F11 P2: die soeben gehashte Version gilt in diesem Prozess als geprüft —
        // interaktive Pfade (Mic-Tap, 🐞-Export) müssen nie erneut über ~670 MB.
        verifiedThisProcess[variant.name] = true
        return true
    }

    /** Löscht alle Versionen außer der aktiven (Platz zurückgewinnen). */
    fun sweepStaleVersions() {
        val active = activeVersion() ?: return
        versionsDir.listFiles()?.forEach { dir ->
            if (dir.isDirectory && dir.name != active) dir.deleteRecursively()
        }
    }

    /** Verwirft alle Downloadreste (.part/.etag) und Marker. */
    fun clearDownloads() {
        downloadsDir.listFiles()?.forEach { it.delete() }
        clearMarkers()
    }

    // ---- Marker (DOWNLOADING/VERIFYING für die Statusanzeige) ----

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

    // ---- Pfad-Traversal-Schutz ----

    /** Erlaubter Basisname aus dem Manifest — ohne '/', '..' etc. */
    private fun safeName(name: String): String = safeName(name, ModelVariant.ONNX_CPU)

    /** V5: Whitelist-Pruefung gegen die VARIANTE (Store kennt seine via manifestFor). */
    private fun safeName(name: String, variant: ModelVariant): String {
        require(isAllowedName(name, variant)) { "Dateiname nicht whitelistet: $name" }
        return name
    }

    private fun isAllowedName(name: String, variant: ModelVariant): Boolean =
        name.isNotEmpty() &&
            '/' !in name && '\\' !in name && name != "." && name != ".." &&
            manifestFor(variant).any { it.storeName == name }

    /**
     * Kind-Pfad mit Doppelschutz: (1) der Name (ggf. mit ".part"-Suffix) muss
     * zur Manifest-Whitelist passen, (2) der kanonische Pfad muss direkt im
     * kanonischen Elternverzeichnis liegen (Traversal).
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
