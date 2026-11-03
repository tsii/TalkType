package de.voicekeyboard.layouts

import de.voicekeyboard.keyboard.KeyboardModel
import de.voicekeyboard.keyboard.KeyboardTheme
import de.voicekeyboard.keyboard.LongPressMenu
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * T7 (v0.1.37) + vc39 (REVIEW_V0137 P2#3): menu clamping against the view edge on
 * the symbol layers — two counter-checks across ALL 15 assets:
 *
 *  1. INVARIANT: for EVERY menu set (value + alternates, ≤ 8) of the symbol layers
 *     with standard centering over the base key (centering + clamping
 *     to both view edges via [LongPressMenu.menuLeftClamped]): the leftmost
 *     cell edge ≥ 0 AND the right cell edge ≤ view width. Vc39: the grid
 *     geometry is NO LONGER rebuilt inline (view/Σ constant), but obtained via
 *     the PRODUCTION normalization [KeyboardModel] (keyCenterX from the
 *     measured model, gap/padding parameters like KeyboardView:
 *     KEY_GAP_DP · density, horizontalPadding 0) — if KEY_GAP_DP or
 *     padding drifts, the test follows automatically. Additionally a drift anchor:
 *     with KEY_GAP_DP = 0 and padding = 0 EVERY cell is pixel-identical with the
 *     unit grid (1u = view/Σ of the row) — the anchor turns RED instead of the
 *     test secretly staying green with outdated inline geometry
 *     (red proof: KEY_GAP_DP tentatively 1 ⇒ digit cell 105.6 instead of 108 px ⇒
 *     FAILED, reverted; message in the vc39 report).
 *     Formal cell geometry from LongPressMenu: cell 48 dp, padding 4 dp
 *     (each × density 2.625 → 126/11 px), menu width n·126 + (n+1)·11; view
 *     1080 px (emulator 420 dpi). RED PROOF T7: a menu set of 10 (10 cells
 *     = 1381 px) cannot keep all cells inside the view in ANY position —
 *     the width check fires (reproduced with 10 alternates on de-de/dot:
 *     FAILED, then reverted; message in the T7 report).
 *  2. T6 CHARACTER-LOSS REMEDY (explicit counter-check): the documented
 *     pre-characters (… ” ª º ‰ ±) are reachable again per asset (direct key ∪
 *     menu entry of the symbol layers). T7 measurement: … (10 direct-key assets
 *     de/en/es/fr/it/nl/pl/ru/sv/tr) and ± (documented cs/pl/ru/sv/tr) are
 *     reachable via the language-neutral T4 menus even in ALL 15 —
 *     hence the full asset list; ” (en/pl/sv/tr), ª/º (es/pt), ‰ (da/fi) per
 *     documented pre-assignment.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SymbolMenuClampAcrossAssetsTest {

    private val assetIds = listOf(
        "cs-cz", "da-dk", "de-de", "el-gr", "en-us", "es-es", "fi-fi", "fr-fr",
        "it-it", "nl-nl", "pl-pl", "pt-pt", "ru-ru", "sv-se", "tr-tr",
    )

    private fun asset(id: String) =
        LayoutParser.parse(java.io.File("src/main/assets/layouts/$id.json").readText())

    /** Production dimensions like KeyboardView: KeyboardTheme constants × density 2.625 (420 dpi). */
    private val density = 2.625f
    private val viewWidth = 1080
    private val cellPx = (KeyboardTheme.POPUP_CELL_DP * density).roundToInt()
    private val padPx = (KeyboardTheme.POPUP_PAD_DP * density).roundToInt()

    @Test
    fun everySymbolMenuSetStaysFullyInViewWithClamp() {
        for (id in assetIds) {
            val a = asset(id)
            for (layerName in listOf("symbols", "symbolsShift")) {
                val layer = a.layers.getValue(layerName)
                // Vc39: PRODUCTION normalization instead of an inline view/Σ constant —
                // KeyboardModel measures the layer exactly like the renderer (gap
                // and padding parameters like KeyboardView.rebuildModel).
                val model = KeyboardModel(
                    layer = layer,
                    totalWidthPx = viewWidth.toFloat(),
                    keyHeightPx = (KeyboardTheme.POPUP_HEIGHT_DP * density).roundToInt().toFloat(),
                    rowGapPx = 0f,
                    keyGapPx = KeyboardTheme.KEY_GAP_DP * density,
                )
                val measured = model.keys.associateBy { it.id }
                // drift anchor (REVIEW_V0137 P2#3): KEY_GAP_DP = 0 and padding = 0
                // ⇒ every cell is pixel-identical with the unit grid (1u = view/Σ
                // of the row; PLAN_SYMBOL_RASTER „KEY_GAP_DP=0 ⇒ pixel-identical" with
                // the letter grid). The anchor turns RED if someone changes gap/padding
                // (red proof vc39: KEY_GAP_DP tentatively 1 ⇒ digit cell
                // 105.6 instead of 108 px ⇒ FAILED, reverted) — the clamp part alone
                // would deliberately stay green, because the clamp is position-robust.
                for (row in layer.rows) {
                    val unitExpected = viewWidth / row.sumOf { it.width }
                    for (src in row) {
                        val mk = measured.getValue(src.id)
                        val w = mk.right - mk.left
                        assertTrue(
                            "$id/$layerName/${src.id} cell pixel-identical with 1u=" +
                                "$unitExpected (KEY_GAP/padding drift?) — is $w",
                            abs(w - src.width * unitExpected) < 0.5f,
                        )
                    }
                }
                for (src in layer.rows.flatten()) {
                    if (src.kind == KeyKind.TEXT && src.alternates.isNotEmpty()) {
                        val n = src.alternates.size
                        assertTrue("$id/$layerName/${src.id} menu set ≤ 8 (${n + 1})",
                            n + 1 <= LongPressMenu.MAX_CELLS)
                        val menuW = LongPressMenu.menuWidthPx(cellPx, n, padPx)
                        assertTrue(
                            "$id/$layerName/${src.id} menu $n cells ($menuW px) fits into the view",
                            menuW <= viewWidth,
                        )
                        val centerX = measured.getValue(src.id).centerX.toInt()
                        val left = LongPressMenu.menuLeftClamped(
                            n, centerX, cellPx, viewWidth, padPx,
                        )
                        assertTrue(
                            "$id/$layerName/${src.id} leftmost cell edge ≥ 0 (left=$left)",
                            left >= 0,
                        )
                        assertTrue(
                            "$id/$layerName/${src.id} right edge ≤ view " +
                                "(left=$left menuW=$menuW)",
                            left + menuW <= viewWidth,
                        )
                    }
                }
            }
        }
    }

    @Test
    fun t6VerloreneZeichenSindJeAssetWiederErreichbar() {
        val documented = mapOf(
            // … : direct key pre-T6 in de/en/es/fr/it/nl/pl/ru/sv/tr; da/fi/el
            //     likewise in the inventory (dot menu respectively direct key) ⇒ all 15.
            "…" to assetIds,
            // ” : direct key pre-T6 in en/pl/sv/tr (today: dquote menu).
            "”" to listOf("en-us", "pl-pl", "sv-se", "tr-tr"),
            // ª/º : direct keys pre-T6 in es/pt (stay direct keys).
            "ª" to listOf("es-es", "pt-pt"),
            "º" to listOf("es-es", "pt-pt"),
            // ‰ : direct key pre-T6 in da/fi (T6 relocation: percent menu).
            "‰" to listOf("da-dk", "fi-fi"),
            // ± : documented cs/pl/ru/sv/tr; re-measured T7: the language-neutral
            //     plus menu [±] reaches it in ALL 15 ⇒ full list.
            "±" to assetIds,
        )
        for (id in assetIds) {
            val a = asset(id)
            val reachable = mutableSetOf<String>()
            for (layerName in listOf("symbols", "symbolsShift")) {
                for (row in a.layers.getValue(layerName).rows) {
                    for (key in row) {
                        if (key.kind == KeyKind.TEXT) reachable.add(key.label)
                        key.alternates.forEach { reachable.add(it) }
                    }
                }
            }
            for ((ch, ids) in documented) {
                if (id in ids) {
                    assertTrue("$id: T6 pre-character '$ch' reachable again", ch in reachable)
                }
            }
        }
    }
}
