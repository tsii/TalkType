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
        // V0.1.53 (PLAN_QWEN17B S1): the 1.7B id IS a set now — QWEN3_ASR_17B,
        // INERT (empty manifest, never READY, never offered); the engine
        // falls back to Parakeet for it.
        assertEquals(ModelVariant.QWEN3_ASR_17B, ModelVariant.fromId("qwen3-asr-1.7b-int8"))
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
            assertTrue("urlPath FLAT (litterbox name, no subdirectory): ${f.storeName}",
                f.urlPath.isNotEmpty() && '/' !in f.urlPath && '\\' !in f.urlPath)
        }
        // the pinned production list verbatim (QWEN_ASR_CONTRACT §3):
        assertEquals(
            listOf(
                ModelFile("encoder.int8.onnx", 187_361_021L,
                    "327974e11e2914037272224fc439af093bb251d0d232c0513bc9054d1ae6adba", urlPath = "lget9z.onnx"),
                ModelFile("qwen_prefill.int8.onnx", 752_810_624L,
                    "1a505e73e42074a2fdded9cdef4d9484586d52dbd654760982df738e1c03e48d", urlPath = "9r67k7.onnx"),
                ModelFile("qwen_step.int8.onnx", 752_598_179L,
                    "9a7f8946996be4f36c0687265cc18f0d929c459001d15d1dfc5b6f783cb198d5", urlPath = "hzd13o.onnx"),
                ModelFile("tokenizer.json", 11_429_653L,
                    "fe1fad59be22a41ee293363fcf95fdedbc7c93f3b49270b1d2e18bd1399a7a05", urlPath = "6cae7m.json"),
                ModelFile("mel_filters.npy", 103_040L,
                    "8abf12444e0d9c0fd6c5e49f8b977d41e47b51a5eb48d399c232578d3d5e914f", urlPath = "66z3qn.npy"),
                ModelFile("mel_config.json", 260L,
                    "1888b2defc1b7db6ca081bd706f31e8fc1107cd44f1eba644834251f6dccd24c", urlPath = "7d4dyq.json"),
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
    fun `baseUrlFor points every set to its own source - QWEN pinned to the litterbox host`() {
        assertEquals(ModelManifest.BASE_URL, ModelManifest.baseUrlFor(ModelVariant.ONNX_CPU))
        assertEquals(ModelManifest.BASE_URL_LITERT, ModelManifest.baseUrlFor(ModelVariant.LITERT_CPU))

        // V0.1.54 (PLAN_HOSTING): the QWEN base URL is PINNED — litterbox
        // (temporary 72h upload, valid until ~2026-11-19 22:30 UTC). Non-empty
        // = offered (DownloadPolicy frees the set, RAM gate stays).
        assertEquals("https://litter.catbox.moe/", ModelManifest.baseUrlFor(ModelVariant.QWEN3_ASR))
        assertEquals(ModelManifest.BASE_URL_QWEN, ModelManifest.baseUrlFor(ModelVariant.QWEN3_ASR))
    }

    /** V0.1.54 (PLAN_HOSTING): the downloader builds baseUrl + urlPath —
     *  the EXACT full URLs of all 6 QWEN files (litterbox flat names). */
    @Test
    fun `qwen download defaults build the exact full URLs (base + urlPath)`() {
        assertEquals("https://litter.catbox.moe/", ModelManifest.BASE_URL_QWEN)
        val urls = ModelManifest.QWEN.associate { it.storeName to ModelManifest.BASE_URL_QWEN + it.urlPath }
        assertEquals("https://litter.catbox.moe/lget9z.onnx", urls.getValue("encoder.int8.onnx"))
        assertEquals("https://litter.catbox.moe/9r67k7.onnx", urls.getValue("qwen_prefill.int8.onnx"))
        assertEquals("https://litter.catbox.moe/hzd13o.onnx", urls.getValue("qwen_step.int8.onnx"))
        assertEquals("https://litter.catbox.moe/6cae7m.json", urls.getValue("tokenizer.json"))
        assertEquals("https://litter.catbox.moe/66z3qn.npy", urls.getValue("mel_filters.npy"))
        assertEquals("https://litter.catbox.moe/7d4dyq.json", urls.getValue("mel_config.json"))
    }
}

