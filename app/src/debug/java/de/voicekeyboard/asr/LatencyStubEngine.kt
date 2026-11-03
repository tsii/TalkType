package de.voicekeyboard.asr

import de.voicekeyboard.audio.PcmWindow
import java.io.File
import kotlin.math.roundToInt

/**
 * AP1 (docs/PLAN_C2_FABLE_R2.md §1.2): deterministische Latenz-Stub-Engine für
 * C2-Simulationen auf dem Emulator/Gerät — Debug-Sourceset NUR.
 *
 *  - transcribe() schläft `base_ms + α · buffer_s` auf der ECHTZEITUHR
 *    (die Pipeline sieht nur Decode-Dauer und Textlänge; α-Default 38,5 =
 *    Gerätekurve I3, Stress-Varianten 80/150 via debug.vk.engine).
 *  - Pseudo-Text: Länge ∝ buffer_s (`WORDS_PER_SECOND`), Muster „wort<i>",
 *    SATZZEICHEN '.' an jedem 8. Wort (füttert die Segmentregel (a)).
 *    Wort-IDs sind je Fenster von 1 an deterministisch aus der Pufferlänge
 *    abgeleitet — aufeinanderfolgende Decodes des WACHSENDEN Segment-Puffers
 *    sind präfix-stabil (Agreement/Commit laufen realistisch); nach einem
 *    Segment-Restart beginnt die Nummerierung neu (wie eine echte Trans-
 *    kription des Ausschnitts), die [runId] des Controllers bleibt monoton
 *    und wird unverändert durchgereicht.
 *
 * Regel 9: Inhalte sind generische Musterwort-Zahlen, niemals Nutzerdaten.
 * Grenze (im Plan dokumentiert): für RSS/OOM- und Qualitätstests ist die
 * ECHTE Engine zu verwenden (debug.vk.engine=real).
 */
class LatencyStubEngine(
    val alphaMsPerSecond: Double = 38.5,
    val baseMs: Long = 0L,
    private val wordsPerSecond: Double = 2.0,
) : AsrEngine {

    override fun load(modelDir: File) {
        // Kein Modell — die Latenz ist reine Rechenzeit-Simulation.
    }

    override suspend fun transcribe(
        window: PcmWindow,
        sessionId: Long,
        runId: Long,
    ): Hypothesis {
        val bufferS = window.samples.size.toDouble() / window.sampleRate
        val sleepMs = baseMs + (alphaMsPerSecond * bufferS).toLong()
        if (sleepMs > 0) Thread.sleep(sleepMs) // Echtzeituhr, bewusst (AP1)
        val wordCount = (bufferS * wordsPerSecond).roundToInt().coerceAtLeast(1)
        val sb = StringBuilder()
        for (i in 1..wordCount) {
            if (i > 1) sb.append(' ')
            sb.append("wort").append(i)
            if (i % 8 == 0) sb.append('.') // Satzzeichen alle 8 Wörter (Regel a)
        }
        return Hypothesis(
            IntArray(0), sb.toString(), IntArray(0), sessionId, runId,
            window.samples.size, incomplete = false,
        )
    }

    override fun close() {}
}
