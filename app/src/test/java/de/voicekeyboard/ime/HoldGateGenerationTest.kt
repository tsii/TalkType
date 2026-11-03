package de.voicekeyboard.ime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AP7-W1 (docs/PLAN_C2_FABLE_R2.md §0.1.5/0.1.6): Halte-Gate generationengebunden —
 * reine [HoldGate]-Logik, JVM-testbar ohne Android. Der W1b-Race: ein ASYNCHRON
 * nachgezogenes Session-Ende (onAborted der Vorgänger-Session) nullte das
 * globale Gate-Boolean und öffnete so das Gate unter dem Finger der NEUEREN
 * Geste ⇒ F43-Auto-Stop „bis zum ersten Satzzeichen → Bereit".
 */
class HoldGateGenerationTest {

    // (a) Fremd-Reset (alte Gen) abgewiesen + Zähler — Gate bleibt zu.
    @Test
    fun `a) Fremd-Reset alte Generation abgewiesen und gezaehlt`() {
        val g = HoldGate()
        g.begin("bar-hold") // Gen 1
        val g2 = g.begin("footer-hold") // Gen 2 — zweite Gen gewinnt (Supersede)
        assertFalse("Reset der ALTEN Gen muss abgewiesen werden", g.end(1))
        assertEquals("Gate bleibt auf Gen 2 (ZU)", g2, g.activeGen)
        assertEquals("Fremd-Reset gezählt", 1, g.foreignResets)
    }

    // (b) Eigener Reset nullt.
    @Test
    fun `b) Eigener Reset nullt das Gate`() {
        val g = HoldGate()
        val gen = g.begin("bar-hold")
        assertTrue("Reset der EIGENEN Gen wirkt", g.end(gen))
        assertEquals("Gate offen nach eigenem Reset", 0L, g.activeGen)
        assertEquals("kein Fremd-Reset", 0, g.foreignResets)
    }

    // (c) SYMPTOMSEQUENZ (Fable §0.1.5 Race-Pfad): DOWN₁ → Session 1 startet →
    // abort₁ (asynchron, verspätet gepostet) → DOWN₂ (neue Geste) → der
    // verspätete Reset₁ läuft JETZT — das Gate muss ZU bleiben, sonst feuert
    // F43 beim ersten Satzzeichen (W1b).
    @Test
    fun `c) Symptomsequenz - verspaeteter Abort der Vorgaenger-Session laesst Gate zu`() {
        val g = HoldGate()
        val gen1 = g.begin("bar-hold") // Versuch 1: Finger unten
        g.bindSession() // Session 1 an Gen 1 gebunden
        val genAtStart = g.activeGen // Service: Gen VOR dem (asynchronen) Callback
        // Versuch 2: neuer DOWN, bevor der Abort-Callback von Session 1 lief:
        val gen2 = g.begin("bar-hold")
        // ... jetzt läuft der VERSPÄTETE Abort-Reset von Session 1 nach:
        assertFalse("verspäteter Reset der Session-1-Gen abgewiesen", g.end(genAtStart))
        assertEquals("Gate bleibt ZU (Gen 2 aktiv)", gen2, g.activeGen)
        assertEquals("Fremd-Reset gezählt (positiver W1b-Beweis)", 1, g.foreignResets)
        assertTrue("autoStopSuppressed()==true — Gate ZU", g.activeGen != 0L)
        // und die echte UP von Versuch 2 beendet korrekt:
        assertTrue(g.end(gen2))
        assertEquals(0L, g.activeGen)
        assertEquals("kein weiterer Fremd-Reset", 1, g.foreignResets)
        // gen1 (Vorsuch) ist erledigt — ein DOPPELTES Nachziehen ist harmlos
        // (Gate offen, KEIN Fremd-Zähler — sonst Rauschen je Session):
        assertFalse(g.end(gen1))
        assertEquals("Nachziehen über offenem Gate zählt NICHT", 1, g.foreignResets)
    }

    // (d) Toggle ohne Gen ⇒ Gate offen (toggle by design).
    @Test
    fun `d) Toggle ohne Geste - Gate offen`() {
        val g = HoldGate()
        // Toggle-Start: KEIN begin() — Session an "keine Geste" gebunden:
        g.bindSession() // sessionGen = activeGen = 0
        assertEquals(0L, g.sessionGen)
        assertTrue("autoStopSuppressed()==false — Gate offen", g.activeGen == 0L)
        // Session-Ende (stopped/abort) mit sessionGen=0 über offenem Gate:
        assertTrue("harmloses Nachziehen (0==0)", g.end(0))
        assertEquals("kein Fremd-Reset bei Toggle", 0, g.foreignResets)
    }

    // (e) Doppeltes beginHold (Supersede): zweite Gen gewinnt, alte UP nullt nicht.
    @Test
    fun `e) Supersede - doppeltes begin zweiter gewinnt`() {
        val g = HoldGate()
        val gen1 = g.begin("bar-hold")
        val gen2 = g.begin("bar-hold") // zweiter DOWN, bevor der erste UP kam
        assertEquals(gen2, g.activeGen)
        // der ALTE UP läuft nach:
        assertFalse("alter UP nullt nicht", g.end(gen1))
        assertEquals(gen2, g.activeGen)
        assertEquals(1, g.foreignResets)
        // der NEUE UP beendet korrekt:
        assertTrue(g.end(gen2))
        assertEquals(0L, g.activeGen)
    }

    // (f) Bindung + sessionGen: Session endet gebunden; Gen-Sequenz monoton.
    @Test
    fun `f) Session-Bindung und monotone Generationen`() {
        val g = HoldGate()
        val gen = g.begin("footer-hold")
        assertEquals("footer-hold", g.src)
        g.bindSession()
        assertEquals(gen, g.sessionGen)
        assertTrue(g.end(gen))
        // src bleibt bis zur nächsten Geste stehen (Diagnose der LETZTEN Quelle):
        assertEquals("footer-hold", g.src)
        assertEquals("Gen bleibt monoton", gen + 1, g.begin("bar-hold"))
        assertEquals("bar-hold", g.src)
    }
}
