package de.voicekeyboard.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * F11 (P0) — der interaktive Pfad der IME darf nicht hashen.
 *
 * Gemessen auf <device>: der Tap auf die neue Mikrofon-Taste führte zu
 * `am_anr: InputMethod is not responding. Waited 5000ms for MotionEvent`, weil
 * `onMicDown` → `ModelStore.isReady()` → SHA-256 über ~670 MB auf dem
 * Main-Thread lief. Diese Tests fixieren den Ersatz `isReadyQuick()`:
 *
 *  - interaktiv: Existenz + Größe reichen (Vollprüfung bleibt bei Aktivierung,
 *    ModelStore.activate → Zeile 216, und bei verifyInstalled),
 *  - einmal pro Prozess vollständig geprüft → kein erneutes Hashen,
 *  - `status()` (Vollprüfung) und `statusQuick()` (interaktiv) bleiben
 *    unterscheidbar, damit die Setup-Oberfläche weiter hart prüft.
 */
class ModelStoreQuickReadyTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun miniManifest(a: ByteArray, b: ByteArray): List<ModelFile> = listOf(
        ModelFile("model.bin", a.size.toLong(), Sha256.ofBytes(a)),
        ModelFile("vocab.txt", b.size.toLong(), Sha256.ofBytes(b)),
    )

    private fun activatedStore(root: File, a: ByteArray, b: ByteArray): ModelStore {
        val store = ModelStore(root, miniManifest(a, b))
        val staging = store.newStagingDir()
        File(staging, "model.bin").writeBytes(a)
        File(staging, "vocab.txt").writeBytes(b)
        assertTrue("Aktivierung erwartet", store.activate(staging))
        return store
    }

    @Test
    fun `ohne Modell meldet auch die schnelle Prüfung nicht bereit`() {
        val store = ModelStore(tmp.newFolder(), miniManifest(byteArrayOf(1), byteArrayOf(2)))
        assertFalse(store.isReadyQuick())
        assertEquals(ModelStatus.ABSENT, store.statusQuick())
    }

    @Test
    fun `schnelle Prüfung prüft Größe nicht Hash — Vollprüfung bleibt scharf`() {
        val a = byteArrayOf(1, 2, 3)
        val b = byteArrayOf(9, 8)
        val store = activatedStore(tmp.newFolder(), a, b)
        // Inhalt austauschen, Größe identisch lassen (exakt der Korruptionsfall).
        File(store.modelDir(), "model.bin").writeBytes(byteArrayOf(7, 7, 7))
        // Frische Instanz = kein Prozess-Cache.
        val root = store.modelDir().parentFile!!.parentFile
        val fresh = ModelStore(root, miniManifest(a, b))
        assertFalse("Vollprüfung muss die Korruption erkennen", fresh.verifyInstalled())
        assertTrue("interaktive Prüfung: Größe ja, Hash nein", fresh.isReadyQuick())
        assertEquals(ModelStatus.READY, fresh.statusQuick())
        assertEquals(ModelStatus.BROKEN, fresh.status())
    }

    @Test
    fun `Prozess-Cache ueberspringt nie die Existenzpruefung`() {
        val a = byteArrayOf(1, 2, 3)
        val b = byteArrayOf(9, 8)
        val store = activatedStore(tmp.newFolder(), a, b)
        assertTrue(store.isReady()) // füllt den Prozess-Cache
        assertTrue(File(store.modelDir(), "vocab.txt").delete())
        assertFalse("fehlende Datei darf nicht als bereit gelten", store.isReadyQuick())
        assertEquals(ModelStatus.BROKEN, store.statusQuick())
    }

    @Test
    fun `nach erfolgreicher Vollprüfung wird im Prozess nicht erneut gehasht`() {
        val a = byteArrayOf(1, 2, 3)
        val b = byteArrayOf(9, 8)
        val store = activatedStore(tmp.newFolder(), a, b)
        assertTrue(store.isReady()) // füllt den Prozess-Cache
        File(store.modelDir(), "vocab.txt").writeBytes(byteArrayOf(1, 1)) // gleiche Größe
        assertTrue("Cache: bereit ohne erneutes Hashen", store.isReadyQuick())
        assertFalse("Vollprüfung erkennt die Änderung weiterhin", store.verifyInstalled())
    }
}
