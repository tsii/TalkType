package de.voicekeyboard.asr.qwen

import de.voicekeyboard.model.ModelManifest
import de.voicekeyboard.model.Sha256
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * T22f1 (PLAN_V0146 §6; QWEN_ASR_CONTRACT §4.0/§4.1/§6): golden tests for the
 * pure-Kotlin Qwen input chain [QwenInputs] — mel frontend, encoder
 * attention mask, prompt token ids. NO onnxruntime, NO network: the fixtures
 * under `qwen/` are generated HOST-side by tools/qwen_ref/gen_qwen_input_fixtures.py
 * (numpy mirror of run_qwen_ref.py).
 *
 * Tolerances (pinned in fixtures_mel.json meta):
 *  - small cases (silence, tone): COMPLETE expected arrays, |diff| <= 1e-6;
 *  - long cases (de_smoke 2.8 s, de_pause 3 s): per-frame L2 norms within
 *    1e-4 relative; the float32 byte-level sha256 parity with the numpy
 *    reference is REPORTED via stdout (T22f2 wants bit-exact inputs — the
 *    test binds the robust L2 criterion, see XML system-out for the parity).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35]) // project pattern: SDK 36 needs Java 21, the test JVM is 17
class QwenInputsTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var inputs: QwenInputs
    private lateinit var melFixtures: JSONObject
    private lateinit var maskFixtures: JSONObject
    private lateinit var promptFixtures: JSONObject

    @Before
    fun setUp() {
        // model dir layout like the app package: mel_filters.npy + mel_config.json
        val dir = tmp.newFolder()
        for (name in listOf("mel_filters.npy", "mel_config.json")) {
            javaClass.getResourceAsStream("/qwen/$name")!!.use { input ->
                File(dir, name).outputStream().use { input.copyTo(it) }
            }
        }
        // the copies ARE the pinned model package files (ModelManifest.QWEN, T22b'):
        assertEquals(
            ModelManifest.forVariant(ModelVariant2.QWEN).first { it.storeName == "mel_filters.npy" }.sha256,
            Sha256.ofFile(File(dir, "mel_filters.npy")),
        )
        inputs = QwenInputs.load(dir)
        melFixtures = resourceJson("fixtures_mel.json")
        maskFixtures = resourceJson("fixtures_mask.json")
        promptFixtures = resourceJson("fixtures_prompt.json")
    }

    // imports would be circular-ish; small local alias for the enum:
    private object ModelVariant2 {
        val QWEN = de.voicekeyboard.model.ModelVariant.QWEN3_ASR
    }

    private fun resourceJson(name: String): JSONObject = JSONObject(
        javaClass.getResourceAsStream("/qwen/$name")!!.readBytes().toString(Charsets.UTF_8),
    )

    private fun floatsOf(arr: JSONArray): FloatArray =
        FloatArray(arr.length()) { arr.getDouble(it).toFloat() }

    private fun longsOf(arr: JSONArray): LongArray =
        LongArray(arr.length()) { arr.getLong(it) }

    private fun sha256LeFloat32(features: FloatArray): String {
        val bb = ByteBuffer.allocate(features.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        for (f in features) bb.putFloat(f)
        return Sha256.ofBytes(bb.array())
    }

    private fun melCase(label: String): Triple<String, FloatArray, JSONObject> {
        val c = melFixtures.getJSONArray("cases").let { arr ->
            (0 until arr.length()).map { arr.getJSONObject(it) }.first { it.getString("label") == label }
        }
        return Triple(label, floatsOf(c.getJSONArray("input")), c)
    }

    // ---- frontend: small references COMPLETE (tolerance 1e-6) ----

    @Test
    fun `frontend matches the complete small references within 1e-6`() {
        for (label in listOf("silence_1s", "tone_440hz_0_5s")) {
            val (lab, raw, case) = melCase(label)
            val out = inputs.frontend(raw)
            assertEquals("$lab frames", case.getInt("n_frames"), out.frames)
            assertEquals("$lab bands", 128, out.bands)
            assertEquals("$lab layout [1,128,T]", out.bands * out.frames, out.features.size)
            val expected: JSONArray = case.getJSONArray("expected")
            assertEquals("$lab rows", 128, expected.length())
            for (band in 0 until expected.length()) {
                val row: JSONArray = expected.getJSONArray(band)
                assertEquals("$lab row $band length", out.frames, row.length())
                for (t in 0 until row.length()) {
                    val ref = row.getDouble(t).toFloat()
                    val diff = Math.abs(out.features[band * out.frames + t] - ref)
                    assertTrue("$lab band=$band frame=$t diff=$diff", diff <= 1e-6)
                }
            }
        }
    }

    // ---- frontend: long signals per-frame L2 (+ sha parity report) ----

    @Test
    fun `frontend long signals match per-frame L2 - sha parity reported`() {
        for (label in listOf("smoke_2_8s", "pause_3_0s")) {
            val (lab, raw, case) = melCase(label)
            val frames = case.getInt("n_frames")
            val refL2: JSONArray = case.getJSONArray("frame_l2")
            val out = inputs.frontend(raw)
            assertEquals("$lab frames", frames, out.frames)
            for (t in 0 until frames) {
                var sum = 0.0
                for (band in 0 until out.bands) {
                    val v = out.features[band * out.frames + t].toDouble()
                    sum += v * v
                }
                val norm = Math.sqrt(sum)
                val ref = refL2.getDouble(t)
                val rel = Math.abs(norm - ref) / Math.max(ref, 1e-9)
                assertTrue("$lab frame=$t norm=$norm ref=$ref rel=$rel", rel <= 1e-4)
            }
            // T22f2 wants bit-exact inputs: parity with the numpy reference is
            // REPORTED (captured in the XML system-out) — binding is the L2 check.
            val parity = sha256LeFloat32(out.features) == case.getString("float32_sha256")
            println("T22F1-SHA-PARITY case=$lab match=$parity")
        }
    }

    @Test
    fun `frontend pads short input to a full second minimum`() {
        // 100 samples < 0.5 s => min_length 8000 => full second => 100 frames
        val out = inputs.frontend(FloatArray(100) { it.toFloat() * 1e-4f })
        assertEquals(100, out.frames)
        // 0.7 s => full second => 100 frames (contract: padding on FULL seconds)
        assertEquals(100, inputs.frontend(FloatArray(11200)).frames)
        // 1.5 s => 2 s => 200 frames
        assertEquals(200, inputs.frontend(FloatArray(24000)).frames)
    }

    // ---- attention mask: 104-token blocks incl. rest window ----

    @Test
    fun `attention mask follows the 104-token block rule with rest window`() {
        val cases: JSONArray = maskFixtures.getJSONArray("cases")
        for (i in 0 until cases.length()) {
            val c = cases.getJSONObject(i)
            val s = c.getInt("s")
            val expectedBlocks: JSONArray = c.getJSONArray("blocks")
            val mask = inputs.buildAttentionMask(s)
            assertEquals("mask size [1,1,S,S]", s * s, mask.size)
            // every value is exactly 0 or -1e9
            assertTrue(mask.all { it == 0f || it == -1e9f })
            // reconstruct the blocks row by row: every row has EXACTLY ONE 0-run
            // (its own block) — adjacent blocks appear as new runs at the boundary.
            val blocks = mutableListOf<List<Int>>()
            for (row in 0 until s) {
                var start = -1
                var runEnd = -1
                for (col in 0 until s) {
                    val allowed = mask[row * s + col] == 0f
                    if (allowed && start < 0) start = col
                    if (allowed) runEnd = col + 1
                }
                assertTrue("S=$s row=$row must be in a block", start >= 0)
                val run = listOf(start, runEnd)
                if (blocks.isEmpty() || blocks.last() != run) {
                    assertTrue(
                        "S=$s row=$row blocks must be adjacent",
                        blocks.isEmpty() || blocks.last()[1] == run[0],
                    )
                    blocks += run
                }
            }
            assertEquals("S=$s block count", expectedBlocks.length(), blocks.size)
            for (b in 0 until expectedBlocks.length()) {
                val e = expectedBlocks.getJSONArray(b)
                assertEquals("S=$s block $b", listOf(e.getInt(0), e.getInt(1)), blocks[b])
            }
            // block structure is symmetric and consistent over ALL rows:
            for (row in intArrayOf(0, s / 2, s - 1)) {
                for (col in 0 until s) {
                    val sameBlock = blocks.any { row >= it[0] && row < it[1] && col >= it[0] && col < it[1] }
                    assertEquals("S=$s row=$row col=$col", if (sameBlock) 0f else -1e9f, mask[row * s + col])
                }
            }
        }
    }

    // ---- prompt tokens: prefix + S x audio_pad + suffix (contract §6) ----

    @Test
    fun `prompt tokens match prefix audio_pad x S and suffix exactly`() {
        val prefix = longsOf(promptFixtures.getJSONArray("prefix"))
        val suffix = longsOf(promptFixtures.getJSONArray("suffix"))
        val padId = promptFixtures.getLong("audio_pad_id")
        assertEquals(151676L, padId)
        assertEquals(18, prefix.size + suffix.size) // 18 + S == L (contract §4.2)
        val cases: JSONArray = promptFixtures.getJSONArray("cases")
        for (i in 0 until cases.length()) {
            val c = cases.getJSONObject(i)
            val s = c.getInt("s")
            val ids = inputs.buildPromptTokens(s)
            assertEquals("S=$s length", c.getInt("length"), ids.size)
            assertArrayEquals("S=$s ids", longsOf(c.getJSONArray("ids")), ids)
            // structure: prefix + S pads + suffix, verbatim
            assertEquals(prefix.toList() + List(s) { padId } + suffix.toList(), ids.toList())
            assertEquals("S=$s pads", s, ids.count { it == padId })
        }
    }
}
