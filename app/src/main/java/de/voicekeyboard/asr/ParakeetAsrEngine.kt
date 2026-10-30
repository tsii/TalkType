package de.voicekeyboard.asr

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import android.util.Log
import de.voicekeyboard.audio.PcmWindow
import java.io.Closeable
import java.io.File
import java.nio.FloatBuffer
import java.nio.IntBuffer
import java.nio.LongBuffer
import kotlin.math.min

/**
 * Parakeet TDT 0.6B v3 (int8) über ONNX Runtime — Mel → Encoder → TDT-Greedy.
 *
 * Pipeline (docs/ASR_CONTRACT.md §2/§3):
 *  PCM16 → float32 [-1,1] → nemo128 (waveforms [1,N] float / waveforms_lens [1] int64)
 *  → features [1,128,T] → encoder (audio_signal [1,128,T] float / length [1] int64)
 *  → outputs [1,1024,T/8], frames = min(encoded_lengths[0], outputs.shape[2])
 *  → TdtGreedy mit JointStep-Adapter (frame-weise, encoder_outputs [1,1024,1]).
 *
 * Kein Softmax, kein Sampling, kein Resampling. Logging nur Dauern (Tag "VK-ASR"),
 * niemals Audiodaten oder Texte.
 */
class ParakeetAsrEngine : AsrEngine, Closeable {

    companion object {
        private const val TAG = "VK-ASR"
    }

    private var sessions: OnnxSessions? = null
    private var vocab: Vocabulary? = null

    override fun load(modelDir: File) {
        check(sessions == null) { "AsrEngine.load() wurde bereits aufgerufen" }
        val vocabFile = File(modelDir, "vocab.txt")
        check(vocabFile.isFile) { "vocab.txt fehlt im Modellordner" } // Regel 9: nie Pfade in Fehlermeldungen
        val vocab = Vocabulary.parse(vocabFile.readText())
        this.sessions = OnnxSessions.load(modelDir)
        this.vocab = vocab
    }

