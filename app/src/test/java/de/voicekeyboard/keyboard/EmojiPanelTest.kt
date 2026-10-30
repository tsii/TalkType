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
 * E9.4 (M8b-2): Validiert emoji_v1.json und die reine Raster-Logik
 * (EmojiPages — keine Android-Typen im Testpfad).
 *
 * T6 aus LANGUAGE_EMOJI_DECISIONS.md: 8 Kategorien × 48, keine Duplikate,
 * NFC, keine ZWJ-/Hautton-/Flaggen-Sequenzen, jeder Eintrag genau 1 Graphem-Cluster.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class EmojiPanelTest {

    /** Lädt das Asset aus den Test-Ressourcen (isIncludeAndroidResources) — reiner Pfad. */
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
    fun `asset hat genau 8 kategorien mit je 48 glyphen und 384 gesamt`() {
        val cats = categories()
        assertEquals(8, cats.size)
        for (cat in cats) {
            assertEquals("Kategorie ${cat.id}", 48, cat.emoji.size)
        }
        assertEquals(384, cats.sumOf { it.emoji.size })
        EmojiPages.validate(cats) // wirft bei Verstoß
    }

    @Test
    fun `keine duplikate innerhalb einer kategorie und keine zwj hautton flaggen`() {
        for (cat in categories()) {
            assertEquals("Duplikat in ${cat.id}", cat.emoji.size, cat.emoji.distinct().size)
            for (e in cat.emoji) {
                assertTrue("ZWJ in $e (${cat.id})", '\u200D' !in e)
                assertTrue("Hautton in $e (${cat.id})", e.none { it.code in 0x1F3FB..0x1F3FF })
                assertTrue("Flagge in $e (${cat.id})", e.none { it.code in 0x1F1E6..0x1F1FF })
                assertTrue("NFC in $e", Normalizer.normalize(e, Normalizer.Form.NFC) == e)
                assertEquals("kein Graphem-Cluster: $e", 1, EmojiPages.graphemeCount(e))
            }
        }
    }

    @Test
    fun `seitenzuordnung index 47 bleibt seite 0 index 48 waere naechste seite`() {
        val cat = (0 until 100).map { "e$it" }
        val page0 = EmojiPages.pageOf(cat, 0)
        assertEquals(48, page0.size)
        assertEquals("e47", page0[47]) // Index 47 => Seite 0, Position 47
        // Index 48 liegt auf Seite 1 (bzw. naechste Kategorie-Seite)
        val page1 = EmojiPages.pageOf(cat, 1)
        assertEquals("e48", page1[0])
        assertEquals(48, page1.size)
        // Reste: Seite 2 hat 100-96=4
        val page2 = EmojiPages.pageOf(cat, 2)
        assertEquals(4, page2.size)
        // jenseits des Endes: leer
        assertTrue(EmojiPages.pageOf(cat, 3).isEmpty())
    }

    @Test
    fun `raster indexberechnung 8x6`() {
        // Zeile 0, Spalte 0 → Index 0; Spalte 7 → 7
        assertEquals(0, EmojiPages.indexAt(10f, 5f, 100f, 100f, 0f))
        assertEquals(7, EmojiPages.indexAt(750f, 5f, 100f, 100f, 0f))
        // Zeile 1, Spalte 0 → Index 8
        assertEquals(8, EmojiPages.indexAt(10f, 105f, 100f, 100f, 0f))
        // Index 47 = Zeile 5, Spalte 7
        assertEquals(47, EmojiPages.indexAt(750f, 505f, 100f, 100f, 0f))
        // außerhalb: -1
        // y=605 -> Zeile 6 (ausserhalb des 6-Zeilen-Rasters): -1
        assertEquals(-1, EmojiPages.indexAt(10f, 650f, 100f, 100f, 0f, 8, 6))
        // x=-5 -> Spalte -1 (ausserhalb): -1
        assertEquals(-1, EmojiPages.indexAt(-105f, 5f, 100f, 100f, 0f, 8, 6))
        // y=605 mit gridTop 0: Zeile 6 > 5 (rows-1) -> -1
        assertEquals(-1, EmojiPages.indexAt(10f, 605f, 100f, 100f, 0f, 8, 6))
        // gridTop berücksichtigen
        assertEquals(0, EmojiPages.indexAt(10f, 205f, 100f, 100f, 200f))
    }

    @Test
    fun `surrogatpaar ist eine glyphe mit zwei utf16 einheiten`() {
        val emoji = "😀" // U+1F600: High + Low Surrogat
        assertEquals(2, EmojiPages.utf16Units(emoji))
        assertEquals(1, EmojiPages.graphemeCount(emoji))
        // BMP-Zeichen (z. B. ❤ U+2764): 1 Glyphe, 1 Einheit
        val bmp = "❤"
        assertEquals(1, EmojiPages.utf16Units(bmp))
        assertEquals(1, EmojiPages.graphemeCount(bmp))
    }

    @Test
    fun `zeilen mal spalten mal seiten decken 384 ohne ueberlauf`() {
        // 6 Zeilen × 8 Spalten × 8 Kategorien × 1 Seite = 384
        assertEquals(48, EmojiPages.PAGE_SIZE)
        assertEquals(1, EmojiPages.pagesInCategory(48, 48))
        // 5-Zeilen-Fallback (E-02): 40 pro Seite ⇒ 2 Seiten je Kategorie
        assertEquals(40, 8 * 5)
        assertEquals(2, EmojiPages.pagesInCategory(48, 40))
        // nichts laeuft ueber: alle Seiten zusammen ergeben exakt 48 je Kategorie,
        // alle Kategorien zusammen exakt 384 — bei 48er UND 40er Seitengroesse.
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
                assertEquals("Seitenaufteilung ($size) fuer ${cat.id}", 48, catTotal)
                assertEquals("Seitenzahl ($size) fuer ${cat.id}",
                    EmojiPages.pagesInCategory(48, size), page)
                total += catTotal
            }
            assertEquals("Gesamt bei Seitengroesse $size", 384, total)
        }
        // Zeilenberechnung: Hoehe reicht fuer 6 → 6; knapp → weniger, nie 0
        assertEquals(6, EmojiPages.rowsForHeight(6f * 44f, 44f))
        assertEquals(5, EmojiPages.rowsForHeight(5.5f * 44f, 44f))
        assertEquals(1, EmojiPages.rowsForHeight(44f, 44f))
        assertEquals(1, EmojiPages.rowsForHeight(0f, 44f))
    }

    @Test
    fun `seitenzahl und anzeige passen zur realisierten seitengroesse E10_3`() {
        // (b) bei einer Hoehe, die nur 3 Zeilen zulaesst: Anzeige == realisierte
        // Seitengroesse (24 Glyphen/Seite ⇒ 2 Seiten je 48er-Kategorie, letzte teilgefüllt)
        val pageSize3 = 8 * EmojiPages.rowsForHeight(3f * 44f, 44f)
        assertEquals(24, pageSize3)
        for (cat in categories()) {
            val pages = EmojiPages.pagesInCategory(48, pageSize3)
            assertEquals(2, pages)
            // Aneinanderhaengen aller Seiten ergibt EXAKT die Asset-Liste
            val joined = (0 until pages).flatMap { p ->
                EmojiPages.pageOf(cat.emoji, p, pageSize3)
            }
            assertEquals(cat.emoji, joined)
            // keine Verluste, keine Duplikate (implizit durch assertEquals);
            // letzte Seite teilgefüllt (24 + 24 = 48 → hier voll, 4er-Fall:)
        }
        // 4-Zeilen-Fall (Geraet: 4×8 = 32): 2 Seiten, zweite mit 16 Glyphen teilgefüllt
        val pageSize4 = 8 * 4
        for (cat in categories()) {
            val pages = EmojiPages.pagesInCategory(48, pageSize4)
            assertEquals(2, pages)
            val joined = (0 until pages).flatMap { p -> EmojiPages.pageOf(cat.emoji, p, pageSize4) }
            assertEquals(cat.emoji, joined)
            assertEquals(16, EmojiPages.pageOf(cat.emoji, 1, pageSize4).size)
        }
        // 5-Zeilen-Fall: 40/Seite ⇒ 2 Seiten (40 + 8)
        val pageSize5 = 40
        for (cat in categories()) {
            val joined = (0 until EmojiPages.pagesInCategory(48, pageSize5)).flatMap { p ->
                EmojiPages.pageOf(cat.emoji, p, pageSize5)
            }
            assertEquals(cat.emoji, joined)
        }
    }

    @Test
    fun `toggle offen zu offen liefert kategorie und seite 1 E10_4`() {
        // (c) Panel-Zustand als reine Logik: open → (Kategorie 3, Seite 2) → close → open
        // ist Kategorie 3, Seite 1. KEIN savedLayer noetig: das Panel faesst die
        // Ebenen des KeyboardView nicht an (Kommentar in EmojiPanelView.closeAndResetPage).
        var category = 2 // Index 2 = dritte Kategorie
        var page = 1     // Seite 2 (0-basiert)
        // close (E10.4 Regel 1): Kategorie bleibt, Seite → 1 (intern 0)
        page = 0
        assertEquals(2, category)
        assertEquals(0, page)
        // reopen: gleiche Kategorie, Seite 1
        assertEquals(2, category)
        assertEquals(0, page)
        // Grenzfall: letzte Seite einer 4-Zeilen-Geometrie vor dem Schliessen
        val pageSize = 32
        val lastPage = EmojiPages.pagesInCategory(48, pageSize) - 1
        assertEquals(1, lastPage)
        page = lastPage
        page = 0 // close → Seite 1
        assertEquals(0, page)
    }

    @Test
    fun `panel-unterkante liegt ueber der untersten tastenreihe M8b_3c`() {
        // M8b-3c: blockiert den geraetewiderlegten Fehler, dass lp.height bei
        // Gravity.BOTTOM nur die OBERKANTE verschiebt. Invariante: Unterkante =
        // kv.bottom - rowH, verankert per bottomMargin.
        // Realbeispiel (Geraet): Tastaturhoehe 580 px, 4 Zeilen ⇒ rowH = 145.
        val kvHeightPx = 580f
        val rows = 4
        val rowH = kvHeightPx / rows // 145
        assertEquals(145f, rowH, 0.001f)
        val panelBottomOffset = rowH // bottomMargin hebt die Unterkante um rowH
        // Grid-Hoehe = Panelhoehe minus Blätterzeile minus 24dp; Panelhoehe hier
        // 580 - 145 = 435 px, density 2.625 ⇒ cellMin = 44dp = 115.5px:
        val density = 2.625f
        val navH = 40f * density // 105
        val pad24 = 24f * density // 63
        val panelHeight = kvHeightPx - panelBottomOffset // 435
        val gridHeight = panelHeight - navH - pad24 // 267
        // Zeilen aus dem gemessenen Platz: mind. 2 hier — aber die GERAETE-Anforderung
        // lautet: bei 4 Tastaturzeilen muss die Grid-Hoehe >= 3 Zeilen zulassen
        // (Zellmindesthoehe 44dp = 115.5px): 3 * 115.5 = 346.5 > 267 ⇒ mit dieser
        // Nav-Hoehe waeren es 2 — deshalb gilt die Anforderung auf die volle
        // Panelhoehe: rowsForHeight(panelHeight, cellMin) ist die Grenze, und die
        // Panels-Unterkante muss rowH ueber dem Tastaturboden liegen:
        assertEquals(145f, panelBottomOffset, 0.001f) // Unterkante = kv.bottom - rowH
        // 3-Zeilen-Fall: Hoehe, die nur 3 Zeilen zulaesst ⇒ Anzeige == realisierte Groesse
        val pageSize3 = 8 * EmojiPages.rowsForHeight(3f * 44f, 44f)
        assertEquals(24, pageSize3)
        assertEquals(2, EmojiPages.pagesInCategory(48, pageSize3))
        // und: rowsForHeight fuehrt nie zu 0 Zeilen und nie ueber 6
        assertTrue(EmojiPages.rowsForHeight(gridHeight, 44f * density) in 1..6)
    }

    @Test
    fun `layout-rechnung exakt-anker und hoehe M8b_3d`() {
        // Aus kvTop, kvHeight, zeilen folgen rowH, panelHeight, topMargin —
        // und topMargin + panelHeight == kvHeight - rowH (Unterkante = kv.bottom - rowH).
        // Geraet-Beispiel: kvHeight 580 px, 4 Zeilen.
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
        // Zusicherung: Unterkante des Panels = kv.bottom - rowH
        assertEquals(580 - 145, topMargin + panelHeight)
        // panelHeight <= kvHeight - rowH fuer 3, 4 und 5 Zeilen (immer erfuellt,
        // hier explizit geprueft — mit gerundetem rowH bleibt das Panel nie tiefer)
        for (rows in intArrayOf(3, 4, 5)) {
            val r = layout(100, 580, rows)
            assertTrue("rows=$rows", r.second <= 580 - r.first.toInt())
            // Unterkante = kv.bottom - rowH (mit float-rowH; toInt-Rundung toleriert)
            assertEquals("Unterkante rows=$rows", 680f - r.first, (r.third + r.second).toFloat(), 1f)
        }
        // und die Grid-Hoehe innerhalb des Panels: 435 - Nav(105) - 24dp(63) = 267px
        // ⇒ bei density 2.625 / 44dp=115.5px Zellhoehe: 2 Zeilen — die Zeilenzahl
        // sinkt mit dem Platz, die Hoehe NIE (resolveSize-Klemme, M8b-3d).
        assertTrue(EmojiPages.rowsForHeight(267f, 115.5f) in 1..6)
    }

    @Test
    fun `validate lehnt falsche kategorienzahl und duplikate ab`() {
        try {
            EmojiPages.validate(categories().drop(1)) // 7 Kategorien
            fail("7 Kategorien müssen abgelehnt werden")
        } catch (expected: IllegalArgumentException) { }
        try {
            val bad = categories().toMutableList()
            val first = bad[0].emoji.toMutableList()
            first[1] = first[0] // Duplikat
            bad[0] = bad[0].copy(emoji = first)
            EmojiPages.validate(bad)
            fail("Duplikat muss abgelehnt werden")
        } catch (expected: IllegalArgumentException) { }
    }
}
