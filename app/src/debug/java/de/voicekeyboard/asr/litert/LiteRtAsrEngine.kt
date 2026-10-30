package de.voicekeyboard.asr.litert

import de.voicekeyboard.asr.AsrEngine
import de.voicekeyboard.asr.AsrThreadConfig
import de.voicekeyboard.asr.Hypothesis
import de.voicekeyboard.audio.PcmWindow
import de.voicekeyboard.model.ModelManifest
import de.voicekeyboard.model.ModelVariant
import org.tensorflow.lite.Interpreter
import java.io.Closeable
import java.io.File
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicLong

/**
 * V3-SKELETT / V6a: LiteRT-Engine für Variante B
 * (Arm/parakeet-tdt-0-6b-v3-int8-litert).
 *
 * - `load()` öffnet die vier `.tflite`-Dateien aus [ModelManifest.forVariant]
 *   (LITERT_CPU) mit dem LiteRT-CPU-Interpreter (kein NPU, kein AOT).
 * - V6a: [featurize] führt eine ECHTE Inferenz auf dem Featurizer aus —
 *   Input-Form/-Typ werden ZUR LAUFZEIT aus dem Interpreter abgefragt
 *   (nicht geraten): `getInputTensor(0)`, `shape()`, `dataType()`.
 * - Streaming-Cache ist Instanz-Zustand und wird bei Session-Wechsel VERWORFEN.
 * - `transcribe()` bleibt in dieser Stufe typisiert gesperrt
 *   ([AsrNotReadyException]) — keine stille Leer-Text-Rückgabe (Regel A1).
 * - `close()` gibt alle Interpreter frei.
 */
class LiteRtAsrEngine : AsrEngine, Closeable {

    /** Typisierter, bewusster Fehler: Inferenz-Schritt in dieser Stufe nicht verdrahtet. */
    class AsrNotReadyException(message: String) : IllegalStateException(message)

    private var interpreters: List<Interpreter> = emptyList()

    /** Featurizer-Interpreter (Index im Manifest: parakeet_…_featurizer_…tflite). */
    private var featurizer: Interpreter? = null

    /** Streaming-Cache: nur innerhalb EINER sessionId gültig (Session-Wechsel ⇒ neu). */
    private var cachedSessionId: Long = -1L
    private val cacheGeneration = AtomicLong(0)

    override fun load(modelDir: File) {
        check(interpreters.isEmpty()) { "AsrEngine.load() wurde bereits aufgerufen" }
        val files = ModelManifest.forVariant(ModelVariant.LITERT_CPU)
            .filter { it.storeName.endsWith(".tflite") }
        check(files.size == TFLITE_COUNT) {
            "Variante B erwartet $TFLITE_COUNT .tflite-Dateien, gefunden: ${files.size}"
        }
        val opened = ArrayList<Interpreter>(files.size)
        try {
            for (entry in files) {
                // V10 (L1-01): Interpreter aus der DATEI via LiteRtLoader —
                // KEIN readBytes()/Heap-Kopie des 648-MB-Encoders (mmap über
                // Interpreter(File, Options)); Größenpruefung gegen das Manifest
                // im Loader, VOR dem open. Throwable-Grenze (auch Errors) im Loader
                // mit typisiertem Fehler + bereinigter Ursache.
                val loaded = LiteRtLoader.open(
                    File(modelDir, entry.storeName),
                    threadsForDevice(),
                    entry.sizeBytes,
                )
                opened.add(loaded.interpreter)
                if (entry.storeName.contains("featurizer")) {
                    featurizer = opened.last()
                }
            }
            interpreters = opened
        } catch (t: Throwable) {
            // SB1-03-Muster: bei Folgefehler (auch Errors) alle bereits geoeffneten
            // Interpreter schliessen — keine halboffenen Hybrid-Zustaende.
            opened.forEach { runCatching { it.close() } }
            featurizer = null
            throw t
        }
    }

    /** True, wenn load() erfolgreich war (Engine betriebsbereit für featurize). */
    val isLoaded: Boolean
        get() = featurizer != null

    /**
     * V6a-Testzugang (androidTest): öffnet NUR den Featurizer aus [file] —
     * für den Einzelbeweis, ohne die anderen drei B-Dateien. Produktionspfad
     * bleibt [load] (alle vier, Größen-/Namensprüfung gegen das Manifest).
     */
    /**
     * V8: Featurizer-Lauf ueber [LiteRtFeaturizer] — alle 7 Eingaenge aus dem
     * offiziellen Arm-Manifest (raw_wav, sample_mask, frame_mask, feature_mean,
     * feature_inv_std, pre_encode_feature_cache, previous_sample). Die
     * 1-Eingangs-Annahme aus V6a ist ersetzt; AsrNotReadyException nur noch
     * bei echten Abweichungen (falsche Tensorzahl, Formabweichung vom Manifest).
     */
    fun featurize(pcm: ShortArray, sampleRate: Int): IntArray =
        featurize(FloatArray(pcm.size) { pcm[it] / Short.MAX_VALUE.toFloat() }, pcm.size)

