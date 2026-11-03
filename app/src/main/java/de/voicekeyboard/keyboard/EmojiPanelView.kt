package de.voicekeyboard.keyboard

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import org.json.JSONObject

/**
 * Emoji panel (E9.4, M8b-2/M8b-3): overlay ABOVE the keyboard inside the
 * IME window (no own window — focus/editor stay unchanged, E3).
 *
 * M8b-3:
 *  - E-01: the panel draws an opaque background FIRST over its
 *    entire own surface; views underneath (the keyboard) are never
 *    visible. INVARIANT: background first, then glyphs.
 *  - E-02: the panel height is limited by the container to the keyboard height;
 *    the cell size is computed from the AVAILABLE height (not from a fixed
 *    row height). If 6 rows do not fit, the panel renders 5 rows × 8 = 40
 *    per side and pages within the category — the nav row lies
 *    WITHIN the panel surface, nothing protrudes over the keyboard.
 *
 * Insertion exclusively over the existing writing path
 * ([Listener.onEmojiSelected] → VoiceKeyboardService.onTextInput → commitText).
 * The page/index logic is extracted as a pure JVM function in [EmojiPages].
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

    /** Raw category definitions (id, labelKey, emoji list) — pure data. */
    data class EmojiCategory(val id: String, val labelKey: String, val emoji: List<String>)

    private val density = resources.displayMetrics.density
    private val cellMinPx = 44f * density // hit height >= 44 dp (E9.4)
    private val navHeightPx = 40f * density // paging row (E10.2 height)
    private val navBottomPaddingPx = 8f * density // padding inside the OWN surface (V2)

    // S4 — stock tokens (DESIGN_STOCK_LOOK §1): the same palette as keyboard/bar,
    // light/dark by system night mode; no own color values anymore. Sizes and
    // row counts of the grid stay UNCHANGED (only colors swapped).
    private val palette = KeyboardTheme.palette(
        isDark = (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES,
    )
    private val bgPaint = Paint().apply { color = palette.background } // opaque (E-01)
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
    private var pageInCategory = 0 // page cursor within the category (E-02 fallback)

    /** Current grid row count (6 or fewer, computed from the height). */
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

    /** Loads the JSON asset (schemaVersion 1, categories[].{id,labelKey,emoji}). */
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
        // V2: respects the height limit passed by the layout manager (LinearLayout,
        // weight=1) — resolveSize clamps to the offer, no inflating beyond it
        // (a stack child must never enlarge the IME root). Too little space
        // reduces the ROW COUNT (onSizeChanged), never the height.
        val h = resolveSize(suggestedMinimumHeight, heightMeasureSpec)
        setMeasuredDimension(w, h)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        cellW = w / EmojiPages.COLUMNS.toFloat()
        // V2: grid height = OWN height (assigned by the layout manager) minus
        // paging row minus edge padding — coerceIn: never more than available;
        // with too little space the row count drops to at least 1, the height never.
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
        // E-01 INVARIANT: the panel draws an opaque background FIRST over
        // its entire own surface; views underneath are never visible.
        // (E10.1: the bottom key row lies OUTSIDE this surface and stays
        // visible/operable — the surface ends at the top edge of the bottom row.)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgPaint)
        // thin separator line at the top (toward the status row)
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
        // Paging row WITHIN the own surface (V2): category name + page,
        // display always from the realized grid (no fixed value).
        // NO silent dismiss on the page center anymore — the way back is exclusively
        // the native footer button (V2 §3).
        val pagesInCat = EmojiPages.pagesInCategory(
            categories[categoryIndex].emoji.size, pageSize,
        )
        uiPaint.textSize = 15f * density
        uiPaint.color = palette.textFunctional // S4: function text (category/page)
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

    /** Category name from the string resource (E10.2); fallback: key without prefix. */
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
                    y < gridBottom -> { // grid: insert emoji (existing writing path)
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
                    else -> { // nav row: ONLY paging (V2 §3: no silent dismiss)
                        when {
                            x < mid - 60f * density -> prevPage()
                            x > mid + 60f * density -> nextPage()
                            else -> Unit // center = pure category/page display
                        }
                    }
                }
                return true
            }
        }
        return false
    }

    /**
     * E10.4: closing the panel (from outside, e. g. 😊 toggle or language switch).
     * Category is kept, page → 1 (page 0 internally). The panel does not touch
     * the layers of the KeyboardView — E10.1 makes the bottom row visible,
     * the state (activeLayerName/capsLock/Shift) survives on its own,
     * NO savedLayer machinery needed (explicit E10.1 simplification).
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
        } else { // wrap: next category, first page
            categoryIndex = (categoryIndex + 1) % categories.size
            pageInCategory = 0
        }
        invalidate()
    }

    private fun prevPage() {
        if (pageInCategory > 0) {
            pageInCategory--
        } else { // wrap: previous category, last page
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
 * Pure JVM logic of the emoji grid (E9.4): page mapping, index computation,
 * row/page split, asset validation. No Android types.
 */
