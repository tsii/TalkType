package de.voicekeyboard.dictation

import java.io.File

/**
 * F35 ("route D", docs/KORPUS_DIKTAT_TESTPLAN.md §2): corpus fixture at RUNTIME —
 * NO new binary assets, no network. Concatenates the four existing
 * 16 kHz/s16/mono assets (de_smoke + de_part1 + de_part2 + de_pause) and
 * stretches the sequence with 0.3 s zero blocks between repetitions to the
 * exact target length (30 s / 60 s). Rule 9: samples never leave the test —
 * assertions/logs only see counters and durations.
 */
object KorpusFixture {
    const val SAMPLE_RATE = 16_000

    /** Feed block size of the test source: 320 samples = 20 ms (test plan §3). */
    const val BLOCK_SAMPLES = 320

    /** 0.3 s synthetic silence (zero blocks) between repetitions (§2). */
    const val GAP_SAMPLES = 4_800

    /** F43: trim the internal silence sequences of the corpus parts to 2.0 s —
     *  otherwise the new auto-stop (DictationCoordinator.TRAILING_SILENCE_MS
     *  = 2500 ms) would end the paced run in the middle of the fixture
     *  (de_pause.wav contains 4.38 s of silence at 2.4 s). Test fixture
     *  adjustment only, no product code; the limit is deliberately BELOW
     *  2500 ms. Threshold = production SILENCE_RMS (0.01). */
    private const val MAX_SILENT_RUN_BLOCKS = 100 // 2,0 s @ 320 Samples / 16 kHz

    private val PART_NAMES = listOf("de_smoke.wav", "de_part1.wav", "de_part2.wav", "de_pause.wav")

    /** The four corpus parts (lazy, read once per test JVM). */
    val parts: List<ShortArray> by lazy { PART_NAMES.map { loadWavS16Mono(resolve(it)) } }

    /** Real duration of the parts in s (samples/16000) — for the actual report. */
    fun partSeconds(): List<Double> = parts.map { it.size.toDouble() / SAMPLE_RATE }

    /**
     * Fixture with EXACTLY [totalSamples] samples: the four parts in order,
     * 0.3 s zero blocks per cycle, repetition, cut off at the target
     * (480,000 samples = 30.0 s / 960,000 = 60.0 s — both divisible by
     * 32,000, i.e. no remainder in the coordinator tick).
     */
    fun build(totalSamples: Int): ShortArray {
        check(totalSamples > 0)
        // F43: pause-clamped copies of the parts (the originals in [parts] stay
        // unchanged for the actual report — partSeconds() reports the real durations).
        val src = parts.map { clampSilence(it) }
        val units = src.size + 1 // 4 parts + 1 silence gap per cycle
        val out = ShortArray(totalSamples)
        var pos = 0
        var idx = 0
        while (pos < totalSamples) {
            val unit = if (idx % units == src.size) ShortArray(GAP_SAMPLES) else src[idx % units]
            val n = minOf(unit.size, totalSamples - pos)
            System.arraycopy(unit, 0, out, pos, n)
            pos += n
            idx++
        }
        return out
    }

    /** F43: trims silence sequences (block RMS < production SILENCE_RMS) to
     *  [MAX_SILENT_RUN_BLOCKS] blocks; loud stays untouched. Pure fixture
     *  logic (no product behavior). */
    private fun clampSilence(samples: ShortArray): ShortArray {
        var run = 0
        var w = 0
        val out = ShortArray(samples.size)
        var i = 0
        while (i < samples.size) {
            val end = minOf(i + BLOCK_SAMPLES, samples.size)
            var acc = 0L
            for (j in i until end) acc += samples[j].toLong() * samples[j]
            val silent = kotlin.math.sqrt(acc.toDouble() / (end - i)) / 32_768.0 < 0.01
            if (silent && run >= MAX_SILENT_RUN_BLOCKS) {
                i = end // silent block above the limit: discard (pause trimmed)
                continue
            }
            if (silent) run++ else run = 0
            for (j in i until end) {
                out[w] = samples[j]
                w++
            }
            i = end
        }
        return if (w == samples.size) samples else out.copyOf(w)
    }

    /** Finds testdata/ from the test working directory (walk-up, max 6 levels). */
    private fun resolve(name: String): File {
        var dir = File(System.getProperty("user.dir") ?: ".")
        repeat(6) {
            val cand = File(dir, "testdata")
            if (File(cand, name).isFile) return cand.resolve(name)
            dir = dir.parentFile ?: return@repeat
        }
        throw IllegalStateException("testdata/$name nicht gefunden")
    }

    /** Minimal s16le-mono reader: finds the data chunk (independent of header layout). */
    private fun loadWavS16Mono(file: File): ShortArray {
        val b = file.readBytes()
        require(b.size > 44 && String(b, 0, 4) == "RIFF" && String(b, 8, 4) == "WAVE") {
            "Kein RIFF/WAVE: " + file.name
        }
        var i = 12
        while (i + 8 <= b.size) {
            val id = String(b, i, 4)
            val size = ((b[i + 4].toInt() and 0xff) or ((b[i + 5].toInt() and 0xff) shl 8) or
                ((b[i + 6].toInt() and 0xff) shl 16) or ((b[i + 7].toInt() and 0xff) shl 24))
            if (id == "data") {
                val n = minOf(size, b.size - i - 8)
                require(n > 1) { "data-Chunk leer: " + file.name }
                val out = ShortArray(n / 2)
                for (j in out.indices) {
                    val off = i + 8 + j * 2
                    out[j] = (b[off].toInt() or (b[off + 1].toInt() shl 8)).toShort()
                }
                return out
            }
            i += 8 + size + if (size and 1 == 1) 1 else 0
        }
        throw IllegalStateException("data-Chunk nicht gefunden: " + file.name)
    }
}
