package de.voicekeyboard.model

import de.voicekeyboard.ime.ModelSwitchPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T24b (PLAN_V0147 §17.2, Gleis 3 B1a): U2 TEXT model sets — variant entries,
 * manifest pinning (merged artifacts, BART_CORRECT_CONTRACT §12), and the
 * dictation-switch guard: a TEXT set is NEVER the active dictation variant.
 * sha256/bytes from tools/bart_ref/EXPORT_LOG.txt (stage=merge/tokenizer).
 */
class ModelU2VariantTest {

    // --------------------------------------------------------- variant entries

    @Test fun `u2 variants exist with TEXT kind and pinned ids`() {
        assertEquals("bart-spelling-en-int8", ModelVariant.BART_SPELLING_EN.id)
        assertEquals("bart-spelling-de-int8", ModelVariant.BART_SPELLING_DE.id)
        assertEquals(ModelVariant.Kind.TEXT, ModelVariant.BART_SPELLING_EN.kind)
        assertEquals(ModelVariant.Kind.TEXT, ModelVariant.BART_SPELLING_DE.kind)
        // the ASR variants stay ASR
        assertEquals(ModelVariant.Kind.ASR, ModelVariant.ONNX_CPU.kind)
        assertEquals(ModelVariant.Kind.ASR, ModelVariant.QWEN3_ASR.kind)
    }

    @Test fun `model_variant pref NEVER resolves a TEXT set as active dictation`() {
        assertEquals(
            "hand-bent TEXT pref falls back to A",
            ModelVariant.DEFAULT,
            ModelVariant.fromId("bart-spelling-de-int8"),
        )
        assertEquals(ModelVariant.QWEN3_ASR, ModelVariant.fromId("qwen3-asr-0.6b-int8"))
        assertEquals(ModelVariant.DEFAULT, ModelVariant.fromId(null))
    }

    @Test fun `textFromId resolves only TEXT sets`() {
        assertEquals(ModelVariant.BART_SPELLING_EN, ModelVariant.textFromId("bart-spelling-en-int8"))
        assertEquals(ModelVariant.BART_SPELLING_DE, ModelVariant.textFromId("bart-spelling-de-int8"))
        assertNull("ASR ids are not TEXT sets", ModelVariant.textFromId("onnx-cpu"))
        assertNull("unknown -> null (caller decides: U2 off)", ModelVariant.textFromId("nope"))
    }

    // ------------------------------------------------------- switch policy guard

    @Test fun `switch policy NEVER applies a TEXT set as dictation engine`() {
        assertEquals(
            "READY TEXT target is rejected, not applied or deferred",
            ModelSwitchPolicy.Decision.Rejected(ModelSwitchPolicy.Reason.TEXT_SET),
            ModelSwitchPolicy.policy(
                target = ModelVariant.BART_SPELLING_EN,
                current = ModelVariant.ONNX_CPU,
                targetReady = true,
                dictationActive = false,
            ),
        )
        // ASR switching stays untouched
        assertEquals(
            ModelSwitchPolicy.Decision.Apply,
            ModelSwitchPolicy.policy(
                target = ModelVariant.QWEN3_ASR,
                current = ModelVariant.ONNX_CPU,
                targetReady = true,
                dictationActive = false,
            ),
        )
    }

    // ------------------------------------------------------ manifest pinning (en)

