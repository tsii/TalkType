package de.voicekeyboard.correct

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * T24c (PLAN_V0147 §17.2, Gleis 3 B3): BartCorrectEngine — greedy KV decode
 * loop over the three graphs (encoder -> decoder_init -> decoder_step), the
 * vocab_meta contract (EOS/PAD/Start, prefix "correct: " for de), the
 * §6/§17.1 substitution guard (pure), lazy load with injected graphs
 * (Mock/Mini-Session per the Go order; the real tokenizer is the B3b seam —
 * here a fake tokenizer). Fixture words are test constants (rule 9).
 */
class BartCorrectEngineTest {

    // ------------------------------------------------------------- fakes

    /** Records every call; returns deterministic logits over a tiny vocab. */
    private class FakeGraphs(var scriptedTokens: List<Long>, val eos: Long, val start: Long) : BartCorrectEngine.BartGraphs {
        val calls = ArrayList<String>()
        var encodeIds: LongArray? = null
        var crossKSeenTwice = false

        override fun encode(inputIds: LongArray): FloatArray {
            calls.add("encode:${inputIds.size}")
            encodeIds = inputIds.copyOf()
            return FloatArray(inputIds.size * 768)
        }

        override fun decodeInit(startId: Long, encoderHidden: FloatArray): BartCorrectEngine.InitOut {
            calls.add("init")
            return BartCorrectEngine.InitOut(
                logits = logitsFor(scriptedTokens[0]),
                selfK = FloatArray(12 * 64),
                selfV = FloatArray(12 * 64),
                crossK = FloatArray(1),
                crossV = FloatArray(1),
            )
        }

        private var step = 0
        override fun decodeStep(
            tokenId: Long,
            position: Long,
            selfK: FloatArray,
            selfV: FloatArray,
            crossK: FloatArray,
            crossV: FloatArray,
        ): BartCorrectEngine.StepOut {
            step++
            calls.add("step:tok=$tokenId pos=$position")
            val next = scriptedTokens.getOrNull(step) ?: eos
            return BartCorrectEngine.StepOut(
                logits = logitsFor(next),
                selfK = FloatArray((step + 1) * 12 * 64),
                selfV = FloatArray((step + 1) * 12 * 64),
                crossK = crossK,
                crossV = crossV,
            )
        }

        override fun close() = Unit

        /** argmax picks the scripted token id directly. */
        private fun logitsFor(token: Long): FloatArray =
            LongArray(V).let { FloatArray(V) { i -> if (i.toLong() == token) 10f else 0f } }

        companion object { const val V = 100 }
    }

    /** Fake tokenizer: id = word hash mod V, decode joins ids back. */
    private class FakeTokenizer(val map: Map<String, Long>, val idToWord: Map<Long, String>) : BartTokenizer {
        override fun encode(text: String): LongArray =
            text.split(' ').filter { it.isNotEmpty() }.map { map[it] ?: 0L }.toLongArray()

        override fun decode(ids: LongArray): String =
            ids.map { idToWord[it] ?: "" }.filter { it.isNotEmpty() }.joinToString(" ")
    }

    private fun vocabMetaFile(start: Long, eos: Long, pad: Long, prefix: String?): File {
        val f = File.createTempFile("vocab_meta", ".json")
        f.writeText(
            if (prefix == null) {
                """{"decoder_start_token_id":$start,"eos_token_id":$eos,"pad_token_id":$pad}"""
            } else {
                """{"decoder_start_token_id":$start,"eos_token_id":$eos,"pad_token_id":$pad,"input_prefix":"$prefix"}"""
            },
        )
        f.deleteOnExit()
        return f
    }

    private fun engine(
        meta: File,
        script: List<Long>,
        eos: Long,
        start: Long,
        tk: BartTokenizer,
    ): Pair<BartCorrectEngine, FakeGraphs> {
        val graphs = FakeGraphs(script, eos, start)
        val engine = BartCorrectEngine(
            graphsLoader = { graphs },
            tokenizer = tk,
            vocabMetaFile = { meta },
        )
        engine.load(File("/fake/dir"))
        return engine to graphs
    }

    // -------------------------------------------------------- greedy loop

    @Test fun `greedy loop stops at EOS and yields the scripted tokens`() {
        // vocab: morgem->5 morgen->6 Welt->7; script: morgen, Welt, EOS
        val tk = FakeTokenizer(
            mapOf("correct:" to 1L, "morgem" to 5L, "morgen" to 6L, "Welt" to 7L),
            mapOf(6L to "morgen", 7L to "Welt"),
        )
        val (engine, graphs) = engine(
            meta = vocabMetaFile(start = 2, eos = 2, pad = 1, prefix = null),
            script = listOf(6L, 7L, 2L),
            eos = 2, start = 2, tk = tk,
        )
        val result = engine.correct("morgem Welt")
        assertEquals("morgen Welt", result.output)
        assertTrue("complete run (EOS reached)", result.complete)
        assertEquals("one step per generated token (then EOS argmax)", 2, graphs.calls.count { it.startsWith("step:") })
    }

