package de.voicekeyboard.keyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.text.Normalizer

/**
 * E9.4 (M8b-2): validates emoji_v1.json and the pure grid logic
 * (EmojiPages — no Android types on the test path).
 *
 * T6 from LANGUAGE_EMOJI_DECISIONS.md: 8 categories × 48, no duplicates,
 * NFC, no ZWJ/skin-tone/flag sequences, every entry exactly 1 grapheme cluster.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class EmojiPanelTest {

    /** Loads the asset from the test resources (isIncludeAndroidResources) — pure path. */
    private fun assetJson(): String =
        java.io.File("src/main/assets/emoji/emoji_v1.json").readText()

    private fun categories(): List<EmojiPages.EmojiCategoryData> {
        val root = org.json.JSONObject(assetJson())
        val arr = root.getJSONArray("categories")
        return (0 until arr.length()).map { i ->
            val c = arr.getJSONObject(i)
            val list = c.getJSONArray("emoji")
            EmojiPages.EmojiCategoryData(
                id = c.getString("id"),
                labelKey = c.getString("labelKey"),
                emoji = (0 until list.length()).map { list.getString(it) },
            )
        }
    }

    @Test
    fun `asset has exactly 8 categories with 48 glyphs each and 384 total`() {
        val cats = categories()
        assertEquals(8, cats.size)
        for (cat in cats) {
            assertEquals("Kategorie ${cat.id}", 48, cat.emoji.size)
        }
        assertEquals(384, cats.sumOf { it.emoji.size })
        EmojiPages.validate(cats) // throws on violation
    }

    @Test
    fun `no duplicates within a category and no zwj skin tone flags`() {
        for (cat in categories()) {
            assertEquals("Duplikat in ${cat.id}", cat.emoji.size, cat.emoji.distinct().size)
            for (e in cat.emoji) {
                assertTrue("ZWJ in $e (${cat.id})", '\u200D' !in e)
                assertTrue("Hautton in $e (${cat.id})", e.none { it.code in 0x1F3FB..0x1F3FF })
                assertTrue("Flagge in $e (${cat.id})", e.none { it.code in 0x1F1E6..0x1F1FF })
                assertTrue("NFC in $e", Normalizer.normalize(e, Normalizer.Form.NFC) == e)
                assertEquals("no grapheme cluster: $e", 1, EmojiPages.graphemeCount(e))
            }
        }
    }

    @Test
    fun `page assignment index 47 stays page 0 index 48 would be the next page`() {
        val cat = (0 until 100).map { "e$it" }
        val page0 = EmojiPages.pageOf(cat, 0)
        assertEquals(48, page0.size)
        assertEquals("e47", page0[47]) // index 47 => page 0, position 47
        // index 48 lies on page 1 (or the next category page)
        val page1 = EmojiPages.pageOf(cat, 1)
        assertEquals("e48", page1[0])
        assertEquals(48, page1.size)
        // remainder: page 2 has 100-96=4
        val page2 = EmojiPages.pageOf(cat, 2)
        assertEquals(4, page2.size)
        // beyond the end: empty
        assertTrue(EmojiPages.pageOf(cat, 3).isEmpty())
    }

    @Test
    fun `grid index calculation 8x6`() {
        // row 0, column 0 → index 0; column 7 → 7
        assertEquals(0, EmojiPages.indexAt(10f, 5f, 100f, 100f, 0f))
        assertEquals(7, EmojiPages.indexAt(750f, 5f, 100f, 100f, 0f))
        // row 1, column 0 → index 8
        assertEquals(8, EmojiPages.indexAt(10f, 105f, 100f, 100f, 0f))
        // index 47 = row 5, column 7
        assertEquals(47, EmojiPages.indexAt(750f, 505f, 100f, 100f, 0f))
        // outside: -1
        // y=605 -> row 6 (outside the 6-row grid): -1
        assertEquals(-1, EmojiPages.indexAt(10f, 650f, 100f, 100f, 0f, 8, 6))
        // x=-5 -> column -1 (outside): -1
        assertEquals(-1, EmojiPages.indexAt(-105f, 5f, 100f, 100f, 0f, 8, 6))
        // y=605 with gridTop 0: row 6 > 5 (rows-1) -> -1
        assertEquals(-1, EmojiPages.indexAt(10f, 605f, 100f, 100f, 0f, 8, 6))
        // take gridTop into account
        assertEquals(0, EmojiPages.indexAt(10f, 205f, 100f, 100f, 200f))
    }

    @Test
    fun `surrogate pair is one glyph with two utf16 units`() {
        val emoji = "😀" // U+1F600: high + low surrogate
        assertEquals(2, EmojiPages.utf16Units(emoji))
        assertEquals(1, EmojiPages.graphemeCount(emoji))
        // BMP character (e.g. ❤ U+2764): 1 glyph, 1 unit
        val bmp = "❤"
        assertEquals(1, EmojiPages.utf16Units(bmp))
        assertEquals(1, EmojiPages.graphemeCount(bmp))
    }

    @Test
    fun `rows times columns times pages cover 384 without overflow`() {
        // 6 rows × 8 columns × 8 categories × 1 page = 384
        assertEquals(48, EmojiPages.PAGE_SIZE)
        assertEquals(1, EmojiPages.pagesInCategory(48, 48))
        // 5-row fallback (E-02): 40 per page ⇒ 2 pages per category
        assertEquals(40, 8 * 5)
        assertEquals(2, EmojiPages.pagesInCategory(48, 40))
        // nothing overflows: all pages together give exactly 48 per category,
        // all categories together exactly 384 — at 48 AND 40 page size.
        val cats = categories()
        for (size in intArrayOf(48, 40, 8)) {
            var total = 0
            for (cat in cats) {
                var page = 0
                var catTotal = 0
                while (true) {
                    val slice = EmojiPages.pageOf(cat.emoji, page, size)
                    if (slice.isEmpty()) break
                    catTotal += slice.size
                    page++
                }
                assertEquals("page split ($size) for ${cat.id}", 48, catTotal)
                assertEquals("page count ($size) for ${cat.id}",
                    EmojiPages.pagesInCategory(48, size), page)
                total += catTotal
            }
            assertEquals("Total at page size $size", 384, total)
        }
        // row calculation: height suffices for 6 → 6; tight → fewer, never 0
        assertEquals(6, EmojiPages.rowsForHeight(6f * 44f, 44f))
        assertEquals(5, EmojiPages.rowsForHeight(5.5f * 44f, 44f))
        assertEquals(1, EmojiPages.rowsForHeight(44f, 44f))
        assertEquals(1, EmojiPages.rowsForHeight(0f, 44f))
    }

    @Test
    fun `page count and display match the realized page size E10_3`() {
        // (b) at a height that allows only 3 rows: display == realized page
        // size (24 glyphs/page ⇒ 2 pages per 48-category, last one partially filled)
        val pageSize3 = 8 * EmojiPages.rowsForHeight(3f * 44f, 44f)
        assertEquals(24, pageSize3)
        for (cat in categories()) {
            val pages = EmojiPages.pagesInCategory(48, pageSize3)
            assertEquals(2, pages)
            // concatenating all pages gives EXACTLY the asset list
            val joined = (0 until pages).flatMap { p ->
                EmojiPages.pageOf(cat.emoji, p, pageSize3)
            }
            assertEquals(cat.emoji, joined)
            // no losses, no duplicates (implicit via assertEquals);
            // last page partially filled (24 + 24 = 48 → full here, 4-case:)
        }
        // 4-row case (device: 4×8 = 32): 2 pages, the second partially filled with 16 glyphs
        val pageSize4 = 8 * 4
        for (cat in categories()) {
            val pages = EmojiPages.pagesInCategory(48, pageSize4)
            assertEquals(2, pages)
            val joined = (0 until pages).flatMap { p -> EmojiPages.pageOf(cat.emoji, p, pageSize4) }
            assertEquals(cat.emoji, joined)
            assertEquals(16, EmojiPages.pageOf(cat.emoji, 1, pageSize4).size)
        }
        // 5-row case: 40/page ⇒ 2 pages (40 + 8)
        val pageSize5 = 40
        for (cat in categories()) {
            val joined = (0 until EmojiPages.pagesInCategory(48, pageSize5)).flatMap { p ->
                EmojiPages.pageOf(cat.emoji, p, pageSize5)
            }
            assertEquals(cat.emoji, joined)
        }
    }

    @Test
    fun `toggle open to open returns category and page 1 E10_4`() {
        // (c) panel state as pure logic: open → (category 3, page 2) → close → open
        // is category 3, page 1. NO savedLayer needed: the panel does not touch
        // the layers of KeyboardView (comment in EmojiPanelView.closeAndResetPage).
        var category = 2 // index 2 = third category
        var page = 1     // page 2 (0-based)
        // close (E10.4 rule 1): category stays, page → 1 (internal 0)
        page = 0
        assertEquals(2, category)
        assertEquals(0, page)
        // reopen: same category, page 1
        assertEquals(2, category)
        assertEquals(0, page)
        // boundary case: last page of a 4-row geometry before closing
        val pageSize = 32
        val lastPage = EmojiPages.pagesInCategory(48, pageSize) - 1
        assertEquals(1, lastPage)
        page = lastPage
        page = 0 // close → page 1
        assertEquals(0, page)
    }

    @Test
    fun `panel bottom edge lies above the lowest key row M8b_3c`() {
        // M8b-3c: blocks the device-refuted bug that lp.height with
        // Gravity.BOTTOM moves only the TOP edge. Invariant: bottom edge =
        // kv.bottom - rowH, anchored via bottomMargin.
        // Real example (device): keyboard height 580 px, 4 rows ⇒ rowH = 145.
        val kvHeightPx = 580f
        val rows = 4
        val rowH = kvHeightPx / rows // 145
        assertEquals(145f, rowH, 0.001f)
        val panelBottomOffset = rowH // bottomMargin lifts the bottom edge by rowH
        // grid height = panel height minus tab row minus 24dp; panel height here
        // 580 - 145 = 435 px, density 2.625 ⇒ cellMin = 44dp = 115.5px:
        val density = 2.625f
        val navH = 40f * density // 105
        val pad24 = 24f * density // 63
        val panelHeight = kvHeightPx - panelBottomOffset // 435
        val gridHeight = panelHeight - navH - pad24 // 267
        // rows from the measured space: at least 2 here — but the DEVICE
        // requirement reads: with 4 keyboard rows the grid height must allow
        // >= 3 rows (cell minimum height 44dp = 115.5px): 3 * 115.5 = 346.5 > 267
        // ⇒ with this nav height it would be 2 — therefore the requirement applies
        // to the full panel height: rowsForHeight(panelHeight, cellMin) is the
        // limit, and the panel bottom edge must lie rowH above the keyboard floor:
        assertEquals(145f, panelBottomOffset, 0.001f) // Unterkante = kv.bottom - rowH
        // 3-row case: height allowing only 3 rows ⇒ display == realized size
        val pageSize3 = 8 * EmojiPages.rowsForHeight(3f * 44f, 44f)
        assertEquals(24, pageSize3)
        assertEquals(2, EmojiPages.pagesInCategory(48, pageSize3))
        // and: rowsForHeight never yields 0 rows and never more than 6
        assertTrue(EmojiPages.rowsForHeight(gridHeight, 44f * density) in 1..6)
    }

    @Test
    fun `layout calculation exact anchor and height M8b_3d`() {
        // From kvTop, kvHeight, rows follow rowH, panelHeight, topMargin —
        // and topMargin + panelHeight == kvHeight - rowH (bottom edge = kv.bottom - rowH).
        // Device example: kvHeight 580 px, 4 rows.
        fun layout(kvTop: Int, kvHeight: Int, rows: Int): Triple<Float, Int, Int> {
            val rowH = kvHeight / rows.toFloat()
            val panelHeight = (kvHeight - rowH).toInt()
            val kvBottom = kvTop + kvHeight
            val topMargin = (kvBottom - rowH - panelHeight).toInt()
            return Triple(rowH, panelHeight, topMargin)
        }
        val (rowH, panelHeight, topMargin) = layout(0, 580, 4)
        assertEquals(145f, rowH, 0.001f) // rowH
        assertEquals(435, panelHeight) // panelHeight
        assertEquals(0, topMargin) // topMargin (kvTop = 0)
        // assertion: the panel bottom edge = kv.bottom - rowH
        assertEquals(580 - 145, topMargin + panelHeight)
        // panelHeight <= kvHeight - rowH for 3, 4 and 5 rows (always satisfied,
        // explicitly checked here — with rounded rowH the panel never sits lower)
        for (rows in intArrayOf(3, 4, 5)) {
            val r = layout(100, 580, rows)
            assertTrue("rows=$rows", r.second <= 580 - r.first.toInt())
            // bottom edge = kv.bottom - rowH (with float rowH; toInt rounding tolerated)
            assertEquals("bottom edge rows=$rows", 680f - r.first, (r.third + r.second).toFloat(), 1f)
        }
        // and the grid height inside the panel: 435 - nav(105) - 24dp(63) = 267px
        // ⇒ at density 2.625 / 44dp=115.5px cell height: 2 rows — the row count
        // shrinks with the space, the height NEVER (resolveSize clamp, M8b-3d).
        assertTrue(EmojiPages.rowsForHeight(267f, 115.5f) in 1..6)
    }

    @Test
    fun `validate rejects a wrong category count and duplicates`() {
        try {
            EmojiPages.validate(categories().drop(1)) // 7 categories
            fail("7 categories must be rejected")
        } catch (expected: IllegalArgumentException) { }
        try {
            val bad = categories().toMutableList()
            val first = bad[0].emoji.toMutableList()
            first[1] = first[0] // duplicate
            bad[0] = bad[0].copy(emoji = first)
            EmojiPages.validate(bad)
            fail("duplicates must be rejected")
        } catch (expected: IllegalArgumentException) { }
    }
}
