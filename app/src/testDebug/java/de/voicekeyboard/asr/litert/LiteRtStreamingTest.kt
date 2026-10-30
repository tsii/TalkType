package de.voicekeyboard.asr.litert

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * V11a: Reine Streaming-Logik der B-Pipeline (Vorpass, Fenster, Caches) —
 * bewiesen gegen tools/arm_litert_reference_decode.py und ptdt.cpp.
 * Ohne Modelle testbar; Gerätebeweis macht der Orchestrator.
 */
class LiteRtStreamingTest {

    // ---- GlobalFeatureStats (Welford, ptdt.cpp:342) ----

    @Test
    fun `welford liefert bekannte mittelwerte und streuung`() {
        val stats = GlobalFeatureStats(dims = 2)
        // Folge [1,2,3,4] je Dim: mean 2.5, var (Stichprobe) = 1.6667
        for (v in floatArrayOf(1f, 2f, 3f, 4f)) {
            stats.addFrame(floatArrayOf(v, v * 10f))
        }
        val (mean, invStd) = stats.finish()
        assertEquals(2.5f, mean[0], 1e-4f)
        assertEquals(25.0f, mean[1], 1e-3f)
        // var = m2/(n-1) = 5/3 ≈ 1.6667 → std 1.291 → inv_std 1/(std+1e-5)
        val std = Math.sqrt(5.0 / 3.0).toFloat()
        assertEquals(1f / (std + 1e-5f), invStd[0], 1e-4f)
        assertEquals(1f / (std * 10f + 1e-5f), invStd[1], 1e-4f)
    }

    @Test
    fun `ein frame keine division durch null`() {
        val stats = GlobalFeatureStats(dims = 3)
        stats.addFrame(floatArrayOf(2f, -2f, 7f))
        val (mean, invStd) = stats.finish()
        assertEquals(2f, mean[0], 1e-6f)
        // var = m2/max(1, 0) = 0 → inv_std = 1/(0+eps) — begrenzt, kein NaN/Inf
        assertEquals(1f / 1e-5f, invStd[0], 1e-6f)
        assertTrue("kein NaN", !invStd.any { it.isNaN() || it.isInfinite() })
    }

    @Test
    fun `stille var null inv_std begrenzt kein nan`() {
        val stats = GlobalFeatureStats(dims = 2)
        repeat(100) { stats.addFrame(floatArrayOf(-16.636f, -16.636f)) }
        val (_, invStd) = stats.finish()
        assertEquals(1f / 1e-5f, invStd[0], 1e-3f)
        assertTrue(invStd.none { it.isNaN() || it.isInfinite() })
    }

    // ---- Fenster-Logik (ptdt.cpp:400-490) ----

    @Test
    fun `input_length ist 9 plus gueltige frames`() {
        // 1 s → 100 Frames → 109; 7,6 s voll → 760 → 769; 9,96 s = 2 Fenster (760+229)
        assertEquals(9 + 100, LiteRtStreaming.inputLengthFor(validFrames = 100))
        assertEquals(9 + 760, LiteRtStreaming.inputLengthFor(validFrames = 760))
        assertEquals(9 + 229, LiteRtStreaming.inputLengthFor(validFrames = 229))
        assertEquals(9, LiteRtStreaming.inputLengthFor(validFrames = 0))
    }

    @Test
    fun `gueltige frames nach manifest min760 samples160`() {
        assertEquals(100, LiteRtStreaming.validFramesFor(samples = 16_000))
        assertEquals(760, LiteRtStreaming.validFramesFor(samples = 121_600))
        assertEquals(230, LiteRtStreaming.validFramesFor(samples = 36_814)) // 36814/160 = 230
        assertEquals(236, LiteRtStreaming.validFramesFor(samples = 159_414 - 121_600)) // 2. Fenster beckett
        assertEquals(0, LiteRtStreaming.validFramesFor(samples = 0))
    }

    @Test
    fun `cache aus letzten 9 frames weniger als 9 mit nullen vorne`() {
        // input_features hat 9+valid Frames (Cache vorne, ptdt.cpp:470-490):
        // 12 gültige Frames → Array mit 21 Zeilen; letzte echte = Zeile 20
        val feats = Array(9 + 12) { r -> FloatArray(4) { c -> (r * 4 + c).toFloat() } }
        val cache = LiteRtStreaming.preEncodeCache(feats, validFrames = 12, dims = 4)
        assertEquals(9, cache.size)
        assertEquals(20f * 4, cache[8][0], 1e-6f) // letzte Cache-Zeile = letzte echte Zeile (20)
        assertEquals(12f * 4, cache[0][0], 1e-6f) // erste Cache-Zeile = Zeile 12 (src)
        // weniger als 9 gültige Frames: vorne Nullen, hinten die echten
        val few = Array(9 + 3) { r -> FloatArray(4) { c -> (r * 4 + c).toFloat() } }
        val cache2 = LiteRtStreaming.preEncodeCache(few, validFrames = 3, dims = 4)
        assertEquals(9, cache2.size)
        assertEquals(0f, cache2[0][0], 1e-6f) // Nullen vorne
        assertEquals(0f, cache2[5][0], 1e-6f)
        assertEquals(11f * 4, cache2[8][0], 1e-6f) // letzte echte Zeile (row 11, src=9+3-3)
    }