    override suspend fun transcribe(window: PcmWindow, sessionId: Long, runId: Long): Hypothesis {
        val sessions = checkNotNull(sessions) { "AsrEngine nicht geladen — load() vor transcribe() aufrufen" }
        val vocab = checkNotNull(vocab)
        val env = OrtEnvironment.getEnvironment()
        val total0 = System.nanoTime()

        val pcm = window.toFloatMono()
        val nSamples = window.samples.size
        if (nSamples == 0) {
            return Hypothesis(IntArray(0), "", IntArray(0), sessionId, runId, 0, false)
        }

        // ---- 1. Mel-Frontend: waveforms [1,N] float, waveforms_lens [1] int64 ----
        val mel0 = System.nanoTime()
        val features: FloatArray
        val melTime: Int
        OnnxTensor.createTensor(env, FloatBuffer.wrap(pcm), longArrayOf(1, nSamples.toLong())).use { waveforms ->
            OnnxTensor.createTensor(env, LongBuffer.wrap(longArrayOf(nSamples.toLong())), longArrayOf(1)).use { lens ->
                sessions.mel.run(
                    mapOf(
                        AsrContract.MEL_IN_WAVEFORMS to waveforms,
                        AsrContract.MEL_IN_WAVEFORMS_LENS to lens,
                    )
                ).use { result ->
                    val featTensor = tensor(result, AsrContract.MEL_OUT_FEATURES)
                    val shape = featTensor.info.shape
                    require(shape.size == 3 && shape[1] == AsrContract.MEL_BINS.toLong()) {
                        "Mel-Features haben unerwartete Form: ${shape.contentToString()}"
                    }
                    features = FloatArray(featTensor.floatBuffer.remaining())
                    featTensor.floatBuffer.get(features)
                    val lensTensor = tensor(result, AsrContract.MEL_OUT_FEATURES_LENS)
                    val fl = LongArray(1)
                    lensTensor.longBuffer.get(fl)
                    melTime = fl[0].toInt()
                }
            }
        }
        val melNanos = System.nanoTime() - mel0
        check(melTime > 0) { "Mel-Frontend lieferte features_lens=0 für $nSamples Samples" }

        // ---- 2. Encoder: audio_signal [1,128,T] float, length [1] int64 ----
        val enc0 = System.nanoTime()
        val encOut: FloatArray
        val encFrames: Int
        OnnxTensor.createTensor(
            env, FloatBuffer.wrap(features),
            longArrayOf(1, AsrContract.MEL_BINS.toLong(), melTime.toLong())
        ).use { audioSignal ->
            OnnxTensor.createTensor(env, LongBuffer.wrap(longArrayOf(melTime.toLong())), longArrayOf(1)).use { length ->
                sessions.encoder.run(
                    mapOf(
                        AsrContract.ENC_IN_AUDIO_SIGNAL to audioSignal,
                        AsrContract.ENC_IN_LENGTH to length,
                    )
                ).use { result ->
                    val outTensor = tensor(result, AsrContract.ENC_OUT_OUTPUTS)
                    val shape = outTensor.info.shape
                    require(shape.size == 3 && shape[1] == AsrContract.ENCODER_DIM.toLong()) {
                        "Encoder-Output hat unerwartete Form: ${shape.contentToString()}"
                    }
                    encOut = FloatArray(outTensor.floatBuffer.remaining())
                    outTensor.floatBuffer.get(encOut)
                    val lensTensor = tensor(result, AsrContract.ENC_OUT_ENCODED_LENGTHS)
                    val el = LongArray(1)
                    lensTensor.longBuffer.get(el)
                    // §3: frames = min(encoded_lengths[0], outputs.shape[2])
                    encFrames = min(el[0].toInt(), shape[2].toInt())
                }
            }
        }
        val encNanos = System.nanoTime() - enc0
        check(encFrames > 0) { "Encoder lieferte 0 Frames für $nSamples Samples" }

        // NaN-Guard VOR dem Decode (§7 Fehlerabbildung): ein NaN/Inf-Encoder-Output
        // darf nicht erst den kompletten Decoder durchlaufen (Argmax auf NaN → Index 0).
        for (v in encOut) {
            if (!v.isFinite()) {
                throw IllegalStateException("Encoder-Output enthält NaN/Inf (Wert $v)")
            }
        }

        // ---- 3. TDT-Greedy mit JointStep-Adapter ----
        val dec0 = System.nanoTime()
        val budget = TokenBudget.budgetTokens(window.audioSeconds)
        val joint = EncoderJointStep(env, sessions.decoder, encOut, encFrames)
        val result = TdtGreedy.decode(joint, encFrames, budget) { vocab.isSpecial(it) }
        val decNanos = System.nanoTime() - dec0
        val totalNanos = System.nanoTime() - total0

        val frameTimesMs = IntArray(result.frameTimes.size) { i ->
            result.frameTimes[i] * AsrContract.FRAME_MS
        }
        val text = vocab.detokenize(result.tokenIds)

        // Nur Dauern/Kennzahlen loggen — KEINE Audiodaten, KEINE Texte.
        Log.i(
            TAG,
            "audio=${"%.1f".format(window.audioSeconds * 1000.0)}ms samples=$nSamples frames=$encFrames " +
                "mel=${melNanos / 1_000_000}ms encoder=${encNanos / 1_000_000}ms " +
                "decode=${decNanos / 1_000_000}ms steps=${result.steps} tokens=${result.tokenIds.size} " +
                "total=${totalNanos / 1_000_000}ms incomplete=${result.incomplete} reason=${result.reason}"
        )

        return Hypothesis(
            tokenIds = result.tokenIds,
            text = text,
            frameTimesMs = frameTimesMs,
            sessionId = sessionId,
            runId = runId,
            audioSamples = nSamples,
            incomplete = result.incomplete,
        )
    }

    override fun close() {
        sessions?.close()
        sessions = null
        vocab = null
    }

    private fun tensor(result: ai.onnxruntime.OrtSession.Result, name: String): OnnxTensor {
        val value = result.get(name).orElse(null)
            ?: throw IllegalStateException("Output-Tensor '$name' fehlt im Ergebnis")
        return value as OnnxTensor
    }
}

/**
 * JointStep-Adapter: füttert pro Schritt decoder_joint mit
 *  encoder_outputs [1,1024,1] (Frame t), targets [1,1] int32,
 *  target_length [1] int32, input_states_1/2 [2,1,640] float
 * und liest outputs [1,1,1,8198] + output_states_1/2 zurück.
 * Achsenreihenfolge von outputs ist gemessen [B,T,U,8198] (§2) — bei T=U=1
 * ist der lineare Buffer genau [0,0,0,*], deshalb passt das flache Auslesen.
 */
