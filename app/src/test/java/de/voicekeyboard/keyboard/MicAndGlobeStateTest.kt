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
 * D10 (docs/DESIGN_MIC_AND_GLOBE.md) — S5 unit tests, test-first:
 *
 *  (a) endonym labeling of the space key per language (§0/§1: “Space, localized
 *      label” — the source is the EXISTING endonym table LanguageCycle.ENDONYMS,
 *      no new table; resolved via SpaceLabelRule).
 *  (b) hit area/position of the mic key in the model geometry (§1:
 *      footer `?123 · 🌐 · 😊 · Space · ‹ · › · Enter · Mic`, mic weight 2 at the
 *      far right, space 5→4, the sum stays 13, the last right edge exactly W).
 *  (c) after the footer reflow ALL previous key IDs continue to exist in the
 *      model (§1: “keep ID, action, label and alternates” — 0 JSON).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MicAndGlobeStateTest {

    // ---- test layer (footer row as in the assets: [..., Mic, Enter]) ----

    private fun action(id: String, label: String, width: Double, value: String) =
        LayoutKey(id, label, width, KeyKind.ACTION, value)

    private fun footerRow(): List<LayoutKey> = listOf(
        action("symbols", "?123", 1.5, "layer-symbols"),
        action("language", "🌐", 1.0, "language"),
        action("emoji", "😊", 1.0, "emoji"),
        action("space", "Leer", 5.0, "space"),
        action("cursor-left", "‹", 1.0, "cursor-left"),
        action("cursor-right", "›", 1.0, "cursor-right"),
        action("mic", "🎤", 1.0, "mic"),   // assets: Mic BEFORE Enter
        action("enter", "⏎", 1.5, "enter"),
    )

    private fun layer(rows: List<List<LayoutKey>>): LayoutLayer = LayoutLayer(name = "base", rows = rows)

    private fun letterRow(vararg ids: String): List<LayoutKey> =
        ids.map { LayoutKey(it, it, 1.0, KeyKind.TEXT, it) }

    // ================= (a) endonym labeling of the space key =================

    @Test
    fun `space key shows the endonym of the active language - all 15 assets`() {
        val ctx = RuntimeEnvironment.getApplication() as Application
        val files = ctx.assets.list("layouts")!!.filter { it.endsWith(".json") }
        assertEquals(15, files.size)
        for (file in files) {
            val asset = LayoutParser.parse(
                ctx.assets.open("layouts/$file").bufferedReader().use { it.readText() },
            )
            // source: EXISTING endonym table (LanguageCycle.ENDONYMS), resolved via
            // the asset language tag — exactly what KeyboardView passes to
            // SpaceLabelRule (no new table).
            val endonym = LanguageCycle.endonym(asset.language.tag.lowercase())
            assertTrue(
                "the endonym for ${asset.id} (tag ${asset.language.tag}) must be found",
                endonym != asset.language.tag.lowercase() && endonym.isNotBlank(),
            )
            // SpaceLabelRule takes the endonym (non-whitespace ⇒ asset label wins):
            assertEquals(endonym, SpaceLabelRule.resolve(endonym, "␣"))
        }
    }

    @Test
    fun `endonym examples from D10 - Deutsch English Polski Espanol`() {
        assertEquals("Deutsch", LanguageCycle.endonym("de-de"))
        assertEquals("English (US)", LanguageCycle.endonym("en-us"))
        assertEquals("Polski", LanguageCycle.endonym("pl-pl"))
        assertEquals("Español", LanguageCycle.endonym("es-es"))
        assertEquals("Русский", LanguageCycle.endonym("ru-ru"))
    }

    // ============ (b) mic hit area and position in the model geometry ============

    @Test
    fun `mic is the last key at the far right with weight 2 and a full hit area`() {
        val width = 1080f
        val m = KeyboardModel(layer(listOf(footerRow())), width, keyHeightPx = 144f, rowGapPx = 0f, keyGapPx = 0f, fit = null)
        val footer = m.keys // the only row
        val mic = footer.first { it.id == "mic" }
        val enter = footer.first { it.id == "enter" }
        // position: Mic at the FAR right, Enter directly before it (D10 §1 footer order)
        assertEquals("mic", footer.last().id)
        assertEquals("enter", footer[footer.size - 2].id)
        assertTrue("mic must lie to the right of enter", mic.right > enter.right)
        // weight share: Mic 2/13 of the usable width (D10: 15.385 % ≈ 166 px at W=1080)
        assertEquals(2f / 13f * width, mic.right - mic.left, 0.5f)
        // last right edge exactly W (no offcut)
        assertEquals(width, mic.right, 0.5f)
        // hit area ≥ 48 dp (at density 420 = 126 px): 166 px ✓
        assertTrue("mic width ${mic.right - mic.left} px must be ≥ 126 px (48 dp @420)", mic.right - mic.left >= 126f)
        assertEquals(144f, mic.bottom - mic.top, 0.01f) // full row height
        // a hit at the center returns mic — the same path as touch/accessibility
        assertEquals("mic", m.keyAt(mic.centerX, mic.centerY)?.id)
        // space gives up exactly one weight unit (5 → 4), the sum stays 13:
        val space = footer.first { it.id == "space" }
        assertEquals(4f / 13f * width, space.right - space.left, 0.5f)
    }

    @Test
    fun `mic stays bottom right and hittable with a scaled fit`() {
        // S1 scaling (650-px child offer, 55-dp rows): the fit changes the
        // heights, NOT the footer composition.
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
        assertEquals("mic must be the last key", "mic", m.keys.last().id)
        assertEquals("last right edge exactly W", 1080f, mic.right, 0.5f)
        assertEquals("mic", m.keyAt(mic.centerX, mic.centerY)?.id)
        assertEquals(fit.rowPx.toFloat(), mic.bottom - mic.top, 0.01f)
        // Mic is in the bottom row (largest top)
        assertEquals(m.keys.maxOf { it.top }, mic.top, 0.01f)
    }

    @Test
    fun `footer composition does not touch rows without a mic`() {
        val rows = listOf(letterRow("a", "b", "c"), letterRow("d", "e", "f"))
        val m = KeyboardModel(layer(rows), 300f, keyHeightPx = 50f, rowGapPx = 6f, keyGapPx = 4f, fit = null)
        assertEquals(listOf("a", "b", "c", "d", "e", "f"), m.keys.map { it.id })
    }

    // ============ (c) all previous key IDs stay in the model ============

    @Test
    fun `reflow leaves ALL IDs unchanged - no loss no additions`() {
        val fullLayer = layer(listOf(
            letterRow("q", "w", "e", "r", "t", "z", "u", "i", "o", "p", "ue", "oe", "ae", "ss"),
            letterRow("a", "s", "d", "f", "g", "h", "j", "k", "l", "y", "x", "c", "v", "b", "n", "m"),
            footerRow(),
        ))
        val sourceIds = fullLayer.rows.flatten().map { it.id }.toSet()
        val m = KeyboardModel(fullLayer, 1080f, keyHeightPx = 144f, rowGapPx = 0f, keyGapPx = 0f, fit = null)
        assertEquals("the ID set must be identical (D10: keep IDs)", sourceIds, m.keys.map { it.id }.toSet())
        assertEquals("38 keys (de-de analog: 30 letters + 8 footer)", 38, m.keys.size)
        // labels and actions stay untouched (only position/weight composed):
        val mic = m.keys.first { it.id == "mic" }
        assertEquals("🎤", mic.label)
        assertEquals("mic", mic.value)
        val space = m.keys.first { it.id == "space" }
        assertEquals("Leer", space.label) // the label stays the asset label (here “Leer”);
        // the display resolves KeyboardView via the endonym — see (a).
    }
}
