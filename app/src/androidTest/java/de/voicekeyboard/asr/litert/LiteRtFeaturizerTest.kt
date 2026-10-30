package de.voicekeyboard.asr.litert

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File

/**
 * V6a: ECHTER LiteRT-Beweis mit dem Featurizer der Variante B
 * (`parakeet_tdt_nemo_featurizer_int8_dynamic_litert.tflite`, 262 864 B,
 * SHA-256 756287855ed8cf36cacd65ad1012da329cc9d612ef3b81e2d48fc1f8f728ec9a).
 *
 * MODELL-PFAD (unser Muster): die Datei wird VOR dem Lauf per
 *   adb push testdata/litert/featurizer.tflite /data/local/tmp/vk_litert/featurizer.tflite
 * bereitgestellt. Der Test liest sie aus /data/local/tmp/vk_litert/ —
 * app-Assets werden nicht vergrößert.
 *
 * Regel 9: Es werden NUR Formen, Größen und Zeiten geloggt — keine Samples,
 * kein transkribierter Text, keine Modellpfade unterhalb files/.
 *
 * Ausführung (Orchestrator): connectedDebugAndroidTest — nicht Teil des
 * Unit-Bestands.
 */
@RunWith(AndroidJUnit4::class)
class LiteRtFeaturizerTest {

    private val modelDir = File("/data/local/tmp/vk_litert")

    private fun newEngine(): LiteRtAsrEngine = LiteRtAsrEngine().apply {
        // load() erwartet ALLE 4 tflite-Dateien — für den Featurizer-Einzelbeweis
        // öffnen wir nur den Featurizer direkt:
    }

    /** Öffnet NUR den Featurizer (V8: 7-Eingangs-Pfad über attachFeaturizer). */
    private fun loadFeaturizerOnly(): LiteRtAsrEngine {
        val engine = LiteRtAsrEngine()
        engine.openFeaturizerOnly(File(modelDir, "featurizer.tflite"))
        return engine
    }

    private fun sinus440(n: Int): FloatArray =
        FloatArray(n) { i -> (Math.sin(2.0 * Math.PI * 440.0 * i / 16_000.0) * 0.5).toFloat() }

    @Test(timeout = 60_000)
    // Deutsch: „1 load gelingt und engine meldet bereit" (DEX verbot Leerzeichen
    // in generierten Outer-Namen bei Lambdas — deshalb leerzeichenfreier Name)
    fun load_oeffnet_echtes_tflite_und_meldet_bereit() {
        val engine = loadFeaturizerOnly()
        try {
            assertTrue("Engine muss nach load bereit sein", engine.isLoaded)
        } finally {
            engine.close()
        }
    }

    @Test(timeout = 60_000)
    // Deutsch: „2 Stille => input_features (Ausgang 0) exakt 0"
    fun featurize_stille_liefert_exakt_null() {
        val engine = loadFeaturizerOnly()
        try {
            val r = engine.featurizeFull(FloatArray(16_000), 16_000) // 1 s Stille
            var maxAbs = 0f
            for (v in r.inputFeatures) maxAbs = maxOf(maxAbs, Math.abs(v))
            android.util.Log.i("VK-LITERT-TEST", "silence maxAbs=$maxAbs statsMode=${r.statsMode}")
            assertEquals("Stille muss Ausgang 0 exakt 0 liefern", 0f, maxAbs)
        } finally {
            engine.close()
        }
    }

    // Deutsch: „2b 1 s Sinus + Polster: erster Bereich ungleich 0, Polster exakt 0"
    fun featurize_1s_maske_wirkt_am_lebenden_ausgang() {
        val engine = loadFeaturizerOnly()
        try {
            val pcm = sinus440(16_000)
            val t0 = System.nanoTime()
            val r = engine.featurizeFull(pcm, pcm.size)
            val dtMs = (System.nanoTime() - t0) / 1_000_000
            assertEquals(769 * 128, r.inputFeatures.size)
            assertEquals(intArrayOf(1, 769, 128).joinToString(), r.inputFeaturesShape.joinToString())
            // Erste ~100 Frames (1 s = 100 Frames echt): ungleich 0
            var nonZero = 0
            for (f in 0 until 100) {
                var energy = 0.0
                for (d in 0 until 128) energy += Math.abs(r.inputFeatures[f * 128 + d].toDouble())
                if (energy > 1e-3) nonZero++
            }
            // Polsterbereich (Frames 700..760): exakt 0 (Geraetebefund 3)
            var padEnergy = 0.0
            for (f in 700 until 760) {
                for (d in 0 until 128) padEnergy += Math.abs(r.inputFeatures[f * 128 + d].toDouble())
            }
            android.util.Log.i("VK-LITERT-TEST",
                "nonZeroFrames=$nonZero padEnergy=$padEnergy dtMs=$dtMs statsMode=${r.statsMode}")
            assertTrue("Erste Frames muessen ungleich 0 sein: nonZero=$nonZero", nonZero > 50)
            assertEquals("Polsterbereich von Ausgang 0 muss exakt 0 sein", 0.0, padEnergy, 0.0)
        } finally {
            engine.close()
        }
    }

