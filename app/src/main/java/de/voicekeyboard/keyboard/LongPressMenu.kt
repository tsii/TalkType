package de.voicekeyboard.keyboard

import kotlin.math.abs

/**
 * F22 (docs/DESIGN_KEY_POPUP.md §1/§6): reine Geometrie-/Auswahl-Naht des
 * Langdruckmenüs — KEIN Android-Typ, analog [KeySurface]; der KeyboardView
 * bleibt Adapter für Touch, Timer und Zeichnen.
 *
 * Maße (density 420, 2,625 px/dp): Zelle 48 dp = 126 px, Menühöhe 56 dp = 147 px,
 * Abstand zur Grundtaste 8 dp = 21 px. Das Grundzeichen bleibt auf seiner echten
 * Taste — das Menü hat NUR die Alternativen-Zellen (Asset-Deckel 8, keine neunte
 * Zelle; effektive View-Obergrenze s. [MAX_CELLS]).
 * Zellintervalle: links geschlossen, rechts offen; außerhalb -> keine Auswahl.
 */
object LongPressMenu {

    /**
     * DESIGN §3: höchstens acht Alternativen-Zellen (>8 ist Validierungsfall des
     * Assets — der Deckel selbst bleibt 8). Vc39-Realitätsnachzug (REVIEW_V0137):
     * der Renderer (KeyboardView.openLongPressMenu) unterdrückt ZUSÄTZLICH Mengen,
     * deren Menü nicht in die View passt — Formel: menuW(n) = n·cell + (n+1)·pad.
     * Bei 1080 px View, cell = 126 px, pad = 11 px (density 2,625) gilt:
     * n=8 → 8·126 + 9·11 = 1107 px > 1080 ⇒ Guard menuW > width unterdrückt,
     * effektiv max 7 (n=7 → 7·126 + 8·11 = 970 px passt). Ändert Dichte, View-
     * Breite oder Pad, verschiebt sich diese Grenze — Formel hier halten, damit
     * der Drift sichtbar bleibt.
     */
    const val MAX_CELLS = 8

    /** Zellanzahl mit Deckel 8 — die Liste selbst wird hier nicht still gekürzt validiert. */
    fun cellCount(alternates: List<String>): Int = minOf(alternates.size, MAX_CELLS)

    /**
     * F23c (AOSP-Beleg rowkeys_qwertz1.xml: keyHintLabel + additionalMoreKeys):
     * Der Hint wird zusaetzliche Popup-Zelle, damit die Ziffer per Langdruck
     * erreichbar ist. Regeln: der Hint kommt AN DAS ENDE (Sprachalternates
     * bleiben vorn, Zelle 0 verschiebt sich nicht); keine Verdopplung; null/
     * blank entfaellt; ueber [maxCells] faellt ZUERST der Hint, nie eine
     * Sprachalternate.
     */
    fun withHint(alternates: List<String>, hint: String?, maxCells: Int): List<String> {
        val h = hint?.trim().orEmpty()
        if (h.isEmpty() || h in alternates) return alternates
        return if (alternates.size >= maxCells) alternates else alternates + h
    }

    /**
     * F24e0 (DESIGN_KEY_POPUP.md §7, Nutzerentscheidung 07:2x): eine Menuzelle kann
     * eine AKTION tragen statt Text zu committen (z. B. cursor_left/cursor_right aus
     * der Fusszeile). [actionCells] ist die optionale, parallel zu den Zellen
     * indizierte Aktionsliste (kurz = die restlichen Zellen sind Text); null bedeutet
     * "wie bisher: Text committen". Ein leerer/nur-Whitespace-Eintrag ist wie kein
     * Eintrag (F24e5: "" = normales Textfeld, Asset-Schreibweise aus F24e1; waere er
     * eine "Aktion" mit leerem Namen, fielde er ins onAction-when und es passierte
     * nichts — Geraetefall Punkt-Menue). Reine Logik, keine Android-Typen.
     */
    fun cellAction(index: Int, actionCells: List<String>): String? =
        actionCells.getOrNull(index)?.takeIf { it.isNotBlank() }

    /**
     * Menübreite in px: n Zellen plus Polsterstreifen der Breite [padPx] an beiden
     * Außenkanten UND zwischen den Zellen: `n*cellPx + (n+1)*padPx`
     * (Vorgabe: 126·3 + 10·4 = 418). n ≤ 0 -> 0.
     */
    fun menuWidthPx(cellPx: Int, n: Int, padPx: Int): Int =
        if (n <= 0) 0 else cellPx * n + padPx * (n + 1)

