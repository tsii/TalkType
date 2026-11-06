package de.voicekeyboard.asr.qwen

import android.util.Log
import de.voicekeyboard.asr.AsrEngine
import de.voicekeyboard.asr.Hypothesis
import de.voicekeyboard.audio.PcmWindow
import java.io.File
import kotlin.math.sqrt

/**
 * T22f2 (PLAN_V0146 §6; QWEN_ASR_CONTRACT §4/§5/§6): dictation engine for the
 * Qwen3-ASR-0.6B set — chunked OFFLINE per window, same [AsrEngine] contract
 * as ParakeetAsrEngine.
 *
 * Pipeline per window: silence guard -> [QwenInputs.frontend] (log-mel) ->
 * block mask -> [QwenGraphs.encode] -> prompt (contract §6, language German
 * fixed) -> [QwenGraphs.prefill] -> greedy [QwenGraphs.step] loop ->
 * [QwenTokenizer.detokenize].
 *
 * T22-oom-fix 2 (3rd emulator run): the KV cache lives in ORT-OWNED native
 * memory (generation chain, exactly two generations alive, ~156 MB at the
 * 15 s segment cap) — nothing in the Java heap, no direct ByteBuffer of ours
 * (Dalvik counts those against the same growth limit).
 *
 * SEGMENT CAP [MAX_SEGMENT_AUDIO_S] = 15 s: a transcribe call NEVER gets
 * more (longer holds are segmented by the caller — DictationCoordinator's
 * segmenter runs at 15 s for the Qwen set, VoiceKeyboardService wiring).
 * A window beyond the cap is defensively truncated and reported as
 * incomplete (never silent data loss).
 *
 * Guards (contract §5: the LLM decoder HALLUCINATES on silence — mandatory):
 *  - SILENCE: window RMS below [SILENCE_RMS] -> empty result, NO graph run;
 *    longer silences are already handled by the app-side silence detection
 *    (audio_error:silence), this is the per-window fast path.
 *  - REPEAT: an 8-token pattern completely twice within the last 16 tokens
 *    -> abort, incomplete=true.
 *  - CAP: max [MAX_NEW_TOKENS] without EOS -> incomplete=true (contract §5:
 *    never reached by the goldens; silence hallucinations stay bounded).
 *  - POST: a decode of only whitespace/punctuation -> "" (no text output).
 *
 * KV cache (T22-oom-fix): the decode loop holds ONE off-heap pool handle
 * ([QwenGraphs.KvCache], direct ByteBuffers, ping-pong A/B) — no KV data in
 * the Java heap, no per-step allocation; the pool dies with the handle.
 *
 * [Hypothesis.frameTimesMs] stays empty (LLM decoder has no frame timing —
 * plan §0 allows it). Logging: durations/counts only, never contents (rule 9).
 */
