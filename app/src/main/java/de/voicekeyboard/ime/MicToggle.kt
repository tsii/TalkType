package de.voicekeyboard.ime

import de.voicekeyboard.dictation.DictationState

/**
 * D10 §2 + F9 d: Was ein Tap auf die Fußreihen-Mikrofon-Taste in welchem
 * Zustand auslöst — als reine Funktion, damit die Zustandsmaschine ohne
 * IME-Lebenszyklus bewiesen werden kann.
 *
 * - IDLE (oder noch kein Coordinator): START — Aufnahme beginnen.
 * - RECORDING: STOP — Aufnahme beenden und final dekodieren (Sonst ist die
 *   Taste ein Start-only-Schalter und eine laufende Aufnahme nicht mehr zu
 *   beenden, F8 Q6 = P1).
 * - STARTING / FINALIZING (und jeder künftige Zwischenzustand): IGNORE —
 *   ein Tap darf hier keinen Neustart auslösen, weil onMicDown dann an
 *   coordinator.start() scheitert und „Start fehlgeschlagen“ zeigt (F9 d = P2).
 */
enum class MicToggleAction { START, STOP, IGNORE }

/**
 * D10-Rest §1 („Daten und PTT“) — Ereignisse der Fussreihen-HALTEGESTE:
 * DOWN startet, UP beendet (mit Finale), CANCEL bricht ab (abort, KEIN
 * Final-Decode). Reine Funktion, damit die Geste ohne IME-Lebenszyklus
 * beweisbar ist (siehe MicToggleTest).
 */
enum class MicHoldAction { START, STOP, ABORT, IGNORE }

/**
 * DOWN auf dem Fussreihen-Mikrofon: NUR aus IDLE (oder ohne Coordinator)
 * starten — in RECORDING/STARTING/FINALIZING ist ein DOWN ein bewusstes
 * Nichtstun (kein Toggle-Stop über die Press-Ebene; das Stoppen kommt
 * ausschließlich über das Loslassen, D10 §1).
 */
fun micHoldPressAction(state: DictationState?): MicHoldAction = when (state) {
    null, DictationState.IDLE -> MicHoldAction.START
    else -> MicHoldAction.IGNORE
}

/**
 * Loslassen des Fussreihen-Mikrofons:
 * - normal (Finger UP): RECORDING/STARTING → STOP (Final-Decode); sonst IGNORE
 *   (z. B. nie gestartet, weil Berechtigung/Modell fehlten).
 * - CANCEL (System/Geste stiehlt den Pointer): RECORDING/STARTING → ABORT —
 *   sicherer Abbruch OHNE Final-Decode (kein Nachfahren eines Decodes in
 *   einen Editor, in den der Nutzer nicht mehr schaut).
 */
fun micHoldReleaseAction(state: DictationState?, cancelled: Boolean): MicHoldAction =
    if (cancelled) {
        when (state) {
            DictationState.RECORDING, DictationState.STARTING -> MicHoldAction.ABORT
            else -> MicHoldAction.IGNORE
        }
    } else {
        when (state) {
            DictationState.RECORDING, DictationState.STARTING -> MicHoldAction.STOP
            else -> MicHoldAction.IGNORE
        }
    }

fun micToggleAction(state: DictationState?): MicToggleAction = when (state) {
    null, DictationState.IDLE -> MicToggleAction.START
    DictationState.RECORDING -> MicToggleAction.STOP
    else -> MicToggleAction.IGNORE
}
