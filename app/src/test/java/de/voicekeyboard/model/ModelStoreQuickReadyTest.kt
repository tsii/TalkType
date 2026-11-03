package de.voicekeyboard.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * F11 (P0) — the interactive path of the IME must not hash.
 *
 * Measured on <device>: the tap on the new microphone key led to
 * `am_anr: InputMethod is not responding. Waited 5000ms for MotionEvent`,
 * because `onMicDown` → `ModelStore.isReady()` → SHA-256 over ~670 MB ran on
 * the main thread. These tests pin down the replacement `isReadyQuick()`:
 *
 *  - interactive: existence + size suffice (the full check stays at
 *    activation, ModelStore.activate → line 216, and at verifyInstalled),
 *  - fully checked once per process → no repeated hashing,
 *  - `status()` (full check) and `statusQuick()` (interactive) remain
 *    distinguishable so the setup surface keeps checking hard.
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
        assertTrue("activation expected", store.activate(staging))
        return store
    }

    @Test
    fun `without a model even the quick check does not report ready`() {
        val store = ModelStore(tmp.newFolder(), miniManifest(byteArrayOf(1), byteArrayOf(2)))
        assertFalse(store.isReadyQuick())
        assertEquals(ModelStatus.ABSENT, store.statusQuick())
    }

    @Test
    fun `quick check checks size not hash - the full check stays sharp`() {
        val a = byteArrayOf(1, 2, 3)
        val b = byteArrayOf(9, 8)
        val store = activatedStore(tmp.newFolder(), a, b)
        // swap the content, keep the size identical (exactly the corruption case).
        File(store.modelDir(), "model.bin").writeBytes(byteArrayOf(7, 7, 7))
        // fresh instance = no process cache.
        val root = store.modelDir().parentFile!!.parentFile
        val fresh = ModelStore(root, miniManifest(a, b))
        assertFalse("the full check must detect the corruption", fresh.verifyInstalled())
        assertTrue("interactive check: size yes, hash no", fresh.isReadyQuick())
        assertEquals(ModelStatus.READY, fresh.statusQuick())
        assertEquals(ModelStatus.BROKEN, fresh.status())
    }

    @Test
    fun `the process cache never skips the existence check`() {
        val a = byteArrayOf(1, 2, 3)
        val b = byteArrayOf(9, 8)
        val store = activatedStore(tmp.newFolder(), a, b)
        assertTrue(store.isReady()) // fills the process cache
        assertTrue(File(store.modelDir(), "vocab.txt").delete())
        assertFalse("a missing file must not count as ready", store.isReadyQuick())
        assertEquals(ModelStatus.BROKEN, store.statusQuick())
    }

    @Test
    fun `after a successful full check the process does not hash again`() {
        val a = byteArrayOf(1, 2, 3)
        val b = byteArrayOf(9, 8)
        val store = activatedStore(tmp.newFolder(), a, b)
        assertTrue(store.isReady()) // fills the process cache
        File(store.modelDir(), "vocab.txt").writeBytes(byteArrayOf(1, 1)) // same size
        assertTrue("cache: ready without hashing again", store.isReadyQuick())
        assertFalse("the full check still detects the change", store.verifyInstalled())
    }
}