    /**
     * T7 (v0.1.37, Nutzerbefund Symbolebene): Popup-links aus der MENGE der
     * Zellen — Standard-Zentrierung über der Grundtaste ([keyCenterX]), dann
     * Klemmung des Start-x an beide View-Kanten, so dass KEINE Zelle außerhalb
     * des Views läuft (Zellen werden NIE abgeschnitten). Rein geometrisch:
     * Menübreite = [menuWidthPx] aus [menuCount] Zellen; Klemmung wie
     * [menuLeftPx] (breiter als der View -> 0; Pass-Fit entscheidet der
     * Adapter, der dann nicht öffnet). Rot-Beleg Invariante: 10 Zellen
     * (126 px + 11 px Polster) sind 1381 px > 1080 px View — KEINE Position
     * hält alle Zellen im View.
     */
    fun menuLeftClamped(
        menuCount: Int,
        keyCenterX: Int,
        cellW: Int,
        viewWidth: Int,
        padPx: Int,
    ): Int = menuLeftPx(keyCenterX, menuWidthPx(cellW, menuCount, padPx), viewWidth)

    /**
     * Menü-links: mittig über der Grundtaste ([keyCenterX]), geklemmt an
     * `[0, viewWidth - menuWidth]` — nie negativ, auch wenn das Menü breiter als
     * die View wäre (dann 0; Pass-Fit entscheidet der Adapter).
     */
    fun menuLeftPx(keyCenterX: Int, menuWidth: Int, viewWidth: Int): Int {
        if (menuWidth >= viewWidth) return 0
        return (keyCenterX - menuWidth / 2).coerceIn(0, viewWidth - menuWidth)
    }

    /**
     * Zeilenposition (F28a + T1-Zweizonenregel):
     *  - Zeile 0 (Buchstabenreihe Q..P): **T1-Zweizonenregel** (kein Band bekannt):
     *    Zone A komplett OBERHALB der Taste im View ([row0MenuBelowKey] false),
     *    sonst Zone B Kante (top=0) — NIE unter der Taste. **T5 (vc37)**: mit
     *    bekanntem Statusband ([statusBandPx] > 0, Adapter: eigene top-Kante im
     *    Stack) öffnet das r0-Menü stattdessen IM BAND (menuTopForStatusBand) —
     *    Nutzer-Willen: eine Zeile über der Buchstabentaste, dort wo
     *    Statuszeile+🐞 sitzen; Invariante: nie unter die Reihe der Taste.
     *  - Fußzeile: direkt über der Taste, Klemmung auf 0 (F28a2).
     *  - mittlere Zeilen: eine Zeile höher, Klemmung auf 0 (F28a).
     */
    fun menuTopPx(
        keyTop: Int,
        menuHeight: Int,
        gapPx: Int,
        rowHeightPx: Int,
        rowIndex: Int,
        isFooterRow: Boolean,
        padPx: Int = 10,
        cellPx: Int = 126,
        spaceAbovePx: Int = 0,
        /** T5 (vc37): Bandhöhe über der View-Oberkante (Statuszeile+🐞); 0 = kein
         *  Band ⇒ T1-Zweizonenregel. Default 0 hält ALLE Bestandsaufrufe byte-identisch. */
        statusBandPx: Int = 0,
        /** T5 (vc37): Oberkante der Reihe unter der gedrückten Taste — Invariante
         *  der Band-Zone (top + menuH ≤ rowBelowTop). Default: unverbindlich. */
        rowBelowTop: Int = Int.MAX_VALUE,
    ): Int = when {
        rowIndex == 0 -> menuTopForStatusBand(keyTop, menuHeight, gapPx, statusBandPx, rowBelowTop)
        isFooterRow -> (keyTop - gapPx - menuHeight).coerceAtLeast(0)
        else -> (keyTop - rowHeightPx - gapPx - menuHeight).coerceAtLeast(0)
    }

