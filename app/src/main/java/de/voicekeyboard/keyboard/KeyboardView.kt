package de.voicekeyboard.keyboard

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
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
         * D10-Rest §1: Loslassen — Finger UP (cancelled=false) beendet MIT
         * Finale; ACTION_CANCEL (cancelled=true) bricht ab OHNE Final-Decode.
         * Nur der Pointer, der das Mic gedrückt hat, liefert dieses Ereignis.
         */
        fun onMicHoldEnd(cancelled: Boolean) {}
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

    // S3 — Typografie nach Rolle (Sol §1): 24 sp Buchstaben, 22 sp Ziffern,
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
    private val secondaryPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = KeyboardTheme.SP_SECONDARY_SP * resources.displayMetrics.scaledDensity
    }
    private val rect = RectF()

    private var activePointerId = MotionEvent.INVALID_POINTER_ID
    private var pressedKey: KeyboardModel.MeasuredKey? = null

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
        model?.keyAt(x, y)?.takeIf { it.bottom <= height + 0.5f }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (model == null) return
        // S3: Canvas deckend mit der Tastaturfläche füllen (Sol §1 — Root und Canvas
        // deckend, flach; normale Tasten sind identisch zum Hintergrund).
        canvas.drawColor(palette.background)
        for (key in visibleKeys()) {
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
            rect.set(key.left, key.top, key.right, key.bottom)
            if (cornerRadius > 0f) {
                canvas.drawRoundRect(rect, cornerRadius, cornerRadius, keyPaint)
            } else {
                canvas.drawRect(rect, keyPaint) // Stock: Radius 0 — keine Einzelkarten
            }
            // Typografie nach Rolle (Größe) + Zustand (Farbe — gedrückt ändert sie NICHT):
            val paint: Paint = when {
                key.kind != "text" && key.value == KeyActions.MIC -> micPaint // D10: 26 dp
                key.kind != "text" && key.value == KeyActions.SPACE -> secondaryPaint // 12 sp
                key.kind != "text" -> functionPaint                                       // 14 sp
                key.value.length == 1 && key.value[0].isDigit() -> digitPaint            // 22 sp
                else -> letterPaint                                                      // 24 sp
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
            val label = if (isMic && micCheckShowing) "✓" else displayLabel(key)
            canvas.drawText(label, key.centerX, y, paint)
        }
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

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                activePointerId = event.getPointerId(0)
                val down = keyAtVisible(event.x, event.y)
                pressedKey = down
                // D10-Rest §1: DOWN auf dem Fussreihen-Mic startet die Haltegeste —
                // ausschließlich DIESER Pointer besitzt sie; ein späterer zweiter
                // Pointer (z. B. 🌐) erzeugt kein synthetisches Mic-UP.
                if (down != null && down.kind == "action" && down.value == KeyActions.MIC) {
                    micHeld = down
                    listener?.onMicHoldStart()
                }
                performClick()
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (event.findPointerIndex(activePointerId) != -1) {
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
                if (wasMicHold != null) {
                    listener?.onMicHoldEnd(cancelled = event.actionMasked == MotionEvent.ACTION_CANCEL)
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
                    if (key != null && key === pressedKey) handleKey(key, event.eventTime)
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
