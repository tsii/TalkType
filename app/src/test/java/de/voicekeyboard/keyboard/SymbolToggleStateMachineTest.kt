package de.voicekeyboard.keyboard

import android.app.Application
import android.view.MotionEvent
import android.view.View
import de.voicekeyboard.layouts.LayoutRepository
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * V0.1.49 (docs/PLAN_GEOMETRY_V0149.md §5/§7 row 7): the layer switch state
 * machine on the REAL de-de assets — the user path
 *
 *   base → ?123 (footer) → #+= (symbols Z2[0]) → ?123 (symbolsShift Z2[0])
 *   → ABC (footer)
 *
 * must produce EXACTLY [symbols, symbolsShift, symbols, base] (0 tolerance —
 * string equality, no delta).
 *
 * Regression against the OLD KeyboardView semantics (pre-V0.1.49, line ~1345):
 * `?123` used to forward to symbolsShift while symbolShift was set — the
 * second `?123` (from symbolsShift) would have STAYED on symbolsShift. Since
 * V0.1.49 `?123` ALWAYS leads to the numbers page (symbols) and only `#+=`
 * (layer-symbols-shift) opens the special characters.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SymbolToggleStateMachineTest {

    private val app: Application get() = RuntimeEnvironment.getApplication() as Application

    /** Plain tap (DOWN + UP within 100 ms — no long-press) on the key centre. */
    private fun tap(view: KeyboardView, id: String, at: Long) {
        val key = view.keyOf(id)
            ?: throw AssertionError("key '$id' not found on layer '${view.activeLayerName}'")
        view.dispatchTouchEvent(
            MotionEvent.obtain(at, at, MotionEvent.ACTION_DOWN, key.centerX, key.centerY, 0),
        )
        view.dispatchTouchEvent(
            MotionEvent.obtain(at, at + 100, MotionEvent.ACTION_UP, key.centerX, key.centerY, 0),
        )
    }

    @Test
    fun tapFolgeFuehrtExaktUeberSymbolsSymbolsShiftSymbolsZurueck() {
        val view = KeyboardView(app)
        view.listener = object : KeyboardView.Listener {
            override fun onTextInput(text: String) = Unit
            override fun onAction(action: String) = Unit
        }
        view.setLayout(LayoutRepository(app).loadOrFallback("de-de"), 1080)
        view.measure(
            View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        view.layout(0, 0, view.measuredWidth, view.measuredHeight)

        assertEquals("start on base", "base", view.activeLayerName)

        // 1. '?123' footer (base r3[0], layer-symbols) → numbers page.
        tap(view, "symbols", 0)
        assertEquals("1st stop", "symbols", view.activeLayerName)

        // 2. '#+=' toggle (symbols r2[0], layer-symbols-shift) → special characters.
        tap(view, "symbols-toggle", 200)
        assertEquals("2nd stop", "symbolsShift", view.activeLayerName)

        // 3. '?123' toggle (symbolsShift r2[0], layer-symbols) → back to the
        //    numbers page. REGRESSION: with the old semantics this tap STAYED
        //    on symbolsShift (switchLayer(if (symbolShift) "symbolsShift" …)).
        tap(view, "symbols-back", 400)
        assertEquals("3rd stop: ?123 NEVER stays on symbolsShift", "symbols", view.activeLayerName)

        // 4. 'ABC' footer (symbols r3[0], layer-base) → letters.
        tap(view, "base", 600)
        assertEquals("4th stop", "base", view.activeLayerName)
    }

    /** The full path as ONE exact sequence assertion (plan §7 row 7, 0 tolerance). */
    @Test
    fun vollpfadAlsExakteSequenz() {
        val view = KeyboardView(app)
        view.listener = object : KeyboardView.Listener {
            override fun onTextInput(text: String) = Unit
            override fun onAction(action: String) = Unit
        }
        view.setLayout(LayoutRepository(app).loadOrFallback("de-de"), 1080)
        view.measure(
            View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        view.layout(0, 0, view.measuredWidth, view.measuredHeight)

        val path = mutableListOf<String>()
        for ((keyId, at) in listOf("symbols" to 0L, "symbols-toggle" to 200L,
                                   "symbols-back" to 400L, "base" to 600L)) {
            tap(view, keyId, at)
            path.add(view.activeLayerName)
        }
        assertEquals(
            "?123 → #+= → ?123 → ABC must be exactly [symbols, symbolsShift, symbols, base]",
            listOf("symbols", "symbolsShift", "symbols", "base"),
            path,
        )

        // Round trip: a SECOND run of the same path must behave identically
        // (no stale symbolShift state survives the ABC return).
        val path2 = mutableListOf<String>()
        for ((keyId, at) in listOf("symbols" to 800L, "symbols-toggle" to 1000L,
                                  "symbols-back" to 1200L, "base" to 1400L)) {
            tap(view, keyId, at)
            path2.add(view.activeLayerName)
        }
        assertEquals(
            "second round trip identical (no stale symbolShift)",
            listOf("symbols", "symbolsShift", "symbols", "base"),
            path2,
        )
    }
}
