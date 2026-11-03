package de.voicekeyboard.layouts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import de.voicekeyboard.layouts.KeyKind
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import android.app.Application
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Loads the real assets and validates them against the parser. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LayoutParserTest {

    private fun asset(name: String): String {
        val context = RuntimeEnvironment.getApplication() as Application
        return context.assets.open("layouts/$name").bufferedReader().use { it.readText() }
    }

    private val expectedAssets = listOf(
        "cs-cz", "da-dk", "de-de", "el-gr", "en-us", "es-es", "fi-fi", "fr-fr",
        "it-it", "nl-nl", "pl-pl", "pt-pt", "ru-ru", "sv-se", "tr-tr",
    )

    private fun existingAssets(): List<String> {
        val context = RuntimeEnvironment.getApplication() as Application
        return context.assets.list("layouts")!!.filter { it.endsWith(".json") }
            .map { it.removeSuffix(".json") }
    }

    @Test
    fun `a valid de-de asset parses and yields all layers`() {
        val parsed = LayoutParser.parse(asset("de-de.json"))
        assertEquals("de-de", parsed.id)
        assertEquals(1, parsed.schemaVersion)
        assertEquals(setOf("base", "shift", "symbols", "symbolsShift"), parsed.layers.keys)
        assertEquals("ltr", parsed.language.direction)
        assertEquals("de-DE", parsed.language.tag)
    }

    @Test
    fun `a valid en-us asset parses`() {
        val parsed = LayoutParser.parse(asset("en-us.json"))
        assertEquals("en-us", parsed.id)
    }

    @Test
    fun `all 15 language assets parse`() {
        val existing = existingAssets()
        assertTrue("assets in the directory: $existing", expectedAssets.all { it in existing })
        for (name in expectedAssets) {
            val parsed = try {
                LayoutParser.parse(asset("$name.json"))
            } catch (e: LayoutParser.LayoutException) {
                fail("asset '$name' does not parse: ${e.message}")
                return
            }
            assertEquals(name, parsed.id)
        }
    }

    @Test
    fun `invalid JSON throws LayoutException with a clear message`() {
        try {
            LayoutParser.parse("{ not json")
            fail("LayoutException expected")
        } catch (e: LayoutParser.LayoutException) {
            assertTrue(e.message!!.contains("Not valid JSON"))
        }
    }

    @Test
    fun `an unknown action is rejected`() {
        val json = """
        {
          "schemaVersion": 1, "id": "xx-xx",
          "language": {"tag": "xx-XX", "label": "X", "caseLocale": "xx-XX", "direction": "ltr"},
          "deadKeys": {},
          "layers": {
            "base": {"rows": [[
              {"id": "a", "label": "a", "width": 1, "kind": "text", "value": "a"},
              {"id": "k", "label": "K", "width": 1, "kind": "action", "value": "explodieren"}
            ]]},
            "shift": {"rows": [[
              {"id": "a", "label": "A", "width": 2, "kind": "text", "value": "A"}
            ]]},
            "symbols": {"rows": [[
              {"id": "s1", "label": "1", "width": 2, "kind": "text", "value": "1"}
            ]]},
            "symbolsShift": {"rows": [[
              {"id": "s2", "label": "2", "width": 2, "kind": "text", "value": "2"}
            ]]}
          }
        }
        """.trimIndent()
        try {
            LayoutParser.parse(json)
            fail("LayoutException expected")
        } catch (e: LayoutParser.LayoutException) {
            assertTrue(e.message!!.contains("unknown action"))
        }
    }

    @Test
    fun `a missing shift layer is rejected`() {
        val json = """
        {
          "schemaVersion": 1, "id": "xx-xx",
          "language": {"tag": "xx-XX", "label": "X", "caseLocale": "xx-XX", "direction": "ltr"},
          "deadKeys": {},
          "layers": {
            "base": {"rows": [[{"id": "a", "label": "a", "width": 1, "kind": "text", "value": "a"}]]},
            "symbols": {"rows": [[{"id": "1", "label": "1", "width": 1, "kind": "text", "value": "1"}]]},
            "symbolsShift": {"rows": [[{"id": "2", "label": "2", "width": 1, "kind": "text", "value": "2"}]]}
          }
        }
        """.trimIndent()
        try {
            LayoutParser.parse(json)
            fail("LayoutException expected")
        } catch (e: LayoutParser.LayoutException) {
            assertTrue("message: ${e.message}", e.message!!.contains("'shift' missing"))
        }
    }

    @Test
    fun `an unknown schemaVersion is rejected`() {
        val json = """{"schemaVersion": 2, "id": "xx-xx"}"""
        try {
            LayoutParser.parse(json)
            fail("LayoutException expected")
        } catch (e: LayoutParser.LayoutException) {
            assertTrue(e.message!!.contains("Unknown schemaVersion"))
        }
    }

    @Test
    fun `a deviating row sum between layers is rejected`() {
        val json = """
        {
          "schemaVersion": 1, "id": "xx-xx",
          "language": {"tag": "xx-XX", "label": "X", "caseLocale": "xx-XX", "direction": "ltr"},
          "deadKeys": {},
          "layers": {
            "base": {"rows": [[{"id": "a", "label": "a", "width": 1, "kind": "text", "value": "a"},
                               {"id": "b", "label": "b", "width": 1, "kind": "text", "value": "b"}]]},
            "shift": {"rows": [[{"id": "a", "label": "A", "width": 1.5, "kind": "text", "value": "A"},
                                {"id": "b", "label": "B", "width": 1, "kind": "text", "value": "B"}]]},
            "symbols": {"rows": [[{"id": "1", "label": "1", "width": 2, "kind": "text", "value": "1"}]]},
            "symbolsShift": {"rows": [[{"id": "2", "label": "2", "width": 2, "kind": "text", "value": "2"}]]}
          }
        }
        """.trimIndent()
        try {
            LayoutParser.parse(json)
            fail("LayoutException expected")
        } catch (e: LayoutParser.LayoutException) {
            assertTrue("message: ${e.message}", e.message!!.contains("Row-sum deviation"))
        }
    }

    @Test
    fun `a duplicate key ID within a layer is rejected`() {
        val json = """
        {
          "schemaVersion": 1, "id": "xx-xx",
          "language": {"tag": "xx-XX", "label": "X", "caseLocale": "xx-XX", "direction": "ltr"},
          "deadKeys": {},
          "layers": {
            "base": {"rows": [[{"id": "a", "label": "a", "width": 1, "kind": "text", "value": "a"},
                               {"id": "a", "label": "b", "width": 1, "kind": "text", "value": "b"}]]},
            "shift": {"rows": [[{"id": "a", "label": "A", "width": 2, "kind": "text", "value": "A"}]]},
            "symbols": {"rows": [[{"id": "1", "label": "1", "width": 2, "kind": "text", "value": "1"}]]},
            "symbolsShift": {"rows": [[{"id": "2", "label": "2", "width": 2, "kind": "text", "value": "2"}]]}
          }
        }
        """.trimIndent()
        try {
            LayoutParser.parse(json)
            fail("LayoutException expected")
        } catch (e: LayoutParser.LayoutException) {
            assertTrue(e.message!!.contains("Duplicate key ID"))
        }
    }

    // ---- M9a: availableIds scan + SpaceLabelRule ----

    @Test
    fun `M9a availableIds scans all 15 canonical ids without a json extension`() {
        val context = RuntimeEnvironment.getApplication() as Application
        val repo = LayoutRepository(context)
        val ids = repo.availableIds()
        val canonical = listOf(
            "de-de", "en-us", "fr-fr", "es-es", "it-it", "pt-pt", "nl-nl", "pl-pl",
            "tr-tr", "ru-ru", "cs-cz", "sv-se", "da-dk", "fi-fi", "el-gr",
        )
        assertEquals(15, ids.size)
        assertEquals(canonical, ids) // canonical order, no directories, no extension
        assertTrue(ids.none { it.endsWith(".json") })
    }

    @Test
    fun `M9a label rule espacio stays, blank gets a fallback, value stays space`() {
        // asset label with non-whitespace ⇒ take it over unchanged
        assertEquals("Espacio", SpaceLabelRule.resolve("Espacio", "Space"))
        assertEquals("Mellemrum", SpaceLabelRule.resolve("Mellemrum", "Space"))
        // blank label (da-dk/fi-fi symbols) ⇒ fallback
        assertEquals("Space", SpaceLabelRule.resolve(" ", "Space"))
        assertEquals("Space", SpaceLabelRule.resolve("", "Space"))
        // the insertion value stays untouched: the assets commit value=="space"
        val de = LayoutParser.parse(asset("de-de.json"))
        val spaceKey = de.layers.values.flatMap { it.rows.flatten() }.first { it.value == "space" }
        assertEquals("space", spaceKey.value)
        assertEquals("Leer", SpaceLabelRule.resolve(spaceKey.label, "Space"))
    }

    // ---- M9b: E11 space labels (table maintained, not read from the assets) ----

    private val e11Labels = mapOf(
        "de-de" to "Leer", "en-us" to "Space", "fr-fr" to "Espace", "es-es" to "Espacio",
        "it-it" to "Spazio", "pt-pt" to "Espaço", "nl-nl" to "Spatie", "pl-pl" to "Spacja",
        "tr-tr" to "Boşluk", "ru-ru" to "Пробел", "cs-cz" to "Mezera", "sv-se" to "Mellanslag",
        "da-dk" to "Mellemrum", "fi-fi" to "Väli", "el-gr" to "Διάστημα",
    )

    @Test
    fun `M9b all 15 assets carry the E11 label on space in all layers`() {
        for ((id, expected) in e11Labels) {
            val parsed = LayoutParser.parse(asset("$id.json"))
            for ((layerName, layer) in parsed.layers) {
                val spaceKeys = layer.rows.flatten().filter { it.value == "space" }
                assertEquals("asset $id layer $layerName: exactly 1 space key", 1, spaceKeys.size)
                val key = spaceKeys[0]
                assertEquals("asset $id layer $layerName label", expected, key.label)
                assertEquals("asset $id layer $layerName value", "space", key.value)
            }
        }
    }

    @Test
    fun `M9b counter-check a wrong space label is rejected`() {
        val parsed = LayoutParser.parse(asset("ru-ru.json"))
        // counter-check 1: a deviating label ("Leer" instead of "Пробел") must make the check fail
        for ((layerName, layer) in parsed.layers) {
            val spaceKey = layer.rows.flatten().first { it.value == "space" }
            try {
                org.junit.Assert.assertFalse("counter-check: the label difference should have been noticed",
                    spaceKey.label == e11Labels.getValue("ru-ru"))
                fail("counter-check $layerName: the deviating label was not detected")
            } catch (expected: AssertionError) {
                // correct: the assertion is false -> fail() throws
            }
            assertEquals("Пробел", spaceKey.label)
        }
    }

    // ---- F23a1: optional hint field (docs/DESIGN_DEDE_ZIELBILD.md §1/§4) ----
    // rule: a broken hint must NEVER kill the keyboard — missing/empty/too long
    // (> 2 characters) -> null, the key's remaining fields stay intact.

    private fun hintAsset(keyJson: String) = """
        {
          "schemaVersion": 1, "id": "xx-xx",
          "language": {"tag": "xx-XX", "label": "X", "caseLocale": "xx-XX", "direction": "ltr"},
          "deadKeys": {},
          "layers": {
            "base": {"rows": [[
              {"id": "q", "label": "q", "width": 1, "kind": "text", "value": "q", "alternates": ["x", "y"]$keyJson}
            ]]},
            "shift": {"rows": [[
              {"id": "q", "label": "Q", "width": 1, "kind": "text", "value": "Q"}
            ]]},
            "symbols": {"rows": [[
              {"id": "s1", "label": "1", "width": 1, "kind": "text", "value": "1"}
            ]]},
            "symbolsShift": {"rows": [[
              {"id": "s1", "label": "1", "width": 1, "kind": "text", "value": "1"}
            ]]}
          }
        }
        """.trimIndent()

    private fun hintOf(keyJson: String) =
        LayoutParser.parse(hintAsset(keyJson)).layers.getValue("base").rows[0][0]

    @Test fun `a) a key without a hint field yields null`() {
        org.junit.Assert.assertNull(hintOf("").hint)
    }

    @Test fun `b) a hint with a digit is read`() {
        assertEquals("1", hintOf(", \"hint\": \"1\"").hint)
    }

    @Test fun `c) an empty hint becomes null, not the empty string`() {
        org.junit.Assert.assertNull(hintOf(", \"hint\": \"\"").hint)
    }

    @Test fun `d) a too long hint is discarded, the remaining fields stay intact`() {
        val key = hintOf(", \"hint\": \"abc\"")
        org.junit.Assert.assertNull("a hint with > 2 characters is discarded", key.hint)
        // the important part: the key itself is loaded intact
        assertEquals("q", key.id)
        assertEquals("q", key.label)
        assertEquals("q", key.value)
        assertEquals(1.0, key.width, 0.0)
        assertEquals(listOf("x", "y"), key.alternates)
    }

    // ---- F24e1: optional cellActions field (action cells in the long-press menu) ----

    private fun cellActionsAsset(altJson: String, actionsJson: String?) = """
        {
          "schemaVersion": 1, "id": "xx-xx",
          "language": {"tag": "xx-XX", "label": "X", "caseLocale": "xx-XX", "direction": "ltr"},
          "deadKeys": {},
          "layers": {
            "base": {"rows": [[
              {"id": "p", "label": ".", "width": 1, "kind": "text", "value": ".",
               "alternates": [$altJson]${actionsJson?.let { ", \"cellActions\": [$it]" } ?: ""}}
            ]]},
            "shift": {"rows": [[
              {"id": "p", "label": ".", "width": 1, "kind": "text", "value": "."}
            ]]},
            "symbols": {"rows": [[
              {"id": "s1", "label": "1", "width": 1, "kind": "text", "value": "1"}
            ]]},
            "symbolsShift": {"rows": [[
              {"id": "s1", "label": "1", "width": 1, "kind": "text", "value": "1"}
            ]]}
          }
        }
        """.trimIndent()

    private fun cellActionsOf(altJson: String, actionsJson: String?) =
        LayoutParser.parse(cellActionsAsset(altJson, actionsJson)).layers.getValue("base").rows[0][0].cellActions

    @Test fun `F24e1 a) missing cellActions yield emptyList`() {
        assertEquals(emptyList<String>(), cellActionsOf("\",\", \"!\", \"?\"", null))
    }

    @Test fun `F24e1 b) five cellActions on a key with five alternates stay 5, empty string stays`() {
        val got = cellActionsOf(
            "\",\", \"!\", \"?\", \"‹\", \"›\"",
            "\"\", \"\", \"\", \"cursor-left\", \"cursor-right\"",
        )
        assertEquals(5, got.size)
        assertEquals(listOf("", "", "", "cursor-left", "cursor-right"), got)
    }

    // ---- F27a1: MARGIN margin surfaces (stock geometry) ----

    private fun marginAsset(marginJson: String) = """
        {
          "schemaVersion": 1, "id": "xx-xx",
          "language": {"tag": "xx-XX", "label": "X", "caseLocale": "xx-XX", "direction": "ltr"},
          "deadKeys": {},
          "layers": {
            "base": {"rows": [[
              $marginJson,
              {"id": "q", "label": "q", "width": 1, "kind": "text", "value": "q"}
            ]]},
            "shift": {"rows": [[
              $marginJson,
              {"id": "q", "label": "Q", "width": 1, "kind": "text", "value": "Q"}
            ]]},
            "symbols": {"rows": [[
              {"id": "s1", "label": "1", "width": 2, "kind": "text", "value": "1"}
            ]]},
            "symbolsShift": {"rows": [[
              {"id": "s1", "label": "1", "width": 2, "kind": "text", "value": "1"}
            ]]}
          }
        }
        """.trimIndent()

    @Test fun `F27a1 a) a margin key is parsed (kind MARGIN, width passed through)`() {
        val key = LayoutParser.parse(
            marginAsset("""{"id": "margin-left", "kind": "margin", "width": 1.5}"""),
        ).layers.getValue("base").rows[0][0]
        assertEquals(KeyKind.MARGIN, key.kind)
        assertEquals(1.5, key.width, 0.0)
        assertEquals("", key.value)
        assertEquals("", key.label)
        assertTrue(key.alternates.isEmpty())
    }

    @Test fun `F27a1 b) a margin with a value is rejected`() {
        val json = marginAsset("""{"id": "margin-left", "kind": "margin", "width": 1, "value": "x"}""")
        var caught = false
        try {
            LayoutParser.parse(json)
        } catch (e: LayoutParser.LayoutException) {
            caught = true
        }
        assertTrue("a margin with a value must be rejected", caught)
    }
}
