package de.voicekeyboard.keyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * F22: Spezifikation der reinen Popup-Naht [LongPressMenu] — geschrieben VOR dem
 * Produktionscode. Maße aus docs/DESIGN_KEY_POPUP.md §1 (density 420, 2,625 px/dp:
 * Zelle 48 dp = 126 px, Höhe 56 dp = 147 px, Abstand 8 dp = 21 px). Die vorgegebenen
 * Aufgaben-Zahlen (u. a. menuWidthPx(126,3,10)=418) sind verbindlich.
 */
class LongPressMenuTest {

    // --- cellCount: Deckel 8 (DESIGN §3: max 8; >8 ist Asset-Validierungsfall) ---

    @Test fun `cellCount deckelt auf 8`() {
        assertEquals(8, LongPressMenu.cellCount(List(9) { it.toString() }))
        assertEquals(8, LongPressMenu.cellCount(List(8) { it.toString() }))
        assertEquals(3, LongPressMenu.cellCount(listOf("a", "b", "c")))
    }

    @Test fun `cellCount null Liste bleibt null Zellen`() {
        assertEquals(0, LongPressMenu.cellCount(emptyList()))
    }

    // --- menuWidthPx: Zellen + Polsterstreifen (Pad an beiden Außenkanten und
    //     zwischen den Zellen: n*cell + (n+1)*pad — Vorgabe 126*3 + 4*10 = 418) ---

    @Test fun `menuWidthPx drei Zellen mit pad 10 ist 418`() {
        assertEquals(418, LongPressMenu.menuWidthPx(126, 3, 10))
    }

    @Test fun `menuWidthPx null Zellen ist null`() {
        assertEquals(0, LongPressMenu.menuWidthPx(126, 0, 10))
    }

    // --- pick: Zellintervalle links geschlossen, rechts offen; außerhalb -1 ---

    @Test fun `pick liefert Zellindex fuer Innenkoordinaten`() {
        assertEquals(0, LongPressMenu.pick(0, 126, 3))
        assertEquals(0, LongPressMenu.pick(125, 126, 3))
        assertEquals(1, LongPressMenu.pick(126, 126, 3))
        assertEquals(2, LongPressMenu.pick(377, 126, 3)) // letztes Intervall inklusiv
    }

    @Test fun `pick ausserhalb und Randfaelle liefern -1`() {
        assertEquals(-1, LongPressMenu.pick(400, 126, 3)) // jenseits 3*126=378
        assertEquals(-1, LongPressMenu.pick(-5, 126, 3)) // negativ
        assertEquals(-1, LongPressMenu.pick(10, 126, 0)) // keine Zellen
        assertEquals(-1, LongPressMenu.pick(0, 0, 3)) // kaputte Zellbreite
    }

    // --- menuLeftPx: Mittelpunkt über Grundtaste, Klemmung in [0, viewWidth-menuWidth] ---

    @Test fun `menuLeftPx klemmt linken Ueberstand auf null`() {
        assertEquals(0, LongPressMenu.menuLeftPx(10, 399, 1080)) // gewünscht 10-199=-189
    }

    @Test fun `menuLeftPx klemmt rechten Ueberstand an die rechte Kante`() {
        assertEquals(1080 - 399, LongPressMenu.menuLeftPx(1070, 399, 1080))
    }

    @Test fun `menuLeftPx mittig ohne Klemmung und nie negativ`() {
        assertEquals(540 - 199, LongPressMenu.menuLeftPx(540, 399, 1080))
        assertEquals(0, LongPressMenu.menuLeftPx(0, 2000, 1080)) // breiter als View -> 0
        assertTrue(LongPressMenu.menuLeftPx(-100, 399, 1080) >= 0)
    }

    // --- T7 (v0.1.37) menuLeftClamped: Popup-links aus der MENGE der Zellen —
    //     Standard-Zentrierung über der Grundtaste, Klemmung an beide View-
    //     Kanten, KEINE Zelle außerhalb (Start-x wird geklemmt, nie abgeschnitten).
    //     Produktionsmaße Symbolebene: Zelle 126 px (48 dp), Polster 11 px
    //     (4 dp · 2,625), View 1080 px (420 dpi). ---

    @Test fun `menuLeftClamped zentriert und klemmt an beide View-Kanten`() {
        // 2 Zellen à 126 + 3 Polster à 11 = 285 px.
        assertEquals(540 - 285 / 2, LongPressMenu.menuLeftClamped(2, 540, 126, 1080, 11))
        assertEquals(0, LongPressMenu.menuLeftClamped(2, 100, 126, 1080, 11))    // links raus
        assertEquals(1080 - 285, LongPressMenu.menuLeftClamped(2, 1070, 126, 1080, 11)) // rechts
    }

    @Test fun `menuLeftClamped Menge 10 passt in KEINE Position des Views`() {
        // T7-Rot-Beleg: 10 Zellen = 10·126 + 11·11 = 1381 px > 1080 px View —
        // der Clamp kann keine Zelle mehr retten (Bestand: 0; der Adapter
        // öffnet bei menuW > width nicht). Die Invariante left+menuW ≤ View
        // schlägt für JEDE Position an.
        assertEquals(1381, LongPressMenu.menuWidthPx(126, 10, 11))
        assertTrue(LongPressMenu.menuWidthPx(126, 10, 11) > 1080)
        assertEquals(0, LongPressMenu.menuLeftClamped(10, 540, 126, 1080, 11))
    }

