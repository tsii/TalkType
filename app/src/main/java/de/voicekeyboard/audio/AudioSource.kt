package de.voicekeyboard.audio

/**
 * Audio-Erfassungs-Schnittstelle nach IMPLEMENTATION_PLAN.md §B.1 (verbindlich).
 *
 * Pull-Modell: ein Capture-Thread füllt eine interne Queue (monotone Sequenz),
 * der Consumer ruft blockierend [read] mit Timeout. Kein Android-Typ im
 * Interface — JVM-testbar. RMS-Pegel und Fehler laufen über optionale
 * Listener (Fehlerkanal), damit die UI den Pegel anzeigen kann, ohne die
 * Queue zu leeren.
 */
data class PcmBlock(
    val seq: Long,            // monotone Blocknummer der Session
    val startSample: Long,    // Sample-Index relativ zum Sessionstart (16 kHz)
    val samples: ShortArray,  // 16 kHz mono s16
) {
    override fun equals(other: Any?): Boolean =
        other is PcmBlock && seq == other.seq && startSample == other.startSample &&
            samples.contentEquals(other.samples)

    override fun hashCode(): Int = 31 * (31 * seq.hashCode() + startSample.hashCode()) + samples.contentHashCode()
}

interface AudioSource {
    /**
     * Startet die Erfassung für eine Session. Idempotent: ein zweiter Aufruf
     * während einer laufenden Session startet nichts neu und liefert false.
     */
    fun start(sessionId: Long): Boolean

    /**
     * Blockierendes Lesen mit Timeout.
     * @return > 0: Anzahl gelesener Samples in [dst]; 0: Timeout/keine Daten; < 0: Fehler (Session beendet)
     */
    fun read(dst: ShortArray, timeoutMs: Int): Int

    /** Beendet die Aufnahme und liefert die ÄLTEREN Queue-Reste (nicht den letzten read). */
    fun stopAndDrain(): List<PcmBlock>

    /**
     * Besitzt bereits empfangene Rest-Blöcke (nach [stopAndDrain] leer) und gibt
     * native Ressourcen frei. Ruft niemals Listener-Callbacks mehr auf.
     */
    fun release()
}

/** Fehlerkanal: Callback-basiert, da [read] nur einen Rückgabecode hat. */
fun interface AudioErrorListener {
    /**
     * @param code Fehlercode (Init-Fehler, ungültiger Zustand, Aufnahmestopp
     *   durch System, Routenverlust) — nur Codes/Dauern, nie Audiodaten.
     */
    fun onError(code: String, detail: String)
}

/** Pegelkanal: RMS pro Block (linear, 0..1), für die UI-Anzeige. */
fun interface AudioLevelListener {
    fun onLevel(rms: Float, blockSeq: Long)
}
