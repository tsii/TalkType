package de.voicekeyboard.keyboard

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.Bundle
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityNodeProvider
import de.voicekeyboard.layouts.CaseMapper
import de.voicekeyboard.layouts.KeyActions
import de.voicekeyboard.layouts.KeyKind
import de.voicekeyboard.layouts.LayoutAsset
import kotlin.math.roundToInt

/**
 * Own keyboard view (no android.inputmethodservice.KeyboardView).
 * Renders keys from a KeyboardModel and handles touch with pointer capture:
 * a movement stays on the key of the DOWN event until UP/CANCEL.
 *
 * Layer switch: `layer-symbols`/`layer-base` are handled here (analogous to Shift),
 * including model rebuild and re-render. Shift logic only affects the
 * letter layers (base/shift); the symbol layers are never caps-toggled (DESIGN §7.1).
 */
class KeyboardView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    interface Listener {
        fun onTextInput(text: String)
        fun onAction(action: String)

        /**
         * D10 remainder §1: footer-row Mic as a HOLD GESTURE — DOWN starts the
         * recording (service: onMicDown). The deliberate tap path (onAction MIC)
         * stays for accessibility; the touch gesture goes through here.
         */
        fun onMicHoldStart() {}

        /**
         * D10 remainder §1 / T3 (v0.1.35): release — finger UP (cancelled=false)
         * ends WITH a final; ACTION_CANCEL (cancelled=true) aborts WITHOUT
         * a final decode. Only the pointer that pressed the Mic delivers this
         * event. [holdDurationMs] = DOWN→UP duration from `event.eventTime`
         * (T3: tap ≤ 300 ms vs. a real hold-release — purely additional
         * information, no rework of the gesture).
         */
        fun onMicHoldEnd(cancelled: Boolean, holdDurationMs: Long) {}