    fun featurize(
        pcm: FloatArray, pcmLen: Int,
        prevCache: FloatArray? = null, prevSample: Float? = null,
    ): IntArray {
        val f = featurizerHandle
            ?: throw AsrNotReadyException("Featurizer nicht geladen — load()/attachFeaturizer zuerst")
        return f.featurize(pcm, pcmLen, prevCache, prevSample).inputFeaturesShape
    }

    /** Voller Featurizer-Zugang (Instrumented-Test / Produktion V9). */
    fun featurizeFull(
        pcm: FloatArray, pcmLen: Int,
        prevCache: FloatArray? = null, prevSample: Float? = null,
    ): LiteRtFeaturizer.Result {
        val f = featurizerHandle
            ?: throw AsrNotReadyException("Featurizer nicht geladen — load()/attachFeaturizer zuerst")
        return f.featurize(pcm, pcmLen, prevCache, prevSample)
    }


    // ---- V11a: Signature-basierte Streaming-Pipeline (Vorpass + Fenster) ----

    private var signatureFeaturizer: LiteRtSignature? = null
    private var signatureEncoder: LiteRtSignature? = null

    /** Encoder-Cache-Zustand (ptdt.cpp: Fenster zu Fenster weiterschleifen). */
    private var encCacheChannel = FloatArray(24 * 190 * 1024)
    private var encCacheTime = FloatArray(24 * 1024 * 4)
    private var encCacheLen = longArrayOf(0L)

    fun attachSignatureFeaturizer(file: File) {
        val m = LiteRtLoader.open(file, threadsForDevice())
        signatureFeaturizer = LiteRtSignature(m.interpreter)
    }

    fun attachSignatureEncoder(file: File) {
        val m = LiteRtLoader.open(file, threadsForDevice())
        signatureEncoder = LiteRtSignature(m.interpreter)
    }

    /**
     * ptdt.cpp:342: Vorpass über die GANZE Aufnahme — Welford über log_mel
     * (NEUTRAL-Läufe), liefert (mean[128], invStd[128]).
     * MUSS vor dem ersten Dekodiervorpass laufen (Encoder braucht normalisierte
     * Features; log_mel lebt NUR im Signature-Pfad — s. LiteRtSignature-KDoc).
     */
    fun precomputeGlobalStats(audio: FloatArray, length: Int): Pair<FloatArray, FloatArray> {
        val f = signatureFeaturizer
            ?: throw AsrNotReadyException("Signature-Featurizer fehlt — attachSignatureFeaturizer zuerst")
        val stats = GlobalFeatureStats(LiteRtStreaming.FEAT_DIM)
        var off = 0
        while (off < length) {
            val remaining = length - off
            if (remaining < LiteRtStreaming.FRAME_HOP) break
            val n = minOf(LiteRtStreaming.WINDOW_SAMPLES, remaining)
            val valid = LiteRtStreaming.validFramesFor(n)
            val out = runFeaturizerSignature(f, audio, off, n,
                FloatArray(LiteRtStreaming.FEAT_DIM), FloatArray(LiteRtStreaming.FEAT_DIM) { 1f },
                Array(LiteRtStreaming.CACHE_FRAMES) { FloatArray(LiteRtStreaming.FEAT_DIM) },
                0f)
            @Suppress("UNCHECKED_CAST")
            val logMel = out["log_mel"] as? Array<FloatArray>
                ?: throw AsrNotReadyException("log_mel fehlt im Ausgang: ${f.outputNames}")
            for (frame in 0 until valid) stats.addFrame(logMel[frame])
            off += n
        }
        return stats.finish()
    }

    /** Ein Featurizer-Signature-Lauf mit Nullpolsterung (ptdt.cpp:400-434). */
    private fun runFeaturizerSignature(
        f: LiteRtSignature, audio: FloatArray, start: Int, n: Int,
        mean: FloatArray, invStd: FloatArray,
        cache: Array<FloatArray>, prevSample: Float,
    ): Map<String, Any> {
        val raw = FloatArray(LiteRtStreaming.WINDOW_SAMPLES)
        val sampleMask = FloatArray(LiteRtStreaming.WINDOW_SAMPLES)
        System.arraycopy(audio, start, raw, 0, n)
        for (i in 0 until n) sampleMask[i] = 1f
        val valid = LiteRtStreaming.validFramesFor(n)
        val frameMask = FloatArray(LiteRtStreaming.NUM_VALID_FRAMES)
        for (i in 0 until valid) frameMask[i] = 1f
        return f.run(
            mapOf(
                "raw_wav" to arrayOf(raw),
                "sample_mask" to arrayOf(sampleMask),
                "frame_mask" to arrayOf(arrayOf(frameMask)),
                "feature_mean" to arrayOf(arrayOf(mean)),
                "feature_inv_std" to arrayOf(arrayOf(invStd)),
                "pre_encode_feature_cache" to arrayOf(cache),
                "previous_sample" to floatArrayOf(prevSample),
            ),
        )
    }