    /**
     * T5 (vc37, Nutzer-Urteil Emulator-Bild): Position des r0-Langdruckmenüs
     * (oberste Reihe, Q..P) — **Band-Zone**: das Menü öffnet IM STATUSBAND, eine
     * Zeile über der Buchstabentaste (dort wo Statuszeile+🐞 sitzen; das Band
     * gehört zum Root/Stack, NICHT zur KeyboardView — top = −statusBandPx in
     * View-Koordinaten). [statusBandPx] = Bandhöhe über der View-Oberkante
     * (Adapter: eigene top-Kante im Stack); 0 = kein Band ⇒ T1-Zweizonenregel
     * unverändert (Regressionsschutz). [rowBelowTop] = Oberkante der Reihe
     * UNTER der gedrückten (Invariante top + menuH ≤ rowBelowTop — das Menü
     * überdeckt nie die Reihe unter der Taste; der Überhang menuH − statusBandPx
     * ragt nur in die oberste Reihe). Guard: verletzt die Band-Zone die
     * Invariante (Riesenmenü), fällt sie auf die Zweizonenregel zurück.
     */
    fun menuTopForStatusBand(
        keyTop: Int,
        menuHeight: Int,
        gapPx: Int,
        statusBandPx: Int,
        rowBelowTop: Int,
    ): Int {
        // T5 Band-Zone: top = statusBandTop (= −statusBandPx im View-System) — nur
        // wenn die Invariante hält (Menüunterkante nie unter die Reihe der Taste).
        val bandTop = -statusBandPx
        if (statusBandPx > 0 && bandTop + menuHeight <= rowBelowTop) return bandTop
        // T5-Fallback (3) / Bestand: T1-Zweizonenregel — Zone A oben im View,
        // sonst Zone B Kante (nie unter der Taste).
        return if (row0MenuBelowKey(keyTop, menuHeight, gapPx, 10, 126, 0)) {
            0
        } else {
            keyTop - gapPx - menuHeight
        }
    }

    /**
     * T1: reine r0-Zonen-Entscheidung (Bestandsunterschrift B1 unverändert) —
     * true ⇒ oben passt NICHT in den View (above = keyTop − gap − menuHeight
     * < 0) ⇒ **Zone B: Kante (top=0)**. B1 lief in diesem Fall die Position
     * UNTER der Taste (vc34-Nutzerbefund: exakt über Reihe 2); T1 ersetzt den
     * Below-Fallback durch die Kante. Der Adapter leitet die Arming-Zone aus
     * DEMSELBEN Ergebnis ab (gemeinsamer Wechsel: das Menü liegt nie unter der
     * Taste ⇒ [stillArmed] läuft im F22-L-Standard, menuBelowKey=false).
     * [padPx]/[cellPx]/[spaceAbovePx] bleiben aus Signatur-Stabilität erhalten,
     * beeinflussen die Entscheidung aber NICHT (Overlay über der View-Oberkante
     * garantiert keine Sichtbarkeit, F54/F34).
     */
    fun row0MenuBelowKey(
        keyTop: Int,
        menuHeight: Int,
        gapPx: Int,
        padPx: Int,
        cellPx: Int,
        spaceAbovePx: Int,
    ): Boolean = keyTop - gapPx - menuHeight < 0

    /**
     * Auswahl: [offsetX] ist die waagrechte Innenkoordinate relativ zum Beginn
     * der ersten Zelle. Zellintervalle links geschlossen, rechts offen;
     * außerhalb (negativ, jenseits n·cellPx, n ≤ 0, cellPx ≤ 0) -> -1.
     */
    fun pick(offsetX: Int, cellPx: Int, n: Int): Int {
        if (n <= 0 || cellPx <= 0 || offsetX < 0) return -1
        val idx = offsetX / cellPx
        return if (idx < n) idx else -1
    }

    /**
     * F22-M (Geraetefund <device>, oberste Reihe): ersetzt das Aufwaerts-Tor
     * F22-K. Eine Zelle gewinnt erst, wenn der Finger die Grundtaste WIRKLICH
     * verlassen hat — in WELCHE Richtung ist egal: innerhalb der um [slopPx]
     * verkleinerten Grundtaste (Offset vom Tastenmittelpunkt) gewinnt NIE eine
     * Zelle. Grund der Richtungs-Offenheit: bei Reihe 0 klemmt [menuTopPx] das
     * Menü an die View-Oberkante; ein Aufwaerts-Tor y < keyTop - slop < 0 waere
     * fuer die obere Reihe dauerhaft unerreichbar (0 Zeichen in allen Zellen).
     * Halten ohne Bewegung = innerhalb der Grundtaste = Grundzeichen — der
     * F22-K-Geraetefund („è“ ohne Schieben) bleibt damit behoben.
     */
    fun cellWins(
        offsetXFromKeyCenter: Int,
        offsetYFromKeyCenter: Int,
        keyWidth: Int,
        keyHeight: Int,
        slopPx: Int,
    ): Boolean = !(abs(offsetXFromKeyCenter) <= keyWidth / 2 - slopPx &&
        abs(offsetYFromKeyCenter) <= keyHeight / 2 - slopPx)

