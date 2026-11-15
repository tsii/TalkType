package de.voicekeyboard.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * V0.1.53 (PLAN_QWEN17B §B/§C, S1/S2): the Qwen3-ASR 1.7B variant exists but
 * stays INERT — no checked model files exist yet (prefill int8 corrupt, step
 * not exported, no sha256 pins), so the pinned manifest list is EMPTY and
 * BASE_URL_QWEN_17B is empty: never offered, never downloadable, never
 * selectable. The unlock is a later commit that pins files + host (§B.2).
 */
class ModelManifestQwen17bTest {

    // --------------------------------------------------------- variant entry

    @Test fun `variant entry resolves its pinned id and stays an ASR set`() {
        assertEquals("qwen3-asr-1.7b-int8", ModelVariant.QWEN3_ASR_17B.id)
        assertEquals(ModelVariant.QWEN3_ASR_17B, ModelVariant.fromId("qwen3-asr-1.7b-int8"))
        assertEquals(ModelVariant.Kind.ASR, ModelVariant.QWEN3_ASR_17B.kind)
        assertNull("never a TEXT set", ModelVariant.textFromId("qwen3-asr-1.7b-int8"))
        assertEquals("DEFAULT stays A", ModelVariant.DEFAULT, ModelVariant.fromId(null))
    }

    // --------------------------------------------------- inert manifest pin

    @Test fun `manifest list is empty and the base URL is unpinned - inert`() {
        assertTrue(
            "no checked files yet -> EMPTY pinned list (structural inertness)",
            ModelManifest.forVariant(ModelVariant.QWEN3_ASR_17B).isEmpty(),
        )
        assertEquals("", ModelManifest.BASE_URL_QWEN_17B)
        assertEquals("", ModelManifest.baseUrlFor(ModelVariant.QWEN3_ASR_17B))
        assertEquals(0L, ModelManifest.totalBytes(ModelVariant.QWEN3_ASR_17B))
        assertFalse(
            "empty whitelist rejects every name",
            ModelManifest.isAllowedName("encoder.int8.onnx", ModelVariant.QWEN3_ASR_17B),
        )
    }

    // ------------------------------------------------- DownloadPolicy refuses

    @Test fun `download policy refuses the inert set - canOfferSet false everywhere`() {
        assertEquals(
            "empty manifest => not unlocked",
            DownloadPolicy.SpaceNeed.Unsupported(),
            DownloadPolicy.spaceNeedFor(ModelVariant.QWEN3_ASR_17B),
        )
        assertNull("no free-space floor while inert", DownloadPolicy.requiredFreeBytes(ModelVariant.QWEN3_ASR_17B))
        for (ram in listOf(4L shl 30, 8L shl 30, 16L shl 30)) {
            assertFalse(
                "never offered, no matter the RAM ($ram)",
                DownloadPolicy.canOfferSet(ModelVariant.QWEN3_ASR_17B, ram, 64L shl 30),
            )
        }
    }

    // --------------------------------------------------- wiring fails fast

    @Test fun `download wiring maps the 1_7B id to the inert set - no network`() {
        val wiring = downloadWiring("qwen3-asr-1.7b-int8")
        assertEquals(ModelVariant.QWEN3_ASR_17B, wiring.variant)
        assertTrue(wiring.manifest.isEmpty())
        assertEquals("", wiring.baseUrl)
    }
}
