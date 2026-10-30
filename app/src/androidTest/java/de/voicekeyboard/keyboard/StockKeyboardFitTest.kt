package de.voicekeyboard.keyboard

import android.view.View
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import de.voicekeyboard.ime.ImeRootView
import de.voicekeyboard.layouts.LayoutAsset
import de.voicekeyboard.layouts.LayoutParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * S2-Instrumented-Test (DESIGN_STOCK_LOOK.md §4, Zeile S2) — läuft auf dem
 * Emulator/Gerät des Orchestrators.
 *
 * Pflichtfälle: zweimal dasselbe Feld öffnen, Feldwechsel, Emoji-Ebene verlassen —
 * danach müssen ALLE Tasten-IDs des aktiven Layouts
 *  (a) im Modell vorhanden sein (kein Row-Drop — S1-Vertrag),
 *  (b) mit vollem Rect innerhalb der View-Grenzen liegen (keine halb gezeichnete
 *      Taste), unterer Rand überschreitet das Budget nicht,
 *  (c) treffbar sein: Trefferermittlung am Rect-Mittelpunkt liefert dieselbe ID
 *      (derselbe Pfad wie Touch/Accessibility).
 *
 * Geprüft wird DE (4 Reihen) und PT (5 Reihen). Das Angebot ist hart auf 650 px
 * gesetzt (AT_MOST, wie die IME-Window-Kette) — der Test rät keine Gerätepixel,
 * er misst dieselbe Root-Kette: ImeRootView −(Nav-Padding, im Test 0) −(48-dp-
 * Statuszeile) → KeyboardView-Spec;KeyboardView skaliert ALLE Reihen hinein.
 */
@RunWith(AndroidJUnit4::class)
class StockKeyboardFitTest {

    private val widthPx = 1080
    private val offeredPx = 650 // beobachtetes schwankendes Kleinstangebot

    private fun targetContext(): android.content.Context =
        InstrumentationRegistry.getInstrumentation().targetContext

    private fun loadAsset(id: String): LayoutAsset {
        val json = targetContext().assets.open("layouts/$id.json")
            .bufferedReader().use { it.readText() }
        return LayoutParser.parse(json)
    }

    private fun <T> onMain(block: () -> T): T {
        var result: T? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync { result = block() }
        @Suppress("UNCHECKED_CAST")
        return result as T
    }

    /** Öffnet die echte Root-Kette mit hartem 650-px-Angebot und legt sie aus. */
    private fun openRoot(asset: LayoutAsset, width: Int = widthPx): ImeRootView = onMain {
        val root = ImeRootView(targetContext())
        root.keyboardView.setLayout(asset, width)
        root.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(offeredPx, View.MeasureSpec.AT_MOST),
        )
        root.layout(0, 0, width, root.measuredHeight)
        root
    }

    private fun remeasure(root: ImeRootView, width: Int) = onMain {
        root.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(offeredPx, View.MeasureSpec.AT_MOST),
        )
        root.layout(0, 0, width, root.measuredHeight)
    }

    /** (a)+(b)+(c): alle IDs vorhanden, volle Rects im Budget, Mittelpunkt trifft sich selbst. */
    private fun assertAllKeysIntact(view: KeyboardView, asset: LayoutAsset, label: String) {
        val expectedIds = asset.layers.getValue(view.activeLayerName).rows.flatten()
            .map { it.id }.toSet()
        val drawn = view.drawnKeys
        assertEquals("$label: Tasten-IDs dürfen nicht verloren gehen", expectedIds, drawn.map { it.id }.toSet())
        assertTrue("$label: es müssen Tasten gezeichnet werden", drawn.isNotEmpty())
        for (k in drawn) {
            assertTrue("$label: Rect von '${k.id}' muss in der View liegen (top)", k.top >= -0.5f)
            assertTrue(
                "$label: unterer Rand von '${k.id}' überschreitet das Budget " +
                    "(bottom=${k.bottom}, viewHeight=${view.height})",
                k.bottom <= view.height + 0.5f,
            )
            assertTrue("$label: volles Rect für '${k.id}' (keine halbe Taste)", k.bottom - k.top > 0f)
            assertEquals(
                "$label: Treffer am Mittelpunkt von '${k.id}' muss die eigene ID liefern",
                k.id, view.keyAtPoint(k.centerX, k.centerY)?.id,
            )
        }
    }

    @Test
    fun deLayout_vierReihen_ZweitoeffnungFeldwechselEmoji_alleIDs_erhalten() {
        checkFullSequence("de-de")
    }

    @Test
    fun ptLayout_fuenfReihen_ZweitoeffnungFeldwechselEmoji_alleIDs_erhalten() {
        checkFullSequence("pt-pt")
    }

    /**
     * Sequenz je Layout: Öffnung 1 → Feldwechsel (Breite 1080→1440→1080, Re-Measure)
     * → Emoji-Ebene und zurück → Öffnung 2 (NEUE Instanz: kein Fit der Öffnung 1
     * darf weiterwirken) → additionally bare KeyboardView mit hartem 650-px-Measure.
     */
    private fun checkFullSequence(assetId: String) {
        val asset = loadAsset(assetId)

        // ── Öffnung 1 ──
        val root1 = openRoot(asset)
        assertAllKeysIntact(root1.keyboardView, asset, "$assetId Öffnung 1")
        assertTrue(
            "$assetId: Root-Gesamthöhe muss im 650-px-Angebot bleiben (ist ${root1.height})",
            root1.height <= offeredPx,
        )

        // ── Feldwechsel: anderes Feld, anderes Angebot an die Root-Kette ──
        remeasure(root1, 1440)
        assertAllKeysIntact(root1.keyboardView, asset, "$assetId Feldwechsel (1440)")
        remeasure(root1, widthPx)
        assertAllKeysIntact(root1.keyboardView, asset, "$assetId Feldwechsel zurück")

        // ── Emoji-Ebene und zurück (Ebenen-Roundtrip über denselben Stack) ──
        onMain {
            root1.showEmojiPanel()
            assertTrue("Emoji-Modus muss sichtbar sein", root1.isEmojiPanelVisible)
            root1.hideEmojiPanel()
        }
        remeasure(root1, widthPx)
        assertAllKeysIntact(root1.keyboardView, asset, "$assetId nach Emoji-Rückkehr")

        // ── Öffnung 2: NEUE Instanz — kein globaler/klebriger Fit erlaubt ──
        val root2 = openRoot(asset)
        assertAllKeysIntact(root2.keyboardView, asset, "$assetId Öffnung 2")

        // ── deterministischer Hack: nackte KeyboardView, hartes 650-px-Angebot ──
        val bare = onMain {
            val v = KeyboardView(targetContext())
            v.setLayout(asset, widthPx)
            v.measure(
                View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(offeredPx, View.MeasureSpec.AT_MOST),
            )
            v.layout(0, 0, widthPx, v.measuredHeight)
            v
        }
        assertAllKeysIntact(bare, asset, "$assetId bare 650 px")
    }
}