    @Test fun `position ids advance by one per step (contract §3)`() {
        val tk = FakeTokenizer(emptyMap(), emptyMap())
        val (engine, graphs) = engine(
            meta = vocabMetaFile(start = 2, eos = 2, pad = 1, prefix = null),
            script = listOf(6L, 6L, 2L),
            eos = 2, start = 2, tk = tk,
        )
        engine.correct("x")
        assertEquals(
            "pos starts at 1 (P=1 from init) and advances",
            listOf("step:tok=6 pos=1", "step:tok=6 pos=2"),
            graphs.calls.filter { it.startsWith("step:") },
        )
    }

    @Test fun `cap of 64 tokens without EOS is incomplete - never unbounded`() {
        val tk = FakeTokenizer(emptyMap(), mapOf(6L to "w"))
        val script = List(100) { 6L } // EOS never fires
        val (engine, graphs) = engine(
            meta = vocabMetaFile(start = 2, eos = 2, pad = 1, prefix = null),
            script = script, eos = 2, start = 2, tk = tk,
        )
        val result = engine.correct("x")
        assertEquals("64 generated tokens max (63 step calls + 1 init)", 63, graphs.calls.count { it.startsWith("step:") })
        assertEquals("generated token count at the cap", 64, result.generatedTokens)
        assertTrue("incomplete flag set", !result.complete)
    }

    // ------------------------------------------------------- vocab meta

    @Test fun `vocab meta parses start eos pad and the de prefix`() {
        val de = BartCorrectEngine.VocabMeta.load(vocabMetaFile(0, 1, 0, "correct: "))
        assertEquals(0L, de.startId)
        assertEquals(1L, de.eosId)
        assertEquals(0L, de.padId)
        assertEquals("correct: ", de.prefix)
        val en = BartCorrectEngine.VocabMeta.load(vocabMetaFile(2, 2, 1, null))
        assertEquals(2L, en.startId)
        assertNull("en has NO prefix", en.prefix)
    }

    @Test fun `de prefix is prepended before tokenization - en stays bare`() {
        class RecTok : BartTokenizer {
            var last: String? = null
            override fun encode(text: String): LongArray {
                last = text
                return longArrayOf(1)
            }

            override fun decode(ids: LongArray) = "x"
        }
        val deTk = RecTok()
        val (deEngine, _) = engine(vocabMetaFile(0, 1, 0, "correct: "), listOf(1L), 1, 0, deTk)
        deEngine.correct("morgem Welt")
        assertEquals("de: prefix applied before tokenization", "correct: morgem Welt", deTk.last)

        val enTk = RecTok()
        val (enEngine, _) = engine(vocabMetaFile(2, 2, 1, null), listOf(2L), 2, 2, enTk)
        enEngine.correct("morgem World")
        assertEquals("en: NO prefix", "morgem World", enTk.last)
    }

    // ----------------------------------------------------------- the guard

    @Test fun `identical output is NoAction - the silence equivalent`() {
        assertEquals(
            GuardDecision.NoAction,
            substitutionPlan("morgem Welt", "morgem Welt"),
        )
    }

    @Test fun `word count drift is rejected`() {
        assertEquals(
            GuardDecision.Reject,
            substitutionPlan("morgem Welt", "morgen Welt extra"),
        )
        assertEquals(
            GuardDecision.Reject,
            substitutionPlan("morgem Welt", "morgen"),
        )
    }

    @Test fun `pure substitutions commit as a plan with positions`() {
        val plan = substitutionPlan("morgem Welt", "morgen Welt")
        assertTrue(plan is GuardDecision.Substitutions)
        plan as GuardDecision.Substitutions
        assertEquals(listOf(WordSubstitution(0, "morgem", "morgen")), plan.items)
    }

    @Test fun `more than 30 percent replaced words are rejected`() {
        // 10 words, 4 differ -> 4 > max(1, ceil(3)) -> reject
        val typed = listOf("a1", "b", "c", "d", "e", "f", "g", "h", "i", "j").joinToString(" ")
        val fixed = listOf("a2", "b2", "c2", "d2", "e", "f", "g", "h", "i", "j").joinToString(" ")
        assertEquals(GuardDecision.Reject, substitutionPlan(typed, fixed))
        // 3 of 10 -> allowed (<= ceil(3))
        val fixed3 = listOf("a2", "b2", "c2", "d", "e", "f", "g", "h", "i", "j").joinToString(" ")
        assertTrue(substitutionPlan(typed, fixed3) is GuardDecision.Substitutions)
    }

    @Test fun `word shape is preserved - length plus minus one, same non-word chars`() {
        // length drift of 2 -> reject
        assertEquals(
            GuardDecision.Reject,
            substitutionPlan("morgem", "morgennn"),
        )
        // hyphen position preserved -> substitution ok
        assertTrue(
            substitutionPlan("Team-Meetng", "Team-Meeting") is GuardDecision.Substitutions,
        )
        // hyphen disappears -> reject
        assertEquals(
            GuardDecision.Reject,
            substitutionPlan("Team-Meetng", "TeamMeetng"),
        )
    }
}
