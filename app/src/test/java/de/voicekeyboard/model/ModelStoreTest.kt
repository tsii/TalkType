package de.voicekeyboard.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Reine JVM-Tests für [ModelStore] mit temporären Verzeichnissen und einem
 * Mini-Manifest (kleine Dateien, echte SHA-256-Werte zur Laufzeit berechnet —
 * kein Netzwerk, keine echten 671 MB).
 */
class ModelStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    /** Mini-Manifest mit zwei kleinen Dateien; Hashes zur Laufzeit berechnet. */
    private fun miniManifest(a: ByteArray, b: ByteArray): List<ModelFile> = listOf(
        ModelFile("model.bin", a.size.toLong(), Sha256.ofBytes(a)),
        ModelFile("vocab.txt", b.size.toLong(), Sha256.ofBytes(b)),
    )

    private fun write(dir: File, name: String, bytes: ByteArray): File =
        File(dir, name).apply { writeBytes(bytes) }

    // ---- Status ohne alles ----

    @Test fun `leerer Store ist ABSENT ohne Reste`() {
        val store = ModelStore(tmp.newFolder(), miniManifest(byteArrayOf(1), byteArrayOf(2)))
        assertEquals(ModelStatus.ABSENT, store.status())
        assertFalse(store.isPresent())
        assertFalse(store.isReady())
        assertNull(store.activeVersion())
    }

    // ---- vorhanden / fehlend / hash-falsch ----

    @Test fun `vollstaendige und korrekte Version ist READY`() {
        val a = byteArrayOf(1, 2, 3); val b = byteArrayOf(9, 8)
        val root = tmp.newFolder()
        val store = ModelStore(root, miniManifest(a, b))
        val staging = store.newStagingDir()
        write(staging, "model.bin", a)
        write(staging, "vocab.txt", b)
        assertTrue(store.activate(staging))
        assertEquals(ModelStatus.READY, store.status())
        assertTrue(store.isReady())
        assertTrue(store.verifyInstalled())
    }

    @Test fun `fehlende Datei in aktiver Version ist BROKEN`() {
        val a = byteArrayOf(1, 2, 3); val b = byteArrayOf(9, 8)
        val root = tmp.newFolder()
        val store = ModelStore(root, miniManifest(a, b))
        val staging = store.newStagingDir()
        write(staging, "model.bin", a)
        write(staging, "vocab.txt", b)
        assertTrue(store.activate(staging))
        // Datei nachträglich beschädigen (fehlend)
        File(store.modelDir(), "vocab.txt").delete()
        assertEquals(ModelStatus.BROKEN, store.status())
        assertFalse(store.isReady())
    }

    @Test fun `veraenderte Datei (Hash falsch) ist BROKEN`() {
        val a = byteArrayOf(1, 2, 3); val b = byteArrayOf(9, 8)
        val root = tmp.newFolder()
        val store = ModelStore(root, miniManifest(a, b))
        val staging = store.newStagingDir()
        write(staging, "model.bin", a)
        write(staging, "vocab.txt", b)
        assertTrue(store.activate(staging))
        // Byte kippen → Hash weicht ab (ohne Längenänderung)
        val f = File(store.modelDir(), "vocab.txt")
        val broken = b.copyOf().also { it[0] = 7 }
        f.writeBytes(broken)
        assertFalse(store.verifyInstalled())
        assertEquals(ModelStatus.BROKEN, store.status())
    }

    // ---- .part-Rest / Download-Marker ----

    @Test fun `part-Rest ohne Zeiger zeigt DOWNLOADING`() {
        val root = tmp.newFolder()
        val store = ModelStore(root, miniManifest(byteArrayOf(1), byteArrayOf(2)))
        store.partFile("model.bin").writeBytes(byteArrayOf(1))
        assertEquals(ModelStatus.DOWNLOADING, store.status())
        assertEquals(ModelStatus.DOWNLOADING, store.status()) // idempotent abfragbar
    }

    @Test fun `clearDownloads entfernt part und etag Reste`() {
        val root = tmp.newFolder()
        val store = ModelStore(root, miniManifest(byteArrayOf(1), byteArrayOf(2)))
        store.partFile("model.bin").writeBytes(byteArrayOf(1))
        store.storeEtag("model.bin", "\"v1\"")
        store.clearDownloads()
        assertNull(store.etagFor("model.bin"))
        assertEquals(ModelStatus.ABSENT, store.status())
    }

    @Test fun `marker DOWNLOADING und VERIFYING setzen sich vor ABSENT-READY`() {
        val root = tmp.newFolder()
        val store = ModelStore(root, miniManifest(byteArrayOf(1), byteArrayOf(2)))
        store.markDownloading()
        assertEquals(ModelStatus.DOWNLOADING, store.status())
        store.markVerifying()
        assertEquals(ModelStatus.VERIFYING, store.status())
        store.clearMarkers()
        assertEquals(ModelStatus.ABSENT, store.status())
    }

    // ---- Aktivierung: atomar, kein Mischzustand ----

    @Test fun `activate bricht ab bei unvollstaendiger Staging Version - aktive bleibt intakt`() {
        val a = byteArrayOf(1, 2, 3); val b = byteArrayOf(9, 8)
        val root = tmp.newFolder()
        val store = ModelStore(root, miniManifest(a, b))
        // Version 1 aktiv
        val v1 = store.newStagingDir()
        write(v1, "model.bin", a); write(v1, "vocab.txt", b)
        assertTrue(store.activate(v1))
        // Version 2 unvollständig: eine Datei falsch (Hash-Mismatch in Staging)
        val v2 = store.newStagingDir()
        write(v2, "model.bin", byteArrayOf(42)); write(v2, "vocab.txt", b)
        assertFalse(store.activate(v2))
        // Aktive Version unverändert: READY, kein Mischzustand
        assertEquals(ModelStatus.READY, store.status())
        assertEquals(a.toList(), File(store.modelDir(), "model.bin").readBytes().toList())
        assertEquals(v1.name, store.activeVersion())
    }

    @Test fun `activate loescht alte Versionen - Platz zurueckgewonnen`() {
        val a = byteArrayOf(1, 2, 3); val b = byteArrayOf(9, 8)
        val root = tmp.newFolder()
        val store = ModelStore(root, miniManifest(a, b))
        val v1 = store.newStagingDir()
        write(v1, "model.bin", a); write(v1, "vocab.txt", b)
        assertTrue(store.activate(v1))
        val v2 = store.newStagingDir()
        write(v2, "model.bin", a); write(v2, "vocab.txt", b)
        assertTrue(store.activate(v2))
        assertEquals(v2.name, store.activeVersion())
        // v1-Verzeichnis weg (nur die aktive Version bleibt)
        val versions = File(root, "versions").listFiles()?.map { it.name } ?: emptyList()
        assertEquals(listOf(v2.name), versions)
    }

    @Test fun `movePartToStaging verschiebt part atomar und entfernt etag`() {
        val a = byteArrayOf(1, 2, 3)
        val root = tmp.newFolder()
        val store = ModelStore(root, miniManifest(a, byteArrayOf(7)))
        val part = store.partFile("model.bin"); part.writeBytes(a)
        store.storeEtag("model.bin", "\"e1\"")
        val staging = store.newStagingDir()
        assertTrue(store.movePartToStaging("model.bin", staging))
        assertFalse(part.isFile)                 // weg aus downloads
        assertNull(store.etagFor("model.bin"))   // Etag mit entsorgt
        assertEquals(a.toList(), File(staging, "model.bin").readBytes().toList())
    }

    // ---- Pfad-Traversal-Schutz ----

    @Test(expected = IllegalArgumentException::class)
    fun `partFile weist unbekannten Dateinamen ab`() {
        val store = ModelStore(tmp.newFolder(), miniManifest(byteArrayOf(1), byteArrayOf(2)))
        store.partFile("boese.bin")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `partFile weist Traversal ab - Slashes`() {
        val store = ModelStore(tmp.newFolder(), miniManifest(byteArrayOf(1), byteArrayOf(2)))
        store.partFile("../model.bin")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `partFile weist Punktpfad ab`() {
        val store = ModelStore(tmp.newFolder(), miniManifest(byteArrayOf(1), byteArrayOf(2)))
        store.partFile("..")
    }

    // ---- Manifest-Integrität (Werte aus docs/MODEL_SHA256.txt) ----

    @Test fun `Default-Manifest enthaelt alle vier Dateien mit 671 MB Summe`() {
        assertEquals(4, ModelManifest.DEFAULT.size)
        assertEquals(670_619_706L, ModelManifest.TOTAL_BYTES) // 639,4 KiB + 671 MB int8 laut ASR_CONTRACT
        assertEquals(
            "a9fde1486ebfcc08f328d75ad4610c67835fea58c73ba57e3209a6f6cf019e9f",
            ModelManifest.fileByName("nemo128.onnx")?.sha256,
        )
        assertEquals(
            "d58544679ea4bc6ac563d1f545eb7d474bd6cfa467f0a6e2c1dc1c7d37e3c35d",
            ModelManifest.fileByName("vocab.txt")?.sha256,
        )
        assertTrue(ModelManifest.isAllowedName("vocab.txt"))
        assertFalse(ModelManifest.isAllowedName("böse/onnx"))
    }
}