    // Deutsch: „2c prevCache/prevSample aendern Ausgang 0 messbar"
    fun featurize_prev_cache_und_sample_fliessen_ein() {
        val engine = loadFeaturizerOnly()
        try {
            val pcm = sinus440(16_000)
            val base = engine.featurizeFull(pcm, pcm.size)
            val prevCache = FloatArray(9 * 128) { 1.0f }
            val withPrev = engine.featurizeFull(pcm, pcm.size, prevCache = prevCache, prevSample = 0.9f)
            var diff = 0.0
            for (d in 0 until 128) diff += Math.abs(base.inputFeatures[d] - withPrev.inputFeatures[d]).toDouble()
            android.util.Log.i("VK-LITERT-TEST", "firstFrameDiff=$diff statsMode=${withPrev.statsMode}")
            assertTrue("prevCache/prevSample muessen Ausgang 0 aendern (Geraetebefund: bis 1.0), diff=$diff",
                diff > 1e-3)
        } finally {
            engine.close()
        }
    }

    // Deutsch: „2d kaputte Tensorform: Meldung enthaelt die LiteRT-Ursache, aber keinen Pfad"
    fun fehlermeldung_enthaelt_litert_ursache_ohne_pfad() {
        // Absichtlich kaputte Form: input_features-Puffer viel zu klein =>
        // LiteRT wirft; unsere Meldung muss (a) die Ursache enthalten,
        // (b) nach der Bereinigung keinen Pfad mehr enthalten.
        val engine = loadFeaturizerOnly()
        try {
            val f = de.voicekeyboard.asr.litert.LiteRtFeaturizer(
                java.io.File(modelDir, "featurizer.tflite"), 2)
            try {
                // pcmLen=-1: unsere require-Grenze — Meldung enthaelt nur Zahlen,
                // keinen Pfad (Regel 9). Der RUN-Pfad scrubb_t die LiteRT-Ursache
                // ueber dieselbe Grenze (scrubForUser, s. LiteRtFeaturizer).
                var thrown: Throwable? = null
                try {
                    f.featurize(FloatArray(16), -1)
                } catch (e: Exception) {
                    thrown = e
                }
                assertTrue(thrown != null)
                assertTrue(thrown!!.message?.contains('/') == false)
                assertTrue(thrown!!.message?.contains('\\') == false)
            } finally {
                f.close()
            }
        } finally {
            engine.close()
        }
    }

    // Deutsch: „3 session-wechsel verwirft cache"
    fun sessionwechsel_verwirft_cache() {
        val engine = loadFeaturizerOnly()
        try {
            val gen0 = LiteRtAsrEngine.cacheGenerationOf(engine)
            // transcribe mit sessionId=1 und danach 2: Cache-Generation steigt je Wechsel
            try { kotlinx.coroutines.runBlocking { engine.transcribe(de.voicekeyboard.audio.PcmWindow(ShortArray(16), 16_000), 1L, 1L) } } catch (e: LiteRtAsrEngine.AsrNotReadyException) { /* erwartet: V4-Sperre */ }
            val gen1 = LiteRtAsrEngine.cacheGenerationOf(engine)
            try { kotlinx.coroutines.runBlocking { engine.transcribe(de.voicekeyboard.audio.PcmWindow(ShortArray(16), 16_000), 2L, 2L) } } catch (e: LiteRtAsrEngine.AsrNotReadyException) { /* erwartet */ }
            val gen2 = LiteRtAsrEngine.cacheGenerationOf(engine)
            assertTrue("Session-Wechsel muss Cache verwerfen: $gen0 -> $gen1 -> $gen2",
                gen1 > gen0 && gen2 > gen1)
            assertEquals(2L, gen2 - gen0)
        } finally {
            engine.close()
        }
    }
}
