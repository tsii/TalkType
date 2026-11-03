package de.voicekeyboard.audio

import java.io.File
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.math.sqrt

/**
 * F30 (docs/EMULATOR_DIKTAT_HARNESS.md, Option C): Debug-only AudioSource, die eine
 * WAV-Datei (16 kHz mono s16) als deterministischen Capture-Ersatz abspielt.
 *
 * Zweck: Abbruchpfade (Stall-Watchdog, Stille, stop()-Finale) REPRODUZIERBAR machen —
 * ohne Emulator-Audio, ohne ONNX (der Coordinator bekommt in Tests eine Fake-Engine).
 *
 * Semantik:
 * - Block-Lieferung: 10 ms = 160 Samples pro [read]-Erfolg (Capture-Blöcke, wie die
 *   Tabelle §4 der Harness-Doku).
 * - ZEITGETREUHEIT über injizierbare Uhr [nowMs] (ms, monotone Testzeit; Produktion
 *   dieses debug-Types: SystemClock). [read] blockiert auf einer Condition, bis die
 *   Uhr das Block-Fälligkeitsdatum erreicht hat — KEIN Thread.sleep im Rhythmus.
 * - Szenario-Parameter:
 *   [silenceFromMs]  ab dieser Uhrzeit liefert die Quelle KEINE Blöcke mehr (read=0,
 *                    "toter" Capture — Watchdog-Material). KEIN Nullpegel-Stream:
 *                    Stille hier heißt "Quelle liefert nichts" (der Nutzerfall "Mikro
 *                    stumm"); ein streamender Nullpegel wäre laut Doku §4/S2 "Stille ≠
 *                    Stall" und löste den Watchdog NICHT aus.
 *   [gapFromMs]/[gapToMs]  bounded: in diesem Uhrzeitfenster liefert die Quelle 0
 *                    (z. B. 3500 ms = Watchdog-Auslöser), danach geht's weiter.
 *   [stopAfterMs]   nach dieser Uhrzeit ist die Quelle "zu Ende" (read=0 forever).
 *   Alle Default = LONG_MAX bzw. -1 (kein Szenario aktiv).
 * - WAV kürzer als die Szene: Inhalt wird GESCHLEIFT (de_smoke.wav ist 6,4 s).
 * - Regel 9: nie Audio-/Textinhalte loggen — dieser Typ loggt überhaupt nichts
 *   (der Coordinator loggt nur Zahlen).
 */
