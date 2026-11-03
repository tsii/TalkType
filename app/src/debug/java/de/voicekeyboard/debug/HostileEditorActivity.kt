package de.voicekeyboard.debug

import android.app.Activity
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.Spannable

import android.text.style.SuggestionSpan
import android.text.style.UnderlineSpan
import android.util.Log
import android.view.inputmethod.InputMethodManager
import android.webkit.WebView
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

/**
 * AP2 (docs/PLAN_C2_FABLE_R2.md §1.2): „feindlicher Editor“ für die C2-Simulation —
 * Debug-Build NUR (Emulator/Phone; Skript §1.3 Schritt 5 startet sie per
 * `am start -n de.voicekeyboard/.debug.HostileEditorActivity --es mode <mode>`).
 * Ein EditText, dessen Verhalten das Intent-Extra `mode` wählt — jeder Modus
 * imitiert ein reales App-Muster, um die jeweilige Zielhypothese zu provozieren:
 *
 *  - `plain`      Kontrolle (wie DebugInputActivity).
 *  - `restart:N`  nach je N Zeichen Zuwachs `imm.restartInput(view)` (H-E1;
 *                 Chrome-/Apps-Restart-Muster).
 *  - `cursor:N`   nach je N Zeichen Zuwachs `setSelection(len-1)` dann
 *                 `setSelection(len)` (Compose-/Autokorrektur-Muster, H-E2).
 *  - `spans`      bei jedem Text-Change SuggestionSpan+UnderlineSpan auf das
 *                 letzte Wort (Spell-Checker-Muster, H-E2).
 *  - `slowmain:MS` afterTextChanged blockiert den Main-Thread MS ms (H-E3;
 *                 z. B. 300/1500 — echte Busy-Wait-Schleife, kein sleep-Problem:
 *                 Blockieren IST der Modus).
 *  - `hideime:S`  nach S Sekunden `imm.hideSoftInputFromWindow(...)` (H-A2).
 *  - `webview`    WebView mit contenteditable-DIV, fester Offline-Inhalt
 *                 (~2000 Zeichen, data:-URL, KEIN Netzwerk — Regel: kein
 *                 Netzwerkzugriff) — echtes Chrome-Restart-Verhalten (H-E1).
 *
 * Regel 9: Diese Activity loggt NUR TextLÄNGEN und Modus-Codes unter dem Tag
 * [TAG] — niemals Textinhalte.
 */
class HostileEditorActivity : Activity() {

