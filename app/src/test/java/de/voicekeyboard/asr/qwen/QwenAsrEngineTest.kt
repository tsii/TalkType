package de.voicekeyboard.asr.qwen

import de.voicekeyboard.audio.PcmWindow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import kotlin.math.sin

/**
 * T22f2 (PLAN_V0146 §6): decode-core tests with FAKE graphs (no onnxruntime,
 * no network). The scripted token IDs are REAL Qwen ids (host-side encoded
 * with the pinned tokenizer.json — the APP never encodes, only the fakes
 * deliver ids): the normal path uses the ids of the contract §5 golden
 * `Das ist der erste Teil des Doktats.` — the engine must detokenize them
 * to EXACTLY that text via the pinned tokenizer fixture.
 *
 * Guards per contract §5 (LLM hallucination countermeasures):
 * silence -> '' WITHOUT graph runs; repeat pattern -> abort incomplete;
 * cap 128 -> incomplete; EOS -> early + complete; punctuation-only -> '';
 * close() releases the graphs.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35]) // project pattern: SDK 36 needs Java 21, the test JVM is 17
class QwenAsrEngineTest {

    @get:Rule
    val tmp = TemporaryFolder()

    /** Real ids of `Das ist der erste Teil des Doktats.` (contract §5 golden). */
    private val part1Ids = intArrayOf(32617, 5999, 2694, 61217, 47355, 939, 66850, 83, 1862, 13)
    private val part1Text = "Das ist der erste Teil des Doktats."

    /** 8 distinct ids, repeatable pattern for the repeat guard. */
    private val pattern = intArrayOf(18175, 422, 31580, 367, 2714, 2712, 406, 10928)

    /** 128 distinct, repeat-free ids (host-encoded long text) — hits the cap. */
    private val capIds = intArrayOf(
        18175, 422, 31580, 367, 2714, 2712, 406, 10928, 52287, 9970, 8535, 28883, 74890, 2660, 2030,
        2694, 18372, 7070, 8151, 4865, 35323, 2478, 23952, 395, 2746, 350, 559, 2628, 2746, 27258,
        261, 465, 13177, 89, 38651, 13177, 89, 304, 2918, 4443, 746, 11687, 81625, 22010, 9919, 713,
        724, 295, 38132, 13368, 26692, 1728, 393, 43226, 2030, 14248, 1579, 72269, 547, 1014, 64,
        1070, 13368, 264, 24612, 60098, 2030, 6616, 9236, 89, 7066, 68312, 16030, 95524, 50697, 704,
        1863, 14320, 77, 13368, 71620, 1241, 30781, 12070, 343, 39307, 13017, 956, 68, 709, 304,
        19461, 1079, 51292, 46668, 8688, 268, 13177, 31793, 17790, 14649, 35951, 1863, 30753, 42189,
        6772, 9443, 4370, 12412, 61550, 4069, 6703, 32936, 27258, 261, 465, 40870, 2746, 9660, 8400,
        709, 8688, 4081, 768, 93985, 2030, 12788, 336,
    )

    private lateinit var dir: File

    @Before
    fun setUp() {
        // model dir like the app package: tokenizer.json + mel files (pinned fixtures)
        dir = tmp.newFolder()
        for (name in listOf("tokenizer.json", "mel_filters.npy", "mel_config.json")) {
            javaClass.getResourceAsStream("/qwen/$name")!!.use { input ->
                File(dir, name).outputStream().use { input.copyTo(it) }
            }
        }
    }

    /** Deterministic, clearly audible 1-s window (rms >> silence threshold). */
    private fun speechWindow(): PcmWindow {
        val samples = ShortArray(16_000) { i ->
            (9000 * sin(2 * Math.PI * 220.0 * i / 16_000.0)).toInt().toShort()
        }
        return PcmWindow(samples, 16_000)
    }

    /** Scripted graphs: prefill emits script[0], every step the next id (or [exhaustedId]).
     *  T22-oom-fix 2: the KV is a GENERATION CHAIN — each step returns a NEW
     *  handle; the engine passes the PREVIOUS step's handle into the next call
     *  and the old generation is freed inside step (deterministic, ORT-owned). */
    private class FakeKv(val size: Int, var closed: Boolean = false) : QwenGraphs.KvCache {
        override fun close() {
            closed = true
        }
    }

    private class FakeQwenGraphs(
        private val script: IntArray,
        private val exhaustedId: Int = EOS_IM_END,
        private val kvSize: Int = 1,
    ) : QwenGraphs {
        var encodeCalls = 0
        var prefillCalls = 0
        var stepCalls = 0
        var closed = false
        val pastLens = mutableListOf<Int>()
        val pastKvSizes = mutableListOf<Int>()
        val pastKvInstances = mutableListOf<QwenGraphs.KvCache>()
        val returnedHandles = mutableListOf<FakeKv>()
        val closedHandles = mutableListOf<FakeKv>()
        var lastS = 0
        private var index = 0

        private fun logitsFor(id: Int): FloatArray = FloatArray(151_936).also { it[id] = 1f }

        override fun encode(inputFeatures: FloatArray, frames: Int, attnMask: FloatArray, s: Int): FloatArray {
            encodeCalls++
            lastS = s
            return FloatArray(s * 1024) { 0.5f }
        }

        override fun prefill(promptIds: LongArray, audioEmbeds: FloatArray, s: Int): PrefillOut {
            prefillCalls++
            return PrefillOut(logitsFor(script.first()), FakeKv(kvSize))
        }

        override fun step(id: Long, kv: QwenGraphs.KvCache, pastLen: Int): StepOut {
            stepCalls++
            pastLens += pastLen
            val fake = kv as FakeKv
            pastKvSizes += fake.size
            pastKvInstances += kv
            fake.closed = true // the OLD generation is freed inside step (chain)
            closedHandles += fake
            index++
            val next = if (index < script.size) script[index] else exhaustedId
            val nextKv = FakeKv(kvSize)
            returnedHandles += nextKv
            return StepOut(logitsFor(next), nextKv)
        }

        override fun close() {
            closed = true
        }

        companion object {
            private const val EOS_IM_END = 151645
        }
    }

    private fun engine(fake: FakeQwenGraphs): QwenAsrEngine =
        QwenAsrEngine(graphsLoader = { fake }).also { it.load(dir) }

    private fun graphCalls(fake: FakeQwenGraphs): Int =
        fake.encodeCalls + fake.prefillCalls + fake.stepCalls

    // ---- silence guard: no graph run at all ----

    @Test
    fun `silence window returns empty text without any graph call`() {
        val fake = FakeQwenGraphs(part1Ids)
        val e = engine(fake)
        val h = runBlocking { e.transcribe(PcmWindow(ShortArray(16_000), 16_000), sessionId = 1L, runId = 1L) }
        assertEquals("", h.text)
        assertTrue(h.tokenIds.isEmpty())
        assertFalse(h.incomplete)
        assertEquals("silence must never reach the graphs", 0, graphCalls(fake))
    }

    // ---- normal path: scripted real ids -> contract golden text ----

    @Test
    fun `normal path decodes scripted real ids to the golden text`() {
        val fake = FakeQwenGraphs(part1Ids)
        val e = engine(fake)
        val h = runBlocking { e.transcribe(speechWindow(), sessionId = 7L, runId = 3L) }
        assertEquals(part1Text, h.text)
        assertArrayEquals(part1Ids, h.tokenIds)
        assertFalse(h.incomplete)
        assertEquals(7L, h.sessionId)
        assertEquals(3L, h.runId)
        assertEquals(16_000, h.audioSamples)
        // graph flow: 1 encode, 1 prefill, one step per emitted token
        assertEquals(1, fake.encodeCalls)
        assertEquals(1, fake.prefillCalls)
        assertEquals(part1Ids.size, fake.stepCalls)
        // KV contract (§4.2/§4.3): prefill length L = 18+S = 31 (1 s -> S=13),
        // every step sees the grown past (31, 32, ...)
        assertEquals((31..40).toList(), fake.pastLens)
    }

    // ---- EOS: early, complete ----

    @Test
    fun `eos ends the decode early and complete`() {
        val fake = FakeQwenGraphs(part1Ids.copyOf(3))
        val e = engine(fake)
        val h = runBlocking { e.transcribe(speechWindow(), sessionId = 1L, runId = 1L) }
        assertEquals("Das ist der", h.text)
        assertFalse(h.incomplete)
        assertEquals(3, fake.stepCalls)
    }

    // ---- cap: 128 tokens without EOS -> incomplete ----

    @Test
    fun `cap of 128 tokens marks the hypothesis incomplete`() {
        // exhaustedId NON-EOS: the script never ends — only the cap can stop the loop
        val fake = FakeQwenGraphs(capIds, exhaustedId = 659) // " ."
        val e = engine(fake)
        val h = runBlocking { e.transcribe(speechWindow(), sessionId = 1L, runId = 1L) }
        assertTrue("capped decode must be incomplete", h.incomplete)
        assertEquals(128, h.tokenIds.size)
        assertEquals(128, fake.stepCalls)
        assertArrayEquals(capIds, h.tokenIds)
    }

    // ---- repeat guard: 8-token pattern twice within the last 16 ----

    @Test
    fun `repeat pattern aborts the decode with incomplete`() {
        val script = IntArray(3 + 16) { i -> if (i < 3) part1Ids[i] else pattern[(i - 3) % 8] }
        val fake = FakeQwenGraphs(script)
        val e = engine(fake)
        val h = runBlocking { e.transcribe(speechWindow(), sessionId = 1L, runId = 1L) }
        assertTrue("repeat hallucination must abort as incomplete", h.incomplete)
        assertEquals("3 lead tokens + 16 pattern tokens emitted", 19, h.tokenIds.size)
        assertEquals("the 19th token aborts BEFORE another step", 18, fake.stepCalls)
    }

    // ---- post-guard: punctuation/whitespace-only decode -> "" ----

    @Test
    fun `punctuation-only decode is dropped but the graphs did run`() {
        val fake = FakeQwenGraphs(intArrayOf(659)) // real id of " ."
        val e = engine(fake)
        val h = runBlocking { e.transcribe(speechWindow(), sessionId = 1L, runId = 1L) }
        assertEquals("no text from a punctuation-only decode", "", h.text)
        assertTrue(h.tokenIds.isEmpty())
        assertFalse(h.incomplete)
        assertEquals("the graphs ran — only the OUTPUT is dropped", 1, fake.encodeCalls)
        assertEquals(1, fake.prefillCalls)
    }

    // ---- release ----

    @Test
    fun `close releases the graphs`() {
        val fake = FakeQwenGraphs(part1Ids)
        val e = engine(fake)
        e.close()
        assertTrue("graphs must be closed exactly once", fake.closed)
    }

    // ---- T22-fix/T22-oom-fix: KV generation chain + segment cap ----

    /** REAL export truth: the KV carries 16 repeated heads ([28,1,16,P,128]).
     *  T22-oom-fix 2: the engine walks a GENERATION CHAIN — step k's returned
     *  handle is passed to step k+1, the OLD generation is freed inside step,
     *  the LAST one is closed after the loop (deterministic, ORT-owned). */
    @Test
    fun `kv generation chain - 16-head size, old gen freed, last gen closed`() {
        val promptLen = 31 // 18 + S for the 1-s window (S=13)
        val kvLen = 28 * 16 * promptLen * 128 // [28,1,16,31,128] runtime truth
        val fake = FakeQwenGraphs(part1Ids.copyOf(3), kvSize = kvLen)
        val e = engine(fake)
        val h = runBlocking { e.transcribe(speechWindow(), sessionId = 1L, runId = 1L) }
        assertEquals("Das ist der", h.text)
        assertEquals(3, fake.stepCalls)
        assertEquals("the KV size flows through the chain (no 8-head assumption)", List(3) { kvLen }, fake.pastKvSizes)
        // chain: the handle received by step k IS the one RETURNED by step k-1
        assertTrue(
            "step 1 receives the prefill handle",
            fake.pastKvInstances[0] === fake.closedHandles[0],
        )
        for (k in 1 until fake.pastKvInstances.size) {
            assertTrue(
                "step $k receives the handle returned by step ${k - 1}",
                fake.pastKvInstances[k] === fake.returnedHandles[k - 1],
            )
        }
        // old generations freed inside step (exactly one per step), each once
        assertEquals(fake.stepCalls, fake.closedHandles.size)
        assertEquals("no generation closed twice", fake.closedHandles.size, fake.closedHandles.toSet().size)
        assertTrue("the last generation is closed by the engine", fake.returnedHandles.last().closed)
    }

    /** T22-oom-fix 2: the 15-s segment cap — a longer window is defensively
     *  truncated to 15 s and marked incomplete; the graphs never see more
     *  audio (S = 13*15 = 195). */
    @Test
    fun `segment cap 15s truncates longer windows and marks incomplete`() {
        val fake = FakeQwenGraphs(part1Ids)
        val e = engine(fake)
        val samples = ShortArray(20 * 16_000) { i ->
            (9000 * kotlin.math.sin(2 * Math.PI * 220.0 * i / 16_000.0)).toInt().toShort()
        }
        val h = runBlocking { e.transcribe(PcmWindow(samples, 16_000), sessionId = 1L, runId = 1L) }
        assertTrue("truncated segment must be incomplete", h.incomplete)
        assertEquals("15 s -> S = 13*15 = 195 audio tokens", 195, fake.lastS)
    }
}
