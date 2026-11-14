package de.voicekeyboard.correct

import java.io.File

/**
 * T24c (PLAN_V0147 §17.2, Gleis 3 B3): the U2 spelling-correction engine —
 * greedy KV decode loop over the three merged graphs
 * (BART_CORRECT_CONTRACT §3/§4): encoder -> decoder_init (cross-KV built,
 * loop-invariant) -> decoder_step loop until EOS, cap 64.
 *
 * Testability: the GRAPHS and the TOKENIZER are INJECTED seams (fake graphs
 * in BartCorrectEngineTest — Mock/Mini-Session per the Go order); the
 * production ORT loader lives in [BartOrtGraphs] (JVM-untestable, like
 * OrtQwenGraphs), the real tokenizer (BPE en / SP-Unigram de) is the B3b
 * seam [BartTokenizer]. The engine NEVER logs text (rule 9).
 *
 * Load: lazy over the ModelStore tree — the merged set keeps the
 * subdirectory layout ("shared/", "tokenizer/") and ORT resolves the
 * ExternalData references relative to the graph files (B2 store names).
 */
class BartCorrectEngine internal constructor(
    private val graphsLoader: (File) -> BartGraphs,
    private val tokenizer: BartTokenizer,
    private val vocabMetaFile: (File) -> File = { File(it, "tokenizer/vocab_meta.json") },
) : AutoCloseable {

    /**
     * Graph seam (contract §3): batch 1, no padding; ids int64, floats
     * float32. self/cross KV layouts are the engine's opaque FloatArrays —
     * the production graphs move them to/from ORT tensors.
     */
    interface BartGraphs : AutoCloseable {
        /** input_ids [S] -> last_hidden [S*768]. */
        fun encode(inputIds: LongArray): FloatArray

        /** first decode step: cross-KV built, P=1. */
        fun decodeInit(startId: Long, encoderHidden: FloatArray): InitOut

        /** one decode step with the KV cache; positions start at 1 (P=1). */
        fun decodeStep(
            tokenId: Long,
            position: Long,
            selfK: FloatArray,
            selfV: FloatArray,
            crossK: FloatArray,
            crossV: FloatArray,
        ): StepOut
    }

    data class InitOut(
        val logits: FloatArray,
        val selfK: FloatArray,
        val selfV: FloatArray,
        val crossK: FloatArray,
        val crossV: FloatArray,
    )

    data class StepOut(
        val logits: FloatArray,
        val selfK: FloatArray,
        val selfV: FloatArray,
        val crossK: FloatArray,
        val crossV: FloatArray,
    )

    /** vocab_meta.json (contract §5): ids + the de prefix. */
    data class VocabMeta(val startId: Long, val eosId: Long, val padId: Long, val prefix: String?) {
        companion object {
            /** Key names of the EXPORTED vocab_meta.json (T24a runner,
             *  BART_CORRECT_CONTRACT §5) — decoder_start feeds the engine's
             *  decodeInit, eos the loop stop, input_prefix the de "correct: ". */
            fun load(file: File): VocabMeta {
                check(file.isFile) { "vocab_meta missing" } // rule 9: no paths
                val root = MiniJson.parseObject(file.readText())
                val prefix = (root["input_prefix"] as? String)?.takeIf { it.isNotEmpty() }
                return VocabMeta(
                    startId = (root["decoder_start_token_id"] as? Long) ?: 0L,
                    eosId = (root["eos_token_id"] as? Long) ?: 0L,
                    padId = (root["pad_token_id"] as? Long) ?: 0L,
                    prefix = prefix,
                )
            }
        }
    }

    /** One correction run: raw model output + the §6 guard plan. */
    data class CorrectionResult(
        val output: String,
        val complete: Boolean,
        val generatedTokens: Int,
    )

    private var graphs: BartGraphs? = null
    private var meta: VocabMeta? = null

    /** Lazy load (B2 store tree; ORT sessions + vocab meta). */
    @Synchronized
    fun load(modelDir: File) {
        check(graphs == null) { "already loaded" }
        meta = VocabMeta.load(vocabMetaFile(modelDir))
        graphs = graphsLoader(modelDir)
    }

    val isLoaded: Boolean get() = graphs != null

    /**
     * Greedy correction run (contract §4): prefix (de "correct: ") +
     * tokenize -> encode -> decoder_init -> loop argmax/EOS/step, cap
     * [MAX_NEW_TOKENS] -> detokenize. The SUBSTITUTION GUARD runs SEPARATELY
     * over (typed, output) — [substitutionPlan] — the caller decides what a
     * rejection means (rule 9: nothing is logged here).
     */
    @Synchronized
    fun correct(text: String): CorrectionResult {
        val g = graphs ?: error("not loaded")
        val m = meta ?: error("not loaded")
        val input = (m.prefix ?: "") + text
        val ids = tokenizer.encode(input)
        val encoderHidden = g.encode(ids)
        var init = g.decodeInit(m.startId, encoderHidden)
        var selfK = init.selfK
        var selfV = init.selfV
        var logits = init.logits
        val crossK = init.crossK
        val crossV = init.crossV
        val generated = ArrayList<Long>(MAX_NEW_TOKENS)
        var complete = false
        for (pos in 0 until MAX_NEW_TOKENS) {
            val tok = argmax(logits)
            if (tok == m.eosId) {
                complete = true
                break
            }
            generated.add(tok)
            if (pos == MAX_NEW_TOKENS - 1) break // cap reached -> incomplete
            val step = g.decodeStep(tok, pos + 1L, selfK, selfV, crossK, crossV)
            selfK = step.selfK
            selfV = step.selfV
            logits = step.logits
        }
        return CorrectionResult(
            output = tokenizer.decode(generated.toLongArray()),
            complete = complete,
            generatedTokens = generated.size,
        )
    }

    override fun close() {
        graphs?.close()
        graphs = null
    }

    companion object {
        /** Contract §4: greedy cap, never reached by the goldens. */
        const val MAX_NEW_TOKENS = 64

        internal fun argmax(logits: FloatArray): Long {
            var best = 0
            for (i in 1 until logits.size) if (logits[i] > logits[best]) best = i
            return best.toLong()
        }
    }
}

