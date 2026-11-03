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
 * AP2 (docs/PLAN_C2_FABLE_R2.md §1.2): „hostile editor“ for the C2 simulation —
 * debug build ONLY (emulator/phone; script §1.3 step 5 starts it via
 * `am start -n de.voicekeyboard/.debug.HostileEditorActivity --es mode <mode>`).
 * An EditText whose behavior is chosen by the intent extra `mode` — each mode
 * imitates a real app pattern to provoke the respective target hypothesis:
 *
 *  - `plain`      control (like DebugInputActivity).
 *  - `restart:N`  after every N characters of growth `imm.restartInput(view)` (H-E1;
 *                 Chrome/app restart pattern).
 *  - `cursor:N`   after every N characters of growth `setSelection(len-1)` then
 *                 `setSelection(len)` (Compose/autocorrect pattern, H-E2).
 *  - `spans`      on every text change SuggestionSpan+UnderlineSpan on the
 *                 last word (spell checker pattern, H-E2).
 *  - `slowmain:MS` afterTextChanged blocks the main thread for MS ms (H-E3;
 *                 e.g. 300/1500 — real busy-wait loop, no sleep problem:
 *                 blocking IS the mode).
 *  - `hideime:S`  after S seconds `imm.hideSoftInputFromWindow(...)` (H-A2).
 *  - `webview`    WebView with contenteditable DIV, fixed offline content
 *                 (~2000 characters, data: URL, NO network — rule: no
 *                 network access) — real Chrome restart behavior (H-E1).
 *
 * Rule 9: this activity logs ONLY text LENGTHS and mode codes under the tag
 * [TAG] — never text contents.
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
            "plain" -> Unit // control: nothing hostile
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

    // ---- Modes: helpers -------------------------------------------------------------

    /** restart:N / cursor:N — N as Int (default 40). */
    private fun intArg(m: String): Int =
        m.substringAfter(':').toIntOrNull() ?: 40

    /** slowmain:MS — duration in ms (default 300). */
    private fun msArg(m: String): Long =
        m.substringAfter(':').toLongOrNull() ?: 300L

    /** hideime — seconds as Double (mode without ':', default 10 s). */
    private fun secondsArg(m: String, defaultSeconds: Double): Double =
        if (m.contains(':')) m.substringAfter(':').toDoubleOrNull() ?: defaultSeconds else defaultSeconds

    /** H-E3: block the main thread for MS ms (busy — blocking is the mode). */
    private fun busyBlockMain(ms: Long) {
        val until = android.os.SystemClock.uptimeMillis() + ms
        log("slowmain block ms=$ms")
        while (android.os.SystemClock.uptimeMillis() < until) {
            // deliberately busy-wait: Thread.sleep would be artificially
            // interruptible/reschedulable by the runtime; the blockade should be real.
        }
    }

    /** `spans`: on EVERY text change Suggestion+UnderlineSpan on the last word. */
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
     * Calls [action] after every N CHARACTERS OF GROWTH (counted cumulatively, more robust
     * than per single change — PASTE/commit blocks count along).
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
            if (len <= seen) { // deletion/replacement: the counter follows
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

    /** `webview`: offline WebView with contenteditable DIV (~2000 characters of content, data: URL). */
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
            settings.javaScriptEnabled = false // no script, no network
            loadDataWithBaseURL(null, html, "text/html", "utf-8", null)
        }
        setContentView(webView)
        webView.requestFocus()
    }

    private fun log(what: String) {
        // Rule 9: lengths/codes only — never text contents.
        Log.i(TAG, what)
    }

    private companion object {
        const val TAG = "VK-ED"
    }
}
