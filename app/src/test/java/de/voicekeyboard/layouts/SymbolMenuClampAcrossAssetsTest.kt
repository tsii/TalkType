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
 * T7 (v0.1.37) + vc39 (REVIEW_V0137 P2#3): Menü-Clamp gegen den View-Rand auf
 * den Symbolebenen — zwei Gegenproben über ALLE 15 Assets:
 *
 *  1. INVARIANTE: für JEDE Menü-Menge (value + alternates, ≤ 8) der Symbolebenen
 *     gilt bei Standard-Zentrierung über der Grundtaste (Zentrieren + Klemmen
 *     an beide View-Kanten via [LongPressMenu.menuLeftClamped]): linkeste
 *     Zellkante ≥ 0 UND rechte Zellkante ≤ View-Breite. Vc39: die Raster-
 *     Geometrie wird NICHT mehr inline nachgebaut (View/Σ-Konstante), sondern
 *     über die PRODUKTIONS-Normierung [KeyboardModel] bezogen (keyCenterX aus
 *     dem gemessenen Modell, Fugen-/Padding-Parameter wie KeyboardView:
 *     KEY_GAP_DP · density, horizontalPadding 0) — driftet KEY_GAP_DP oder
 *     Padding, folgt der Test automatisch mit. Zusätzlich Drift-Anker: bei
 *     KEY_GAP_DP = 0 und Padding = 0 ist JEDE Zelle pixelgleich mit dem
 *     Einheitsraster (1u = View/Σ der Reihe) — der Anker wird ROT, statt dass
 *     der Test heimlich mit veralteter Inline-Geometrie weiter grün läuft
 *     (Rot-Beleg: KEY_GAP_DP probehalber 1 ⇒ Ziffernzelle 105,6 statt 108 px ⇒
 *     FAILED, zurückgebaut; Meldung im vc39-Report).
 *     Formale Zell-Geometrie aus LongPressMenu: Zelle 48 dp, Polster 4 dp
 *     (je × density 2,625 → 126/11 px), Menübreite n·126 + (n+1)·11; View
 *     1080 px (Emulator 420 dpi). ROT-BELEG T7: eine Menü-Menge 10 (10 Zellen
 *     = 1381 px) kann in KEINER Position alle Zellen im View halten —
 *     Breiten-Prüfung schlägt an (nachgestellt mit 10 Alternates auf de-de/dot:
 *     FAILED, danach zurückgebaut; Meldung im T7-Report).
 *  2. T6-Zeichenverlust-HEILUNG (explizite Gegenprobe): die dokumentierten
 *     Vor-Zeichen (… ” ª º ‰ ±) sind je Asset wieder erreichbar (Direkttaste ∪
 *     Menü-Eintrag der Symbolebenen). Messung T7: … (10 Direkttasten-Assets
 *     de/en/es/fr/it/nl/pl/ru/sv/tr) und ± (dokumentiert cs/pl/ru/sv/tr) sind
 *     über die sprachneutralen T4-Menüs sogar in ALLEN 15 erreichbar —
 *     darum volle Asset-Liste; ” (en/pl/sv/tr), ª/º (es/pt), ‰ (da/fi) nach
 *     dokumentierter Vor-Belegung.
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

    /** Produktionsmaße wie KeyboardView: KeyboardTheme-Konstanten × density 2,625 (420 dpi). */
    private val density = 2.625f
    private val viewWidth = 1080
    private val cellPx = (KeyboardTheme.POPUP_CELL_DP * density).roundToInt()
    private val padPx = (KeyboardTheme.POPUP_PAD_DP * density).roundToInt()

    @Test
    fun jedeSymbolMenueMengeBleibtMitClampKomplettImView() {
        for (id in assetIds) {
            val a = asset(id)
            for (layerName in listOf("symbols", "symbolsShift")) {
                val layer = a.layers.getValue(layerName)
                // Vc39: PRODUKTIONS-Normierung statt Inline-View/Σ-Konstante —
                // KeyboardModel misst die Ebene exakt wie der Renderer (Fugen-
                // und Padding-Parameter wie KeyboardView.rebuildModel).
                val model = KeyboardModel(
                    layer = layer,
                    totalWidthPx = viewWidth.toFloat(),
                    keyHeightPx = (KeyboardTheme.POPUP_HEIGHT_DP * density).roundToInt().toFloat(),
                    rowGapPx = 0f,
                    keyGapPx = KeyboardTheme.KEY_GAP_DP * density,
                )
                val measured = model.keys.associateBy { it.id }
                // Drift-Anker (REVIEW_V0137 P2#3): KEY_GAP_DP = 0 und Padding = 0
                // ⇒ jede Zelle ist pixelgleich mit dem Einheitsraster (1u = View/Σ
                // der Reihe; PLAN_SYMBOL_RASTER „KEY_GAP_DP=0 ⇒ pixelgleich" mit dem
                // Buchstabenraster). Der Anker wird ROT, wenn jemand Fugen/Padding
                // ändert (Rot-Beleg vc39: KEY_GAP_DP probehalber 1 ⇒ Ziffernzelle
                // 105,6 statt 108 px ⇒ FAILED, zurückgebaut) — der Clamp-Teil allein
                // bliebe bewusst grün, weil der Clamp positionsrobust ist.
                for (row in layer.rows) {
                    val unitExpected = viewWidth / row.sumOf { it.width }
                    for (src in row) {
                        val mk = measured.getValue(src.id)
                        val w = mk.right - mk.left
                        assertTrue(
                            "$id/$layerName/${src.id} Zelle pixelgleich mit 1u=" +
                                "$unitExpected (KEY_GAP/Padding-Drift?) — ist $w",
                            abs(w - src.width * unitExpected) < 0.5f,
                        )
                    }
                }
                for (src in layer.rows.flatten()) {
                    if (src.kind == KeyKind.TEXT && src.alternates.isNotEmpty()) {
                        val n = src.alternates.size
                        assertTrue("$id/$layerName/${src.id} Menü-Menge ≤ 8 (${n + 1})",
                            n + 1 <= LongPressMenu.MAX_CELLS)
                        val menuW = LongPressMenu.menuWidthPx(cellPx, n, padPx)
                        assertTrue(
                            "$id/$layerName/${src.id} Menü $n Zellen ($menuW px) passt in den View",
                            menuW <= viewWidth,
                        )
                        val centerX = measured.getValue(src.id).centerX.toInt()
                        val left = LongPressMenu.menuLeftClamped(
                            n, centerX, cellPx, viewWidth, padPx,
                        )
                        assertTrue(
                            "$id/$layerName/${src.id} linkeste Zellkante ≥ 0 (left=$left)",
                            left >= 0,
                        )
                        assertTrue(
                            "$id/$layerName/${src.id} rechte Kante ≤ View " +
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
            // … : Direkttaste pre-T6 in de/en/es/fr/it/nl/pl/ru/sv/tr; da/fi/el
            //     ebenfalls im Bestand (dot-Menü bzw. Direkttaste) ⇒ alle 15.
            "…" to assetIds,
            // ” : Direkttaste pre-T6 in en/pl/sv/tr (heute: dquote-Menü).
            "”" to listOf("en-us", "pl-pl", "sv-se", "tr-tr"),
            // ª/º : Direkttasten pre-T6 in es/pt (bleiben Direkttasten).
            "ª" to listOf("es-es", "pt-pt"),
            "º" to listOf("es-es", "pt-pt"),
            // ‰ : Direkttaste pre-T6 in da/fi (T6-Verlagerung: percent-Menü).
            "‰" to listOf("da-dk", "fi-fi"),
            // ± : dokumentiert cs/pl/ru/sv/tr; nachgemessen T7: sprachneutrales
            //     plus-Menü [±] erreicht es in ALLEN 15 ⇒ volle Liste.
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
                    assertTrue("$id: T6-Vor-Zeichen '$ch' wieder erreichbar", ch in reachable)
                }
            }
        }
    }
}
