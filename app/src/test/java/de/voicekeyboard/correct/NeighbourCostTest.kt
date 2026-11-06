package de.voicekeyboard.correct

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T23c (docs/PLAN_V0147.md §14.3): neighbour costs from the layout key rects.
 * Row pitch = 1.5x column pitch: same-row neighbours are 'benachbart' (0.7),
 * vertical q-a is 'weit' (1.0), same-key alternates 0.4 (u/ü), unreachable
 * chars flat 1.0. Loaded models are deterministic.
 */
class NeighbourCostTest {

    @Test fun qwertzAdjacentKeysAreCheap() {
        val de = NeighbourModel.load("de-de")
        assertEquals(0.7, de.cost('z', 'u'), 1e-9) // QWERTZ row 0 neighbours
        assertEquals(0.7, de.cost('m', 'n'), 1e-9) // row 2 neighbours
        assertEquals(0.7, de.cost('s', 'd'), 1e-9) // row 1 neighbours
    }

    @Test fun verticalQaIsFar() {
        val de = NeighbourModel.load("de-de")
        assertEquals(1.0, de.cost('q', 'a'), 1e-9)
    }

    @Test fun qwertyYAndUAreNeighbours() {
        val en = NeighbourModel.load("en-us")
        assertEquals(0.7, en.cost('y', 'u'), 1e-9)
        assertEquals(1.0, en.cost('z', 'y'), 1e-9) // z is on the bottom row
    }

    @Test fun alternatesShareTheHostKey() {
        val de = NeighbourModel.load("de-de")
        assertEquals(0.4, de.cost('u', 'ü'), 1e-9)
        assertEquals(0.4, de.cost('a', 'ä'), 1e-9)
        assertEquals(0.4, de.cost('o', 'ö'), 1e-9)
        assertEquals(0.4, de.cost('s', 'ß'), 1e-9)
    }

    @Test fun unreachableCharsCostFlatSubstitution() {
        val de = NeighbourModel.load("de-de")
        assertEquals(1.0, de.cost('a', 'æ'), 1e-9)
        assertEquals(0.0, de.cost('a', 'a'), 1e-9)
    }

    @Test fun loadedModelsAreDeterministic() {
        val json = resolveLayoutJson("de-de").readText()
        val a = NeighbourModel.parse("de-de", json)
        val b = NeighbourModel.parse("de-de", json)
        assertEquals(a.reachableChars(), b.reachableChars())
        for (x in a.reachableChars()) {
            for (y in a.reachableChars()) {
                assertEquals("$x/$y", a.cost(x, y), b.cost(x, y), 0.0)
            }
        }
        assertTrue(a.reachableChars().containsAll(listOf('q', 'a', 'z', 'ü', 'ä', 'ö', 'ß', '.')))
    }

    private fun resolveLayoutJson(id: String): java.io.File {
        var base = java.io.File(System.getProperty("user.dir") ?: ".")
        repeat(6) {
            for (rel in listOf("src/main/assets/layouts", "app/src/main/assets/layouts")) {
                val f = java.io.File(base, "$rel/$id.json")
                if (f.isFile) return f
            }
            base = base.parentFile ?: return@repeat
        }
        throw IllegalStateException("assets/layouts/$id.json not found")
    }
}