        /**
         * B2w: backspace repeat in WORD mode (held ≥
         * [BackspaceRepeat.WORD_THRESHOLD_MS] = 2000 ms) — ONE whole word per
         * firing (3 words/s) instead of a single character. Internal gesture of
         * the repeat timer (no layout action); the character path stays on
         * onAction(BACKSPACE).
         */
        fun onBackspaceWord() {}
    }

    var listener: Listener? = null

    private val density = resources.displayMetrics.density

    // S3 — stock tokens (KeyboardTheme, DESIGN_STOCK_LOOK §1): palette by system
    // light/dark (no target-app-theme guessing); geometry nominal values 55/44 dp per
    // row count, gaps 0, radius 0. The FIT logic (rows/hits/scaling)
    // stays unchanged from S1/S2 — only the nominal values now follow the spec.
    private val palette: KeyboardPalette = KeyboardTheme.palette(
        isDark = (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES,
    )
    private val rowGapPx = KeyboardTheme.ROW_GAP_DP * density
    private val keyGapPx = KeyboardTheme.KEY_GAP_DP * density
    private val cornerRadius = KeyboardTheme.KEY_RADIUS_DP * density
    /** F36/V2 §7: 2 px gap per key side (@ 2.625) — the surface shrinks, the
     *  gap shows the background (the only key delimitation, no separator stroke). */
    private val keyFugePx = KeyboardTheme.KEY_FUGE_DP * density

    /** Spec §1: 55 dp per row at ≤ 4 rows, 44 dp at ≥ 5 (PT stays five-row). */
    private fun desiredRowHeightPx(rows: Int): Float = KeyboardTheme.rowHeightDp(rows) * density

    var layout: LayoutAsset? = null
        private set
    var activeLayerName: String = "base"
        private set
    var capsLock: Boolean = false
        private set

    /** True if the shift uppercase form of the letters is currently shown. */
    val shiftShowing: Boolean
        get() = (activeLayerName == "shift")

    private var shiftTemporary: Boolean = false // one-off shift until the next letter
    private var symbolShift: Boolean = false // within the symbol layers: symbols vs. symbolsShift

    private var model: KeyboardModel? = null

    /** P0 (2026-11-01): test access to the measured grid (render proof of the
     *  glyph paths needs the key centers) — read-only, no setter. */
    internal val currentModel: KeyboardModel?
        get() = model

    /**
     * T21/S2: fit of the CURRENT measurement (instance field, no global state).
     * Recomputed on EVERY onMeasure and passed through to rebuildModel/rebuilds
     * — a fit from an earlier opening cannot carry over, because every measurement
     * completely replaces it and every new instance starts at null.
     */
    private var currentFit: KeyboardHeight.HeightFit? = null
    /** ID → source key of the active layer; built once in rebuildModel (no O(n²) in onDraw). */
    private var sourceKeys: Map<String, de.voicekeyboard.layouts.LayoutKey> = emptyMap()

    // S3 — surface-fill paint (key surface flat; color per state in onDraw).
    private val keyPaint = Paint().apply { textAlign = Paint.Align.CENTER }

    // S3 — typography by role (spec §1, T2/vc36): 26 sp letters, 24 sp digits,
    // 14 sp function, 12 sp space/status — as sp (fontScale via scaledDensity).
    // The COLORS are set per state in onDraw (pressed does NOT change the
    // text color; toggled only tints the glyph).
    private val letterPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = KeyboardTheme.SP_LETTER_SP * resources.displayMetrics.scaledDensity
    }
    private val digitPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = KeyboardTheme.SP_DIGIT_SP * resources.displayMetrics.scaledDensity
    }
    private val functionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = KeyboardTheme.SP_FUNCTION_SP * resources.displayMetrics.scaledDensity
    }
    // D10 §2: prominent 26-dp microphone in the Mic cell ("icon size 26 dp") —
    // visible character larger, color like function text (state table S3/S4).
    private val micPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = 26f * density
    }
    // F27b: Shift/Backspace/Enter icons larger (24 dp instead of 14 sp function text).
    private val actionGlyphPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = KeyboardTheme.ACTION_GLYPH_DP * density
    }
    // F29: the three action glyphs as paths — IDENTICAL stroke width (font glyphs
    // ⇧/⌫/⏎ draw lines of varying thickness).
    private val actionGlyphStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = KeyboardTheme.ACTION_GLYPH_STROKE_DP * density
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private val largeActionGlyphs = setOf(
        KeyActions.SHIFT, KeyActions.BACKSPACE, KeyActions.ENTER,
    )
    private val secondaryPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = KeyboardTheme.SP_SECONDARY_SP * resources.displayMetrics.scaledDensity
    }
    // F22 (D12): long-press menu paints — surface palette.keyPressed, glyph 24 sp
    // (docs/DESIGN_KEY_POPUP.md §1); no new colors, no shadow/border.
    private val popupPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val popupGlyphPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = KeyboardTheme.POPUP_GLYPH_SP * resources.displayMetrics.scaledDensity
    }
    // F23a2 (D12 §7.1): hint legend 0.5 × actual main label size, capped
    // by the theme spec (SP_KEY_HINT_SP); color = palette.textSecondary (§7.3).
    private val hintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = minOf(
            letterPaint.textSize * 0.5f,
            KeyboardTheme.SP_KEY_HINT_SP * resources.displayMetrics.scaledDensity,
        )
    }
    private val rect = RectF()

    private var activePointerId = MotionEvent.INVALID_POINTER_ID
    private var pressedKey: KeyboardModel.MeasuredKey? = null

    // ---- F22 (D12): long-press menu — adapter state, geometry via LongPressMenu ----

    /** Open while the selection grid is shown; null = no menu. */
    private class LongPressPopup(
        val key: KeyboardModel.MeasuredKey,
        val glyphs: List<String>,
        val cells: Int,
        val left: Int,
        val top: Int,
        val width: Int,
        val height: Int,
        val cellPx: Int,
        val padPx: Int,
        /** F24e0: optional action cells (indexed in parallel to glyphs); empty = all text. */
        val actionCells: List<String> = emptyList(),
    )

    private var popup: LongPressPopup? = null
    private var popupSelected = -1
    private var armedKey: KeyboardModel.MeasuredKey? = null
    private var armedLayer: String = ""
    private var downX = 0f
    private var downY = 0f
    /** Movement outside the arming zone: disarm — a gesture never re-arms (F22-L). */
    private var armCancelledByMove = false
    /**
     * F24e5c: the REAL palette is computed once on arming and remembered
     * (-1 = not set), so the MOVE zone is never built from newly computed,
     * possibly different values (device finding: the zone stayed at about
     * +/-150 px, armZoneOffsets from a MOVE recomputation had no effect).
     */
    private var armedMenuLeftPx = -1
    private var armedMenuWidthPx = -1
    private val popupRadius = KeyboardTheme.POPUP_RADIUS_DP * density
    private val moveCancelPx = KeyboardTheme.POPUP_MOVE_CANCEL_DP * density
    // F22-L: popup dimensions as fields — the arming zone must never be tighter than
    // the later popup (otherwise one slides out before the popup is open).
    private val popupHeightPx = (KeyboardTheme.POPUP_HEIGHT_DP * density).roundToInt()
    private val popupGapPx = (KeyboardTheme.POPUP_GAP_DP * density).roundToInt()
    private val longPressRunnable = Runnable { openPopupIfArmed() }

    init {
        // Allows TalkBack/uiautomator to see the keyboard as a clickable element;
        // the individual keys are reachable via the AccessibilityNodeProvider (below).
        isClickable = true
        contentDescription = "Keyboard"
    }

    fun setLayout(asset: LayoutAsset, widthPx: Int) {
        layout = asset
        activeLayerName = "base"
        shiftTemporary = false
        capsLock = false
        symbolShift = false
        rebuildModel(widthPx)
        requestLayout()
        invalidate()
    }

    /**
     * L-02: fully reset Shift/Caps/state (after a language or layer switch no
     * dead shift state may stay active — otherwise the next key commits the
     * uppercase form, e. g. "P" instead of "p").
     */
    fun resetShiftState() {
        shiftTemporary = false
        capsLock = false
        symbolShift = false
        if (activeLayerName == "shift") activeLayerName = "base"
        rebuildModel(width.takeIf { it > 0 } ?: lastModelWidth)
        invalidate()
    }

    private fun rebuildModel(widthPx: Int, fit: KeyboardHeight.HeightFit? = currentFit) {
        val asset = layout ?: return
        // F22 (§2): rebuild/re-resize discards timer and menu — a menu of an old generation
        // must never commit into a new layer/size.
        // F24e5d: only on a real generation change (layer/width/height). A
        // remeasure with an identical stamp (IME layout pass while the gesture
        // is held) must not kill the arming — otherwise a swing into the menu row
        // can never open the palette (device finding F24e5d).
        val stamp = "$activeLayerName|$widthPx|${fit?.viewHeightPx}"
        if (stamp != modelStamp) {
            modelStamp = stamp
            discardLongPress()
        }
        if (widthPx > 0) lastModelWidth = widthPx
        val layer = asset.layers[activeLayerName] ?: asset.layers.getValue("base")
        model = KeyboardModel(
            layer = layer,
            totalWidthPx = widthPx.toFloat(),
            keyHeightPx = desiredRowHeightPx(layer.rows.size), // S3: 55/44 dp per row count
            rowGapPx = rowGapPx,
            keyGapPx = keyGapPx,
            fit = fit,
        )
        sourceKeys = layer.rows.flatten().associateBy { it.id }
    }

    /** Text this key commits in the current state (language-sensitive case mapping). */
    private fun textFor(key: KeyboardModel.MeasuredKey): String {
        val asset = layout ?: return key.value
        val src = sourceKeys[key.id] ?: return key.value
        if (src.kind != KeyKind.TEXT) return src.value
        // shift/symbolsShift layers already carry the target form in their value; base/symbols we map ourselves.
        return if (activeLayerName == "base" || activeLayerName == "symbols") {
            if (shiftShowing) CaseMapper.upper(src.value, asset.language.caseLocale) else src.value
        } else {
            src.value
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        // T21/S2: offer = height spec size; UNSPECIFIED (≤ 0) counts as unlimited,
        // then maxPx decides. The offer of this view is the child spec of the
        // ImeRootView chain — navigation inset AND status row are already
        // subtracted in it EXACTLY ONCE each (evidence in ImeRootView.kt):
        //  - Navigation: setOnApplyWindowInsetsListener → setPadding(…, nav) sets
        //    the inset as the root's bottom padding; FrameLayout thereby reduces the
        //    measure spec of the stack — KeyboardView computes with navInsetPx = 0.
        //  - Status row: the vertical stack measures the statusRow (48 dp) first
        //    and subtracts its height from the child measure spec of the KeyboardView
        //    (LinearLayout.onMeasure → measureChildBeforeLayout) — hence
        //    statusLinePx = 0 here. A second subtraction would be the "exactly once" violation.
        val offeredPx = if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.UNSPECIFIED) {
            0
        } else {
            MeasureSpec.getSize(heightMeasureSpec)
        }
        val rows = layout?.layers?.get(activeLayerName)?.rows?.size
            ?: layout?.layers?.getValue("base")?.rows?.size ?: 1
        // S1: ALL rows are scaled, none is dropped (no row drop).
        // S3: nominal values from KeyboardTheme (55/44 dp per row count, gap 0).
        val fit = KeyboardHeight.fitHeight(
            offeredPx = offeredPx,
            rows = rows,
            desiredRowPx = desiredRowHeightPx(rows).toInt(),
            desiredRowGapPx = rowGapPx.toInt(),
            statusLinePx = 0, // subtraction happens in the stack (evidence above) — exactly once
            navInsetPx = 0,   // subtraction happens in the root padding (evidence above) — exactly once
            maxPx = (MAX_HEIGHT_DP * density).toInt(),
            minKeyHeightPx = (desiredRowHeightPx(rows) * 0.6f).toInt().coerceAtLeast(1),
        )
        currentFit = fit
        // Re-measure on EVERY onMeasure (width AND current fit): a
        // model with a stale fit can never be drawn this way.
        if (width > 0) rebuildModel(width, fit)
        setMeasuredDimension(width, fit.viewHeightPx)
    }

    /** Width the model was last built with (P0: real view width, not right edge). */
    private var lastModelWidth: Int = -1

    /**
     * F24e5d: generation stamp of the built model (layer|width|height).
     * A remeasure with identical parameters is NOT a generation change —
     * previously rebuildModel tore down timer and arming (discardLongPress)
     * on EVERY onMeasure/onSizeChanged, even in the middle of a held
     * long-press gesture: far-away menu cells never opened, nearby ones
     * only when the remeasure happened to be skipped.
     */
    private var modelStamp = ""

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        // P0: rebuild also on size changes — with the fit of the CURRENT
        // measurement (instance field, T21: no global state).
        rebuildModel(w, currentFit)
        invalidate()
    }

    /** T21: drawn keys (complete stock, no row drop) — readable for
     *  instrumented tests (StockKeyboardFitTest) and diagnostics; read-only snapshot. */
    val drawnKeys: List<KeyboardModel.MeasuredKey>
        get() = visibleKeys()

    /** T21: hit detection via the same path as touch/accessibility — readable
     *  for instrumented tests (rect midpoint must yield its own ID). */
    fun keyAtPoint(x: Float, y: Float): KeyboardModel.MeasuredKey? = keyAtVisible(x, y)

    /**
     * T19: ONLY the rows that lie WITHIN the determined height — safety
     * net; since S1 the fit contains ALL rows scaled, so nothing falls out
     * here anymore (height is the live view height, no cache).
     */
    private fun visibleKeys(): List<KeyboardModel.MeasuredKey> {
        val m = model ?: return emptyList()
        val limit = height + 0.5f // the grid lies on whole rows; 0.5 px tolerance
        return m.keys.filter { it.bottom <= limit }
    }

    /** Hit detection only in the visible area (see [visibleKeys]). */
    private fun keyAtVisible(x: Float, y: Float): KeyboardModel.MeasuredKey? =
        model?.keyAt(x, y)
            ?.takeIf { it.bottom <= height + 0.5f }
            ?.takeIf { it.kind != "margin" } // F27a1/a2: MARGIN = pure width, no touch

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (model == null) return
        // S3: fill the canvas opaquely with the keyboard surface (spec §1 — root and canvas
        // opaque, flat; normal keys are identical to the background).
        canvas.drawColor(palette.background)
        for (key in visibleKeys()) {
            // F27a2: MARGIN = transparent width — no KeySurface, no glyph,
            // no touch (hit test ignores it above); the digit grid stays drawn.
            if (key.kind == "margin") continue
            val pressed = key === pressedKey
            val isMic = key.kind == "action" && key.value == KeyActions.MIC
            // D11: surface/glyph decision via the pure seam (KeySurface) —
            // micRecording > caps fill (real capsLock, stays pressed) > pressed > normal;
            // glyph contrary to the fill, temporary shift only a glyph accent.
            val isShiftKey = key.kind == "action" && key.value == KeyActions.SHIFT
            val decision = KeySurface.decide(
                istShiftKey = isShiftKey,
                capsLock = capsLock,
                shiftTemporary = shiftTemporary,
                symbolShift = symbolShift,
                pressed = pressed,
                micRecording = isMic && micRecording,
                activeLayerIsSymbols = activeLayerName == "symbols" || activeLayerName == "symbolsShift",
            )
            keyPaint.color = when (decision.surface) {
                KeySurface.Surface.MIC_RECORDING -> palette.accent // D10 remainder §2 unchanged
                KeySurface.Surface.CAPS_FILL -> palette.accent // D11: capsLock accent surface
                KeySurface.Surface.PRESSED -> palette.keyPressed
                KeySurface.Surface.NORMAL -> palette.keyNormal
            }
            // F36/V2 §7: key surface = full rectangle minus 2 px gap PER SIDE (gap
            // shows the background — 4 px between keys, the only delimitation, no
            // separate separator lines), corner radius from the theme (10 px @ 2.625); the
            // pressed/caps/mic state uses the same surface (color from the theme).
            rect.set(
                key.left + keyFugePx,
                key.top + keyFugePx,
                key.right - keyFugePx,
                key.bottom - keyFugePx,
            )
            if (cornerRadius > 0f) {
                canvas.drawRoundRect(rect, cornerRadius, cornerRadius, keyPaint)
            } else {
                canvas.drawRect(rect, keyPaint) // V1 legacy path (radius 0) — V2 takes round
            }
            // Typography by role (size) + state (color — pressed does NOT change it):
            val paint: Paint = when {
                key.kind != "text" && key.value == KeyActions.MIC -> micPaint // D10: 26 dp
                // F27b: Shift/Backspace/Enter icons larger (other actions stay 14 sp).
                key.kind != "text" && key.value in largeActionGlyphs -> actionGlyphPaint
                key.kind != "text" && key.value == KeyActions.SPACE -> secondaryPaint // 12 sp
                key.kind != "text" -> functionPaint                                       // 14 sp
                key.value.length == 1 && key.value[0].isDigit() -> digitPaint            // 24 sp
                else -> letterPaint                                                      // 26 sp
            }
            paint.color = when {
                key.kind == "text" -> palette.textPrimary
                // D10 remainder §2 unchanged: check mark in the toggle glyph color.
                isMic && micCheckShowing -> palette.keyToggledGlyph
                // D11: glyph tokens from the seam — contrary color (micRecordingGlyph)
                // on accent surfaces (caps/recording), accent (keyToggledGlyph) only
                // for temporary shift / symbol shift, otherwise role color.
                decision.glyph == KeySurface.Glyph.CONTRAST -> palette.micRecordingGlyph
                decision.glyph == KeySurface.Glyph.ACCENT -> palette.keyToggledGlyph
                key.value == KeyActions.SPACE -> palette.textSecondary
                else -> palette.textFunctional
            }
            val y = key.centerY - (paint.ascent() + paint.descent()) / 2f
            // D10 remainder §2: the check mark replaces the Mic glyph for 600 ms (same cell,
            // same size — no size change, no animation).
            when {
                isMic && micCheckShowing -> canvas.drawText("✓", key.centerX, y, paint)
                // F29: Shift/Backspace/Enter as paths with IDENTICAL stroke width
                // (color like the other glyph branch: paint.color is already set).
                key.kind != "text" && key.value in largeActionGlyphs ->
                    drawActionGlyph(canvas, key.centerX, key.centerY, key.value, paint.color)
                else -> canvas.drawText(displayLabel(key), key.centerX, y, paint)
            }
            drawKeyHint(canvas, key) // F23a2: digit hint top right (§7), otherwise no-op
        }
        // F22 (D12 §1): the menu layer is drawn LAST (own path);
        // the base key stays visible — the menu sits 8 dp above it.
        popup?.let { drawLongPressMenu(canvas, it) }
    }

    // ---- F22: long-press menu — pure dimensions via LongPressMenu, here only the adapter ----

    /**
     * F23a2 (DESIGN_KEY_POPUP.md §7.2-§7.5): small digit hint legend top
     * right — ONLY text keys with a set hint in base/shift (symbols/actions/
     * space/mic/popup never, §7.4; the hint is never transformed). If the legend
     * does not fit into the key, ONLY it is omitted (§7.5, no collision faking).
     */
    private fun drawKeyHint(canvas: Canvas, key: KeyboardModel.MeasuredKey) {
        val src = sourceKeys[key.id] ?: return
        if (src.kind != KeyKind.TEXT || src.hint == null) return
        if (activeLayerName != "base" && activeLayerName != "shift") return
        val hint = src.hint
        val ink = hintPaint.measureText(hint)
        val padPx = KeyboardTheme.POPUP_PAD_DP * density
        val cx = KeySurface.hintAnchorX(key.right, padPx, ink)
        if (cx - ink / 2f < key.left) return // legend too wide for the key: out
        val rows = layout?.layers?.get(activeLayerName)?.rows?.size ?: 4
        val topDp = KeySurface.hintTopOffsetDp(
            KeyboardTheme.POPUP_PAD_DP,
            ((key.bottom - key.top) / density).toDouble(),
            KeyboardTheme.rowHeightDp(rows),
        )
        val glyphTop = key.top + topDp * density
        // Baseline from real font metrics (§7.2), not "top edge + 2 dp".
        val baseline = glyphTop - hintPaint.ascent()
        if (baseline + hintPaint.descent() > key.bottom) return // no space: out
        hintPaint.color = palette.textSecondary
        canvas.drawText(hint, cx, baseline, hintPaint)
    }

    // F23c: menu list = language alternates + hint digit at the end (pure seam
    // LongPressMenu.withHint); MAX_CELLS cap and unfolding rules there.
    private fun alternatesOf(key: KeyboardModel.MeasuredKey): List<String> {
        val src = sourceKeys[key.id] ?: return emptyList()
        return LongPressMenu.withHint(src.alternates, src.hint, LongPressMenu.MAX_CELLS)
    }

    private fun openPopupIfArmed() {
        val key = armedKey ?: return
        // Generation/pointer guard (§2): layer switched or gesture aborted
        // -> no menu. The third old part "finger no longer on the origin key"
        // (key !== pressedKey) is REMOVED per F24e5d: pressedKey is already
        // set to null while sliding (line in the MOVE branch: pressedKey = if (inKey ===
        // pressedKey) ... else null) and would have dropped the arming before the timer
        // as soon as the finger left the base key — but exactly that "hold,
        // THEN slide into the menu row" of F22-L is the intended path.
        // The guard still acts via activeLayerName/armedLayer (layer switch)
        // and armCancelledByMove (zone really left); a UP outside the
        // palette never commits anyway (popupSelectionAt / cellWins, line ~715).
        if (armCancelledByMove || activeLayerName != armedLayer) {
            if (debuggable()) {
                // TEMPORARY F24e5 DIAGNOSTICS: early abort in the timer (numbers only).
                android.util.Log.d(
                    "VK-HIT",
                    "arm-cancel reason=1 cancel=$armCancelledByMove sameLayer=${activeLayerName == armedLayer}",
                )
            }
            armedKey = null
            return
        }
        val glyphs = alternatesOf(key)
        if (glyphs.isEmpty()) {
            armedKey = null
            return
        }
        val n = LongPressMenu.cellCount(glyphs)
        val cellPx = (KeyboardTheme.POPUP_CELL_DP * density).roundToInt()
        val padPx = (KeyboardTheme.POPUP_PAD_DP * density).roundToInt()
        val menuH = popupHeightPx // F22-L: identical dimensions as the arming zone
        val gapPx = popupGapPx
        val menuW = LongPressMenu.menuWidthPx(cellPx, n, padPx)
        // If the complete geometry does not fit into the view clip: the menu does NOT
        // open (D12 §1; the flip fallback of the top row is a separate acceptance point).
        if (width <= 0 || height <= 0 || menuW > width) {
            armedKey = null
            return
        }
        // T7 (v0.1.37): popup-left from the SET of cells — default
        // centering over the base key, then clamping to both view edges
        // (menuLeftClamped), so that NO cell runs outside the view.
        val left = LongPressMenu.menuLeftClamped(n, key.centerX.toInt(), cellPx, width, padPx)
        // F28a2: row position + footer row + status row height — row 0: T1
        // two-zone rule (zone A above the key, entirely inside the view; otherwise
        // zone B: edge top=0 — NEVER below the key, user finding vc34); footer row:
        // directly above the key; middle rows: one row higher.
        val rowHeightPx = (key.bottom - key.top).toInt()
        val rowIndex = model?.keys?.count { it.top < key.top } ?: 1
        // F28a2: "is footer row" via the CONTENT of the row (space key) — not via
        // rowIndex==last: (a) the footer row is not the last model row
        // (the ⌄/globe bar lies below it), (b) rowIndex counted keys, not
        // rows. Geometry (keyTop near view bottom) only as a fallback without a model.
        val isFooterRow = model?.keys?.any { it.top == key.top && it.value == KeyActions.SPACE }
            ?: (key.top >= height - 2 * rowHeightPx)
        // T5 (vc37, user verdict emulator screenshot): with a known STATUS BAND the
        // r0 menu opens IN THE BAND (menuTopForStatusBand) — one row above the
        // letter key, right where status row + 🐞 sit. Band height = own top
        // edge in the stack (the band belongs to the root, NOT to the view);
        // row-below-top = bottom edge of the pressed key (invariant:
        // menu never below the key's row). Without a band (top=0, e. g. before the
        // layout) the T1 two-zone rule stays.
        val bandPx = this.top
        val top = LongPressMenu.menuTopPx(
            key.top.toInt(), menuH, gapPx, rowHeightPx, rowIndex, isFooterRow,
            padPx = padPx,
            cellPx = cellPx,
            // F54: space above the KeyboardView's top edge = own top edge
            // in the stack (status row height).
            spaceAbovePx = bandPx,
            statusBandPx = bandPx,
            rowBelowTop = key.bottom.toInt(),
        )
        if (top + menuH > height) {
            armedKey = null
            return
        }
        // F24e1: action cells from the asset (in parallel to alternates, hence also to
        // glyphs — a possible hint always lies BEHIND the action values);
        // only as many cells as the menu really shows.
        val actionCells = sourceKeys[key.id]?.cellActions?.take(n) ?: emptyList()
        popup = LongPressPopup(key, glyphs.take(n), n, left, top, menuW, menuH, cellPx, padPx, actionCells)
        // T5 (vc37): if the menu protrudes over the view's top edge into the STATUS BAND, the
        // view lifts BRIEFLY over the statusRow (F34: elevation 1f) — otherwise
        // the palette would stay Z-hidden behind the bar in the band (F54 family).
        // The menu stays WITHIN the stack bounds (band top = stack top) ⇒ NO
        // root/window clipping chain needed; the stack has clipChildren=false (F28a).
        // Reset on closing (discardLongPress/UP-CANCEL) — translationY and
        // visibility remain untouched (order rule).
        if (top < 0) {
            elevation = 2f
            popupAboveView = true
        }
        // F24e3 MEASURABILITY (rule 9: numbers only, no glyph/field content): this way the
        // palette lies open without guessing it. Debug build only (like the VK-HIT in ACTION_UP).
        if (debuggable()) {
            android.util.Log.d("VK-HIT", "popup-open cells=$n left=$left top=$top")
        }
        popupSelected = -1
        invalidate()
    }

    /**
     * Hit cell: LongPressMenu.pick on the interior coordinate, additionally limited
     * exactly to the cell surface — padding strips (4 dp) and the menu border never
     * hit an alternate (D12 §6 paddingIsNotACharacter).
     */
    private fun popupCellAt(p: LongPressPopup, x: Float, y: Float): Int {
        if (y < p.top + p.padPx || y >= p.top + p.padPx + p.cellPx) return -1
        val idx = LongPressMenu.pick((x - (p.left + p.padPx)).toInt(), p.cellPx + p.padPx, p.cells)
        if (idx < 0) return -1
        val cellLeft = (p.left + p.padPx).toFloat() + idx * (p.cellPx + p.padPx)
        return if (x >= cellLeft && x < cellLeft + p.cellPx) idx else -1
    }

    /**
     * Cell choice of a touch point — first the base-character gate [LongPressMenu.cellWins]
     * (F22-M: base key left, direction irrelevant — slop = moveCancelPx, origin
     * KeyboardTheme.POPUP_MOVE_CANCEL_DP = 8 dp, docs/DESIGN_KEY_POPUP.md §2
     * „8 dp / 21 px“ line; touch coordinate against the key rectangle), then the
     * exact cell surface (padding strips never hit).
     */
    private fun popupSelectionAt(p: LongPressPopup, x: Float, y: Float): Int {
        val key = p.key
        if (!LongPressMenu.cellWins(
                offsetXFromKeyCenter = (x - key.centerX).toInt(),
                offsetYFromKeyCenter = (y - key.centerY).toInt(),
                keyWidth = (key.right - key.left).toInt(),
                keyHeight = (key.bottom - key.top).toInt(),
                slopPx = moveCancelPx.toInt(),
            )
        ) {
            return -1
        }
        return popupCellAt(p, x, y)
    }

    /**
     * Commit over the existing path: text via listener.onTextInput (D12 §2);
     * F24e0: if the cell carries an action (LongPressMenu.cellAction), it is sent like
     * a key press via listener.onAction (e. g. the sendKeyEvent branches
     * of the service) — drawing, thresholds and cellWins stay untouched.
     */
    private fun commitAlternate(p: LongPressPopup, cell: Int) {
        val action = LongPressMenu.cellAction(cell, p.actionCells)
        // F24e3 MEASURABILITY (rule 9: cell index + action name from the allowlist or "-",
        // no glyph, no field content). Debug build only.
        if (debuggable()) {
            android.util.Log.d("VK-HIT", "popup-commit cell=$cell action=${action ?: "-"}")
        }
        if (action != null) {
            listener?.onAction(action)
        } else {
            listener?.onTextInput(p.glyphs[cell])
        }
        // Temporary shift is consumed ONLY on a successful commit (D12 §2).
        if (shiftTemporary) {
            shiftTemporary = false
            applyLayerState()
        }
    }

    private fun isInsideKey(key: KeyboardModel.MeasuredKey, x: Float, y: Float): Boolean =
        x >= key.left && x < key.right && y >= key.top && y < key.bottom

    /** VK-HIT logs only in the debug build (rule 9 — release stays log-free). */
    private fun debuggable(): Boolean =
        (context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0

    /** Own drawing path: opaque surface keyPressed, selected cell accent, glyph textPrimary. */
    private fun drawLongPressMenu(canvas: Canvas, p: LongPressPopup) {
        rect.set(p.left.toFloat(), p.top.toFloat(), (p.left + p.width).toFloat(), (p.top + p.height).toFloat())
        popupPaint.color = palette.keyPressed
        if (popupRadius > 0f) canvas.drawRoundRect(rect, popupRadius, popupRadius, popupPaint)
        else canvas.drawRect(rect, popupPaint)
        for (i in 0 until p.cells) {
            val cellLeft = (p.left + p.padPx).toFloat() + i * (p.cellPx + p.padPx)
            val selected = i == popupSelected
            if (selected) {
                // Highlight inside the menu's outer edge (D12 §1: clips, no border).
                popupPaint.color = palette.accent
                rect.set(cellLeft, (p.top + p.padPx).toFloat(), cellLeft + p.cellPx, (p.top + p.padPx + p.cellPx).toFloat())
                canvas.drawRect(rect, popupPaint)
            }
            popupGlyphPaint.color = if (selected) palette.micRecordingGlyph else palette.textPrimary
            val cx = cellLeft + p.cellPx / 2f
            val cy = (p.top + p.padPx).toFloat() + p.cellPx / 2f
            val base = cy - (popupGlyphPaint.ascent() + popupGlyphPaint.descent()) / 2f
            // P0 (2026-11-01): the three action glyphs in the menu come from the
            // PATH EXPORT — no font glyph (⌫ would be tofu on the device).
            when (p.glyphs[i]) {
                "⌫" -> drawActionGlyph(canvas, cx, cy, KeyActions.BACKSPACE, popupGlyphPaint.color, popupGlyphPaint.textSize)
                "⇧", "⇪" -> drawActionGlyph(canvas, cx, cy, KeyActions.SHIFT, popupGlyphPaint.color, popupGlyphPaint.textSize)
                "⏎" -> drawActionGlyph(canvas, cx, cy, KeyActions.ENTER, popupGlyphPaint.color, popupGlyphPaint.textSize)
                else -> canvas.drawText(p.glyphs[i], cx, base, popupGlyphPaint)
            }
        }
    }

    /** Discard timer + menu (layout/size/editor change, §2): 0 characters. */
    private fun discardLongPress() {
        if (armedKey != null && debuggable()) {
            // TEMPORARY F24e5d DIAGNOSTICS (numbers only): the arming died here.
            android.util.Log.d("VK-HIT", "arm-discard")
        }
        removeCallbacks(longPressRunnable)
        armedKey = null
        armedMenuLeftPx = -1 // F24e5c
        armedMenuWidthPx = -1
        if (popup != null) {
            popup = null
            popupSelected = -1
            if (popupAboveView) { // T5: lower the band overlay again (statusRow to the front)
                elevation = 0f
                popupAboveView = false
            }
        }
    }

    override fun onDetachedFromWindow() {
        discardLongPress()
        cancelBackspaceRepeat() // B2: no repeat timer after detach
        super.onDetachedFromWindow()
    }

    /** F29: the three action glyphs (⇧/⌫/⏎) as canvas paths — IDENTICAL
     *  stroke width ([KeyboardTheme.ACTION_GLYPH_STROKE_DP]) instead of font glyphs.
     *  Bounding box = ACTION_GLYPH_DP square, centered on (cx, cy). */
    /** F29/P0 (2026-11-01): the three action glyphs (⇧/⌫/⏎) as canvas paths —
     *  IDENTICAL stroke width ([KeyboardTheme.ACTION_GLYPH_STROKE_DP]) and
     *  IDENTICAL optical height (bounding box 0.60·s tall). F36/V2 §8: ⌫ = pure
     *  left arrow with base (X removed); F46: ⇧ = up arrow made of shaft +
     *  head + base in the same style; ⏎ = enter arrow. [size] = bounding box (default
     *  ACTION_GLYPH_DP; the popup passes its own glyph size). */
    /** F34/P2-3 (REVIEW_F29_F33): path cache per glyph type + size + position —
     *  previously a new Path allocation per key per onDraw (hot path). The key positions
     *  are fixed per layout, so the cache grows once and stays stable. */
    private val glyphPathCache = HashMap<Triple<String, Int, Int>, List<Path>>()

    /** F34/P2-3: build the glyph paths (2 per type) — ONCE per (value, size, cx, cy),
     *  the result is held in [glyphPathCache]. Coordinates in view pixels. */
    private fun buildActionGlyphPaths(value: String, s: Float, cx: Float, cy: Float): List<Path> {
        val outline = Path()
        val detail = Path()
        when (value) {
            KeyActions.BACKSPACE -> {
                // F36/V2 §8: ⌫ = PURE left arrow with base (the old X is removed)
                // — path points of the 1x1 spec box (shaft 0.80|0.50 → 0.30|0.50; head
                // 0.52|0.30 → 0.30|0.50 → 0.52|0.70; base 0.18|0.32 → 0.18|0.68),
                // proportional scaling (×1.5) into the s bounding box ⇒ optical
                // height 0.60·s like ⇧/⏎ ("in the ⇧/⏎ style", P0 equal-height gate),
                // stroke width ACTION_GLYPH_STROKE_DP, round caps (Paint.Cap ROUND).
                outline.moveTo(cx + 0.465f * s, cy)               // shaft right
                outline.lineTo(cx - 0.285f * s, cy)               // shaft toward the tip
                outline.moveTo(cx + 0.045f * s, cy - 0.30f * s)   // head top
                outline.lineTo(cx - 0.285f * s, cy)               // arrow tip left
                outline.lineTo(cx + 0.045f * s, cy + 0.30f * s)    // head bottom
                detail.moveTo(cx - 0.465f * s, cy - 0.27f * s)    // base top
                detail.lineTo(cx - 0.465f * s, cy + 0.27f * s)     // base bottom
            }
            KeyActions.ENTER -> {
                // ⏎: return arrow — down, to the left, tip points left
                // (optical height 0.60s like the other two)
                outline.moveTo(cx + 0.24f * s, cy - 0.30f * s)
                outline.lineTo(cx + 0.24f * s, cy + 0.10f * s)
                outline.lineTo(cx - 0.30f * s, cy + 0.10f * s)
                detail.moveTo(cx - 0.10f * s, cy - 0.10f * s)
                detail.lineTo(cx - 0.30f * s, cy + 0.10f * s)
                detail.lineTo(cx - 0.10f * s, cy + 0.30f * s)
            }
            else -> {
                // F46 (user finding v0.1.29): ⇧ = up arrow in the SAME style as
                // ⌫/⏎ — stroked path from SHAFT + ARROW HEAD (+ base bar like ⌫),
                // NO outline/filled triangle (the F39 draft looked like a triangle).
                // Proportions: V2-§8 reference (shaft/head/base), scaled into the 0.60·s
                // bounding box — the P0 equal-height gate to ⌫/⏎ stays satisfied,
                // stroke width ACTION_GLYPH_STROKE_DP (2.0 dp), round caps.
                // T2 (v0.1.35, user finding Pixel/vc34): arrow (head+bar) OFFSET
                // from the base bar — shaft bottom edge and line from
                // the pure seam KeyboardTheme.ShiftGlyph ( correspondence to
                // ⌫, where the head is offset 0.18·s from the base; previously 0.07·s:
                // merged with the 2-dp stroke — "it stood on the line").
                outline.moveTo(cx, cy + KeyboardTheme.ShiftGlyph.arrowBottomRatio() * s) // shaft bottom (T2: offset)
                outline.lineTo(cx, cy - 0.26f * s)                // shaft into the head
                outline.moveTo(cx - 0.24f * s, cy - 0.06f * s)     // head wing left
                outline.lineTo(cx, cy - 0.30f * s)                 // tip
                outline.lineTo(cx + 0.24f * s, cy - 0.06f * s)     // head wing right
                detail.moveTo(cx - 0.20f * s, cy + KeyboardTheme.ShiftGlyph.BASE_LINE_RATIO * s) // base bar like ⌫
                detail.lineTo(cx + 0.20f * s, cy + KeyboardTheme.ShiftGlyph.BASE_LINE_RATIO * s)
            }
        }
        return listOf(outline, detail)
    }

    private fun drawActionGlyph(
        canvas: Canvas, cx: Float, cy: Float, value: String, color: Int,
        size: Float = KeyboardTheme.ACTION_GLYPH_DP * density,
    ) {
        val p = actionGlyphStrokePaint
        p.color = color
        // F34/P2-3: paths from the cache (built once), onDraw only draws.
        val s = size
        val paths = glyphPathCache.getOrPut(Triple(value, s.toInt(), cx.toInt())) {
            buildActionGlyphPaths(value, s, cx, cy)
        }
        for (path in paths) canvas.drawPath(path, p)
    }

    private fun displayLabel(key: KeyboardModel.MeasuredKey): String {
        val src = sourceKeys[key.id]
        if (src?.kind == KeyKind.ACTION) {
            return when (src.value) {
                KeyActions.BACKSPACE -> "⌫"
                KeyActions.ENTER -> "⏎"
                KeyActions.SPACE ->
                    // D10 §0/§1: Space shows the NAME of the active language in its endonym
                    // („Deutsch", „English (US)", „Polski" …). The source is the EXISTING
                    // endonym table LanguageCycle.ENDONYMS — no new table;
                    // resolved via SpaceLabelRule (non-whitespace wins).
                    de.voicekeyboard.layouts.SpaceLabelRule.resolve(
                        layout?.language?.tag?.lowercase()
                            ?.let { de.voicekeyboard.ime.LanguageCycle.endonym(it) }
                            ?: src.label,
                        context.getString(de.voicekeyboard.R.string.key_space_label),
                    )
                KeyActions.SHIFT -> if (capsLock) "⇪" else "⇧"
                KeyActions.CAPS -> "⇪"
                else -> src.label
            }
        }
        return src?.label ?: key.label
    }

    private var lastShiftTapMs: Long = 0L

    /** D10 remainder §1: the pointer that pressed the footer-row Mic (hold gesture). */
    private var micHeld: KeyboardModel.MeasuredKey? = null

    /** T3 (v0.1.35): eventTime of the Mic DOWN — basis of the DOWN→UP duration for the
     *  tap/hold classification (service: micHoldReleaseDecision). */
    private var micDownEventTimeMs: Long = 0L

    /** T5 (vc37): the open popup protrudes over the view's top edge into the status band —
     *  the view is lifted over the statusRow for that time (elevation 2f > 1f, F34). */
    private var popupAboveView = false

    /** True while the recording is running — Mic surface in the accent color (D10 §2). */
    var micRecording: Boolean = false
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }

    /** D10 §2: commit check mark after a successful finish — 600 ms, no full-surface flash. */
    private var micCheckShowing = false

    // ---- B2 (PLAN_V0133 §B2): backspace repeat — pure time logic + timer adapter ----

    /** B2/B2w: schedule (450 ms start, 70 ms period; from 2000 ms whole words
     *  every 333 ms) — JVM-testable (BackspaceTest). */
    private val backspaceRepeat = BackspaceRepeat()

    /** B2/B2w: repeat firing — CHARACTER over the SAME service branch
     *  (onAction(BACKSPACE)), WORD over onBackspaceWord (same editor seam). */
    private val backspaceRepeatRunnable = Runnable {
        when (backspaceRepeat.tick(android.os.SystemClock.elapsedRealtime())) {
            BackspaceRepeatMode.CHARACTER -> listener?.onAction(KeyActions.BACKSPACE)
            BackspaceRepeatMode.WORD -> listener?.onBackspaceWord()
            null -> Unit
        }
        scheduleBackspaceRepeat()
    }

    private fun scheduleBackspaceRepeat() {
        val delay = backspaceRepeat.nextDelayMs(android.os.SystemClock.elapsedRealtime()) ?: return
        postDelayed(backspaceRepeatRunnable, delay)
    }

    /** B2: external stop (service: editor change; view: detach/UP/CANCEL). */
    fun cancelBackspaceRepeat() {
        backspaceRepeat.cancel()
        removeCallbacks(backspaceRepeatRunnable)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                activePointerId = event.getPointerId(0)
                val down = keyAtVisible(event.x, event.y)
                pressedKey = down
                // B2 (PLAN_V0133 §B2): backspace DOWN performs EXACTLY ONE action
                // and arms the repeat timer (450 ms start / 70 ms period; from
                // 2000 ms held whole words every 333 ms) —
                // UP/CANCEL ends without a second action (no double delete).
                if (down != null && down.kind == "action" && down.value == KeyActions.BACKSPACE) {
                    if (backspaceRepeat.down(android.os.SystemClock.elapsedRealtime())) {
                        handleAction(KeyActions.BACKSPACE, event.eventTime)
                    }
                    scheduleBackspaceRepeat()
                }
                // F22 (D12 §2): arming ONLY for text keys with alternates —
                // Mic/Caps/Enter/Delete/?123/Globe/Emoji/Space are ACTIONs and
                // stay on their existing path (Mic hold gesture, DOUBLE_TAP 350).
                if (down != null && down.kind == "text" && alternatesOf(down).isNotEmpty()) {
                    armedKey = down
                    armedLayer = activeLayerName
                    downX = event.x
                    downY = event.y
                    armCancelledByMove = false
                    // F24e5c: compute and remember the real palette on arming — the
                    // MOVE zone uses EXCLUSIVELY these two values.
                    val cellPx = (KeyboardTheme.POPUP_CELL_DP * density).roundToInt()
                    val padPx = (KeyboardTheme.POPUP_PAD_DP * density).roundToInt()
                    val menuW = LongPressMenu.menuWidthPx(
                        cellPx, LongPressMenu.cellCount(alternatesOf(down)), padPx,
                    )
                    if (width > 0 && menuW <= width) {
                        armedMenuWidthPx = menuW
                        // T7 (v0.1.37): the same clamped position as on opening
                        // (menuLeftClamped) — the arming zone is the ACTUAL
                        // palette, never the unclamped nominal center.
                        armedMenuLeftPx = LongPressMenu.menuLeftClamped(
                            LongPressMenu.cellCount(alternatesOf(down)),
                            down.centerX.toInt(), cellPx, width, padPx,
                        )
                    } else {
                        armedMenuWidthPx = -1
                        armedMenuLeftPx = -1
                    }
                    if (debuggable()) {
                        val (zMin, zMax) = LongPressMenu.armZoneOffsets(
                            down.centerX.toInt(), width, armedMenuWidthPx, armedMenuLeftPx,
                            moveCancelPx.toInt(),
                        )
                        android.util.Log.d(
                            "VK-HIT",
                            "popup-zone left=$armedMenuLeftPx w=$armedMenuWidthPx min=$zMin max=$zMax",
                        )
                    }
                    postDelayed(longPressRunnable, KeyboardTheme.LONG_PRESS_MS)
                }
                // D10 remainder §1: DOWN on the footer-row Mic starts the hold gesture —
                // EXCLUSIVELY THIS pointer owns it; a later second
                // pointer (e. g. 🌐) generates no synthetic Mic UP.
                if (down != null && down.kind == "action" && down.value == KeyActions.MIC) {
                    micHeld = down
                    // T3 (v0.1.35): duration measurement at the Mic DOWN — remember the
                    // eventTime of DOWN; the UP passes the duration on to the service
                    // (tap ≤ 300 ms vs. hold-release, micHoldReleaseDecision).
                    micDownEventTimeMs = event.eventTime
                    listener?.onMicHoldStart()
                }
                performClick()
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (event.findPointerIndex(activePointerId) != -1) {
                    // F22: open menu -> cell selection (drawing only, no commit);
                    // otherwise arming zone (F22-L): hold-then-slide is the
                    // intended path — aborting happens only outside the zone.
                    val p = popup
                    if (p != null) {
                        // F22-M: cell selection ONLY after the finger has really
                        // left the base key (LongPressMenu.cellWins, direction
                        // irrelevant) — MOVE jitter on the base key never selects a cell.
                        val cell = popupSelectionAt(p, event.x, event.y)
                        if (cell != popupSelected) {
                            popupSelected = cell
                            invalidate()
                        }
                    } else if (armedKey != null && !armCancelledByMove) {
                        // F22-L: zone rule of the pure seam instead of an 8-dp sphere —
                        // laterally keyWidth/2 + slop, upward up to the popup's top edge,
                        // downward keyHeight/2 + slop. a real slide into the
                        // menu row keeps the arming until the timer.
                        val k = armedKey!!
                        // F24e5c: NEVER recompute the bounds — the real palette
                        // remembered at arming time is authoritative (-1 = run the old mode).
                        val (armMin, armMax) =
                            if (armedMenuLeftPx >= 0 && armedMenuWidthPx > 0) {
                                LongPressMenu.armZoneOffsets(
                                    k.centerX.toInt(), width, armedMenuWidthPx, armedMenuLeftPx,
                                    moveCancelPx.toInt(),
                                )
                            } else {
                                0 to 0
                            }
                        if (!LongPressMenu.stillArmed(
                                offsetX = (event.x - downX).toInt(),
                                offsetY = (event.y - downY).toInt(),
                                keyWidth = (k.right - k.left).toInt(),
                                keyHeight = (k.bottom - k.top).toInt(),
                                slopPx = moveCancelPx.toInt(),
                                menuHeightPx = popupHeightPx,
                                gapPx = popupGapPx,
                                minOffsetX = armMin,
                                maxOffsetX = armMax,
                                // T1 (v0.1.35, debugger sharpening): position and
                                // arming switch TOGETHER — the two-zone rule
                                // (LongPressMenu.menuTopPx) NEVER opens the menu
                                // below the base key (r0 previously sat exactly
                                // over row 2 per the B1 below-fallback, user finding
                                // vc34). The F38 mirrored one (downward zone) is
                                // thereby dead in the adapter; stillArmed runs in the
                                // F22-L default: an upward/sideways swing into the
                                // menu row stays armed, downward only up to
                                // keyHeight/2 + slop. The mirrored zone remains
                                // tested in LongPressMenu as a function contract.
                                menuBelowKey = false,
                            )
                        ) {
                            armedKey = null
                            armCancelledByMove = true // the same gesture never re-arms
                            removeCallbacks(longPressRunnable)
                        } else if (debuggable()) {
                            // TEMPORARY F24e5 DIAGNOSTICS (numbers only): the zone held at this MOVE.
                            android.util.Log.d(
                                "VK-HIT",
                                "arm-hold dx=${(event.x - downX).toInt()} dy=${(event.y - downY).toInt()}",
                            )
                        }
                    }
                    val inKey = keyAtVisible(event.x, event.y)
                    // Pointer capture: the key of the DOWN stays pressed; rejection is optical only.
                    // The Mic hold gesture stays intact (release = stop,
                    // regardless of whether the finger hovers over the cell — PTT semantics).
                    pressedKey = if (inKey === pressedKey) pressedKey else null
                    invalidate()
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val idx = event.findPointerIndex(activePointerId)
                // D10 remainder §1: Mic hold gesture — release ends it (UP: with a final),
                // ACTION_CANCEL aborts (abort, no final decode). The tap path
                // (handleKey/onAction) is NOT additionally fired for the Mic
                // (no double start).
                val wasMicHold = micHeld
                micHeld = null
                // F22: popup/timer cleanup before all paths — outside and
                // CANCEL NEVER commit a character (§2), the timer never commits later either.
                val openPopup = popup
                popup = null
                popupSelected = -1
                if (popupAboveView) { // T5: lower the band overlay again (statusRow to the front)
                    elevation = 0f
                    popupAboveView = false
                }
                if (armedKey != null && debuggable()) {
                    // TEMPORARY F24e5d DIAGNOSTICS: UP(1)/CANCEL(3) during arming
                    // (numbers only) — CANCEL would be a gesture abort by the platform.
                    android.util.Log.d("VK-HIT", "arm-end action=${event.actionMasked}")
                }
                armedKey = null
                armedMenuLeftPx = -1 // F24e5c: reset the zone fields
                armedMenuWidthPx = -1
                removeCallbacks(longPressRunnable)
                // B2: backspace repeat stops deterministically on UP/CANCEL — the
                // normal UP tap path does NOT fire again for backspace (double delete).
                backspaceRepeat.cancel()
                removeCallbacks(backspaceRepeatRunnable)
                if (wasMicHold != null) {
                    // T3: DOWN→UP duration from the remembered eventTimes — purely
                    // additional information (tap ≤ 300 ms vs. hold-release).
                    listener?.onMicHoldEnd(
                        cancelled = event.actionMasked == MotionEvent.ACTION_CANCEL,
                        holdDurationMs = (event.eventTime - micDownEventTimeMs).coerceAtLeast(0L),
                    )
                } else if (openPopup != null) {
                    // F22: open menu — UP counts; UP/CANCEL outside: 0 characters.
                    if (idx != -1 && event.actionMasked == MotionEvent.ACTION_UP) {
                        // F22-M: UP without really leaving the base key falls through
                        // cellWins() back to -1 -> the base-character branch wins
                        // (D12 §2; device findings „è“ and row-0 exclusion).
                        val cell = popupSelectionAt(openPopup, event.x, event.y)
                        when {
                            cell >= 0 -> commitAlternate(openPopup, cell) // a real slide
                            isInsideKey(openPopup.key, event.x, event.y) ->
                                handleKey(openPopup.key, event.eventTime) // base character exactly once
                            else -> Unit // abort: 0 characters, no "last hit"
                        }
                    }
                } else if (idx != -1 && event.actionMasked == MotionEvent.ACTION_UP) {
                    val key = keyAtVisible(event.x, event.y)
                    // P0 MEASURABILITY: exactly ONE debug line per ACTION_UP (debug
                    // build only; no text/field contents, only key id + geometry).
                    if ((context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
                        android.util.Log.d(
                            "VK-HIT",
                            "id=${key?.id ?: "none"} value=${key?.value ?: "none"} " +
                                "rect=<${key?.left?.toInt() ?: -1},${key?.top?.toInt() ?: -1}," +
                                "${key?.right?.toInt() ?: -1},${key?.bottom?.toInt() ?: -1}> " +
                                "touch=<${event.x.toInt()},${event.y.toInt()}> " +
                                "view=${width}x${height}",
                        )
                    }
                    if (key != null && key === pressedKey &&
                        !(key.kind == "action" && key.value == KeyActions.BACKSPACE)
                    ) {
                        handleKey(key, event.eventTime) // B2: backspace already fired at DOWN
                    }
                }
                pressedKey = null
                activePointerId = MotionEvent.INVALID_POINTER_ID
                invalidate()
                return true
            }
        }
        return false
    }

    /**
     * D10 §2: short-lived commit check mark — ONLY after a genuinely accepted
     * edit (call: service in the onStopped finale), 600 ms, no size change
     * and no layout animation; the cell stays in its place.
     */
    fun showMicCheck() {
        micCheckShowing = true
        invalidate()
        postDelayed({
            micCheckShowing = false
            invalidate()
        }, MIC_CHECK_MS)
    }

    private fun handleKey(key: KeyboardModel.MeasuredKey, eventTime: Long) {
        val src = sourceKeys[key.id] ?: return
        when (src.kind) {
            KeyKind.TEXT -> {
                listener?.onTextInput(textFor(key))
                if (shiftTemporary) {
                    shiftTemporary = false
                    applyLayerState()
                }
            }
            KeyKind.DEAD -> {
                // Dead keys follow in stage 2; no effect here.
            }
            KeyKind.MARGIN -> {
                // F27a2: pure margin area — never typeable (hit test already ignores it;
                // this branch is defensive programming).
            }
            KeyKind.ACTION -> handleAction(src.value, eventTime)
        }
    }

    private fun handleAction(action: String, eventTime: Long) {
        when (action) {
            KeyActions.SHIFT -> {
                // P1 fix: in the symbol layers the shift key toggles between
                // symbols and symbolsShift (base layer: Shift/CapsLock as before).
                if (activeLayerName == "symbols" || activeLayerName == "symbolsShift") {
                    symbolShift = !symbolShift
                    switchLayer(
                        if (symbolShift) "symbolsShift" else "symbols",
                        fromSymbols = true,
                    )
                    return
                }
                // P1-02 fix: double-tap test first — the "switch off" branch previously
                // stood before the double-tap check, so the second tap switched Shift
                // off instead of activating CapsLock.
                if (eventTime - lastShiftTapMs < DOUBLE_TAP_MS && shiftTemporary && !capsLock) {
                    // Double tap on Shift → CapsLock (second tap within DOUBLE_TAP_MS).
                    capsLock = true
                    shiftTemporary = false
                    lastShiftTapMs = eventTime
                } else if (shiftTemporary || capsLock) {
                    // Shift with active temporary shift or CapsLock switches everything off.
                    shiftTemporary = false
                    capsLock = false
                } else {
                    shiftTemporary = true
                    lastShiftTapMs = eventTime
                }
                applyLayerState()
            }
            KeyActions.CAPS -> {
                capsLock = !capsLock
                shiftTemporary = false
                applyLayerState()
            }
            KeyActions.LAYER_SYMBOLS -> switchLayer(if (symbolShift) "symbolsShift" else "symbols", fromSymbols = true)
            KeyActions.LAYER_BASE -> switchLayer("base", fromSymbols = false)
            else -> listener?.onAction(action)
        }
    }

    /**
     * Layer switch including model rebuild and re-render.
     * When switching to the symbol layers the shift state of the letters
     * is frozen and restored when switching back.
     */
    private fun switchLayer(layerName: String, fromSymbols: Boolean) {
        val asset = layout ?: return
        val target = asset.layers[layerName] ?: return
        val symbolsMode = target.name == "symbols" || target.name == "symbolsShift"
        if (symbolsMode) {
            symbolShift = (target.name == "symbolsShift")
        } else {
            symbolShift = false
        }
        activeLayerName = target.name
        rebuildModel(width.takeIf { it > 0 } ?: lastModelWidth)
        requestLayout()
        invalidate()
        sendAccessibilityEvent(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)
    }

    /** Shift/CapsLock → update the layer display (effective only on the letter layers). */
    private fun applyLayerState() {
        val asset = layout ?: return
        val inLetters = activeLayerName == "base" || activeLayerName == "shift"
        if (inLetters) {
            activeLayerName = if (showShift()) "shift" else "base"
            rebuildModel(width.takeIf { it > 0 } ?: lastModelWidth)
        }
        invalidate()
        sendAccessibilityEvent(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)
    }

    private fun showShift(): Boolean = shiftTemporary || capsLock

    // ─── Accessibility (VK-05) ────────────────────────────────────────────

    /**
     * Virtual nodes per key: TalkBack/third-party apps thereby see every key
     * as its own clickable element with label (contentDescription) and
     * CLICK action (performAction → same path as touch).
     */
    override fun getAccessibilityNodeProvider(): AccessibilityNodeProvider {
        return object : AccessibilityNodeProvider() {
            override fun createAccessibilityNodeInfo(virtualViewId: Int): AccessibilityNodeInfo? {
                val key = keyForVirtualId(virtualViewId) ?: return null
                val info = AccessibilityNodeInfo.obtain()
                info.setVisibleToUser(true)
                info.packageName = context.packageName
                info.className = "android.widget.Button"
                info.text = displayLabel(key)
                info.contentDescription = accessibilityDescription(key)
                val screenBounds = accessibilityBounds(key)
                val rect = android.graphics.Rect()
                rect.set(
                    screenBounds.left.toInt(), screenBounds.top.toInt(),
                    screenBounds.right.toInt(), screenBounds.bottom.toInt(),
                )
                info.setBoundsInScreen(rect)
                info.setBoundsInParent(rect)
                info.isEnabled = true
                info.isClickable = true
                info.addAction(AccessibilityNodeInfo.ACTION_CLICK)
                info.addAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS)
                return info
            }

            override fun performAction(virtualViewId: Int, action: Int, arguments: Bundle?): Boolean {
                val key = keyForVirtualId(virtualViewId) ?: return false
                return when (action) {
                    AccessibilityNodeInfo.ACTION_CLICK -> {
                        handleKey(key, System.currentTimeMillis())
                        true
                    }
                    AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS -> true
                    else -> false
                }
            }

            override fun findFocus(focus: Int): AccessibilityNodeInfo? = null
        }
    }

    private fun keyForVirtualId(virtualViewId: Int): KeyboardModel.MeasuredKey? =
        visibleKeys().getOrNull(virtualViewId) // T19: same list as onDraw (IDs stable)

    /** Human-readable description of a key for TalkBack. */
    private fun accessibilityDescription(key: KeyboardModel.MeasuredKey): String {
        val src = sourceKeys[key.id]
        return when (src?.kind) {
            KeyKind.ACTION -> when (src.value) {
                KeyActions.BACKSPACE -> "Delete"
                KeyActions.ENTER -> "Enter key"
                KeyActions.SPACE -> "Space bar"
                KeyActions.SHIFT -> if (capsLock) "Caps lock" else "Shift"
                KeyActions.LAYER_SYMBOLS -> "Digits and symbols"
                KeyActions.LAYER_BASE -> "Letters"
                KeyActions.CURSOR_LEFT -> "Cursor left"
                KeyActions.CURSOR_RIGHT -> "Cursor right"
                KeyActions.LANGUAGE -> "Switch language"
                KeyActions.EMOJI -> "Emojis"
                KeyActions.MIC -> "Dictate"
                else -> src.label
            }
            KeyKind.TEXT -> "Key ${src?.label ?: key.label}"
            KeyKind.DEAD -> "Dead key ${src?.label ?: key.label}"
            KeyKind.MARGIN -> "" // F27a2: invisible margin area — no TalkBack name
            null -> key.label
        }
    }

    private fun accessibilityBounds(key: KeyboardModel.MeasuredKey): RectF {
        val pos = IntArray(2)
        getLocationOnScreen(pos)
        return RectF(
            pos[0] + key.left,
            pos[1] + key.top,
            pos[0] + key.right,
            pos[1] + key.bottom,
        )
    }

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.className = KeyboardView::class.java.name
        info.contentDescription = "Keyboard ${layout?.language?.label ?: ""}"
    }

    override fun onPopulateAccessibilityEvent(event: AccessibilityEvent) {
        super.onPopulateAccessibilityEvent(event)
        if (event.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED) {
            pressedKey?.let { event.text.add(accessibilityDescription(it)) }
        }
    }

    companion object {
        private const val DOUBLE_TAP_MS = 350L

        /** D10 §2: commit check mark duration ("600 ms, no full-surface flash"). */
        private const val MIC_CHECK_MS = 600L

        /** T19/S2: design cap 276 dp ≈ 727 px with navigation bar (704 px without). */
        private const val MAX_HEIGHT_DP = 276
    }
}
