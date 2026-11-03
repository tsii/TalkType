package de.voicekeyboard.ime

import de.voicekeyboard.dictation.DictationState
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * F9 d (P2) + F8 Q6 (P1): Zustandsmaschine des Fußreihen-Mikrofons.
 * Die Taste muss (a) starten UND stoppen und (b) in Zwischenzuständen
 * bewusst nichts tun — sonst meldet sie „Start fehlgeschlagen“.
 */
class MicToggleTest {

    @Test
    fun `ohne Coordinator startet der erste Tap`() {
        assertEquals(MicToggleAction.START, micToggleAction(null))
    }

    @Test
    fun `IDLE startet die Aufnahme`() {
        assertEquals(MicToggleAction.START, micToggleAction(DictationState.IDLE))
    }

    @Test
    fun `RECORDING stoppt die Aufnahme (zweiter Tap ist kein Schlucken mehr)`() {
        assertEquals(MicToggleAction.STOP, micToggleAction(DictationState.RECORDING))
    }

    @Test
    fun `STARTING und FINALIZING sind ein bewusstes Nichtstun`() {
        assertEquals(MicToggleAction.IGNORE, micToggleAction(DictationState.STARTING))
        assertEquals(MicToggleAction.IGNORE, micToggleAction(DictationState.FINALIZING))
    }

    @Test
    fun `jede Zustand der enum ist zugeordnet, kein Fall faellt durch`() {
        // START/STOP nur in IDLE bzw RECORDING; alle anderen Zustände IGNORE.
        for (s in DictationState.entries) {
            val a = micToggleAction(s)
            when (s) {
                DictationState.IDLE -> assertEquals(MicToggleAction.START, a)
                DictationState.RECORDING -> assertEquals(MicToggleAction.STOP, a)
                else -> assertEquals("Zustand $s darf keinen Start/Stop auslösen", MicToggleAction.IGNORE, a)
            }
        }
    }

    // ---- D10-Rest §1: HALTEGESTE auf dem Fussreihen-Mikrofon ----

    @Test
    fun `Haltegeste DOWN startet nur aus IDLE - kein Toggle-Stop ueber die Press-Ebene`() {
        assertEquals(MicHoldAction.START, micHoldPressAction(null))
        assertEquals(MicHoldAction.START, micHoldPressAction(DictationState.IDLE))
        // In laufenden/uebergangenen Zustaenden ist DOWN bewusst NICHTS:
        assertEquals(MicHoldAction.IGNORE, micHoldPressAction(DictationState.RECORDING))
        assertEquals(MicHoldAction.IGNORE, micHoldPressAction(DictationState.STARTING))
        assertEquals(MicHoldAction.IGNORE, micHoldPressAction(DictationState.FINALIZING))
    }

    @Test
    fun `Haltegeste UP beendet mit Finale - nie gestartet heisst bewusst nichts`() {
        assertEquals(MicHoldAction.STOP, micHoldReleaseAction(DictationState.RECORDING, cancelled = false))
        assertEquals(MicHoldAction.STOP, micHoldReleaseAction(DictationState.STARTING, cancelled = false))
        // IDLE/FINALIZING: kein Stop (nicht gestartet bzw. Finale laeuft bereits)
        assertEquals(MicHoldAction.IGNORE, micHoldReleaseAction(null, cancelled = false))
        assertEquals(MicHoldAction.IGNORE, micHoldReleaseAction(DictationState.IDLE, cancelled = false))
        assertEquals(MicHoldAction.IGNORE, micHoldReleaseAction(DictationState.FINALIZING, cancelled = false))
    }

