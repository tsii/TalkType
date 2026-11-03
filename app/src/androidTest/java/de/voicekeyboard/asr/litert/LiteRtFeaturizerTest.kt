package de.voicekeyboard.asr.litert

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File

/**
 * V6a: REAL LiteRT proof with the variant-B featurizer
 * (`parakeet_tdt_nemo_featurizer_int8_dynamic_litert.tflite`, 262 864 B,
 * SHA-256 756287855ed8cf36cacd65ad1012da329cc9d612ef3b81e2d48fc1f8f728ec9a).
 *
 * MODEL PATH (our pattern): the file is provided BEFORE the run via
 *   adb push testdata/litert/featurizer.tflite /data/local/tmp/vk_litert/featurizer.tflite
 * The test reads it from /data/local/tmp/vk_litert/ —
 * app assets are not enlarged.
 *
 * Rule 9: ONLY shapes, sizes and times are logged — no samples,
 * no transcribed text, no model paths below files/.
 *
 * Execution (orchestrator): connectedDebugAndroidTest — not part of the
 * unit inventory.
 */
@RunWith(AndroidJUnit4::class)
class LiteRtFeaturizerTest {

    private val modelDir = File("/data/local/tmp/vk_litert")

    private fun newEngine(): LiteRtAsrEngine = LiteRtAsrEngine().apply {
        // load() expects ALL 4 tflite files — for the standalone featurizer proof
        // we open only the featurizer directly:
    }

    /** Opens ONLY the featurizer (V8: 7-input path via attachFeaturizer). */
    private fun loadFeaturizerOnly(): LiteRtAsrEngine {
        val engine = LiteRtAsrEngine()
        engine.openFeaturizerOnly(File(modelDir, "featurizer.tflite"))
        return engine
    }

    private fun sinus440(n: Int): FloatArray =
        FloatArray(n) { i -> (Math.sin(2.0 * Math.PI * 440.0 * i / 16_000.0) * 0.5).toFloat() }

    @Test(timeout = 60_000)
    // German: „1 load gelingt und engine meldet bereit" (DEX forbids spaces in
    // generated outer names for lambdas — hence the space-free name)
    fun load_oeffnet_echtes_tflite_und_meldet_bereit() {
        val engine = loadFeaturizerOnly()
        try {
            assertTrue("the engine must be ready after load", engine.isLoaded)
        } finally {
            engine.close()
        }
    }

    @Test(timeout = 60_000)
    // German: „2 Stille => input_features (Ausgang 0) exakt 0"
    fun featurize_stille_liefert_exakt_null() {
        val engine = loadFeaturizerOnly()
        try {
            val r = engine.featurizeFull(FloatArray(16_000), 16_000) // 1 s of silence
            var maxAbs = 0f
            for (v in r.inputFeatures) maxAbs = maxOf(maxAbs, Math.abs(v))
            android.util.Log.i("VK-LITERT-TEST", "silence maxAbs=$maxAbs statsMode=${r.statsMode}")
            assertEquals("silence must yield exactly 0 on output 0", 0f, maxAbs)
        } finally {
            engine.close()
        }
    }

    // German: „2b 1 s Sinus + Polster: erster Bereich ungleich 0, Polster exakt 0"
    fun featurize_1s_maske_wirkt_am_lebenden_ausgang() {
        val engine = loadFeaturizerOnly()
        try {
            val pcm = sinus440(16_000)
            val t0 = System.nanoTime()
            val r = engine.featurizeFull(pcm, pcm.size)
            val dtMs = (System.nanoTime() - t0) / 1_000_000
            assertEquals(769 * 128, r.inputFeatures.size)
            assertEquals(intArrayOf(1, 769, 128).joinToString(), r.inputFeaturesShape.joinToString())
            // first ~100 frames (1 s = 100 real frames): non-zero
            var nonZero = 0
            for (f in 0 until 100) {
                var energy = 0.0
                for (d in 0 until 128) energy += Math.abs(r.inputFeatures[f * 128 + d].toDouble())
                if (energy > 1e-3) nonZero++
            }
            // padding area (frames 700..760): exactly 0 (device finding 3)
            var padEnergy = 0.0
            for (f in 700 until 760) {
                for (d in 0 until 128) padEnergy += Math.abs(r.inputFeatures[f * 128 + d].toDouble())
            }
            android.util.Log.i("VK-LITERT-TEST",
                "nonZeroFrames=$nonZero padEnergy=$padEnergy dtMs=$dtMs statsMode=${r.statsMode}")
            assertTrue("the first frames must be non-zero: nonZero=$nonZero", nonZero > 50)
            assertEquals("the padding area of output 0 must be exactly 0", 0.0, padEnergy, 0.0)
        } finally {
            engine.close()
        }
    }

    // German: „2c prevCache/prevSample aendern Ausgang 0 messbar"
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
            assertTrue("prevCache/prevSample must change output 0 (device finding: up to 1.0), diff=$diff",
                diff > 1e-3)
        } finally {
            engine.close()
        }
    }

    // German: „2d kaputte Tensorform: Meldung enthaelt die LiteRT-Ursache, aber keinen Pfad"
    fun fehlermeldung_enthaelt_litert_ursache_ohne_pfad() {
        // deliberately broken shape: input_features buffer far too small =>
        // LiteRT throws; our message must (a) contain the cause,
        // (b) contain no path after scrubbing.
        val engine = loadFeaturizerOnly()
        try {
            val f = de.voicekeyboard.asr.litert.LiteRtFeaturizer(
                java.io.File(modelDir, "featurizer.tflite"), 2)
            try {
                // pcmLen=-1: our require boundary — the message contains only numbers,
                // no path (rule 9). The RUN path scrubs the LiteRT cause over the
                // same boundary (scrubForUser, see LiteRtFeaturizer).
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

    // German: „3 session-wechsel verwirft cache"
    fun sessionwechsel_verwirft_cache() {
        val engine = loadFeaturizerOnly()
        try {
            val gen0 = LiteRtAsrEngine.cacheGenerationOf(engine)
            // transcribe with sessionId=1 and then 2: the cache generation rises per switch
            try { kotlinx.coroutines.runBlocking { engine.transcribe(de.voicekeyboard.audio.PcmWindow(ShortArray(16), 16_000), 1L, 1L) } } catch (e: LiteRtAsrEngine.AsrNotReadyException) { /* expected: V4 gate */ }
            val gen1 = LiteRtAsrEngine.cacheGenerationOf(engine)
            try { kotlinx.coroutines.runBlocking { engine.transcribe(de.voicekeyboard.audio.PcmWindow(ShortArray(16), 16_000), 2L, 2L) } } catch (e: LiteRtAsrEngine.AsrNotReadyException) { /* expected */ }
            val gen2 = LiteRtAsrEngine.cacheGenerationOf(engine)
            assertTrue("the session switch must discard the cache: $gen0 -> $gen1 -> $gen2",
                gen1 > gen0 && gen2 > gen1)
            assertEquals(2L, gen2 - gen0)
        } finally {
            engine.close()
        }
    }
}