    @Test fun `menuWidthPx Formelgrenze - 8er-Set unterdrueckt, 7 passt`() {
        // Vc39-KDoc-Nachzug (REVIEW_V0137, LongPressMenu.MAX_CELLS):
        // menuW(n) = n·126 + (n+1)·11 — bei View 1080 px ist ein 8er-Set
        // (1107 px) breiter als der View und wird vom Guard menuW > width
        // unterdrückt; die EFFEKTIVE Obergrenze ist 7 Zellen (970 px).
        assertEquals(1107, LongPressMenu.menuWidthPx(126, 8, 11))
        assertEquals(970, LongPressMenu.menuWidthPx(126, 7, 11))
        assertTrue("8er-Set größer als View (Guard unterdrückt)",
            LongPressMenu.menuWidthPx(126, 8, 11) > 1080)
        assertTrue("7er-Set passt (effektives Maximum)",
            LongPressMenu.menuWidthPx(126, 7, 11) <= 1080)
    }

    // --- menuTopPx (F28a): mittlere Zeile — eine Zeile HOEHER; Klemmung nach oben (nie < 0) ---

    @Test fun `menuTopPx mittlere Zeile liegt eine Zeile ueber der Taste`() {
        // F28a: top=1000, Zeile 1 (mittel, lastRow=3), rowH=150, Menu 147, Abstand 21 -> 1000-150-21-147 = 682
        assertEquals(682, LongPressMenu.menuTopPx(1000, 147, 21, 150, rowIndex = 1, isFooterRow = false))
    }

    @Test fun `menuTopPx mittlere Zeile ueber dem Rand klemmt auf null`() {
        assertEquals(0, LongPressMenu.menuTopPx(100, 147, 21, 150, rowIndex = 1, isFooterRow = false)) // 100-150-21-147=-218 -> 0
        assertTrue(LongPressMenu.menuTopPx(0, 147, 21, 150, rowIndex = 1, isFooterRow = false) >= 0)
    }

    // --- F22-M: cellWins — Grundtasten-Rechteck-Tor (Richtung egal; Geraetefund
    //     <device>: oberste Reihe war mit dem Aufwaerts-Tor dauerhaft dicht) ---

    @Test fun `cellWins auf der Grundtaste gewinnt nie`() {
        assertFalse(LongPressMenu.cellWins(0, 0, 98, 144, 21))
    }

    @Test fun `cellWins knapp oberhalb der Slop-Zone gewinnt`() {
        assertTrue(LongPressMenu.cellWins(0, -(144 / 2 - 21 + 1), 98, 144, 21))
    }

    @Test fun `cellWins knapp seitlich raus gewinnt — Weg fuer die obere Reihe`() {
        assertTrue(LongPressMenu.cellWins(98 / 2 - 21 + 1, 0, 98, 144, 21))
        assertTrue(LongPressMenu.cellWins(-(98 / 2 - 21 + 1), 0, 98, 144, 21))
    }

    @Test fun `cellWins innerhalb Slop abwaerts und auf der Grenze gewinnt nicht`() {
        assertFalse(LongPressMenu.cellWins(0, 144 / 2 - 21, 98, 144, 21))
        assertFalse(LongPressMenu.cellWins(98 / 2 - 21, 0, 98, 144, 21)) // exakte Grenze: innen
    }

    // --- F22-L: stillArmed — Zonenregel statt 8-dp-Kugel (Geraetefund b/c:
    //     echtes Schieben in die Menuezeile durfte die Bewaffnung nicht brechen) ---

    private val keyW = 98 // 1080 px / 11 Tasten ~ 98 px
    private val keyH = 126
    private val slop = 21 // 8 dp @ density 420
    private val menuH = 147 // 56 dp @ density 420
    private val gap = 21 // 8 dp @ density 420

    @Test fun `stillArmed zentriert ist immer erlaubt`() {
        assertTrue(LongPressMenu.stillArmed(0, 0, keyW, keyH, slop, menuH, gap))
    }

    @Test fun `stillArmed zulaessig bis an die obere Menuegrenze`() {
        // aufwaerts erlaubt bis menuHeight + gap + slop = 189 px (y negativ = hoch)
        assertTrue(LongPressMenu.stillArmed(0, -188, keyW, keyH, slop, menuH, gap)) // randig drin
        assertTrue(LongPressMenu.stillArmed(0, -189, keyW, keyH, slop, menuH, gap)) // exakt Grenze
        assertFalse(LongPressMenu.stillArmed(0, -190, keyW, keyH, slop, menuH, gap)) // drueber hinaus
    }

    @Test fun `stillArmed seitlich grosszuegig bis halbe Tastenbreite plus slop`() {
        assertTrue(LongPressMenu.stillArmed(49 + 21 - 1, 0, keyW, keyH, slop, menuH, gap))
        assertTrue(LongPressMenu.stillArmed(-(49 + 21), 0, keyW, keyH, slop, menuH, gap)) // Grenze
        assertFalse(LongPressMenu.stillArmed(49 + 21 + 1, 0, keyW, keyH, slop, menuH, gap))
    }

    @Test fun `stillArmed abwaerts nur bis halbe Tastenhoehe plus slop`() {
        assertTrue(LongPressMenu.stillArmed(0, 63 + 21, keyW, keyH, slop, menuH, gap)) // exakt
        assertFalse(LongPressMenu.stillArmed(0, 63 + 21 + 1, keyW, keyH, slop, menuH, gap))
    }

