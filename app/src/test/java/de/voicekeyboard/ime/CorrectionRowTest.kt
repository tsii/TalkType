package de.voicekeyboard.ime

import android.app.Application
import android.content.pm.ApplicationInfo
import android.graphics.Typeface
import android.text.Spanned
import android.text.style.StyleSpan
import android.text.style.UnderlineSpan
import android.view.View
import androidx.core.view.isVisible
import de.voicekeyboard.correct.RegionReplacer
import de.voicekeyboard.layouts.LayoutRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * T23f (V0.1.47, docs/PLAN_V0147.md §6): correction row in the statusRow slot —
 * 40 dp in release when the field is eligible AND the setup switch is on,
 * GONE otherwise (keyboard keeps the full budget, T1 fallback). Debug keeps
 * status text + 🎤 + 🐞 left of the correction text. Wand 48 dp wide,
 * apply/undo content descriptions + glyph rotation, clicks via onWandPress.
 * The row enters the height budget EXACTLY ONCE (existing stack mechanism).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CorrectionRowTest {

    private val app: Application get() = RuntimeEnvironment.getApplication() as Application

    private fun setDebuggable(on: Boolean) {
        if (on) {
            app.applicationInfo.flags = app.applicationInfo.flags or ApplicationInfo.FLAG_DEBUGGABLE
        } else {
            app.applicationInfo.flags =
                app.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE.inv()
        }
    }

    private fun buildReleaseRoot(inputType: Int): ImeRootView {
        setDebuggable(false)
        val view = ImeRootView(app)
        view.keyboardView.setLayout(
            LayoutRepository(app).loadOrFallback("de-de"),
            app.resources.displayMetrics.widthPixels,
        )
        view.setFieldInputType(inputType)
        return view
    }

    private fun measureAndLayout(view: ImeRootView, heightOffer: Int = 0) {
        view.measure(
            View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
            if (heightOffer == 0) {
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
            } else {
                View.MeasureSpec.makeMeasureSpec(heightOffer, View.MeasureSpec.EXACTLY)
            },
        )
        view.layout(0, 0, view.measuredWidth, view.measuredHeight)
    }

    // ------------------------------------------------------- eligibility matrix

    /** inputType x setup switch -> row visible/hidden (release). */
    @Test
    fun `eligibility matrix - inputType x switch decides row visibility`() {
        val eligible = listOf(
            android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_VARIATION_NORMAL,
            android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_VARIATION_WEB_EDIT_TEXT,
            android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES,
            // REVIEW_V0147 VK-6 (Oracle decision 2026-11-05): postal address and
            // person name are DELIBERATELY ELIGIBLE — the user contract names
            // no address exclusion (§6); note in PLAN + 0.1.48 open point.
            android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_VARIATION_POSTAL_ADDRESS,
            android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_VARIATION_PERSON_NAME,
        )
        val ineligible = listOf(
            android.text.InputType.TYPE_NULL,
            android.text.InputType.TYPE_CLASS_NUMBER,
            android.text.InputType.TYPE_CLASS_PHONE,
            android.text.InputType.TYPE_CLASS_DATETIME,
            android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD,
            android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
            android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
            android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_VARIATION_URI,
            android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,
            android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS,
        )
        assertTrue("matrix covers >= 13 rows", eligible.size + ineligible.size >= 13)

        for (type in eligible) {
            val view = buildReleaseRoot(type)
            assertEquals("eligible type $type: row VISIBLE", View.VISIBLE, view.statusRow.visibility)
        }
        for (type in ineligible) {
            val view = buildReleaseRoot(type)
            assertEquals("ineligible type $type: row GONE", View.GONE, view.statusRow.visibility)
        }

        // setup switch (pref corr_row_enabled, fed via supplier — no pref access in the view)
        val view = buildReleaseRoot(
            android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_VARIATION_NORMAL,
        )
        view.typingSuggestionsSupplier = { false }
        view.refreshCorrectionRowVisibility()
        assertEquals("switch off: row GONE", View.GONE, view.statusRow.visibility)
        view.typingSuggestionsSupplier = { true }
        view.refreshCorrectionRowVisibility()
        assertEquals("switch on: row VISIBLE", View.VISIBLE, view.statusRow.visibility)
    }

    // ------------------------------------------------------------- height budget

    @Test
    fun `GONE row leaves the keyboard height unchanged`() {
        val density = app.resources.displayMetrics.density
        val view = buildReleaseRoot(
            android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD,
        )
        measureAndLayout(view)
        assertEquals(View.GONE, view.statusRow.visibility)
        assertEquals("no band without row: keyboard top == 0 (T1 fallback)", 0, view.keyboardView.top)
    }

    @Test
    fun `visible row costs exactly 40 dp once - band is the keyboard top`() {
        val density = app.resources.displayMetrics.density
        val row40 = (40 * density).toInt()
        val view = buildReleaseRoot(
            android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_VARIATION_NORMAL,
        )
        measureAndLayout(view)

        assertEquals("row height exactly 40 dp", row40, view.statusRow.height)
        assertEquals("statusBandPx == keyboard top == 40 dp", row40, view.keyboardView.top)

        // tight offer: the row is subtracted EXACTLY ONCE — like the P0 tight
        // window test: offer UNDER the natural height, the keyboard scales into
        // (offer - headroom - 40 dp); a double subtraction would cost 40 dp more.
        view.measure(
            View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        val naturalH = view.measuredHeight
        val tight = naturalH - (20 * density).toInt()
        assertTrue("test precondition: the offer is under the natural height", tight > 0)
        view.measure(
            View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(tight, View.MeasureSpec.EXACTLY),
        )
        view.layout(0, 0, view.measuredWidth, view.measuredHeight)
        assertEquals("row stays 40 dp under the tight offer", row40, view.statusRow.height)
        val expectedKeyboard = tight - view.headroomPx - row40
        assertTrue(
            "keyboard receives offer - headroom - 40 dp exactly once " +
                "(double subtraction would cost another ${row40}px), " +
                "expected ~$expectedKeyboard got ${view.keyboardView.height}",
            Math.abs(view.keyboardView.height - expectedKeyboard) <= (2 * density).toInt(),
        )
    }

    // ------------------------------------------------------------- row content

    @Test
    fun `setCorrection shows bold underlined changed words and dims when empty`() {
        val view = buildReleaseRoot(
            android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_VARIATION_NORMAL,
        )
        view.setCorrection("morgem Welt", "morgen Welt", hasSuggestion = true, enabled = true)
        assertEquals("morgen Welt", view.correctionText.text.toString())
        val span = view.correctionText.text as Spanned
        val bold = span.getSpans(0, span.length, StyleSpan::class.java)
        val underline = span.getSpans(0, span.length, UnderlineSpan::class.java)
        assertEquals("one changed word marked bold", 1, bold.size)
        assertEquals("one changed word underlined", 1, underline.size)
        assertEquals(Typeface.BOLD, bold[0].style)
        assertEquals("morgen", span.toString().substring(span.getSpanStart(bold[0]), span.getSpanEnd(bold[0])))
        assertEquals("wand enabled with suggestion", true, view.wandButton.isEnabled)
        assertEquals(1f, view.correctionText.alpha, 1e-3f)

        view.setCorrection(null, null, hasSuggestion = false, enabled = false)
        assertEquals("no suggestion: text empty", "", view.correctionText.text.toString())
        assertFalse("wand disabled without suggestion", view.wandButton.isEnabled)
        assertTrue("dimmed without suggestion", view.correctionText.alpha < 1f)
    }

    // ------------------------------------------------------------ wand button

    @Test
    fun `wand glyph and description switch with undo level and clicks dispatch`() {
        val view = buildReleaseRoot(
            android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_VARIATION_NORMAL,
        )
        assertEquals(
            app.getString(de.voicekeyboard.R.string.wand_apply_correction),
            view.wandButton.contentDescription,
        )
        assertEquals(0f, view.wandButton.rotation, 1e-3f)

        var pressed: RegionReplacer.WandPress? = null
        view.onWandPress = { pressed = it }
        view.wandButton.performClick()
        assertEquals(RegionReplacer.WandPress.APPLY, pressed)

        view.setWandUndoMode(true)
        assertEquals(
            app.getString(de.voicekeyboard.R.string.wand_undo_correction),
            view.wandButton.contentDescription,
        )
        assertEquals("undo glyph: wand rotated", 180f, view.wandButton.rotation, 1e-3f)
        view.wandButton.performClick()
        assertEquals(RegionReplacer.WandPress.UNDO, pressed)

        view.setWandUndoMode(false)
        assertEquals(
            app.getString(de.voicekeyboard.R.string.wand_apply_correction),
            view.wandButton.contentDescription,
        )
        assertEquals(0f, view.wandButton.rotation, 1e-3f)
    }

    @Test
    fun `wand is 48 dp wide inside the 40 dp row`() {
        val density = app.resources.displayMetrics.density
        val view = buildReleaseRoot(
            android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_VARIATION_NORMAL,
        )
        measureAndLayout(view)
        assertEquals("wand width 48 dp", (48 * density).toInt(), view.wandButton.width)
        assertTrue("wand visible in eligible release", view.wandButton.isVisible)
        assertTrue("wand is the last child of the row", view.statusRow.getChildAt(view.statusRow.childCount - 1) === view.wandButton)
    }

    // ------------------------------------------------------------- debug fields

    @Test
    fun `debug fields stay debug only and sit left of the correction content`() {
        // RELEASE: eligible row shows ONLY correction content
        val release = buildReleaseRoot(
            android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_VARIATION_NORMAL,
        )
        assertEquals(View.GONE, release.statusText.visibility)
        assertEquals(View.GONE, release.micButton.visibility)
        assertEquals(View.GONE, release.debugButton.visibility)
        assertEquals(View.VISIBLE, release.correctionText.visibility)
        assertEquals(View.VISIBLE, release.wandButton.visibility)

        // DEBUG: stock left (status text, mic, 🐞) + correction content + wand
        setDebuggable(true)
        val debug = ImeRootView(app)
        measureAndLayout(debug)
        assertEquals(View.VISIBLE, debug.statusText.visibility)
        assertEquals(View.VISIBLE, debug.micButton.visibility)
        assertEquals(View.VISIBLE, debug.debugButton.visibility)
        assertEquals("debug row stays 48 dp", (48 * app.resources.displayMetrics.density).toInt(), debug.statusRow.height)
        val order = listOf(debug.statusText, debug.micButton, debug.debugButton, debug.correctionText, debug.wandButton)
        for (i in 0 until order.size - 1) {
            assertTrue(
                "row order ${order[i]} before ${order[i + 1]}",
                debug.statusRow.indexOfChild(order[i]) < debug.statusRow.indexOfChild(order[i + 1]),
            )
        }
    }

    // ================================================= REVIEW_V0147 fix round

    /**
     * VK-2 (P1): the undo glyph must keep the wand CLICKABLE after the row
     * cleared — the only undo window (§5) lies between APPLY and the next
     * keystroke; a disabled wand would make UNDO unreachable in production.
     * Review sequence: setCorrection(true) -> clearRow -> setWandUndoMode(true)
     * -> performClick -> onWandPress(UNDO).
     */
    @Test
    fun `undo glyph keeps the wand clickable after the row cleared`() {
        val view = buildReleaseRoot(
            android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_VARIATION_NORMAL,
        )
        var pressed: de.voicekeyboard.correct.RegionReplacer.WandPress? = null
        view.onWandPress = { pressed = it }

        view.setCorrection("morgem Welt", "morgen Welt", hasSuggestion = true, enabled = true)
        view.setCorrection(null, null, hasSuggestion = false, enabled = false) // clearRow state
        view.setWandUndoMode(true)
        assertTrue("wand clickable in undo mode despite the cleared row", view.wandButton.isEnabled)
        view.wandButton.performClick()
        assertEquals(de.voicekeyboard.correct.RegionReplacer.WandPress.UNDO, pressed)

        // without undo level AND without suggestion the wand stays disabled
        view.setWandUndoMode(false)
        assertFalse("wand disabled again without undo level/suggestion", view.wandButton.isEnabled)
    }
}