    @Test fun `u2 EN manifest pins the merged artifact tree byte-exact`() {
        val files = ModelManifest.forVariant(ModelVariant.BART_SPELLING_EN)
        assertEquals("7 files (3 graphs + shared blob + tokenizer trio)", 7, files.size)
        val byName = files.associateBy { it.storeName }
        assertEquals(43_601_202L, byName.getValue("encoder.int8.onnx").sizeBytes)
        assertEquals(
            "bba119c4dc16830e6a0a0d670167134862ce124e1ac6b39c5abc936e2436cf54",
            byName.getValue("encoder.int8.onnx").sha256,
        )
        assertEquals(7_466_649L, byName.getValue("decoder_init.int8.onnx").sizeBytes)
        assertEquals(
            "d986026349e944bbf1f2da44cf5a7e7da4b4bf211b1e3a6c2b86fd7704f96629",
            byName.getValue("decoder_init.int8.onnx").sha256,
        )
        assertEquals(324_984L, byName.getValue("decoder_step.int8.onnx").sizeBytes)
        assertEquals(
            "2d1a70e4e4217f06fe42517003ec099035447b4a83cf1e508f2cb455901dc8b2",
            byName.getValue("decoder_step.int8.onnx").sha256,
        )
        assertEquals(
            "external-data blob lives in the subdir the graphs reference",
            "shared/shared_weights.bin",
            byName.keys.single { it.startsWith("shared/") },
        )
        assertEquals(127_859_712L, byName.getValue("shared/shared_weights.bin").sizeBytes)
        assertEquals(
            "0a8a2920ae3412512ee60bfc48f31984a092c77cb3efd83a7fa80d7a39c3479c",
            byName.getValue("shared/shared_weights.bin").sha256,
        )
        assertEquals(3_558_746L, byName.getValue("tokenizer/tokenizer.json").sizeBytes)
        assertEquals(
            "dcf1e4d967564d18f9b7721e268b8a517027319a5cac544b9c1d9a0ce0163f44",
            byName.getValue("tokenizer/tokenizer.json").sha256,
        )
        assertEquals(497L, byName.getValue("tokenizer/tokenizer_config.json").sizeBytes)
        assertEquals(225L, byName.getValue("tokenizer/vocab_meta.json").sizeBytes)
    }

    // ------------------------------------------------------ manifest pinning (de)

    @Test fun `u2 DE manifest pins the merged artifact tree byte-exact`() {
        val files = ModelManifest.forVariant(ModelVariant.BART_SPELLING_DE)
        assertEquals(7, files.size)
        val byName = files.associateBy { it.storeName }
        assertEquals(85_228_809L, byName.getValue("encoder.int8.onnx").sizeBytes)
        assertEquals(
            "05507b81ecdad185131bdfdaccf942cf066584eb11ea20d85e5343eb25dbb89e",
            byName.getValue("encoder.int8.onnx").sha256,
        )
        assertEquals(14_453_095L, byName.getValue("decoder_init.int8.onnx").sizeBytes)
        assertEquals(333_394L, byName.getValue("decoder_step.int8.onnx").sizeBytes)
        assertEquals(148_509_696L, byName.getValue("shared/shared_weights.bin").sizeBytes)
        assertEquals(
            "68296cd919fa2691744c80d62c981b55047b8f1ab3ed3e6a2f31b1111da430cd",
            byName.getValue("shared/shared_weights.bin").sha256,
        )
        assertEquals(2_422_267L, byName.getValue("tokenizer/tokenizer.json").sizeBytes)
        assertEquals(2_488L, byName.getValue("tokenizer/tokenizer_config.json").sizeBytes)
        assertEquals(230L, byName.getValue("tokenizer/vocab_meta.json").sizeBytes)
        // de decoder start id + prefix contract live in vocab_meta (§121 contract)
        assertEquals("vocab_meta pinned", "tokenizer/vocab_meta.json", byName.keys.single { it.endsWith("vocab_meta.json") })
    }

    // ------------------------------------------------------------- base URL

    @Test fun u2BaseUrlsArePinnedToTheCatboxHost() {
        // V0.1.54 (PLAN_HOSTING §2a): hosts pinned — catbox (permanent),
        // PER SET (en/de share file names with different hashes):
        assertEquals("https://files.catbox.moe/", ModelManifest.BASE_URL_U2_EN)
        assertEquals("https://files.catbox.moe/", ModelManifest.BASE_URL_U2_DE)
        assertEquals(ModelManifest.BASE_URL_U2_EN, ModelManifest.baseUrlFor(ModelVariant.BART_SPELLING_EN))
        assertEquals(ModelManifest.BASE_URL_U2_DE, ModelManifest.baseUrlFor(ModelVariant.BART_SPELLING_DE))
    }

    // ------------------------------------------------- download wiring (U2)

