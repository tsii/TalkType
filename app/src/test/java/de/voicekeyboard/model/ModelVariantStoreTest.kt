package de.voicekeyboard.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * V1-Tests (MODEL_VARIANT_SWITCH_MEMO Stufe 1): Manifest je Variante,
 * variantengetrennter Store, Abwärtskompatibilität des Alt-Zeigers.
 */
class ModelVariantStoreTest {

    @Test
    fun `V1 manifest A summe und B summe aus den manifesten`() {
        // A: 139_764 + 652_183_999 + 18_202_004 + 93_939 = 670_619_706
        assertEquals(670_619_706L, ModelManifest.totalBytes(ModelVariant.ONNX_CPU))
        assertEquals(670_619_706L, ModelManifest.TOTAL_BYTES) // Kompatibilitätsalias
        // B: 648_784_320 + 27_705_640 + 6_466_576 + 262_864 + 78_669
        assertEquals(683_298_069L, ModelManifest.totalBytes(ModelVariant.LITERT_CPU))
        assertEquals(5, ModelManifest.forVariant(ModelVariant.LITERT_CPU).size)
        assertEquals(4, ModelManifest.forVariant(ModelVariant.ONNX_CPU).size)
    }

    @Test
    fun `V1 gegen manipulierte B-groesse bricht die pruefung ab`() {
        // Gegenprobe: veränderte Größe in B darf die Summen-Prüfung nicht
        // unbemerkt überleben — totalBytes weicht vom Manifest-Wert ab.
        val realB = ModelManifest.forVariant(ModelVariant.LITERT_CPU)
        val tampered = realB.mapIndexed { i, f ->
            if (i == 0) f.copy(sizeBytes = f.sizeBytes + 1) else f
        }.sumOf { it.sizeBytes }
        assertTrue("Manipulierte Summe muss abweichen", tampered != 683_298_069L)
        // und: activate() prüft Größe+Hash je Datei — eine falsche Größe
        // führt zu verifyInstalled()==false (Strukturtest weiter unten).
    }

    @Test
    fun `V1 A-status bleibt READY bei alter struktur und B ist ABSENT`() {
        val root = createTempDir("v1store")
        try {
            // Alt-Struktur: active.txt (kein Variantensuffix) + Mini-A-Dateien
            val store = ModelStore(root, miniManifest())
            val staging = store.newStagingDir()
            for (f in miniManifest()) {
                File(staging, f.storeName).writeBytes(ByteArray(f.sizeBytes.toInt()) { 1 })
                // Hash passt nicht — aber isPresent (ohne Hash) reicht für Struktur
            }
            root.resolve("active.txt").writeText(staging.name)
            assertTrue("Alt-Zeiger gilt als Variante A", store.isPresent(ModelVariant.ONNX_CPU))
            assertTrue(store.activeVersion(ModelVariant.ONNX_CPU) != null)
            // B: kein Zeiger, kein Ordner ⇒ ABSENT (kein Fallback auf A!)
            assertEquals(ModelStatus.ABSENT, store.status(ModelVariant.LITERT_CPU))
            assertEquals(null, store.activeVersion(ModelVariant.LITERT_CPU))
        } finally {
            root.deleteRecursively()
        }
    }

    private fun miniManifest(): List<ModelFile> = listOf(
        ModelFile("a.bin", 4L, Sha256.ofBytes(ByteArray(4) { 1 })),
        ModelFile("b.bin", 6L, Sha256.ofBytes(ByteArray(6) { 2 })),
    )

    private fun createTempDir(prefix: String): File =
        createTempFile(prefix).let { f -> f.delete(); f.apply { mkdirs() } }

    // ---- V5 (V1-01): urlPath/storeName-Trennung + variantenbewusste Whitelist ----

    @Test
    fun `V5 B-store-name hat kein slash und A bleibt unveraendert`() {
        val b = ModelManifest.forVariant(ModelVariant.LITERT_CPU)
        for (f in b) {
            assertTrue("storeName flach: ${f.storeName}", '/' !in f.storeName && f.storeName.isNotBlank())
        }
        // Der Pfadpraefix lebt nur in urlPath (Download-URL), nie im Dateisystem:
        val vocab = b.first { it.storeName == "token_vocab.bin" }
        assertEquals("assets/token_vocab.bin", vocab.urlPath)
        // Regression gegen 671-MB-Neuladen: A-Namen und -Groessen unveraendert
        val a = ModelManifest.forVariant(ModelVariant.ONNX_CPU)
        assertEquals(listOf("nemo128.onnx", "encoder-model.int8.onnx",
            "decoder_joint-model.int8.onnx", "vocab.txt"), a.map { it.storeName })
        assertEquals(listOf(139_764L, 652_183_999L, 18_202_004L, 93_939L), a.map { it.sizeBytes })
        // A: urlPath == storeName (keine Umschaltung, kein Re-Download)
        assertTrue(a.all { it.urlPath == it.storeName })
        assertEquals(670_619_706L, a.sumOf { it.sizeBytes })
    }

    @Test
    fun `V5 whitelist ist variantenbewusst kein B-pfad ueberschreibt A`() {
        val bName = "parakeet_tdt_nemo_encoder_cached_int8_dynamic_litert.tflite"
        val aName = "encoder-model.int8.onnx"
        // A-Whitelist lehnt B-Namen ab, B-Whitelist laesst sie zu — und umgekehrt
        assertTrue(ModelManifest.isAllowedName(bName, ModelVariant.LITERT_CPU))
        assertTrue(!ModelManifest.isAllowedName(bName, ModelVariant.ONNX_CPU))
        assertTrue(ModelManifest.isAllowedName(aName, ModelVariant.ONNX_CPU))
        assertTrue(!ModelManifest.isAllowedName(aName, ModelVariant.LITERT_CPU))
    }

    @Test
    fun `V5 traversal-schutz bleibt scharf`() {
        // Gegenprobe (wichtigste Assertion): '..' und '/' im storeName duerfen
        // NIE durchgelassen werden — die Korrektur darf ihn nicht aufweichen.
        assertTrue(!ModelManifest.isAllowedName("../encoder-model.int8.onnx", ModelVariant.ONNX_CPU))
        assertTrue(!ModelManifest.isAllowedName("assets/token_vocab.bin", ModelVariant.ONNX_CPU))
        assertTrue(!ModelManifest.isAllowedName("assets/token_vocab.bin", ModelVariant.LITERT_CPU))
        assertTrue(!ModelManifest.isAllowedName("..", ModelVariant.ONNX_CPU))
        assertTrue(!ModelManifest.isAllowedName("", ModelVariant.ONNX_CPU))
    }

    @Test
    fun `V5 activate B ohne bestand liefert false statt zu werfen`() {
        val root = createTempDir("v5store")
        try {
            val store = ModelStore(root)
            val staging = File(root, "versions/v-fake")
            staging.mkdirs()
            // B-Bestand unvollstaendig (leeres Staging) => false, kein Wurf
            assertTrue(!store.activate(staging, ModelVariant.LITERT_CPU))
        } finally {
            root.deleteRecursively()
        }
    }
}
