package de.voicekeyboard.asr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * S12: Pure, JVM-testable ASR runtime configuration.
 * Lever 1: thread/quota setting of the CPU EP (AsrThreadConfig.forDevice).
 * Lever 2: chunk/hypothesis interval (chunkMs, hypothesisMinIntervalMs).
 *
 * Defaults = today's actual values (OnnxSessions: intra=min(4,cores), inter=1;
 * Coordinator: tick 1 s = chunkMs 1000; MIN_AUDIO_SECONDS_PARTIALS 2 s),
 * so that standard behavior stays identical. TDD: test before implementation.
 */
class AsrThreadConfigTest {

    // ---- Lever 1: forDevice clamping ----

    @Test
    fun `forDevice clamps intra down and up`() {
        // Mid range: cores=6 → intra=4 (today's actual: min(4, cores)), inter=1
        assertEquals(4, AsrThreadConfig.forDevice(cores = 6, ramMb = 4096).intraOpThreads)
        assertEquals(1, AsrThreadConfig.forDevice(cores = 6, ramMb = 4096).interOpThreads)
        // clamp upward: cores=64 → intra=max 6
        assertEquals(6, AsrThreadConfig.forDevice(cores = 64, ramMb = 8192).intraOpThreads)
        // exotically small: cores=1 → intra stays in the valid range (min 2 after clamp)
        assertEquals(2, AsrThreadConfig.forDevice(cores = 1, ramMb = 512).intraOpThreads)
        // ramMb=0 (unknown): default behavior as today (no crash)
        val c = AsrThreadConfig.forDevice(cores = 8, ramMb = 0)
        assertTrue(c.intraOpThreads in 2..6)
        assertTrue(c.interOpThreads in 1..2)
    }

    @Test
    fun `forDevice defaults are today's actual values`() {
        // Today's actual state: intra=min(4, cores), inter=1 → forDevice must deliver
        // the same values on typical 4-8-core devices.
        assertEquals(4, AsrThreadConfig.forDevice(cores = 4, ramMb = 4096).intraOpThreads)
        assertEquals(1, AsrThreadConfig.forDevice(cores = 4, ramMb = 4096).interOpThreads)
    }

    // ---- Lever 1: application provable (fake receiver) ----

    @Test
    fun `applyTo sets exactly the configured values on the fake`() {
        val fake = FakeSessionOptionReceiver()
        val config = AsrThreadConfig(intraOpThreads = 3, interOpThreads = 2)
        config.applyTo(fake)
        assertEquals(3, fake.intra)
        assertEquals(2, fake.inter)
        // default config: same values as today (min(4,..)/1)
        val default = AsrThreadConfig.forDevice(cores = 4, ramMb = 4096)
        default.applyTo(fake)
        assertEquals(4, fake.intra)
        assertEquals(1, fake.inter)
    }

    /** Test double for the ORT options interface (no ORT internals reflection). */
    private class FakeSessionOptionReceiver : AsrThreadConfig.OptionReceiver {
        var intra = -1
        var inter = -1
        override fun setIntraOpThreads(n: Int) { intra = n }
        override fun setInterOpThreads(n: Int) { inter = n }
    }

    // ---- Lever 2: chunk/interval logic (pure, without audio) ----

    @Test
    fun `chunks are cut exactly at the chunk boundaries`() {
        // F21: chunkMs GEOMETRY is unchanged — the test pins chunkMs=1000
        // (the DEFAULT has been 2000 since F21, but that belongs to the default asserts).
        val cfg = AsrThreadConfig(intraOpThreads = 4, interOpThreads = 1, chunkMs = 1000)
        // 1 s chunk = 16000 samples at 16 kHz; 5 s audio → boundaries 1..5 s
        val boundaries = cfg.chunkBoundaries(totalSamples = 16000 * 5)
        assertEquals(listOf(16000, 32000, 48000, 64000, 80000), boundaries)
        // remainder: 2.5 s → boundaries at 1 s and 2 s (remaining 0.5 s in the last chunk)
        assertEquals(listOf(16000, 32000), cfg.chunkBoundaries(totalSamples = 40000))
    }

    @Test
    fun `hypotheses no more often than interval but at least once per chunk`() {
        val cfg = AsrThreadConfig.DEFAULT // chunkMs=1000, hypothesisMinIntervalMs=2000
        // ticks of 1 s each: hypothesis only every >= 2 s, the first at the latest after 2 s
        val emissionTimes = cfg.hypothesisEmissionTimes(tickTimesMs = listOf(1000, 2000, 3000, 4000, 5000))
        // first hypothesis immediately at the first valid tick (at least once per chunk)
        assertEquals(listOf(1000, 3000, 5000), emissionTimes)
        var last = Long.MIN_VALUE / 2 // Long base: no Int overflow on the first element
        for (t in emissionTimes) {
            assertTrue("interval violated at $t (last $last)",
                t - last >= cfg.hypothesisMinIntervalMs)
            last = t.toLong()
        }
        assertTrue("at least one emission", emissionTimes.isNotEmpty())
    }

