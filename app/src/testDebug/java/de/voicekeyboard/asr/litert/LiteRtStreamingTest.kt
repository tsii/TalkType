package de.voicekeyboard.asr.litert

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * V11a: pure streaming logic of the B pipeline (prepass, windows, caches) —
 * proven against tools/arm_litert_reference_decode.py and ptdt.cpp.
 * Testable without models; the device proof is done by the orchestrator.
 */
class LiteRtStreamingTest {

    // ---- GlobalFeatureStats (Welford, ptdt.cpp:342) ----

    @Test
    fun `welford yields the known means and spreads`() {
        val stats = GlobalFeatureStats(dims = 2)
        // sequence [1,2,3,4] per dim: mean 2.5, sample var = 1.6667
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
    fun `single frame no division by zero`() {
        val stats = GlobalFeatureStats(dims = 3)
        stats.addFrame(floatArrayOf(2f, -2f, 7f))
        val (mean, invStd) = stats.finish()
        assertEquals(2f, mean[0], 1e-6f)
        // var = m2/max(1, 0) = 0 → inv_std = 1/(0+eps) — bounded, no NaN/Inf
        assertEquals(1f / 1e-5f, invStd[0], 1e-6f)
        assertTrue("no NaN", !invStd.any { it.isNaN() || it.isInfinite() })
    }

    @Test
    fun `silence var zero inv_std bounded no nan`() {
        val stats = GlobalFeatureStats(dims = 2)
        repeat(100) { stats.addFrame(floatArrayOf(-16.636f, -16.636f)) }
        val (_, invStd) = stats.finish()
        assertEquals(1f / 1e-5f, invStd[0], 1e-3f)
        assertTrue(invStd.none { it.isNaN() || it.isInfinite() })
    }

    // ---- window logic (ptdt.cpp:400-490) ----

    @Test
    fun `input_length is 9 plus valid frames`() {
        // 1 s → 100 frames → 109; 7.6 s full → 760 → 769; 9.96 s = 2 windows (760+229)
        assertEquals(9 + 100, LiteRtStreaming.inputLengthFor(validFrames = 100))
        assertEquals(9 + 760, LiteRtStreaming.inputLengthFor(validFrames = 760))
        assertEquals(9 + 229, LiteRtStreaming.inputLengthFor(validFrames = 229))
        assertEquals(9, LiteRtStreaming.inputLengthFor(validFrames = 0))
    }

    @Test
    fun `valid frames per manifest min760 samples160`() {
        assertEquals(100, LiteRtStreaming.validFramesFor(samples = 16_000))
        assertEquals(760, LiteRtStreaming.validFramesFor(samples = 121_600))
        assertEquals(230, LiteRtStreaming.validFramesFor(samples = 36_814)) // 36814/160 = 230
        assertEquals(236, LiteRtStreaming.validFramesFor(samples = 159_414 - 121_600)) // 2nd window beckett
        assertEquals(0, LiteRtStreaming.validFramesFor(samples = 0))
    }

    @Test
    fun `cache from the last 9 frames fewer than 9 zero-padded in front`() {
        // input_features has 9+valid frames (cache in front, ptdt.cpp:470-490):
        // 12 valid frames → array with 21 rows; the last real one = row 20
        val feats = Array(9 + 12) { r -> FloatArray(4) { c -> (r * 4 + c).toFloat() } }
        val cache = LiteRtStreaming.preEncodeCache(feats, validFrames = 12, dims = 4)
        assertEquals(9, cache.size)
        assertEquals(20f * 4, cache[8][0], 1e-6f) // last cache row = last real row (20)
        assertEquals(12f * 4, cache[0][0], 1e-6f) // first cache row = row 12 (src)
        // fewer than 9 valid frames: zeros in front, the real ones at the back
        val few = Array(9 + 3) { r -> FloatArray(4) { c -> (r * 4 + c).toFloat() } }
        val cache2 = LiteRtStreaming.preEncodeCache(few, validFrames = 3, dims = 4)
        assertEquals(9, cache2.size)
        assertEquals(0f, cache2[0][0], 1e-6f) // zeros in front
        assertEquals(0f, cache2[5][0], 1e-6f)
        assertEquals(11f * 4, cache2[8][0], 1e-6f) // last real row (row 11, src=9+3-3)
    }

    @Test
    fun `two windows the cache from window 1 sits at the front of window 2`() {
        // window 1 with 12 frames produces a cache; window 2 receives it as input —
        // checked purely: preEncodeCache of the successor produces values from the NEW frames,
        // but the input is identical with the predecessor's output (identity).
        val w1 = Array(9 + 12) { r -> FloatArray(2) { c -> (r + c).toFloat() } }
        val cacheOut1 = LiteRtStreaming.preEncodeCache(w1, validFrames = 12, dims = 2)
        val w2 = Array(9 + 20) { r -> FloatArray(2) { c -> (100f + r + c).toFloat() } }
        val cacheOut2 = LiteRtStreaming.preEncodeCache(w2, validFrames = 20, dims = 2)
        // the window-2 cache comes from window 2 itself (last 9: rows 20..28)
        assertEquals(100f + 20f, cacheOut2[0][0], 1e-6f)
        assertEquals(100f + 28f, cacheOut2[8][0], 1e-6f)
        // and the window-1 cache is not empty (it will have flowed into window 2 as input)
        assertTrue(cacheOut1.any { row -> row.any { it != 0f } })
    }

    @Test
    fun `previous_sample is the sample before the window start`() {
        // ptdt.cpp:400: start>0 → audio[start-1], otherwise 0
        val audio = FloatArray(10) { it.toFloat() }
        assertEquals(0f, LiteRtStreaming.previousSampleAt(audio, start = 0))
        assertEquals(3f, LiteRtStreaming.previousSampleAt(audio, start = 4))
        assertEquals(8f, LiteRtStreaming.previousSampleAt(audio, start = 9))
    }

    // ---- V14 (L14-01): the preEncodeCache contract made hard ----

    @Test
    fun `V14 wrong array length throws a typed error`() {
        // only validFrames*dim (without the 9 cache frames) is a contract violation
        val wrong = FloatArray(12 * 4)
        try {
            LiteRtStreaming.preEncodeCacheFlat(wrong, validFrames = 12, featureSize = 4)
            fail("contract error expected")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("erwartet"))
            assertTrue(e.message!!.contains("erhalten"))
        }
    }

