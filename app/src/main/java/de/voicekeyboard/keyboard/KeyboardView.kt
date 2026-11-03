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
 * Eigene Tastatur-View (kein android.inputmethodservice.KeyboardView).
 * Rendert Tasten aus einem KeyboardModel und behandelt Touch mit Pointer-Capture:
 * eine Bewegung bleibt auf der Taste des DOWN-Events bis UP/CANCEL.
 *
 * Ebenenwechsel: `layer-symbols`/`layer-base` werden hier behandelt (analog Shift),
 * inkl. Rebuild des Modells und Re-Render. Shift-Logik wirkt nur auf die
 * Buchstaben-Ebenen (base/shift); die Symbolebenen werden nie gecaps-t (DESIGN §7.1).
 */
class KeyboardView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    interface Listener {
        fun onTextInput(text: String)
        fun onAction(action: String)

        /**
         * D10-Rest §1: Fussreihen-Mic als HALTEGESTE — DOWN startet die
         * Aufnahme (Service: onMicDown). Bewusster Tap-Pfad (onAction MIC)
         * bleibt für Accessibility erhalten; die Touch-Geste geht hier entlang.
         */
        fun onMicHoldStart() {}

        /**
         * D10-Rest §1 / T3 (v0.1.35): Loslassen — Finger UP (cancelled=false)
         * beendet MIT Finale; ACTION_CANCEL (cancelled=true) bricht ab OHNE
         * Final-Decode. Nur der Pointer, der das Mic gedrückt hat, liefert dieses
         * Ereignis. [holdDurationMs] = DOWN→UP-Dauer aus `event.eventTime`
         * (T3: Tap ≤ 300 ms vs. echter Hold-Release — reine Zusatz-Information,
         * kein Umbau der Geste).
         */
        fun onMicHoldEnd(cancelled: Boolean, holdDurationMs: Long) {}
    }

    var listener: Listener? = null

    private val density = resources.displayMetrics.density

    // S3 — Stock-Tokens (KeyboardTheme, DESIGN_STOCK_LOOK §1): Palette nach System
    // Hell/Dunkel (kein Ziel-App-Theme-Raten); Geometrie-Nennwerte 55/44 dp je
    // Reihenzahl, Lücken 0, Radius 0. Die FIT-Logik (Reihen/Treffer/Skalierung)
    // bleibt unverändert aus S1/S2 — nur die Nennwerte folgen jetzt Sol.
    private val palette: KeyboardPalette = KeyboardTheme.palette(
        isDark = (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES,
    )
    private val rowGapPx = KeyboardTheme.ROW_GAP_DP * density
    private val keyGapPx = KeyboardTheme.KEY_GAP_DP * density
    private val cornerRadius = KeyboardTheme.KEY_RADIUS_DP * density
    /** F36/V2 §7: 2 px Fuge je Tastenseite (@ 2,625) — die Fläche schrumpft, die
     *  Fuge zeigt den Hintergrund (einzige Tastenabgrenzung, kein Trennlinien-Strich). */
    private val keyFugePx = KeyboardTheme.KEY_FUGE_DP * density

    /** Sol §1: 55 dp je Reihe bei ≤ 4 Reihen, 44 dp bei ≥ 5 (PT bleibt fünfzeilig). */
    private fun desiredRowHeightPx(rows: Int): Float = KeyboardTheme.rowHeightDp(rows) * density

    var layout: LayoutAsset? = null
        private set
    var activeLayerName: String = "base"
        private set
    var capsLock: Boolean = false
        private set

    /** True, wenn aktuell die Shift-Großform der Buchstaben angezeigt wird. */
    val shiftShowing: Boolean
        get() = (activeLayerName == "shift")

    private var shiftTemporary: Boolean = false // einmaliges Shift bis zum nächsten Buchstaben
    private var symbolShift: Boolean = false // innerhalb der Symbolebenen: symbols vs. symbolsShift

    private var model: KeyboardModel? = null

    /** P0 (2026-11-01): Test-Zugang zum gemessenen Raster (Render-Beweis der
     *  Glyph-Pfade braucht die Key-Zentren) — read-only, kein Setter. */
    internal val currentModel: KeyboardModel?
        get() = model

    /**
     * T21/S2: Fit der AKTUELLEN Messung (Instanzfeld, kein globaler Zustand).
     * Wird bei JEDEM onMeasure neu berechnet und an rebuildModel/rebuilds
     * durchgereicht — ein Fit aus einer früheren Öffnung kann nicht weiterwirken,
     * weil jede Messung ihn komplett ersetzt und jede Neuinstanz bei null startet.
     */
    private var currentFit: KeyboardHeight.HeightFit? = null
    /** ID → Quelle-Taste der aktiven Ebene; beim rebuildModel einmal aufgebaut (kein O(n²) im onDraw). */
    private var sourceKeys: Map<String, de.voicekeyboard.layouts.LayoutKey> = emptyMap()

    // S3 — Flächenfüll-Paint (Tastenfläche flach; Farbe je Zustand im onDraw).
    private val keyPaint = Paint().apply { textAlign = Paint.Align.CENTER }

    // S3 — Typografie nach Rolle (Sol §1, T2/vc36): 26 sp Buchstaben, 24 sp Ziffern,
    // 14 sp Funktion, 12 sp Space/Status — als sp (fontScale über scaledDensity).
    // Die FARBEN werden je Zustand im onDraw gesetzt (gedrückt ändert die
    // Textfarbe NICHT; getoggelt färbt nur die Glyphe).
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
    // D10 §2: prominentes 26-dp-Mikrofon in der Mic-Zelle („Icongröße 26 dp") —
    // sichtbares Zeichen größer, Farbe wie Funktionstext (Zustandstabelle S3/S4).
    private val micPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = 26f * density
    }
    // F27b: Shift/Backspace/Enter-Icons größer (24 dp statt 14 sp Funktionstext).
    private val actionGlyphPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = KeyboardTheme.ACTION_GLYPH_DP * density
    }
    // F29: die drei Action-Glyphen als Pfade — IDENTISCHE Strichstärke (Font-Glyphen
    // ⇧/⌫/⏎ zeichnen unterschiedlich dicke Linien).
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
    // F22 (D12): Langdruckmenü-Paints — Fläche palette.keyPressed, Zeichen 24 sp
    // (docs/DESIGN_KEY_POPUP.md §1); keine neuen Farben, kein Schatten/Rahmen.
    private val popupPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val popupGlyphPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = KeyboardTheme.POPUP_GLYPH_SP * resources.displayMetrics.scaledDensity
    }
    // F23a2 (D12 §7.1): Hint-Legende 0,5 x tatsaechliche Hauptlabel-Groesse, gedeckelt
    // durch das Theme-Soll (SP_KEY_HINT_SP); Farbe = palette.textSecondary (§7.3).
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

    // ---- F22 (D12): Langdruckmenü — Adapter-Zustand, Geometrie via LongPressMenu ----

    /** Offen, während des Auswahl-Rasters; null = kein Menü. */
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
        /** F24e0: optionale Aktionszellen (parallel zu glyphs indiziert); leer = alles Text. */
        val actionCells: List<String> = emptyList(),
    )

    private var popup: LongPressPopup? = null
    private var popupSelected = -1
    private var armedKey: KeyboardModel.MeasuredKey? = null
    private var armedLayer: String = ""
    private var downX = 0f
    private var downY = 0f
    /** Bewegung auesserhalb der Arming-Zone: Bewaffnen weg — Geste bewaffnet nie neu (F22-L). */
    private var armCancelledByMove = false
    /**
     * F24e5c: die ECHTE Palette wird einmal beim Armen berechnet und gemerkt
     * (-1 = nicht gesetzt), damit die MOVE-Zone nie aus neu berechneten,
     * evtl. anderen Werten gebaut wird (Geraetefund: Zone blieb bei ca.
     * +/-150 px, armZoneOffsets aus MOVE-neuer Rechnung wirkte nicht).
     */
    private var armedMenuLeftPx = -1
    private var armedMenuWidthPx = -1
    private val popupRadius = KeyboardTheme.POPUP_RADIUS_DP * density
    private val moveCancelPx = KeyboardTheme.POPUP_MOVE_CANCEL_DP * density
    // F22-L: Popup-Maße als Felder — die Arming-Zone darf nie enger sein als das
    // spaetere Popup (sonst schiebt man raus, bevor das Popup offen ist).
    private val popupHeightPx = (KeyboardTheme.POPUP_HEIGHT_DP * density).roundToInt()
    private val popupGapPx = (KeyboardTheme.POPUP_GAP_DP * density).roundToInt()
    private val longPressRunnable = Runnable { openPopupIfArmed() }

    init {
        // Erlaubt TalkBack/uiautomator, die Tastatur als anklickbares Element zu sehen;
        // die einzelnen Tasten sind über den AccessibilityNodeProvider (unten) erreichbar.
        isClickable = true
        contentDescription = "Tastatur"
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
     * L-02: Shift/Caps/Zustand vollständig zurücksetzen (nach Sprach- oder
     * Ebenenwechsel darf kein toter Shift-Zustand aktiv bleiben — sonst
     * committet die nächste Taste die Großform, z. B. „P“ statt „p").
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
        // F22 (§2): Neu-/Um-Bau verwirft Timer und Menü — ein Menü alter Generation
        // darf nie in eine neue Schicht/Größe committen.
        // F24e5d: nur bei echtem Generationenwechsel (Schicht/Breite/Höhe). Ein
        // Remeasure mit identischem Stempel (IME-Layout-Pass während die Geste
        // gehalten wird) darf die Bewaffnung nicht töten — sonst kann ein Schwenk
        // in die Menüzeile die Palette nie öffnen (Geraetefund F24e5d).
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
            keyHeightPx = desiredRowHeightPx(layer.rows.size), // S3: 55/44 dp je Reihenzahl
            rowGapPx = rowGapPx,
            keyGapPx = keyGapPx,
            fit = fit,
        )
        sourceKeys = layer.rows.flatten().associateBy { it.id }
    }

    /** Text, den diese Taste im aktuellen Zustand committet (sprachsensitives Case-Mapping). */
    private fun textFor(key: KeyboardModel.MeasuredKey): String {
        val asset = layout ?: return key.value
        val src = sourceKeys[key.id] ?: return key.value
        if (src.kind != KeyKind.TEXT) return src.value
        // shift/symbolsShift-Ebenen tragen die Zielform bereits im Value; base/symbols mappen wir selbst.
        return if (activeLayerName == "base" || activeLayerName == "symbols") {
            if (shiftShowing) CaseMapper.upper(src.value, asset.language.caseLocale) else src.value
        } else {
            src.value
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        // T21/S2: Angebot = Height-Spec-Size; UNSPECIFIED (≤ 0) gilt als unbegrenzt,
        // dann entscheidet maxPx. Das Angebot dieser View ist die Child-Spec der
        // ImeRootView-Kette — Navigationsinset UND Statuszeile sind darin schon
        // GENAU JE EINMAL abgezogen (Beleg in ImeRootView.kt):
        //  - Navigation: setOnApplyWindowInsetsListener → setPadding(…, nav) setzt
        //    den Inset als unteres Root-Padding; FrameLayout reduziert damit die
        //    MeasureSpec des Stacks — KeyboardView rechnet mit navInsetPx = 0.
        //  - Statuszeile: der vertikale Stack misst die statusRow (48 dp) zuerst
        //    und zieht ihre Höhe von der Child-MeasureSpec der KeyboardView ab
        //    (LinearLayout.onMeasure → measureChildBeforeLayout) — deshalb hier
        //    statusLinePx = 0. Ein zweiter Abzug wäre der „genau einmal"-Verstoß.
        val offeredPx = if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.UNSPECIFIED) {
            0
        } else {
            MeasureSpec.getSize(heightMeasureSpec)
        }
        val rows = layout?.layers?.get(activeLayerName)?.rows?.size
            ?: layout?.layers?.getValue("base")?.rows?.size ?: 1
        // S1: ALLE Reihen werden skaliert, keine wird verworfen (kein Row-Drop).
        // S3: Nennwerte aus KeyboardTheme (55/44 dp je Reihenzahl, Lücke 0).
        val fit = KeyboardHeight.fitHeight(
            offeredPx = offeredPx,
            rows = rows,
            desiredRowPx = desiredRowHeightPx(rows).toInt(),
            desiredRowGapPx = rowGapPx.toInt(),
            statusLinePx = 0, // Abzug passiert im Stack (Beleg s.o.) — genau einmal
            navInsetPx = 0,   // Abzug passiert im Root-Padding (Beleg s.o.) — genau einmal
            maxPx = (MAX_HEIGHT_DP * density).toInt(),
            minKeyHeightPx = (desiredRowHeightPx(rows) * 0.6f).toInt().coerceAtLeast(1),
        )
        currentFit = fit
        // Bei JEDEM onMeasure neu einmessen (Breite UND aktueller Fit): ein
        // Modell mit veraltetem Fit kann so nie gezeichnet werden.
        if (width > 0) rebuildModel(width, fit)
        setMeasuredDimension(width, fit.viewHeightPx)
    }

    /** Breite, mit der das Modell zuletzt gebaut wurde (P0: echte View-Breite, nicht rechte Kante). */
    private var lastModelWidth: Int = -1

    /**
     * F24e5d: Generation-Stempel des aufgebauten Modells (Schicht|Breite|Höhe).
     * Ein Remeasure mit identischen Parametern ist KEIN Generationenwechsel —
     * bisher riss rebuildModel bei JEDEM onMeasure/onSizeChanged Timer und
     * Bewaffnung (discardLongPress), und zwar auch mitten in einer gehaltenen
     * Langdruck-Geste: weit entfernte Menüzellen öffneten nie, nahegelegene
     * erst, wenn das Remeasure zufällig ausblieb.
     */
    private var modelStamp = ""

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        // P0: Rebuild auch bei Groessen-Aenderung — mit dem Fit der AKTUELLEN
        // Messung (Instanzfeld, T21: kein globaler Zustand).
        rebuildModel(w, currentFit)
        invalidate()
    }

    /** T21: gezeichnete Tasten (vollständiger Bestand, kein Row-Drop) — lesbar für
     *  Instrumented-Tests (StockKeyboardFitTest) und Diagnose; Read-Only-Snapshot. */
    val drawnKeys: List<KeyboardModel.MeasuredKey>
        get() = visibleKeys()

    /** T21: Trefferermittlung über denselben Pfad wie Touch/Accessibility — lesbar
     *  für Instrumented-Tests (Rect-Mittelpunkt muss die eigene ID liefern). */
    fun keyAtPoint(x: Float, y: Float): KeyboardModel.MeasuredKey? = keyAtVisible(x, y)

    /**
     * T19: NUR die Reihen, die INNERHALB der ermittelten Hoehe liegen — Sicherheits-
     * netz; seit S1 enthält der Fit ALLE Reihen skaliert, sodass hier nichts mehr
     * herausfällt (height ist die Live-View-Hoehe, kein Cache).
     */
    private fun visibleKeys(): List<KeyboardModel.MeasuredKey> {
        val m = model ?: return emptyList()
        val limit = height + 0.5f // Raster liegt auf ganzen Reihen; 0,5 px Toleranz
        return m.keys.filter { it.bottom <= limit }
    }

    /** Treffer nur im sichtbaren Bereich (siehe [visibleKeys]). */
    private fun keyAtVisible(x: Float, y: Float): KeyboardModel.MeasuredKey? =
        model?.keyAt(x, y)
            ?.takeIf { it.bottom <= height + 0.5f }
            ?.takeIf { it.kind != "margin" } // F27a1/a2: MARGIN = reine Breite, kein Touch

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (model == null) return
        // S3: Canvas deckend mit der Tastaturfläche füllen (Sol §1 — Root und Canvas
        // deckend, flach; normale Tasten sind identisch zum Hintergrund).
        canvas.drawColor(palette.background)
        for (key in visibleKeys()) {
            // F27a2: MARGIN = transparente Breite — kein KeySurface, keine Glyphe,
            // kein Touch (Hit-Test ignoriert es oben); Ziffernraster bleibt gezeichnet.
            if (key.kind == "margin") continue
            val pressed = key === pressedKey
            val isMic = key.kind == "action" && key.value == KeyActions.MIC
            // D11: Flächen-/Glyphen-Entscheidung über die reine Naht (KeySurface) —
            // micRecording > Caps-Füllung (echtes capsLock, bleibt gedrückt) > pressed > normal;
            // Glyphe konträr zur Füllung, temporäres Shift nur Glyphen-Akzent.
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
                KeySurface.Surface.MIC_RECORDING -> palette.accent // D10-Rest §2 unverändert
                KeySurface.Surface.CAPS_FILL -> palette.accent // D11: CapsLock-Akzentfläche
                KeySurface.Surface.PRESSED -> palette.keyPressed
                KeySurface.Surface.NORMAL -> palette.keyNormal
            }
            // F36/V2 §7: Tastenfläche = Vollrechteck minus 2 px Fuge JE SEITE (Fuge
            // zeigt den Hintergrund — 4 px zwischen Tasten, einzige Abgrenzung, keine
            // eigenen Trennlinien), Eckenradius aus dem Theme (10 px @ 2,625); der
            // gedrückte/Caps-/Mic-Zustand nutzt dieselbe Fläche (Farbe aus dem Theme).
            rect.set(
                key.left + keyFugePx,
                key.top + keyFugePx,
                key.right - keyFugePx,
                key.bottom - keyFugePx,
            )
            if (cornerRadius > 0f) {
                canvas.drawRoundRect(rect, cornerRadius, cornerRadius, keyPaint)
            } else {
                canvas.drawRect(rect, keyPaint) // V1-Restpfad (Radius 0) — V2 nimmt rund
            }
            // Typografie nach Rolle (Größe) + Zustand (Farbe — gedrückt ändert sie NICHT):
            val paint: Paint = when {
                key.kind != "text" && key.value == KeyActions.MIC -> micPaint // D10: 26 dp
                // F27b: Shift/Backspace/Enter-Icons größer (Sonstige Actions bleiben 14 sp).
                key.kind != "text" && key.value in largeActionGlyphs -> actionGlyphPaint
                key.kind != "text" && key.value == KeyActions.SPACE -> secondaryPaint // 12 sp
                key.kind != "text" -> functionPaint                                       // 14 sp
                key.value.length == 1 && key.value[0].isDigit() -> digitPaint            // 24 sp
                else -> letterPaint                                                      // 26 sp
            }
            paint.color = when {
                key.kind == "text" -> palette.textPrimary
                // D10-Rest §2 unverändert: Häkchen in der Toggle-Glyph-Farbe.
                isMic && micCheckShowing -> palette.keyToggledGlyph
                // D11: Glyphen-Tokens aus der Naht — Gegenfarbe (micRecordingGlyph)
                // auf Akzentflächen (Caps/Aufnahme), Akzent (keyToggledGlyph) nur
                // für temporäres Shift / Symbol-Shift, sonst Rollenfarbe.
                decision.glyph == KeySurface.Glyph.CONTRAST -> palette.micRecordingGlyph
                decision.glyph == KeySurface.Glyph.ACCENT -> palette.keyToggledGlyph
                key.value == KeyActions.SPACE -> palette.textSecondary
                else -> palette.textFunctional
            }
            val y = key.centerY - (paint.ascent() + paint.descent()) / 2f
            // D10-Rest §2: Häkchen ersetzt das Mic-Glyph für 600 ms (gleiche Zelle,
            // gleiche Größe — kein Größenwechsel, keine Animation).
            when {
                isMic && micCheckShowing -> canvas.drawText("✓", key.centerX, y, paint)
                // F29: Shift/Backspace/Enter als Pfade mit IDENTISCHER Strichstärke
                // (Farbe wie der übrige Glyph-Zweig: paint.color ist bereits gesetzt).
                key.kind != "text" && key.value in largeActionGlyphs ->
                    drawActionGlyph(canvas, key.centerX, key.centerY, key.value, paint.color)
                else -> canvas.drawText(displayLabel(key), key.centerX, y, paint)
            }
            drawKeyHint(canvas, key) // F23a2: Ziffernhint rechts oben (§7), sonst No-Op
        }
        // F22 (D12 §1): die Menu-Ebene wird ZULETZT gezeichnet (eigener Pfad);
        // die Grundtaste bleibt sichtbar — das Menü sitzt mit 8 dp Abstand darüber.
        popup?.let { drawLongPressMenu(canvas, it) }
    }

    // ---- F22: Langdruckmenü — reine Maße via LongPressMenu, hier nur Adapter ----

    /**
     * F23a2 (DESIGN_KEY_POPUP.md §7.2-§7.5): kleine Ziffernhint-Legende rechts
     * oben — NUR Texttasten mit gesetztem hint in base/shift (symbols/Actions/
     * Space/Mic/Popup nie, §7.4; hint wird nie transformiert). Passt die Legende
     * nicht in die Taste, wird NUR sie ausgelassen (§7.5, keine Kollisionstaeuschung).
     */
    private fun drawKeyHint(canvas: Canvas, key: KeyboardModel.MeasuredKey) {
        val src = sourceKeys[key.id] ?: return
        if (src.kind != KeyKind.TEXT || src.hint == null) return
        if (activeLayerName != "base" && activeLayerName != "shift") return
        val hint = src.hint
        val ink = hintPaint.measureText(hint)
        val padPx = KeyboardTheme.POPUP_PAD_DP * density
        val cx = KeySurface.hintAnchorX(key.right, padPx, ink)
        if (cx - ink / 2f < key.left) return // zu breite Legende fuer die Taste: aus
        val rows = layout?.layers?.get(activeLayerName)?.rows?.size ?: 4
        val topDp = KeySurface.hintTopOffsetDp(
            KeyboardTheme.POPUP_PAD_DP,
            ((key.bottom - key.top) / density).toDouble(),
            KeyboardTheme.rowHeightDp(rows),
        )
        val glyphTop = key.top + topDp * density
        // Baseline aus echten Font-Metriken (§7.2), nicht "Oberkante + 2 dp".
        val baseline = glyphTop - hintPaint.ascent()
        if (baseline + hintPaint.descent() > key.bottom) return // kein Platz: aus
        hintPaint.color = palette.textSecondary
        canvas.drawText(hint, cx, baseline, hintPaint)
    }

    // F23c: Menu-Liste = Sprachalternates + Hint-Ziffer am Ende (reine Naht
    // LongPressMenu.withHint); MAX_CELLS-Deckel und Entfaltungsregeln dort.
    private fun alternatesOf(key: KeyboardModel.MeasuredKey): List<String> {
        val src = sourceKeys[key.id] ?: return emptyList()
        return LongPressMenu.withHint(src.alternates, src.hint, LongPressMenu.MAX_CELLS)
    }

    private fun openPopupIfArmed() {
        val key = armedKey ?: return
        // Generations-/Zeigerschutz (§2): Schicht gewechselt oder Geste abgebrochen
        // -> kein Menü. Der dritte alte Teil "Finger nicht mehr auf der Ursprungstaste"
        // (key !== pressedKey) ist F24e5d ENTFALLEN: pressedKey wird beim Schieben
        // bereits auf null gesetzt (Zeile im MOVE-Zweig: pressedKey = if (inKey ===
        // pressedKey) ... else null) und hat hier die Bewaffnung vor dem Timer
        // verworfen, sobald der Finger die Grundtaste verliess — genau das "halten,
        // DANN in die Menuezeile schieben" von F22-L ist aber der vorgesehene Weg.
        // Der Schutz wirkt weiterhin ueber activeLayerName/armedLayer (Schichtwechsel)
        // und armCancelledByMove (Zone wirklich verlassen); ein UP ausserhalb der
        // Palette committet ohnehin nie (popupSelectionAt / cellWins, Zeile ~715).
        if (armCancelledByMove || activeLayerName != armedLayer) {
            if (debuggable()) {
                // TEMPORARE F24e5-DIAGNOSE: frueher Abbruch im Timer (nur Zahlen).
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
        val menuH = popupHeightPx // F22-L: identische Maße wie die Arming-Zone
        val gapPx = popupGapPx
        val menuW = LongPressMenu.menuWidthPx(cellPx, n, padPx)
        // Passt die komplette Geometrie nicht in den View-Clip: Menü öffnet NICHT
        // (D12 §1; der Flip-Fallback der obersten Reihe ist separater Abnahmepunkt).
        if (width <= 0 || height <= 0 || menuW > width) {
            armedKey = null
            return
        }
        // T7 (v0.1.37): Popup-links aus der MENGE der Zellen — Standard-
        // Zentrierung über der Grundtaste, dann Klemmung an beide View-Kanten
        // (menuLeftClamped), so dass KEINE Zelle außerhalb des Views läuft.
        val left = LongPressMenu.menuLeftClamped(n, key.centerX.toInt(), cellPx, width, padPx)
        // F28a2: Zeilenposition + Fußzeile + Statusleistenhoehe — Zeile 0: T1-
        // Zweizonenregel (Zone A über der Taste, komplett im View; sonst Zone B:
        // Kante top=0 — NIE unter der Taste, Nutzerbefund vc34); Fußzeile: direkt
        // ueber der Taste; mittlere: eine Zeile hoeher.
        val rowHeightPx = (key.bottom - key.top).toInt()
        val rowIndex = model?.keys?.count { it.top < key.top } ?: 1
        // F28a2: "ist Fußzeile" UEBER DEN INHALT der Reihe (Space-Key) — nicht ueber
        // rowIndex==letzte: (a) die Fußzeile ist nicht die letzte Modellreihe
        // (darunter liegt die ⌄/Globus-Leiste), (b) rowIndex zaehlte Keys, nicht
        // Reihen. Geometrie (keyTop nahe View-Boden) nur als Fallback ohne Modell.
        val isFooterRow = model?.keys?.any { it.top == key.top && it.value == KeyActions.SPACE }
            ?: (key.top >= height - 2 * rowHeightPx)
        // T5 (vc37, Nutzer-Urteil Emulator-Bild): mit bekanntem STATUSBAND öffnet
        // das r0-Menü IM BAND (menuTopForStatusBand) — eine Zeile über der
        // Buchstabentaste, dort wo Statuszeile+🐞 sitzen. Bandhöhe = eigene
        // Oberkante im Stack (das Band gehört zum Root, NICHT zur View);
        // Reihe-darunter-Top = Unterkante der gedrückten Taste (Invariante:
        // Menü nie unter die Reihe der Taste). Ohne Band (top=0, z. B. vor dem
        // Layout) bleibt die T1-Zweizonenregel.
        val bandPx = this.top
        val top = LongPressMenu.menuTopPx(
            key.top.toInt(), menuH, gapPx, rowHeightPx, rowIndex, isFooterRow,
            padPx = padPx,
            cellPx = cellPx,
            // F54: Platz ueber der KeyboardView-Oberkante = eigene Top-Kante
            // im Stack (Statuszeilen-Hoehe).
            spaceAbovePx = bandPx,
            statusBandPx = bandPx,
            rowBelowTop = key.bottom.toInt(),
        )
        if (top + menuH > height) {
            armedKey = null
            return
        }
        // F24e1: Aktionszellen aus dem Asset (parallel zu alternates, also auch zu
        // glyphs — ein eventuelles hint liegt immer HINTER den Aktionswerten);
        // nur so viele Zellen wie das Menü wirklich zeigt.
        val actionCells = sourceKeys[key.id]?.cellActions?.take(n) ?: emptyList()
        popup = LongPressPopup(key, glyphs.take(n), n, left, top, menuW, menuH, cellPx, padPx, actionCells)
        // T5 (vc37): ragt das Menü über die View-Oberkante ins STATUSBAND, hebt
        // sich die View KURZZEITIG über die statusRow (F34: elevation 1f) — sonst
        // bliebe die Palette im Band Z-hinter der Leiste unsichtbar (F54-Familie).
        // Das Menü bleibt INNERHALB der Stack-Grenzen (Band-Top = Stack-Top) ⇒ KEINE
        // Root-/Window-Clipping-Kette nötig; der Stack hat clipChildren=false (F28a).
        // Reset beim Schließen (discardLongPress/UP-CANCEL) — translationY und
        // Sichtbarkeit bleiben unberührt (Auftrags-Regel).
        if (top < 0) {
            elevation = 2f
            popupAboveView = true
        }
        // F24e3 MESSBARKEIT (Regel 9: nur Zahlen, kein Glyph/Feldinhalt): damit liegt
        // die Palette, ohne sie zu erraten. Nur Debug-Build (wie das VK-HIT in ACTION_UP).
        if (debuggable()) {
            android.util.Log.d("VK-HIT", "popup-open cells=$n left=$left top=$top")
        }
        popupSelected = -1
        invalidate()
    }

    /**
     * Trefferzelle: LongPressMenu.pick auf der Innenkoordinate, zusätzlich exakt
     * auf die Zellfläche begrenzt — Polsterstreifen (4 dp) und Menu-Rand treffen
     * nie eine Alternative (D12 §6 paddingIsNotACharacter).
     */
    private fun popupCellAt(p: LongPressPopup, x: Float, y: Float): Int {
        if (y < p.top + p.padPx || y >= p.top + p.padPx + p.cellPx) return -1
        val idx = LongPressMenu.pick((x - (p.left + p.padPx)).toInt(), p.cellPx + p.padPx, p.cells)
        if (idx < 0) return -1
        val cellLeft = (p.left + p.padPx).toFloat() + idx * (p.cellPx + p.padPx)
        return if (x >= cellLeft && x < cellLeft + p.cellPx) idx else -1
    }

    /**
     * Zellwahl eines Touchpunkts — erst das Grundzeichen-Tor [LongPressMenu.cellWins]
     * (F22-M: Grundtaste verlassen, Richtung egal — Slop = moveCancelPx, Herkunft
     * KeyboardTheme.POPUP_MOVE_CANCEL_DP = 8 dp, docs/DESIGN_KEY_POPUP.md §2
     * „8 dp / 21 px“-Linie; Touch-Koordinate gegen das Tasten-Rechteck), dann die
     * exakte Zellflaeche (Polsterstreifen treffen nie).
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
     * Commit über den bestehenden Pfad: Text via listener.onTextInput (D12 §2);
     * F24e0: traegt die Zelle eine Aktion (LongPressMenu.cellAction), wird sie wie
     * ein Tastendruck über listener.onAction gesendet (z. B. sendKeyEvent-Zweige
     * des Services) — Zeichnung, Schwellwerte und cellWins bleiben unberührt.
     */
    private fun commitAlternate(p: LongPressPopup, cell: Int) {
        val action = LongPressMenu.cellAction(cell, p.actionCells)
        // F24e3 MESSBARKEIT (Regel 9: Zellindex + Aktionsname aus der Allowlist oder "-",
        // kein Glyph, kein Feldinhalt). Nur Debug-Build.
        if (debuggable()) {
            android.util.Log.d("VK-HIT", "popup-commit cell=$cell action=${action ?: "-"}")
        }
        if (action != null) {
            listener?.onAction(action)
        } else {
            listener?.onTextInput(p.glyphs[cell])
        }
        // Temporäres Shift wird NUR bei erfolgreichem Commit verbraucht (D12 §2).
        if (shiftTemporary) {
            shiftTemporary = false
            applyLayerState()
        }
    }

    private fun isInsideKey(key: KeyboardModel.MeasuredKey, x: Float, y: Float): Boolean =
        x >= key.left && x < key.right && y >= key.top && y < key.bottom

    /** VK-HIT-Logs nur im Debug-Build (Regel 9 — Release bleibt log-frei). */
    private fun debuggable(): Boolean =
        (context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0

    /** Eigener Zeichenpfad: deckende Fläche keyPressed, Auswahlzelle accent, Zeichen textPrimary. */
    private fun drawLongPressMenu(canvas: Canvas, p: LongPressPopup) {
        rect.set(p.left.toFloat(), p.top.toFloat(), (p.left + p.width).toFloat(), (p.top + p.height).toFloat())
        popupPaint.color = palette.keyPressed
        if (popupRadius > 0f) canvas.drawRoundRect(rect, popupRadius, popupRadius, popupPaint)
        else canvas.drawRect(rect, popupPaint)
        for (i in 0 until p.cells) {
            val cellLeft = (p.left + p.padPx).toFloat() + i * (p.cellPx + p.padPx)
            val selected = i == popupSelected
            if (selected) {
                // Highlight innerhalb der Menü-Außenkante (D12 §1: clippt, kein Rahmen).
                popupPaint.color = palette.accent
                rect.set(cellLeft, (p.top + p.padPx).toFloat(), cellLeft + p.cellPx, (p.top + p.padPx + p.cellPx).toFloat())
                canvas.drawRect(rect, popupPaint)
            }
            popupGlyphPaint.color = if (selected) palette.micRecordingGlyph else palette.textPrimary
            val cx = cellLeft + p.cellPx / 2f
            val cy = (p.top + p.padPx).toFloat() + p.cellPx / 2f
            val base = cy - (popupGlyphPaint.ascent() + popupGlyphPaint.descent()) / 2f
            // P0 (2026-11-01): die drei Action-Glyphen im Menü kommen aus dem
            // PFADEXPORT — kein Font-Glyph (⌫ wäre auf dem Gerät Tofu).
            when (p.glyphs[i]) {
                "⌫" -> drawActionGlyph(canvas, cx, cy, KeyActions.BACKSPACE, popupGlyphPaint.color, popupGlyphPaint.textSize)
                "⇧", "⇪" -> drawActionGlyph(canvas, cx, cy, KeyActions.SHIFT, popupGlyphPaint.color, popupGlyphPaint.textSize)
                "⏎" -> drawActionGlyph(canvas, cx, cy, KeyActions.ENTER, popupGlyphPaint.color, popupGlyphPaint.textSize)
                else -> canvas.drawText(p.glyphs[i], cx, base, popupGlyphPaint)
            }
        }
    }

    /** Timer + Menü verwerfen (Layout-/Größen-/Editorwechsel, §2): 0 Zeichen. */
    private fun discardLongPress() {
        if (armedKey != null && debuggable()) {
            // TEMPORARE F24e5d-DIAGNOSE (nur Zahlen): Bewaffnung starb hier.
            android.util.Log.d("VK-HIT", "arm-discard")
        }
        removeCallbacks(longPressRunnable)
        armedKey = null
        armedMenuLeftPx = -1 // F24e5c
        armedMenuWidthPx = -1
        if (popup != null) {
            popup = null
            popupSelected = -1
            if (popupAboveView) { // T5: Band-Overlay wieder ablassen (statusRow nach vorn)
                elevation = 0f
                popupAboveView = false
            }
        }
    }

    override fun onDetachedFromWindow() {
        discardLongPress()
        cancelBackspaceRepeat() // B2: kein Repeat-Timer nach Detach
        super.onDetachedFromWindow()
    }

    /** F29: die drei Action-Glyphen (⇧/⌫/⏎) als Canvas-Pfade — IDENTISCHE
     *  Strichstärke ([KeyboardTheme.ACTION_GLYPH_STROKE_DP]) statt Font-Glyphen.
     *  Bindekiste = ACTION_GLYPH_DP quadratisch, zentriert auf (cx, cy). */
    /** F29/P0 (2026-11-01): die drei Action-Glyphen (⇧/⌫/⏎) als Canvas-Pfade —
     *  IDENTISCHE Strichstärke ([KeyboardTheme.ACTION_GLYPH_STROKE_DP]) und
     *  IDENTISCHE optische Höhe (Bindekiste 0,60·s hoch). F36/V2 §8: ⌫ = reiner
     *  Links-Pfeil mit Sockel (X entfernt); F46: ⇧ = Aufwärts-Pfeil aus Schaft +
     *  Kopf + Sockel im selben Stil; ⏎ = Enter-Pfeil. [size] = Bindekiste (Standard
     *  ACTION_GLYPH_DP; Popup übergibt seine eigene Glyphen-Größe). */
    /** F34/P2-3 (REVIEW_F29_F33): Pfad-Cache je Glyphen-Typ + Größe + Position —
     *  vorher neue Path-Allokation je Taste je onDraw (Hot-Path). Die Key-Positionen
     *  sind pro Layout fix, der Cache wächst also einmalig und bleibt stabil. */
    private val glyphPathCache = HashMap<Triple<String, Int, Int>, List<Path>>()

    /** F34/P2-3: die Glyph-Pfade (2 je Type) bauen — EINMAL je (value, size, cx, cy),
     *  Ergebnis wird in [glyphPathCache] gehalten. Koordinaten in View-Pixel. */
    private fun buildActionGlyphPaths(value: String, s: Float, cx: Float, cy: Float): List<Path> {
        val outline = Path()
        val detail = Path()
        when (value) {
            KeyActions.BACKSPACE -> {
                // F36/V2 §8: ⌫ = REINER Links-Pfeil mit Sockel (das alte X ist entfernt)
                // — Pfadpunkte der 1x1-Spec-Box (Schaft 0,80|0,50 → 0,30|0,50; Kopf
                // 0,52|0,30 → 0,30|0,50 → 0,52|0,70; Sockel 0,18|0,32 → 0,18|0,68),
                // proportionale Skalierung (×1,5) in die s-Bindekiste ⇒ optische
                // Höhe 0,60·s wie ⇧/⏎ („im ⇧/⏎-Stil", P0-Gleiche-Höhe-Gate),
                // Strichstärke ACTION_GLYPH_STROKE_DP, runde Enden (Paint-Cap ROUND).
                outline.moveTo(cx + 0.465f * s, cy)               // Schaft rechts
                outline.lineTo(cx - 0.285f * s, cy)               // Schaft zur Spitze
                outline.moveTo(cx + 0.045f * s, cy - 0.30f * s)   // Kopf oben
                outline.lineTo(cx - 0.285f * s, cy)               // Pfeilspitze links
                outline.lineTo(cx + 0.045f * s, cy + 0.30f * s)    // Kopf unten
                detail.moveTo(cx - 0.465f * s, cy - 0.27f * s)    // Sockel oben
                detail.lineTo(cx - 0.465f * s, cy + 0.27f * s)     // Sockel unten
            }
            KeyActions.ENTER -> {
                // ⏎: Return-Pfeil — runter, nach links, Spitze zeigt nach links
                // (optische Höhe 0,60s wie die anderen beiden)
                outline.moveTo(cx + 0.24f * s, cy - 0.30f * s)
                outline.lineTo(cx + 0.24f * s, cy + 0.10f * s)
                outline.lineTo(cx - 0.30f * s, cy + 0.10f * s)
                detail.moveTo(cx - 0.10f * s, cy - 0.10f * s)
                detail.lineTo(cx - 0.30f * s, cy + 0.10f * s)
                detail.lineTo(cx - 0.10f * s, cy + 0.30f * s)
            }
            else -> {
                // F46 (Nutzer-Befund v0.1.29): ⇧ = Aufwärts-Pfeil im GLEICHEN Stil wie
                // ⌫/⏎ — Stroked-Pfad aus SCHAFT + PFEILKOPF (+ Sockelleiste wie ⌫),
                // KEIN Umriss-/Flächendreieck (der F39-Entwurf wirkte als Dreieck).
                // Proportionen: V2-§8-Referenz (Schaft/Kopf/Sockel), in die 0,60·s-
                // Bindekiste skaliert — P0-Gleiche-Höhe-Gate zu ⌫/⏎ bleibt erfüllt,
                // Strichstärke ACTION_GLYPH_STROKE_DP (2,0 dp), runde Enden.
                // T2 (v0.1.35, Nutzerbefund Pixel/vc34): Pfeil (Kopf+Balken) von
                // der Sockelleiste ABGESETZT — Schaft-Unterkante und Linie aus
                // der reinen Naht KeyboardTheme.ShiftGlyph ( Korrespondenz zum
                // ⌫, wo der Kopf 0,18·s vom Sockel abgesetzt ist; vorher 0,07·s:
                // mit dem 2-dp-Strich verschmolzen — „stand auf der Linie auf").
                outline.moveTo(cx, cy + KeyboardTheme.ShiftGlyph.arrowBottomRatio() * s) // Schaft unten (T2: abgesetzt)
                outline.lineTo(cx, cy - 0.26f * s)                // Schaft in den Kopf
                outline.moveTo(cx - 0.24f * s, cy - 0.06f * s)     // Kopf-Flügel links
                outline.lineTo(cx, cy - 0.30f * s)                 // Spitze
                outline.lineTo(cx + 0.24f * s, cy - 0.06f * s)     // Kopf-Flügel rechts
                detail.moveTo(cx - 0.20f * s, cy + KeyboardTheme.ShiftGlyph.BASE_LINE_RATIO * s) // Sockelleiste wie ⌫
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
        // F34/P2-3: Pfade aus dem Cache (einmalig gebaut), onDraw zeichnet nur.
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
                    // D10 §0/§1: Space zeigt den NAMEN der aktiven Sprache im Endonym
                    // („Deutsch", „English (US)", „Polski" …). Quelle ist die VORHANDENE
                    // Endonym-Tabelle LanguageCycle.ENDONYMS — keine neue Tabelle;
                    // aufgelöst über SpaceLabelRule (Nicht-Whitespace gewinnt).
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

    /** D10-Rest §1: Der Pointer, der das Fussreihen-Mic gedrückt hat (Haltegeste). */
    private var micHeld: KeyboardModel.MeasuredKey? = null

    /** T3 (v0.1.35): eventTime des Mic-DOWN — Basis der DOWN→UP-Dauer für die
     *  Tap/Hold-Klassifikation (Service: micHoldReleaseDecision). */
    private var micDownEventTimeMs: Long = 0L

    /** T5 (vc37): das offene Popup ragt über die View-Oberkante ins Statusband —
     *  View solange über die statusRow gehoben (elevation 2f > 1f, F34). */
    private var popupAboveView = false

    /** True, solange die Aufnahme läuft — Mic-Fläche in Akzentfarbe (D10 §2). */
    var micRecording: Boolean = false
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }

    /** D10 §2: Commit-Häkchen nach erfolgreichem Abschluss — 600 ms, keine Vollflächenblitz. */
    private var micCheckShowing = false

    // ---- B2 (PLAN_V0133 §B2): Backspace-Repeat — reine Zeitlogik + Timer-Adapter ----

    /** B2: Zeitplan (450 ms Start, 70 ms Periode) — JVM-testbar (BackspaceTest). */
    private val backspaceRepeat = BackspaceRepeat()

    /** B2: Repeat-Feuerung — läuft über SELBEN Servicezweig (onAction(BACKSPACE)). */
    private val backspaceRepeatRunnable = Runnable {
        if (backspaceRepeat.tick(android.os.SystemClock.elapsedRealtime())) {
            listener?.onAction(KeyActions.BACKSPACE)
        }
        scheduleBackspaceRepeat()
    }

    private fun scheduleBackspaceRepeat() {
        val delay = backspaceRepeat.nextDelayMs(android.os.SystemClock.elapsedRealtime()) ?: return
        postDelayed(backspaceRepeatRunnable, delay)
    }

    /** B2: externer Stopp (Service: Editorwechsel; View: Detach/UP/CANCEL). */
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
                // B2 (PLAN_V0133 §B2): Backspace-DOWN führt GENAU EINE Aktion aus
                // und armiert den Repeat-Timer (450 ms Start / 70 ms Periode) —
                // UP/CANCEL beendet ohne zweite Aktion (kein Doppeldelete).
                if (down != null && down.kind == "action" && down.value == KeyActions.BACKSPACE) {
                    if (backspaceRepeat.down(android.os.SystemClock.elapsedRealtime())) {
                        handleAction(KeyActions.BACKSPACE, event.eventTime)
                    }
                    scheduleBackspaceRepeat()
                }
                // F22 (D12 §2): Bewaffnen NUR fuer Texttasten mit Alternativen —
                // Mic/Caps/Enter/Loeschen/?123/Globus/Emoji/Space sind ACTIONs und
                // bleiben auf ihrem bestehenden Pfad (Mic-Haltegeste, DOUBLE_TAP 350).
                if (down != null && down.kind == "text" && alternatesOf(down).isNotEmpty()) {
                    armedKey = down
                    armedLayer = activeLayerName
                    downX = event.x
                    downY = event.y
                    armCancelledByMove = false
                    // F24e5c: echte Palette beim Armen berechnen und merken — die
                    // MOVE-Zone benutzt AUSSCHLIESSLICH diese beiden Werte.
                    val cellPx = (KeyboardTheme.POPUP_CELL_DP * density).roundToInt()
                    val padPx = (KeyboardTheme.POPUP_PAD_DP * density).roundToInt()
                    val menuW = LongPressMenu.menuWidthPx(
                        cellPx, LongPressMenu.cellCount(alternatesOf(down)), padPx,
                    )
                    if (width > 0 && menuW <= width) {
                        armedMenuWidthPx = menuW
                        // T7 (v0.1.37): dieselbe Clamp-Position wie beim Öffnen
                        // (menuLeftClamped) — die Arming-Zone ist die TATSAECHLICHE
                        // Palette, nie die ungeklemmte Nennmitte.
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
                // D10-Rest §1: DOWN auf dem Fussreihen-Mic startet die Haltegeste —
                // ausschließlich DIESER Pointer besitzt sie; ein späterer zweiter
                // Pointer (z. B. 🌐) erzeugt kein synthetisches Mic-UP.
                if (down != null && down.kind == "action" && down.value == KeyActions.MIC) {
                    micHeld = down
                    // T3 (v0.1.35): Dauermessung beim Mic-DOWN — eventTime des DOWN
                    // merken; das UP reicht die Dauer an den Service weiter
                    // (Tap ≤ 300 ms vs. Hold-Release, micHoldReleaseDecision).
                    micDownEventTimeMs = event.eventTime
                    listener?.onMicHoldStart()
                }
                performClick()
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (event.findPointerIndex(activePointerId) != -1) {
                    // F22: offenes Menu -> Zellauswahl (nur Zeichnen, kein Commit);
                    // sonst Arming-Zone (F22-L): halten-dann-schieben ist der
                    // vorgesehene Weg — abgebrochen wird erst ausserhalb der Zone.
                    val p = popup
                    if (p != null) {
                        // F22-M: Zellauswahl NUR nachdem der Finger die Grundtaste
                        // wirklich verlassen hat (LongPressMenu.cellWins, Richtung
                        // egal) — MOVE-Jitter auf der Grundtaste waehlt nie eine Zelle.
                        val cell = popupSelectionAt(p, event.x, event.y)
                        if (cell != popupSelected) {
                            popupSelected = cell
                            invalidate()
                        }
                    } else if (armedKey != null && !armCancelledByMove) {
                        // F22-L: Zonenregel der reinen Naht statt 8-dp-Kugel —
                        // seitlich keyWidth/2 + slop, aufwaerts bis Popup-Oberkante,
                        // abwaerts keyHeight/2 + slop. echtes Schieben in die
                        // Menuezeile haelt die Bewaffnung bis zum Timer.
                        val k = armedKey!!
                        // F24e5c: Grenzen NIE neu berechnen — die beim Armen gemerkte
                        // echte Palette ist massgebend (-1 = alten Modus fahren).
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
                                // T1 (v0.1.35, Debugger-Nachschärfe): Position und
                                // Arming wechseln GEMEINSAM — die Zweizonenregel
                                // (LongPressMenu.menuTopPx) öffnet das Menü NIE
                                // unter der Grund taste (r0 lag bisher per B1-
                                // Below-Fallback exakt über Reihe 2, Nutzerbefund
                                // vc34). Die F38-Gespiegelte (Abwärts-Zone) ist
                                // damit im Adapter tot; stillArmed läuft im F22-L-
                                // Standard: Aufwärts-/Seitwärts-Schwenk in die
                                // Menüzeile bleibt bewaffnet, Abwärts nur bis
                                // keyHeight/2 + slop. Die gespiegelte Zone bleibt
                                // als Funktionsvertrag in LongPressMenu getestet.
                                menuBelowKey = false,
                            )
                        ) {
                            armedKey = null
                            armCancelledByMove = true // dieselbe Geste bewaffnet nie neu
                            removeCallbacks(longPressRunnable)
                        } else if (debuggable()) {
                            // TEMPORARE F24e5-DIAGNOSE (nur Zahlen): Zone hielt bei diesem MOVE.
                            android.util.Log.d(
                                "VK-HIT",
                                "arm-hold dx=${(event.x - downX).toInt()} dy=${(event.y - downY).toInt()}",
                            )
                        }
                    }
                    val inKey = keyAtVisible(event.x, event.y)
                    // Pointer-Capture: Taste des DOWN bleibt gedrückt; Abweisung nur optisch.
                    // Die Mic-Haltegeste bleibt dabei bestehen (Loslassen = Stop,
                    // egal ob der Finger ueber der Zelle steht — PTT-Semantik).
                    pressedKey = if (inKey === pressedKey) pressedKey else null
                    invalidate()
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val idx = event.findPointerIndex(activePointerId)
                // D10-Rest §1: Mic-Haltegeste — Loslassen beendet (UP: mit Finale),
                // ACTION_CANCEL bricht ab (abort, kein Final-Decode). Der Tap-Pfad
                // (handleKey/onAction) wird für das Mic NICHT zusätzlich gefeuert
                // (kein Doppel-Start).
                val wasMicHold = micHeld
                micHeld = null
                // F22: Popup-/Timer-Aufräumen vor allen Pfaden — ausserhalb und
                // CANCEL committen NIE ein Zeichen (§2), der Timer committet spaeter nie.
                val openPopup = popup
                popup = null
                popupSelected = -1
                if (popupAboveView) { // T5: Band-Overlay wieder ablassen (statusRow nach vorn)
                    elevation = 0f
                    popupAboveView = false
                }
                if (armedKey != null && debuggable()) {
                    // TEMPORARE F24e5d-DIAGNOSE: UP(1)/CANCEL(3) während Bewaffnung
                    // (nur Zahlen) — CANCEL wäre ein Geste-Abbruch durch die Plattform.
                    android.util.Log.d("VK-HIT", "arm-end action=${event.actionMasked}")
                }
                armedKey = null
                armedMenuLeftPx = -1 // F24e5c: Zonen-Felder zuruecksetzen
                armedMenuWidthPx = -1
                removeCallbacks(longPressRunnable)
                // B2: Backspace-Repeat stoppt bei UP/CANCEL deterministisch — der
                // normale UP-Tap-Pfad feuert für Backspace NICHT nochmal (Doppeldelete).
                backspaceRepeat.cancel()
                removeCallbacks(backspaceRepeatRunnable)
                if (wasMicHold != null) {
                    // T3: DOWN→UP-Dauer aus den gemerkten eventTimes — reine
                    // Zusatz-Information (Tap ≤ 300 ms vs. Hold-Release).
                    listener?.onMicHoldEnd(
                        cancelled = event.actionMasked == MotionEvent.ACTION_CANCEL,
                        holdDurationMs = (event.eventTime - micDownEventTimeMs).coerceAtLeast(0L),
                    )
                } else if (openPopup != null) {
                    // F22: offenes Menu — UP zaehlt; UP/CANCEL ausserhalb: 0 Zeichen.
                    if (idx != -1 && event.actionMasked == MotionEvent.ACTION_UP) {
                        // F22-M: UP ohne echtes Verlassen der Grundtaste faellt durch
                        // cellWins() auf -1 zurueck -> Grundzeichen-Zweig gewinnt
                        // (D12 §2; Geraetefunde „è“ und Reihe-0-Ausschluss).
                        val cell = popupSelectionAt(openPopup, event.x, event.y)
                        when {
                            cell >= 0 -> commitAlternate(openPopup, cell) // echtes Schieben
                            isInsideKey(openPopup.key, event.x, event.y) ->
                                handleKey(openPopup.key, event.eventTime) // Grundzeichen genau einmal
                            else -> Unit // Abbruch: 0 Zeichen, kein „letzten Treffer“
                        }
                    }
                } else if (idx != -1 && event.actionMasked == MotionEvent.ACTION_UP) {
                    val key = keyAtVisible(event.x, event.y)
                    // P0-MESSBARKEIT: genau EINE Debug-Zeile pro ACTION_UP (nur im
                    // Debug-Build; keine Text-/Feldinhalte, nur Tasten-id + Geometrie).
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
                        handleKey(key, event.eventTime) // B2: Backspace feuerte schon bei DOWN
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
     * D10 §2: kurzlebiges Commit-Häkchen — NUR nach tatsächlich akzeptiertem
     * Edit (Aufruf: Service im onStopped-Finale), 600 ms, kein Größenwechsel
     * und keine Layoutanimation; die Zelle bleibt an ihrem Ort.
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
                // Deadkeys folgen in Stufe 2; hier ohne Wirkung.
            }
            KeyKind.MARGIN -> {
                // F27a2: reine Randflaeche — nie tippbar (Hit-Test ignoriert sie
                // bereits; dieser Ast ist Defensive-Programmierung).
            }
            KeyKind.ACTION -> handleAction(src.value, eventTime)
        }
    }

    private fun handleAction(action: String, eventTime: Long) {
        when (action) {
            KeyActions.SHIFT -> {
                // P1-Fix: In den Symbolebenen schaltet die Shift-Taste zwischen
                // symbols und symbolsShift um (Base-Ebene: Shift/CapsLock wie gehabt).
                if (activeLayerName == "symbols" || activeLayerName == "symbolsShift") {
                    symbolShift = !symbolShift
                    switchLayer(
                        if (symbolShift) "symbolsShift" else "symbols",
                        fromSymbols = true,
                    )
                    return
                }
                // P1-02-Fix: Zuerst Doppel-Tipp-Test — der „abschalten"-Zweig stand
                // vorher vor dem Doppel-Tipp-Check, sodass der zweite Tap Shift
                // abschaltete statt CapsLock zu aktivieren.
                if (eventTime - lastShiftTapMs < DOUBLE_TAP_MS && shiftTemporary && !capsLock) {
                    // Doppel-Tipp auf Shift → CapsLock (zweiter Tap innerhalb DOUBLE_TAP_MS).
                    capsLock = true
                    shiftTemporary = false
                    lastShiftTapMs = eventTime
                } else if (shiftTemporary || capsLock) {
                    // Shift bei aktivem temporärem Shift oder CapsLock deaktiviert alles.
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
     * Ebenenwechsel inkl. Modell-Rebuild und Re-Render.
     * Beim Wechsel in die Symbolebenen wird der Shift-Zustand der Buchstaben
     * eingefroren und beim Zurückschalten wiederhergestellt.
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

    /** Shift/CapsLock → Layer-Anzeige aktualisieren (nur auf Buchstaben-Ebenen wirksam). */
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
     * Virtuelle Knoten je Taste: TalkBack/dritte Apps sehen damit jede Taste
     * als eigenes, anklickbares Element mit Label (contentDescription) und
     * CLICK-Aktion (performAction → gleicher Pffad wie Touch).
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
        visibleKeys().getOrNull(virtualViewId) // T19: gleiche Liste wie onDraw (IDs stabil)

    /** Menschlich lesbare Beschreibung einer Taste für TalkBack. */
    private fun accessibilityDescription(key: KeyboardModel.MeasuredKey): String {
        val src = sourceKeys[key.id]
        return when (src?.kind) {
            KeyKind.ACTION -> when (src.value) {
                KeyActions.BACKSPACE -> "Löschen"
                KeyActions.ENTER -> "Eingabetaste"
                KeyActions.SPACE -> "Leertaste"
                KeyActions.SHIFT -> if (capsLock) "Feststell shift" else "Umschalt"
                KeyActions.LAYER_SYMBOLS -> "Ziffern und Symbole"
                KeyActions.LAYER_BASE -> "Buchstaben"
                KeyActions.CURSOR_LEFT -> "Cursor links"
                KeyActions.CURSOR_RIGHT -> "Cursor rechts"
                KeyActions.LANGUAGE -> "Sprache wechseln"
                KeyActions.EMOJI -> "Emojis"
                KeyActions.MIC -> "Diktieren"
                else -> src.label
            }
            KeyKind.TEXT -> "Taste ${src?.label ?: key.label}"
            KeyKind.DEAD -> "Tottaste ${src?.label ?: key.label}"
            KeyKind.MARGIN -> "" // F27a2: unsichtbare Randflaeche — kein TalkBack-Name
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
        info.contentDescription = "Tastatur ${layout?.language?.label ?: ""}"
    }

    override fun onPopulateAccessibilityEvent(event: AccessibilityEvent) {
        super.onPopulateAccessibilityEvent(event)
        if (event.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED) {
            pressedKey?.let { event.text.add(accessibilityDescription(it)) }
        }
    }

    companion object {
        private const val DOUBLE_TAP_MS = 350L

        /** D10 §2: Commit-Häkchen-Dauer („600 ms, kein Vollflächenblitz"). */
        private const val MIC_CHECK_MS = 600L

        /** T19/S2: Design-Kappe 276 dp ≈ 727 px mit Navigationsleiste (704 px ohne). */
        private const val MAX_HEIGHT_DP = 276
    }
}