    // F38: Zeile 0 — Menü liegt UNTEN, die vertikale Bewaffnungszone ist gespiegelt
    // (Schwenk nach UNTEN in die Menüzellen bleibt bewaffnet, aufwärts eng).
    @Test fun `F38 stillArmed menuBelowKey - abwaerts bis Menuegrenze, aufwaerts eng`() {
        assertTrue(LongPressMenu.stillArmed(0, menuH + gap + slop, keyW, keyH, slop, menuH, gap, menuBelowKey = true)) // exakt Grenze
        assertFalse(LongPressMenu.stillArmed(0, menuH + gap + slop + 1, keyW, keyH, slop, menuH, gap, menuBelowKey = true)) // drunter hinaus
        assertTrue(LongPressMenu.stillArmed(0, -(63 + 21), keyW, keyH, slop, menuH, gap, menuBelowKey = true)) // aufwaerts: halbe Taste + slop
        assertFalse(LongPressMenu.stillArmed(0, -(63 + 21 + 1), keyW, keyH, slop, menuH, gap, menuBelowKey = true))
        // Standard (Menü oben) bleibt unveraendert:
        assertTrue(LongPressMenu.stillArmed(0, -188, keyW, keyH, slop, menuH, gap))
        assertFalse(LongPressMenu.stillArmed(0, menuH + gap + slop, keyW, keyH, slop, menuH, gap))
    }

    // ---- F23c: Hint als zusaetzliche Popup-Zelle (AOSP additionalMoreKeys) ----
    // Der Hint kommt AN DAS ENDE — Sprachalternates bleiben vorn (Zelle 0 bleibt z. B. ae bei a).
    // Ueberschreitet die Liste MAX_CELLS, faellt ZUERST der Hint, nicht die Sprachalternates.

    @Test fun `withHint - ziffer kommt ans ende der alternates`() {
        assertEquals(listOf("é", "è", "3"), LongPressMenu.withHint(listOf("é", "è"), "3", 8))
    }

    @Test fun `withHint - keine Verdopplung wenn hint schon enthalten`() {
        assertEquals(listOf("1"), LongPressMenu.withHint(listOf("1"), "1", 8))
    }

    @Test fun `withHint - leerer alternates-liste mit hint ergibt eine zelle`() {
        assertEquals(listOf("1"), LongPressMenu.withHint(emptyList(), "1", 8))
    }

    @Test fun `withHint - null und blank-hint ergeben unveraendert bzw leer`() {
        assertEquals(emptyList<String>(), LongPressMenu.withHint(emptyList(), null, 8))
        assertEquals(emptyList<String>(), LongPressMenu.withHint(emptyList(), "  ", 8))
        assertEquals(listOf("é"), LongPressMenu.withHint(listOf("é"), "", 8)) // blank: nichtsanhaengen
    }

    @Test fun `withHint - max cells 7 plus hint fuellt die achte zelle`() {
        val seven = (1..7).map { "s$it" } // enthaelt den Hint "1" nicht
        val r = LongPressMenu.withHint(seven, "1", 8)
        assertEquals(8, r.size)
        assertEquals("1", r.last())
    }

    @Test fun `withHint - bei acht sprachalternates faellt der hint zuerst weg`() {
        val eight = (1..8).map { "s$it" }
        assertEquals(eight, LongPressMenu.withHint(eight, "1", 8))
    }

    // ---- F24e0: Menuzellen koennen Aktionen tragen (cursor_left / cursor_right) ----

    @Test fun `cellAction - ohne actionCells verhaelt sich alles wie bisher`() {
        assertNull(LongPressMenu.cellAction(0, emptyList()))
        assertNull(LongPressMenu.cellAction(1, emptyList()))
    }

    @Test fun `cellAction - Aktion an Zellenposition, Rest bleibt Text`() {
        val actions = listOf("cursor_left", "cursor_right")
        assertEquals("cursor_left", LongPressMenu.cellAction(0, actions))
        assertEquals("cursor_right", LongPressMenu.cellAction(1, actions))
        assertNull("Zelle 2 traegt keine Aktion", LongPressMenu.cellAction(2, actions))
    }

    @Test fun `cellAction - negative Indizes und ueber Zellzahl liefern null`() {
        val actions = listOf("cursor_left", "cursor_right")
        assertNull(LongPressMenu.cellAction(-1, actions))
        assertNull(LongPressMenu.cellAction(2, actions))
        assertNull(LongPressMenu.cellAction(99, actions))
    }

    // ---- F24e5 P1-Fix: "" = normales Textfeld (Asset-Schreibweise aus F24e1) ----
    // Frueher: "" != null -> Aktions-Zweig -> onAction("") -> kein KeyActions-Zweig ->
    // Nichts passierte (Geraetefall: Punkt-Menue committete keine Textzelle).

    @Test fun `cellAction - leerer Eintrag ist Textfeld, nur nicht-leere sind Aktionen`() {
        val actions = listOf("", "", "cursor-left")
        assertNull("Zelle 0 ist Textfeld", LongPressMenu.cellAction(0, actions))
        assertNull("Zelle 1 ist Textfeld", LongPressMenu.cellAction(1, actions))
        assertEquals("cursor-left", LongPressMenu.cellAction(2, actions))
    }

