package de.voicekeyboard.keyboard

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import org.json.JSONObject

/**
 * Emoji-Panel (E9.4, M8b-2/M8b-3): Overlay ÜBER der Tastatur innerhalb des
 * IME-Fensters (kein eigenes Window — Fokus/Editor bleiben unverändert, E3).
 *
 * M8b-3:
 *  - E-01: Das Panel zeichnet ZUERST einen deckenden Hintergrund über seine
 *    gesamte eigene Fläche; darunter liegende Sichten (die Tastatur) sind nie
 *    sichtbar. INVARIANTE: erst Hintergrund, dann Glyphen.
 *  - E-02: Die Panel-Höhe wird vom Container auf die Tastaturhöhe begrenzt;
 *    die Zellgröße wird aus der VERFÜGBAREN Höhe berechnet (nicht aus fester
 *    Zeilenhöhe). Passen 6 Zeilen nicht, rendert das Panel 5 Zeilen × 8 = 40
 *    pro Seite und blättert innerhalb der Kategorie — die Nav-Zeile liegt
 *    INNERHALB der Panelfläche, nichts überragt die Tastatur.
 *
 * Einfügen ausschließlich über den bestehenden Schreibweg
 * ([Listener.onEmojiSelected] → VoiceKeyboardService.onTextInput → commitText).
 * Die Seiten-/Index-Logik ist als reine JVM-Funktion in [EmojiPages] extrahiert.
 */
