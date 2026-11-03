package de.voicekeyboard.keyboard

import de.voicekeyboard.layouts.KeyKind
import de.voicekeyboard.layouts.LayoutKey
import de.voicekeyboard.layouts.LayoutLayer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyboardModelTest {

    private fun layer(): LayoutLayer = LayoutLayer(
        name = "base",
        rows = listOf(
            listOf(
                LayoutKey("a", "a", 1.0, KeyKind.TEXT, "a"),
                LayoutKey("b", "b", 2.0, KeyKind.TEXT, "b"),
                LayoutKey("c", "c", 1.0, KeyKind.TEXT, "c"),
            ),
            listOf(
                LayoutKey("shift", "⇧", 1.5, KeyKind.ACTION, "shift"),
                LayoutKey("space", "␣", 2.5, KeyKind.ACTION, "space"),
            ),
        ),
    )

    // Bestandsschutz: alle Bestandstests bleiben DETERMINISTISCH (fit = null),
    // unabhängig davon, welcher andere Test zuletzt einen Fit veröffentlicht hat.
    private fun model(
        width: Float = 300f,
        keyHeight: Float = 50f,
        rowGap: Float = 6f,
        keyGap: Float = 4f,
    ) = KeyboardModel(layer(), width, keyHeightPx = keyHeight, rowGapPx = rowGap, keyGapPx = keyGap, fit = null)

    @Test
    fun `Breitensumme entspricht nutzbarer Breite pro Zeile`() {
        val width = 300f
        val gap = 4f
        val m = model(width, keyGap = gap)
        val row0 = m.keys.filter { it.top == 0f }
        val row1 = m.keys.filter { it.top != 0f }
        assertEquals(width, row0.sumOf { (it.right - it.left).toDouble() }.toFloat() + gap * (row0.size - 1), 0.01f)
        assertEquals(width, row1.sumOf { (it.right - it.left).toDouble() }.toFloat() + gap * (row1.size - 1), 0.01f)
    }

    @Test
    fun `Hoehe ist Zeilenzahl mal Zeilenhoehe plus Luecken`() {
        val m = model(keyHeight = 50f, rowGap = 6f)
        assertEquals(50f + 6f + 50f, m.heightPx, 0.01f)
    }

    @Test
    fun `Treffer in Tastenmitte liefert die richtige Taste`() {
        val m = model()
        val b = m.keys.first { it.id == "b" }
        assertEquals("b", m.keyAt(b.centerX, b.centerY)?.id)
        val space = m.keys.first { it.id == "space" }
        assertEquals("space", m.keyAt(space.centerX, space.centerY)?.id)
    }

    @Test
    fun `Treffer in der Luecke zwischen Tasten liefert die naechste Taste`() {
        val m = model()
        val a = m.keys.first { it.id == "a" }
        val b = m.keys.first { it.id == "b" }
        val gapX = (a.right + b.left) / 2f
        // In der Lücke: eine der beiden angrenzenden Tasten (nicht null).
        val hit = m.keyAt(gapX, a.centerY)
        assertEquals(true, hit != null && (hit.id == "a" || hit.id == "b"))
    }

    @Test
    fun `Punkt ausserhalb der Tastatur liefert null`() {
        val m = model()
        assertNull(m.keyAt(-50f, 25f))
        assertNull(m.keyAt(150f, 999f))
    }

    @Test
    fun `Relative Breiten werden proportional verteilt`() {
        val m = model()
        val a = m.keys.first { it.id == "a" }
        val b = m.keys.first { it.id == "b" }
        assertEquals(2f * (a.right - a.left), (b.right - b.left), 0.01f)
    }

    // ---- T20/S1: skalierte Reihen aus dem Budget — KEIN Row-Drop ----

    /**
     * S1-Pflichtfall: bei begrenztem Budget werden ALLE Reihen (und damit alle
     * Tasten-IDs) skaliert; die Rects sind vollständig (keine halb gezeichnete
     * Taste), der untere Rand bleibt im Budget, die Breitenverhältnisse bleiben
     * proportional.
     */
    @Test
    fun `Fit skaliert alle Reihen - alle IDs mit vollstaendigen Rects im Budget`() {
        // Angebot 350 px (2 Reihen): Keys-Budget = 350-126-21 = 203 px < Bedarf 294 px
        val fit = KeyboardHeight.fitHeight(
            offeredPx = 350, rows = 2, desiredRowPx = 144, desiredRowGapPx = 6,
            statusLinePx = 126, navInsetPx = 21, maxPx = 725, minKeyHeightPx = 84,
        )
        assertEquals(2, fit.rows)
        assertTrue("muss skaliert sein", fit.rowPx < 144)

        val m = KeyboardModel(
            layer(), 300f, keyHeightPx = 144f, rowGapPx = 6f, keyGapPx = 4f, fit = fit,
        )
        // ALLE Tasten-IDs erhalten (kein Row-Drop — T19 hätte Reihe 2 verworfen):
        assertEquals(setOf("a", "b", "c", "shift", "space"), m.keys.map { it.id }.toSet())
        // Vollständige Rects: jede Taste GENAU eine Reihenhöhe hoch, keine halbe Taste:
        for (k in m.keys) {
            assertEquals("Taste ${k.id} muss die volle Reihenhoehe haben", fit.rowPx.toFloat(), k.bottom - k.top, 0.01f)
        }
        // Unterer Rand im Budget: letzte Unterkante == Keys-Höhe, nicht darüber:
        assertEquals(fit.keysHeightPx.toFloat(), m.heightPx, 0.5f)
        assertTrue("unterer Rand ueberschreitet das Budget", m.heightPx <= fit.keysHeightPx + 0.5f)
        // Breitenverhaeltnisse bleiben proportional (Tastenbreite unangetastet):
        val a = m.keys.first { it.id == "a" }
        val b = m.keys.first { it.id == "b" }
        assertEquals(2f * (a.right - a.left), (b.right - b.left), 0.01f)
    }

    /** Defensive: ein Fit mit ANDERER Reihenzahl (Ebenenwechsel) wird ignoriert. */
    @Test
    fun `Fit mit abweichender Reihenzahl wird ignoriert`() {
        val wrongRows = KeyboardHeight.fitHeight(
            350, 5, 144, 6, 126, 21, 725, 84, // 5 Reihen, Layer hat 2
        )
        val m = KeyboardModel(layer(), 300f, keyHeightPx = 144f, rowGapPx = 6f, keyGapPx = 4f, fit = wrongRows)
        assertEquals("feste Masse, Fit ignoriert", 144f, m.keys.first().bottom - m.keys.first().top, 0.01f)
        assertEquals(144f + 6f + 144f, m.heightPx, 0.01f)
    }

    // F27a2: MARGIN wird wie jede Taste in die Zeilenbreite eingeplant (zählt ins
    // Σ ein) und belegt die Breite, hat aber kein Label/keine Glyphe (view-seitig
    // ignoriert Hit-Test/Draw — KeyboardView.keyAtVisible/onDraw).
    @Test
    fun `F27a2 margin zaehlt ins Zeilen-Sigma ein und belegt Breite`() {
        val m = KeyboardModel(
            LayoutLayer(
                name = "base",
                rows = listOf(
                    listOf(
                        LayoutKey("margin-left", "", 1.0, KeyKind.MARGIN, ""),
                        LayoutKey("a", "a", 1.0, KeyKind.TEXT, "a"),
                        LayoutKey("b", "b", 1.0, KeyKind.TEXT, "b"),
                        LayoutKey("margin-right", "", 1.0, KeyKind.MARGIN, ""),
                    ),
                ),
            ),
            300f, keyHeightPx = 50f, rowGapPx = 6f, keyGapPx = 4f, fit = null,
        )
        val row = m.keys.filter { it.top == 0f }
        assertEquals("MARGIN-Keys liegen im Modell als Breite drin", 4, row.size)
        val sum = row.sumOf { (it.right - it.left).toDouble() } + 4f * (row.size - 1)
        assertEquals("Summe + Gaepen = nutzbare Breite", 300f, sum.toFloat(), 0.01f)
        val margin = m.keys.first { it.id == "margin-left" }
        val a = m.keys.first { it.id == "a" }
        assertEquals("MARGIN Breite = Tastenbreite (je 1 Einheit)", a.right - a.left, margin.right - margin.left, 0.01f)
        assertEquals("", margin.label)
    }

    // F27d: D10_FOOTER.composeRow ist no-op, wenn die Zeile KEIN enter traegt.
    // Neues Footer-Zielbild (Stock-Geometrie): enter liegt in r2, die Fussreihe r3
    // hat KEIN enter — compose darf sie unveraendert lassen (keine Mic/Enter-
    // Umplatzung, keine Space->Mic-Breiten-Abgabe).
    @Test
    fun `F27d composeRow ist no-op ohne enter in der Zeile`() {
        val footer = listOf(
            LayoutKey("symbols", "?123", 1.5, KeyKind.ACTION, "layer-symbols"),
            LayoutKey("language", "\uD83C\uDF10", 1.0, KeyKind.ACTION, "language"),
            LayoutKey("emoji", "\uD83D\uDE0A", 1.0, KeyKind.ACTION, "emoji"),
            LayoutKey("space", "Space", 7.0, KeyKind.ACTION, "space"),
            LayoutKey("period", ".", 1.0, KeyKind.TEXT, ".", alternates = listOf(",", "!"), cellActions = listOf("", "cursor-left")),
            LayoutKey("mic", "\uD83C\uDFA4", 1.5, KeyKind.ACTION, "mic"),
        )
        val composed = D10_FOOTER.compose(listOf(footer))
        assertEquals("Zeilenanzahl unveraendert", 1, composed.size)
        val out = composed[0]
        assertEquals("Tastenanzahl unveraendert", footer.size, out.size)
        assertEquals("Reihenfolge/IDs unveraendert", footer.map { it.id }, out.map { it.id })
        assertEquals("Breiten unveraendert", footer.map { it.width }, out.map { it.width })
        assertEquals("mic bleibt 1.5 (bekommt ohne enter nichts)", 1.5, out.first { it.id == "mic" }.width, 0.0)
        assertEquals("space bleibt 7 (gibt ohne enter nichts ab)", 7.0, out.first { it.id == "space" }.width, 0.0)
    }
}
