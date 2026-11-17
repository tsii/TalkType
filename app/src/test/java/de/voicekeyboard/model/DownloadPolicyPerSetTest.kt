package de.voicekeyboard.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.Executor

/**
 * T22d (PLAN_V0146 §4/§6): download policy + wiring PER SET.
 *
 *  (1) ACTIVATION HOLE: `ModelStore.activate(staging, variant)` with an EMPTY
 *      manifest must return false and leave pointer/directories untouched —
 *      an empty list would otherwise activate ANY staging dir vacuously
 *      (risk reported in T22b; production QWEN is pinned since T22b', the
 *      guard is pinned here via an emptyList fixture store).
 *  (2) `DownloadPolicy.spaceNeedFor(variant)` = model bytes + 1.5 GB reserve;
 *      empty manifest ⇒ Unsupported ("modell nicht freigeschaltet");
 *      Parakeet numerically UNCHANGED (2.5 GB floor: max(MIN_FREE_BYTES,
 *      spaceNeedFor(A))).
 *  (3) `canOfferSet(variant, totalRamBytes, freeBytes)`: A always (no RAM
 *      gate); QWEN only with pinned manifest AND RAM >= 8 GB (plan §2).
 *  (4) EXTRA_VARIANT routing: ONE variant decides downloader base URL,
 *      manifest source and store variant — pure mapping, NO network in tests.
 *
 * Existing DownloadPolicy/DownloadController/ModelStore suites run as
 * regression (A behavior unchanged).
 */
class DownloadPolicyPerSetTest {

    @get:Rule
    val tmp = TemporaryFolder()

    // ---- (1) activation hole ----

    @Test
    fun `activate refuses a set with an empty manifest - disk untouched`() {
        // T22b': the production QWEN list is PINNED now — the empty-manifest
        // guard is pinned via a fixture store (constructor manifest = emptyList
        // applies to every variant in the store):
        val root = tmp.newFolder()
        val store = ModelStore(root, emptyList())
        val staging = store.newStagingDir()
        File(staging, "encoder.int8.onnx").writeBytes(byteArrayOf(1))

        assertFalse("empty set must never activate", store.activate(staging, ModelVariant.QWEN3_ASR))

        // pointer/directories untouched:
        assertNull(store.activeVersion(ModelVariant.QWEN3_ASR))
        assertFalse(File(root, "active-qwen3-asr-0.6b-int8.txt").isFile)
        assertTrue("staging dir must survive the refusal", staging.isDirectory)
        assertEquals(ModelStatus.ABSENT, store.statusQuick(ModelVariant.QWEN3_ASR))
    }

    // ---- (2) space need per set ----

    @Test
    fun `spaceNeedFor A stays numerically the old 2_5 GB floor`() {
        val needA = DownloadPolicy.spaceNeedFor(ModelVariant.ONNX_CPU)
        assertTrue(needA is DownloadPolicy.SpaceNeed.Required)
        assertEquals(
            670_619_706L + 1_500_000_000L, // model bytes + 1.5 GB reserve
            (needA as DownloadPolicy.SpaceNeed.Required).bytes,
        )
        // effective A floor: max(MIN_FREE_BYTES, spaceNeedFor(A)) = 2.5 GB —
        // i.e. decide() keeps its EXACT old numbers:
        assertEquals(DownloadPolicy.MIN_FREE_BYTES, DownloadPolicy.requiredFreeBytes(ModelVariant.ONNX_CPU))
        assertEquals(
            DownloadPolicy.Decision.InsufficientSpace(DownloadPolicy.MIN_FREE_BYTES - 1),
            DownloadPolicy.decide(true, DownloadPolicy.MIN_FREE_BYTES - 1, ModelStatus.ABSENT),
        )
        assertEquals(
            DownloadPolicy.Decision.Start,
            DownloadPolicy.decide(true, DownloadPolicy.MIN_FREE_BYTES, ModelStatus.ABSENT),
        )
        // B (debug-only) is a full set too:
        val needB = DownloadPolicy.spaceNeedFor(ModelVariant.LITERT_CPU)
        assertEquals(683_298_069L + 1_500_000_000L, (needB as DownloadPolicy.SpaceNeed.Required).bytes)
    }

    @Test
    fun `spaceNeedFor QWEN is enabled in production - pinned numbers and floor`() {
        // V0.1.54: BASE_URL_QWEN is pinned (litterbox host) — the production
        // set reports the pinned numbers now (1_704_302_777 model bytes +
        // 1.5 GB reserve > A floor):
        assertEquals(
            DownloadPolicy.SpaceNeed.Required(1_704_302_777L + 1_500_000_000L),
            DownloadPolicy.spaceNeedFor(ModelVariant.QWEN3_ASR),
        )
        assertEquals(3_204_302_777L, DownloadPolicy.requiredFreeBytes(ModelVariant.QWEN3_ASR))
        // an EMPTY list (fixture injection) stays Unsupported("not unlocked"):
        assertEquals(
            DownloadPolicy.SpaceNeed.Unsupported(),
            DownloadPolicy.spaceNeedFor(ModelVariant.QWEN3_ASR, emptyList(), ModelManifest.BASE_URL_QWEN),
        )
    }