    private val mode: String by lazy { intent.getStringExtra("mode") ?: "plain" }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        log("created mode=$mode")
        if (mode == "webview") {
            buildWebView()
            return
        }
        val pad = (resources.displayMetrics.density * 12).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        root.addView(TextView(this).apply {
            text = "Hostile Editor ($mode)"
            textSize = 14f
        })
        val field = EditText(this).apply {
            hint = "Text …"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 6
            gravity = android.view.Gravity.TOP
        }
        root.addView(field)
        setContentView(root)
        installMode(field)
        field.requestFocus()
    }

    private fun installMode(field: EditText) {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        when (val m = mode) {
            "plain" -> Unit // Kontrolle: nichts feindliches
            "spans" -> field.addTextChangedListener(SpanChanger(field))
            "hideime" -> field.postDelayed(
                {
                    log("hideime fired len=${field.text.length}")
                    imm.hideSoftInputFromWindow(field.windowToken, 0)
                },
                (secondsArg(m, defaultSeconds = 10.0) * 1000L).toLong(),
            )
            else -> when {
                m.startsWith("restart:") -> field.addTextChangedListener(
                    EveryNChars(field, intArg(m)) { log("restartInput len=${field.text.length}"); imm.restartInput(field) },
                )
                m.startsWith("cursor:") -> field.addTextChangedListener(
                    EveryNChars(field, intArg(m)) {
                        val len = field.text.length
                        log("cursor jump len=$len")
                        field.setSelection((len - 1).coerceAtLeast(0))
                        field.setSelection(len)
                    },
                )
                m.startsWith("slowmain:") -> field.addTextChangedListener(
                    EveryNChars(field, 1) { busyBlockMain(msArg(m)) },
                )
                else -> log("mode unknown")
            }
        }
    }

    // ---- Modi: Helfer -------------------------------------------------------------

    /** restart:N / cursor:N — N als Int (Default 40). */
    private fun intArg(m: String): Int =
        m.substringAfter(':').toIntOrNull() ?: 40

    /** slowmain:MS — Dauer in ms (Default 300). */
    private fun msArg(m: String): Long =
        m.substringAfter(':').toLongOrNull() ?: 300L

    /** hideime — Sekunden als Double (mode ohne ':', Default 10 s). */
    private fun secondsArg(m: String, defaultSeconds: Double): Double =
        if (m.contains(':')) m.substringAfter(':').toDoubleOrNull() ?: defaultSeconds else defaultSeconds

    /** H-E3: den Main-Thread MS ms blockieren (busy — blockieren ist der Modus). */
    private fun busyBlockMain(ms: Long) {
        val until = android.os.SystemClock.uptimeMillis() + ms
        log("slowmain block ms=$ms")
        while (android.os.SystemClock.uptimeMillis() < until) {
            // bewusst busy-wait: Thread.sleep wäre von der Runtime künstlich
            // unterbrechbar/verschiebbar; die Blockade soll real sein.
        }
    }

    /** `spans`: bei JEDEM Text-Change Suggestion-+UnderlineSpan auf das letzte Wort. */
    private class SpanChanger(private val field: EditText) : android.text.TextWatcher {
        override fun beforeTextChanged(s: CharSequence, a: Int, b: Int, c: Int) = Unit
        override fun onTextChanged(s: CharSequence, a: Int, b: Int, c: Int) = Unit
        override fun afterTextChanged(s: Editable) {
            val text = s ?: return
            val wordStart = text.lastIndexOf(' ').let { if (it < 0) 0 else it + 1 }
            if (wordStart >= text.length) return
            val spans = text.getSpans(wordStart, text.length, Any::class.java)
            for (sp in spans) text.removeSpan(sp)
            text.setSpan(
                SuggestionSpan(field.context, null, SuggestionSpan.FLAG_MISSPELLED),
                wordStart, text.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
            text.setSpan(UnderlineSpan(), wordStart, text.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

    /**
     * Ruft [action] nach je N ZEICHEN ZUWACHS (kumulativ gezählt, robuster als
     * je Einzeländerung — PASTE/Commit-Blöcke zählen mit).
     */
    private class EveryNChars(
        private val field: EditText,
        private val n: Int,
        private val action: () -> Unit,
    ) : android.text.TextWatcher {
        private var seen = 0
        private var fired = 0
        override fun beforeTextChanged(s: CharSequence, a: Int, b: Int, c: Int) = Unit
        override fun onTextChanged(s: CharSequence, start: Int, before: Int, count: Int) = Unit
        override fun afterTextChanged(s: Editable) {
            val len = s.length
            if (len <= seen) { // Löschung/Ersatz: Zähler folgen
                seen = len
                return
            }
            val grown = len - seen
            seen = len
            fired += grown
            while (fired >= n) {
                fired -= n
                action()
            }
        }
    }

    /** `webview`: Offline-WebView mit contenteditable-DIV (~2000 Zeichen Inhalt, data:-URL). */
    private fun buildWebView() {
        val body = buildString {
            append("Webview-Korpus ")
            var i = 1
            while (length < 2000) {
                append("wort").append(i++).append(if (i % 8 == 0) ". " else " ")
            }
        }
        val html =
            "<html><body contenteditable=\"true\" style=\"font-size:16px;padding:8px\">" +
            body + "</body></html>"
        val webView = WebView(this).apply {
            settings.javaScriptEnabled = false // kein Skript, kein Netzwerk
            loadDataWithBaseURL(null, html, "text/html", "utf-8", null)
        }
        setContentView(webView)
        webView.requestFocus()
    }

    private fun log(what: String) {
        // Regel 9: nur Längen/Codes — niemals Textinhalte.
        Log.i(TAG, what)
    }

    private companion object {
        const val TAG = "VK-ED"
    }
}
