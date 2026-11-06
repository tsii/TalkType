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
    fun `spaceNeedFor QWEN is not enabled in production - pinned numbers via baseUrl fixture`() {
        // VK-2: the production base URL is EMPTY (server host pinned at upload
        // time) — the set reports Unsupported("not enabled yet"):
        assertEquals(
            DownloadPolicy.SpaceNeed.Unsupported(DownloadPolicy.SpaceNeed.REASON_NOT_ENABLED),
            DownloadPolicy.spaceNeedFor(ModelVariant.QWEN3_ASR),
        )
        assertNull(
            "no free-space floor while not enabled",
            DownloadPolicy.requiredFreeBytes(ModelVariant.QWEN3_ASR),
        )
        // the PINNED numbers stay sharp via the baseUrl fixture (host pinned =
        // one-line unlock): 1_704_302_777 model bytes + 1.5 GB reserve > A floor.
        val enabled = "https://download-server.example/TalkType/models/qwen3-asr-0.6b-int8/"
        val need = DownloadPolicy.spaceNeedFor(ModelVariant.QWEN3_ASR, baseUrl = enabled)
        assertTrue(need is DownloadPolicy.SpaceNeed.Required)
        assertEquals(1_704_302_777L + 1_500_000_000L, (need as DownloadPolicy.SpaceNeed.Required).bytes)
        assertEquals(3_204_302_777L, DownloadPolicy.requiredFreeBytes(ModelVariant.QWEN3_ASR, baseUrl = enabled))
        // an EMPTY list (fixture injection) stays Unsupported("not unlocked")
        assertEquals(
            DownloadPolicy.SpaceNeed.Unsupported(),
            DownloadPolicy.spaceNeedFor(ModelVariant.QWEN3_ASR, emptyList(), enabled),
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

        // QWEN in PRODUCTION (VK-2: empty base URL): never offerable —
        // "not enabled yet", no matter the RAM:
        for (ram in listOf(ram79, ram8, ram12)) {
            assertFalse(DownloadPolicy.canOfferSet(ModelVariant.QWEN3_ASR, ram, plentyFree))
        }

        // QWEN with a PINNED base URL (fixture): RAM gate at 8 GB + the
        // 3.2 GB space floor of the set (1.7 GB model + 1.5 GB reserve):
        assertFalse(DownloadPolicy.canOfferSet(ModelVariant.QWEN3_ASR, ram79, plentyFree, baseUrl = enabled))
        assertTrue(DownloadPolicy.canOfferSet(ModelVariant.QWEN3_ASR, ram8, plentyFree, baseUrl = enabled))
        assertTrue(DownloadPolicy.canOfferSet(ModelVariant.QWEN3_ASR, ram12, plentyFree, baseUrl = enabled))
        assertFalse(
            "below the QWEN space floor",
            DownloadPolicy.canOfferSet(ModelVariant.QWEN3_ASR, ram12, 3_204_302_776L, baseUrl = enabled),
        )
        assertTrue(
            DownloadPolicy.canOfferSet(ModelVariant.QWEN3_ASR, ram12, 3_204_302_777L, baseUrl = enabled),
        )

        // QWEN with an EMPTY manifest (fixture): never offerable —
        // "modell nicht freigeschaltet", no matter the RAM/URL:
        for (ram in listOf(ram79, ram8, ram12)) {
            assertFalse(DownloadPolicy.canOfferSet(ModelVariant.QWEN3_ASR, ram, plentyFree, emptyList(), enabled))
        }

        // B (debug-only, full set): offerable without a RAM gate:
        assertTrue(DownloadPolicy.canOfferSet(ModelVariant.LITERT_CPU, ram79, plentyFree))
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
        assertTrue("1.7B draft id is NOT a set (gate: 0.6B)", downloadWiring("qwen3-asr-1.7b-int8").variant == ModelVariant.ONNX_CPU)

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
        // production QWEN: manifest pinned (6 files) but base URL EMPTY —
        // the run must fail BEFORE any staging/marker/download op.
        val store = ModelStore(tmp.newFolder())
        val controller = DownloadController(
            store,
            { throw AssertionError("no download op may run for a set that is not enabled") },
            ModelManifest.forVariant(ModelVariant.QWEN3_ASR),
            DirectExecutor(),
            variant = ModelVariant.QWEN3_ASR,
        )

        controller.start()

        val failed = controller.state
        assertTrue("not-enabled set => FAILED, never READY", failed is DownloadController.State.Failed)
        assertTrue(
            "reason: set not enabled yet",
            (failed as DownloadController.State.Failed).error is ModelError.Storage,
        )
        assertEquals(ModelStatus.ABSENT, store.statusQuick(ModelVariant.QWEN3_ASR))
    }

    private class DirectExecutor : Executor {
        override fun execute(command: Runnable) = command.run()
    }
}
