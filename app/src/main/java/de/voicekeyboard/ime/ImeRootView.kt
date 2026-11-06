package de.voicekeyboard.ime

import android.content.Context
import android.content.res.Configuration
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.Typeface
import android.os.Build
import android.text.InputType
import android.text.SpannableString
import android.text.Spanned
import android.text.TextUtils
import android.text.style.StyleSpan
import android.text.style.UnderlineSpan
import android.util.AttributeSet
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.view.WindowInsets
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import de.voicekeyboard.R
import de.voicekeyboard.correct.RegionReplacer
import de.voicekeyboard.keyboard.EmojiPanelView
import de.voicekeyboard.keyboard.KeyboardPalette
import de.voicekeyboard.keyboard.KeyboardTheme
import de.voicekeyboard.keyboard.KeyboardView
import kotlin.math.roundToInt

/**
 * IME container (V2: mode switch instead of overlay, EMOJI_PANEL_DESIGN_V2 §2).
 *
 * Exactly two modes, mutually exclusive stack children:
 *  - Keyboard: statusRow VISIBLE, keyboardView VISIBLE, emoji block GONE.
 *  - Emoji:    statusRow VISIBLE, keyboardView GONE (completely, bottom row too
 *              — no invisible keyboard hit surfaces), emoji block
 *              VISIBLE with native footer button "Back to keyboard".
 *
 * No overlay math: no onLayout height handling, no margin/gravity anchors
 * for the panel (the three overlay variants are refuted by the device, M8b-3b/c/d).
 * The size contract is a design measure: emoji block 240 dp total height incl.
 * footer; EmojiPanel height=0 + weight 1 — the layout manager distributes.
 * System navigation insets are set ONCE as bottom root padding.
 */

/** T18-A: tag of the insets evidence lines (numbers only, rule 9). */
internal const val INSETS_LOG_TAG = "VK-INSETS"

/** T23f (§6): alpha of the suggestion text while NO suggestion exists (dimmed). */
private const val DIMMED_ALPHA = 0.4f

/**
 * T23f (PLAN_V0147 §6): pure field eligibility for the correction row —
 * TYPE_CLASS_TEXT minus password/web-password/visible-password/URI/e-mail
 * variants minus NO_SUGGESTIONS; other classes (numeric/phone/datetime/NULL)
 * are never eligible. Pure and top-level for the Robolectric matrix test.
 */
internal fun isCorrectionFieldEligible(inputType: Int): Boolean {
    if (inputType and InputType.TYPE_MASK_CLASS != InputType.TYPE_CLASS_TEXT) return false
    val variation = inputType and InputType.TYPE_MASK_VARIATION
    if (variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
        variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ||
        variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD ||
        variation == InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS ||
        variation == InputType.TYPE_TEXT_VARIATION_URI ||
        variation == InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
    ) {
        return false
    }
    return inputType and InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS == 0
}

/**
 * T18-A evidence line (rule 9: numbers ONLY — never text/content): "vis" is the
 * visibility-dependent nav value the current padding uses, "ign" the nav value
 * IGNORING visibility, "tap" the tappable-element value, "gest"/"sysWin" the raw
 * sources, "pad" the applied bottom root padding, "h" the view height. Both
 * events (ev=apply at insets delivery, ev=start at onStartInputView) together
 * prove whether the nav padding followed a temporarily hidden bar (T18 cause).
 */
internal fun insetsEvidenceLine(ev: String, insets: WindowInsets?, pad: Int, h: Int): String {
    val gest = insets?.systemGestureInsets?.bottom ?: -1
    val sysWin = insets?.systemWindowInsetBottom ?: -1
    val ign = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && insets != null) {
        insets.getInsetsIgnoringVisibility(WindowInsets.Type.navigationBars()).bottom
    } else {
        -1
    }
    val tap = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && insets != null) {
        insets.getInsets(WindowInsets.Type.tappableElement()).bottom
    } else {
        -1
    }
    val vis = maxOf(gest, sysWin)
    return "ev=$ev vis=$vis ign=$ign tap=$tap gest=$gest sysWin=$sysWin pad=$pad h=$h"
}