    // ---- (3) canOfferSet matrix: RAM 7.9 / 8 / 12 GB x manifest/baseUrl fixtures ----

    @Test
    fun `canOfferSet matrix - RAM 7_9 8 12 GB x manifest or baseUrl fixtures`() {
        val ram79 = 8_000_000_000L // 7.9 GB row: clearly below the 8 GiB gate
        val ram8 = 8L shl 30 // exactly the gate (plan §2: >= 8 GB)
        val ram12 = 12L shl 30
        val plentyFree = 10_000_000_000L
        // VK-2: fixture base URL = the set is enabled (host pinned)
        val enabled = "https://download-server.example/TalkType/models/qwen3-asr-0.6b-int8/"

        // A: ALWAYS offerable (no RAM gate — plan §2: "RAM gate (Qwen only)"),
        // as long as the (unchanged) 2.5 GB space floor holds:
        for (ram in listOf(ram79, ram8, ram12)) {
            assertTrue("A at ${ram}B RAM", DownloadPolicy.canOfferSet(ModelVariant.ONNX_CPU, ram, plentyFree))
        }
        assertFalse(
            "A below the 2.5 GB floor stays blocked (E3 unchanged)",
            DownloadPolicy.canOfferSet(ModelVariant.ONNX_CPU, ram12, DownloadPolicy.MIN_FREE_BYTES - 1),
        )
        assertTrue(DownloadPolicy.canOfferSet(ModelVariant.ONNX_CPU, ram12, DownloadPolicy.MIN_FREE_BYTES))

        // QWEN in PRODUCTION (V0.1.54: base URL PINNED): RAM gate at 8 GB +
        // the 3.2 GB space floor of the set (1.7 GB model + 1.5 GB reserve):
        assertFalse("RAM gate blocks below 8 GB", DownloadPolicy.canOfferSet(ModelVariant.QWEN3_ASR, ram79, plentyFree))
        assertTrue(DownloadPolicy.canOfferSet(ModelVariant.QWEN3_ASR, ram8, plentyFree))
        assertTrue(DownloadPolicy.canOfferSet(ModelVariant.QWEN3_ASR, ram12, plentyFree))
        assertFalse(
            "below the QWEN space floor",
            DownloadPolicy.canOfferSet(ModelVariant.QWEN3_ASR, ram12, 3_204_302_776L),
        )
        assertTrue(
            DownloadPolicy.canOfferSet(ModelVariant.QWEN3_ASR, ram12, 3_204_302_777L),
        )

        // QWEN with an EMPTY manifest (fixture): never offerable —
        // "modell nicht freigeschaltet", no matter the RAM/URL:
        for (ram in listOf(ram79, ram8, ram12)) {
            assertFalse(DownloadPolicy.canOfferSet(ModelVariant.QWEN3_ASR, ram, plentyFree, emptyList(), enabled))
        }

        // B (debug-only, full set): offerable without a RAM gate:
        assertTrue(DownloadPolicy.canOfferSet(ModelVariant.LITERT_CPU, ram79, plentyFree))
    }

    // ---- (3b) V0.1.53 regression: an EMPTY base URL offers NOTHING ----

    @Test
    fun `an inert set offers NOTHING - regression for every not-enabled set`() {
        // V0.1.54: QWEN and the U2 sets have PINNED hosts — only the 1.7B set
        // stays inert in PRODUCTION; the empty-base-URL guard stays pinned
        // via the baseUrl="" fixture for every set:
        for (v in listOf(
            ModelVariant.QWEN3_ASR,
            ModelVariant.BART_SPELLING_EN,
            ModelVariant.BART_SPELLING_DE,
        )) {
            assertNull("no free-space floor with an empty base: ${v.id}", DownloadPolicy.requiredFreeBytes(v, baseUrl = ""))
            assertFalse(
                "never offered with an empty base, no matter the RAM/space: ${v.id}",
                DownloadPolicy.canOfferSet(v, totalRamBytes = 16L shl 30, freeBytes = 8L shl 30, baseUrl = ""),
            )
        }
        // the 1.7B set stays inert in PRODUCTION (empty manifest + empty base):
        assertNull(DownloadPolicy.requiredFreeBytes(ModelVariant.QWEN3_ASR_17B))
        assertFalse(
            DownloadPolicy.canOfferSet(ModelVariant.QWEN3_ASR_17B, totalRamBytes = 16L shl 30, freeBytes = 8L shl 30),
        )
    }

    // ---- (4) EXTRA_VARIANT routing (pure mapping, no network) ----

