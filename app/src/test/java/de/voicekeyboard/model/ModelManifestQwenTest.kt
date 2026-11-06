package de.voicekeyboard.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * T22b/T22b' (PLAN_V0146 §4/§7): manifest structure of the second dictation
 * model set. The T22a GATE has decided (v0.1.46): Qwen3-ASR-0.6B int8 as the
 * experimental second set, set id "qwen3-asr-0.6b-int8". The production file
 * list is PINNED here end-to-end (names/sizes/sha256 from
 * docs/QWEN_ASR_CONTRACT.md §3; sizes/shas cross-checked against the export).
 *
 * Pins:
 *  (a) `ModelVariant.fromId("qwen3-asr-0.6b-int8")` → QWEN3_ASR; unknown/null
 *      falls back to ONNX_CPU (existing pattern);
 *  (b) `manifestFor(QWEN3_ASR)` = 6 FLAT files (no subdirectories — the
 *      server delivers flat names, the engine finds them in the model dir),
 *      sizes > 0, sha256 64-hex; `manifestFor(ONNX_CPU)` unchanged;
 *  (c) `isAllowedName` whitelist per set: every A file name is not allowed
 *      for QWEN and vice versa (REAL names, no fixtures needed any more);
 *  (d) base URL per set: QWEN points to
 *      `<download-server>/TalkType/models/qwen3-asr-0.6b-int8/` — host
 *      deliberately the EXISTING BASE_URL_A host (nothing invented here).
 */
class ModelManifestQwenTest {

    @get:Rule
    val tmp = TemporaryFolder()

    // ---- (a) variant id ----

    @Test
    fun `fromId resolves QWEN3_ASR and unknown ids fall back to A`() {
        assertEquals(ModelVariant.QWEN3_ASR, ModelVariant.fromId("qwen3-asr-0.6b-int8"))
        assertEquals("id string matches the enum", "qwen3-asr-0.6b-int8", ModelVariant.QWEN3_ASR.id)
        // existing pattern: unknown/null ⇒ A (never an unnamed variant active)
        assertEquals(ModelVariant.ONNX_CPU, ModelVariant.fromId("no-such-set"))
        assertEquals(ModelVariant.ONNX_CPU, ModelVariant.fromId(null))
        assertEquals(ModelVariant.ONNX_CPU, ModelVariant.fromId("QWEN3-ASR-0.6B-INT8")) // case-sensitive
        // the 1.7B candidate id of the draft is NOT a set (gate decision: 0.6B)
        assertEquals(ModelVariant.ONNX_CPU, ModelVariant.fromId("qwen3-asr-1.7b-int8"))
        // old ids unchanged (0.1.45 users keep their set)
        assertEquals(ModelVariant.ONNX_CPU, ModelVariant.fromId("onnx-cpu"))
        assertEquals(ModelVariant.LITERT_CPU, ModelVariant.fromId("litert-cpu"))
        assertEquals(ModelVariant.ONNX_CPU, ModelVariant.DEFAULT)
    }

    // ---- (b) manifest per set: 6 pinned production files ----

    @Test
    fun `manifestFor QWEN pins 6 flat files - A stays untouched`() {
        val qwen = ModelManifest.forVariant(ModelVariant.QWEN3_ASR)
        // 6 files, FLAT names (no '/', never a subdirectory):
        assertEquals(6, qwen.size)
        for (f in qwen) {
            assertTrue("flat name: ${f.storeName}", '/' !in f.storeName && '\\' !in f.storeName)
            assertTrue("size > 0: ${f.storeName}", f.sizeBytes > 0)
            assertTrue("sha256 64-hex: ${f.storeName}",
                Regex("^[0-9a-f]{64}$").matches(f.sha256))
            assertTrue("urlPath flat == storeName: ${f.storeName}", f.urlPath == f.storeName)
        }
        // the pinned production list verbatim (QWEN_ASR_CONTRACT §3):
        assertEquals(
            listOf(
                ModelFile("encoder.int8.onnx", 187_361_021L,
                    "327974e11e2914037272224fc439af093bb251d0d232c0513bc9054d1ae6adba"),
                ModelFile("qwen_prefill.int8.onnx", 752_810_624L,
                    "1a505e73e42074a2fdded9cdef4d9484586d52dbd654760982df738e1c03e48d"),
                ModelFile("qwen_step.int8.onnx", 752_598_179L,
                    "9a7f8946996be4f36c0687265cc18f0d929c459001d15d1dfc5b6f783cb198d5"),
                ModelFile("tokenizer.json", 11_429_653L,
                    "fe1fad59be22a41ee293363fcf95fdedbc7c93f3b49270b1d2e18bd1399a7a05"),
                ModelFile("mel_filters.npy", 103_040L,
                    "8abf12444e0d9c0fd6c5e49f8b977d41e47b51a5eb48d399c232578d3d5e914f"),
                ModelFile("mel_config.json", 260L,
                    "1888b2defc1b7db6ca081bd706f31e8fc1107cd44f1eba644834251f6dccd24c"),
            ),
            qwen,
        )
        // space requirement (T22d): model bytes + 1.5 GB reserve, above the 2.5 GB floor
        assertEquals(1_704_302_777L, ModelManifest.totalBytes(ModelVariant.QWEN3_ASR))
        // A unchanged (regression against the 671 MB contract):
        assertEquals(4, ModelManifest.forVariant(ModelVariant.ONNX_CPU).size)
        assertEquals(670_619_706L, ModelManifest.totalBytes(ModelVariant.ONNX_CPU))
        assertEquals(670_619_706L, ModelManifest.TOTAL_BYTES)
        assertEquals(5, ModelManifest.forVariant(ModelVariant.LITERT_CPU).size)
        // the token file of B stays the ONLY urlPath != storeName case:
        assertTrue(ModelManifest.forVariant(ModelVariant.LITERT_CPU)
            .all { it.urlPath == it.storeName || it.storeName == "token_vocab.bin" })
    }

