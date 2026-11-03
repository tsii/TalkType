package de.voicekeyboard.ime

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A3 (PLAN_V0133 §A3) — `seg_final`-Logartefakt beseitigen:
 *  1. Neue Session nach beendeter mit seg=1: Mic-DOWN setzt die Sessionbaseline
 *     (-1) — am Sessionstart (Zähler 0) wird KEIN `seg_final` geloggt (vorher:
 *     Altartefakt aus altem sessLastSeg=1 gegen neuen Zähler 0, Logzeile noch
 *     vor `start_ok`); danach loggt ein echtes Mini-Finale (0→1) genau einmal.
 *  2. `1→1` erzeugt keinen Eintrag; die zweite Session wiederholt denselben
 *     Verlauf ohne Altartefakt.
 *
 * Reine Entscheidung (Regel 9): nur ECHTE Zählererhöhung loggt —
 * `previous >= 0 && current > previous`; der Sessionwechsel öffnet die
 * Baseline ohne Eintrag. Keine Änderung am Segmenter oder dessen Zähler.
 */
class SegmentSessionLogTest {

    // A3-Rote-Test 1: alte Session seg=1 → Mic-DOWN → neue Session seg=0:
    // KEIN seg_final am Start; echtes Mini-Finale 0→1 loggt genau EINMAL.
    // (Realistisches Modell: updateStatus beobachtet jede Session zuerst mit
    // Zähler 0 — z. B. bei start_ok —, das Mini-Finale später als 0→1.)
    @Test fun `A3 neue Session nach beendeter mit seg1 loggt kein seg_final und Mini-Finale genau einmal`() {
        val log = SegmentSessionLog()
        // alte Session: regulärer Verlauf — 0 (kein Eintrag), dann Mini-Finale 0→1
        log.onMicDown()
        assertFalse("kein Eintrag am Sessionstart der alten Session", log.onSegmentCount(0))
        assertTrue("echtes Mini-Finale 0→1 loggt", log.onSegmentCount(1))

        // Mic-DOWN: NEUE Session — Sessionbaseline ohne Eintrag
        log.onMicDown()

        // neuer Session-Zähler 0 (nahe start_ok): KEIN Altartefakt-Eintrag
        assertFalse("kein seg_final am Sessionstart (Zähler 0 nach Baseline)", log.onSegmentCount(0))
        // gleiches Bild bei erneuter Beobachtung (start_ok-Umfeld)
        assertFalse("kein seg_final bei stabilem Zähler 0", log.onSegmentCount(0))

        // echtes Mini-Finale der NEUEN Session: genau EIN Eintrag
        assertTrue("Mini-Finale 0→1 der neuen Session loggt", log.onSegmentCount(1))
        assertFalse("kein zweiter Eintrag fuer denselben Zaehlerstand", log.onSegmentCount(1))
    }

    // A3-Rote-Test 2: 1→1 erzeugt KEINEN Eintrag; zweite Session wiederholt
    // denselben Verlauf ohne Altartefakt.
    @Test fun `A3 1zu1 loggt nichts und zweite Session ohne Altartefakt`() {
        val log = SegmentSessionLog()
        log.onMicDown()
        assertFalse("kein Eintrag am Sessionstart (0)", log.onSegmentCount(0))
        assertTrue("erstes echtes Mini-Finale loggt", log.onSegmentCount(1))
        assertFalse("1→1 erzeugt keinen Eintrag", log.onSegmentCount(1))

        // zweite Session (Mic-DOWN): derselbe Verlauf, kein Altartefakt
        log.onMicDown()
        assertFalse("kein Eintrag am neuen Sessionstart (0)", log.onSegmentCount(0))
        assertFalse("kein Eintrag bei stabilem 0", log.onSegmentCount(0))
        assertTrue("echtes Mini-Finale der zweiten Session loggt genau einmal", log.onSegmentCount(1))
        assertFalse("1→1 bleibt ohne Eintrag", log.onSegmentCount(1))

        // dritte Session: Baseline erneut sauber
        log.onMicDown()
        assertFalse("kein Eintrag am dritten Sessionstart", log.onSegmentCount(0))
        assertTrue("echtes Mini-Finale der dritten Session loggt", log.onSegmentCount(1))
    }
}