class FakeWavAudioSource(
    wavFile: File,
    private val silenceFromMs: Long = Long.MAX_VALUE,
    private val gapFromMs: Long = -1L,
    private val gapToMs: Long = -1L,
    private val stopAfterMs: Long = Long.MAX_VALUE,
    private val nowMs: () -> Long = { System.nanoTime() / 1_000_000L },
    var errorListener: AudioErrorListener? = null,
    var levelListener: AudioLevelListener? = null,
) : AudioSource {

    private val samples: ShortArray
    private val lock = ReentrantLock()
    private val dataAvailable = lock.newCondition()
    @Volatile private var released = false
    private var sessionStartMs = 0L // nur unter [lock]; Block-Nr. 0 ist fällig ab hier
    private var nextBlockIndex = 0L // nur unter [lock]; Block-Nr. der nächsten Lieferung

    companion object {
        const val SAMPLE_RATE = 16_000
        /** 10 ms @ 16 kHz = 160 Samples (Harness-Doku §4: 10-ms-Capture-Blöcke). */
        const val BLOCK_SAMPLES = 160
        /** Reale Poll-Obergrenze pro read (Safety-Net; der Rhythmus kommt von [nowMs]). */
        private const val POLL_NANOS = 5_000_000L
        const val ERR_INIT = "init"

        /**
         * F30: Debug-Wiring (nur im debug-Quellordner kompilierbar):
         * `scenario = "throughput" | "silence" | "gap" | "short"` über das
         * VKS-Feld (VoiceKeyboardService.debugAudioSourceFactory) schaltbar —
         * Produktion (release) kompiliert diesen Ordner nicht und bleibt
         * AudioRecordSource.
         */
        @JvmStatic
        fun build(
            wavFile: File,
            scenario: String,
            nowMs: () -> Long = { System.nanoTime() / 1_000_000L },
        ): FakeWavAudioSource = when (scenario) {
            "throughput" -> FakeWavAudioSource(wavFile, nowMs = nowMs)
            "silence" -> FakeWavAudioSource(wavFile, silenceFromMs = 0L, nowMs = nowMs)
            "gap" -> FakeWavAudioSource(
                wavFile, gapFromMs = 1_000L, gapToMs = 4_500L, nowMs = nowMs,
            )
            "short" -> FakeWavAudioSource(wavFile, stopAfterMs = 1_200L, nowMs = nowMs)
            else -> throw IllegalArgumentException("Unbekanntes Szenario (nur Name, kein Inhalt)")
        }
    }

    init {
        samples = loadWavSamples(wavFile)
    }

    /** Minimaler s16le-mono-Reader: sucht das "data"-Chunk (Header-Layout unabhängig). */
    private fun loadWavSamples(file: File): ShortArray {
        if (!file.isFile) throw IllegalStateException("WAV fehlt: " + file.name)
        val b = file.readBytes()
        if (b.size < 44 || String(b, 0, 4) != "RIFF" || String(b, 8, 4) != "WAVE") {
            throw IllegalStateException("Kein RIFF/WAVE: " + file.name)
        }
        var i = 12
        while (i + 8 <= b.size) {
            val id = String(b, i, 4)
            val size = ((b[i + 4].toInt() and 0xff) or
                ((b[i + 5].toInt() and 0xff) shl 8) or
                ((b[i + 6].toInt() and 0xff) shl 16) or
                ((b[i + 7].toInt() and 0xff) shl 24))
            if (id == "data") {
                val n = minOf(size, b.size - i - 8)
                require(n > 0) { "data-Chunk leer: " + file.name }
                val out = ShortArray(n / 2)
                for (j in out.indices) {
                    val off = i + 8 + j * 2
                    out[j] = (b[off].toInt() or (b[off + 1].toInt() shl 8)).toShort()
                }
                return out
            }
            i += 8 + size + if (size and 1 == 1) 1 else 0
        }
        throw IllegalStateException("data-Chunk nicht gefunden: " + file.name)
    }

    /** Idempotent (AudioSource-Vertrag). Merkt den Sessionstart — daran richtet
     *  sich die Block-Fälligkeit (Block i fällig bei sessionStart + i×10 ms). */
    override fun start(sessionId: Long): Boolean {
        lock.withLock {
            if (released) {
                errorListener?.onError(ERR_INIT, "released")
                return false
            }
            if (sessionStartMs == 0L) sessionStartMs = nowMs()
            return true
        }
    }

    /**
     * Blockierendes Lesen, ZEITGETREU auf der [nowMs]-Uhr:
     * - liefert das nächste 10-ms-Block der WAV (geschleift), sobald die Uhr das
     *   Fälligkeitsdatum (Block-Nr. × 10 ms) erreicht hat;
     * - 0 = Stille/Ende/Gap (Szenario) ODER Fälligkeitsdatum noch offen (dann wird
     *   bis zum nächsten Uhrsprung auf der Condition gewartet — kein Hot-Spin);
     * - -1 = freigegeben. Jedes 0-Ergebnis rate-limited auf [POLL_NANOS]
     *   (damit der Coordinator-Stall-Watchdog im Rhythmus checked wird).
     */
    override fun read(dst: ShortArray, timeoutMs: Int): Int {
        lock.withLock {
            while (true) {
                if (released) return -1
                val t = nowMs()
                if (t >= silenceFromMs || stopAfterMs <= t || inGap(t)) {
                    dataAvailable.awaitNanos(POLL_NANOS)
                    return 0
                }
                if (t < sessionStartMs + nextBlockIndex * 10L) {
                    dataAvailable.awaitNanos(POLL_NANOS)
                    continue // Fälligkeitsdatum noch nicht erreicht — Uhr wartet
                }
                val n = minOf(dst.size, BLOCK_SAMPLES)
                val base = (nextBlockIndex * BLOCK_SAMPLES % samples.size).toInt()
                for (j in 0 until n) {
                    dst[j] = samples[((base + j) % samples.size).toInt()]
                }
                nextBlockIndex += 1
                // Pegelkanal: RMS pro Block (0..1) — nur Zahlen, nie Inhalte.
                levelListener?.let {
                    var acc = 0L
                    for (j in 0 until n) acc += dst[j].toLong() * dst[j].toLong()
                    it.onLevel((sqrt(acc.toDouble() / n) / 32_768.0).toFloat(), nextBlockIndex - 1)
                }
                return n
            }
        }
    }

    /** Szenario-Gap: Uhrzeitfenster ohne Block-Lieferung (nur aktiv, wenn gapFromMs >= 0). */
    private fun inGap(t: Long): Boolean =
        gapFromMs >= 0L && gapToMs > gapFromMs && t in gapFromMs until gapToMs

    /** Keine interne Queue (Blöcke wurden per read direkt geliefert) → leer. */
    override fun stopAndDrain(): List<PcmBlock> = emptyList()

    /** Weckt den blockierenden read (Capture-Loop läuft über -1 sauber aus). */
    override fun release() {
        lock.withLock {
            released = true
            dataAvailable.signalAll()
        }
    }
}
