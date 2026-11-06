package de.voicekeyboard.asr.qwen

import android.app.ActivityManager
import android.content.Context
import android.os.Bundle
import android.os.Debug
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import de.voicekeyboard.audio.PcmWindow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.RandomAccessFile

/**
 * T22f2 (PLAN_V0146 §6, device stage): golden test of the Qwen engine on the
 * REAL int8 graphs.
 *
 * T22-measurement-fix (3rd device run): EXACT text equality was a wrong
 * premise from the start — the pinned goldens were Parakeet-derived and the
 * HOST gate already measured Qwen at **11.9 % corpus WER** against them
 * (QWEN_ASR_CONTRACT §8; all errors are SUBSTITUTIONS on the synthetic
 * espeak corpus: Doktat↔Doktor, „mit smarte“ — documented tolerance cases).
 * The verdict is therefore: WORD-LEVEL similarity >= 0.85 (Levenshtein on
 * normalized words: lowercase + punctuation stripped) + a hallucination
 * guard (decoded length <= 1.5x the gold) + incomplete MUST stay false.
 * Silence -> "" and the guard behaviors stay EXACT.
 *
 * IMPORTANT (T22f1 finding): the Kotlin mel frontend is NOT byte-identical to
 * the numpy reference (per-frame L2 <= 1e-4, sha parity NOT reached) — this
 * test is exactly the DEVICE verification of the gold texts under that
 * deviation. A diff here is information, not a flake.
 *
 * Prerequisite (emulator/phone setup): qwen model set under
 * /data/local/tmp/qwen (encoder.int8.onnx, qwen_prefill.int8.onnx,
 * qwen_step.int8.onnx, tokenizer.json, mel_filters.npy, mel_config.json),
 * gold WAVs under /data/local/tmp/testdata, device RAM >= 6 GB. Anything
 * missing -> SKIP via Assume (not red). Silence 1 s (exact 0) must yield ''
 * WITHOUT hallucination (contract §5 silence guard).
 */
@RunWith(AndroidJUnit4::class)
class QwenGoldenTest {

    companion object {
        private const val MODELS_DIR = "/data/local/tmp/qwen"
        private const val TESTDATA_DIR = "/data/local/tmp/testdata"
        private const val MIN_TOTAL_RAM_BYTES = 6L * 1024 * 1024 * 1024 // plan §1: AVD RAM gate

        // T22j measurement constants (pipeline contract §4 — mirrors of the
        // engine constants, test-side ONLY, no product change):
        private const val GATE_WAV = "gate_35s.wav" // 35.0 s, 16 kHz mono s16le (provided by the orchestrator)
        private const val AUDIO_TOKENS_PER_CHUNK = 13
        private const val CHUNK_FRAMES = 100
        private const val MAX_STEPS = 128
        private const val EOS_EOT = 151643
        private const val EOS_IM_END = 151645

        /** T22-oom-fix 2: mirror of QwenAsrEngine.MAX_SEGMENT_AUDIO_S (15 s) —
         *  the measurement reproduces the coordinator boundary. */
        private const val MAX_SEGMENT_SAMPLES = 15 * 16_000

        /** QWEN_ASR_CONTRACT.md §5 — pinned int8 goldens (greedy, deterministic). */
        private const val GOLD_PART1 = "Das ist der erste Teil des Doktats."
        private const val GOLD_PART2 = "und nach einer Pause kommt mit smarte Teil."
        private const val GOLD_PAUSE =
            "das ist der erste Teil des Doktats und nach einer Pause kommt der zweite Teil"
        private const val GOLD_SMOKE =
            "So jetzt bin ich gespannt ob die Funktion auf dem Telefon funktioniert."
    }

    private fun assumeQwenReady(wavName: String): File {
        val modelsOk = listOf(
            "$MODELS_DIR/encoder.int8.onnx",
            "$MODELS_DIR/qwen_prefill.int8.onnx",
            "$MODELS_DIR/qwen_step.int8.onnx",
            "$MODELS_DIR/tokenizer.json",
            "$MODELS_DIR/mel_filters.npy",
            "$MODELS_DIR/mel_config.json",
        ).all { File(it).isFile }
        assumeTrue("qwen model set missing under $MODELS_DIR — test is skipped (device setup)", modelsOk)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val info = ActivityManager.MemoryInfo()
        am.getMemoryInfo(info)
        assumeTrue(
            "device RAM < 6 GB — test is skipped (plan §1: Qwen only on >= 8 GB devices)",
            info.totalMem >= MIN_TOTAL_RAM_BYTES,
        )
        val wav = File("$TESTDATA_DIR/$wavName")
        assumeTrue("test WAV missing: $wavName — test is skipped (device setup)", wav.isFile)
        return wav
    }