/**
 * T18 (PLAN_T18_HEIGHT): bottom navigation inset for the root padding — PURE
 * function (unit-testable without a laid-out view; the listener below passes
 * the same density source as the old cap logic: v.resources.displayMetrics).
 * API>=30: derived from getInsetsIgnoringVisibility(navigationBars) +
 * tappableElement, so a TRANSIENT hidden system bar (browser fullscreen) keeps
 * the footer OUT of the IME-switch strip — the old visibility-dependent sources
 * (systemGestureInsets/systemWindowInsetBottom) reported 0 there and the
 * stale 0 persisted until view re-creation (user screenshots).
 * API<30: fallback as before (gest/sysWin, visibility-dependent).
 * P0-0.1.20 cap kept: the bottom root padding is the ONLY mechanism that can
 * push/compress the status row — never more than 120 dp (the cap applies only
 * to the padding, the insets delivery stays untouched).
 */
internal fun navBottomPx(insets: WindowInsets?, density: Float): Int {
    val raw = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && insets != null) {
        maxOf(
            insets.getInsetsIgnoringVisibility(WindowInsets.Type.navigationBars()).bottom,
            insets.getInsets(WindowInsets.Type.tappableElement()).bottom,
        )
    } else {
        maxOf(
            insets?.systemGestureInsets?.bottom ?: 0,
            insets?.systemWindowInsetBottom ?: 0,
        )
    }
    return raw.coerceAtMost((120 * density).roundToInt())
}

class ImeRootView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : FrameLayout(context, attrs) {

    val statusText: TextView
    val micButton: Button
    /** P0 (2026-11-01): 🐞 as the FIRST view child of the statusRow (48 dp, right) —
     *  previously a dynamic addView in the service (fragile, untested, disappeared
     *  on the device). Always visible, the service only attaches the listener. */
    val debugButton: Button
    /** T9 (v0.1.38): the status row as a whole — visible in DEBUG only (see init);
     *  the visibility is accessible to tests without going over micButton.parent. */
    val statusRow: LinearLayout

    // ============================================================ T23f (§6)
    /** Correction row content (release): suggestion text, single line, START-ellipsized. */
    val correctionText: TextView
    /** Magic wand (§6, vector ic_wand — no emoji, minSdk 29): 48 dp wide, rightmost. */
    val wandButton: ImageButton
    /** Setup switch "Typing suggestions" (pref corr_row_enabled) — read via THIS
     *  supplier; the view never touches prefs (T23g injects the reader). */
    var typingSuggestionsSupplier: () -> Boolean = { true } // default ON (§6)

    /** T23l (§14.6): ALL_ACTIVE prefix tag ("DE") in front of the suggestion —
     *  the endonym moves from the space bar to the correction row; empty = off. */
    private var correctionPrefix: String = ""

    /** REVIEW_V0147 VK-2: the last setCorrection pair — the undo glyph must keep
     *  the wand CLICKABLE even after the row cleared (the only undo window
     *  lies between APPLY and the next keystroke, §5). */
    private var lastHasSuggestion = false
    private var lastEnabled = false
    /** Wand-press dispatch for T23g: APPLY or UNDO (undo glyph mode). */
    var onWandPress: ((RegionReplacer.WandPress) -> Unit)? = null

    /** T23f: eligibility of the CURRENT field (set by the service via setFieldInputType). */
    private var correctionFieldEligible: Boolean = false
    /** T23f: undo glyph mode (wand rotated + undo content description). */
    private var wandUndoMode: Boolean = false
    /** T9: FLAG_DEBUGGABLE read ONCE — debug keeps status text + 🎤 + 🐞 in the row. */
    private val debugStatusRow: Boolean =
        (context.applicationInfo?.flags ?: 0) and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0
    val keyboardView: KeyboardView
    val emojiPanel: EmojiPanelView

    /** Native way-back button in the emoji block (V2 §3): full, wide, ≥48dp, own hit test. */
    val backToKeyboardButton: Button

    /** Emoji content block: alternative stack child (no overlay). */
    private val emojiBlock: LinearLayout

    /** T15 (Opus option H): the vertical stack — promoted to a field so that
     *  [keyboardTopInWindow] stays pure view arithmetic (no re-walk of the hierarchy). */
    private val stack: LinearLayout