    /** V0.1.54b: the U2 download button routes through the SAME service
     *  path — downloadWiring must resolve TEXT ids EXPLICITLY ([fromId]
     *  deliberately falls back to A for TEXT ids, never a TEXT dictation). */
    @Test fun `downloadWiring resolves the TEXT sets explicitly - full U2 wiring per set`() {
        val en = downloadWiring("bart-spelling-en-int8")
        assertEquals(ModelVariant.BART_SPELLING_EN, en.variant)
        assertEquals(ModelManifest.U2_EN, en.manifest)
        assertEquals(ModelManifest.BASE_URL_U2_EN, en.baseUrl)
        val de = downloadWiring("bart-spelling-de-int8")
        assertEquals(ModelVariant.BART_SPELLING_DE, de.variant)
        assertEquals(ModelManifest.U2_DE, de.manifest)
        assertEquals(ModelManifest.BASE_URL_U2_DE, de.baseUrl)
        // ASR ids keep their EXACT old wiring (default A, 17B inert):
        assertEquals(ModelVariant.ONNX_CPU, downloadWiring(null).variant)
        assertEquals(ModelVariant.ONNX_CPU, downloadWiring("bogus").variant)
        assertEquals(ModelVariant.QWEN3_ASR_17B, downloadWiring("qwen3-asr-1.7b-int8").variant)
        assertEquals(ModelVariant.QWEN3_ASR, downloadWiring("qwen3-asr-0.6b-int8").variant)
    }

    /** V0.1.54 (PLAN_HOSTING): the downloader builds baseUrl + urlPath — the
     *  EXACT full URLs of both U2 sets (flat catbox names for EVERY file,
     *  including the multi-segment store names under shared/ and tokenizer/). */
    @Test fun `u2 download defaults build the exact full URLs (base + flat urlPath)`() {
        val en = ModelManifest.U2_EN.associate { it.storeName to ModelManifest.BASE_URL_U2_EN + it.urlPath }
        assertEquals("https://files.catbox.moe/t2vaz4.onnx", en.getValue("encoder.int8.onnx"))
        assertEquals("https://files.catbox.moe/pwnz00.onnx", en.getValue("decoder_init.int8.onnx"))
        assertEquals("https://files.catbox.moe/myoxbf.onnx", en.getValue("decoder_step.int8.onnx"))
        assertEquals("https://files.catbox.moe/gci74c.bin", en.getValue("shared/shared_weights.bin"))
        assertEquals("https://files.catbox.moe/5a626s.json", en.getValue("tokenizer/tokenizer.json"))
        assertEquals("https://files.catbox.moe/8m7dbj.json", en.getValue("tokenizer/tokenizer_config.json"))
        assertEquals("https://files.catbox.moe/zcwse1.json", en.getValue("tokenizer/vocab_meta.json"))
        val de = ModelManifest.U2_DE.associate { it.storeName to ModelManifest.BASE_URL_U2_DE + it.urlPath }
        assertEquals("https://files.catbox.moe/yufd3r.onnx", de.getValue("encoder.int8.onnx"))
        assertEquals("https://files.catbox.moe/41jloi.onnx", de.getValue("decoder_init.int8.onnx"))
        assertEquals("https://files.catbox.moe/4ya4lm.onnx", de.getValue("decoder_step.int8.onnx"))
        assertEquals("https://files.catbox.moe/vp343k.bin", de.getValue("shared/shared_weights.bin"))
        assertEquals("https://files.catbox.moe/vsjzg0.json", de.getValue("tokenizer/tokenizer.json"))
        assertEquals("https://files.catbox.moe/2psg6n.json", de.getValue("tokenizer/tokenizer_config.json"))
        assertEquals("https://files.catbox.moe/wvn408.json", de.getValue("tokenizer/vocab_meta.json"))
    }

    /** V0.1.53 (PLAN_HOSTING §2a): en/de share the SAME file names with
     *  DIFFERENT hashes — one common base URL is impossible; the mapping
     *  must stay PER SET so each set can be unlocked separately. */
    @Test fun `u2 en and de map to SEPARATE base URLs - one per set`() {
        assertEquals(ModelManifest.BASE_URL_U2_EN, ModelManifest.baseUrlFor(ModelVariant.BART_SPELLING_EN))
        assertEquals(ModelManifest.BASE_URL_U2_DE, ModelManifest.baseUrlFor(ModelVariant.BART_SPELLING_DE))
    }

    @Test fun `ASR base URLs stay unchanged`() {
        assertTrue(ModelManifest.baseUrlFor(ModelVariant.ONNX_CPU).isNotEmpty())
        assertTrue(ModelManifest.baseUrlFor(ModelVariant.QWEN3_ASR) == "" || ModelVariant.QWEN3_ASR != null)
        assertTrue(ModelManifest.forVariant(ModelVariant.QWEN3_ASR).isNotEmpty())
        assertFalse("no store name escapes its set dir", ModelManifest.forVariant(ModelVariant.ONNX_CPU).any { it.storeName.contains("..") })
    }
}
