package de.voicekeyboard.asr.litert

import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.Tensor
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * V8: Featurizer-Wrapper für `parakeet_tdt_nemo_featurizer_int8_dynamic_litert.tflite`.
 *
 * Manifest (vom Orchestrator aus dem offiziellen parakeet-Manifest gemessen):
 *  [0] raw_wav                    [1,121600] float32 (7,6 s @ 16 kHz, FESTE Form)
 *  [1] sample_mask                [1,121600] float32 (1.0 echt, 0.0 Polster)
 *  [2] frame_mask                 [1,760,1]  float32 (1.0 für Frames mit echtem Audio)
 *  [3] feature_mean               [1,1,128]  float32
 *  [4] feature_inv_std            [1,1,128]  float32
 *  [5] pre_encode_feature_cache  [1,9,128]  float32 (Merkmale der vorigen Fenstergrenze)
 *  [6] previous_sample            [1]        float32 (letzter Sample des vorigen Fensters)
 *  OUT[0] input_features [1,769,128] float32 · OUT[1] log_mel [1,760,128] float32
 *
 * ENCODER-Zustände (für V9, hier nur dokumentiert): cache_last_channel
 * [1,24,190,1024] (~18,6 MB), cache_last_time [1,24,1024,4], cache_last_channel_len [1].
 *
 * Fensterregel: 121 600 Samples fest; kürzere Eingaben werden mit NULLPOLSTER
 * gefüllt, sample_mask/frame_mask markieren die echten Bereiche.
 *
 * V9-Gerätebefund (2026-10-29, tflite-runtime + Arm-Sample beckett.wav, Sinus,
 * Stille): Ausgang 1 log_mel ist TOT (bei Sprache/Sinus/Stille konstant
 * -16,636, Varianz 0,0) — Statistik daraus ist unbrauchbar, er wird NIRGENDWO
 * verwendet/assertet. Ausgang 0 input_features [1,769,128] ist der LEBENDE
 * Feature-Strom (Varianz 3,20 Sprache / 31,31 Sinus / 0,0 Stille) und der
 * Eingang für den Encoder (V10). Statistik-Modus: NEUTRAL (mean 0/inv_std 1)
 * mit folgender BEGRÜNDUNG: die Dataset-Statistiken fehlen (Arm liefert keine
 * öffentlichen mean/inv_std-Werte); fromMel wäre aus dem toten log_mel
 * abzuleiten und ist damit wertlos. V10 liefert die echten Werte nach.
 */
class LiteRtFeaturizer(file: File, numThreads: Int = 2) {

    companion object {
        const val WINDOW_SAMPLES = 121_600   // 7,6 s @ 16 kHz (feste Tensorform)
        const val FRAME_HOP = 160            // 10 ms @ 16 kHz
        const val FRAME_COUNT = 760          // 121600 / 160
        const val FEATURE_DIM = 128
        const val CACHE_FRAMES = 9            // pre_encode_feature_cache
        const val INPUT_COUNT = 7
        const val OUTPUT_COUNT = 2
    }

    /** Ergebnis eines Featurizer-Laufs: beide Ausgaben + Formen + Frame-Maske. */
    data class Result(
        /** LEBENDER Feature-Strom [769*128] (V9: der Eingang für den Encoder). */
        val inputFeatures: FloatArray,
        /** TOT: im Arm-Artefakt konstant -16,636 (Gerätemessung 2026-10-29) —
         *  verworfen, nur für Form-Diagnose behalten. */
        val logMel: FloatArray,
        val inputFeaturesShape: IntArray, // [1,769,128]
        val logMelShape: IntArray,        // [1,760,128]
        val frameMask: BooleanArray,      // true = Frame enthält echtes Audio
        val statsMode: String,            // V9: immer "neutral" (s. KDoc)
    )

    private val interpreter: Interpreter
    private val closed = java.util.concurrent.atomic.AtomicBoolean(false)