    @Test
    fun `CANCEL bricht ohne Finale ab - nur aus laufender Aufnahme`() {
        assertEquals(MicHoldAction.ABORT, micHoldReleaseAction(DictationState.RECORDING, cancelled = true))
        assertEquals(MicHoldAction.ABORT, micHoldReleaseAction(DictationState.STARTING, cancelled = true))
        assertEquals(MicHoldAction.IGNORE, micHoldReleaseAction(DictationState.IDLE, cancelled = true))
        assertEquals(MicHoldAction.IGNORE, micHoldReleaseAction(DictationState.FINALIZING, cancelled = true))
        assertEquals(MicHoldAction.IGNORE, micHoldReleaseAction(null, cancelled = true))
    }

    @Test
    fun `Haltegeste deckt jeden DictationState in jeder Event-Kombination ab`() {
        for (s in DictationState.entries) {
            val press = micHoldPressAction(s)
            val release = micHoldReleaseAction(s, cancelled = false)
            val cancel = micHoldReleaseAction(s, cancelled = true)
            when (s) {
                DictationState.IDLE -> {
                    assertEquals(MicHoldAction.START, press)
                    assertEquals(MicHoldAction.IGNORE, release)
                    assertEquals(MicHoldAction.IGNORE, cancel)
                }
                DictationState.RECORDING, DictationState.STARTING -> {
                    assertEquals(MicHoldAction.IGNORE, press)
                    assertEquals(MicHoldAction.STOP, release)
                    assertEquals(MicHoldAction.ABORT, cancel)
                }
                else -> assertEquals(
                    "Zustand $s darf auf kein Haltegeste-Ereignis reagieren",
                    MicHoldAction.IGNORE, press,
                ).also {
                    assertEquals(MicHoldAction.IGNORE, release)
                    assertEquals(MicHoldAction.IGNORE, cancel)
                }
            }
        }
    }

    // ---- T3 (v0.1.35, Debugger-Design, Beweis /tmp/vk_phone_e2.log Z.74-97):
    // Tap/Hold-Klassifikation der Fußzeilen-Mikro-Geste (micHoldReleaseDecision) ----

    // ① frischer Tap, UP 150 ms VOR start_ok ⇒ TOGGLE_ON — die Session läuft
    // weiter (STARTING = Start noch nicht fertig), das Flag bleibt UNgesetzt
    // (micStartFinishedAction RUN ⇒ nach start_ok RECORDING, kein Stop).
    @Test
    fun `T3 frischer Tap 150ms VOR start_ok - TOGGLE_ON, Session laeuft weiter`() {
        assertEquals(
            MicReleaseDecision.TOGGLE_ON,
            micHoldReleaseDecision(
                stateAtDown = DictationState.IDLE,
                stateAtRelease = DictationState.STARTING, // start_ok noch nicht gekommen
                holdDurationMs = 150L,
                cancelled = false,
                startingUp = true,
            ),
        )
        // TOGGLE_ON setzt KEIN wantedStop-Flag — nach start_ok läuft die Session (RUN):
        assertEquals(MicStartFinishAction.RUN, micStartFinishedAction(wantedStop = false, wantedAbort = false))
    }

    // ② frischer Tap, UP 40 ms NACH start_ok ⇒ TOGGLE_ON, KEIN Stop — genau der
    // Beweisfall (Warm): vorher tötete micHoldReleaseAction(RECORDING, UP) die
    // Session sofort (stopped ok=0/audio_s=0, 10-90 ms nach start_ok).
    @Test
    fun `T3 frischer Tap 40ms NACH start_ok - TOGGLE_ON, kein Stop, Session lebt`() {
        assertEquals(
            MicReleaseDecision.TOGGLE_ON,
            micHoldReleaseDecision(
                stateAtDown = DictationState.IDLE,
                stateAtRelease = DictationState.RECORDING, // start_ok kam schon
                holdDurationMs = 40L,
                cancelled = false,
                startingUp = false,
            ),
        )
        // auch der Down-lose-Fall (null = kein Coordinator) ist ein frischer Tap:
        assertEquals(
            MicReleaseDecision.TOGGLE_ON,
            micHoldReleaseDecision(null, DictationState.RECORDING, 40L, cancelled = false, startingUp = false),
        )
    }

