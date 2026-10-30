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
 * Instrumented Golden-Test (Stufe 2b-2): beweist, dass die ASR-Engine auf dem
 * Gerät die EXAKTEN Token-IDs aus docs/ASR_CONTRACT.md §4.1 liefert.
 *
 * Vergleichsreihenfolge (§4.1): erst Token-IDs (Decode-Fehler), dann Text
 * (Detok-Fehler). `de_smoke` enthält einen dokumentierten echten Modellfehler
 * (`Dikti-Funktion` statt `Diktierfunktion`) — der Golden-Test hält den
 * Modell-Output fest, nicht den Wunschtext.
 *
 * Voraussetzung (Emulator-Setup): Modelle unter /data/local/tmp/models,
 * WAVs unter /data/local/tmp/testdata. Fehlt etwas, wird der Test per
 * Assume SKIIPPED (nicht rot).
 */
@RunWith(AndroidJUnit4::class)
class ParakeetGoldenTest {

    companion object {
        private const val MODELS_DIR = "/data/local/tmp/models"
        private const val TESTDATA_DIR = "/data/local/tmp/testdata"

        /** ASR_CONTRACT.md §4.1 Golden-Token-IDs. */
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
        assumeTrue("Golden-Modelle fehlen unter $MODELS_DIR — Test wird übersprungen (Emulator-Setup)", modelsOk)
        assumeTrue("Test-WAV fehlt: $path — Test wird übersprungen (Emulator-Setup)", f.isFile)
        return f
    }

    /**
     * Eigener minimaler RIFF/WAVE-Parser (kein Fremdimport): PCM16 mono 16 kHz.
     * Liest nur die Chunks "fmt " und "data"; unbekannte Chunks werden übersprungen.
     */
    private fun parseWavPcm16Mono16k(file: File): PcmWindow {
        RandomAccessFile(file, "r").use { raf ->
            val header = ByteArray(12)
            raf.readFully(header)
            require(String(header, 0, 4, Charsets.US_ASCII) == "RIFF") { "Kein RIFF: ${file.name}" }
            require(String(header, 8, 4, Charsets.US_ASCII) == "WAVE") { "Kein WAVE: ${file.name}" }
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
                        require(chunkSize % 2 == 0) { "data-Chunk keine PCM16-Ausrichtung" }
                        val bytes = ByteArray(chunkSize)
                        raf.readFully(bytes)
                        val out = ShortArray(nSamples)
                        for (i in 0 until nSamples) {
                            out[i] = readLeShort(bytes, i * 2)
                        }
                        samples = out
                    }
                    else -> raf.seek(raf.filePointer + chunkSize + (chunkSize % 2)) // Padding
                }
                if (chunkSize % 2 == 1) raf.seek(raf.filePointer + 1) // odd-size Padding
            }
            require(audioFormat == 1) { "Kein PCM (format=$audioFormat): ${file.name}" }
            require(channels == 1) { "Nicht mono (channels=$channels): ${file.name}" }
            require(sampleRate == PcmWindow.SAMPLE_RATE_16K) { "Nicht 16 kHz (=$sampleRate): ${file.name}" }
            require(bitsPerSample == 16) { "Nicht 16 bit (=$bitsPerSample): ${file.name}" }
            return PcmWindow(checkNotNull(samples) { "Kein data-Chunk: ${file.name}" }, sampleRate)
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
                "$wavName: Token-IDs weichen vom Golden ab (ASR_CONTRACT §4.1)",
                expectedIds.toList(),
                hypothesis.tokenIds.toList(),
            )
            assertEquals("$wavName: Text weicht vom Golden ab", expectedText, hypothesis.text)
            assertFalse("$wavName: Dekodierung unvollständig", hypothesis.incomplete)
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
