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
}