    // ---- (c) whitelist per set (cross-set rejection, REAL names) ----

    @Test
    fun `isAllowedName rejects names across sets`() {
        // every REAL A name is allowed for A and rejected for QWEN:
        for (name in ModelManifest.forVariant(ModelVariant.ONNX_CPU).map { it.storeName }) {
            assertTrue("A name allowed for A: $name", ModelManifest.isAllowedName(name, ModelVariant.ONNX_CPU))
            assertFalse(
                "A name must never be allowed for the QWEN set: $name",
                ModelManifest.isAllowedName(name, ModelVariant.QWEN3_ASR),
            )
        }
        // reverse direction, now with the REAL QWEN list (T22b'):
        for (name in ModelManifest.forVariant(ModelVariant.QWEN3_ASR).map { it.storeName }) {
            assertTrue("QWEN name allowed for QWEN: $name", ModelManifest.isAllowedName(name, ModelVariant.QWEN3_ASR))
            assertFalse(
                "QWEN name must never be allowed for the A set: $name",
                ModelManifest.isAllowedName(name, ModelVariant.ONNX_CPU),
            )
        }
        // no overlap between the sets (whitelists are disjoint):
        val a = ModelManifest.forVariant(ModelVariant.ONNX_CPU).map { it.storeName }.toSet()
        val q = ModelManifest.forVariant(ModelVariant.QWEN3_ASR).map { it.storeName }.toSet()
        assertTrue(a.intersect(q).isEmpty())
        // traversal stays rejected for EVERY set:
        assertFalse(ModelManifest.isAllowedName("../nemo128.onnx", ModelVariant.QWEN3_ASR))
        assertFalse(ModelManifest.isAllowedName("mel/encoder.int8.onnx", ModelVariant.QWEN3_ASR))
        assertFalse(ModelManifest.isAllowedName("assets/token_vocab.bin", ModelVariant.LITERT_CPU))
        assertFalse(ModelManifest.isAllowedName("", ModelVariant.QWEN3_ASR))
    }

    @Test
    fun `store whitelist is per set - names stay in their own set`() {
        // end-to-end through ModelStore (path traversal protection): each real
        // name passes in its OWN set, the OTHER set's name is refused.
        val store = ModelStore(tmp.newFolder())
        val aName = "nemo128.onnx"
        val qName = "encoder.int8.onnx"
        store.partFile(aName, ModelVariant.ONNX_CPU)     // own set: ok
        store.partFile(qName, ModelVariant.QWEN3_ASR)    // own set: ok
        for ((name, variant) in listOf(aName to ModelVariant.QWEN3_ASR, qName to ModelVariant.ONNX_CPU)) {
            var threw = false
            try {
                store.partFile(name, variant)
            } catch (e: IllegalArgumentException) {
                threw = true
            }
            assertTrue("$name must be refused for ${variant.id}", threw)
        }
    }

    // ---- (d) base URL per set ----
    @Test
    fun `baseUrlFor points every set to its own source - QWEN empty until the server host is pinned`() {
        assertEquals(ModelManifest.BASE_URL, ModelManifest.baseUrlFor(ModelVariant.ONNX_CPU))
        assertEquals(ModelManifest.BASE_URL_LITERT, ModelManifest.baseUrlFor(ModelVariant.LITERT_CPU))

        // VK-2 (REVIEW_V0146): the QWEN base URL is EMPTY — the draft
        // placeholder host was NOT resolvable; the server host is pinned at
        // upload time (post-GATE). Empty = not offered (DownloadPolicy
        // Unsupported "not enabled yet", Setup card not downloadable).
        assertEquals("", ModelManifest.baseUrlFor(ModelVariant.QWEN3_ASR))
        assertEquals(ModelManifest.BASE_URL_QWEN, ModelManifest.baseUrlFor(ModelVariant.QWEN3_ASR))
    }
}