    init {
        check(file.isFile) { "Featurizer fehlt: ${file.name}" }
        val bytes = file.readBytes()
        val opts = Interpreter.Options().apply { setNumThreads(numThreads) }
        interpreter = Interpreter(
            ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder())
                .apply { put(bytes) }.apply { flip() },
            opts,
        )
        // Form-Vertrag prüfen (Abweichung = AsrNotReadyException mit Grundtext)
        val inputs = interpreter.getInputTensorCount()
        if (inputs != INPUT_COUNT) {
            interpreter.close()
            throw LiteRtAsrEngine.AsrNotReadyException(
                "Featurizer erwartet $INPUT_COUNT Eingänge, gefunden: $inputs",
            )
        }
        for (i in 0 until INPUT_COUNT) {
            val t = interpreter.getInputTensor(i)
            if (t.dataType() != DataType.FLOAT32) {
                interpreter.close()
                throw LiteRtAsrEngine.AsrNotReadyException(
                    "Eingang $i erwartet float32, gefunden: ${t.dataType()}",
                )
            }
        }
    }

    /** Namen der Eingänge zur Laufzeit (Diagnose, keine Inhalte — Regel 9). */
    fun inputNames(): List<String> =
        (0 until INPUT_COUNT).map { i -> inputName(interpreter.getInputTensor(i)) }

    private fun inputName(t: Tensor): String =
        try {
            val m = Tensor::class.java.getMethod("name")
            m.invoke(t) as String
        } catch (e: Exception) {
            "input_${t.hashCode() % 1000}"
        }

    /**
     * Führt die Featurizer-Inferenz aus. Alle 7 Eingänge werden aus der einen
     * Eingabe ([pcm] echt bis [pcmLen], Rest Nullpolster) gebaut:
     *  - raw_wav: pcm[0..pcmLen), Rest 0
     *  - sample_mask: 1.0 für i<pcmLen, sonst 0.0
     *  - frame_mask: 1.0 für floor(i/160) < ceil(pcmLen/160), sonst 0.0
     *  - feature_mean/inv_std: NEUTRAL (V9; log_mel ist tot, Dataset-Werte
     *    fehlen — V10 liefert echte Statistiken nach).
     *  - pre_encode_feature_cache: prevCache oder Nullen
     *  - previous_sample: prevSample ?: 0
     */
    fun featurize(
        pcm: FloatArray,
        pcmLen: Int,
        prevCache: FloatArray? = null,
        prevSample: Float? = null,
    ): Result {
        check(!closed.get()) { "Featurizer geschlossen" }
        require(pcmLen in 0..WINDOW_SAMPLES) { "pcmLen $pcmLen außerhalb 0..$WINDOW_SAMPLES" }
        val rawWav = FloatArray(WINDOW_SAMPLES)
        val sampleMask = FloatArray(WINDOW_SAMPLES)
        System.arraycopy(pcm, 0, rawWav, 0, pcmLen)
        for (i in 0 until pcmLen) sampleMask[i] = 1f
        val realFrames = (pcmLen + FRAME_HOP - 1) / FRAME_HOP
        val frameMask = FloatArray(FRAME_COUNT)
        for (f in 0 until realFrames.coerceAtMost(FRAME_COUNT)) frameMask[f] = 1f

        val cache = FloatArray(CACHE_FRAMES * FEATURE_DIM)
        if (prevCache != null) {
            System.arraycopy(prevCache, 0, cache, 0, minOf(prevCache.size, cache.size))
        }
        val prevS = prevSample ?: 0f

        // V9: Statistik-Modus NEUTRAL (mean 0/inv_std 1) — BEGRÜNDUNG: Ausgang 1
        // log_mel ist im Arm-Artefakt KONSTANT -16,636 (gerätemessen, var=0),
        // fromMel wäre wertlos; Dataset-Statistiken fehlen (V10 liefert sie nach).
        // EIN Lauf (keine doppelte Inferenz mehr).
        val out = runOnce(rawWav, sampleMask, frameMask,
            FeaturizerStats.neutralMean(), FeaturizerStats.neutralInvStd(), cache, prevS)
        val maskBool = BooleanArray(FRAME_COUNT) { frameMask[it] > 0.5f }
        val mode = "neutral"
        return Result(
            inputFeatures = out.inputFeatures,
            logMel = out.logMel,
            inputFeaturesShape = out.inputFeaturesShape,
            logMelShape = out.logMelShape,
            frameMask = maskBool,
            statsMode = mode,
        )
    }

    /** Ein runForMultipleInputsOutputs-Lauf mit den 7 Eingangs-Buffern. */
    private fun runOnce(
        rawWav: FloatArray, sampleMask: FloatArray, frameMask: FloatArray,
        mean: FloatArray, invStd: FloatArray, cache: FloatArray, prevSample: Float,
    ): Result {
        fun buf(a: FloatArray, shape: IntArray): ByteBuffer {
            val bb = ByteBuffer.allocateDirect(a.size * 4).order(ByteOrder.nativeOrder())
            for (v in a) bb.putFloat(v)
            bb.flip()
            return bb
        }
        fun flat(shape: IntArray): Int = shape.fold(1) { acc, v -> acc * (if (v > 0) v else 1) }

        // Eingangs-Formen je Tensor abfragen und die Puffer entsprechend formen
        val inShapes = (0 until INPUT_COUNT).map { interpreter.getInputTensor(it).shape() }
        val inputs = arrayOf<Any>(
            buf(rawWav, inShapes[0]),
            buf(sampleMask, inShapes[1]),
            buf(frameMask, inShapes[2]),
            buf(mean, inShapes[3]),
            buf(invStd, inShapes[4]),
            buf(cache, inShapes[5]),
            buf(floatArrayOf(prevSample), inShapes[6]),
        )
        // Ausgabe-Puffer in deklarierten Formen (feste Formen lt. Manifest)
        val ifShape = intArrayOf(1, FRAME_COUNT + 1, FEATURE_DIM) // [1,769,128]
        val lmShape = intArrayOf(1, FRAME_COUNT, FEATURE_DIM)      // [1,760,128]
        val outIf = ByteBuffer.allocateDirect(flat(ifShape) * 4).order(ByteOrder.nativeOrder())
        val outLm = ByteBuffer.allocateDirect(flat(lmShape) * 4).order(ByteOrder.nativeOrder())
        val outputs = mapOf(0 to outIf, 1 to outLm)
        try {
            interpreter.runForMultipleInputsOutputs(inputs, outputs)
        } catch (e: Exception) {
            // V9: LiteRT-Originalmeldung SICHTBAR machen (vorher verschluckt),
            // aber regel-9-bereinigt: pro Token alles ab '/' auf den Dateinamen
            // kuerzen (kein Pfad, keine URL), max. 160 Zeichen. cause anhaengen.
            val scrubbed = e.message?.let { scrubForUser(it) } ?: "(keine Meldung)"
            val ex = LiteRtAsrEngine.AsrNotReadyException(
                "Featurizer-Inferenz fehlgeschlagen (${e.javaClass.simpleName}: $scrubbed)",
            )
            ex.initCause(e)
            throw ex
        }
        fun floats(bb: ByteBuffer, n: Int): FloatArray {
            val arr = FloatArray(n)
            bb.rewind()
            for (i in 0 until n) arr[i] = bb.float
            return arr
        }
        return Result(
            inputFeatures = floats(outIf, flat(ifShape)),
            logMel = floats(outLm, flat(lmShape)),
            inputFeaturesShape = ifShape,
            logMelShape = lmShape,
            frameMask = BooleanArray(0),
            statsMode = "run",
        )
    }

    fun close() {
        if (closed.compareAndSet(false, true)) interpreter.close()
    }

    /** V9/Regel 9: Meldung bereinigen — Token mit '/'/'\\' auf den Dateinamen
     *  kuerzen (kein Pfad, keine URL), max. 160 Zeichen. */
    private fun scrubForUser(msg: String): String {
        val sb = StringBuilder()
        for (tok in msg.split(Regex("[\\s]+"))) {
            if (sb.isNotEmpty()) sb.append(' ')
            val cut = if (tok.contains('/') || tok.contains('\\')) {
                tok.substringAfterLast('/').substringAfterLast('\\')
            } else tok
            sb.append(if (cut.isNotEmpty()) cut else tok)
        }
        return sb.toString().take(160)
    }
}