    @Test fun `cellAction - nur-Whitespace-Eintrag liefert null`() {
        assertNull(LongPressMenu.cellAction(0, listOf("   ")))
    }

    // ---- F24e5: seitliche Bewaffnung so breit wie die Palette selbst ----
    // Geraetefund: 5er-Menue (696 px) ueber der Punkt-Taste (83 px) — die Komma-
    // Zelle bei -270 px lag ausserhalb der alten keyWidth/2-Schranke und die
    // Palette oeffnete gar nicht. Loslassen aussen einer Zelle committet nie,
    // also kann die weitere Zone kein falsches Zeichen erzeugen.

    // ---- F24e5: seitliche Bewaffnung = die TATSAECHLICHE Palette (nach Clamp) ----
    // Geraetefund 08:2x: menuWidthPx-Nennbreite half um die Tastenmitte wirkte zu klein
    // (Palette 696 px startet durch den Rand-Clamp bei left=384, Tastenmitte 747 —
    // Zelle 0 bei Offset -279 blieb ausserhalb der Zone). Jetzt: Zone = Palette.

    @Test fun `armZoneOffsets - Messung Zelle 0 (-279) in der Zone, -400 ausserhalb`() {
        // Geraetemaße: centerX 747, View 1080, Palette 696 bei left 384, slop 12.
        val (minOff, maxOff) = LongPressMenu.armZoneOffsets(747, 1080, 696, 384, 12)
        assertEquals(-375, minOff)
        assertEquals(345, maxOff)
        assertTrue("Komma-Zelle (-279) muss bewaffnet bleiben",
            LongPressMenu.stillArmed(-279, 0, 83, 144, 12, 168, 20, minOff, maxOff))
        assertFalse(LongPressMenu.stillArmed(-400, 0, 83, 144, 12, 168, 20, minOff, maxOff))
        // rechte Seite: +345 inclusive Grenze, +346 draussen
        assertTrue(LongPressMenu.stillArmed(345, 0, 83, 144, 12, 168, 20, minOff, maxOff))
        assertFalse(LongPressMenu.stillArmed(346, 0, 83, 144, 12, 168, 20, minOff, maxOff))
    }

    @Test fun `armZoneOffsets - ohne Clamp liegt die Zone symmetrisch um die Mitte`() {
        val (minOff, maxOff) = LongPressMenu.armZoneOffsets(540, 1080, 696, 192, 12)
        assertEquals(192 - 12 - 540, minOff)
        assertEquals(192 + 696 + 12 - 540, maxOff)
    }

    @Test fun `stillArmed - ohne Zonengrenzen gilt altes keyWidth-Verhalten (Default)`() {
        // Default (0,0): keyWidth/2 + slop — die alten 7er-Aufrufe bleiben gueltig.
        assertFalse(LongPressMenu.stillArmed(-279, 0, 83, 144, 12, 168, 20))
        assertTrue(LongPressMenu.stillArmed(-(83 / 2 + 12), 0, 83, 144, 12, 168, 20))
    }

    // F28a/F38/T1: Zeilenposition — 3 Zweige:
    //  Zeile 0: T1-Zweizonenregel — Zone A OBEN (keyTop − gap − menuH ≥ 0,
    //            komplett im View; die Overlay-Fläche über der View-Oberkante
    //            garantiert KEINE Sichtbarkeit, F54/F34), sonst Zone B: Kante
    //            (top=0) — NIE unter der Taste (B1-Below-Fallback entfernt,
    //            Nutzerbefund vc34: Menü überdeckte Reihe 2);
    //  letzte Zeile: direkt ueber der Taste (keyTop - gap - menuH, Zustand 0.1.16);
    //  mittlere Zeilen: eine Zeile hoeher (keyTop - rowH - gap - menuH).
    @Test fun `F38 a) Zeile 0 ohne Platz oben - Kante statt unter der Taste`() {
        val top = LongPressMenu.menuTopPx(keyTop = 0, menuHeight = 60, gapPx = 8, rowHeightPx = 150, rowIndex = 0, isFooterRow = false)
        assertEquals("T1 Zone B: Tastaturkante (top=0), NICHT unter der Taste", 0, top)
        assertTrue("Menü beginnt nie oberhalb der Tastaturflaeche (>= 0)", top >= 0)
        // Zone A bleibt: MIT Platz über der Taste (keyTop 100 > gap+menuH 68)
        // liegt das r0-Menü OBEN (vollständig im View).
        val top2 = LongPressMenu.menuTopPx(keyTop = 100, menuHeight = 60, gapPx = 8, rowHeightPx = 150, rowIndex = 0, isFooterRow = false)
        assertEquals("Zone A: mit Platz oben -> keyTop - gap - menuH", 100 - 8 - 60, top2)
    }

    // ---- B1 → T1 (v0.1.35): Reihe-0-Ziffernmenü (Q..P) — Zweizonenregel ----
    // F54 a) blieb entfernt (Overlay über der View-Oberkante garantiert keine
    // Sichtbarkeit: F34-statusRow-Z + Root-Clipping). B1s Below-Fallback
    // (Position UNTER der Taste, exakt über Reihe 2) war der vc34-Nutzerbefund
    // — T1 ersetzt ihn durch Zone B: Tastaturkante (top=0), NIE unter der Taste.

