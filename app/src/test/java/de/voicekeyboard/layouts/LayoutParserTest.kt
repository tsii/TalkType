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

/** Lädt die echten Assets und validiert sie gegen den Parser. */
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
    fun `gueltiges de-de Asset parst und liefert alle Ebenen`() {
        val parsed = LayoutParser.parse(asset("de-de.json"))
        assertEquals("de-de", parsed.id)
        assertEquals(1, parsed.schemaVersion)
        assertEquals(setOf("base", "shift", "symbols", "symbolsShift"), parsed.layers.keys)
        assertEquals("ltr", parsed.language.direction)
        assertEquals("de-DE", parsed.language.tag)
    }

    @Test
    fun `gueltiges en-us Asset parst`() {
        val parsed = LayoutParser.parse(asset("en-us.json"))
        assertEquals("en-us", parsed.id)
    }

    @Test
    fun `alle 15 Sprach-Assets parst`() {
        val existing = existingAssets()
        assertTrue("Assets im Verzeichnis: $existing", expectedAssets.all { it in existing })
        for (name in expectedAssets) {
            val parsed = try {
                LayoutParser.parse(asset("$name.json"))
            } catch (e: LayoutParser.LayoutException) {
                fail("Asset '$name' parst nicht: ${e.message}")
                return
            }
            assertEquals(name, parsed.id)
        }
    }

    @Test
    fun `ungueltiges JSON wirft LayoutException mit klarer Meldung`() {
        try {
            LayoutParser.parse("{ kein json")
            fail("LayoutException erwartet")
        } catch (e: LayoutParser.LayoutException) {
            assertTrue(e.message!!.contains("Kein gültiges JSON"))
        }
    }

    @Test
    fun `unbekannte Aktion wird abgelehnt`() {
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
            fail("LayoutException erwartet")
        } catch (e: LayoutParser.LayoutException) {
            assertTrue(e.message!!.contains("unbekannte Aktion"))
        }
    }

    @Test
    fun `fehlende Shift-Ebene wird abgelehnt`() {
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
            fail("LayoutException erwartet")
        } catch (e: LayoutParser.LayoutException) {
            assertTrue("Meldung: ${e.message}", e.message!!.contains("'shift' fehlt"))
        }
    }

    @Test
    fun `unbekannte schemaVersion wird abgelehnt`() {
        val json = """{"schemaVersion": 2, "id": "xx-xx"}"""
        try {
            LayoutParser.parse(json)
            fail("LayoutException erwartet")
        } catch (e: LayoutParser.LayoutException) {
            assertTrue(e.message!!.contains("Unbekannte schemaVersion"))
        }
    }

    @Test
    fun `abweichende Zeilensumme zwischen Ebenen wird abgelehnt`() {
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
            fail("LayoutException erwartet")
        } catch (e: LayoutParser.LayoutException) {
            assertTrue("Meldung: ${e.message}", e.message!!.contains("Zeilensumme"))
        }
    }

    @Test
    fun `doppelte Tasten-ID in einer Ebene wird abgelehnt`() {
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
            fail("LayoutException erwartet")
        } catch (e: LayoutParser.LayoutException) {
            assertTrue(e.message!!.contains("Doppelte Tasten-ID"))
        }
    }

    // ---- M9a: availableIds-Scan + SpaceLabelRule ----

    @Test
    fun `M9a availableIds scannt alle 15 kanonischen ids ohne json-endung`() {
        val context = RuntimeEnvironment.getApplication() as Application
        val repo = LayoutRepository(context)
        val ids = repo.availableIds()
        val canonical = listOf(
            "de-de", "en-us", "fr-fr", "es-es", "it-it", "pt-pt", "nl-nl", "pl-pl",
            "tr-tr", "ru-ru", "cs-cz", "sv-se", "da-dk", "fi-fi", "el-gr",
        )
        assertEquals(15, ids.size)
        assertEquals(canonical, ids) // kanonische Reihenfolge, keine Verzeichnisse, keine Endung
        assertTrue(ids.none { it.endsWith(".json") })
    }

    @Test
    fun `M9a label-regel espacio bleibt, blank erhaelt fallback, value bleibt space`() {
        // Asset-Label mit Nicht-Whitespace ⇒ unverändert uebernehmen
        assertEquals("Espacio", SpaceLabelRule.resolve("Espacio", "Space"))
        assertEquals("Mellemrum", SpaceLabelRule.resolve("Mellemrum", "Space"))
        // Blank-Label (da-dk/fi-fi symbols) ⇒ Fallback
        assertEquals("Space", SpaceLabelRule.resolve(" ", "Space"))
        assertEquals("Space", SpaceLabelRule.resolve("", "Space"))
        // Einfuege-Wert bleibt unangetastet: die Assets committen value=="space"
        val de = LayoutParser.parse(asset("de-de.json"))
        val spaceKey = de.layers.values.flatMap { it.rows.flatten() }.first { it.value == "space" }
        assertEquals("space", spaceKey.value)
        assertEquals("Leer", SpaceLabelRule.resolve(spaceKey.label, "Space"))
    }

    // ---- M9b: E11 Space-Labels (Tabelle hinterlegt, nicht aus Assets abgelesen) ----

    private val e11Labels = mapOf(
        "de-de" to "Leer", "en-us" to "Space", "fr-fr" to "Espace", "es-es" to "Espacio",
        "it-it" to "Spazio", "pt-pt" to "Espaço", "nl-nl" to "Spatie", "pl-pl" to "Spacja",
        "tr-tr" to "Boşluk", "ru-ru" to "Пробел", "cs-cz" to "Mezera", "sv-se" to "Mellanslag",
        "da-dk" to "Mellemrum", "fi-fi" to "Väli", "el-gr" to "Διάστημα",
    )

    @Test
    fun `M9b alle 15 assets haben E11-label auf space in allen ebenen`() {
        for ((id, expected) in e11Labels) {
            val parsed = LayoutParser.parse(asset("$id.json"))
            for ((layerName, layer) in parsed.layers) {
                val spaceKeys = layer.rows.flatten().filter { it.value == "space" }
                assertEquals("Asset $id Ebene $layerName: genau 1 Space-Taste", 1, spaceKeys.size)
                val key = spaceKeys[0]
                assertEquals("Asset $id Ebene $layerName Label", expected, key.label)
                assertEquals("Asset $id Ebene $layerName value", "space", key.value)
            }
        }
    }

    @Test
    fun `M9b gegenprobe falsches space-label wird abgelehnt`() {
        val parsed = LayoutParser.parse(asset("ru-ru.json"))
        // Gegenprobe 1: abweichendes Label ("Leer" statt "Пробел") muss die Pruefung scheitern lassen
        for ((layerName, layer) in parsed.layers) {
            val spaceKey = layer.rows.flatten().first { it.value == "space" }
            try {
                org.junit.Assert.assertFalse("Gegenprobe: Label-Differenz haette auffallen muessen",
                    spaceKey.label == e11Labels.getValue("ru-ru"))
                fail("Gegenprobe $layerName: abweichendes Label wurde nicht erkannt")
            } catch (expected: AssertionError) {
                // richtig: die Assertion ist false -> fail() wirft
            }
            assertEquals("Пробел", spaceKey.label)
        }
    }

    // ---- F23a1: optionales hint-Feld (docs/DESIGN_DEDE_ZIELBILD.md §1/§4) ----
    // Regel: ein kaputtes hint darf NIE die Tastatur killen — fehlend/leer/zu
    // lang (> 2 Zeichen) -> null, die uebrigen Felder der Taste bleiben intakt.

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

    @Test fun `a) Taste ohne hint-Feld liefert null`() {
        org.junit.Assert.assertNull(hintOf("").hint)
    }

    @Test fun `b) hint mit einer Ziffer wird gelesen`() {
        assertEquals("1", hintOf(", \"hint\": \"1\"").hint)
    }

    @Test fun `c) leerer hint wird zu null, nicht zum leeren String`() {
        org.junit.Assert.assertNull(hintOf(", \"hint\": \"\"").hint)
    }

    @Test fun `d) zu langer hint wird verworfen, uebrige Felder bleiben intakt`() {
        val key = hintOf(", \"hint\": \"abc\"")
        org.junit.Assert.assertNull("hint mit > 2 Zeichen wird verworfen", key.hint)
        // der wichtige Teil: die Taste selbst ist unversehrt geladen
        assertEquals("q", key.id)
        assertEquals("q", key.label)
        assertEquals("q", key.value)
        assertEquals(1.0, key.width, 0.0)
        assertEquals(listOf("x", "y"), key.alternates)
    }

    // ---- F24e1: optionales cellActions-Feld (Aktionszellen im Langdruck-Menue) ----

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

    @Test fun `F24e1 a) fehlende cellActions liefern emptyList`() {
        assertEquals(emptyList<String>(), cellActionsOf("\",\", \"!\", \"?\"", null))
    }

    @Test fun `F24e1 b) funf cellActions an Taste mit funf alternates bleiben 5, leerstring bleibt`() {
        val got = cellActionsOf(
            "\",\", \"!\", \"?\", \"‹\", \"›\"",
            "\"\", \"\", \"\", \"cursor-left\", \"cursor-right\"",
        )
        assertEquals(5, got.size)
        assertEquals(listOf("", "", "", "cursor-left", "cursor-right"), got)
    }

    // ---- F27a1: MARGIN-Randflaechen (Stock-Geometrie) ----

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

    @Test fun `F27a1 a) margin-Key wird geparst (kind MARGIN, width durchgereicht)`() {
        val key = LayoutParser.parse(
            marginAsset("""{"id": "margin-left", "kind": "margin", "width": 1.5}"""),
        ).layers.getValue("base").rows[0][0]
        assertEquals(KeyKind.MARGIN, key.kind)
        assertEquals(1.5, key.width, 0.0)
        assertEquals("", key.value)
        assertEquals("", key.label)
        assertTrue(key.alternates.isEmpty())
    }

    @Test fun `F27a1 b) margin mit value wird abgelehnt`() {
        val json = marginAsset("""{"id": "margin-left", "kind": "margin", "width": 1, "value": "x"}""")
        var caught = false
        try {
            LayoutParser.parse(json)
        } catch (e: LayoutParser.LayoutException) {
            caught = true
        }
        assertTrue("margin mit value muss abgelehnt werden", caught)
    }
}
