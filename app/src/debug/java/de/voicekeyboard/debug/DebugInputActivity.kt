package de.voicekeyboard.debug

import android.app.Activity
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.WindowInsets
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Debug build only: simple text field to test the IME without a foreign app
 * (emulator verification, screenshots). Not part of the release APK.
 *
 * T15 evidence infra: a SECOND EditText is docked to the bottom screen edge
 * (root-Kante unten) — it lies exactly in the region the IME window covers with
 * its transparent T15 headroom band, so on the emulator it proves:
 *  (a) app content is VISIBLE through the headroom band (no opaque strip),
 *  (b) a touch in the band FOCUSES this field (touchableRegion = keyboard only),
 *  (c) the keyboard long-press popup visibly OVERLAPS this input bar
 *      (browser-URL-bar analogon, stock behavior).
 * The existing field and its logic stay unchanged.
 */
class DebugInputActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // T15 correction (emu 5564, MEASURED): under enforced edge-to-edge (API 35,
        // targetSdk ≥ 35) NEITHER setSoftInputMode(ADJUST_RESIZE) NOR
        // setDecorFitsSystemWindows(true) resizes the activity — the dock stayed at
        // [33,2216][1047,2340] BEHIND the open keyboard. Working alternative (measured):
        // the bar tracks the IME insets as bottomMargin — insets.ime().bottom is the
        // distance from the screen bottom to the keyboard's VISIBLE top edge (with
        // T15 contentTopInsets the transparent headroom band is NOT counted) ⇒ the
        // bar sits DIRECTLY above the keyboard, browser-URL-bar semantics.
        val pad = (resources.displayMetrics.density * 12).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        root.addView(TextView(this).apply {
            text = "Debug-Eingabefeld"
            textSize = 14f
        })
        val field = EditText(this).apply {
            hint = "Hier tippen …"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 4
            gravity = android.view.Gravity.TOP
        }
        root.addView(field)
        // T15 band probe: dock container = FrameLayout so the probe can sit at the
        // bottom content edge while the existing content (incl. its padding) stays
        // byte-identical as the first child. bottomMargin tracks the ime insets
        // (see onCreate) — no IME: above the navigation bar; IME open: directly
        // above the keyboard's visible top edge (band zone).
        val bandProbe = EditText(this).apply {
            hint = "Band probe — input bar above keyboard"
            inputType = InputType.TYPE_CLASS_TEXT
        }
        val dock = FrameLayout(this).apply {
            addView(root)
            addView(
                bandProbe,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                ).apply {
                    // volle Breite minus Rand (links/rechts), unten an der root-Kante angedockt
                    gravity = Gravity.BOTTOM
                    leftMargin = pad
                    rightMargin = pad
                },
            )
            setOnApplyWindowInsetsListener { _, insets ->
                val imeBottom = insets.getInsets(WindowInsets.Type.ime()).bottom
                val navBottom = insets.getInsets(WindowInsets.Type.systemBars()).bottom
                (bandProbe.layoutParams as FrameLayout.LayoutParams).bottomMargin =
                    maxOf(imeBottom, navBottom)
                bandProbe.requestLayout()
                insets
            }
        }
        setContentView(dock)
        field.requestFocus()
    }
}