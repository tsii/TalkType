package de.voicekeyboard.layouts

import de.voicekeyboard.keyboard.KeyboardModel
import de.voicekeyboard.layouts.KeyActions
import de.voicekeyboard.layouts.KeyKind
import de.voicekeyboard.layouts.LayoutParser
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * V0.1.51 geometry warden tests (docs/PLAN_V0151.md §A — the tables below
 * are the binding replacement of the V0.1.50 correction of
 * docs/PLAN_GEOMETRY_V0149.md §1/§2).
 * Target geometry, tolerance ±1e-3 for relative widths, ±0.5 px for the
 * pixel probe, ±1e-6 for the ⌫ ratio:
 *
 *  - Letter layers (base+shift), 13 standard assets: Z1 = margin 0.500 +
 *    9 × 1.000 + ⌫ 1.000 (Σ 10.5; ⌫ == letter width, V0.1.51 user finding);
 *    Z2 = ⇧ 1.500 + 7 × 1.000 + ⏎ 1.500 (⏎ == ⇧, symmetric wings); footer =
 *    symbols 1.5 · language 1 · emoji 1 · space 6.5 · period 1 · mic 2 (Σ 13).
 *    NEW V0.1.50 alignment: the M center sits on the i|o GAP of Z0
 *    (8.0 units = 864 px at W=1080) — not centered under the i.
 *  - cs-cz: Z1 = margin 0.500 + 10 × 1.000 + ⌫ 1.000 (Σ 11.5); Z2 = ⇧ 2.000
 *    + 6 × 1.000 + ⏎ 2.000 (Σ 10, unchanged; the M is NOT on the i|o gap
 *    here by design — 6-letter row, table row "unverändert").
 *  - ru-ru: Z0 unchanged (13 keys, Σ 10); Z1 = margin 0.500 + 11 × 1.000 +
 *    ⌫ 1.000 (Σ 12.5); Z2 = ⇧ 1.500 + 10 × 0.900 + ⏎ 1.500 (Σ 12).
 *  - Symbol layers (symbols+symbolsShift), all 15: Z1 = margin 0.500 +
 *    10 × 1.000 + ⌫ 1.000 (Σ 11.5); Z2 = toggle 1.000 + 10 × 0.750 +
 *    ⏎ 1.500 (Σ 10); NO shift key; footer unchanged (space 7.5).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class GeometryV0149Test {

    private val stdAssets = listOf(
        "da-dk", "de-de", "el-gr", "en-us", "es-es", "fi-fi", "fr-fr", "it-it",
        "nl-nl", "pl-pl", "pt-pt", "sv-se", "tr-tr",
    )
    private val allAssets = stdAssets + listOf("cs-cz", "ru-ru")
    private val letterLayers = listOf("base", "shift")
    private val symbolLayers = listOf("symbols", "symbolsShift")
    private val allLayers = letterLayers + symbolLayers

    /** Row-1 spec: letters, letter width, margin width, backspace width. */
    private data class Z1Spec(val letters: Int, val letterW: Double, val marginW: Double, val backspaceW: Double)

    /** Row-2 spec: letters, letter width, shift width. */
    private data class Z2Spec(val letters: Int, val letterW: Double, val shiftW: Double)

    private fun z1Spec(id: String) = when (id) {
        "cs-cz" -> Z1Spec(10, 1.000, 0.500, 1.000)
        "ru-ru" -> Z1Spec(11, 1.000, 0.500, 1.000)
        else -> Z1Spec(9, 1.000, 0.500, 1.000)
    }

    private fun z2Spec(id: String) = when (id) {
        "cs-cz" -> Z2Spec(6, 1.000, 2.000)
        "ru-ru" -> Z2Spec(10, 0.900, 1.500)
        else -> Z2Spec(7, 1.000, 1.500)
    }

    private fun asset(id: String) =
        LayoutParser.parse(File("src/main/assets/layouts/$id.json").readText())

    private fun rows(id: String, layer: String) = asset(id).layers.getValue(layer).rows

    /** Character inventory of a layer: values ∪ labels of TEXT keys ∪ all alternates. */
    private fun inventory(layer: LayoutLayer): List<String> {
        val chars = mutableSetOf<String>()
        for (key in layer.rows.flatten()) {
            if (key.kind == KeyKind.TEXT) {
                chars.add(key.value)
                chars.add(key.label)
            }
            key.alternates.forEach { chars.add(it) }
        }
        return chars.sorted()
    }

    // ---------------------------------------------------------------- 1a

    /** V0.1.51 Σ table: letters 10/10.5/10/13 (cs 10/11.5/10/13,
     *  ru 10/12.5/12/13), symbols 10/11.5/10/13 for ALL 15. */
    private fun rowTarget(id: String, ln: String, r: Int): Double = when (r) {
        0 -> 10.0
        3 -> 13.0
        else -> when (ln) {
            "base", "shift" -> when (r) {
                1 -> when (id) { "cs-cz" -> 11.5; "ru-ru" -> 12.5; else -> 10.5 }
                else -> if (id == "ru-ru") 12.0 else 10.0
            }
            else -> if (r == 1) 11.5 else 10.0 // symbols/symbolsShift
        }
    }

    @Test
    fun rowSumsMatchTheV0151Table() {
        for (id in allAssets) {
            for (ln in allLayers) {
                val rs = rows(id, ln)
                for (r in 0..3) {
                    assertEquals("$id/$ln: Σ row $r", rowTarget(id, ln, r), rs[r].sumOf { it.width }, 1e-3)
                }
            }
        }
    }

    // ---------------------------------------------------------------- 1b

    @Test
    fun homeRowHasBackspaceRightOfL() {
        for (id in allAssets) {
            val spec = z1Spec(id)
            for (ln in letterLayers) {
                val z1 = rows(id, ln)[1]
                assertEquals("$id/$ln: Z1[0] is the margin", KeyKind.MARGIN, z1[0].kind)
                assertEquals("$id/$ln: Z1 margin width", spec.marginW, z1[0].width, 1e-3)
                assertEquals("$id/$ln: exactly one margin in Z1", 1, z1.count { it.kind == KeyKind.MARGIN })
                val backspace = z1.last()
                assertEquals("$id/$ln: ⌫ is the last key of Z1 (right of l)", "backspace", backspace.id)
                assertEquals("$id/$ln: ⌫ is an action key", KeyKind.ACTION, backspace.kind)
                assertEquals("$id/$ln: ⌫ value", KeyActions.BACKSPACE, backspace.value)
                assertEquals("$id/$ln: exactly ONE ⌫ in Z1 (V0.1.49 §0: variant A, not B)",
                    1, z1.count { it.value == KeyActions.BACKSPACE },
                )
                val letters = z1.filter { it.kind == KeyKind.TEXT }
                assertEquals("$id/$ln: Z1 letter count", spec.letters, letters.size)
                for (k in letters) {
                    assertEquals("$id/$ln/${k.id}: Z1 letter width", spec.letterW, k.width, 1e-3)
                }
                assertEquals(
                    "$id/$ln: ⌫ == letter width (V0.1.51: one full unit)",
                    letters.first().width, backspace.width, 1e-6,
                )
                assertEquals("$id/$ln: ⌫ width", spec.backspaceW, backspace.width, 1e-3)
            }
        }
    }

    // ---------------------------------------------------------------- 1c

    @Test
    fun enterEqualsShiftOnLetterLayers() {
        for (id in allAssets) {
            val spec = z2Spec(id)
            for (ln in letterLayers) {
                val z2 = rows(id, ln)[2]
                val enter = z2.last()
                assertEquals("$id/$ln: ⏎ is the last key of Z2", KeyActions.ENTER, enter.value)
                assertEquals("$id/$ln: ⏎ is an action key", KeyKind.ACTION, enter.kind)
                val shift = z2.first()
                assertEquals("$id/$ln: Z2[0] is ⇧", KeyActions.SHIFT, shift.value)
                assertEquals("$id/$ln: ⇧ width", spec.shiftW, shift.width, 1e-3)
                assertEquals(
                    "$id/$ln: ⏎ == ⇧ (symmetric wings, V0.1.50)",
                    shift.width, enter.width, 1e-3,
                )
                val letters = z2.filter { it.kind == KeyKind.TEXT }
                assertEquals("$id/$ln: Z2 letter count", spec.letters, letters.size)
                for (k in letters) {
                    assertEquals("$id/$ln/${k.id}: Z2 letter width", spec.letterW, k.width, 1e-3)
                }
                assertTrue("$id/$ln: no period in Z2 (moved to the footer)", z2.none { it.id == "period" })
            }
        }
    }

    // ---------------------------------------------------------------- 1d

    @Test
    fun footerDotBetweenSpaceAndMicAll15() {
        for (id in allAssets) {
            for (ln in allLayers) {
                val rs = rows(id, ln)
                assertEquals("$id/$ln: ⏎ exactly once in Z2", 1, rs[2].count { it.value == KeyActions.ENTER })
                assertEquals("$id/$ln: no ⏎ in the footer", 0, rs[3].count { it.value == KeyActions.ENTER })
            }
            for (ln in letterLayers) {
                val z3 = rows(id, ln)[3]
                assertEquals(
                    "$id/$ln: footer ids (period between space and mic)",
                    listOf("symbols", "language", "emoji", "space", "period", "mic"),
                    z3.map { it.id },
                )
                val byId = z3.associateBy { it.id }
                assertEquals("$id/$ln: space width", 6.500, byId.getValue("space").width, 1e-3)
                assertEquals("$id/$ln: mic width", 2.000, byId.getValue("mic").width, 1e-3)
                assertEquals("$id/$ln: mic is the last key", "mic", z3.last().id)
                assertEquals("$id/$ln: period is a text key", KeyKind.TEXT, byId.getValue("period").kind)
                assertEquals("$id/$ln: period value", ".", byId.getValue("period").value)
                assertEquals("$id/$ln: footer Σ", 13.0, z3.sumOf { it.width }, 1e-3)
            }
        }
    }

    // ---------------------------------------------------------------- 1e

    @Test
    fun symbolPagesToggleAtLeftAboveSpace() {
        for (id in allAssets) {
            for (ln in symbolLayers) {
                val rs = rows(id, ln)
                val z1 = rs[1]
                assertEquals("$id/$ln: Z1[0] is the margin", KeyKind.MARGIN, z1[0].kind)
                assertEquals("$id/$ln: Z1 margin width", 0.500, z1[0].width, 1e-3)
                val z1Letters = z1.filter { it.kind == KeyKind.TEXT }
                assertEquals("$id/$ln: 10 characters in Z1", 10, z1Letters.size)
                for (k in z1Letters) {
                    assertEquals("$id/$ln/${k.id}: Z1 character width", 1.000, k.width, 1e-3)
                }
                val backspace = z1.last()
                assertEquals("$id/$ln: ⌫ at the end of Z1", KeyActions.BACKSPACE, backspace.value)
                assertEquals(
                    "$id/$ln: ⌫ == character width (V0.1.51: one full unit)",
                    z1Letters.first().width, backspace.width, 1e-6,
                )
                assertEquals("$id/$ln: ⌫ width", 1.000, backspace.width, 1e-3)

                val z2 = rs[2]
                val toggle = z2.first()
                assertEquals("$id/$ln: Z2[0] is an action key (the toggle)", KeyKind.ACTION, toggle.kind)
                assertEquals("$id/$ln: toggle width", 1.000, toggle.width, 1e-3)
                val z2Letters = z2.filter { it.kind == KeyKind.TEXT }
                assertEquals("$id/$ln: 10 characters in Z2", 10, z2Letters.size)
                for (k in z2Letters) {
                    assertEquals("$id/$ln/${k.id}: Z2 character width", 0.750, k.width, 1e-3)
                }
                val enter = z2.last()
                assertEquals("$id/$ln: ⏎ at the end of Z2", KeyActions.ENTER, enter.value)
                assertEquals("$id/$ln: ⏎ width", 1.500, enter.width, 1e-3)
                assertEquals(
                    "$id/$ln: no shift key on the symbol layers",
                    0, rs.flatten().count { it.value == KeyActions.SHIFT },
                )
            }
            // Return toggle: symbolsShift Z2[0] leads back to the number page.
            val back = rows(id, "symbolsShift")[2].first()
            assertEquals("$id: symbolsShift Z2[0] returns to the numbers", KeyActions.LAYER_SYMBOLS, back.value)
            assertEquals("$id: symbolsShift Z2[0] label", "?123", back.label)
            assertEquals("$id: symbolsShift Z2[0] width", 1.000, back.width, 1e-3)
            // Forward toggle label …
            val forward = rows(id, "symbols")[2].first()
            assertEquals("$id: symbols Z2[0] label", "#+=", forward.label)
            // … and the Teil-B action (LAYER_SYMBOLS_ALT lives in KeyActions.ALL —
            // the parser accepts the real value, no shim anymore).
            assertEquals(
                "symbols Z2[0] action",
                "layer-symbols-shift", forward.value,
            )
        }
    }

    // ---------------------------------------------------------------- 1f

    @Test
    fun pxProbeAt1080() {
        for (id in allAssets) {
            for (ln in letterLayers) {
                val model = KeyboardModel(asset(id).layers.getValue(ln), 1080f, 100f, 0f, 0f, 0f)
                val rowGroups = model.keys.groupBy { it.top }.values.toList()
                assertEquals("$id/$ln: 4 rows", 4, rowGroups.size)
                for ((r, group) in rowGroups.withIndex()) {
                    assertEquals("$id/$ln: row $r right edge", 1080f, group.maxOf { it.right }, 0.5f)
                }
                fun textWidth(r: Int): Float {
                    val k = rowGroups[r].first { it.kind == "text" }
                    return k.right - k.left
                }
                fun widthById(r: Int, keyId: String): Float {
                    val k = rowGroups[r].single { it.id == keyId }
                    return k.right - k.left
                }
                when (id) {
                    "cs-cz" -> {
                        // Z1 Σ 11.5 → unit 93.91 px; Z2 Σ 10 → unit 108.0 px.
                        assertEquals("$id/$ln: Z1 margin 46.96 px", 46.96f, widthById(1, "m-left"), 0.5f)
                        assertEquals("$id/$ln: Z1 letter 93.91 px", 93.91f, textWidth(1), 0.5f)
                        assertEquals("$id/$ln: Z1 ⌫ 93.91 px", 93.91f, widthById(1, "backspace"), 0.5f)
                        assertEquals("$id/$ln: Z2 letter 108.0 px", 108.0f, textWidth(2), 0.5f)
                        assertEquals("$id/$ln: ⇧ 216.0 px", 216.0f, widthById(2, "shift"), 0.5f)
                        assertEquals("$id/$ln: ⏎ 216.0 px (== ⇧)", 216.0f, widthById(2, "enter"), 0.5f)
                    }
                    "ru-ru" -> {
                        // Z1 Σ 12.5 → unit 86.40 px; Z2 Σ 12 → unit 90.0 px.
                        assertEquals("$id/$ln: Z1 margin 43.20 px", 43.2f, widthById(1, "m-left"), 0.5f)
                        assertEquals("$id/$ln: Z1 letter 86.40 px", 86.4f, textWidth(1), 0.5f)
                        assertEquals("$id/$ln: Z1 ⌫ 86.40 px (== letter)", 86.4f, widthById(1, "backspace"), 0.5f)
                        assertEquals("$id/$ln: Z2 letter 81.0 px (Σ12 → u=90)", 81.0f, textWidth(2), 0.5f)
                        assertEquals("$id/$ln: ⇧ 135.0 px", 135.0f, widthById(2, "shift"), 0.5f)
                        assertEquals("$id/$ln: ⏎ 135.0 px (== ⇧)", 135.0f, widthById(2, "enter"), 0.5f)
                    }
                    else -> {
                        // Z1 Σ 10.5 → unit 102.86 px; Z2 Σ 10 → unit 108.0 px.
                        assertEquals("$id/$ln: Z1 margin 51.43 px", 51.43f, widthById(1, "m-left"), 0.5f)
                        assertEquals("$id/$ln: Z1 letter 102.86 px", 102.86f, textWidth(1), 0.5f)
                        assertEquals("$id/$ln: Z1 ⌫ 102.86 px (== letter)", 102.86f, widthById(1, "backspace"), 0.5f)
                        assertEquals("$id/$ln: ⇧ 162.0 px", 162.0f, widthById(2, "shift"), 0.5f)
                        assertEquals("$id/$ln: Z2 letter 108.0 px", 108.0f, textWidth(2), 0.5f)
                        assertEquals("$id/$ln: ⏎ 162.0 px (== ⇧)", 162.0f, widthById(2, "enter"), 0.5f)
                    }
                }
                assertEquals("$id/$ln: space 540.0 px", 540.0f, widthById(3, "space"), 0.5f)
                assertEquals("$id/$ln: mic 166.2 px", 166.2f, widthById(3, "mic"), 0.5f)
            }
            for (ln in symbolLayers) {
                val model = KeyboardModel(asset(id).layers.getValue(ln), 1080f, 100f, 0f, 0f, 0f)
                val rowGroups = model.keys.groupBy { it.top }.values.toList()
                // Z1 Σ 11.5 → unit 93.91 px: characters 93.91 px, ⌫ 93.91 px.
                assertEquals("$id/$ln: symbol Z1 character 93.91 px", 93.91f, rowGroups[1].first { it.kind == "text" }.let { it.right - it.left }, 0.5f)
                assertEquals("$id/$ln: symbol Z1 ⌫ 93.91 px (== character, V0.1.51)", 93.91f, rowGroups[1].single { it.id == "backspace" }.let { it.right - it.left }, 0.5f)
            }
        }
    }

    // ---------------------------------------------------------------- 1g

    /**
     * Character inventory snapshot: sorted(values ∪ labels of text keys ∪
     * alternates) per asset+layer. The snapshot file was generated ONCE from
     * the pre-migration assets (commit 087c834) and is now READ-ONLY evidence:
     * a missing file is a hard failure, never re-created — otherwise a later
     * migration could silently bless its own damage (review V0149 P2-4).
     */
    @Test
    fun characterInventoryUnchangedPerAsset() {
        val snapshotFile = File("src/test/resources/geometry_v0149_inventory.txt")
        if (!snapshotFile.exists()) {
            fail(
                "geometry_v0149_inventory.txt is missing — it is committed evidence " +
                    "from the pre-migration assets (087c834) and must never be re-created " +
                    "from the current tree.",
            )
        }
        val expected = snapshotFile.readLines()
            .filter { it.isNotBlank() }
            .associate { line -> line.split("\t", limit = 3).let { it[0] + "\t" + it[1] to it[2] } }
        assertEquals("snapshot covers all 15 assets × 4 layers", allAssets.size * allLayers.size, expected.size)
        for (id in allAssets) {
            for (ln in allLayers) {
                val actual = inventory(asset(id).layers.getValue(ln)).joinToString("\u0001")
                assertEquals(
                    "$id/$ln: character inventory (values ∪ labels ∪ alternates) unchanged",
                    expected.getValue("$id\t$ln"), actual,
                )
            }
        }
    }

    // ---------------------------------------------------------------- 1h

    /**
     * V0.1.50 user correction: in the ORIGINAL the M of the third letter row
     * sits at the i|o GAP of the first row — its CENTER on the i|o boundary
     * (8.0 units = 864 px at W=1080), not centered under the i. Measured on
     * the REAL KeyboardModel result (no unit arithmetic).
     *
     * 13 standard assets only: cs-cz carries a 6-letter Z2 by design (its
     * M center is 7.5u = 810 px, table row "Z2 unverändert"), ru-ru has no
     * i|o row at all (13-key Z0, Cyrillic) — both are their own table rows.
     */
    @Test
    fun mCenteredUnderTheIAndOGap() {
        for (id in stdAssets) {
            for (ln in letterLayers) {
                val model = KeyboardModel(asset(id).layers.getValue(ln), 1080f, 100f, 0f, 0f, 0f)
                val rowGroups = model.keys.groupBy { it.top }.values.toList()
                val z0 = rowGroups[0]
                assertEquals("$id/$ln: Z0 has 10 keys", 10, z0.size)
                // The i|o gap: seam between the 8th and the 9th Z0 key.
                val ioGap = z0[7].right
                assertEquals("$id/$ln: 9th Z0 key starts at the i|o gap", ioGap, z0[8].left, 0.5f)
                assertEquals("$id/$ln: i|o gap at 864 px (8.0u × 108)", 864f, ioGap, 0.5f)
                // The M key is the LAST TEXT key of Z2 (m/μ).
                val m = rowGroups[2].last { it.kind == "text" }
                assertEquals(
                    "$id/$ln: M center sits ON the i|o gap (V0.1.50)",
                    ioGap, m.centerX, 0.5f,
                )
                assertEquals("$id/$ln: M center at 864 px", 864f, m.centerX, 0.5f)
            }
        }
    }
}
