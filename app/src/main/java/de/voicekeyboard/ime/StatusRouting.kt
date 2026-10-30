package de.voicekeyboard.ime

/**
 * F13c/P2-3 (docs/REVIEW_F13_STATUSBAR.md): Verdrahtungsnaht der Statuszeile.
 *
 * B1 („starte Aufnahme…" wurde sticky) war ein NAHTFEHLER: die falsche Stelle
 * wählte den Kanal. Diese Tabelle macht die Naht explizit und JVM-testbar
 * (StatusRoutingTest) — der Service ruft [route] an jeder betroffenen Stelle:
 *
 *  - [StatusEvent.Starting] (Startmeldung)      → TRANSIENT (überholt bei
 *    RECORDING/FINALIZING/neuerem Fehler; darf kein stickyError setzen).
 *  - [StatusEvent.Hint] (Hinweis/Bestätigung)   → TRANSIENT (Anzeige bis zum
 *    nächsten updateStatus; drückt keine spätere Fehler-/Aufnahmemeldung).
 *  - [StatusEvent.Error] (blockierender Fehler) → STICKY (R-2: bleibt bis zur
 *    nächsten bewussten Aktion bzw. bis ein abgeschlossener Erfolg sie löst).
 *
 * Ein Rückfall (Starttext/Hinweis wieder über den Sticky-Kanal) macht den
 * StatusRoutingTest rot, ohne dass ein Gerät laufen muss.
 */
internal enum class StatusChannel { TRANSIENT, STICKY }

internal sealed class StatusEvent {
    abstract val text: String

    /** Transiente Startmeldung (z. B. „Starte Aufnahme…"). */
    data class Starting(override val text: String) : StatusEvent()

    /** Hinweis/Bestätigung ohne Blockadecharakter (z. B. Diagnose kopiert). */
    data class Hint(override val text: String) : StatusEvent()

    /** Blockierender Fehler (R-2): bleibt sticky bis zur nächsten Aktion. */
    data class Error(override val text: String) : StatusEvent()
}

internal object StatusRouting {
    fun route(event: StatusEvent): StatusChannel = when (event) {
        is StatusEvent.Starting -> StatusChannel.TRANSIENT
        is StatusEvent.Hint -> StatusChannel.TRANSIENT
        is StatusEvent.Error -> StatusChannel.STICKY
    }
}
