package de.voicekeyboard.keyboard

import android.app.Application
import android.view.MotionEvent
import android.view.View
import de.voicekeyboard.R
import de.voicekeyboard.ime.ImeRootView
import de.voicekeyboard.layouts.LayoutRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * T23l (PLAN_V0147 §14.2/§14.6): globe long-press → language-mode popup
 * (CURRENT endonym | ALL_ACTIVE device-language label), slide selection like
 * the character menus, single-active-language hint cell (E9.1 rule 4), short
 * tap unchanged, ALL_ACTIVE space label, correction-row prefix tag.
 * Robolectric view-level test (pattern: ImeRootViewLayoutTest builds the
 * view; the pure seams stay in LongPressMenuTest).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class GlobeLongpressModeTest {

    private val app: Application get() = RuntimeEnvironment.getApplication() as Application
    private val actions = ArrayList<String>()
    private val langModeSelections = ArrayList<Boolean>()

    @Before
    fun reset() {
        actions.clear()
        langModeSelections.clear()
    }

    private fun buildView(lang: String = "de-de"): KeyboardView {
        opener = null
        val view = KeyboardView(app)
        view.listener = object : KeyboardView.Listener {
            override fun onAction(action: String) {
                actions.add(action)
            }

            override fun onTextInput(text: String) = Unit
        }
        // T23l: deterministic timer seam — the view stays unattached (Robolectric
        // has no ViewRootImpl, postDelayed would land on the static run queue).
        view.longPressSchedule = { opener = it }
        view.setLayout(LayoutRepository(app).loadOrFallback(lang), 1080)
        view.measure(
            View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        view.layout(0, 0, view.measuredWidth, view.measuredHeight)
        return view
    }

    /** Opener captured by the timer seam (one armed long-press per view). */
    private var opener: (() -> Unit)? = null

    private fun touch(view: KeyboardView, action: Int, x: Float, y: Float, t: Long) {
        view.dispatchTouchEvent(MotionEvent.obtain(t, t, action, x, y, 0))
    }

    private fun globeCenter(view: KeyboardView) =
        view.keyOf("language")!!.let { it.centerX to it.centerY }

    private fun openLangPopup(view: KeyboardView): KeyboardView.LongPressPopup {
        val (x, y) = globeCenter(view)
        touch(view, MotionEvent.ACTION_DOWN, x, y, 0)
        val fire = opener
        assertTrue("long-press timer armed on DOWN", fire != null)
        fire!!() // fire after the LONG_PRESS_MS threshold
        val p = view.popup
        assertTrue("long-press on the globe opens a popup", p != null)
        return p!!
    }

    private fun cellCenter(p: KeyboardView.LongPressPopup, cell: Int): Pair<Float, Float> {
        val x = p.left + p.padPx + cell * (p.cellWidthPx + p.padPx) + p.cellWidthPx / 2f
        val y = p.top + p.padPx + p.cellHeightPx / 2f
        return x to y
    }

    // ------------------------------------------------------------- popup open

    @Test
    fun `long-press on the globe opens a two-cell language-mode popup`() {
        val view = buildView()
        assertNull("no popup before the long-press", view.popup)
        val p = openLangPopup(view)

        assertTrue("langMode popup", p.langMode)
        assertEquals("two cells", 2, p.cells)
        assertEquals("cell 0 = endonym of the current language", "Deutsch", p.glyphs[0])
        assertEquals("cell 1 = device-language all-languages label", "All languages", p.glyphs[1])
        assertTrue(
            "label cells may be wider than tall (Robolectric measureText = 0 -> base)",
            p.cellWidthPx >= p.cellHeightPx,
        )
        assertEquals("no disabled cell with several active languages", -1, p.langDisabledCell)

        // UP WITHOUT slide: neither a mode selection nor the tap action
        val (x, y) = globeCenter(view)
        touch(view, MotionEvent.ACTION_UP, x, y, 600)
        assertNull("popup closed after UP", view.popup)
        assertTrue("no langMode selection without slide", langModeSelections.isEmpty())
        assertFalse("long-press does NOT switch the layout", actions.contains("language"))
    }

    @Test
    fun `slide to the second cell selects ALL_ACTIVE`() {
        val view = buildView()
        view.onLangModeSelect = { langModeSelections.add(it) }
        val p = openLangPopup(view)

        val (cx, cy) = cellCenter(p, 1)
        touch(view, MotionEvent.ACTION_MOVE, cx, cy, 600)
        assertEquals("cell 1 selected after the slide", 1, view.popupSelected)
        touch(view, MotionEvent.ACTION_UP, cx, cy, 650)
        assertEquals("onLangModeSelect(ALL)", listOf(true), langModeSelections)
        assertFalse("no layout switch on the langMode commit", actions.contains("language"))
    }

    @Test
    fun `slide to the first cell selects CURRENT`() {
        val view = buildView()
        view.onLangModeSelect = { langModeSelections.add(it) }
        val p = openLangPopup(view)

        val (cx, cy) = cellCenter(p, 0)
        touch(view, MotionEvent.ACTION_MOVE, cx, cy, 600)
        touch(view, MotionEvent.ACTION_UP, cx, cy, 650)
        assertEquals("onLangModeSelect(CURRENT)", listOf(false), langModeSelections)
    }

    @Test
    fun `single active language disables the second cell with the hint title`() {
        val view = buildView()
        view.singleActiveLanguageSupplier = { true }
        view.onLangModeSelect = { langModeSelections.add(it) }
        val p = openLangPopup(view)

        assertEquals(
            "disabled cell carries the E9.1 hint as its title",
            app.getString(R.string.language_switch_single_active),
            p.glyphs[1],
        )
        assertEquals("second cell disabled", 1, p.langDisabledCell)

        // slide onto the disabled cell: no selection possible (no dead clicking)
        val (cx, cy) = cellCenter(p, 1)
        touch(view, MotionEvent.ACTION_MOVE, cx, cy, 600)
        assertEquals("disabled cell is not selectable", -1, view.popupSelected)
        touch(view, MotionEvent.ACTION_UP, cx, cy, 650)
        assertTrue("no selection on the disabled cell", langModeSelections.isEmpty())
    }

    @Test
    fun `short tap on the globe switches the layout - unchanged regression`() {
        val view = buildView()
        val (x, y) = globeCenter(view)
        touch(view, MotionEvent.ACTION_DOWN, x, y, 0)
        touch(view, MotionEvent.ACTION_UP, x, y, 100) // << LONG_PRESS_MS
        assertNull("no popup on a short tap", view.popup)
        assertEquals("short tap switches the layout as before", listOf("language"), actions)
        assertTrue("no langMode selection on a short tap", langModeSelections.isEmpty())
        assertTrue("no timer fired on a short tap", view.popup == null)
    }

    // ------------------------------------------------------------ space label

    @Test
    fun `space label switches between endonym and all-languages label`() {
        val view = buildView("de-de")
        val space = view.keyOf("space")!!
        assertEquals("CURRENT: endonym as today", "Deutsch", view.displayLabel(space))

        view.typingLangModeSupplier = { true }
        assertEquals("ALL_ACTIVE: device-language label", "All languages", view.displayLabel(space))
    }

    @Test
    fun `all-languages label follows the device locale - pt-BR and DE-de`() {
        val view = buildView("de-de")
        view.typingLangModeSupplier = { true }
        val space = view.keyOf("space")!!

        app.resources.configuration.setLocales(android.os.LocaleList.forLanguageTags("pt-BR"))
        assertEquals("pt-BR -> pt", "Todos idiomas", view.displayLabel(space))

        app.resources.configuration.setLocales(android.os.LocaleList.forLanguageTags("DE-de"))
        assertEquals("DE-de -> de (case + separator)", "Alle Sprachen", view.displayLabel(space))
    }

    // ----------------------------------------------------------- row prefix tag

    @Test
    fun `correction row shows the language prefix tag in ALL_ACTIVE`() {
        app.applicationInfo.flags = app.applicationInfo.flags and
            android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE.inv()
        val row = ImeRootView(app)
        row.setFieldInputType(
            android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_VARIATION_NORMAL,
        )
        row.setCorrectionPrefix("DE")
        row.setCorrection("morgem Welt", "morgen Welt", hasSuggestion = true, enabled = true)
        assertEquals("DE · morgen Welt", row.correctionText.text.toString())

        row.setCorrectionPrefix("")
        row.setCorrection("morgem Welt", "morgen Welt", hasSuggestion = true, enabled = true)
        assertEquals("no prefix in CURRENT", "morgen Welt", row.correctionText.text.toString())
    }
}
