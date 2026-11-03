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
 * P0-Regression (0.1.20): die Statuszeile („Bereit"/„Diktat" + Mic + 🐞) und der
 * 48-dp-Höhenhaushalt oberhalb der q-Reihe. Der Test belegt den Grundzustand:
 *
 *  - Root-View enthält statusText, micButton und die (wie im Service angebauten)
 *    🐞-Debug-Taste — alle drei VISIBLE mit Höhe > 0;
 *  - Statuszeile ist EXAKT 48 dp;
 *  - die Tastatur liegt DARUNTER (top ≥ Statuszeilenhöhe) und behält ihre
 *    Reihen (S1: kein Row-Drop) — auch bei engem Fensterangebot (EXACTLY).
 *
 * Messung wie das IME-Framework: Breite EXACTLY, Höhe UNSPECIFIED
 * (Fenster Größe = View gemessene Höhe) bzw. EXACTLY bei begrenztem Angebot.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ImeRootViewLayoutTest {

    private val app: Application get() = RuntimeEnvironment.getApplication() as Application

    private fun statusRowOf(view: ImeRootView): LinearLayout =
        view.micButton.parent as LinearLayout

    /** T9 (v0.1.38): Injizierbare Naht = context.applicationInfo.flags — der
     *  Konstruktor liest FLAG_DEBUGGABLE EINMAL. buildRoot() setzt das Flag
     *  explizit: DIESE Klasse belegt den DEBUG-Bestand (P0-Regression); der
     *  Release-Fall hat die beiden eigenen T9-Tests unten. */
    private fun setDebuggable(on: Boolean) {
        if (on) {
            app.applicationInfo.flags = app.applicationInfo.flags or ApplicationInfo.FLAG_DEBUGGABLE
        } else {
            app.applicationInfo.flags =
                app.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE.inv()
        }
    }

    /** Baut die Root wie [VoiceKeyboardService.onCreateInputView]:
     *  Layout de-de + 🐞-Button (48 dp) an derselben Stelle (statusRow = micButton.parent). */
    private fun buildRoot(): ImeRootView {
        setDebuggable(true)
        val view = ImeRootView(app)
        view.keyboardView.setLayout(
            LayoutRepository(app).loadOrFallback("de-de"),
            app.resources.displayMetrics.widthPixels,
        )
        // P0: die 🐞-Taste kommt aus der ImeRootView selbst (view.debugButton) —
        // das alte manuelle addView im Test hat das brüchige Service-Verhalten
        // maskiert; es ist gelöscht.
        return view
    }

    @Test
    fun `P0 Grundzustand - Statuszeile 48dp sichtbar und Tastatur darunter`() {
        val view = buildRoot()
        val density = app.resources.displayMetrics.density
        val status48 = (48 * density).toInt()

        // IME-Messung: Breite EXACTLY, Hoehe UNSPECIFIED (Fenster folgt der View).
        view.measure(
            View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        view.layout(0, 0, view.measuredWidth, view.measuredHeight)

        val statusRow = statusRowOf(view)
        assertEquals("Statuszeile VISIBLE", View.VISIBLE, statusRow.visibility)
        assertEquals("Statuszeile hat exakt 48 dp", status48, statusRow.height)

        assertEquals("statusText VISIBLE", View.VISIBLE, view.statusText.visibility)
        assertTrue("statusText Hoehe > 0", view.statusText.height > 0)
        assertEquals("statusText fuellt die Zeile", status48, view.statusText.height)

        assertEquals("micButton VISIBLE", View.VISIBLE, view.micButton.visibility)
        assertTrue("micButton Hoehe > 0", view.micButton.height > 0)
        assertEquals("micButton fuellt die Zeile", status48, view.micButton.height)

        val debugButton = statusRow.getChildAt(statusRow.childCount - 1) as Button
        assertEquals("🐞-Taste ist letzte Statuszeilen-Komponente", "🐞", debugButton.text.toString())
        assertEquals("🐞 VISIBLE", View.VISIBLE, debugButton.visibility)
        assertTrue("🐞 Hoehe > 0", debugButton.height > 0)

        assertEquals("Tastatur VISIBLE", View.VISIBLE, view.keyboardView.visibility)
        assertTrue("Tastatur Hoehe > 0", view.keyboardView.height > 0)
        assertTrue(
            "Tastatur liegt UNTER der Statuszeile, nicht drueber",
            view.keyboardView.top >= statusRow.height,
        )
    }

    @Test
    fun `P0 enges Fensterangebot - Statuszeile bleibt 48dp und Tastatur skaliert statt Row-Drop`() {
        val view = buildRoot()
        val density = app.resources.displayMetrics.density
        val status48 = (48 * density).toInt()

        // Erst natuerliche Groesse bestimmen, dann ENGERS Angebot erzwingen
        // (Fenster hoeher kappen als Inhalt + Statuszeile).
        view.measure(
            View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        val naturalH = view.measuredHeight
        val tightH = naturalH - (80 * density).toInt()
        assertTrue("Testvoraussetzung: Angebot ist enger als Inhalt", tightH > 0)

        view.measure(
            View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(tightH, View.MeasureSpec.EXACTLY),
        )
        view.layout(0, 0, view.measuredWidth, view.measuredHeight)

        val statusRow = statusRowOf(view)
        assertEquals("Statuszeile bleibt VISIBLE", View.VISIBLE, statusRow.visibility)
        assertEquals("Statuszeile bleibt exakt 48 dp", status48, statusRow.height)
        assertTrue("statusText Hoehe > 0", view.statusText.height > 0)
        assertTrue("micButton Hoehe > 0", view.micButton.height > 0)

        // S1-Vertrag: alle 4 Reihen bleiben, Reihenhoehe wird gesenkt.
        assertTrue("Tastatur bleibt sichtbar", view.keyboardView.visibility == View.VISIBLE)
        assertTrue(
            "Tastatur füllt den Rest (Hoehe <= Angebot - Statuszeile)",
            view.keyboardView.height <= tightH - status48 + 1,
        )
        assertTrue("Tastatur Hoehe > 0 (kein leerer Kasten)", view.keyboardView.height > 0)
    }

    // ==================================================================
    // T9 (v0.1.38): Statuszeile nur Debug — Release ⇒ statusRow GONE
    // (Statustext + 🎤 + 🐞 komplett, KEIN Layout-Platz); Debug ⇒ Bestand.
    // ROT-Zuerst: beide Tests liefen vor der ImeRootView-Änderung rot
    // (Release-Test: statusRow war VISIBLE).
    // ==================================================================

    @Test
    fun `T9 Release - statusRow GONE in Tastatur- UND Emoji-Modus`() {
        setDebuggable(false)
        val view = ImeRootView(app)
        assertEquals("Release: statusRow GONE (kein Layout-Platz)", View.GONE, view.statusRow.visibility)
        assertFalse("Tastaturmodus aktiv", view.isEmojiPanelVisible)

        view.showEmojiPanel()
        assertEquals("Release, Emoji-Modus: statusRow bleibt GONE", View.GONE, view.statusRow.visibility)
        assertTrue("Emoji-Block VISIBLE", view.isEmojiPanelVisible)

        view.hideEmojiPanel()
        assertEquals("Release, zurück im Tastaturmodus: statusRow bleibt GONE",
            View.GONE, view.statusRow.visibility)
    }

    @Test
    fun `T9 Debug - statusRow VISIBLE (Bestand) in beiden Modi`() {
        setDebuggable(true)
        val view = ImeRootView(app)
        assertEquals("Debug: statusRow VISIBLE (Bestand)", View.VISIBLE, view.statusRow.visibility)

        view.showEmojiPanel()
        assertEquals("Debug, Emoji-Modus: statusRow bleibt VISIBLE",
            View.VISIBLE, view.statusRow.visibility)
    }

    // ==================================================================
    // P0-Runde 2026-11-01 (Nutzerbefund nach 0.1.24)
    // ==================================================================

    @Test
    fun `P0-2 Grundzustand - Status-Text nie leer und debugButton fest in der Zeile`() {
        val view = buildRoot()
        val density = app.resources.displayMetrics.density
        view.measure(
            View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        view.layout(0, 0, view.measuredWidth, view.measuredHeight)

        // Befund 1a: im Grundzustand (Service noch nicht gelaufen) steht ein Text.
        assertTrue("statusText ist im Grundzustand NICHT leer", view.statusText.text.isNotEmpty())
        assertEquals("Bereit", view.statusText.text.toString())

        // Befund 1b: 🐞 ist ein View-Kind (view.debugButton), rechts (letztes
        // Kind), 48 dp breit, sichtbar mit Hoehe > 0.
        val row = statusRowOf(view)
        val last = row.getChildAt(row.childCount - 1)
        assertSame("debugButton ist letztes Kind der statusRow (rechts)", view.debugButton, last)
        assertEquals("🐞 VISIBLE", View.VISIBLE, view.debugButton.visibility)
        assertEquals("🐞 Breite 48 dp", (48 * density).toInt(), view.debugButton.width)
        assertTrue("🐞 Hoehe > 0", view.debugButton.height > 0)
    }

    // ---- Befund 2: Mic-Zustandsmaschine (reine Funktionen, VKS-Domain) ----

    @Test
    fun `Befund-2a T3 - Hold-Release im Start stoppt nach Startende - Tap-on setzt kein Flag`() {
        assertEquals("Normalfall: Session laeuft", MicStartFinishAction.RUN, micStartFinishedAction(false, false))
        // T3 (v0.1.35, Debugger-Design, Beweis /tmp/vk_phone_e2.log Z.74-97): wantedStop
        // kommt NUR noch vom Hold-Release (>300 ms) waehrend des Starts — er beendet
        // die frische Session WIRKLICH nach Startende (STOP_AFTER_START). Das blinde
        // KEEP_RUNNING (P0-Befund2(a), Quick-Tap-Rettung) ist ersetzt: ein frischer
        // Tap ist TOGGLE_ON (micHoldReleaseDecision) und setzt das Flag GAR NICHT.
        assertEquals("Hold-Release im Start: Stop NACH Startende",
            MicStartFinishAction.STOP_AFTER_START, micStartFinishedAction(true, false))
        // CANCEL bleibt ein Abbruch ohne Finale (D10-Rest S1).
        assertEquals("Abort gewinnt", MicStartFinishAction.ABORT, micStartFinishedAction(true, true))
        assertEquals("Abort ohne Stop", MicStartFinishAction.ABORT, micStartFinishedAction(false, true))
    }

    @Test
    fun `Befund-2b - nach Stop rueckt der Mic-Zustand zurueck (IDLE ist nicht gruen)`() {
        assertTrue("RECORDING ist gruen", micUiRecording(DictationState.RECORDING))
        assertFalse("IDLE (nach Stop) ist NICHT gruen", micUiRecording(DictationState.IDLE))
        assertFalse("STARTING ist NICHT gruen", micUiRecording(DictationState.STARTING))
        assertFalse("FINALIZING ist NICHT gruen", micUiRecording(DictationState.FINALIZING))
        assertFalse("ohne Coordinator (null) ist NICHT gruen", micUiRecording(null))
    }

    @Test
    fun `Befund-2c - exhaustiv - es gibt keinen Zustand, der ohne Session gruen bleibt`() {
        for (s in DictationState.entries) {
            assertEquals("Zustand $s", (s == DictationState.RECORDING), micUiRecording(s))
        }
    }

    // ---- Befund 3: Action-Glyphen als Pfade (DrawOp-Historie, kein Tofu) ----

    /** Tastatur (de-de) messen/legen und auf eine Canvas zeichnen; ShadowCanvas
     *  recordiert ALLE DrawOps (Pfade, Text, Rects) — so ist beweisbar, ob die
     *  Glyphen aus PFAEDEN oder aus dem Font kommen. */
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
        val model = view.currentModel ?: error("kein Modell")
        val canvas = Canvas(Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888))
        view.draw(canvas)
        return canvas to model
    }

    /** Key-Rechtecke der drei Action-Glyphen. */
    private fun glyphKeyRects(model: de.voicekeyboard.keyboard.KeyboardModel): Map<String, RectF> =
        listOf("backspace", "shift", "enter").associateWith { id ->
            val k = model.keys.first { key -> key.id == id }
            RectF(k.left, k.top, k.right, k.bottom)
        }

    @Test
    fun `Befund-3 - die drei Action-Glyphen kommen aus Pfaeden, nie aus dem Font (kein Tofu)`() {
        val (canvas, model) = recordDraw(night = false)
        val sh = Shadows.shadowOf(canvas)
        // 1) Font-Abwesenheit: kein einziger Text-Draw mit einer der drei Glyphen
        //    (vorher: Font-Glyphe U+232B => Tofu-Kasten auf dem Gerat).
        for (i in 0 until sh.textHistoryCount) {
            val t = sh.getDrawnTextEvent(i).text
            assertFalse(
                "Font-Glyphe '$t' statt Pfad gezeichnet (Tofu-Risiko)",
                t in setOf("⌫", "⇧", "⏎", "⇪"),
            )
        }
        // 2) Pfad-Vorhandensein: jedes Glyph hat >= 2 gezeichnete Pfade.
        val rects = glyphKeyRects(model)
        val byKey = rects.keys.associateWith { _ -> 0 }.toMutableMap()
        for (i in 0 until sh.pathPaintHistoryCount) {
            val b = RectF()
            sh.getDrawnPath(i).computeBounds(b, true)
            val hit = rects.entries.firstOrNull { it.value.contains(b.centerX(), b.centerY()) }
            if (hit != null) byKey[hit.key] = byKey.getValue(hit.key) + 1
        }
        for ((k, n) in byKey) assertTrue("$k: >= 2 Pfad-Draws (sind $n)", n >= 2)
    }

    @Test
    fun `Befund-3 - identische optische Hoehe aller drei Glyphen (hell + dunkel)`() {
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
            // Alle drei identisch (Bindekiste 0,60·s), hell und dunkel; Toleranz 2 px.
            assertTrue("night=$night: Hoehe identisch (sind $heights)",
                (heights.min()..heights.max()).let { it.isEmpty() } || heights.max() - heights.min() <= 2f)
            assertTrue("night=$night: Hoehe > 0 (sind $heights)", heights.min() > 5f)
        }
    }

    @Test
    fun `Befund-3 - identische Strichstaerke in allen drei Glyphen (hell + dunkel)`() {
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
            assertTrue("night=$night: Glyph-Pfade gefunden", strokes.size >= 6)
            assertTrue(
                "night=$night: Strichstaerke identisch (sind ${strokes.toSet()})",
                strokes.all { it == strokes.first() },
            )
        }
    }
}
