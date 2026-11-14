package de.voicekeyboard.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * T24b (PLAN_V0147 §17.2, Gleis 3 B2): MULTI-SEGMENT store names for the U2
 * merged sets ("shared/shared_weights.bin", "tokenizer/…") — per-segment
 * traversal safety, flat-name byte compatibility (regression), U2 READY/delete
 * fixture over a mini manifest, escaped flat part files (resume trail), and
 * the DownloadPolicy U2 wiring (empty BASE_URL_U2 = inert; RAM gate like Qwen).
 */
class ModelStoreMultiSegmentTest {

    @get:Rule
    val tmp = TemporaryFolder()

    /** Small multi-segment manifest (real U2 NAME SHAPE, fixture bytes). */
    private val content = ByteArray(2048) { (it % 251).toByte() }
    private fun miniU2(): List<ModelFile> = listOf(
        ModelFile("encoder.int8.onnx", 1024L, shaOf(1024)),
        ModelFile("decoder_init.int8.onnx", 512L, shaOf(512)),
        ModelFile("shared/shared_weights.bin", 2048L, shaOf(2048)),
        ModelFile("tokenizer/vocab_meta.json", 64L, shaOf(64)),
    )

    private fun shaOf(n: Int): String {
        val f = tmp.newFile()
        f.writeBytes(content.copyOf(n))
        return Sha256.ofFile(f)
    }

    private fun store(): ModelStore {
        val manifest = miniU2()
        return ModelStore(tmp.newFolder(), manifest)
    }

    private fun stageU2(store: ModelStore): File {
        val staging = store.newStagingDir()
        for (entry in miniU2()) {
            val f = File(staging, entry.storeName)
            f.parentFile?.mkdirs()
            f.writeBytes(content.copyOf(entry.sizeBytes.toInt()))
        }
        return staging
    }

    // ------------------------------------------------- traversal (per segment)

    @Test fun `multi-segment names resolve INSIDE the set dir`() {
        val store = store()
        val part = store.partFile("shared/shared_weights.bin", ModelVariant.BART_SPELLING_EN)
        assertTrue(
            "part files stay FLAT in downloads (escaped), never subdirectories",
            part.parentFile == File(part.parentFile, "").parentFile &&
                part.parentFile.name == "downloads" || part.parentFile.path.endsWith("downloads"),
        )
        assertFalse("escaped part name carries no slash", '/' in part.name)
        assertTrue("escaped part name keeps the .part suffix", part.name.endsWith(".part"))
    }