    /**
     * V11a Fenster-Schritt (ptdt.cpp:458-505): Featurizer (mit Statistik +
     * Fenster-Cache + previous_sample), Encoder mit input_length = 9 + valid,
     * Encoder-Caches als Ein-/Ausgänge weitergeschleift. Liefert
     * (poolerOutput[95][1024], encoderLength).
     */
    fun encodeWindow(
        audio: FloatArray, length: Int, start: Int,
        mean: FloatArray, invStd: FloatArray,
        prevCache: Array<FloatArray>,
    ): Pair<Array<FloatArray>, Long> {
        val f = signatureFeaturizer
            ?: throw AsrNotReadyException("Signature-Featurizer fehlt")
        val e = signatureEncoder
            ?: throw AsrNotReadyException("Signature-Encoder fehlt — attachSignatureEncoder zuerst")
        val n = minOf(LiteRtStreaming.WINDOW_SAMPLES, length - start)
        val prevSample = LiteRtStreaming.previousSampleAt(audio, start)
        val out = runFeaturizerSignature(f, audio, start, n, mean, invStd, prevCache, prevSample)
        @Suppress("UNCHECKED_CAST")
        val feats = out["input_features"] as? Array<FloatArray>
            ?: throw AsrNotReadyException("input_features fehlt: ${f.outputNames}")
        val valid = LiteRtStreaming.validFramesFor(n)
        val inputLength = LiteRtStreaming.inputLengthFor(valid)
        val encOut = e.run(
            mapOf(
                "input_features" to arrayOf(feats),
                "input_length" to longArrayOf(inputLength.toLong()),
                "cache_last_channel" to arrayOf(arrayOf(arrayOf(encCacheChannel))),
                "cache_last_time" to arrayOf(arrayOf(arrayOf(encCacheTime))),
                "cache_last_channel_len" to encCacheLen,
            ),
        )
        @Suppress("UNCHECKED_CAST")
        val pool = encOut["encoder_pooler_output"] as? Array<FloatArray>
            ?: throw AsrNotReadyException("encoder_pooler_output fehlt: ${e.outputNames}")
        val encLen = when (val el = encOut["encoder_output_length"]) {
            is LongArray -> el[0]
            is Long -> el
            else -> throw AsrNotReadyException("encoder_output_length fehlt: ${e.outputNames}")
        }
        // Caches aktualisieren (ptdt.cpp: NICHT intern!)
        (encOut["cache_last_channel"] as? Array<Array<FloatArray>>)?.let {
            encCacheChannel = it[0][0]
        }
        (encOut["cache_last_time"] as? Array<Array<FloatArray>>)?.let {
            encCacheTime = it[0][0]
        }
        (encOut["cache_last_channel_len"] as? LongArray)?.let { encCacheLen = it }
        return pool to encLen
    }

    /** Cache für das nächste Fenster aus den letzten 9 gültigen Frames (ptdt.cpp:470). */
    fun nextWindowCache(feats: Array<FloatArray>, validFrames: Int): Array<FloatArray> =
        LiteRtStreaming.preEncodeCache(feats, validFrames, LiteRtStreaming.FEAT_DIM)

    /** V8: Featurizer-Wrapper; oeffnet die Datei mit Thread-Anzahl aus der Konfig. */
    private var featurizerHandle: LiteRtFeaturizer? = null

    fun attachFeaturizer(file: File) {
        featurizerHandle?.close()
        featurizerHandle = LiteRtFeaturizer(file, threadsForDevice())
    }

    private fun threadsForDevice(): Int = AsrThreadConfig.forDevice(
        cores = Runtime.getRuntime().availableProcessors(),
        ramMb = AsrThreadConfig.deviceRamMb,
    ).intraOpThreads

    fun openFeaturizerOnly(file: File) {
        // V8: derselbe Pfad wie die Produktion — der 7-Eingangs-Featurizer
        check(file.isFile) { "Featurizer fehlt: ${file.name}" }
        attachFeaturizer(file)
    }

    override suspend fun transcribe(window: PcmWindow, sessionId: Long, runId: Long): Hypothesis {
        check(interpreters.isNotEmpty()) {
            "AsrEngine nicht geladen — load() vor transcribe() aufrufen"
        }
        // Streaming-Cache: Session-Wechsel verwirft den Cache (V3/V6a-Invariante).
        if (cachedSessionId != sessionId) {
            cachedSessionId = sessionId
            cacheGeneration.incrementAndGet()
        }
        // Bewusst gesperrt bis V4: typisierter Fehler statt Leer-Text
        throw AsrNotReadyException(
            "LiteRT-Transkription in dieser Stufe nicht verdrahtet (V4) — Cache-Generation ${cacheGeneration.get()}",
        )
    }

    override fun close() {
        interpreters.forEach { runCatching { it.close() } }
        interpreters = emptyList()
        featurizer = null
        featurizerHandle?.close()
        featurizerHandle = null
        cachedSessionId = -1L
    }

    companion object {
        private const val TFLITE_COUNT = 4

        /** Test-Hook: Cache-Generation (Session-Wechsel inkrementiert). */
        fun cacheGenerationOf(engine: LiteRtAsrEngine): Long = engine.cacheGeneration.get()
    }
}
