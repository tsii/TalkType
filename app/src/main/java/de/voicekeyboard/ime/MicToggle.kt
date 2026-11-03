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

// ---- T3 (v0.1.35, Debugger-Design, Beweis /tmp/vk_phone_e2.log Z.74-97): Tap/Hold-Klassifikation ----

/**
 * T3: maximale DOWN→UP-Dauer in ms, die noch als **TAP** gilt — darunter ist die
 * Fußzeilen-Mikro-Geste ein SCHALTER (Toggle), darüber ein echter Hold-Release
 * (PTT). Beweisbild: vier Sessions starben 10–90 ms nach `start_ok` über das
 * EIGENE Loslassen (mic_down → start_ok+105 ms → holdup → stopped ok=0/audio_s=0),
 * weil `micHoldReleaseAction` bei RECORDING/STARTING + UP pauschal STOP lieferte.
 */
const val MIC_TAP_MAX_MS = 300L

/**
 * T3: Entscheidung beim Loslassen der Fußzeilen-Mikro-Geste — reine Funktion
 * über den Coordinator-Zustand BEIM DOWN ([stateAtDown]: frisch vs. Session/Start
 * bereits aktiv), die DOWN→UP-Dauer ([holdDurationMs], im KeyboardView aus
 * `event.eventTime` gemessen) und den Zustand beim Release:
 *
 *  - **frischer Tap** (DOWN ohne Session, ≤ [MIC_TAP_MAX_MS]) ⇒ [TOGGLE_ON] — die
 *    Session läuft weiter, EGAL ob das UP vor oder nach `start_ok` kam (Kern des
 *    Befunds: Kalt verlor das UP an startingUp/KEEP_RUNNING, Warm an start_ok);
 *  - **Tap auf laufende/startende Session** (DOWN mit aktiver Session) ⇒
 *    [TOGGLE_OFF] — regulär stoppen (der zweite Tap schaltet aus, Gegenprobe);
 *  - **> [MIC_TAP_MAX_MS]** ⇒ echter Hold-Release ⇒ Stop ([HOLD_STOP]); läuft
 *    der Start noch ([startingUp]/STARTING) ⇒ [HOLD_STOP_PENDING] — Stop greift
 *    NACH Startende (micStartFinishedAction: STOP_AFTER_START, nicht blind
 *    KEEP_RUNNING — der frische Tap setzt das Flag GAR NICHT erst);
 *  - **CANCEL** unverändert ⇒ Abbruch ohne Finale (nur aus laufender/startender
 *    Aufnahme).
 */
enum class MicReleaseDecision { TOGGLE_ON, TOGGLE_OFF, HOLD_STOP, HOLD_STOP_PENDING, ABORT, IGNORE }

/** T3: die Release-Entscheidung (reine Naht, JVM-testbar — Muster MicToggleTest). */
fun micHoldReleaseDecision(
    stateAtDown: DictationState?,
    stateAtRelease: DictationState?,
    holdDurationMs: Long,
    cancelled: Boolean,
    startingUp: Boolean,
): MicReleaseDecision = when {
    // CANCEL unverändert: sicherer Abbruch ohne Finale — nur aus laufender/startender Aufnahme.
    cancelled -> when (stateAtRelease) {
        DictationState.RECORDING, DictationState.STARTING -> MicReleaseDecision.ABORT
        else -> MicReleaseDecision.IGNORE
    }
    // Echter Hold-Release: Stop mit Finale; Start läuft noch ⇒ pending nach Startende.
    holdDurationMs > MIC_TAP_MAX_MS -> when (stateAtRelease) {
        DictationState.RECORDING ->
            if (startingUp) MicReleaseDecision.HOLD_STOP_PENDING else MicReleaseDecision.HOLD_STOP
        DictationState.STARTING -> MicReleaseDecision.HOLD_STOP_PENDING
        else -> MicReleaseDecision.IGNORE // nie gestartet (Berechtigung/Modell/Start scheiterte)
    }
    // Tap ≤ 300 ms: DOWN ohne Session ⇒ TOGGLE_ON — nichts stoppen, egal ob
    // das UP vor oder nach start_ok kam (das war der session-toetende Fall).
    stateAtDown == null || stateAtDown == DictationState.IDLE -> MicReleaseDecision.TOGGLE_ON
    // Tap auf laufende/startende Session ⇒ regulär ausschalten (Gegenprobe).
    else -> when (stateAtRelease) {
        DictationState.RECORDING, DictationState.STARTING -> MicReleaseDecision.TOGGLE_OFF
        else -> MicReleaseDecision.IGNORE // Session endete zwischen DOWN und UP
    }
}

fun micToggleAction(state: DictationState?): MicToggleAction = when (state) {
    null, DictationState.IDLE -> MicToggleAction.START
    DictationState.RECORDING -> MicToggleAction.STOP
    else -> MicToggleAction.IGNORE
}