    @Test
    fun `EXTRA_VARIANT wiring maps one variant to baseUrl manifest and store variant`() {
        val q = downloadWiring("qwen3-asr-0.6b-int8")
        assertEquals(ModelVariant.QWEN3_ASR, q.variant)
        assertEquals(ModelManifest.baseUrlFor(ModelVariant.QWEN3_ASR), q.baseUrl)
        assertEquals(ModelManifest.BASE_URL_QWEN, q.baseUrl)
        assertEquals(ModelManifest.forVariant(ModelVariant.QWEN3_ASR), q.manifest)
        assertEquals("production QWEN list is pinned (6 files)", 6, q.manifest.size)
        // V0.1.53 (PLAN_QWEN17B S1): the 1.7B id IS a set now — but INERT
        // (empty manifest + empty base URL): the wiring maps to it and any
        // download fails fast BEFORE a network attempt (controller guard).
        val w17 = downloadWiring("qwen3-asr-1.7b-int8")
        assertEquals(ModelVariant.QWEN3_ASR_17B, w17.variant)
        assertTrue("no checked files pinned yet", w17.manifest.isEmpty())
        assertEquals("", w17.baseUrl)

        // default (extra absent) and unknown ids: A, wiring unchanged:
        for (raw in listOf(null, "bogus", "QWEN3-ASR-1.7B-INT8")) {
            val a = downloadWiring(raw)
            assertEquals(ModelVariant.ONNX_CPU, a.variant)
            assertEquals(ModelManifest.BASE_URL, a.baseUrl)
            assertEquals(ModelManifest.DEFAULT, a.manifest)
        }
    }

    @Test
    fun `controller routes markers parts and activation per variant`() {
        val bytes = byteArrayOf(1, 2, 3)
        val files = listOf(ModelFile("model.bin", 3L, Sha256.ofBytes(bytes)))
        val root = tmp.newFolder()
        val store = ModelStore(root, files)
        val controller = DownloadController(
            store,
            { // fake op: writes the part file OF THE VARIANT (no network)
                object : DownloadController.DownloadOp {
                    override fun download(file: ModelFile, listener: DownloadProgressListener?): Boolean {
                        store.partFile(file.storeName, ModelVariant.LITERT_CPU).writeBytes(bytes)
                        return true
                    }
                }
            },
            files,
            DirectExecutor(),
            variant = ModelVariant.LITERT_CPU,
        )

        controller.start()

        assertEquals(DownloadController.State.Ready, controller.state)
        // store paths of the OWN variant: pointer + READY, no fallback to A:
        assertTrue(File(root, "active-litert-cpu.txt").isFile)
        assertEquals(ModelStatus.READY, store.statusQuick(ModelVariant.LITERT_CPU))
        assertTrue(store.isReady(ModelVariant.LITERT_CPU))
        // set A untouched:
        assertEquals(ModelStatus.ABSENT, store.statusQuick(ModelVariant.ONNX_CPU))
        assertNull(store.activeVersion(ModelVariant.ONNX_CPU))
    }

    @Test
    fun `controller refuses an empty-manifest set without any download op`() {
        val store = ModelStore(tmp.newFolder(), emptyList()) // fixture: nothing unlocked
        val controller = DownloadController(
            store,
            { throw AssertionError("no download op may run for a set that is not unlocked") },
            emptyList(),
            DirectExecutor(),
            variant = ModelVariant.QWEN3_ASR,
        )

        controller.start()

        val failed = controller.state
        assertTrue("empty set => FAILED, never READY", failed is DownloadController.State.Failed)
        assertTrue(
            "reason: set not unlocked",
            (failed as DownloadController.State.Failed).error is ModelError.Storage,
        )
        assertEquals(ModelStatus.ABSENT, store.statusQuick(ModelVariant.QWEN3_ASR))
    }

    @Test
    fun `controller refuses a not-enabled set without any download op (VK-2)`() {
        // production 1.7B (V0.1.54): no pinned files AND base URL EMPTY —
        // the run must fail BEFORE any staging/marker/download op.
        val store = ModelStore(tmp.newFolder())
        val controller = DownloadController(
            store,
            { throw AssertionError("no download op may run for a set that is not enabled") },
            ModelManifest.forVariant(ModelVariant.QWEN3_ASR_17B),
            DirectExecutor(),
            variant = ModelVariant.QWEN3_ASR_17B,
        )

        controller.start()

        val failed = controller.state
        assertTrue("not-enabled set => FAILED, never READY", failed is DownloadController.State.Failed)
        assertTrue(
            "reason: set not enabled/unlocked",
            (failed as DownloadController.State.Failed).error is ModelError.Storage,
        )
        assertEquals(ModelStatus.ABSENT, store.statusQuick(ModelVariant.QWEN3_ASR_17B))
    }

    private class DirectExecutor : Executor {
        override fun execute(command: Runnable) = command.run()
    }
}