    // T1-Abnahme: reale Geometrie keyTop=0,rowH=150,menuH=147,gap=21 ⇒ Kante (0),
    // NICHT -168 (Overlay unsichtbar) und NICHT 171 (unter der Taste, Reihe-2-Deckel).
    @Test fun `T1 r0 reale Geometrie - Kante statt Below-Fallback und statt minus 168`() {
        val zoneB = LongPressMenu.row0MenuBelowKey(0, 147, 21, 10, 126, 126)
        assertTrue("komplette Menüfläche NICHT im View ⇒ Zone B (Kante)", zoneB)
        val top = LongPressMenu.menuTopPx(
            keyTop = 0, menuHeight = 147, gapPx = 21, rowHeightPx = 150,
            rowIndex = 0, isFooterRow = false, padPx = 10, cellPx = 126, spaceAbovePx = 126,
        )
        assertEquals("T1 Zone B: Tastaturkante (top=0)", 0, top)
        org.junit.Assert.assertNotEquals("NICHT -168 (Overlay/Root-Clipping: unsichtbar)", -168, top)
        org.junit.Assert.assertNotEquals("NICHT 171 (unter der Taste, überdeckt Reihe 2)", 171, top)
        assertTrue("top >= 0", top >= 0)
        assertTrue("top + menuH <= 318 (vollständig in der Fläche)", top + 147 <= 318)
    }

    // T1 (Debugger-Nachschärfe): Position und Arming wechseln GEMEINSAM — das
    // r0-Menü öffnet an der Kante (top=0), NIEMALS unter der Taste ⇒ der Adapter
    // übergibt menuBelowKey=false: F22-L-Standard-Zone, Zellen per Aufwärts-/
    // Seitwärts-Schwenk erreichbar (F22-M: Richtung egal). Die gespiegelte
    // Abwärts-Zone von stillArmed bleibt als Funktionsvertrag (unten geprüft).
    @Test fun `T1 Position und Arming wechseln gemeinsam - Standard-Zone, nie unten`() {
        // Zone B (Kante) für die reale r0-Geometrie — dieselbe reine Funktion:
        assertTrue("Zone B: oben passt nicht in den View", LongPressMenu.row0MenuBelowKey(0, 147, 21, 10, 126, 126))
        assertEquals("Kante (0) statt Below-Fallback", 0, LongPressMenu.menuTopPx(0, 147, 21, 150, 0, false))
        // Standard-Zone (menuBelowKey=false): Aufwärts-Schwenk in die Menüzeile …
        assertTrue(
            "Aufwärts-Schwenk bis Menüoberkante bleibt bewaffnet",
            LongPressMenu.stillArmed(0, -(menuH + gap + slop), keyW, keyH, slop, menuH, gap, menuBelowKey = false),
        )
        assertFalse(
            "darüber hinaus ist die Geste verlassen",
            LongPressMenu.stillArmed(0, -(menuH + gap + slop + 1), keyW, keyH, slop, menuH, gap, menuBelowKey = false),
        )
        assertTrue(
            "seitlicher Schwenk bleibt bewaffnet (F22-M)",
            LongPressMenu.stillArmed(keyW / 2 + slop, -(menuH / 2), keyW, keyH, slop, menuH, gap, menuBelowKey = false),
        )
        // Funktionsvertrag der gespiegelten Zone (B1) bleibt erhalten — nur der
        // Adapter verdrahtet sie nicht mehr (T1: nie unter der Taste):
        assertTrue(
            "stillArmed-Below-Modus als Vertrag erhalten",
            LongPressMenu.stillArmed(0, menuH + gap + slop, keyW, keyH, slop, menuH, gap, menuBelowKey = true),
        )
        assertFalse(
            "darunter hinaus ist die Geste verlassen (Vertrag)",
            LongPressMenu.stillArmed(0, menuH + gap + slop + 1, keyW, keyH, slop, menuH, gap, menuBelowKey = true),
        )
        // spaceAbovePx darf die Sichtbarkeits-Entscheidung NICHT retten — auch
        // ein Riesenwert hält Zone B (kein Root-Clipping-Rettungsanker):
        assertTrue("kein Root-Clipping-Rettungsanker", LongPressMenu.row0MenuBelowKey(0, 147, 21, 10, 126, 10_000))
    }

    // B1-Rote-Test 3: mittlere Reihe/Fußzeile und Punkt-Menü — Koordinaten
    // EXAKT unverändert (Bestandsregressionsschutz des Fixes).
    @Test fun `B1 mittlere Reihe Fusszeile und Punkt-Menue exakt unverändert`() {
        // mittlere Zeile: eine Reihe höher, Klemmung auf 0 (F28a)
        assertEquals(682, LongPressMenu.menuTopPx(1000, 147, 21, 150, 1, false))
        assertEquals(0, LongPressMenu.menuTopPx(100, 147, 21, 150, 1, false))
        // Fußzeile (Punkt-Menü-Messung): direkt über der Taste, 432-21-146=265
        assertEquals(265, LongPressMenu.menuTopPx(432, 146, 21, 150, 3, true))
        // dieselbe Taste ohne Footer-Markierung: Zeilenhöhe ab (115)
        assertEquals(115, LongPressMenu.menuTopPx(432, 146, 21, 150, 3, false))
        // Klemmung: letzte Zeile über View-Top → 0
        assertEquals(0, LongPressMenu.menuTopPx(50, 146, 10, 144, 3, true))
        // F38-Bestandsfall r0 mit Platz: bleibt oben
        assertEquals(232, LongPressMenu.menuTopPx(400, 147, 21, 150, 0, false))
    }

