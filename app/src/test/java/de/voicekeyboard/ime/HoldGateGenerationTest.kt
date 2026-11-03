package de.voicekeyboard.ime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AP7-W1 (docs/PLAN_C2_FABLE_R2.md §0.1.5/0.1.6): hold gate generation-bound —
 * pure [HoldGate] logic, JVM-testable without Android. The W1b race: an
 * ASYNCHRONOUS trailing session end (onAborted of the predecessor session)
 * zeroed the global gate boolean and thereby opened the gate under the finger
 * of the NEWER gesture ⇒ F43 auto-stop "until the first punctuation mark →
 * ready".
 */
class HoldGateGenerationTest {

    // (a) foreign reset (old gen) rejected + counter — the gate stays closed.
    @Test
    fun `a) foreign reset of an old generation rejected and counted`() {
        val g = HoldGate()
        g.begin("bar-hold") // gen 1
        val g2 = g.begin("footer-hold") // gen 2 — the second gen wins (supersede)
        assertFalse("a reset of the OLD gen must be rejected", g.end(1))
        assertEquals("gate stays on gen 2 (CLOSED)", g2, g.activeGen)
        assertEquals("foreign reset counted", 1, g.foreignResets)
    }

    // (b) the own reset zeroes it.
    @Test
    fun `b) the own reset zeroes the gate`() {
        val g = HoldGate()
        val gen = g.begin("bar-hold")
        assertTrue("a reset of the OWN gen works", g.end(gen))
        assertEquals("gate open after its own reset", 0L, g.activeGen)
        assertEquals("no foreign reset", 0, g.foreignResets)
    }

    // (c) SYMPTOM SEQUENCE (Fable §0.1.5 race path): DOWN₁ → session 1 starts →
    // abort₁ (asynchronous, posted late) → DOWN₂ (new gesture) → the late
    // reset₁ runs NOW — the gate must stay CLOSED, otherwise F43 fires at
    // the first punctuation mark (W1b).
    @Test
    fun `c) symptom sequence - late abort of the predecessor session keeps the gate closed`() {
        val g = HoldGate()
        val gen1 = g.begin("bar-hold") // attempt 1: finger down
        g.bindSession() // session 1 bound to gen 1
        val genAtStart = g.activeGen // service: the gen BEFORE the (asynchronous) callback
        // attempt 2: a new DOWN before session 1's abort callback ran:
        val gen2 = g.begin("bar-hold")
        // ... now the DELAYED abort reset of session 1 runs trailing:
        assertFalse("a late reset of the session-1 gen is rejected", g.end(genAtStart))
        assertEquals("gate stays CLOSED (gen 2 active)", gen2, g.activeGen)
        assertEquals("foreign reset counted (positive W1b proof)", 1, g.foreignResets)
        assertTrue("autoStopSuppressed()==true — gate CLOSED", g.activeGen != 0L)
        // and the real UP of attempt 2 ends correctly:
        assertTrue(g.end(gen2))
        assertEquals(0L, g.activeGen)
        assertEquals("no further foreign reset", 1, g.foreignResets)
        // gen1 (pre-attempt) is done — a DOUBLE trailing run is harmless
        // (gate open, NO foreign counter — otherwise per-session noise):
        assertFalse(g.end(gen1))
        assertEquals("a catch-up over an open gate does NOT count", 1, g.foreignResets)
    }

    // (d) toggle without a gen ⇒ gate open (toggle by design).
    @Test
    fun `d) toggle without a gesture - gate open`() {
        val g = HoldGate()
        // toggle start: NO begin() — session bound to "no gesture":
        g.bindSession() // sessionGen = activeGen = 0
        assertEquals(0L, g.sessionGen)
        assertTrue("autoStopSuppressed()==false — gate open", g.activeGen == 0L)
        // session end (stopped/abort) with sessionGen=0 over an open gate:
        assertTrue("harmless catch-up (0==0)", g.end(0))
        assertEquals("no foreign reset on toggle", 0, g.foreignResets)
    }

    // (e) double beginHold (supersede): the second gen wins, the old UP does not zero it.
    @Test
    fun `e) supersede - double begin second wins`() {
        val g = HoldGate()
        val gen1 = g.begin("bar-hold")
        val gen2 = g.begin("bar-hold") // second DOWN before the first UP came
        assertEquals(gen2, g.activeGen)
        // the OLD UP runs trailing:
        assertFalse("an old UP does not null it", g.end(gen1))
        assertEquals(gen2, g.activeGen)
        assertEquals(1, g.foreignResets)
        // the NEW UP ends correctly:
        assertTrue(g.end(gen2))
        assertEquals(0L, g.activeGen)
    }

    // (f) binding + sessionGen: the session ends bound; gen sequence monotonic.
    @Test
    fun `f) session binding and monotonic generations`() {
        val g = HoldGate()
        val gen = g.begin("footer-hold")
        assertEquals("footer-hold", g.src)
        g.bindSession()
        assertEquals(gen, g.sessionGen)
        assertTrue(g.end(gen))
        // src stays until the next gesture (diagnostics of the LAST source):
        assertEquals("footer-hold", g.src)
        assertEquals("gen stays monotonic", gen + 1, g.begin("bar-hold"))
        assertEquals("bar-hold", g.src)
    }
}