private class EncoderJointStep(
    private val env: OrtEnvironment,
    private val decoder: ai.onnxruntime.OrtSession,
    private val encOut: FloatArray,
    private val encFrames: Int,
) : JointStep {

    private val frame = FloatArray(AsrContract.ENCODER_DIM)
    private val targets = IntArray(1)
    private val targetLength = intArrayOf(1)

    override fun step(frameIndex: Int, lastLabel: Int, states1: FloatArray, states2: FloatArray): JointOut {
        require(frameIndex in 0 until encFrames) { "Frame-Index $frameIndex außerhalb [0,$encFrames)" }
        // Frame-Spalte aus [1,1024,T] (kanal-major) extrahieren
        val t = frameIndex
        for (ch in 0 until AsrContract.ENCODER_DIM) {
            frame[ch] = encOut[ch * encFrames + t]
        }
        targets[0] = lastLabel

        OnnxTensor.createTensor(
            env, FloatBuffer.wrap(frame),
            longArrayOf(1, AsrContract.ENCODER_DIM.toLong(), 1)
        ).use { encFrameTensor ->
            OnnxTensor.createTensor(env, IntBuffer.wrap(targets), longArrayOf(1, 1)).use { targetsTensor ->
                OnnxTensor.createTensor(env, IntBuffer.wrap(targetLength), longArrayOf(1)).use { targetLenTensor ->
                    OnnxTensor.createTensor(
                        env, FloatBuffer.wrap(states1),
                        longArrayOf(2, 1, AsrContract.PRED_HIDDEN.toLong())
                    ).use { st1 ->
                        OnnxTensor.createTensor(
                            env, FloatBuffer.wrap(states2),
                            longArrayOf(2, 1, AsrContract.PRED_HIDDEN.toLong())
                        ).use { st2 ->
                            decoder.run(
                                mapOf(
                                    AsrContract.DEC_IN_ENCODER_OUTPUTS to encFrameTensor,
                                    AsrContract.DEC_IN_TARGETS to targetsTensor,
                                    AsrContract.DEC_IN_TARGET_LENGTH to targetLenTensor,
                                    AsrContract.DEC_IN_INPUT_STATES_1 to st1,
                                    AsrContract.DEC_IN_INPUT_STATES_2 to st2,
                                )
                            ).use { result ->
                                val logitsTensor = result.get(AsrContract.DEC_OUT_OUTPUTS).orElse(null)
                                    ?: throw IllegalStateException("Joint-Output 'outputs' fehlt")
                                val lTensor = logitsTensor as OnnxTensor
                                val logitsShape = lTensor.info.shape
                                require(
                                    logitsShape.size == 4 &&
                                        logitsShape[0] == 1L && logitsShape[1] == 1L &&
                                        logitsShape[2] == 1L && logitsShape[3] == AsrContract.LOGIT_COUNT.toLong()
                                ) {
                                    "Joint-Output hat unerwartete Form: ${logitsShape.contentToString()} (erwartet [1,1,1,8198])"
                                }
                                val logits = FloatArray(AsrContract.LOGIT_COUNT)
                                lTensor.floatBuffer.get(logits)

                                val o1 = FloatArray(2 * AsrContract.PRED_HIDDEN)
                                val o2 = FloatArray(2 * AsrContract.PRED_HIDDEN)
                                (result.get(AsrContract.DEC_OUT_OUTPUT_STATES_1).orElse(null) as OnnxTensor)
                                    .floatBuffer.get(o1)
                                (result.get(AsrContract.DEC_OUT_OUTPUT_STATES_2).orElse(null) as OnnxTensor)
                                    .floatBuffer.get(o2)

                                val tokenLogits = logits.copyOf(AsrContract.VOCAB_SIZE)
                                val durLogits = logits.copyOfRange(AsrContract.VOCAB_SIZE, AsrContract.LOGIT_COUNT)
                                return JointOut(tokenLogits, durLogits, o1, o2)
                            }
                        }
                    }
                }
            }
        }
    }
}