    // B1-Rote-Test 4: oberer Pfad nur, wenn die KOMPLETTE Menüfläche im View
    // liegt (above >= 0) — Grenzfälle exakt; spaceAbovePx unbeteiligt.
    @Test fun `B1 r0 oberer Pfad nur bei kompletter Flaeche im View`() {
        // reichlich Platz: oben, auch OHNE bekannten Wurzelraum
        assertEquals(232, LongPressMenu.menuTopPx(400, 147, 21, 150, 0, false, 10, 126, 0))
        assertEquals(232, LongPressMenu.menuTopPx(400, 147, 21, 150, 0, false, 10, 126, 126))
        // exakt passende Fläche (above == 0): oberer Pfad, top == 0
        assertEquals(0, LongPressMenu.menuTopPx(168, 147, 21, 150, 0, false, 10, 126, 126))
        assertFalse(LongPressMenu.row0MenuBelowKey(168, 147, 21, 10, 126, 126))
        // 1 px daneben (above == -1): Zone B (Kante) — spaceAbovePx rettet nicht
        assertTrue(LongPressMenu.row0MenuBelowKey(167, 147, 21, 10, 126, 126))
        assertEquals("T1 Zone B: Kante (0), NICHT unter der Taste", 0, LongPressMenu.menuTopPx(167, 147, 21, 150, 0, false, 10, 126, 126))
    }

    // ---- T1 (v0.1.35, Nutzerbefund Pixel/vc34): Zweizonenregel für die
    // Buchstaben-Reihe — Menü NIE unter dem Finger / nie über der Reihe unterhalb ----

    // T1-Kernbehauptung (Debugger-Nachschärfe): r0- und r1-Menü überlappen die
    // Reihe UNTERHALB der gedrückten Taste nicht — top=0-Fall ODER
    // menuTop + menuHeight <= keyTop(Reihe darunter). Reale Pixel-Geometrie
    // (density 2.625): rowH=144 (55 dp), menuH=147 (56 dp), gap=21 (8 dp).
    @Test fun `T1 r0-r1 Menue ueberlappt die Reihe unterhalb nicht - nie unter dem Finger`() {
        val rowH = 144
        val menuH = 147
        val gap = 21
        // r0 = Q..P (keyTop 0), r1 = a..l (keyTop 144): beide öffnen an der Kante.
        for ((row, keyTop) in listOf(0 to 0, 1 to rowH)) {
            val top = LongPressMenu.menuTopPx(keyTop, menuH, gap, rowH, row, isFooterRow = false)
            val rowBelowTop = keyTop + rowH
            assertTrue("r$row: Menü nie unter der gedrückten Taste (top=$top)", top <= keyTop)
            assertTrue(
                "r$row: top=0-Fall oder Menüunterkante frei von der Reihe darunter",
                top == 0 || top + menuH <= rowBelowTop,
            )
        }
        // r0-Kernfall: Kante (0) — der B1-Below-Fallback (171, exakt über
        // Reihe 2) ist weg; Reihe 2 (y..m, Top 288) bleibt vollständig frei:
        val top0 = LongPressMenu.menuTopPx(0, menuH, gap, rowH, 0, isFooterRow = false)
        assertEquals(0, top0)
        assertTrue("Reihe 2 bleibt frei", top0 + menuH <= 2 * rowH)
        // r2/Fußzeile unverändert (Bestandsregel): eine Zeile höher bzw.
        // direkt über der Taste, Klemmung an 0.
        assertEquals(0, LongPressMenu.menuTopPx(2 * rowH, menuH, gap, rowH, 2, false))
        assertEquals(4 * rowH - gap - menuH, LongPressMenu.menuTopPx(4 * rowH, menuH, gap, rowH, 3, true))
    }

    // T1-Zweizonenregel: Zone A (oben, komplett im View) bleibt für Platz über
    // der Taste; Zone B (Kante) gilt an der Grenze — NIE unter der Taste, NIE
    // negativ (keine Overlay-/Root-Clipping-Abhängigkeit: Zone B liegt
    // KOMPLETT in der KeyboardView ⇒ 100 % sichtbar ohne clipChildren).
    @Test fun `T1 Zweizonenregel - Zone A oben im View, Zone B Kante, nie unter der Taste`() {
        // Zone A: genug Platz über der Taste (above >= 0) ⇒ direkt darüber.
        assertEquals(400 - 21 - 147, LongPressMenu.menuTopPx(400, 147, 21, 150, 0, false))
        // Zone-A-Grenze: above == 0 ⇒ top == 0 (Kante, vollständig sichtbar).
        assertEquals(0, LongPressMenu.menuTopPx(168, 147, 21, 150, 0, false))
        // Zone B: 1 px daneben ⇒ Kante (0) — NICHT unter der Taste (338).
        assertEquals(0, LongPressMenu.menuTopPx(167, 147, 21, 150, 0, false))
        // Invariante über alle r0-Fälle: Menü-Oberkante nie unter der Tasten-
        // Oberkante (nie unter dem Finger), nie negativ (kein Overlay nötig).
        for (keyTop in listOf(0, 50, 167, 168, 400)) {
            val top = LongPressMenu.menuTopPx(keyTop, 147, 21, 150, 0, false)
            assertTrue("keyTop=$keyTop: top=$top nie negativ", top >= 0)
            assertTrue("keyTop=$keyTop: top=$top nie unter der Taste", top <= keyTop)
        }
    }