class EmojiPanelView @JvmOverloads constructor(
    context: Context,
    attrs: android.util.AttributeSet? = null,
) : View(context, attrs) {

    interface Listener {
        fun onEmojiSelected(emoji: String)
        fun onDismissRequested()
    }

    var listener: Listener? = null

    /** Rohe Kategorie-Definitionen (id, labelKey, emoji-Liste) — reine Daten. */
    data class EmojiCategory(val id: String, val labelKey: String, val emoji: List<String>)

    private val density = resources.displayMetrics.density
    private val cellMinPx = 44f * density // Trefferhoehe >= 44dp (E9.4)
    private val navHeightPx = 40f * density // Blätterzeile (E10.2-Hoehe)
    private val navBottomPaddingPx = 8f * density // Abstand innerhalb der EIGENEN Flaeche (V2)

    // S4 — Stock-Tokens (DESIGN_STOCK_LOOK §1): dieselbe Palette wie Tastatur/Leiste,
    // hell/dunkel nach System-Nachtmodus; keine eigenen Farbwerte mehr. Größen und
    // Zeilenzahl des Rasters bleiben UNVERÄNDERT (nur Farben getauscht).
    private val palette = KeyboardTheme.palette(
        isDark = (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES,
    )
    private val bgPaint = Paint().apply { color = palette.background } // deckend (E-01)
    private val linePaint = Paint().apply {
        color = palette.separator
        strokeWidth = 1f * density
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        color = palette.textPrimary
        textSize = 26f * density
    }
    private val uiPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }

    private var categories: List<EmojiCategory> = emptyList()
    private var categoryIndex = 0
    private var pageInCategory = 0 // Seitencursor innerhalb der Kategorie (E-02-Fallback)

    /** Aktuelle Raster-Zeilenzahl (6 oder weniger, aus der Hoehe berechnet). */
    private var rows = EmojiPages.ROWS_PER_PAGE
    private var pageSize = EmojiPages.PAGE_SIZE

    var loaded: Boolean = false
        private set

    fun loadCategories(cats: List<EmojiCategory>) {
        categories = cats
        categoryIndex = 0
        pageInCategory = 0
        loaded = cats.isNotEmpty()
        requestLayout()
        invalidate()
    }

    /** Laedt das JSON-Asset (schemaVersion 1, categories[].{id,labelKey,emoji}). */
    fun loadFromJson(json: String) {
        val root = JSONObject(json)
        require(root.optInt("schemaVersion") == 1) { "Unbekannte emoji-Asset schemaVersion" }
        val arr = root.getJSONArray("categories")
        val cats = (0 until arr.length()).map { i ->
            val c = arr.getJSONObject(i)
            val list = c.getJSONArray("emoji")
            EmojiCategory(
                id = c.getString("id"),
                labelKey = c.getString("labelKey"),
                emoji = (0 until list.length()).map { list.getString(it) },
            )
        }
        loadCategories(cats)
    }

    private var cellW = 0f
    private var cellH = 0f
    private var gridTop = 0f
    private var gridBottom = 0f

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        // V2: respektiert das vom Layout-Manager (LinearLayout, weight=1) uebergebene
        // Hoehenlimit — resolveSize klemmt auf das Angebot, kein Aufblaehen darueber
        // (ein Stack-Kind darf die IME-Root nie vergroessern). Zu wenig Flaeche
        // reduziert die ZEILENZAHL (onSizeChanged), nie die Hoehe.
        val h = resolveSize(suggestedMinimumHeight, heightMeasureSpec)
        setMeasuredDimension(w, h)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        cellW = w / EmojiPages.COLUMNS.toFloat()
        // V2: Grid-Hoehe = EIGENE (vom Layout-Manager zugewiesene) Hoehe minus
        // Blätterzeile minus Randabstand — coerceIn: nie mehr als vorhanden;
        // bei zu wenig Platz sinkt die Zeilenzahl auf mindestens 1, die Hoehe nie.
        val gridHeight = (h - navHeightPx - navBottomPaddingPx)
            .coerceIn(cellMinPx, h.toFloat().coerceAtLeast(cellMinPx))
        rows = EmojiPages.rowsForHeight(gridHeight, cellMinPx)
        pageSize = EmojiPages.COLUMNS * rows
        cellH = gridHeight / rows
        gridTop = 0f
        gridBottom = gridTop + cellH * rows
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        // E-01-INVARIANTE: Panel zeichnet ZUERST einen deckenden Hintergrund über
        // seine gesamte eigene Fläche; darunter liegende Sichten sind nie sichtbar.
        // (E10.1: die unterste Tastenreihe liegt AUSSERHALB dieser Fläche und bleibt
        // sichtbar/bedienbar — die Fläche endet an der Oberkante der untersten Reihe.)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgPaint)
        // feine Trennlinie oben (zur Statuszeile)
        canvas.drawLine(0f, 0f, width.toFloat(), 0f, linePaint)
        if (categories.isEmpty()) return
        val page = EmojiPages.pageOf(categories[categoryIndex].emoji, pageInCategory, pageSize)
        for (index in page.indices) {
            val emoji = page[index]
            val row = index / EmojiPages.COLUMNS
            val col = index % EmojiPages.COLUMNS
            val cx = col * cellW + cellW / 2f
            val cy = gridTop + row * cellH + cellH / 2f -
                (textPaint.ascent() + textPaint.descent()) / 2f
            canvas.drawText(emoji, cx, cy, textPaint)
        }
        // Blätterzeile INNERHALB der eigenen Flaeche (V2): Kategoriename + Seite,
        // Anzeige immer aus dem realisierten Raster (kein fester Wert).
        // KEIN stiltes Dismiss mehr auf der Seitenmitte — Rueckweg ist ausschließlich
        // der native Footer-Button (V2 §3).
        val pagesInCat = EmojiPages.pagesInCategory(
            categories[categoryIndex].emoji.size, pageSize,
        )
        uiPaint.textSize = 15f * density
        uiPaint.color = palette.textFunctional // S4: Funktionstext (Kategorie/Seite)
        val navY = gridBottom + (navHeightPx - navBottomPaddingPx) / 2f -
            (uiPaint.ascent() + uiPaint.descent()) / 2f
        val catLabel = categoryLabel(categories[categoryIndex].labelKey)
        canvas.drawText("‹", 30f * density, navY, uiPaint)
        canvas.drawText(
            "$catLabel ${pageInCategory + 1}/$pagesInCat",
            width / 2f, navY, uiPaint,
        )
        canvas.drawText("›", width - 30f * density, navY, uiPaint)
    }

    /** Kategoriename aus der String-Ressource (E10.2); Fallback: Key ohne Praefix. */
    private fun categoryLabel(labelKey: String): String = try {
        resources.getString(
            resources.getIdentifier(labelKey, "string", context.packageName),
        )
    } catch (e: Exception) {
        labelKey.removePrefix("emoji_cat_")
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> return true
            MotionEvent.ACTION_UP -> {
                val x = event.x
                val y = event.y
                val mid = width / 2f
                when {
                    y < gridBottom -> { // Raster: Emoji einfuegen (bestehender Schreibweg)
                        val page = EmojiPages.pageOf(
                            categories[categoryIndex].emoji, pageInCategory, pageSize,
                        )
                        val index = EmojiPages.indexAt(
                            x, y, cellW, cellH, gridTop,
                            EmojiPages.COLUMNS, rows,
                        )
                        val emoji = page.getOrNull(index) ?: return true
                        listener?.onEmojiSelected(emoji)
                        performClick()
                    }
                    else -> { // Nav-Zeile: NUR Blättern (V2 §3: kein stiltes Dismiss)
                        when {
                            x < mid - 60f * density -> prevPage()
                            x > mid + 60f * density -> nextPage()
                            else -> Unit // Mitte = reine Kategorie-/Seitenanzeige
                        }
                    }
                }
                return true
            }
        }
        return false
    }

    /**
     * E10.4: Schließen des Panels (von aussen, z. B. 😊-Toggle oder Sprachwechsel).
     * Kategorie bleibt erhalten, Seite → 1 (Seite 0 intern). Das Panel fasst die
     * Ebenen des KeyboardView NICHT an — E10.1 macht die unterste Reihe sichtbar,
     * der Zustand (activeLayerName/capsLock/Shift) bleibt von allein erhalten,
     * KEINE savedLayer-Maschinerie nötig (explizite E10.1-Vereinfachung).
     */
    fun closeAndResetPage() {
        pageInCategory = 0
    }

    fun openWithFreshPage() {
        pageInCategory = 0
    }

    private fun nextPage() {
        val pages = EmojiPages.pagesInCategory(categories[categoryIndex].emoji.size, pageSize)
        if (pageInCategory + 1 < pages) {
            pageInCategory++
        } else { // Wrap: naechste Kategorie, erste Seite
            categoryIndex = (categoryIndex + 1) % categories.size
            pageInCategory = 0
        }
        invalidate()
    }

    private fun prevPage() {
        if (pageInCategory > 0) {
            pageInCategory--
        } else { // Wrap: vorige Kategorie, letzte Seite
            categoryIndex = (categoryIndex - 1 + categories.size) % categories.size
            pageInCategory = EmojiPages.pagesInCategory(
                categories[categoryIndex].emoji.size, pageSize,
            ) - 1
        }
        invalidate()
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }
}