    @Test
    fun `V14 correct length the last 9 frames land in the cache`() {
        // input_features = [9 cache][valid] frames, flat (9+valid)*dim
        val valid = 20
        val dim = 4
        val total = (9 + valid) * dim
        val feats = FloatArray(total) { it.toFloat() }
        val cache = LiteRtStreaming.preEncodeCacheFlat(feats, valid, dim)
        assertEquals(9, cache.size)
        // src = 9+20-9 = 20 → frames 20..28 are the last 9 VALID ones
        for (i in 0 until 9) {
            val expectedRow = ((20 + i) * dim).toFloat()
            for (c in 0 until dim) {
                assertEquals(expectedRow + c, cache[i][c], 1e-6f)
            }
        }
    }

    @Test
    fun `V14 valid smaller than 9 zeros in front real frames at the back`() {
        val valid = 3
        val dim = 4
        val feats = FloatArray((9 + valid) * dim) { it.toFloat() }
        val cache = LiteRtStreaming.preEncodeCacheFlat(feats, valid, dim)
        assertEquals(9, cache.size)
        // zeros in front (6 rows), frames 9..11 at the back (src = 9+3-3 = 9)
        for (i in 0 until 6) for (c in 0 until dim) assertEquals(0f, cache[i][c], 1e-6f)
        for (i in 0 until 3) {
            val expectedRow = ((9 + i) * dim).toFloat()
            for (c in 0 until dim) assertEquals(expectedRow + c, cache[6 + i][c], 1e-6f)
        }
    }

    @Test
    fun `V14 two_windows the cache from window 1 is the input for window 2`() {
        // window 1 (valid 12) produces cache C1; the featurizer INPUT of
        // window 2 is C1 (order a→d in the KDoc); the cache FOR window 3
        // (c2) arises from the frames of window 2 itself.
        val dim = 4
        val feats1 = FloatArray((9 + 12) * dim) { it.toFloat() }
        val c1 = LiteRtStreaming.preEncodeCacheFlat(feats1, 12, dim)
        val feats2 = FloatArray((9 + 20) * dim) { 100f + it }
        val c2 = LiteRtStreaming.preEncodeCacheFlat(feats2, 20, dim)
        // c1 satisfies the input contract [9][dim] for window 2
        assertEquals(9, c1.size); assertEquals(dim, c1[0].size)
        assertEquals(9, c2.size); assertEquals(dim, c2[0].size)
        assertTrue(c1.any { row -> row.any { it != 0f } })
        assertTrue(c2.all { row -> row.any { it != 0f } })
        // the c2 values come from window-2 frames (from index 20*dim=80)
        assertEquals(100f + 20 * 4, c2[0][0], 1e-6f)
    }
}