    // ---- F54 (Nutzer-Auftrag 02:4x) → T1: Zeile 0 ohne Platz oben — Kante ----

    @Test fun `F54 b) T1 ohne Platz oben - Kante (0), nie negativ, spaceAbove rettet nicht`() {
        // spaceAbovePx=0 (kein Wurzelraum bekannt): oben NUR ganz im View —
        // T1 Zone B: Kante (0), NIE unter der Taste (nicht der B1-Below-Fallback).
        assertTrue("kein Platz oben bekannt -> Zone B", LongPressMenu.row0MenuBelowKey(0, 147, 21, 10, 126, 0))
        val top = LongPressMenu.menuTopPx(
            keyTop = 0, menuHeight = 147, gapPx = 21, rowHeightPx = 150,
            rowIndex = 0, isFooterRow = false, padPx = 10, cellPx = 126, spaceAbovePx = 0,
        )
        assertEquals("T1 Zone B: Kante (>= 0), nie unter der Taste", 0, top)
        assertTrue("Kante nie negativ", top >= 0)
        // Exotik: riesiges Menü — oben passt nie in den View -> Zone B (Kante).
        assertTrue("Riesenmenue -> Zone B",
            LongPressMenu.row0MenuBelowKey(0, 400, 21, 10, 126, 30))
        val top2 = LongPressMenu.menuTopPx(
            keyTop = 0, menuHeight = 400, gapPx = 21, rowHeightPx = 150,
            rowIndex = 0, isFooterRow = false, padPx = 10, cellPx = 126, spaceAbovePx = 30,
        )
        assertEquals(0, top2)
        // Klemmung der anderen Zweige bleibt: Fußzeile/mittlere NIE negativ.
        assertTrue(LongPressMenu.menuTopPx(50, 146, 10, 144, 3, true) >= 0)
        assertTrue(LongPressMenu.menuTopPx(100, 147, 21, 150, 1, false) >= 0)
    }

    @Test fun `F28a b) mittlere Zeile - eine Zeile hoeher als ueber der Taste`() {
        val top = LongPressMenu.menuTopPx(keyTop = 400, menuHeight = 60, gapPx = 8, rowHeightPx = 150, rowIndex = 1, isFooterRow = false)
        assertEquals("keyTop - rowH - gap - menuH", 400 - 150 - 8 - 60, top)
        assertTrue("hoeher als 0.1.16 (keyTop - gap - menuH)", top < 400 - 8 - 60)
        // Klaemmung an 0: mittlere Zeile oberhalb des View-Beginns.
        assertEquals(0, LongPressMenu.menuTopPx(keyTop = 100, menuHeight = 60, gapPx = 8, rowHeightPx = 150, rowIndex = 1, isFooterRow = false))
    }

    @Test fun `F28a c) letzte Zeile - direkt ueber der Taste ohne Zeilenhoehe ergibt 276`() {
        // Nutzer-Messung de-de: keyTop=432, menuHeight=146, gap=10, rowH=144, letzte Zeile
        // -> keyTop - gap - menuHeight = 432 - 10 - 146 = 276 (EINE Reihe ueber der Taste)
        val top = LongPressMenu.menuTopPx(keyTop = 432, menuHeight = 146, gapPx = 10, rowHeightPx = 144, rowIndex = 3, isFooterRow = true)
        assertEquals(276, top)
        // ohne Zeilenhoehe: tiefer als die mittlere-Zeile-Formel es legen wuerde
        assertTrue("keine Zeilenhoehe abgezogen", top > 432 - 144 - 10 - 146)
    }

    @Test fun `F28a d) letzte Zeile - klemmt auf null wenn ueber dem View-Top`() {
        assertEquals(0, LongPressMenu.menuTopPx(keyTop = 50, menuHeight = 146, gapPx = 10, rowHeightPx = 144, rowIndex = 3, isFooterRow = true)) // 50-10-146=-106 -> 0
    }

    @Test fun `F38 e) Zeile 0 - liegt IMMER in der Tastaturflaeche (kein Negativ mehr)`() {
        // T1: rowIndex==0 ohne Platz oben öffnet an der Kante (top=0) — immer
        // >= 0 (vorher -statusBarPx hinter der Statusbar bzw. B1 unter der Taste).
        val top = LongPressMenu.menuTopPx(keyTop = 0, menuHeight = 146, gapPx = 10, rowHeightPx = 144, rowIndex = 0, isFooterRow = false)
        assertEquals(0, top) // T1 Zone B: Kante statt 154 (0+144+10, unter der Taste)
        assertTrue("in der Flaeche, kein Negativ", top >= 0)
    }

