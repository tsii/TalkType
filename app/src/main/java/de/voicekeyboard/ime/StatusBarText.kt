package de.voicekeyboard.ime

/**
 * F13a/B1 (docs/STREAMING_FREEZE_ANALYSE.md §2): Reinlogische Textauswahl der
 * IME-Statuszeile — ohne Android-Typen, damit das Status-Einfrieren
 * („starte Aufnahme…" bleibt sticky und verdeckt Fehler) auf der JVM bewiesen
 * ist (siehe StatusBarTextTest).
 *
 * Zwei strikt getrennte Kategorien:
 *  - TRANSIENT (Startmeldung): nur bis sie überholt ist; setzt KEIN stickyError.
 *  - STICKY (Fehler): bleibt bis zur nächsten bewussten Aktion (R-2) oder bis
 *    ein abgeschlossener Erfolg sie auflöst (R-2b, `success`).
 *
 * Klärheitsregel (2 Zeilen): Ein transistenter Text verdeckt niemals einen
 * FEHLER, der neuer ist — neu = Aufrufer übergibt `errorsNewerThanTransient`
 * (Zeitstempelvergleich beim Aufrufer). Ältere Fehler haben den Starttext
 * nicht zu verstellen; während RECORDING/FINALIZING ist der transiente Text
 * grundsätzlich überholt.
 */
/**
 * F52 (PLAN_V0130 A3): Art des zuletzt gewählten Statustexts — Code für den
 * 🐞-Export (status_res, Regel 9: nur Codes/Ressourcen-Namen, nie Inhalte).
 * RAW ist der nie erlaubte Rückfall (Assertion im Test).
 */
enum class StatusKind { STARTING, RECORDING, FINALIZING, IDLE, HINT, ERROR, DIAG, RAW }

object StatusBarText {

    data class Inputs(
        /** Neu übergebener Text (Fehler) ODER der transiente Starttext selbst. */
        val extra: String? = null,
        /** Aktuell aktiver transienter Text (z. B. „Starte Aufnahme…"); null = keiner. */
        val transientText: String? = null,
        /** Blockierende Fehlermeldung (R-2): bleibt bis nächste bewusste Aktion. */
        val stickyError: String? = null,
        /** Sichtbarer Fehlercode (Audio-/ASR-Fehler, N-4) — kann nie sticky werden. */
        val visibleError: String? = null,
        val recording: Boolean = false,
        val finalizing: Boolean = false,
        /** Vom Aufrufer komponierter Aufnahmetext (Route + Debug-Diagnose). */
        val recordingText: String,
        val finalizingText: String,
        val idleText: String,
        /** Klärheit: Fehler (sticky/visible) sind NEUER als der transiente Text. */
        val errorsNewerThanTransient: Boolean = false,
        /** Ein Erfolg ist abgeschlossen (z. B. Final-Decode mit Ausgabe) → R-2b. */
        val success: Boolean = false,
        /** F52: der aktuelle transiente Text ist ein HINT (nicht die Startmeldung). */
        val transientHint: Boolean = false,
        /** F52: die Aufnahmetext-Zeile trägt den Debug-Diagnose-Suffix (diag). */
        val diagSuffix: Boolean = false,
    )

    data class Result(
        val text: String,
        /** True ⇒ Aufrufer DARF stickyError löschen (nur bei abgeschlossenem Erfolg). */
        val stickyConsumed: Boolean,
        /** True ⇒ Aufrufer DARF/SOLL den transienten Text löschen (er ist überholt). */
        val transientConsumed: Boolean,
        /** True ⇒ extra ist als stickyError zu führen (echter Fehler, nicht transient). */
        val extraIsSticky: Boolean,
        /** F52: Art des gewählten Texts — Grundlage für status_res (Regel 9). */
        val kind: StatusKind,
    )

    fun select(inputs: Inputs): Result {
        // Ein extra, das der transiente Text selbst ist, ist KEIN Fehler.
        val extraTransient = inputs.extra != null && inputs.extra == inputs.transientText
        val effectiveTransient = inputs.transientText ?: inputs.extra?.takeIf { extraTransient }

        // 1. Echtes Fehler-extra: sofort sichtbar und sticky (R-2).
        if (inputs.extra != null && !extraTransient) {
            return Result(inputs.extra, stickyConsumed = false, transientConsumed = true, extraIsSticky = true, kind = StatusKind.ERROR)
        }
        val errorsPresent = inputs.visibleError != null || inputs.stickyError != null
        // 2. TRANSIENT: nur solange kein Recording/Finalizing läuft und kein
        //    NEUERER Fehler da ist (Klärheitsregel, siehe Klassenkommentar).
        if (effectiveTransient != null && !inputs.recording && !inputs.finalizing &&
            !(errorsPresent && inputs.errorsNewerThanTransient)
        ) {
            return Result(effectiveTransient, stickyConsumed = false, transientConsumed = false, extraIsSticky = false, kind = if (inputs.transientHint) StatusKind.HINT else StatusKind.STARTING)
        }
        // 3. Erfolg (abgeschlossene Aufnahme, Zustand zurück im Idle): R-2b —
        //    stickyError darf gelöscht werden, Anzeige ist der Idle-Text.
        if (inputs.success && !inputs.recording && !inputs.finalizing) {
            return Result(inputs.idleText, stickyConsumed = true, transientConsumed = true, extraIsSticky = false, kind = StatusKind.IDLE)
        }
        // 4. Neue Fehler (N-4: visibleError vor stickyError).
        inputs.visibleError?.let {
            return Result(it, stickyConsumed = false, transientConsumed = true, extraIsSticky = false, kind = StatusKind.ERROR)
        }
        inputs.stickyError?.let {
            return Result(it, stickyConsumed = false, transientConsumed = true, extraIsSticky = false, kind = StatusKind.ERROR)
        }
        // 5./6. Betrieb: Aufnahme- und Finalisierungs-Text (transient ist überholt).
        if (inputs.recording) {
            return Result(inputs.recordingText, stickyConsumed = false, transientConsumed = true, extraIsSticky = false, kind = if (inputs.diagSuffix) StatusKind.DIAG else StatusKind.RECORDING)
        }
        if (inputs.finalizing) {
            return Result(inputs.finalizingText, stickyConsumed = false, transientConsumed = true, extraIsSticky = false, kind = StatusKind.FINALIZING)
        }
        // 7. Idle ohne Erfolg: sticky bleibt unberührt (schon oben abgehandelt),
        //    hier ist garantiert kein Fehler mehr aktiv.
        return Result(inputs.idleText, stickyConsumed = false, transientConsumed = true, extraIsSticky = false, kind = StatusKind.IDLE)
    }

    /**
     * F52 (PLAN_V0130 A3): status_res-Code aus dem kind — nur Codes bzw.
     * Ressourcen-NAMEN, nie Inhalte (Regel 9). ERROR trägt den Ressourcen-
     * Namen, wenn der Aufrufer ihn ableiten kann (sonst error:unknown);
     * HINT ist bewusst ohne Namen (der Ressourcen-Name ist an der Naht
     * nicht zentral ableitbar — StatusEvent trägt nur Text).
     */
    fun statusResCode(kind: StatusKind, errorResName: String? = null): String = when (kind) {
        StatusKind.STARTING -> "starting"
        StatusKind.RECORDING -> "recording"
        StatusKind.FINALIZING -> "finalizing"
        StatusKind.IDLE -> "idle"
        StatusKind.HINT -> "hint"
        StatusKind.DIAG -> "diag"
        StatusKind.ERROR -> "error:" + (errorResName ?: "unknown")
        StatusKind.RAW -> "raw"
    }
}