/**
 * Reine JVM-Logik des Emoji-Rasters (E9.4): Seitenzuordnung, Index-Berechnung,
 * Zeilen-/Seitenaufteilung, Asset-Validierung. Keine Android-Typen.
 */
object EmojiPages {

    const val COLUMNS = 8
    const val ROWS_PER_PAGE = 6
    const val PAGE_SIZE = COLUMNS * ROWS_PER_PAGE // 48
    const val CATEGORIES = 8
    const val TOTAL = CATEGORIES * PAGE_SIZE // 384

    /**
     * E-02: Zeilenzahl fuer die verfuegbare Grid-Hoehe — hoechstens
     * ROWS_PER_PAGE, mindestens 1; nie mehr Zeilen als die Hoehe zulaesst.
     */
    fun rowsForHeight(gridHeightPx: Float, cellMinPx: Float): Int {
        if (gridHeightPx <= 0f || cellMinPx <= 0f) return 1
        return minOf(ROWS_PER_PAGE, (gridHeightPx / cellMinPx).toInt()).coerceAtLeast(1)
    }

    /** Seitenzahl einer Kategorie bei gegebener Seitengroesse. */
    fun pagesInCategory(categorySize: Int, pageSize: Int): Int {
        if (pageSize <= 0) return 0
        return (categorySize + pageSize - 1) / pageSize
    }