/**
 * Tokenizer seam (contract §5): encode(prefix+text) -> ids (WITHOUT special
 * padding, batch 1), decode(ids) -> text (special tokens skipped). Production
 * implementations: ByteLevel-BPE (en) / SentencePiece-Unigram (de) over
 * tokenizer.json — Teil B3b; tests inject fakes.
 */
interface BartTokenizer {
    fun encode(text: String): LongArray
    fun decode(ids: LongArray): String
}

/**
 * §6/§17.1 SUBSTITUTION GUARD — PURE: the model output may ONLY replace
 * words — never insert/delete/reorder. Same word count, at most
 * max(1, ceil(30 %)) replaced, each replacement preserves the word shape
 * (length ±1, non-word characters preserved). identical -> NoAction (the
 * silence equivalent §7). Never logs (rule 9).
 */
sealed class GuardDecision {
    /** Output == input: no action, no undo entry, no row update. */
    data object NoAction : GuardDecision()

    /** Any rule violated: the model output is discarded entirely. */
    data object Reject : GuardDecision()

    /** The exact word-level replacements to commit (position-preserving). */
    data class Substitutions(val items: List<WordSubstitution>) : GuardDecision()
}

data class WordSubstitution(val index: Int, val from: String, val to: String)

fun substitutionPlan(typed: String, corrected: String): GuardDecision {
    if (typed == corrected) return GuardDecision.NoAction
    val inWords = typed.split(' ').filter { it.isNotEmpty() }
    val outWords = corrected.split(' ').filter { it.isNotEmpty() }
    if (outWords.size != inWords.size) return GuardDecision.Reject
    val diffs = inWords.mapIndexedNotNull { i, w ->
        if (w != outWords[i]) WordSubstitution(i, w, outWords[i]) else null
    }
    val maxReplaced = maxOf(1, Math.ceil(0.3 * inWords.size).toInt())
    if (diffs.size > maxReplaced) return GuardDecision.Reject
    for (d in diffs) {
        if (!sameWordShape(d.from, d.to)) return GuardDecision.Reject
    }
    return GuardDecision.Substitutions(diffs)
}

/** §6: length ±1 and the non-word characters (hyphen, apostrophe …) stay. */
internal fun sameWordShape(a: String, b: String): Boolean {
    if (Math.abs(a.length - b.length) > 1) return false
    return a.filter { !it.isLetterOrDigit() } == b.filter { !it.isLetterOrDigit() }
}
