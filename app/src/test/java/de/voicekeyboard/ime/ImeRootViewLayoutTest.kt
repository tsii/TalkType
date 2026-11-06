package de.voicekeyboard.ime

import android.app.Application
import android.content.pm.ApplicationInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Insets
import android.graphics.Rect
import android.graphics.RectF
import android.inputmethodservice.InputMethodService
import android.view.View
import android.view.WindowInsets
import android.widget.Button
import android.widget.LinearLayout
import de.voicekeyboard.dictation.DictationState
import de.voicekeyboard.keyboard.KeyboardTheme
import de.voicekeyboard.keyboard.KeyboardView
import de.voicekeyboard.layouts.LayoutRepository
import kotlin.math.roundToInt
import org.robolectric.Robolectric
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

        // T23f (PLAN_V0147 §6): the WAND is the rightmost row child now —
        // 🐞 sits left of it (kept 48 dp, visible, in the row).
        assertEquals("🐞 in the status row", statusRow, view.debugButton.parent)
        assertEquals("🐞 text", "🐞", view.debugButton.text.toString())
        assertEquals("🐞 VISIBLE", View.VISIBLE, view.debugButton.visibility)
        assertTrue("🐞 height > 0", view.debugButton.height > 0)

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
        // T23f (PLAN_V0147 §6): the wand is the last child (right); 🐞 stays a
        // FIXED row child left of it (the original P0 finding, adapted).
        assertSame("debugButton is a fixed child of the status row", row, view.debugButton.parent)
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

    // ==================================================================
    // T15 (Opus option H, docs/PLAN_T13_T15.md): popup headroom — the IME
    // window is 64 dp taller than the keyboard (root paddingTop), the band
    // stays TRANSPARENT (root background = InsetDrawable with top inset) and
    // TOUCH-THROUGH for the app (onComputeInsets → touchableRegion = keyboard
    // only). No visible head bar over the keyboard (root cause R1/R4).
    // ==================================================================

    /** Measures/lay outs the root like the IME framework (width EXACTLY, height UNSPECIFIED). */
    private fun layoutRoot(view: ImeRootView): ImeRootView {
        view.measure(
            View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        view.layout(0, 0, view.measuredWidth, view.measuredHeight)
        return view
    }

    @Test
    fun `T15 headroom - root paddingTop 64dp, clip off, keyboard top at stack top`() {
        val view = layoutRoot(buildRoot())
        val density = app.resources.displayMetrics.density
        val headroom = (KeyboardTheme.POPUP_HEADROOM_DP * density).roundToInt()

        assertEquals("headroomPx = POPUP_HEADROOM_DP · density", headroom, view.headroomPx)
        assertEquals("root paddingTop = headroom (window taller than the keyboard)",
            headroom, view.paddingTop)
        assertFalse("root does not clip the popup away (clipChildren)", view.clipChildren)
        assertFalse("root does not clip the popup away (clipToPadding)", view.clipToPadding)

        // keyboard surface top in window = stack top = paddingTop: in RELEASE this
        // is exactly the KeyboardView top edge (statusRow GONE); in DEBUG it INCLUDES
        // the 48-dp band (mic/🐞 stay inside the touchable region).
        assertEquals(headroom, view.keyboardTopInWindow())
        // the stack begins below the headroom, the keyboard below the status row:
        val stack = view.keyboardView.parent as LinearLayout
        assertEquals(headroom, stack.top)
        assertEquals(headroom + view.statusRow.height, stack.top + view.keyboardView.top)
    }

    @Test
    fun `T15 headroom band stays transparent - no visible head bar over the keyboard`() {
        val view = layoutRoot(buildRoot())
        assertTrue("root background = InsetDrawable (head band transparent)",
            view.background is android.graphics.drawable.InsetDrawable)

        // Root background ALONE on the raster (NOT via view.draw — Robolectric does
        // not apply the child canvas translation to the bitmap): it must leave the
        // headroom strip FULLY transparent and paint only from headroomPx downwards.
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.background.setBounds(0, 0, view.width, view.height)
        view.background.draw(Canvas(bitmap))
        assertEquals("headroom pixel (left) transparent", 0, bitmap.getPixel(5, 5) ushr 24)
        assertEquals(
            "headroom pixel (upper edge, last row) transparent",
            0,
            bitmap.getPixel(view.width / 2, view.headroomPx - 1) ushr 24,
        )
        assertEquals(
            "background paints only from headroomPx (top inset = headroomPx)",
            KeyboardTheme.palette(isDark = false).background,
            bitmap.getPixel(5, view.headroomPx + 5),
        )

        // the opaque surface moved to the stack — it starts at root paddingTop
        // (layout-proven): nothing opaque lies in the headroom band.
        val stack = view.keyboardView.parent as LinearLayout
        assertTrue("stack carries the opaque background (ColorDrawable)",
            stack.background is android.graphics.drawable.ColorDrawable)
        assertEquals(
            "stack background = palette background (S4 root contract)",
            KeyboardTheme.palette(isDark = false).background,
            (stack.background as android.graphics.drawable.ColorDrawable).color,
        )
        assertEquals("stack starts below the headroom", view.paddingTop, stack.top)
    }

    /**
     * T15: onComputeInsets VALUES — VoiceKeyboardService.onComputeInsets delegates
     * 1:1 to [applyImeInsets] with exactly these root values (pure seam, testable
     * without a running service): contentTopInsets = visibleTopInsets =
     * keyboard top, TOUCHABLE_INSETS_REGION with (0, keyboardTop, width, bottom) —
     * touches in the headroom pass through to the app, the keyboard stays touchable.
     */
    @Test
    fun `T15 onComputeInsets values - content and visible top = keyboard top, region keyboard only`() {
        val view = layoutRoot(buildRoot())
        val insets = InputMethodService.Insets()

        applyImeInsets(
            insets = insets,
            keyboardTop = view.keyboardTopInWindow(),
            keyboardBottom = view.keyboardBottomInWindow(),
            width = view.width,
        )

        val top = view.keyboardTopInWindow()
        val bottom = view.keyboardBottomInWindow()
        assertEquals("contentTopInsets = keyboard top", top, insets.contentTopInsets)
        assertEquals("visibleTopInsets = keyboard top", top, insets.visibleTopInsets)
        assertEquals(
            "touchableInsets = REGION (headroom passes touches through)",
            InputMethodService.Insets.TOUCHABLE_INSETS_REGION,
            insets.touchableInsets,
        )
        assertEquals(
            "touchableRegion = (0, keyboardTop, width, keyboardBottom) — keyboard only",
            Rect(0, top, view.width, bottom),
            insets.touchableRegion.bounds,
        )
        // headroom strip (0 until top) lies OUTSIDE the region: app touches there
        // reach the app (acceptance: touch-through 30 dp above the keyboard).
        assertTrue("region top edge is below the headroom strip", insets.touchableRegion.bounds.top > 0)
    }

    /**
     * T15b (Chrome-Enter-Bug 0.1.43): EXTENDED window — the root is taller than the
     * stack (imitation of the measured "Requested w=1080 h=2204" fullscreen state,
     * here via extra bottom padding = window overhang below the keyboard). The
     * region MUST end at the STACK bottom — NOT at root.bottom (old behavior:
     * footer + Enter row below the region = touch-dead).
     */
    @Test
    fun `T15b extended window - region ends at stack bottom, not root bottom`() {
        val view = buildRoot()
        val overhang = (120 * app.resources.displayMetrics.density).toInt()
        // root taller than the keyboard surface: extra bottom padding = window overhang
        view.setPadding(0, view.headroomPx, 0, overhang)
        view.measure(
            View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        view.layout(0, 0, view.measuredWidth, view.measuredHeight)

        val top = view.keyboardTopInWindow()
        val bottom = view.keyboardBottomInWindow()
        assertTrue("test precondition: root overhang below the stack", view.bottom > bottom)
        assertTrue("stack surface is real", view.keyboardSurfaceValid)

        val insets = InputMethodService.Insets()
        applyImeInsets(insets = insets, keyboardTop = top, keyboardBottom = bottom, width = view.width)

        assertEquals(
            "region ends at the STACK bottom — the overhang is NOT touchable",
            Rect(0, top, view.width, bottom),
            insets.touchableRegion.bounds,
        )
        assertTrue(
            "region bottom < root bottom (footer/Enter stay INSIDE the region)",
            insets.touchableRegion.bounds.bottom < view.bottom,
        )
    }

    /**
     * T15b: guard — an UNLAID-OUT root (before layout) or a collapsed stack keeps
     * the super insets values; onComputeInsets returns early
     * ([ImeRootView.keyboardSurfaceValid]).
     */
    @Test
    fun `T15b not laid out - keyboardSurfaceValid false, super insets stand`() {
        val fresh = buildRoot()
        assertFalse("fresh root: not laid out ⇒ invalid", fresh.keyboardSurfaceValid)

        val collapsed = buildRoot()
        collapsed.measure(
            View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.EXACTLY),
        )
        collapsed.layout(0, 0, collapsed.measuredWidth, collapsed.measuredHeight)
        assertFalse(
            "height-0 offer: root/stack collapsed ⇒ invalid (super values stand)",
            collapsed.keyboardSurfaceValid,
        )
    }

    /**
     * T15b: this product has NO extract/fullscreen UI — the fullscreen evaluation
     * (which made the IME window 2204 px tall, root cause of the corrupt insets
     * state with touch-dead Enter row) is unconditionally off.
     */
    @Test
    fun `T15b fullscreen mode suppressed - no extract UI, window stays keyboard-tall`() {
        val service = Robolectric.buildService(VoiceKeyboardService::class.java).get()
        assertFalse("onEvaluateFullscreenMode = false", service.onEvaluateFullscreenMode())
    }

    /**
     * T15 fix (emu 5564 re-measure): the opaque 243 strip between the window top
     * and stack.top is the IME WINDOW/DECOR background (Theme.VoiceKeyboard
     * windowBackground — the root view layer is clean). VoiceKeyboardService.onCreate
     * sets the window background transparent AT RUNTIME (no theme change —
     * SetupActivity shares the theme). Provable here: onCreate applies exactly this
     * to the lazily created IME dialog window.
     */
    @Test
    fun `T15 IME window background transparent - decor paints nothing in the headroom band`() {
        val service = Robolectric.buildService(VoiceKeyboardService::class.java).get()
        service.onCreate()

        val window = service.getWindow()?.window
            ?: error("IME window must exist after onCreate (lazily created Dialog)")
        val background = Shadows.shadowOf(window).backgroundDrawable
        assertTrue(
            "window background = transparent ColorDrawable (headroom shows the app)",
            background is android.graphics.drawable.ColorDrawable &&
                (background as android.graphics.drawable.ColorDrawable).alpha == 0,
        )
    }

    /**
     * T15 hardening: the keyboard surface fill is EXPLICITLY bounded to the view —
     * with the root unclipped (clipChildren/clipToPadding=false for the popup)
     * canvas.drawColor would fill the ENTIRE inherited clip = the whole window,
     * including the transparent headroom band. Draw-op proof (ShadowCanvas rect
     * history): the fill is exactly the view bounds, and NO surface-colored rect
     * leaves [0, height].
     */
    @Test
    fun `T15 keyboard surface fill bounded to the view - never into the headroom band`() {
        val view = KeyboardView(app)
        view.setLayout(LayoutRepository(app).loadOrFallback("de-de"), 1080)
        view.measure(
            View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        view.layout(0, 0, view.measuredWidth, view.measuredHeight)
        assertTrue("test precondition: view laid out", view.width > 0 && view.height > 0)

        val canvas = Canvas(Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888))
        view.draw(canvas)
        val sh = Shadows.shadowOf(canvas)
        val surface = KeyboardTheme.palette(isDark = false).background

        var fullFill = false
        for (i in 0 until sh.rectPaintHistoryCount) {
            val event = sh.getDrawnRect(i)
            if (event.paint.color == surface) {
                if (event.rect == RectF(0f, 0f, view.width.toFloat(), view.height.toFloat())) {
                    fullFill = true
                }
                assertTrue(
                    "surface-colored rect ${event.rect} stays inside the view bounds",
                    event.rect.top >= 0f && event.rect.bottom <= view.height &&
                        event.rect.left >= 0f && event.rect.right <= view.width,
                )
            }
        }
        assertTrue(
            "surface fill = exactly the view bounds (no full-canvas drawColor)",
            fullFill,
        )
    }

    // ==================================================================
    // T18 (PLAN_T18_HEIGHT): the nav bottom padding ignores the VISIBILITY
    // of the system bars — a transient hidden bar (browser fullscreen/scroll)
    // must not pull the footer row into the IME-switch strip.
    // navBottomPx() is the ONE seam the setOnApplyWindowInsetsListener uses.
    // ==================================================================

    /**
     * Nav-bar test insets (real AOSP WindowInsets.Builder, verified against the
     * SDK-35 framework Robolectric runs on): "vis" = getInsets(navigationBars)
     * (visibility-dependent — what 0.1.43 padded with), "ign" =
     * getInsetsIgnoringVisibility(navigationBars) (the T18 fix source).
     */
    private fun navInsets(vis: Int, ign: Int): WindowInsets =
        WindowInsets.Builder()
            .setInsets(WindowInsets.Type.navigationBars(), Insets.of(0, 0, 0, vis))
            .setInsetsIgnoringVisibility(
                WindowInsets.Type.navigationBars(),
                Insets.of(0, 0, 0, ign),
            )
            .setVisible(WindowInsets.Type.navigationBars(), vis > 0)
            .build()

    /**
     * User phone (PLAN_T18_HEIGHT measurement): 420 dpi → density 2.625 — the
     * 120-dp cap is 315 px there, so the REAL nav value 132 px passes UNCAPPED.
     * (Robolectric default is 160 dpi/density 1.0 — its cap 120 px would clamp 132;
     * navBottomPx takes the density as a parameter exactly so tests can pin it.)
     */
    private val deviceDensity = 2.625f

    @Test
    fun `T18 navBottomPx - nav bar VISIBLE 132 - padding 132`() {
        assertEquals(132, navBottomPx(navInsets(vis = 132, ign = 132), deviceDensity))
    }

    @Test
    fun `T18 navBottomPx - transient hidden bar (vis 0, ign 132) - padding stays 132`() {
        // DER Fix-Fall (user screenshots): 0.1.43 padded with max(gest, sysWin) = 0
        // here — the footer rounded into the system IME-switch strip.
        assertEquals(132, navBottomPx(navInsets(vis = 0, ign = 132), deviceDensity))
    }

    @Test
    fun `T18 navBottomPx - null insets and empty insets - padding 0`() {
        assertEquals("null insets -> 0", 0, navBottomPx(null, deviceDensity))
        assertEquals(
            "empty insets (all NONE) -> 0",
            0,
            navBottomPx(WindowInsets.Builder().build(), deviceDensity),
        )
    }

    @Test
    fun `T18 navBottomPx - anomaly 400 - capped at 120dp (P0 cap kept)`() {
        // P0-0.1.20 INSET ANOMALY cap: a similarly large bottom system inset must
        // never squeeze the whole stack — the cap moved INTO navBottomPx unchanged.
        assertEquals("400 at Robolectric density 1.0 -> cap 120 px",
            120, navBottomPx(navInsets(vis = 400, ign = 400), 1.0f))
        assertEquals("400 at device density 2.625 -> cap 120 dp = 315 px",
            (120 * deviceDensity).roundToInt(),
            navBottomPx(navInsets(vis = 400, ign = 400), deviceDensity))
    }

    /**
     * T18 late insets at a REAL ImeRootView (the listener path, dispatched like
     * the framework does): first insets 0 (bar hidden), then ignoring-visibility
     * 132 — the root bottom padding follows WITHOUT an IME switch.
     * The listener reads the density from the view resources: @Config(qualifiers)
     * pins the USER PHONE density (420 dpi → 2.625, cap 315 px) so the REAL nav
     * value 132 px passes uncapped — at the Robolectric default (160 dpi) the
     * cap 120 px would clamp it.
     */
    @Test
    @Config(qualifiers = "420dpi")
    fun `T18 late insets at the root - first 0, then ign 132 - paddingBottom 132`() {
        assertEquals("precondition: 420 dpi qualifier active (density 2.625)",
            2.625f, app.resources.displayMetrics.density, 0.0001f)

        val view = buildRoot()
        view.dispatchApplyWindowInsets(WindowInsets.Builder().build())
        assertEquals("no nav inset: padding 0", 0, view.paddingBottom)

        view.dispatchApplyWindowInsets(navInsets(vis = 0, ign = 132))
        assertEquals(
            "transient hidden bar: padding follows ign 132",
            132,
            view.paddingBottom,
        )
    }
}
