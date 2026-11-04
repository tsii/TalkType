package de.voicekeyboard.ime

import android.content.Context
import android.content.res.Configuration
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.LayerDrawable
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import de.voicekeyboard.R
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

        // T9 (v0.1.38): status row DEBUG ONLY — in the release (FLAG_DEBUGGABLE missing)
        // the statusRow stays GONE: status text + 🎤 + 🐞 occupy NO layout
        // space, the keyboard receives the full height budget. Injectable seam:
        // context.applicationInfo.flags, read ONCE (Robolectric tests control
        // the bit). Service updates (statusText.text, micButton.isEnabled/
        // isActivated) are pure property sets on still-attached views —
        // safe while GONE, no guard needed (checked: VoiceKeyboardService
        // :1537/1550/1552 and syncMicUi :921). T5 band overlay: without a visible
        // statusRow the KeyboardView sits at the stack's top edge (top = 0) ⇒
        // statusBandPx = 0 ⇒ menuTopForStatusBand falls back to the T1 two-zone rule
        // (existing fallback).
        val debugStatusRow =
            (context.applicationInfo?.flags ?: 0) and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0

        statusRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            // T21/S2 height budget (DESIGN_STOCK_LOOK §3): THIS row block is the
            // ONE place where the status row enters the height budget —
            // explicit 48-dp height (not "accidentally" pulled in via the debug
            // button). The vertical stack below measures the statusRow first and
            // subtracts its height from the KeyboardView's child MeasureSpec
            // (LinearLayout.onMeasure → measureChildBeforeLayout); KeyboardView
            // therefore computes with statusLinePx = 0 — EXACTLY ONCE, no double subtraction.
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (48 * density).toInt(),
            )
            // S4: 1-dp separator line BELOW the bar (spec §1: separator lines 1 dp
            // below the status bar — the only fixed line, consistent also in the
            // emoji view). As a background layer WITHIN the 48 dp so that the
            // subtraction chain (key budget) does not change again — no additional
            // stack child, no measurement change.
            val fill = GradientDrawable().apply { setColor(palette.background) }
            val line = GradientDrawable().apply { setColor(palette.separator) }
            background = LayerDrawable(arrayOf(fill, line)).apply {
                setLayerInset(1, 0, (48 * density).toInt() - (1 * density).toInt().coerceAtLeast(1), 0, 0)
            }
            addView(statusText)
            addView(micButton)
            addView(debugButton) // P0: right, fixed in the row (no dynamic addView)
            // T9 (vc39): release ⇒ GONE (no layout space); debug ⇒ as before (VISIBLE).
            // T9b hardening (vc39 evidence run, review follow-up): the mode switchers
            // showEmojiPanel()/hideEmojiPanel() touch ONLY keyboardView/emojiBlock —
            // never statusRow; the service sets no visibility at all. So that no
            // future switcher pulls the release row back, the setting here remains
            // the only place and is enforced by a test (ImeRootViewLayoutTest:
            // the release test checks GONE in keyboard AND emoji mode incl. roundtrip).
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
            val nav = insets.systemGestureInsets.bottom
                .coerceAtLeast(insets.systemWindowInsetBottom)
            // P0-0.1.20: INSET ANOMALY cap — a similarly large bottom system inset
            // (newer OS, IME in its own process) must never squeeze the whole stack:
            // the bottom root padding is the ONLY mechanism that can push/compress
            // the status row (48 dp) from above. The cap applies ONLY to the
            // padding (the height subtraction stays exactly once), not to the
            // insets delivery.
            val navCapped = nav.coerceAtMost((120 * v.resources.displayMetrics.density).toInt())
            v.setPadding(v.paddingLeft, v.paddingTop, v.paddingRight, navCapped)
            insets
        }
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

    /** Keyboard mode (idempotent): emoji block GONE, keyboard VISIBLE; no MotionEvent forwarding. */
    fun hideEmojiPanel() {
        if (!isEmojiPanelVisible) return
        emojiBlock.visibility = GONE
        keyboardView.visibility = VISIBLE
    }

    val isEmojiPanelVisible: Boolean
        get() = emojiBlock.visibility == VISIBLE
}
