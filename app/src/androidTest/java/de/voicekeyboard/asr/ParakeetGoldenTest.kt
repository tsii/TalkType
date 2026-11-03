package de.voicekeyboard.asr

import androidx.test.ext.junit.runners.AndroidJUnit4
import de.voicekeyboard.audio.PcmWindow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.RandomAccessFile

/**
 * Instrumented golden test (stage 2b-2): proves that the ASR engine on the
 * device delivers the EXACT token IDs from docs/ASR_CONTRACT.md §4.1.
 *
 * Comparison order (§4.1): first token IDs (decode error), then text
 * (detok error). `de_smoke` contains a documented real model error
 * (`Dikti-Funktion` instead of `Diktierfunktion`) — the golden test pins the
 * model output, not the wishful text.
 *
 * Prerequisite (emulator setup): models under /data/local/tmp/models,
 * WAVs under /data/local/tmp/testdata. If anything is missing, the test is
 * SKIPPED via Assume (not red).
 */
@RunWith(AndroidJUnit4::class)
class ParakeetGoldenTest {

    companion object {
        private const val MODELS_DIR = "/data/local/tmp/models"
        private const val TESTDATA_DIR = "/data/local/tmp/testdata"

        /** ASR_CONTRACT.md §4.1 golden token IDs. */
        private val GOLDEN_SMOKE = intArrayOf(
            1960, 7877, 7036, 4189, 1076, 317, 2893, 1607, 7868, 7877, 601, 615,
            360, 6085, 7918, 7995, 1297, 1240, 1593, 1044, 357, 567, 4949, 5027,
            1240, 5102, 7883,
        )
        private const val GOLDEN_SMOKE_TEXT =
            "So, jetzt bin ich gespannt, ob die Dikti-Funktion auf dem Telefon funktioniert."

        private val GOLDEN_PAUSE = intArrayOf(
            3211, 929, 623, 468, 974, 1769, 305, 594, 360, 2995, 1780, 7883, 2413,
            3189, 4379, 3000, 1163, 758, 6338, 623, 6099, 582, 1769, 305, 7883,
        )
        private const val GOLDEN_PAUSE_TEXT =
            "Das ist der erste Teil des Diktats. Und nach einer Pause kommt der zweite Teil."
    }

    private fun assumeReadable(path: String): File {
        val f = File(path)
        val modelsOk = listOf(
            "$MODELS_DIR/nemo128.onnx",
            "$MODELS_DIR/encoder-model.int8.onnx",
            "$MODELS_DIR/decoder_joint-model.int8.onnx",
            "$MODELS_DIR/vocab.txt",
        ).all { File(it).isFile }
        assumeTrue("golden models missing under $MODELS_DIR — test is skipped (emulator setup)", modelsOk)
        assumeTrue("test WAV missing: $path — test is skipped (emulator setup)", f.isFile)
        return f
    }

    /**
     * Own minimal RIFF/WAVE parser (no third-party import): PCM16 mono 16 kHz.
     * Reads only the chunks "fmt " and "data"; unknown chunks are skipped.
     */
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
                        val nSamples = chunkSize / 2
                        require(chunkSize % 2 == 0) { "data chunk not PCM16 aligned" }
                        val bytes = ByteArray(chunkSize)
                        raf.readFully(bytes)
                        val out = ShortArray(nSamples)
                        for (i in 0 until nSamples) {
                            out[i] = readLeShort(bytes, i * 2)
                        }
                        samples = out
                    }
                    else -> raf.seek(raf.filePointer + chunkSize + (chunkSize % 2)) // padding
                }
                if (chunkSize % 2 == 1) raf.seek(raf.filePointer + 1) // odd-size padding
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

    private fun assertGolden(wavName: String, expectedIds: IntArray, expectedText: String) {
        val wav = assumeReadable("$TESTDATA_DIR/$wavName")
        val modelDir = File(MODELS_DIR)
        ParakeetAsrEngine().use { engine ->
            engine.load(modelDir)
            val window = parseWavPcm16Mono16k(wav)
            val hypothesis = runBlocking { engine.transcribe(window, sessionId = 1, runId = 1) }
            assertEquals(
                "$wavName: token IDs deviate from the golden (ASR_CONTRACT §4.1)",
                expectedIds.toList(),
                hypothesis.tokenIds.toList(),
            )
            assertEquals("$wavName: text deviates from the golden", expectedText, hypothesis.text)
            assertFalse("$wavName: decoding incomplete", hypothesis.incomplete)
        }
    }

    @Test
    fun deSmoke_matchesGoldenTokenIds() {
        assertGolden("de_smoke.wav", GOLDEN_SMOKE, GOLDEN_SMOKE_TEXT)
    }

    @Test
    fun dePause_matchesGoldenTokenIds() {
        assertGolden("de_pause.wav", GOLDEN_PAUSE, GOLDEN_PAUSE_TEXT)
    }
}
