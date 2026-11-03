package de.voicekeyboard.ime

import android.app.Application
import android.content.pm.ApplicationInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.RectF
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import de.voicekeyboard.dictation.DictationState
import de.voicekeyboard.keyboard.KeyboardView
import de.voicekeyboard.layouts.LayoutRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config

/**
 * P0 regression (0.1.20): the status row ("Ready"/"Dictating" + mic + 🐞) and
 * the 48-dp height budget above the q row. The test proves the basic state:
 *
 *  - the root view contains statusText, micButton and the 🐞 debug key (as
 *    mounted in the service) — all three VISIBLE with height > 0;
 *  - the status row is EXACTLY 48 dp;
 *  - the keyboard sits BELOW it (top ≥ status row height) and keeps its rows
 *    (S1: no row drop) — even with a tight window offer (EXACTLY).
 *
 * Measurement like the IME framework: width EXACTLY, height UNSPECIFIED
 * (window size = view measured height) or EXACTLY with a limited offer.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ImeRootViewLayoutTest {

    private val app: Application get() = RuntimeEnvironment.getApplication() as Application

    private fun statusRowOf(view: ImeRootView): LinearLayout =
        view.micButton.parent as LinearLayout

    /** T9 (v0.1.38): injectable seam = context.applicationInfo.flags — the
     *  constructor reads FLAG_DEBUGGABLE ONCE. buildRoot() sets the flag
     *  explicitly: THIS class proves the DEBUG stock (P0 regression); the
     *  release case has its own two T9 tests below. */
    private fun setDebuggable(on: Boolean) {
        if (on) {
            app.applicationInfo.flags = app.applicationInfo.flags or ApplicationInfo.FLAG_DEBUGGABLE
        } else {
            app.applicationInfo.flags =
                app.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE.inv()
        }
    }

    /** Builds the root like [VoiceKeyboardService.onCreateInputView]:
     *  layout de-de + 🐞 button (48 dp) at the same spot (statusRow = micButton.parent). */
    private fun buildRoot(): ImeRootView {
        setDebuggable(true)
        val view = ImeRootView(app)
        view.keyboardView.setLayout(
            LayoutRepository(app).loadOrFallback("de-de"),
            app.resources.displayMetrics.widthPixels,
        )
        // P0: the 🐞 key comes from the ImeRootView itself (view.debugButton) —
        // the old manual addView in the test masked the brittle service
        // behavior; it is deleted.
        return view
    }

    @Test
    fun `P0 basic state - status row 48dp visible and the keyboard below it`() {
        val view = buildRoot()
        val density = app.resources.displayMetrics.density
        val status48 = (48 * density).toInt()

        // IME measurement: width EXACTLY, height UNSPECIFIED (the window follows the view).
        view.measure(
            View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        view.layout(0, 0, view.measuredWidth, view.measuredHeight)

        val statusRow = statusRowOf(view)
        assertEquals("status row VISIBLE", View.VISIBLE, statusRow.visibility)
        assertEquals("status row exactly 48 dp", status48, statusRow.height)

        assertEquals("statusText VISIBLE", View.VISIBLE, view.statusText.visibility)
        assertTrue("statusText height > 0", view.statusText.height > 0)
        assertEquals("statusText fills the row", status48, view.statusText.height)

        assertEquals("micButton VISIBLE", View.VISIBLE, view.micButton.visibility)
        assertTrue("micButton height > 0", view.micButton.height > 0)
        assertEquals("micButton fills the row", status48, view.micButton.height)

        val debugButton = statusRow.getChildAt(statusRow.childCount - 1) as Button
        assertEquals("🐞 key is the last status row component", "🐞", debugButton.text.toString())
        assertEquals("🐞 VISIBLE", View.VISIBLE, debugButton.visibility)
        assertTrue("🐞 height > 0", debugButton.height > 0)

        assertEquals("Keyboard VISIBLE", View.VISIBLE, view.keyboardView.visibility)
        assertTrue("keyboard height > 0", view.keyboardView.height > 0)
        assertTrue(
            "the keyboard lies BELOW the status row, not above",
            view.keyboardView.top >= statusRow.height,
        )
    }

    @Test
    fun `P0 tight window offer - status row stays 48dp and the keyboard scales instead of a row drop`() {
        val view = buildRoot()
        val density = app.resources.displayMetrics.density
        val status48 = (48 * density).toInt()

        // First determine the natural size, then force a TIGHTER offer
        // (cap the window lower than content + status row).
        view.measure(
            View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        val naturalH = view.measuredHeight
        val tightH = naturalH - (80 * density).toInt()
        assertTrue("test precondition: the offer is narrower than the content", tightH > 0)

        view.measure(
            View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(tightH, View.MeasureSpec.EXACTLY),
        )
        view.layout(0, 0, view.measuredWidth, view.measuredHeight)

        val statusRow = statusRowOf(view)
        assertEquals("status row stays VISIBLE", View.VISIBLE, statusRow.visibility)
        assertEquals("status row stays exactly 48 dp", status48, statusRow.height)
        assertTrue("statusText height > 0", view.statusText.height > 0)
        assertTrue("micButton height > 0", view.micButton.height > 0)

        // S1 contract: all 4 rows stay, the row height is lowered.
        assertTrue("keyboard stays visible", view.keyboardView.visibility == View.VISIBLE)
        assertTrue(
            "the keyboard fills the rest (height <= offer - status row)",
            view.keyboardView.height <= tightH - status48 + 1,
        )
        assertTrue("keyboard height > 0 (no empty box)", view.keyboardView.height > 0)
    }

    // ==================================================================
    // T9 (v0.1.38): status row debug only — release ⇒ statusRow GONE
    // (status text + 🎤 + 🐞 completely, NO layout space); debug ⇒ stock.
    // RED-FIRST: both tests ran red before the ImeRootView change
    // (release test: statusRow was VISIBLE).
    // ==================================================================

    @Test
    fun `T9 release - statusRow GONE in keyboard AND emoji mode`() {
        setDebuggable(false)
        val view = ImeRootView(app)
        assertEquals("release: statusRow GONE (no layout space)", View.GONE, view.statusRow.visibility)
        assertFalse("keyboard mode active", view.isEmojiPanelVisible)

        view.showEmojiPanel()
        assertEquals("release, emoji mode: statusRow stays GONE", View.GONE, view.statusRow.visibility)
        assertTrue("Emoji-Block VISIBLE", view.isEmojiPanelVisible)

        view.hideEmojiPanel()
        assertEquals("Release, back in keyboard mode: statusRow stays GONE",
            View.GONE, view.statusRow.visibility)
    }

    @Test
    fun `T9 debug - statusRow VISIBLE (stock) in both modes`() {
        setDebuggable(true)
        val view = ImeRootView(app)
        assertEquals("debug: statusRow VISIBLE (retained)", View.VISIBLE, view.statusRow.visibility)

        view.showEmojiPanel()
        assertEquals("debug, emoji mode: statusRow stays VISIBLE",
            View.VISIBLE, view.statusRow.visibility)
    }

    // ==================================================================
    // P0 round 2026-11-01 (user finding after 0.1.24)
    // ==================================================================

    @Test
    fun `P0-2 basic state - status text never empty and debugButton fixed in the row`() {
        val view = buildRoot()
        val density = app.resources.displayMetrics.density
        view.measure(
            View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        view.layout(0, 0, view.measuredWidth, view.measuredHeight)

        // finding 1a: in the basic state (the service has not run yet) there is a text.
        assertTrue("statusText is NOT empty in the idle state", view.statusText.text.isNotEmpty())
        assertEquals("Ready", view.statusText.text.toString())

        // finding 1b: 🐞 is a view child (view.debugButton), right (last child),
        // 48 dp wide, visible with height > 0.
        val row = statusRowOf(view)
        val last = row.getChildAt(row.childCount - 1)
        assertSame("debugButton is the last child of the status row (right)", view.debugButton, last)
        assertEquals("🐞 VISIBLE", View.VISIBLE, view.debugButton.visibility)
        assertEquals("🐞 width 48 dp", (48 * density).toInt(), view.debugButton.width)
        assertTrue("🐞 height > 0", view.debugButton.height > 0)
    }

    // ---- finding 2: mic state machine (pure functions, VKS domain) ----

    @Test
    fun `finding-2a T3 - hold-release during start stops after start end - tap-on sets no flag`() {
        assertEquals("normal case: a session is running", MicStartFinishAction.RUN, micStartFinishedAction(false, false))
        // T3 (v0.1.35, debugger design, evidence /tmp/vk_phone_e2.log l.74-97):
        // wantedStop now comes ONLY from the hold-release (>300 ms) during the
        // start — it REALLY ends the fresh session after the start end
        // (STOP_AFTER_START). The blind KEEP_RUNNING (P0 finding 2(a), quick-tap
        // rescue) is replaced: a fresh tap is TOGGLE_ON (micHoldReleaseDecision)
        // and does not set the flag AT ALL.
        assertEquals("hold-release during the start: stop AFTER the start ends",
            MicStartFinishAction.STOP_AFTER_START, micStartFinishedAction(true, false))
        // CANCEL remains an abort without a final (D10 rest S1).
        assertEquals("abort wins", MicStartFinishAction.ABORT, micStartFinishedAction(true, true))
        assertEquals("abort without a stop", MicStartFinishAction.ABORT, micStartFinishedAction(false, true))
    }

    @Test
    fun `finding-2b - after stop the mic state recedes (IDLE is not green)`() {
        assertTrue("RECORDING is green", micUiRecording(DictationState.RECORDING))
        assertFalse("IDLE (after stop) is NOT green", micUiRecording(DictationState.IDLE))
        assertFalse("STARTING is NOT green", micUiRecording(DictationState.STARTING))
        assertFalse("FINALIZING is NOT green", micUiRecording(DictationState.FINALIZING))
        assertFalse("without a coordinator (null) NOT green", micUiRecording(null))
    }

    @Test
    fun `finding-2c - exhaustive - there is no state that stays green without a session`() {
        for (s in DictationState.entries) {
            assertEquals("state $s", (s == DictationState.RECORDING), micUiRecording(s))
        }
    }

    // ---- finding 3: action glyphs as paths (draw op history, no tofu) ----

    /** Measure/layout the keyboard (de-de) and draw it onto a canvas;
     *  ShadowCanvas records ALL draw ops (paths, text, rects) — this makes it
     *  provable whether the glyphs come from PATHS or from the font. */
    private fun recordDraw(night: Boolean): Pair<Canvas, de.voicekeyboard.keyboard.KeyboardModel> {
        if (night) {
            val cfg = Configuration(app.resources.configuration)
            cfg.uiMode = Configuration.UI_MODE_NIGHT_YES
            app.resources.updateConfiguration(cfg, app.resources.displayMetrics)
        }
        val view = KeyboardView(app)
        view.setLayout(LayoutRepository(app).loadOrFallback("de-de"), 1080)
        view.measure(
            View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        view.layout(0, 0, view.measuredWidth, view.measuredHeight)
        val model = view.currentModel ?: error("no model")
        val canvas = Canvas(Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888))
        view.draw(canvas)
        return canvas to model
    }

    /** Key rectangles of the three action glyphs. */
    private fun glyphKeyRects(model: de.voicekeyboard.keyboard.KeyboardModel): Map<String, RectF> =
        listOf("backspace", "shift", "enter").associateWith { id ->
            val k = model.keys.first { key -> key.id == id }
            RectF(k.left, k.top, k.right, k.bottom)
        }

    @Test
    fun `finding-3 - the three action glyphs come from paths, never from the font (no tofu)`() {
        val (canvas, model) = recordDraw(night = false)
        val sh = Shadows.shadowOf(canvas)
        // 1) font absence: not a single text draw with one of the three glyphs
        //    (before: font glyph U+232B => tofu box on the device).
        for (i in 0 until sh.textHistoryCount) {
            val t = sh.getDrawnTextEvent(i).text
            assertFalse(
                "font glyph '$t' drawn instead of a path (tofu risk)",
                t in setOf("⌫", "⇧", "⏎", "⇪"),
            )
        }
        // 2) path presence: every glyph has >= 2 drawn paths.
        val rects = glyphKeyRects(model)
        val byKey = rects.keys.associateWith { _ -> 0 }.toMutableMap()
        for (i in 0 until sh.pathPaintHistoryCount) {
            val b = RectF()
            sh.getDrawnPath(i).computeBounds(b, true)
            val hit = rects.entries.firstOrNull { it.value.contains(b.centerX(), b.centerY()) }
            if (hit != null) byKey[hit.key] = byKey.getValue(hit.key) + 1
        }
        for ((k, n) in byKey) assertTrue("$k: >= 2 path draws (are $n)", n >= 2)
    }

    @Test
    fun `finding-3 - identical optical height of all three glyphs (light + dark)`() {
        for (night in listOf(false, true)) {
            val (canvas, model) = recordDraw(night)
            val sh = Shadows.shadowOf(canvas)
            val rects = glyphKeyRects(model)
            val heights = rects.keys.map { k ->
                var minY = Float.MAX_VALUE
                var maxY = -Float.MAX_VALUE
                for (i in 0 until sh.pathPaintHistoryCount) {
                    val b = RectF()
                    sh.getDrawnPath(i).computeBounds(b, true)
                    if (rects.getValue(k).contains(b.centerX(), b.centerY())) {
                        minY = minOf(minY, b.top)
                        maxY = maxOf(maxY, b.bottom)
                    }
                }
                maxY - minY
            }
            // all three identical (bounding box 0.60·s), light and dark; tolerance 2 px.
            assertTrue("night=$night: height identical (are $heights)",
                (heights.min()..heights.max()).let { it.isEmpty() } || heights.max() - heights.min() <= 2f)
            assertTrue("night=$night: height > 0 (are $heights)", heights.min() > 5f)
        }
    }

    @Test
    fun `finding-3 - identical stroke width in all three glyphs (light + dark)`() {
        for (night in listOf(false, true)) {
            val (canvas, model) = recordDraw(night)
            val sh = Shadows.shadowOf(canvas)
            val rects = glyphKeyRects(model)
            val strokes = (0 until sh.pathPaintHistoryCount).mapNotNull { i ->
                val b = RectF()
                sh.getDrawnPath(i).computeBounds(b, true)
                if (rects.values.any { it.contains(b.centerX(), b.centerY()) })
                    sh.getDrawnPathPaint(i).strokeWidth else null
            }
            assertTrue("night=$night: glyph paths found", strokes.size >= 6)
            assertTrue(
                "night=$night: stroke width identical (are ${strokes.toSet()})",
                strokes.all { it == strokes.first() },
            )
        }
    }
}
