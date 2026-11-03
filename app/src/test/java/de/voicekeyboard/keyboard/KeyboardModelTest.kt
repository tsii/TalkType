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

    // Stock protection: all stock tests stay DETERMINISTIC (fit = null),
    // regardless of which other test published a fit last.
    private fun model(
        width: Float = 300f,
        keyHeight: Float = 50f,
        rowGap: Float = 6f,
        keyGap: Float = 4f,
    ) = KeyboardModel(layer(), width, keyHeightPx = keyHeight, rowGapPx = rowGap, keyGapPx = keyGap, fit = null)

    @Test
    fun `width sum equals the usable width per row`() {
        val width = 300f
        val gap = 4f
        val m = model(width, keyGap = gap)
        val row0 = m.keys.filter { it.top == 0f }
        val row1 = m.keys.filter { it.top != 0f }
        assertEquals(width, row0.sumOf { (it.right - it.left).toDouble() }.toFloat() + gap * (row0.size - 1), 0.01f)
        assertEquals(width, row1.sumOf { (it.right - it.left).toDouble() }.toFloat() + gap * (row1.size - 1), 0.01f)
    }

    @Test
    fun `height is row count times row height plus gaps`() {
        val m = model(keyHeight = 50f, rowGap = 6f)
        assertEquals(50f + 6f + 50f, m.heightPx, 0.01f)
    }

    @Test
    fun `hit in the key center returns the right key`() {
        val m = model()
        val b = m.keys.first { it.id == "b" }
        assertEquals("b", m.keyAt(b.centerX, b.centerY)?.id)
        val space = m.keys.first { it.id == "space" }
        assertEquals("space", m.keyAt(space.centerX, space.centerY)?.id)
    }

    @Test
    fun `hit in the gap between keys returns the next key`() {
        val m = model()
        val a = m.keys.first { it.id == "a" }
        val b = m.keys.first { it.id == "b" }
        val gapX = (a.right + b.left) / 2f
        // in the gap: one of the two adjacent keys (not null).
        val hit = m.keyAt(gapX, a.centerY)
        assertEquals(true, hit != null && (hit.id == "a" || hit.id == "b"))
    }

    @Test
    fun `point outside the keyboard returns null`() {
        val m = model()
        assertNull(m.keyAt(-50f, 25f))
        assertNull(m.keyAt(150f, 999f))
    }

    @Test
    fun `relative widths are distributed proportionally`() {
        val m = model()
        val a = m.keys.first { it.id == "a" }
        val b = m.keys.first { it.id == "b" }
        assertEquals(2f * (a.right - a.left), (b.right - b.left), 0.01f)
    }

    // ---- T20/S1: scaled rows from the budget — NO row drop ----

    /**
     * S1 mandatory case: with a limited budget ALL rows (and thereby all key
     * IDs) are scaled; the rects are complete (no half-drawn key), the lower
     * edge stays within the budget, the width ratios remain proportional.
     */
    @Test
    fun `fit scales all rows - all IDs with complete rects within the budget`() {
        // offer 350 px (2 rows): keys budget = 350-126-21 = 203 px < demand 294 px
        val fit = KeyboardHeight.fitHeight(
            offeredPx = 350, rows = 2, desiredRowPx = 144, desiredRowGapPx = 6,
            statusLinePx = 126, navInsetPx = 21, maxPx = 725, minKeyHeightPx = 84,
        )
        assertEquals(2, fit.rows)
        assertTrue("must be scaled", fit.rowPx < 144)

        val m = KeyboardModel(
            layer(), 300f, keyHeightPx = 144f, rowGapPx = 6f, keyGapPx = 4f, fit = fit,
        )
        // ALL key IDs kept (no row drop — T19 would have discarded row 2):
        assertEquals(setOf("a", "b", "c", "shift", "space"), m.keys.map { it.id }.toSet())
        // complete rects: every key EXACTLY one row height tall, no half key:
        for (k in m.keys) {
            assertEquals("key ${k.id} must have the full row height", fit.rowPx.toFloat(), k.bottom - k.top, 0.01f)
        }
        // lower edge within the budget: last bottom edge == keys height, not above it:
        assertEquals(fit.keysHeightPx.toFloat(), m.heightPx, 0.5f)
        assertTrue("the bottom edge exceeds the budget", m.heightPx <= fit.keysHeightPx + 0.5f)
        // width ratios remain proportional (key width untouched):
        val a = m.keys.first { it.id == "a" }
        val b = m.keys.first { it.id == "b" }
        assertEquals(2f * (a.right - a.left), (b.right - b.left), 0.01f)
    }

    /** Defensive: a fit with a DIFFERENT row count (layer switch) is ignored. */
    @Test
    fun `fit with a differing row count is ignored`() {
        val wrongRows = KeyboardHeight.fitHeight(
            350, 5, 144, 6, 126, 21, 725, 84, // 5 rows, layer has 2
        )
        val m = KeyboardModel(layer(), 300f, keyHeightPx = 144f, rowGapPx = 6f, keyGapPx = 4f, fit = wrongRows)
        assertEquals("feste Masse, Fit ignoriert", 144f, m.keys.first().bottom - m.keys.first().top, 0.01f)
        assertEquals(144f + 6f + 144f, m.heightPx, 0.01f)
    }

    // F27a2: MARGIN is budgeted into the row width like any key (counts into
    // the Σ) and occupies the width, but has no label/no glyph (view side,
    // hit test/draw ignore it — KeyboardView.keyAtVisible/onDraw).
    @Test
    fun `F27a2 margin counts into the row sigma and occupies width`() {
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
        assertEquals("MARGIN keys count as width in the model", 4, row.size)
        val sum = row.sumOf { (it.right - it.left).toDouble() } + 4f * (row.size - 1)
        assertEquals("sum + gaps = usable width", 300f, sum.toFloat(), 0.01f)
        val margin = m.keys.first { it.id == "margin-left" }
        val a = m.keys.first { it.id == "a" }
        assertEquals("MARGIN width = key width (1 unit each)", a.right - a.left, margin.right - margin.left, 0.01f)
        assertEquals("", margin.label)
    }

    // F27d: D10_FOOTER.composeRow is a no-op if the row carries NO enter.
    // New footer target image (stock geometry): enter sits in r2, the footer
    // row r3 has NO enter — compose must leave it unchanged (no mic/enter
    // relocation, no space->mic width transfer).
    @Test
    fun `F27d composeRow is a no-op without enter in the row`() {
        val footer = listOf(
            LayoutKey("symbols", "?123", 1.5, KeyKind.ACTION, "layer-symbols"),
            LayoutKey("language", "\uD83C\uDF10", 1.0, KeyKind.ACTION, "language"),
            LayoutKey("emoji", "\uD83D\uDE0A", 1.0, KeyKind.ACTION, "emoji"),
            LayoutKey("space", "Space", 7.0, KeyKind.ACTION, "space"),
            LayoutKey("period", ".", 1.0, KeyKind.TEXT, ".", alternates = listOf(",", "!"), cellActions = listOf("", "cursor-left")),
            LayoutKey("mic", "\uD83C\uDFA4", 1.5, KeyKind.ACTION, "mic"),
        )
        val composed = D10_FOOTER.compose(listOf(footer))
        assertEquals("line count unchanged", 1, composed.size)
        val out = composed[0]
        assertEquals("Tastenanzahl unveraendert", footer.size, out.size)
        assertEquals("order/IDs unchanged", footer.map { it.id }, out.map { it.id })
        assertEquals("Breiten unveraendert", footer.map { it.width }, out.map { it.width })
        assertEquals("mic stays 1.5 (gets nothing without enter)", 1.5, out.first { it.id == "mic" }.width, 0.0)
        assertEquals("space stays 7 (gives nothing away without enter)", 7.0, out.first { it.id == "space" }.width, 0.0)
    }
}