    /** Seite `page` der Kategorie (Reste am Ende mitgezogen). */
    fun pageOf(category: List<String>, page: Int, pageSize: Int = PAGE_SIZE): List<String> {
        if (pageSize <= 0) return emptyList()
        val from = page * pageSize
        if (from >= category.size) return emptyList()
        return category.subList(from, minOf(category.size, from + pageSize))
    }

    /**
     * Raster-Index fuer Touch-Koordinaten; -1 wenn ausserhalb des Rasters.
     * Zeile/Spalte aus Zellgroesse, Index = row * columns + col.
     */
    fun indexAt(
        x: Float, y: Float, cellW: Float, cellH: Float, gridTop: Float,
        columns: Int = COLUMNS, rows: Int = ROWS_PER_PAGE,
    ): Int {
        if (cellW <= 0f || cellH <= 0f) return -1
        val col = (x / cellW).toInt()
        val row = ((y - gridTop) / cellH).toInt()
        if (col < 0 || col >= columns || row < 0 || row >= rows) return -1
        return row * columns + col
    }

    /** UTF-16-Einheiten eines Glyphen (Surrogatpaar = 2, BMP = 1). */
    fun utf16Units(emoji: String): Int = emoji.length

    /** Graphem-Cluster-Anzahl (v1-Asset: jedes Emoji ist genau 1 Cluster). */
    fun graphemeCount(emoji: String): Int =
        java.text.BreakIterator.getCharacterInstance().let {
            it.setText(emoji)
            var count = 0
            var end = it.first()
            while (end != java.text.BreakIterator.DONE) {
                val s = it.next()
                if (s == java.text.BreakIterator.DONE) break
                count++
                end = s
            }
            count
        }

    /** Validiert die geladenen Kategorien gegen E9.4 (wirft bei Verstoß). */
    fun validate(categories: List<EmojiCategoryData>) {
        require(categories.size == CATEGORIES) {
            "Erwartet genau $CATEGORIES Kategorien, gefunden: ${categories.size}"
        }
        val ids = categories.map { it.id }
        require(ids.size == ids.distinct().size) { "Doppelte Kategorie-ID: $ids" }
        val all = mutableListOf<String>()
        for (cat in categories) {
            require(cat.emoji.size == PAGE_SIZE) {
                "Kategorie '${cat.id}': ${cat.emoji.size} Glyphen != $PAGE_SIZE"
            }
            require(cat.emoji.distinct().size == PAGE_SIZE) {
                "Kategorie '${cat.id}': doppelte Glyphen"
            }
            for (e in cat.emoji) {
                require(e.isNotEmpty()) { "Leerer Emoji-Eintrag in '${cat.id}'" }
                // E9.4: keine Hautton-Modifier (U+1F3FB..U+1F3FF), keine Flaggen (Regional Indicators)
                require(!e.any { ch -> ch.code in 0x1F3FB..0x1F3FF }) {
                    "Hautton-Modifier in '$e' (${cat.id}) verboten (E9.4)"
                }
                require(e.none { ch -> ch.code in 0x1F1E6..0x1F1FF }) {
                    "Flaggen (Regional Indicator) in '$e' (${cat.id}) verboten (E9.4)"
                }
                // kein ZWJ (U+200D) — v1-Asset hat nur Einzel-/Paar-Codepoints
                require('\u200D' !in e) { "ZWJ-Sequenz '$e' (${cat.id}) verboten (E9.4)" }
            }
            all += cat.emoji
        }
        require(all.size == TOTAL) { "Gesamt ${all.size} != $TOTAL" }
    }

    /** Reine Datenklasse (unabhaengig von View-Innenklasse, JVM-testbar). */
    data class EmojiCategoryData(val id: String, val labelKey: String, val emoji: List<String>)
}