    // F28a2: die Fußzeile (Space-Zeile) wird NICHT mehr über rowIndex==letzte
    // erkannt, sondern als isFooterRow übergeben — dann liegt das Menü DIREKT
    // über der Taste OHNE Zeilenhöhe. Gerätefund (18:18): r3-Punkt-Palette
    // keyTop=432, gap=21, menu=146 ⇒ 265 (davor 120 durch Zeilenhöhe).
    @Test fun `F28a2 footer isFooterRow true - menu direkt ueber der Taste (265)`() {
        val top = LongPressMenu.menuTopPx(keyTop = 432, menuHeight = 146, gapPx = 21, rowHeightPx = 150, rowIndex = 3, isFooterRow = true)
        assertEquals("432 - 21 - 146 = 265 (keine Zeilenhoehe)", 265, top)
        // Abgrenzung: dieselbe Taste OHNE Footer-Markierung würde die Zeilenhoehe
        // abziehen (120) — genau der Fehler, den F28a2 behebt.
        val middle = LongPressMenu.menuTopPx(keyTop = 432, menuHeight = 146, gapPx = 21, rowHeightPx = 150, rowIndex = 3, isFooterRow = false)
        assertEquals(115, middle) // 432 - 150 - 21 - 146 = 115
    }

    // ---- T5 (vc37, Nutzer-Urteil Emulator-Bild): r0-Menü IM STATUSBAND ----

    // (1) Band-Zone: r0 (Q..P) öffnet im Statusband — top = Band-Oberkante
    //     (−statusBandPx in View-Koordinaten); Invariante top+menuH ≤ rowBelowTop:
    //     das Menü überdeckt NIE die Reihe unter der gedrückten Taste, der
    //     Überhang menuH−band ragt nur in die oberste Reihe (Finger frei).
    //     Reale Geometrie: Band 126 px (48 dp @2,625), menuH 147, Reihe 144 px.
    @Test fun `T5 Band-Zone - r0-Menue im Statusband, Reihe darunter bleibt frei`() {
        val top = LongPressMenu.menuTopForStatusBand(
            keyTop = 0, menuHeight = 147, gapPx = 21,
            statusBandPx = 126, rowBelowTop = 144,
        )
        assertEquals("top = statusBandTop (Band-Oberkante, view-coords)", -126, top)
        assertTrue("Invariante: Menüunterkante nie unter der gedrückten Taste/Reihe darunter",
            top + 147 <= 144)
        assertTrue("Finger-Position (Reihenmitte ~72) nicht überdeckt", top + 147 < 72)
        // Guard: Riesenmenü verletzt die Invariante ⇒ Zweizonenregel (Zone B):
        assertEquals("Guard: Invariante verletzt ⇒ T1-Zweizonenregel",
            0, LongPressMenu.menuTopForStatusBand(0, 400, 21, statusBandPx = 126, rowBelowTop = 144))
        // menuTopPx-Hook: Band-Parameter reicht die Band-Zone durch (rowIndex 0):
        assertEquals(-126, LongPressMenu.menuTopPx(
            0, 147, 21, 150, 0, false, statusBandPx = 126, rowBelowTop = 144,
        ))
    }

    // (2) Zellwahl mit Zellen bei y<0: cellWins ist richtungsoffen (F22-M) —
    //     negative Offsets (Finger ÜBER dem Tastenzentrum, im Band) wählen
    //     korrekt; die Grundtaste gewinnt NIE, solange der Finger in ihr ist.
    @Test fun `T5 Zellwahl - cellWins akzeptiert negative y (Menuezellen im Band)`() {
        val keyW = 98; val keyH = 126; val slop = 21
        // Finger weit ÜBER dem Zentrum (−100 px, im Band): Grundtaste verlassen ⇒ Zelle gewinnt.
        assertTrue("negativer Offset wählt eine Zelle", LongPressMenu.cellWins(0, -100, keyW, keyH, slop))
        // Finger über dem Zentrum, ABER noch in der verkleinerten Grundtaste: KEINE Zelle.
        assertFalse("in der Grundtaste gewinnt nie eine Zelle",
            LongPressMenu.cellWins(0, -(keyH / 2 - slop - 1), keyW, keyH, slop))
        assertTrue("Grenze: exakt verlassen ⇒ Zelle",
            LongPressMenu.cellWins(0, -(keyH / 2 - slop + 1), keyW, keyH, slop))
        // horizontale Zellwahl bleibt reine x-Arithmetik (Band-x unverändert):
        assertEquals(2, LongPressMenu.pick(300, 126, 3))
        assertEquals(-1, LongPressMenu.pick(-5, 126, 3))
    }

    // (3) Regressionsschutz: Band-Höhe 0 (kein Statusband, z. B. Höhe-Grenzen)
    //     ⇒ die T1-Zweizonenregel gilt unverändert (Zone A oben im View / Zone B Kante).
    @Test fun `T5 Fallback - Bandhoehe 0 laesst die T1-Zweizonenregel unberuehrt`() {
        assertEquals("Zone B (Kante) wie T1", 0,
            LongPressMenu.menuTopForStatusBand(0, 147, 21, statusBandPx = 0, rowBelowTop = 144))
        assertEquals("Zone A wie T1 (Platz oben im View)", 232,
            LongPressMenu.menuTopForStatusBand(400, 147, 21, statusBandPx = 0, rowBelowTop = 550))
        // menuTopPx OHNE Band-Parameter (jede Bestandsaufrufstelle) bleibt byte-identisch:
        assertEquals(0, LongPressMenu.menuTopPx(0, 147, 21, 150, 0, false))
        assertEquals(232, LongPressMenu.menuTopPx(400, 147, 21, 150, 0, false))
        // Footer/mittlere Reihen wie Bestand (keine Band-Abhängigkeit):
        assertEquals(265, LongPressMenu.menuTopPx(432, 146, 21, 150, 3, true))
        assertEquals(682, LongPressMenu.menuTopPx(1000, 147, 21, 150, 1, false))
    }
}