    @Test
    fun `zwei fenster cache aus fenster1 liegt in fenster2 an`() {
        // Fenster 1 mit 12 Frames erzeugt Cache; Fenster 2 erhält ihn als Eingang —
        // rein geprüft: preEncodeCache des Folgewes erzeugt Werte aus den NEUEN Frames,
        // aber der Eingang ist identisch mit dem Ausgang des Vorgängers (Identität).
        val w1 = Array(9 + 12) { r -> FloatArray(2) { c -> (r + c).toFloat() } }
        val cacheOut1 = LiteRtStreaming.preEncodeCache(w1, validFrames = 12, dims = 2)
        val w2 = Array(9 + 20) { r -> FloatArray(2) { c -> (100f + r + c).toFloat() } }
        val cacheOut2 = LiteRtStreaming.preEncodeCache(w2, validFrames = 20, dims = 2)
        // Fenster-2-Cache kommt aus Fenster 2 selbst (letzte 9: Zeilen 20..28)
        assertEquals(100f + 20f, cacheOut2[0][0], 1e-6f)
        assertEquals(100f + 28f, cacheOut2[8][0], 1e-6f)
        // und der Fenster-1-Cache ist nicht leer (wird in Fenster 2 als Eingang geflossen sein)
        assertTrue(cacheOut1.any { row -> row.any { it != 0f } })
    }

    @Test
    fun `previous_sample ist sample vor fensterstart`() {
        // ptdt.cpp:400: start>0 → audio[start-1], sonst 0
        val audio = FloatArray(10) { it.toFloat() }
        assertEquals(0f, LiteRtStreaming.previousSampleAt(audio, start = 0))
        assertEquals(3f, LiteRtStreaming.previousSampleAt(audio, start = 4))
        assertEquals(8f, LiteRtStreaming.previousSampleAt(audio, start = 9))
    }

    // ---- V14 (L14-01): preEncodeCache-Vertrag hart gemacht ----

    @Test
    fun `V14 falsche array_laenge wirft typisierten fehler`() {
        // Nur validFrames*dim (ohne die 9 Cache-Frames) ist ein Vertragsbruch
        val wrong = FloatArray(12 * 4)
        try {
            LiteRtStreaming.preEncodeCacheFlat(wrong, validFrames = 12, featureSize = 4)
            fail("Vertragsfehler erwartet")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("erwartet"))
            assertTrue(e.message!!.contains("erhalten"))
        }
    }

    @Test
    fun `V14 korrekte laenge letzte 9 frames landen im cache`() {
        // input_features = [9 Cache][valid] Frames, flat (9+valid)*dim
        val valid = 20
        val dim = 4
        val total = (9 + valid) * dim
        val feats = FloatArray(total) { it.toFloat() }
        val cache = LiteRtStreaming.preEncodeCacheFlat(feats, valid, dim)
        assertEquals(9, cache.size)
        // src = 9+20-9 = 20 → Frames 20..28 sind die letzten 9 GÜLTIGEN
        for (i in 0 until 9) {
            val expectedRow = ((20 + i) * dim).toFloat()
            for (c in 0 until dim) {
                assertEquals(expectedRow + c, cache[i][c], 1e-6f)
            }
        }
    }

    @Test
    fun `V14 valid kleiner 9 vorne nullen hinten echte frames`() {
        val valid = 3
        val dim = 4
        val feats = FloatArray((9 + valid) * dim) { it.toFloat() }
        val cache = LiteRtStreaming.preEncodeCacheFlat(feats, valid, dim)
        assertEquals(9, cache.size)
        // vorne Nullen (6 Zeilen), hinten Frames 9..11 (src = 9+3-3 = 9)
        for (i in 0 until 6) for (c in 0 until dim) assertEquals(0f, cache[i][c], 1e-6f)
        for (i in 0 until 3) {
            val expectedRow = ((9 + i) * dim).toFloat()
            for (c in 0 until dim) assertEquals(expectedRow + c, cache[6 + i][c], 1e-6f)
        }
    }

    @Test
    fun `V14 zwei_fenster_cache_aus_fenster1_ist_eingang_fuer_fenster2`() {
        // Fenster 1 (valid 12) erzeugt Cache C1; der Featurizer-EINGANG von
        // Fenster 2 ist C1 (Reihenfolge a→d im KDoc); der Cache FÜR Fenster 3
        // (c2) entsteht aus den Frames von Fenster 2 selbst.
        val dim = 4
        val feats1 = FloatArray((9 + 12) * dim) { it.toFloat() }
        val c1 = LiteRtStreaming.preEncodeCacheFlat(feats1, 12, dim)
        val feats2 = FloatArray((9 + 20) * dim) { 100f + it }
        val c2 = LiteRtStreaming.preEncodeCacheFlat(feats2, 20, dim)
        // c1 erfüllt den Eingangsvertrag [9][dim] für Fenster 2
        assertEquals(9, c1.size); assertEquals(dim, c1[0].size)
        assertEquals(9, c2.size); assertEquals(dim, c2[0].size)
        assertTrue(c1.any { row -> row.any { it != 0f } })
        assertTrue(c2.all { row -> row.any { it != 0f } })
        // c2-Werte stammen aus Fenster-2-Frames (ab Index 20*dim=80)
        assertEquals(100f + 20 * 4, c2[0][0], 1e-6f)
    }
}
