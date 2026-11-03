package de.voicekeyboard.dictation

/**
 * F21: cost-aware Decode-Takt — REINE Funktion, keine Uhr, kein Android.
 *
 * Grundproblem (C2, belegt): der Partial-Pfad dekodiert aus Regel-2-Gründen
 * (KONZEPT §5.1: kein Schiebefenster) jedes Mal den KOMPLETTEN bisherigen Puffer.
 * Die Arbeit je Lauf wächst damit linear mit der Aufnahmedauer, die Gesamtarbeit
 * quadratisch. Der einzige erlaubte Hebel ist WANN dekodiert wird — nicht was:
 * das ganze Fenster bleibt, keine neue Zustandsmaschine, kein Policy-Eingriff.
 *
 * Regel: das nächste Partial-Intervall ist das Doppelte der letzten Decode-Zeit
 * (Sicherheitsfaktor 2: der Decode darf im Mittel nur die Hälfte der Realzeit
 * verbrauchen), geklemmt an [baseMs]/[maxMs]. Eine schnelle Engine (Laufzeit ≈
 * [baseMs]/2 oder schneller) bekommt [baseMs] — Verhalten wie bisher.
 */
object DecodePace {

    /**
     * @param lastDecodeMs Dauer des letzten Dekodierlaufs in ms (0 = noch keiner).
     * @param bufferMs    bisherige Audiolänge in ms — nur Plausibilität: ist der
     *                    Puffer jünger als [baseMs], bringt ein engerer Takt nichts.
     * @param baseMs      untere Klammer (Standardtakt, AsrThreadConfig).
     * @param maxMs       obere Klammer — der Takt wächst nie unbegrenzt.
     * @return nächstes Partial-Intervall in ms; defensiv immer in [min(base,max), max],
     *         bei [baseMs] > [maxMs] gilt [maxMs]; Überläufe werden saturiert.
     */
    fun intervalMs(lastDecodeMs: Long, bufferMs: Long, baseMs: Long, maxMs: Long): Long {
        val base = minOf(baseMs, maxMs) // defensive Klammer: base > max ⇒ max gilt
        if (bufferMs < base) return base
        // Sicherheitsfaktor 2, saturierend: > MAX/2 würde *2 überlaufen lassen.
        val doubled = if (lastDecodeMs > Long.MAX_VALUE / 2) Long.MAX_VALUE
        else (lastDecodeMs * 2).coerceAtLeast(0L) // negative Messwerte ⇒ base
        return doubled.coerceIn(base, maxMs)
    }
}