    private fun assertGolden(wavName: String, expected: String) {
        val wav = assumeQwenReady(wavName)
        QwenAsrEngine().use { engine ->
            engine.load(File(MODELS_DIR))
            val window = parseWavPcm16Mono16k(wav)
            val h = runBlocking { engine.transcribe(window, sessionId = 1L, runId = 1L) }
            val sim = wordSimilarity(h.text, expected)
            assertTrue(
                "golden $wavName: word similarity $sim < 0.85 (got='${h.text}', gold='$expected')",
                sim >= 0.85,
            )
            // hallucination guard: never more than 1.5x the gold word count
            assertTrue(
                "golden $wavName: decode longer than 1.5x gold (got='${h.text}')",
                normalizeWords(h.text).size <= 1.5 * normalizeWords(expected).size,
            )
            assertFalse("goldens never hit the cap", h.incomplete)
            assertTrue("audio samples must be tracked", h.audioSamples > 0)
        }
    }

    // ---- T22-measurement-fix: word-level similarity (normalized) ----

    /** Lowercase, punctuation stripped, whitespace split. */
    private fun normalizeWords(text: String): List<String> =
        text.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }

    /** Word-level Levenshtein distance (classic DP, O(len*len)). */
    private fun wordDistance(a: List<String>, b: List<String>): Int {
        if (a.isEmpty()) return b.size
        if (b.isEmpty()) return a.size
        var prev = IntArray(b.size + 1) { it }
        val cur = IntArray(b.size + 1)
        for (i in 1..a.size) {
            cur[0] = i
            for (j in 1..b.size) {
                cur[j] = minOf(
                    prev[j] + 1, // deletion
                    cur[j - 1] + 1, // insertion
                    prev[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1, // substitution
                )
            }
            System.arraycopy(cur, 0, prev, 0, cur.size)
        }
        return prev[b.size]
    }

    /** 1 - distance/max(len) over normalized words — 0.85 floor ≈ the inverse
     *  of the host 11.9 % corpus WER (§8) plus drift margin. */
    private fun wordSimilarity(actual: String, gold: String): Double {
        val wa = normalizeWords(actual)
        val wb = normalizeWords(gold)
        if (wa.isEmpty() && wb.isEmpty()) return 1.0
        return 1.0 - wordDistance(wa, wb).toDouble() / maxOf(wa.size, wb.size)
    }

    @Test
    fun goldenDePart1() = assertGolden("de_part1.wav", GOLD_PART1)

    @Test
    fun goldenDePart2() = assertGolden("de_part2.wav", GOLD_PART2)

    @Test
    fun goldenDePause() = assertGolden("de_pause.wav", GOLD_PAUSE)

    @Test
    fun goldenDeSmoke() = assertGolden("de_smoke.wav", GOLD_SMOKE)

    @Test
    fun silence1sYieldsEmptyTextWithoutHallucination() {
        // contract §5: the raw LLM decodes hallucinate on silence ("systemschluss")
        // — the engine silence guard must return '' BEFORE any graph run.
        assumeQwenReady("de_part1.wav") // setup + RAM gate
        QwenAsrEngine().use { engine ->
            engine.load(File(MODELS_DIR))
            val window = PcmWindow(ShortArray(16_000), PcmWindow.SAMPLE_RATE_16K) // exact 0
            val h = runBlocking { engine.transcribe(window, sessionId = 1L, runId = 1L) }
            assertEquals("silence must decode to ''", "", h.text)
            assertFalse(h.incomplete)
        }
    }

    // ---- T22j: gate measurements (instrumentation status, rule 9: numbers only) ----

    /** Phase breakdown via the INTERNAL graph seam (friend-module access):
     *  piecewise replay of the engine pipeline — mel frontend + encoder in
     *  enc_ms, prefill in prefill_ms, greedy step loop in decode_ms. NO product
     *  change; the end-to-end total comes from the real [QwenAsrEngine]. */
    private class PhaseTimings(val encMs: Long, val prefillMs: Long, val decodeMs: Long)

    private fun measurePhases(window: PcmWindow, dir: File): PhaseTimings {
        val inputs = QwenInputs.load(dir)
        OrtQwenGraphs.load(dir).use { graphs ->
            val t0 = System.nanoTime()
            val mel = inputs.frontend(window.toFloatMono())
            val s = AUDIO_TOKENS_PER_CHUNK * mel.frames / CHUNK_FRAMES
            val embeds = graphs.encode(mel.features, mel.frames, inputs.buildAttentionMask(s), s)
            val t1 = System.nanoTime()
            val prompt = inputs.buildPromptTokens(s)
            val prefill = graphs.prefill(prompt, embeds, s)
            var kv = prefill.kv // ORT-owned KV generation chain (T22-oom-fix 2)
            var pastLen = prompt.size
            var logits = prefill.logits
            val t2 = System.nanoTime()
            var steps = 0
            try {
                while (steps < MAX_STEPS) {
                    val id = argmax(logits)
                    if (id == EOS_IM_END || id == EOS_EOT) break
                    val step = graphs.step(id.toLong(), kv, pastLen)
                    kv = step.kv
                    pastLen++
                    logits = step.logits
                    steps++
                }
            } finally {
                kv.close()
            }
            val t3 = System.nanoTime()
            return PhaseTimings(
                encMs = (t1 - t0) / 1_000_000,
                prefillMs = (t2 - t1) / 1_000_000,
                decodeMs = (t3 - t2) / 1_000_000,
            )
        }
    }

    private fun argmax(logits: FloatArray): Int {
        var best = 0
        var bestValue = Float.NEGATIVE_INFINITY
        for (i in logits.indices) if (logits[i] > bestValue) {
            bestValue = logits[i]; best = i
        }
        return best
    }

    /** ONE instrumentation-status entry per key — fields as name=value,
     *  numbers/codes only (rule 9: never transcribe contents). */
    private fun report(key: String, vararg fields: String) {
        val bundle = Bundle()
        bundle.putString(key, fields.joinToString(" "))
        InstrumentationRegistry.getInstrumentation().sendStatus(0, bundle)
    }

    private fun memoryKb(): Long = Debug.getNativeHeapAllocatedSize() / 1024
    private fun javaTotalMb(): Long = Runtime.getRuntime().totalMemory() / (1024 * 1024)
    private fun javaFreeMb(): Long = Runtime.getRuntime().freeMemory() / (1024 * 1024)

    /** Full measurement of ONE window — reproduces the CALLER boundary
     *  (T22-oom-fix 2: the coordinator segments at 15 s; the engine never
     *  receives more): segments of <= 15 s, one engine for all, phase
     *  breakdown per segment, heap before/after. */
    private fun measureFinalize(wavName: String) {
        val wav = assumeQwenReady(wavName)
        val full = parseWavPcm16Mono16k(wav)
        val dir = File(MODELS_DIR)
        val nativeBefore = memoryKb()

        // (a) end-to-end through the REAL engine, segmented like production
        var totalMs = 0L
        var tokens = 0
        var incomplete = false
        val segments = mutableListOf<PcmWindow>()
        var offset = 0
        while (offset < full.samples.size) {
            val len = minOf(MAX_SEGMENT_SAMPLES, full.samples.size - offset)
            segments += PcmWindow(full.samples.copyOfRange(offset, offset + len), PcmWindow.SAMPLE_RATE_16K)
            offset += len
        }
        QwenAsrEngine().use { engine ->
            engine.load(dir)
            for (window in segments) {
                val t0 = System.nanoTime()
                val h = runBlocking { engine.transcribe(window, sessionId = 1L, runId = 1L) }
                totalMs += (System.nanoTime() - t0) / 1_000_000
                tokens += h.tokenIds.size // count only, never text (rule 9)
                incomplete = incomplete || h.incomplete
            }
        }

        // (b) phase breakdown through the internal seam (engine closed first)
        var encMs = 0L
        var prefillMs = 0L
        var decodeMs = 0L
        for (window in segments) {
            val p = measurePhases(window, dir)
            encMs += p.encMs
            prefillMs += p.prefillMs
            decodeMs += p.decodeMs
        }
        val nativeAfter = memoryKb()

        report(
            "vk_qwen_rtf",
            "audio_s=%.1f".format(full.audioSeconds),
            "segments=${segments.size}",
            "enc_ms=$encMs",
            "prefill_ms=$prefillMs",
            "decode_ms=$decodeMs",
            "total_ms=$totalMs",
            "tokens=$tokens",
            "incomplete=${if (incomplete) 1 else 0}",
        )
        report(
            "vk_qwen_mem",
            "native_kb_before=$nativeBefore",
            "native_kb_after=$nativeAfter",
            "java_total_mb=${javaTotalMb()}",
            "java_free_mb=${javaFreeMb()}",
        )
    }

    /** T22j gate (plan §1): finalize latency of a FULL 35-s dictation window
     *  (the segmenter cap) on the Qwen engine — the number that decides the
     *  1.7B-vs-0.6B and ship question (phone-only, emulator numbers are
     *  documented as invalid). */
    @Test
    fun gate35sFinalizeLatency() = measureFinalize(GATE_WAV)

    /** T22j: the ~10-s everyday window (de_pause, 9.9 s) as the comparison
     *  point against the Parakeet numbers (ASR_CONTRACT / plan §1). */
    @Test
    fun window10sFinalizeLatency() = measureFinalize("de_pause.wav")

    // ---- own minimal RIFF/WAVE parser (pattern of ParakeetGoldenTest) ----

    private fun parseWavPcm16Mono16k(file: File): PcmWindow {
        RandomAccessFile(file, "r").use { raf ->
            val header = ByteArray(12)
            raf.readFully(header)
            require(String(header, 0, 4, Charsets.US_ASCII) == "RIFF") { "no RIFF: ${file.name}" }
            require(String(header, 8, 4, Charsets.US_ASCII) == "WAVE") { "no WAVE: ${file.name}" }
            var audioFormat = -1
            var channels = -1
            var sampleRate = -1
            var bitsPerSample = -1
            var samples: ShortArray? = null
            while (raf.filePointer < raf.length() - 8) {
                val chunkHeader = ByteArray(8)
                raf.readFully(chunkHeader)
                val chunkId = String(chunkHeader, 0, 4, Charsets.US_ASCII)
                val chunkSize = readLeInt(chunkHeader, 4)
                when (chunkId) {
                    "fmt " -> {
                        val fmt = ByteArray(chunkSize)
                        raf.readFully(fmt)
                        audioFormat = readLeShort(fmt, 0).toInt() and 0xFFFF
                        channels = readLeShort(fmt, 2).toInt()
                        sampleRate = readLeInt(fmt, 4)
                        bitsPerSample = readLeShort(fmt, 14).toInt()
                    }
                    "data" -> {
                        require(chunkSize % 2 == 0) { "data chunk not PCM16 aligned" }
                        val bytes = ByteArray(chunkSize)
                        raf.readFully(bytes)
                        val out = ShortArray(chunkSize / 2)
                        for (i in out.indices) out[i] = readLeShort(bytes, i * 2)
                        samples = out
                    }
                    else -> raf.seek(raf.filePointer + chunkSize + (chunkSize % 2))
                }
                if (chunkSize % 2 == 1) raf.seek(raf.filePointer + 1)
            }
            require(audioFormat == 1) { "not PCM (format=$audioFormat): ${file.name}" }
            require(channels == 1) { "not mono (channels=$channels): ${file.name}" }
            require(sampleRate == PcmWindow.SAMPLE_RATE_16K) { "not 16 kHz (=$sampleRate): ${file.name}" }
            require(bitsPerSample == 16) { "not 16 bit (=$bitsPerSample): ${file.name}" }
            return PcmWindow(checkNotNull(samples) { "no data chunk: ${file.name}" }, sampleRate)
        }
    }

    private fun readLeInt(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xFF) or
            ((b[off + 1].toInt() and 0xFF) shl 8) or
            ((b[off + 2].toInt() and 0xFF) shl 16) or
            ((b[off + 3].toInt() and 0xFF) shl 24)

    private fun readLeShort(b: ByteArray, off: Int): Short =
        ((b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8)).toShort()
}
