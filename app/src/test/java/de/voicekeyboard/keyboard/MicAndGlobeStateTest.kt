package de.voicekeyboard.keyboard

import android.app.Application
import de.voicekeyboard.ime.LanguageCycle
import de.voicekeyboard.layouts.KeyKind
import de.voicekeyboard.layouts.LayoutKey
import de.voicekeyboard.layouts.LayoutLayer
import de.voicekeyboard.layouts.LayoutParser
import de.voicekeyboard.layouts.SpaceLabelRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * D10 (docs/DESIGN_MIC_AND_GLOBE.md) — S5-Unit-Tests, test-first:
 *
 *  (a) Endonym-Beschriftung der Leertaste je Sprache (§0/§1: „Space, lokalisiertes
 *      Label" — Quelle ist die VORHANDENE Endonym-Tabelle LanguageCycle.ENDONYMS,
 *      keine neue Tabelle; aufgelöst über SpaceLabelRule).
 *  (b) Trefferfläche/Position der Mic-Taste in der Modell-Geometrie (§1:
 *      Footer `?123 · 🌐 · 😊 · Space · ‹ · › · Enter · Mic`, Mic-Gewicht 2 ganz
 *      rechts, Space 5→4, Summe bleibt 13, letzte rechte Kante exakt W).
 *  (c) Nach dem Footer-Reflow existieren weiterhin ALLE bisherigen Tasten-IDs
 *      im Modell (§1: „ID, Aktion, Label und Alternates beibehalten" — 0 JSON).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MicAndGlobeStateTest {

    // ---- Test-Layer (Footereihe wie in den Assets: [..., Mic, Enter]) ----

    private fun action(id: String, label: String, width: Double, value: String) =
        LayoutKey(id, label, width, KeyKind.ACTION, value)

    private fun footerRow(): List<LayoutKey> = listOf(
        action("symbols", "?123", 1.5, "layer-symbols"),
        action("language", "🌐", 1.0, "language"),
        action("emoji", "😊", 1.0, "emoji"),
        action("space", "Leer", 5.0, "space"),
        action("cursor-left", "‹", 1.0, "cursor-left"),
        action("cursor-right", "›", 1.0, "cursor-right"),
        action("mic", "🎤", 1.0, "mic"),   // Assets: Mic VOR Enter
        action("enter", "⏎", 1.5, "enter"),
    )

    private fun layer(rows: List<List<LayoutKey>>): LayoutLayer = LayoutLayer(name = "base", rows = rows)

    private fun letterRow(vararg ids: String): List<LayoutKey> =
        ids.map { LayoutKey(it, it, 1.0, KeyKind.TEXT, it) }

    // ================= (a) Endonym-Beschriftung der Leertaste =================

    @Test
    fun `leertaste zeigt das Endonym der aktiven Sprache - alle 15 Assets`() {
        val ctx = RuntimeEnvironment.getApplication() as Application
        val files = ctx.assets.list("layouts")!!.filter { it.endsWith(".json") }
        assertEquals(15, files.size)
        for (file in files) {
            val asset = LayoutParser.parse(
                ctx.assets.open("layouts/$file").bufferedReader().use { it.readText() },
            )
            // Quelle: VORHANDENE Endonym-Tabelle (LanguageCycle.ENDONYMS), über den
            // Asset-Sprach-Tag aufgelöst — genau das, was KeyboardView der
            // SpaceLabelRule übergibt (keine neue Tabelle).
            val endonym = LanguageCycle.endonym(asset.language.tag.lowercase())
            assertTrue(
                "Endonym für ${asset.id} (tag ${asset.language.tag}) muss gefunden sein",
                endonym != asset.language.tag.lowercase() && endonym.isNotBlank(),
            )
            // SpaceLabelRule nimmt das Endonym (Nicht-Whitespace ⇒ Asset-Label gewinnt):
            assertEquals(endonym, SpaceLabelRule.resolve(endonym, "␣"))
        }
    }

    @Test
    fun `endonym-Beispiele aus D10 - Deutsch English Polski Espanol`() {
        assertEquals("Deutsch", LanguageCycle.endonym("de-de"))
        assertEquals("English (US)", LanguageCycle.endonym("en-us"))
        assertEquals("Polski", LanguageCycle.endonym("pl-pl"))
        assertEquals("Español", LanguageCycle.endonym("es-es"))
        assertEquals("Русский", LanguageCycle.endonym("ru-ru"))
    }

    // ============ (b) Mic-Trefferfläche und Position in der Modell-Geometrie ============

    @Test
    fun `mic ist letzte Taste ganz rechts mit Gewicht 2 und voller Trefferflaeche`() {
        val width = 1080f
        val m = KeyboardModel(layer(listOf(footerRow())), width, keyHeightPx = 144f, rowGapPx = 0f, keyGapPx = 0f, fit = null)
        val footer = m.keys // einzige Reihe
        val mic = footer.first { it.id == "mic" }
        val enter = footer.first { it.id == "enter" }
        // Position: Mic GANZ rechts, Enter direkt davor (D10 §1 Footer-Reihenfolge)
        assertEquals("mic", footer.last().id)
        assertEquals("enter", footer[footer.size - 2].id)
        assertTrue("mic muss rechts von enter liegen", mic.right > enter.right)
        // Gewichtsanteil: Mic 2/13 der Nutzbreite (D10: 15,385 % ≈ 166 px bei W=1080)
        assertEquals(2f / 13f * width, mic.right - mic.left, 0.5f)
        // Letzte rechte Kante exakt W (kein Verschnitt)
        assertEquals(width, mic.right, 0.5f)
        // Trefferfläche ≥ 48 dp (bei density 420 = 126 px): 166 px ✓
        assertTrue("Mic-Breite ${mic.right - mic.left} px muss ≥ 126 px (48 dp @420) sein", mic.right - mic.left >= 126f)
        assertEquals(144f, mic.bottom - mic.top, 0.01f) // volle Reihenhoehe
        // Treffer am Mittelpunkt liefert mic — derselbe Pfad wie Touch/Accessibility
        assertEquals("mic", m.keyAt(mic.centerX, mic.centerY)?.id)
        // Space gibt genau eine Gewichtseinheit ab (5 → 4), Summe bleibt 13:
        val space = footer.first { it.id == "space" }
        assertEquals(4f / 13f * width, space.right - space.left, 0.5f)
    }

    @Test
    fun `mic bleibt bei skaliertem Fit unten rechts und treffbar`() {
        // S1-Skalierung (650-px-Child-Angebot, 55-dp-Reihen): Fit ändert die
        // Höhen, NICHT die Footer-Komposition.
        val fit = KeyboardHeight.fitHeight(
            offeredPx = 503, rows = 4, desiredRowPx = 144, desiredRowGapPx = 0,
            statusLinePx = 0, navInsetPx = 0, maxPx = 725, minKeyHeightPx = 86,
        )
        val fullLayer = layer(listOf(
            letterRow("q", "w", "e", "r", "t", "z", "u", "i", "o", "p"),
            letterRow("a", "s", "d", "f", "g", "h", "j", "k", "l"),
            letterRow("y", "x", "c", "v", "b", "n", "m"),
            footerRow(),
        ))
        val m = KeyboardModel(fullLayer, 1080f, keyHeightPx = 144f, rowGapPx = 0f, keyGapPx = 0f, fit = fit)
        val mic = m.keys.first { it.id == "mic" }
        assertEquals("mic muss die letzte Taste sein", "mic", m.keys.last().id)
        assertEquals("letzte rechte Kante exakt W", 1080f, mic.right, 0.5f)
        assertEquals("mic", m.keyAt(mic.centerX, mic.centerY)?.id)
        assertEquals(fit.rowPx.toFloat(), mic.bottom - mic.top, 0.01f)
        // Mic ist in der untersten Reihe (größtes top)
        assertEquals(m.keys.maxOf { it.top }, mic.top, 0.01f)
    }

    @Test
    fun `footer-Komposition beruehrt Reihen ohne Mic nicht`() {
        val rows = listOf(letterRow("a", "b", "c"), letterRow("d", "e", "f"))
        val m = KeyboardModel(layer(rows), 300f, keyHeightPx = 50f, rowGapPx = 6f, keyGapPx = 4f, fit = null)
        assertEquals(listOf("a", "b", "c", "d", "e", "f"), m.keys.map { it.id })
    }

    // ============ (c) Alle bisherigen Tasten-IDs bleiben im Modell ============

    @Test
    fun `reflow laesst ALLE IDs unveraendert - kein Verlust keine Neuen`() {
        val fullLayer = layer(listOf(
            letterRow("q", "w", "e", "r", "t", "z", "u", "i", "o", "p", "ue", "oe", "ae", "ss"),
            letterRow("a", "s", "d", "f", "g", "h", "j", "k", "l", "y", "x", "c", "v", "b", "n", "m"),
            footerRow(),
        ))
        val sourceIds = fullLayer.rows.flatten().map { it.id }.toSet()
        val m = KeyboardModel(fullLayer, 1080f, keyHeightPx = 144f, rowGapPx = 0f, keyGapPx = 0f, fit = null)
        assertEquals("ID-Menge muss identisch sein (D10: IDs beibehalten)", sourceIds, m.keys.map { it.id }.toSet())
        assertEquals("38 Tasten (de-de-Analog: 30 Buchstaben + 8 Footer)", 38, m.keys.size)
        // Labels und Aktionen bleiben unberührt (nur Position/Gewicht komponiert):
        val mic = m.keys.first { it.id == "mic" }
        assertEquals("🎤", mic.label)
        assertEquals("mic", mic.value)
        val space = m.keys.first { it.id == "space" }
        assertEquals("Leer", space.label) // Label bleibt Asset-Label (hier „Leer“);
        // die Anzeige löst KeyboardView per Endonym auf — siehe (a).
    }
}
