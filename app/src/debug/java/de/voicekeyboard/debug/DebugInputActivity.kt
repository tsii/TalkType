package de.voicekeyboard.debug

import android.app.Activity
import android.os.Bundle
import android.text.InputType
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Debug build only: simple text field to test the IME without a foreign app
 * (emulator verification, screenshots). Not part of the release APK.
 */
class DebugInputActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
        setContentView(root)
        field.requestFocus()
    }
}