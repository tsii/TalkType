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
 * S2 instrumented test (DESIGN_STOCK_LOOK.md §4, row S2) — runs on the
 * orchestrator's emulator/device.
 *
 * Mandatory cases: open the same field twice, field change, leave the emoji layer —
 * afterwards ALL key IDs of the active layout must
 *  (a) be present in the model (no row drop — S1 contract),
 *  (b) lie with a full rect inside the view bounds (no half-drawn
 *      key), the bottom edge must not exceed the budget,
 *  (c) be hittable: hit testing at the rect center yields the same ID
 *      (the same path as touch/accessibility).
 *
 * Checked are DE (4 rows) and PT (5 rows). The offer is hard-set to 650 px
 * (AT_MOST, like the IME window chain) — the test does not guess device pixels,
 * it measures the same root chain: ImeRootView −(nav padding, 0 in the test)
 * −(48-dp status row) → KeyboardView spec; KeyboardView scales ALL rows into it.
 */
@RunWith(AndroidJUnit4::class)
class StockKeyboardFitTest {

    private val widthPx = 1080
    private val offeredPx = 650 // the smallest observed fluctuating offer

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

    /** Opens the real root chain with a hard 650-px offer and lays it out. */
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

    /** (a)+(b)+(c): all IDs present, full rects within the budget, the center point hits itself. */
    private fun assertAllKeysIntact(view: KeyboardView, asset: LayoutAsset, label: String) {
        val expectedIds = asset.layers.getValue(view.activeLayerName).rows.flatten()
            .map { it.id }.toSet()
        val drawn = view.drawnKeys
        assertEquals("$label: key IDs must not get lost", expectedIds, drawn.map { it.id }.toSet())
        assertTrue("$label: keys must be drawn", drawn.isNotEmpty())
        for (k in drawn) {
            assertTrue("$label: the rect of '${k.id}' must lie inside the view (top)", k.top >= -0.5f)
            assertTrue(
                "$label: the bottom edge of '${k.id}' exceeds the budget " +
                    "(bottom=${k.bottom}, viewHeight=${view.height})",
                k.bottom <= view.height + 0.5f,
            )
            assertTrue("$label: full rect for '${k.id}' (no half key)", k.bottom - k.top > 0f)
            assertEquals(
                "$label: a hit at the center of '${k.id}' must yield its own ID",
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
     * Sequence per layout: opening 1 → field change (width 1080→1440→1080, re-measure)
     * → emoji layer and back → opening 2 (NEW instance: no fit of opening 1 may
     * carry over) → additionally a bare KeyboardView with a hard 650-px measure.
     */
    private fun checkFullSequence(assetId: String) {
        val asset = loadAsset(assetId)

        // ── opening 1 ──
        val root1 = openRoot(asset)
        assertAllKeysIntact(root1.keyboardView, asset, "$assetId opening 1")
        assertTrue(
            "$assetId: the total root height must stay within the 650-px offer (is ${root1.height})",
            root1.height <= offeredPx,
        )

        // ── field change: different field, different offer to the root chain ──
        remeasure(root1, 1440)
        assertAllKeysIntact(root1.keyboardView, asset, "$assetId field change (1440)")
        remeasure(root1, widthPx)
        assertAllKeysIntact(root1.keyboardView, asset, "$assetId field change back")

        // ── emoji layer and back (layer round trip via the same stack) ──
        onMain {
            root1.showEmojiPanel()
            assertTrue("emoji mode must be visible", root1.isEmojiPanelVisible)
            root1.hideEmojiPanel()
        }
        remeasure(root1, widthPx)
        assertAllKeysIntact(root1.keyboardView, asset, "$assetId after emoji return")

        // ── opening 2: NEW instance — no global/sticky fit allowed ──
        val root2 = openRoot(asset)
        assertAllKeysIntact(root2.keyboardView, asset, "$assetId opening 2")

        // ── deterministic hack: bare KeyboardView, hard 650-px offer ──
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