    @Test
    fun `defaults for chunk and interval match the current target values`() {
        // F21 (user approval 2026-10-30): Coordinator tick 2 s (chunkMs=2000),
        // partials from 2 s (interval 2000) — reason: quadratic re-decoding.
        assertEquals(2000, AsrThreadConfig.DEFAULT.chunkMs)
        assertEquals(2000, AsrThreadConfig.DEFAULT.hypothesisMinIntervalMs)
        assertEquals(4, AsrThreadConfig.DEFAULT.intraOpThreads)
        assertEquals(1, AsrThreadConfig.DEFAULT.interOpThreads)
        // F21: pace brackets of the cost-aware decode pace (DecodePace)
        assertEquals(2000L, AsrThreadConfig.DEFAULT.decodePaceBaseMs)
        assertEquals(6000L, AsrThreadConfig.DEFAULT.decodePaceMaxMs)
    }

    // ---- S12b: defaults bit-exact = today's Coordinator behavior ----

    @Test
    fun `S12b default tick equals exactly two TICK_SAMPLES_1S`() {
        // F21: tickSamples = chunkMs * 16_000 / 1000 = 32_000 with defaults (chunkMs 2000)
        val tick = AsrThreadConfig.DEFAULT.chunkMs * 16_000 / 1000
        assertEquals(2 * de.voicekeyboard.ime.DictationCoordinator.TICK_SAMPLES_1S, tick)
    }

    @Test
    fun `S12b changed config cuts and emits accordingly without duplicate text`() {
        // chunkMs=500, hypothesisMinIntervalMs=1000: boundaries every 500 ms,
        // emissions at most every 1000 ms — no duplicate output across
        // chunk boundaries (emission times are strictly monotonically increasing
        // and pairwise >= interval apart).
        val cfg = AsrThreadConfig(intraOpThreads = 4, interOpThreads = 1,
            chunkMs = 500, hypothesisMinIntervalMs = 1000)
        assertEquals(listOf(8000, 16000, 24000), cfg.chunkBoundaries(totalSamples = 24000))
        val ticks = (1..10).map { it * 500 } // 10 ticks of 500 ms each
        val emissions = cfg.hypothesisEmissionTimes(ticks)
        assertEquals(listOf(500, 1500, 2500, 3500, 4500), emissions)
        // invariant: no two emissions within the same chunk window
        var lastChunk = -1
        for (e in emissions) {
            val chunkIdx = (e - 1) / 500
            assertTrue("duplicate emission in chunk $chunkIdx", chunkIdx != lastChunk)
            lastChunk = chunkIdx
        }
    }

    // ---- S12-01-FIX: device RAM instead of Java heap ----

    @Test
    fun `S12-01 ram threshold is a real specification, no heap peeking`() {
        // small DEVICE RAM (384 MB, e.g. old entry-level device) => reduction to 2
        assertEquals(2, AsrThreadConfig.forDevice(cores = 8, ramMb = 384).intraOpThreads)
        // unknown (0) => NO reduction: 4 on normal devices
        assertEquals(4, AsrThreadConfig.forDevice(cores = 8, ramMb = 0).intraOpThreads)
        // modern flagship (<device>, 16 GB) => 4 (not 6: formula up to 8 cores)
        assertEquals(4, AsrThreadConfig.forDevice(cores = 8, ramMb = 15_936).intraOpThreads)
        // heap values (256-512 MB) would NOT be passed here: the threshold 1..2047
        // only hits genuinely tiny RAM. Production sets deviceRamMb from ActivityManager.
    }

    @Test
    fun `S12-01 production source is deviceRamMb, never Runtime maxMemory`() {
        // The service sets deviceRamMb from ActivityManager.totalMem (fake here),
        // OnnxSessions and diagnostics read ONLY AsrThreadConfig.deviceRamMb —
        // the Java heap (maxMemory) never enters forDevice().
        AsrThreadConfig.deviceRamMb = 15_936L // e.g. ActivityManager.totalMem 16 GB
        assertEquals(4, AsrThreadConfig.forDevice(cores = 8, ramMb = AsrThreadConfig.deviceRamMb).intraOpThreads)
        // small device: ActivityManager.totalMem 384 MB
        AsrThreadConfig.deviceRamMb = 384L
        assertEquals(2, AsrThreadConfig.forDevice(cores = 8, ramMb = AsrThreadConfig.deviceRamMb).intraOpThreads)
        // unknown (initial value): no reduction
        AsrThreadConfig.deviceRamMb = 0L
        assertEquals(4, AsrThreadConfig.forDevice(cores = 8, ramMb = AsrThreadConfig.deviceRamMb).intraOpThreads)
    }

    @Test fun `V12 controller guard matches the config interval source`() {
        // behavior with defaults identical: MIN_AUDIO_SECONDS_PARTIALS (2.0 s)
        // now comes from AsrThreadConfig.DEFAULT.hypothesisMinIntervalMs.
        assertEquals(
            2.0,
            de.voicekeyboard.dictation.DictationController.MIN_AUDIO_SECONDS_PARTIALS,
            1e-9,
        )
        assertEquals(
            de.voicekeyboard.dictation.DictationController.MIN_AUDIO_SECONDS_PARTIALS,
            AsrThreadConfig.DEFAULT.hypothesisMinIntervalMs / 1000.0,
            1e-9,
        )
    }
}
