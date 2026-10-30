package de.voicekeyboard.debug

/**
 * Hält den höchsten während einer Diktat-Sitzung gesehenen Audio-Pegel fest.
 *
 * Reine JVM-Logik (kein Android-Typ), damit die Anzeige ohne Gerät bewiesen ist.
 * Regel 9: es wird ausschliesslich ein Maximalwert geführt — keine Samples,
 * keine Kurven, keine Zeitreihen, nichts was auf Inhalte schliessen liesse.
 *
 * Der Wertebereich der Eingabe ist linear 0..1 (siehe `AudioLevelListener`).
 */
class LevelPeak {

    @Volatile private var max: Float = 0f

    /**
     * Pegel eines Blocks melden. Ausserhalb des Bereichs liegende oder
     * nicht-zahlende Werte werden ignoriert (kein Absturz in der Diagnose).
     */
    fun note(rms: Float) {
        if (!rms.isFinite() || rms <= 0f) return
        if (rms > max) max = if (rms > 1f) 1f else rms
    }

    /** Spitze in Prozent (0..100), ganzzahlig — genau das, was die Diagnosezeile zeigt. */
    fun percent(): Int = (max * 100f).toInt().coerceIn(0, 100)

    /**
     * Spitze in dBFS (<= 0) oder null, wenn nie ein Pegel gemessen wurde.
     * Warum: peak_pct runt bei leiser Aufnahme auf 0..2 und ist dort nicht mehr
     * aufloesend. -34 dBFS (Telefonbefund) ist als Zahl erst erkennbar.
     */
    fun dbfs(): Double? {
        if (max <= 0f) return null
        return (20.0 * kotlin.math.log10(max.toDouble())).coerceAtMost(0.0)
    }

    /** Für den Sitzungsbeginn: Spitze vergessen. */
    fun reset() {
        max = 0f
    }

    /**
     * Kurzform für die Diagnosezeile: „2 (-34 dB)“ bzw. „0“. Der Prozentwert
     * allein ist bei leiser Aufnahme nicht unterscheidbar (0 vs. 2 vs. 3),
     * die dBFS-Stufe macht den Unterschied sichtbar (Telefonbefund -34 dBFS).
     */
    fun short(): String {
        val p = percent()
        val d = dbfs() ?: return p.toString()
        return "$p (${"%.0f".format(d)} dB)"
    }
}
