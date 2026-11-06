package de.voicekeyboard.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * T22c (PLAN_V0146 §4): TWO model sets in ONE store must not interfere.
 * Pins the Blocker list from the plan 'Facts read' (docs/PLAN_V0146.md §0):
 *
 *  B1 `sweepStaleVersions()` kept only `activeVersion()` of variant A
 *     (ModelStore.kt:289 before the fix) → activating set 2 DELETED set 1.
 *     RED before the fix: [B1 activate of set 2 keeps the version dir of set 1].
 *  B2 `.downloading`/`.verifying` markers were GLOBAL → a download of set 2
 *     flipped set 1 to DOWNLOADING/VERIFYING.
 *     RED: [B2 markers of set 2 leave set 1 READY].
 *  B3 `absentOrPartial()` reacted to ANY `.part` → a leftover of set 2 made
 *     set 1 look DOWNLOADING (attributeless download trail).
 *     RED: [B3 part leftover of set 2 leaves set 1 ABSENT].
 *  B4 `manifestSetId()` hashed only the constructor manifest (variant A in
 *     production) → both sets shared ONE network-revoke invite id.
 *     RED: no variant parameter existed at all.
 *  deleteSet (plan §2 "Delete other model"): frees a non-active set,
 *  refuses the active one without deleting anything.
 *
 * Stand-in two-set scenario (QWEN3_ASR arrives only in T22b, plan §4):
 * V1 = ONNX_CPU, V2 = LITERT_CPU. Tests stay purely generic per variant.
 */
class ModelStoreTwoSetsTest {

    @get:Rule
    val tmp = TemporaryFolder()

    /** Mini manifest with two small files; hashes computed at runtime. */
    private fun miniManifest(a: ByteArray, b: ByteArray): List<ModelFile> = listOf(
        ModelFile("model.bin", a.size.toLong(), Sha256.ofBytes(a)),
        ModelFile("vocab.txt", b.size.toLong(), Sha256.ofBytes(b)),
    )

    /** Activates a complete staging version for [variant] and returns its dir. */
    private fun activateSet(store: ModelStore, variant: ModelVariant, a: ByteArray, b: ByteArray): File {
        val staging = store.newStagingDir()
        File(staging, "model.bin").writeBytes(a)
        File(staging, "vocab.txt").writeBytes(b)
        assertTrue("activation of ${variant.id} expected", store.activate(staging, variant))
        return staging
    }

    // ---- B1: sweep must not cross sets ----

    @Test
    fun `B1 activate of set 2 keeps the version dir of set 1`() {
        val a = byteArrayOf(1, 2, 3); val b = byteArrayOf(9, 8)
        val root = tmp.newFolder()
        val store = ModelStore(root, miniManifest(a, b))
        val v1Dir = activateSet(store, ModelVariant.ONNX_CPU, a, b)
        assertEquals(ModelStatus.READY, store.status(ModelVariant.ONNX_CPU))

        val v2Dir = activateSet(store, ModelVariant.LITERT_CPU, a, b)

        // B1 (RED before the fix): sweep deleted v1Dir because only the
        // activeVersion() of variant A survived.
        assertTrue("V1 version dir must survive the V2 activation", v1Dir.isDirectory)
        assertTrue("V2 version dir must exist", v2Dir.isDirectory)
        assertEquals(ModelStatus.READY, store.status(ModelVariant.ONNX_CPU))
        assertEquals(ModelStatus.READY, store.status(ModelVariant.LITERT_CPU))
        assertEquals(v1Dir.name, store.activeVersion(ModelVariant.ONNX_CPU))
        assertEquals(v2Dir.name, store.activeVersion(ModelVariant.LITERT_CPU))
    }

    // ---- B2: markers per set ----

    @Test
    fun `B2 markers of set 2 leave set 1 READY`() {
        val a = byteArrayOf(1, 2, 3); val b = byteArrayOf(9, 8)
        val root = tmp.newFolder()
        val store = ModelStore(root, miniManifest(a, b))
        activateSet(store, ModelVariant.ONNX_CPU, a, b)

        store.markDownloading(ModelVariant.LITERT_CPU)
        assertEquals(
            "a V2 download must not demote V1 (B2)",
            ModelStatus.READY,
            store.statusQuick(ModelVariant.ONNX_CPU),
        )
        store.markVerifying(ModelVariant.LITERT_CPU)
        assertEquals(ModelStatus.READY, store.statusQuick(ModelVariant.ONNX_CPU))

        // the OWN marker still counts for the own set:
        store.markDownloading(ModelVariant.ONNX_CPU)
        assertEquals(ModelStatus.DOWNLOADING, store.statusQuick(ModelVariant.ONNX_CPU))
        assertEquals(ModelStatus.VERIFYING, store.statusQuick(ModelVariant.LITERT_CPU))

        // clearing the markers of one set leaves the other one untouched:
        store.clearMarkers(ModelVariant.ONNX_CPU)
        assertEquals(ModelStatus.READY, store.statusQuick(ModelVariant.ONNX_CPU))
        assertEquals(ModelStatus.VERIFYING, store.statusQuick(ModelVariant.LITERT_CPU))
    }