object EmojiPages {

    const val COLUMNS = 8
    const val ROWS_PER_PAGE = 6
    const val PAGE_SIZE = COLUMNS * ROWS_PER_PAGE // 48
    const val CATEGORIES = 8
    const val TOTAL = CATEGORIES * PAGE_SIZE // 384

    /**
     * E-02: row count for the available grid height — at most
     * ROWS_PER_PAGE, at least 1; never more rows than the height allows.
     */
    fun rowsForHeight(gridHeightPx: Float, cellMinPx: Float): Int {
        if (gridHeightPx <= 0f || cellMinPx <= 0f) return 1
        return minOf(ROWS_PER_PAGE, (gridHeightPx / cellMinPx).toInt()).coerceAtLeast(1)
    }

    /** Page count of a category at a given page size. */
    fun pagesInCategory(categorySize: Int, pageSize: Int): Int {
        if (pageSize <= 0) return 0
        return (categorySize + pageSize - 1) / pageSize
    }

    /** Page `page` of the category (remainders at the end included). */
    fun pageOf(category: List<String>, page: Int, pageSize: Int = PAGE_SIZE): List<String> {
        if (pageSize <= 0) return emptyList()
        val from = page * pageSize
        if (from >= category.size) return emptyList()
        return category.subList(from, minOf(category.size, from + pageSize))
    }

    /**
     * Grid index for touch coordinates; -1 if outside the grid.
     * Row/column from cell size, index = row * columns + col.
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

    /** UTF-16 units of a glyph (surrogate pair = 2, BMP = 1). */
    fun utf16Units(emoji: String): Int = emoji.length

    /** Grapheme cluster count (v1 asset: every emoji is exactly 1 cluster). */
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

    /** Validates the loaded categories against E9.4 (throws on violation). */
    fun validate(categories: List<EmojiCategoryData>) {
        require(categories.size == CATEGORIES) {
            "Expected exactly $CATEGORIES categories, found: ${categories.size}"
        }
        val ids = categories.map { it.id }
        require(ids.size == ids.distinct().size) { "Duplicate category ID: $ids" }
        val all = mutableListOf<String>()
        for (cat in categories) {
            require(cat.emoji.size == PAGE_SIZE) {
                "Category '${cat.id}': ${cat.emoji.size} glyphs != $PAGE_SIZE"
            }
            require(cat.emoji.distinct().size == PAGE_SIZE) {
                "Category '${cat.id}': duplicate glyphs"
            }
            for (e in cat.emoji) {
                require(e.isNotEmpty()) { "Empty emoji entry in '${cat.id}'" }
                // E9.4: no skin-tone modifiers (U+1F3FB..U+1F3FF), no flags (regional indicators)
                require(!e.any { ch -> ch.code in 0x1F3FB..0x1F3FF }) {
                    "Skin-tone modifier in '$e' (${cat.id}) forbidden (E9.4)"
                }
                require(e.none { ch -> ch.code in 0x1F1E6..0x1F1FF }) {
                    "Flags (regional indicator) in '$e' (${cat.id}) forbidden (E9.4)"
                }
                // no ZWJ (U+200D) — the v1 asset has only single/pair code points
                require('\u200D' !in e) { "ZWJ sequence '$e' (${cat.id}) forbidden (E9.4)" }
            }
            all += cat.emoji
        }
        require(all.size == TOTAL) { "Total ${all.size} != $TOTAL" }
    }

    /** Pure data class (independent of the view inner class, JVM-testable). */
    data class EmojiCategoryData(val id: String, val labelKey: String, val emoji: List<String>)
}
