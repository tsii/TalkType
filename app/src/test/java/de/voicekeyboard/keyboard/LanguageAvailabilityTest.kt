package de.voicekeyboard.keyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * T23m (PLAN_V0147 §16): pure language availability — filter, fallback,
 * firstAvailable + the dynamic relation of AVAILABLE to the 15 real layout
 * assets (walk-up resolver, no Robolectric needed).
 */
class LanguageAvailabilityTest {

    // ------------------------------------------------------------ the set

    @Test fun `available set is exactly de-de and en-us`() {
        assertEquals(setOf("de-de", "en-us"), LanguageAvailability.AVAILABLE)
    }

    @Test fun `isAvailable matrix`() {
        assertTrue(LanguageAvailability.isAvailable("de-de"))
        assertTrue(LanguageAvailability.isAvailable("en-us"))
        assertFalse(LanguageAvailability.isAvailable("fr-fr"))
        assertFalse(LanguageAvailability.isAvailable("pl-pl"))
        assertFalse(LanguageAvailability.isAvailable(""))
        assertFalse(LanguageAvailability.isAvailable("de"))
        assertFalse(LanguageAvailability.isAvailable("unknown-xx"))
    }

    // ------------------------------------------------------------ filtering

    @Test fun `filterActive keeps only available languages in input order`() {
        assertEquals(
            listOf("de-de", "en-us"),
            LanguageAvailability.filterActive(listOf("de-de", "fr-fr", "en-us")),
        )
        assertEquals(
            listOf("en-us"),
            LanguageAvailability.filterActive(listOf("en-us", "pl-pl")),
        )
    }

    @Test fun `filterActive falls back to the available order when nothing matches`() {
        assertEquals(
            listOf("de-de", "en-us"),
            LanguageAvailability.filterActive(listOf("fr-fr", "pl-pl", "ru-ru")),
        )
        assertEquals(listOf("de-de", "en-us"), LanguageAvailability.filterActive(emptyList()))
    }

    @Test fun `firstAvailable follows the actives then falls back to de-de`() {
        assertEquals("de-de", LanguageAvailability.firstAvailable(listOf("fr-fr", "de-de")))
        assertEquals("en-us", LanguageAvailability.firstAvailable(listOf("en-us", "de-de")))
        assertEquals("de-de", LanguageAvailability.firstAvailable(listOf("fr-fr")))
        assertEquals("de-de", LanguageAvailability.firstAvailable(emptyList()))
    }

    // ------------------------------------------------- relation to the assets

    /**
     * AVAILABLE must be a SUBSET of the real layout assets (15 files — one per
     * language; §16: no available language without a shipped layout).
     */
    @Test fun `available ids exist among the 15 real layout assets`() {
        val layoutDir = resolveLayoutDir()
        val ids = layoutDir.listFiles { f -> f.isFile && f.name.endsWith(".json") }!!
            .map { it.name.removeSuffix(".json") }
        assertEquals("15 layouts shipped", 15, ids.size)
        assertTrue(
            "AVAILABLE must exist among the layouts: missing " +
                (LanguageAvailability.AVAILABLE - ids.toSet()),
            LanguageAvailability.AVAILABLE.all { it in ids },
        )
    }

    private fun resolveLayoutDir(): File {
        var base = File(System.getProperty("user.dir") ?: ".")
        repeat(6) {
            for (rel in listOf("src/main/assets/layouts", "app/src/main/assets/layouts")) {
                val f = File(base, rel)
                if (f.isDirectory) return f
            }
            base = base.parentFile ?: return@repeat
        }
        throw IllegalStateException("assets/layouts not found")
    }
}
