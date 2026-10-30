package de.voicekeyboard.dictation

/**
 * Push-to-Talk-Zustände (DESIGN.md §2.4, KONZEPT.md §2.1).
 *
 * IDLE → STARTING → RECORDING → FINALIZING → IDLE; Fehler/Abbruch führt über
 * geordnetes Cleanup nach IDLE. CANCELLING/STOPPING entfallen als eigene
 * Zustände in dieser Stufe: [DictationController.abort] bzw. [DictationController.stop]
 * laufen synchron und deterministisch nach IDLE.
 */
enum class DictationState {
    /** Keine Aufnahme. start() ist erlaubt. */
    IDLE,

    /** Aufnahme wird initialisiert (Ressourcen/Session anlegen). */
    STARTING,

    /** Mikrofon läuft, Blöcke kommen über feedAudio herein. */
    RECORDING,

    /** Taste losgelassen: finales Decode des Gesamtpuffers läuft. */
    FINALIZING,
}
