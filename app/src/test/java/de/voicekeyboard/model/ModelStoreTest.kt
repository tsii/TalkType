package de.voicekeyboard.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Pure JVM tests for [ModelStore] with temporary directories and a
 * mini manifest (small files, real SHA-256 values computed at runtime —
 * no network, no real 671 MB).
 */
class ModelStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    /** Mini manifest with two small files; hashes computed at runtime. */
    private fun miniManifest(a: ByteArray, b: ByteArray): List<ModelFile> = listOf(
        ModelFile("model.bin", a.size.toLong(), Sha256.ofBytes(a)),
        ModelFile("vocab.txt", b.size.toLong(), Sha256.ofBytes(b)),
    )

    private fun write(dir: File, name: String, bytes: ByteArray): File =
        File(dir, name).apply { writeBytes(bytes) }

    // ---- status without anything ----

    @Test fun `an empty store is ABSENT without leftovers`() {
        val store = ModelStore(tmp.newFolder(), miniManifest(byteArrayOf(1), byteArrayOf(2)))
        assertEquals(ModelStatus.ABSENT, store.status())
        assertFalse(store.isPresent())
        assertFalse(store.isReady())
        assertNull(store.activeVersion())
    }

    // ---- present / missing / wrong hash ----

    @Test fun `a complete and correct version is READY`() {
        val a = byteArrayOf(1, 2, 3); val b = byteArrayOf(9, 8)
        val root = tmp.newFolder()
        val store = ModelStore(root, miniManifest(a, b))
        val staging = store.newStagingDir()
        write(staging, "model.bin", a)
        write(staging, "vocab.txt", b)
        assertTrue(store.activate(staging))
        assertEquals(ModelStatus.READY, store.status())
        assertTrue(store.isReady())
        assertTrue(store.verifyInstalled())
    }

    @Test fun `a missing file in the active version is BROKEN`() {
        val a = byteArrayOf(1, 2, 3); val b = byteArrayOf(9, 8)
        val root = tmp.newFolder()
        val store = ModelStore(root, miniManifest(a, b))
        val staging = store.newStagingDir()
        write(staging, "model.bin", a)
        write(staging, "vocab.txt", b)
        assertTrue(store.activate(staging))
        // damage the file afterwards (missing)
        File(store.modelDir(), "vocab.txt").delete()
        assertEquals(ModelStatus.BROKEN, store.status())
        assertFalse(store.isReady())
    }

    @Test fun `a changed file (wrong hash) is BROKEN`() {
        val a = byteArrayOf(1, 2, 3); val b = byteArrayOf(9, 8)
        val root = tmp.newFolder()
        val store = ModelStore(root, miniManifest(a, b))
        val staging = store.newStagingDir()
        write(staging, "model.bin", a)
        write(staging, "vocab.txt", b)
        assertTrue(store.activate(staging))
        // flip a byte → the hash deviates (without a length change)
        val f = File(store.modelDir(), "vocab.txt")
        val broken = b.copyOf().also { it[0] = 7 }
        f.writeBytes(broken)
        assertFalse(store.verifyInstalled())
        assertEquals(ModelStatus.BROKEN, store.status())
    }

    // ---- .part leftover / download marker ----

    @Test fun `a part leftover without a pointer shows DOWNLOADING`() {
        val root = tmp.newFolder()
        val store = ModelStore(root, miniManifest(byteArrayOf(1), byteArrayOf(2)))
        store.partFile("model.bin").writeBytes(byteArrayOf(1))
        assertEquals(ModelStatus.DOWNLOADING, store.status())
        assertEquals(ModelStatus.DOWNLOADING, store.status()) // idempotently queryable
    }

    @Test fun `clearDownloads removes part and etag leftovers`() {
        val root = tmp.newFolder()
        val store = ModelStore(root, miniManifest(byteArrayOf(1), byteArrayOf(2)))
        store.partFile("model.bin").writeBytes(byteArrayOf(1))
        store.storeEtag("model.bin", "\"v1\"")
        store.clearDownloads()
        assertNull(store.etagFor("model.bin"))
        assertEquals(ModelStatus.ABSENT, store.status())
    }

    @Test fun `the DOWNLOADING and VERIFYING markers take precedence over ABSENT-READY`() {
        val root = tmp.newFolder()
        val store = ModelStore(root, miniManifest(byteArrayOf(1), byteArrayOf(2)))
        store.markDownloading()
        assertEquals(ModelStatus.DOWNLOADING, store.status())
        store.markVerifying()
        assertEquals(ModelStatus.VERIFYING, store.status())
        store.clearMarkers()
        assertEquals(ModelStatus.ABSENT, store.status())
    }

    // ---- activation: atomic, no mixed state ----

    @Test fun `activate aborts on an incomplete staging version - the active one stays intact`() {
        val a = byteArrayOf(1, 2, 3); val b = byteArrayOf(9, 8)
        val root = tmp.newFolder()
        val store = ModelStore(root, miniManifest(a, b))
        // version 1 active
        val v1 = store.newStagingDir()
        write(v1, "model.bin", a); write(v1, "vocab.txt", b)
        assertTrue(store.activate(v1))
        // version 2 incomplete: one file wrong (hash mismatch in staging)
        val v2 = store.newStagingDir()
        write(v2, "model.bin", byteArrayOf(42)); write(v2, "vocab.txt", b)
        assertFalse(store.activate(v2))
        // active version unchanged: READY, no mixed state
        assertEquals(ModelStatus.READY, store.status())
        assertEquals(a.toList(), File(store.modelDir(), "model.bin").readBytes().toList())
        assertEquals(v1.name, store.activeVersion())
    }

    @Test fun `activate deletes old versions - space reclaimed`() {
        val a = byteArrayOf(1, 2, 3); val b = byteArrayOf(9, 8)
        val root = tmp.newFolder()
        val store = ModelStore(root, miniManifest(a, b))
        val v1 = store.newStagingDir()
        write(v1, "model.bin", a); write(v1, "vocab.txt", b)
        assertTrue(store.activate(v1))
        val v2 = store.newStagingDir()
        write(v2, "model.bin", a); write(v2, "vocab.txt", b)
        assertTrue(store.activate(v2))
        assertEquals(v2.name, store.activeVersion())
        // v1 directory gone (only the active version remains)
        val versions = File(root, "versions").listFiles()?.map { it.name } ?: emptyList()
        assertEquals(listOf(v2.name), versions)
    }

    @Test fun `movePartToStaging moves the part atomically and removes the etag`() {
        val a = byteArrayOf(1, 2, 3)
        val root = tmp.newFolder()
        val store = ModelStore(root, miniManifest(a, byteArrayOf(7)))
        val part = store.partFile("model.bin"); part.writeBytes(a)
        store.storeEtag("model.bin", "\"e1\"")
        val staging = store.newStagingDir()
        assertTrue(store.movePartToStaging("model.bin", staging))
        assertFalse(part.isFile)                 // gone from downloads
        assertNull(store.etagFor("model.bin"))   // the etag disposed of too
        assertEquals(a.toList(), File(staging, "model.bin").readBytes().toList())
    }

    // ---- path traversal protection ----

    @Test(expected = IllegalArgumentException::class)
    fun `partFile rejects an unknown file name`() {
        val store = ModelStore(tmp.newFolder(), miniManifest(byteArrayOf(1), byteArrayOf(2)))
        store.partFile("boese.bin")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `partFile rejects traversal - slashes`() {
        val store = ModelStore(tmp.newFolder(), miniManifest(byteArrayOf(1), byteArrayOf(2)))
        store.partFile("../model.bin")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `partFile rejects a dot path`() {
        val store = ModelStore(tmp.newFolder(), miniManifest(byteArrayOf(1), byteArrayOf(2)))
        store.partFile("..")
    }

    // ---- manifest integrity (values from docs/MODEL_SHA256.txt) ----

    @Test fun `the default manifest contains all four files with a 671 MB sum`() {
        assertEquals(4, ModelManifest.DEFAULT.size)
        assertEquals(670_619_706L, ModelManifest.TOTAL_BYTES) // 639.4 KiB + 671 MB int8 per ASR_CONTRACT
        assertEquals(
            "a9fde1486ebfcc08f328d75ad4610c67835fea58c73ba57e3209a6f6cf019e9f",
            ModelManifest.fileByName("nemo128.onnx")?.sha256,
        )
        assertEquals(
            "d58544679ea4bc6ac563d1f545eb7d474bd6cfa467f0a6e2c1dc1c7d37e3c35d",
            ModelManifest.fileByName("vocab.txt")?.sha256,
        )
        assertTrue(ModelManifest.isAllowedName("vocab.txt"))
        assertFalse(ModelManifest.isAllowedName("evil/onnx"))
    }
}