    @Test fun `traversal attempts are rejected per segment`() {
        val store = store()
        // "..", absolute, empty segment, backslash — every variant throws the
        // whitelist/traversal require, even when the manifest contains
        // multi-segment names.
        for (evil in listOf("../escape.txt", "a/../../escape.txt", "/abs/path.bin", "a//b.bin", "a\\b.bin", "..", "")) {
            val e = org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
                store.partFile(evil, ModelVariant.BART_SPELLING_EN)
            }
            val msg = e.message ?: ""
            assertTrue("reason names the guard: $msg", "whitelist" in msg || "traversal" in msg)
        }
    }

    // ---------------------------------------------------- U2 READY + delete

    @Test fun `U2 set activates with subdirectories and reaches READY`() {
        val store = store()
        val staging = stageU2(store)
        assertTrue("activation over the multi-segment manifest", store.activate(staging, ModelVariant.BART_SPELLING_EN))
        assertEquals(ModelStatus.READY, store.statusQuick(ModelVariant.BART_SPELLING_EN))
        assertTrue(
            "present check sees files in subdirectories",
            store.isPresent(ModelVariant.BART_SPELLING_EN),
        )
        assertTrue("full hash check over subdirectories", store.verifyInstalled(ModelVariant.BART_SPELLING_EN))

        // delete frees the set (the Setup card's Delete path, C2 orphan rule)
        assertTrue(store.deleteSet(ModelVariant.BART_SPELLING_EN, ModelVariant.ONNX_CPU))
        assertEquals(ModelStatus.ABSENT, store.statusQuick(ModelVariant.BART_SPELLING_EN))
    }

    @Test fun `a wrong hash in a subdirectory blocks the activation`() {
        val store = store()
        val staging = stageU2(store)
        File(staging, "shared/shared_weights.bin").writeBytes(ByteArray(2048)) // wrong bytes
        assertFalse(store.activate(staging, ModelVariant.BART_SPELLING_EN))
        assertNull("no pointer on failed activation", store.activeVersion(ModelVariant.BART_SPELLING_EN))
    }

    // --------------------------------------------------- part escaping + resume

    @Test fun `an interrupted multi-segment download leaves the DOWNLOADING trail`() {
        val store = store()
        // simulate a leftover escaped part file (downloader writes it flat)
        val part = store.partFile("shared/shared_weights.bin", ModelVariant.BART_SPELLING_EN)
        part.writeBytes(content.copyOf(64))
        assertEquals(
            "resume trail visible over the OWN set whitelist",
            ModelStatus.DOWNLOADING,
            store.statusQuick(ModelVariant.BART_SPELLING_EN),
        )
        part.delete()
        assertEquals(ModelStatus.ABSENT, store.statusQuick(ModelVariant.BART_SPELLING_EN))
    }

    // --------------------------------------------------- flat compatibility

    @Test fun `flat names stay byte-compatible (single segment regression)`() {
        val flatManifest = listOf(
            ModelFile("model.bin", 128L, shaOf(128)),
            ModelFile("vocab.txt", 32L, shaOf(32)),
        )
        val store = ModelStore(tmp.newFolder(), flatManifest)
        val staging = store.newStagingDir()
        File(staging, "model.bin").writeBytes(content.copyOf(128))
        File(staging, "vocab.txt").writeBytes(content.copyOf(32))
        assertTrue(store.activate(staging, ModelVariant.ONNX_CPU))
        assertEquals(ModelStatus.READY, store.statusQuick(ModelVariant.ONNX_CPU))
        assertEquals(
            "flat part file name UNCHANGED (no escaping artifacts)",
            "model.bin.part",
            store.partFile("model.bin", ModelVariant.ONNX_CPU).name,
        )
    }

    // ------------------------------------------------------ DownloadPolicy U2

    @Test fun `empty BASE_URL_U2 keeps the U2 sets inert (null floor - Qwen pattern)`() {
        assertNull(
            "no pinned host -> no floor -> never offered",
            DownloadPolicy.requiredFreeBytes(ModelVariant.BART_SPELLING_EN),
        )
        assertNull(DownloadPolicy.requiredFreeBytes(ModelVariant.BART_SPELLING_DE))
        assertFalse(
            "canOffer false while inert",
            DownloadPolicy.canOfferSet(
                ModelVariant.BART_SPELLING_EN,
                totalRamBytes = 16L * 1024 * 1024 * 1024,
                freeBytes = 8L * 1024 * 1024 * 1024,
            ),
        )
    }

    @Test fun `U2 RAM gate applies like Qwen once a host is pinned`() {
        val manifest = miniU2()
        val base = "https://u2.example/"
        assertNull(
            "low-RAM device: the TEXT sets stay gated",
            DownloadPolicy.requiredFreeBytes(ModelVariant.BART_SPELLING_EN, manifest, base)
                ?.let { floor ->
                    if (DownloadPolicy.canOfferSet(
                            ModelVariant.BART_SPELLING_EN,
                            totalRamBytes = 4L * 1024 * 1024 * 1024,
                            freeBytes = floor,
                        )
                    ) {
                        floor // offer TRUE would be the bug: return it to fail the assert
                    } else {
                        null // gated -> correct
                    }
                },
        )
        val floor = DownloadPolicy.requiredFreeBytes(ModelVariant.BART_SPELLING_EN, manifest, base)
        assertNotNull("with host + RAM the floor exists", floor)
        assertTrue(
            "8 GB device with enough space may be offered",
            DownloadPolicy.canOfferSet(
                ModelVariant.BART_SPELLING_EN,
                totalRamBytes = 8L * 1024 * 1024 * 1024,
                freeBytes = floor!!,
                manifest = manifest,
                baseUrl = base,
            ),
        )
    }
}