    /** T15 (Opus option H, docs/PLAN_T13_T15.md): transparent headroom of the IME
     *  window ABOVE the keyboard — root paddingTop ([KeyboardTheme.POPUP_HEADROOM_DP]).
     *  The long-press popup may extend into it (top ≥ −headroomPx above the stack);
     *  the band itself stays TRANSPARENT (root background = InsetDrawable with top
     *  inset = headroomPx) and TOUCH-THROUGH for the app (onComputeInsets in the
     *  service limits the touchable region to the keyboard). 64 dp = 168 px @2.625
     *  = gap 8 dp + menu height 56 dp: the r0 popup fits COMPLETELY. */
    val headroomPx: Int =
        (KeyboardTheme.POPUP_HEADROOM_DP * resources.displayMetrics.density).roundToInt()

    init {
        val density = resources.displayMetrics.density

        // S4 — stock tokens (DESIGN_STOCK_LOOK §1): ALL colors of the bar, of the
        // root and of the native buttons come from KeyboardTheme (light/dark by
        // system night mode) — no hard-coded color value in this file.
        val palette: KeyboardPalette = KeyboardTheme.palette(
            isDark = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                Configuration.UI_MODE_NIGHT_YES,
        )
        // Dark must take effect at the root too (spec §1) — opaque surface for the whole stack.
        // T15 (Opus option H): the root background is an InsetDrawable with top inset
        // = headroomPx — the headroom band stays TRANSPARENT (no visible head bar over
        // the keyboard); the opaque background moves to the stack (which starts at
        // paddingTop). Everything below (status row/emoji rest area up to the nav
        // padding) keeps the palette color via THIS inset drawable, as before a single
        // setBackgroundColor would have done.
        background = InsetDrawable(ColorDrawable(palette.background), 0, headroomPx, 0, 0)
        // T15: the popup may extend up to −(statusBandPx + headroomPx) above the
        // KeyboardView — the ROOT must not clip it away (the stack already has
        // clipChildren/clipToPadding = false since F28a). ONLY these properties —
        // no layout rebuild.
        clipChildren = false
        clipToPadding = false
        setPadding(paddingLeft, headroomPx, paddingRight, 0)

        statusText = TextView(context).apply {
            // P0 (2026-11-01, user decision): the ground state is NEVER empty —
            // previously (S4) deliberately "", because the service sets the text; the
            // service path (updateStatus) stays, "Ready" is only the
            // construction default in case it (still) did not run.
            text = "Ready"
            // Spec §1: status 12 sp, secondary text color (texts on the left).
            textSize = KeyboardTheme.SP_SECONDARY_SP
            setTextColor(palette.textSecondary)
            gravity = Gravity.CENTER_VERTICAL
            setPadding((8 * density).toInt(), 0, (8 * density).toInt(), 0)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
        }

        micButton = Button(context).apply {
            text = "🎤"
            isEnabled = true // push-to-talk: touch-down starts, touch-up stops (service wiring)
            // S4: native button without a foreign relief theme — colors from the tokens.
            setTextColor(palette.textFunctional)
            setBackgroundColor(palette.background)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.MATCH_PARENT,
            )
        }

        debugButton = Button(context).apply {
            // P0: 48-dp hit surface, RIGHT (last child of the statusRow),
            // tokens like micButton (no foreign relief theme).
            text = "🐞"
            contentDescription = "Copy/share diagnostics"
            isAllCaps = false
            minWidth = 0
            minimumWidth = 0
            setPadding(0, 0, 0, 0)
            setTextColor(palette.textFunctional)
            setBackgroundColor(palette.background)
            layoutParams = LinearLayout.LayoutParams(
                (48 * density).toInt(),
                (48 * density).toInt(),
            )
        }

        // T9 (v0.1.38): the debug flag lives in the FIELD debugStatusRow (T23f).
        //
        // T23f (PLAN_V0147 §6): the statusRow slot IS the correction row now —
        // release: 40 dp, visible iff field eligible + setup switch (see
        // refreshCorrectionRowVisibility); the debug block (status text + 🎤 +
        // 🐞, 48 dp) stays LEFT of the correction content in DEBUG.
        val rowHeightPx = ((if (debugStatusRow) 48 else 40) * density).roundToInt()

        correctionText = TextView(context).apply {
            text = ""
            textSize = 14f
            setSingleLine(true)
            ellipsize = TextUtils.TruncateAt.START // §6: single line, START
            setTextColor(palette.textPrimary)
            gravity = Gravity.CENTER_VERTICAL
            setPadding((8 * density).toInt(), 0, (8 * density).toInt(), 0)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
            visibility = GONE // shown by refreshCorrectionRowVisibility()
        }