    // ---- B3: .part only counts for the own set ----

    @Test
    fun `B3 part leftover of set 2 leaves set 1 ABSENT`() {
        // real manifests (no mini manifest): the store names of A and B are disjoint
        val store = ModelStore(tmp.newFolder())
        val bName = "parakeet_tdt_nemo_encoder_cached_int8_dynamic_litert.tflite"
        store.partFile(bName, ModelVariant.LITERT_CPU).writeBytes(byteArrayOf(1))
        assertEquals(ModelStatus.DOWNLOADING, store.statusQuick(ModelVariant.LITERT_CPU))
        // B3 (RED before the fix): absentOrPartial() reacted to ANY .part
        assertEquals(
            "a V2 part must not fake a V1 download (B3)",
            ModelStatus.ABSENT,
            store.statusQuick(ModelVariant.ONNX_CPU),
        )

        // symmetric: an A part leaves B ABSENT
        val store2 = ModelStore(tmp.newFolder())
        store2.partFile("encoder-model.int8.onnx").writeBytes(byteArrayOf(1))
        assertEquals(ModelStatus.DOWNLOADING, store2.statusQuick(ModelVariant.ONNX_CPU))
        assertEquals(ModelStatus.ABSENT, store2.statusQuick(ModelVariant.LITERT_CPU))
    }

    // ---- B4: manifestSetId per set, stable ----

    @Test
    fun `B4 manifestSetId differs per set and is stable`() {
        val store = ModelStore(tmp.newFolder())
        val idA = store.manifestSetId(ModelVariant.ONNX_CPU)
        val idB = store.manifestSetId(ModelVariant.LITERT_CPU)
        assertNotEquals("each set needs its own invite id (B4)", idA, idB)
        // stable over calls on the same instance:
        assertEquals(idA, store.manifestSetId(ModelVariant.ONNX_CPU))
        assertEquals(idB, store.manifestSetId(ModelVariant.LITERT_CPU))
        // stable over instances (pure function of the manifest list):
        val fresh = ModelStore(tmp.newFolder())
        assertEquals(idA, fresh.manifestSetId(ModelVariant.ONNX_CPU))
        assertEquals(idB, fresh.manifestSetId(ModelVariant.LITERT_CPU))
    }

    // ---- deleteSet ----

    @Test
    fun `deleteSet frees a non-active set completely`() {
        val a = byteArrayOf(1, 2, 3); val b = byteArrayOf(9, 8)
        val root = tmp.newFolder()
        val store = ModelStore(root, miniManifest(a, b))
        val v1Dir = activateSet(store, ModelVariant.ONNX_CPU, a, b)
        val v2Dir = activateSet(store, ModelVariant.LITERT_CPU, a, b)
        store.partFile("model.bin", ModelVariant.ONNX_CPU).writeBytes(byteArrayOf(1))
        store.storeEtag("model.bin", "\"e1\"")
        store.markVerifying(ModelVariant.ONNX_CPU)

        assertTrue(store.deleteSet(ModelVariant.ONNX_CPU, activeVariant = ModelVariant.LITERT_CPU))

        assertFalse("V1 version dir gone", v1Dir.isDirectory)
        assertEquals(ModelStatus.ABSENT, store.statusQuick(ModelVariant.ONNX_CPU))
        assertFalse("V1 part gone", File(root, "downloads/model.bin.part").isFile)
        assertFalse("V1 etag gone", store.etagFor("model.bin") != null)
        // V2 untouched: dir, pointer, status
        assertTrue(v2Dir.isDirectory)
        assertEquals(ModelStatus.READY, store.status(ModelVariant.LITERT_CPU))
    }

    @Test
    fun `deleteSet refuses the active set without deleting anything`() {
        val a = byteArrayOf(1, 2, 3); val b = byteArrayOf(9, 8)
        val root = tmp.newFolder()
        val store = ModelStore(root, miniManifest(a, b))
        activateSet(store, ModelVariant.ONNX_CPU, a, b)
        val v2Dir = activateSet(store, ModelVariant.LITERT_CPU, a, b)

        assertFalse(
            "the active set must be refused",
            store.deleteSet(ModelVariant.LITERT_CPU, activeVariant = ModelVariant.LITERT_CPU),
        )
        assertEquals(ModelStatus.READY, store.statusQuick(ModelVariant.LITERT_CPU))
        assertTrue("refusal deletes nothing", v2Dir.isDirectory)
        assertEquals(
            "V2 version dir intact on refusal",
            ModelStatus.READY,
            store.status(ModelVariant.LITERT_CPU),
        )
    }
}