    /**
     * F24e5-NACHSCHLAG: seitliche Bewaffnungs-Grenzen als Offset zur down-Position.
     * Die Zone ist die TATSAECHLICHE Palette (nach dem Rand-Clamp aus [menuLeftPx]),
     * nicht die halbe Nennbreite um die Tastenmitte — bei angeschobenen Paletten
     * (left=384 statt 399) lag sonst die aeusserste Zelle ausserhalb der Zone und
     * die Palette oeffnete per Schieben nie. Rueckgabe (minOffsetX, maxOffsetX).
     */
    fun armZoneOffsets(centerX: Int, width: Int, menuWidthPx: Int, menuLeftPx: Int, slopPx: Int): Pair<Int, Int> =
        (menuLeftPx - slopPx - centerX) to (menuLeftPx + menuWidthPx + slopPx - centerX)

    /**
     * F22-L (Geraetefund <device> b/c): Bewaffnungs-ZONE statt 8-dp-Kugel.
     * Die 8-dp-Kugel aus D12 §2 killte fast jeden Langdruck, bevor die 450-ms-
     * Schwelle lief — Stock/AOSP erlaubt jedoch: halten, DANN in die Menuezeile
     * schieben. Die Menuezeile liegt UEBER der Grundtaste, daher ist Aufwaerts-
     * Schieben der vorgesehene Weg, kein Abbruch. Zone relativ zum DOWN-Punkt
     * (y negativ = oben): seitlich |offsetX| ≤ keyWidth/2 + slopPx (grosszuegig —
     * 21 px erreicht jeder Finger in < 100 ms), aufwaerts bis menuHeightPx +
     * gapPx + slopPx (nie enger als das spaetere Popup), abwaerts nur bis
     * keyHeight/2 + slopPx; sonst false = Geste wirklich verlassen.
     */
    fun stillArmed(
        offsetX: Int,
        offsetY: Int,
        keyWidth: Int,
        keyHeight: Int,
        slopPx: Int,
        menuHeightPx: Int,
        gapPx: Int,
        /**
         * F24e5-NACHSCHLAG: seitliche Offset-Grenzen aus [armZoneOffsets] (die
         * tatsaechliche, ggf. an den Rand geclimte Palette + slop). (0, 0) = nicht
         * gesetzt -> altes Verhalten (halbe Tastenbreite + slop). Loslassen
         * AUSSERHALB einer Zelle committet ohnehin nie (KeyboardView
         * popupSelectionAt / cellWins), es kann kein falsches Zeichen entstehen.
         */
        minOffsetX: Int = 0,
        maxOffsetX: Int = 0,
        /** F38: Menü liegt UNTER der Grundtaste (Zeile 0) — vertikale Zone
         *  gespiegelt: abwaerts bis menuHeightPx + gapPx + slopPx (Schwenk ins
         *  Menü bleibt bewaffnet), aufwaerts nur keyHeight/2 + slopPx. */
        menuBelowKey: Boolean = false,
    ): Boolean {
        val halfW = keyWidth / 2 + slopPx
        val inX = if (minOffsetX == 0 && maxOffsetX == 0) {
            offsetX >= -halfW && offsetX <= halfW
        } else {
            offsetX >= minOffsetX && offsetX <= maxOffsetX
        }
        if (!inX) return false
        if (menuBelowKey) { // F38: Menü unter der Taste (Zeile 0) — Zone gespiegelt
            if (offsetY > menuHeightPx + gapPx + slopPx) return false // unter dem Menue
            if (offsetY < -(keyHeight / 2 + slopPx)) return false       // aufwaerts verlassen
        } else {
            if (offsetY < -(menuHeightPx + gapPx + slopPx)) return false // ueber dem Menue
            if (offsetY > keyHeight / 2 + slopPx) return false           // abwaerts verlassen
        }
        return true
    }
}