        wandButton = ImageButton(context).apply {
            setImageResource(R.drawable.ic_wand)
            imageTintList = android.content.res.ColorStateList.valueOf(palette.textFunctional)
            background = ColorDrawable(palette.background) // no foreign relief theme
            contentDescription = context.getString(R.string.wand_apply_correction)
            setPadding(0, 0, 0, 0)
            // §6: 48x48 wand — inside the 40-dp release row the height follows
            // the row (MATCH_PARENT); in DEBUG (48-dp row) it is the full square.
            layoutParams = LinearLayout.LayoutParams(
                (48 * density).toInt(),
                LinearLayout.LayoutParams.MATCH_PARENT,
            )
            visibility = GONE
            setOnClickListener {
                onWandPress?.invoke(
                    if (wandUndoMode) RegionReplacer.WandPress.UNDO else RegionReplacer.WandPress.APPLY,
                )
            }
        }

        statusRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            // T21/S2 height budget (DESIGN_STOCK_LOOK §3): THIS row block is the
            // ONE place where the status/correction row enters the height budget —
            // explicit height (48 dp debug incl. the 48-dp debug buttons, 40 dp
            // release per §6), NOT "accidentally" pulled in via a child. The
            // vertical stack below measures the row first and subtracts its
            // height from the KeyboardView's child MeasureSpec
            // (LinearLayout.onMeasure → measureChildBeforeLayout); KeyboardView
            // therefore computes with statusLinePx = 0 — EXACTLY ONCE, no double subtraction.
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                rowHeightPx,
            )
            // S4: 1-dp separator line BELOW the bar (spec §1: separator lines 1 dp
            // below the status bar — the only fixed line, consistent also in the
            // emoji view). As a background layer WITHIN the row height so that the
            // subtraction chain (key budget) does not change again — no additional
            // stack child, no measurement change.
            val fill = GradientDrawable().apply { setColor(palette.background) }
            val line = GradientDrawable().apply { setColor(palette.separator) }
            background = LayerDrawable(arrayOf(fill, line)).apply {
                setLayerInset(1, 0, rowHeightPx - (1 * density).toInt().coerceAtLeast(1), 0, 0)
            }
            addView(statusText)
            addView(micButton)
            addView(debugButton) // P0: fixed row child (T23f: LEFT of the wand)
            addView(correctionText) // T23f: suggestion text (§6 left)
            addView(wandButton) // T23f: wand rightmost (§6 right)
            // T9 (vc39): release ⇒ debug children GONE; the ROW visibility is
            // the ONE place (T23f): DEBUG always VISIBLE (stock), release only
            // with eligible field + setup switch (GONE keeps the full height
            // budget + T1 two-zone fallback for the band overlay).
            if (!debugStatusRow) {
                statusText.visibility = GONE
                micButton.visibility = GONE
                debugButton.visibility = GONE
            }
            visibility = if (debugStatusRow) VISIBLE else GONE
        }

        keyboardView = KeyboardView(context)
        keyboardView.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        )

        // Emoji block (design measure 240dp total incl. footer): panel weighted (height=0,
        // weight=1), footer button below, outside the panel canvas/hit test.
        emojiPanel = EmojiPanelView(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0, // weight distributes — no computed height
                1f,
            )
        }
        backToKeyboardButton = Button(context).apply {
            text = context.getString(R.string.emoji_back_to_keyboard)
            // S4: native footer button in the tokens (no activity theme relief).
            setTextColor(palette.textFunctional)
            setBackgroundColor(palette.background)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                maxOf((48 * density).toInt(), minHeight),
            )
        }
        emojiBlock = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (240 * density).toInt(), // design measure incl. footer (V2 §2)
            )
            addView(emojiPanel)
            addView(backToKeyboardButton)
            visibility = GONE // start: keyboard mode
        }

        // The stack is the root's only content child.
        stack = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            // T15 (Opus option H): the opaque background lives HERE (the stack
            // starts at root paddingTop) — the headroom above stays transparent.
            setBackgroundColor(palette.background)
            // F28a: the long-press menu of the top key row is drawn OVER the
            // status bar (negative top in the KeyboardView coordinate system).
            // The stack must therefore NOT clamp its children to the child/own
            // bound, otherwise the part of the palette extending beyond the
            // view's top edge would be invisible. ONLY these two properties — no layout rebuild.
            clipChildren = false
            clipToPadding = false
            // F34/P2-2 (REVIEW_F29_F33): the statusRow lies ABOVE the
            // KeyboardView in Z order — the r0 popup (negative top) is mere canvas
            // drawing in the KeyboardView child and would leave the 🐞 button
            // touchable, but cover it visually. Elevation = Z lift WITHOUT a
            // layout/geometry change (OutlineProvider.NONE: no shadow edge).
            statusRow.elevation = 1f
            // No outline = no shadow edge; the NONE constant is not
            // resolvable in the offline SDK stub (F34 workaround 2026-11-01).
            statusRow.outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(v: View, o: android.graphics.Outline) {}
            }
            addView(statusRow)
            addView(keyboardView)
            addView(emojiBlock)
        }
        addView(stack)

        // System navigation insets as a normal bottom padding, once for the
        // entire stack (V2 §2) — no panel positioning hack.
        //
        // T21/S2 height budget (DESIGN_STOCK_LOOK §3): THIS line (setPadding with
        // `nav`) is the ONE place where the navigation inset is subtracted:
        // the FrameLayout reduces the stack's MeasureSpec by the root padding,
        // KeyboardView itself computes with navInsetPx = 0 — EXACTLY ONCE.
        // The integration contract follows from that: root offer H → keyboard child
        // receives H − nav − 48 dp and scales ALL rows into exactly this budget.
        setOnApplyWindowInsetsListener { v, insets ->
            // T18 (PLAN_T18_HEIGHT): navBottomPx derives the padding ignoring the
            // VISIBILITY of the system bars (API>=30) — a transient hidden bar
            // (browser fullscreen) must not pull the footer into the IME-switch
            // strip. The height subtraction stays EXACTLY ONCE (this setPadding
            // is the one place, see T21/S2 above); the 120-dp cap lives in
            // navBottomPx.
            val navCapped = navBottomPx(insets, v.resources.displayMetrics.density)
            v.setPadding(v.paddingLeft, v.paddingTop, v.paddingRight, navCapped)
            // T18-A: evidence line — "pad" mirrors the value applied above
            // (format unchanged, numbers only).
            Log.i(INSETS_LOG_TAG, insetsEvidenceLine("apply", insets, navCapped, v.height))
            insets
        }
    }

    // ============================================================ T23f (§6)

    /**
     * T23f: the service feeds the CURRENT field's input type; the row shows
     * only for eligible fields AND setup switch (typingSuggestionsSupplier).
     * Not-eligible/GONE keeps the keyboard's full height budget (T1 two-zone
     * fallback for the long-press band; VK-INSETS untouched).
     */
    fun setFieldInputType(inputType: Int) {
        correctionFieldEligible = isCorrectionFieldEligible(inputType)
        refreshCorrectionRowVisibility()
    }

    /**
     * Recomputes the row visibility (call after the switch supplier or the
     * eligibility changed). DEBUG keeps the row (stock) always; the correction
     * CONTENT itself follows eligibility + switch.
     */
    fun refreshCorrectionRowVisibility() {
        val show = correctionFieldEligible && typingSuggestionsSupplier()
        correctionText.visibility = if (show) VISIBLE else GONE
        wandButton.visibility = if (show) VISIBLE else GONE
        statusRow.visibility = if (debugStatusRow || show) VISIBLE else GONE
    }

    /**
     * T23l (§14.6): language prefix tag (e. g. "DE") for ALL_ACTIVE — set
     * BEFORE/with the next setCorrection; composed as "TAG · suggestion".
     */
    fun setCorrectionPrefix(tag: String) {
        correctionPrefix = tag
    }

    /**
     * T23f (§6): row content. [original]/[corrected] = the typed region and
     * the suggested replacement; words that DIFFER are rendered bold +
     * underlined (Spannable, index-aligned word diff). No suggestion
     * ([hasSuggestion] false): text cleared and dimmed, wand disabled.
     * [enabled] gates the wand action (T23g passes field-eligible && switch &&
     * dictation idle).
     */
    fun setCorrection(
        original: String?,
        corrected: String?,
        hasSuggestion: Boolean,
        enabled: Boolean,
    ) {
        correctionText.text = if (original != null && corrected != null) {
            val styled = styledCorrection(original, corrected)
            if (correctionPrefix.isEmpty()) {
                styled
            } else {
                // T23l: prefix + suggestion; the diff spans move along via append.
                android.text.SpannableStringBuilder(correctionPrefix)
                    .append(" · ")
                    .append(styled)
            }
        } else {
            ""
        }
        correctionText.alpha = if (hasSuggestion) 1f else DIMMED_ALPHA
        wandButton.isEnabled = hasSuggestion && enabled
        lastHasSuggestion = hasSuggestion
        lastEnabled = enabled
    }

    /**
     * T23f (§5): undo glyph mode — wand rotated 180° and the undo content
     * description while an undo level exists (service feeds hasUndoLevel).
     * REVIEW_V0147 VK-2: the glyph ALSO re-enables the wand — an undo level
     * keeps the button clickable even after the row cleared (the only undo
     * window is between APPLY and the next keystroke).
     */
    fun setWandUndoMode(hasUndoLevel: Boolean) {
        wandUndoMode = hasUndoLevel
        wandButton.rotation = if (hasUndoLevel) 180f else 0f
        wandButton.contentDescription = context.getString(
            if (hasUndoLevel) R.string.wand_undo_correction else R.string.wand_apply_correction,
        )
        wandButton.isEnabled = hasUndoLevel || (lastHasSuggestion && lastEnabled)
    }

    private fun styledCorrection(original: String, corrected: String): SpannableString {
        val span = SpannableString(corrected)
        val originalWords = original.split(' ')
        val correctedWords = corrected.split(' ')
        if (originalWords.size != correctedWords.size) {
            // not index-alignable: mark the whole correction
            span.setSpan(StyleSpan(Typeface.BOLD), 0, span.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            span.setSpan(UnderlineSpan(), 0, span.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            return span
        }
        var pos = 0
        for (i in correctedWords.indices) {
            val word = correctedWords[i]
            if (word != originalWords[i]) {
                span.setSpan(StyleSpan(Typeface.BOLD), pos, pos + word.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                span.setSpan(UnderlineSpan(), pos, pos + word.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            pos += word.length + 1 // single space separator (split source)
        }
        return span
    }

    /** Emoji mode (idempotent): keyboard GONE, emoji block VISIBLE. */
    fun showEmojiPanel() {
        if (isEmojiPanelVisible) return
        keyboardView.visibility = GONE
        emojiBlock.visibility = VISIBLE
    }

    /**
     * T15 (Opus option H): top edge of the (opaque) keyboard surface in WINDOW
     * coordinates — pure view arithmetic (top of the root in its parent + top of
     * the stack in the root). In RELEASE this is exactly the KeyboardView's top
     * edge (statusRow GONE, stack begins with the keyboard); in DEBUG it INCLUDES
     * the 48-dp status band, so mic/🐞 stay inside the touchable region (T5 band
     * zone: the r0 popup opens in the band too — menu coords are stack-relative).
     * VoiceKeyboardService.onComputeInsets uses this value as contentTopInsets /
     * visibleTopInsets and as the top edge of the touchableRegion — touches in
     * the headroom above (0 until this value) pass through to the app.
     */
    fun keyboardTopInWindow(): Int = top + stack.top

    /**
     * T15b (Chrome-Enter-Bug 0.1.43): BOTTOM edge of the keyboard surface in
     * WINDOW coordinates — anchored at the STACK bottom, NOT at root.bottom: in
     * fullscreen/resize states (window 2204 px) the root was almost display-tall
     * and the touchableRegion ended 700 px under the keyboard (footer + Enter
     * touch-dead). The stack bottom always describes the real keyboard surface,
     * regardless of window padding/overhang.
     */
    fun keyboardBottomInWindow(): Int = top + stack.bottom

    /**
     * T15b: guard for onComputeInsets — true only if the root is laid out with a
     * REAL keyboard surface (stack bottom > stack top). Otherwise (before the
     * first layout, height 0, or stack collapsed) the super insets values stay.
     */
    val keyboardSurfaceValid: Boolean
        get() = isLaidOut && height > 0 && stack.bottom > stack.top

    /** Keyboard mode (idempotent): emoji block GONE, keyboard VISIBLE; no MotionEvent forwarding. */
    fun hideEmojiPanel() {
        if (!isEmojiPanelVisible) return
        emojiBlock.visibility = GONE
        keyboardView.visibility = VISIBLE
    }

    val isEmojiPanelVisible: Boolean
        get() = emojiBlock.visibility == VISIBLE
}