    // ③ Hold > 300 ms ⇒ echter Hold-Release: STOP mit Finale; Variante Release
    // WÄHREND des Starts ⇒ HOLD_STOP_PENDING — der nachgereichte Stop greift NACH
    // Startende (micStartFinishedAction STOP_AFTER_START, nicht blind KEEP_RUNNING).
    @Test
    fun `T3 Hold ueber 300ms - Stop mit Finale - Release im Start haelt pending Stop nach`() {
        assertEquals(
            "Hold-Release auf laufender Session: regulärer Stop mit Finale",
            MicReleaseDecision.HOLD_STOP,
            micHoldReleaseDecision(DictationState.IDLE, DictationState.RECORDING, 350L, cancelled = false, startingUp = false),
        )
        assertEquals(
            "Release während STARTING: pending Stop",
            MicReleaseDecision.HOLD_STOP_PENDING,
            micHoldReleaseDecision(DictationState.IDLE, DictationState.STARTING, 350L, cancelled = false, startingUp = true),
        )
        assertEquals(
            "Release auf RECORDING, während start_ok noch aussteht: pending Stop",
            MicReleaseDecision.HOLD_STOP_PENDING,
            micHoldReleaseDecision(DictationState.IDLE, DictationState.RECORDING, 350L, cancelled = false, startingUp = true),
        )
        // der pending Stop greift NACH Startende WIRKLICH (T3 ersetzt KEEP_RUNNING):
        assertEquals(MicStartFinishAction.STOP_AFTER_START, micStartFinishedAction(wantedStop = true, wantedAbort = false))
        // Grenze: genau 300 ms sind noch ein Tap (>) — 301 ms schon Hold:
        assertEquals(MicReleaseDecision.TOGGLE_ON,
            micHoldReleaseDecision(DictationState.IDLE, DictationState.RECORDING, MIC_TAP_MAX_MS, cancelled = false, startingUp = false))
        assertEquals(MicReleaseDecision.HOLD_STOP,
            micHoldReleaseDecision(DictationState.IDLE, DictationState.RECORDING, MIC_TAP_MAX_MS + 1, cancelled = false, startingUp = false))
    }

    // ④ Gegenprobe: zweiter Tap auf die laufende Session ⇒ TOGGLE_OFF bleibt
    // (regulär ausschalten) — die T3-Klassifikation öffnet keinen Dauertoaster.
    @Test
    fun `T3 Gegenprobe - zweiter Tap auf laufender Session ist TOGGLE_OFF`() {
        assertEquals(
            MicReleaseDecision.TOGGLE_OFF,
            micHoldReleaseDecision(DictationState.RECORDING, DictationState.RECORDING, 120L, cancelled = false, startingUp = false),
        )
        assertEquals(
            "Tap auf startender Session schaltet ebenfalls aus",
            MicReleaseDecision.TOGGLE_OFF,
            micHoldReleaseDecision(DictationState.RECORDING, DictationState.STARTING, 120L, cancelled = false, startingUp = true),
        )
        // CANCEL unverändert: Abbruch ohne Finale (nur aus laufender/startender Aufnahme):
        assertEquals(MicReleaseDecision.ABORT,
            micHoldReleaseDecision(DictationState.RECORDING, DictationState.RECORDING, 120L, cancelled = true, startingUp = false))
        assertEquals(MicReleaseDecision.IGNORE,
            micHoldReleaseDecision(DictationState.IDLE, DictationState.IDLE, 120L, cancelled = true, startingUp = false))
        // Session endete zwischen DOWN und UP: bewusst nichts:
        assertEquals(MicReleaseDecision.IGNORE,
            micHoldReleaseDecision(DictationState.RECORDING, DictationState.FINALIZING, 120L, cancelled = false, startingUp = false))
    }
}