class QwenAsrEngine internal constructor(
    private val graphsLoader: (File) -> QwenGraphs,
) : AsrEngine, AutoCloseable {

    public constructor() : this({ OrtQwenGraphs.load(it) })

    private var graphs: QwenGraphs? = null
    private var inputs: QwenInputs? = null
    private var tokenizer: QwenTokenizer? = null

    override fun load(modelDir: File) {
        check(graphs == null) { "AsrEngine.load() already called" }
        for (name in listOf("tokenizer.json", "mel_filters.npy", "mel_config.json")) {
            check(File(modelDir, name).isFile) { "model file missing: $name" } // rule 9: name only
        }
        inputs = QwenInputs.load(modelDir)
        tokenizer = QwenTokenizer.load(modelDir)
        graphs = graphsLoader(modelDir)
    }

    override suspend fun transcribe(window: PcmWindow, sessionId: Long, runId: Long): Hypothesis {
        val graphs = checkNotNull(graphs) { "ASR engine not loaded — call load() before transcribe()" }
        val inputs = checkNotNull(inputs)
        val tokenizer = checkNotNull(tokenizer)
        val total0 = System.nanoTime()

        val rawFull = window.toFloatMono() // PCM16 -> [-1,1] float32
        var nSamples = rawFull.size
        var truncated = false
        var raw = rawFull
        if (nSamples > MAX_SEGMENT_SAMPLES) {
            // T22-oom-fix 2: defensive cap — the CALLER segments (15 s); a
            // longer window here is a wiring bug and is reported, not hidden.
            raw = rawFull.copyOf(MAX_SEGMENT_SAMPLES)
            nSamples = MAX_SEGMENT_SAMPLES
            truncated = true
            Log.i(
                TAG,
                "segment_cap=15s samples_dropped=${rawFull.size - MAX_SEGMENT_SAMPLES}",
            )
        }
        if (nSamples == 0) {
            return Hypothesis(IntArray(0), "", IntArray(0), sessionId, runId, 0, false)
        }

        // ---- silence guard (contract §5): exact-zero/near-zero never reaches the graphs ----
        var energy = 0.0
        for (v in raw) energy += (v * v).toDouble()
        if (sqrt(energy / nSamples) < SILENCE_RMS) {
            return Hypothesis(IntArray(0), "", IntArray(0), sessionId, runId, nSamples, false)
        }

        // ---- 1. mel frontend + encoder block mask (contract §4.0/§4.1) ----
        val mel = inputs.frontend(raw)
        val s = AUDIO_TOKENS_PER_CHUNK * mel.frames / CHUNK_FRAMES
        val mask = inputs.buildAttentionMask(s)
        val audioEmbeds = graphs.encode(mel.features, mel.frames, mask, s)
        for (v in audioEmbeds) {
            if (!v.isFinite()) throw IllegalStateException("encoder output contains NaN/Inf (value $v)")
        }

        // ---- 2. prompt (§6) + prefill (§4.2) ----
        val prompt = inputs.buildPromptTokens(s)
        val prefill = graphs.prefill(prompt, audioEmbeds, s)
        var kv = prefill.kv // ORT-owned KV generation (T22-oom-fix 2)
        var pastLen = prompt.size
        var logits = prefill.logits

        // ---- 3. greedy decode loop (§4.3) with repeat/cap guards ----
        val emitted = IntArray(MAX_NEW_TOKENS)
        var n = 0
        var steps = 0
        var incomplete = false
        var id = argmax(logits)
        try {
        while (id != EOS_IM_END && id != EOS_EOT && steps < MAX_NEW_TOKENS) {
            emitted[n++] = id.toInt()
            if (repeatsLast16(emitted, n)) {
                incomplete = true
                break
            }
            val step = graphs.step(id.toLong(), kv, pastLen)
            kv = step.kv // generation chain: the new ORT-owned generation (P -> P+1)
            pastLen++
            logits = step.logits
            steps++
            id = argmax(logits)
        }
        if (!incomplete && id != EOS_IM_END && id != EOS_EOT) incomplete = true // capped
        if (truncated) incomplete = true // segment cap hit: end not decoded
        } finally {
            kv.close() // last KV generation freed deterministically (ORT-owned)
        }

        // ---- 4. detokenize + post-guard ----
        val text = tokenizer.detokenize((0 until n).map { emitted[it].toLong() })
        val totalNanos = System.nanoTime() - total0
        Log.i(
            TAG,
            "audio=${"%.1f".format(window.audioSeconds * 1000.0)}ms samples=$nSamples frames=${mel.frames} " +
                "s=$s steps=$steps tokens=$n total=${totalNanos / 1_000_000}ms incomplete=$incomplete",
        )
        if (text.none { it.isLetterOrDigit() }) {
            // punctuation/whitespace-only decode = hallucination artifact -> no output
            return Hypothesis(IntArray(0), "", IntArray(0), sessionId, runId, nSamples, incomplete)
        }
        // tokenIds: filtered — no special/added tokens (they never leave the engine)
        val ids = (0 until n).map { emitted[it] }.filter { it < SPECIAL_ID_FLOOR }.toIntArray()
        return Hypothesis(ids, text, IntArray(0), sessionId, runId, nSamples, incomplete)
    }

    override fun close() {
        graphs?.close()
        graphs = null
        inputs = null
        tokenizer = null
    }

    private fun argmax(logits: FloatArray): Int {
        var best = 0
        var bestValue = Float.NEGATIVE_INFINITY
        for (i in logits.indices) {
            if (logits[i] > bestValue) {
                bestValue = logits[i]
                best = i
            }
        }
        return best
    }

    /** Last 16 tokens contain an 8-token pattern COMPLETELY twice (aligned: 8+8). */
    private fun repeatsLast16(emitted: IntArray, n: Int): Boolean {
        if (n < REPEAT_WINDOW) return false
        val offset = n - REPEAT_WINDOW
        for (i in 0 until REPEAT_HALF) {
            if (emitted[offset + i] != emitted[offset + REPEAT_HALF + i]) return false
        }
        return true
    }

    private companion object {
        private const val TAG = "VK-ASR"
        private const val AUDIO_TOKENS_PER_CHUNK = 13
        private const val CHUNK_FRAMES = 100
        private const val MAX_NEW_TOKENS = 128 // contract §5 cap (never reached by the goldens)

        /** T22-oom-fix 2: segment cap — the KV pool stays <= ~156 MB native
         *  (P_max = 18 + 13*15 + 128 ≈ 341; two generations). */
        private const val MAX_SEGMENT_AUDIO_S = 15
        private const val MAX_SEGMENT_SAMPLES = MAX_SEGMENT_AUDIO_S * 16_000
        private const val EOS_EOT = 151643 // <｜endoftext｜> (unicode chars replaced in comment)
        private const val EOS_IM_END = 151645 // <|im_end|>
        private const val SPECIAL_ID_FLOOR = 151643 // added/special ids >= floor are filtered
        private const val SILENCE_RMS = 1e-4f // float32-normalized samples (contract §5 exact-zero case)
        private const val REPEAT_WINDOW = 16
        private const val REPEAT_HALF = 8
    }
}
