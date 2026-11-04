package de.voicekeyboard.ime

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * T19 (v0.1.45): pure label formatting of the ONE version text the user sees
 * in the Setup header. The reader [AppVersion.read] (real PackageManager of
 * the test package) is covered by the Robolectric SetupActivity tests
 * ([SetupActivityVersionTest]); here only the pure function is under test.
 */
class AppVersionTest {

    @Test
    fun `release label - TalkType name (code) without debug suffix`() {
        assertEquals(
            "TalkType 0.1.45 (46)",
            AppVersion.versionLabel(AppVersion.Info("0.1.45", 46), debug = false),
        )
    }

    @Test
    fun `debug label - suffix appended after the release label`() {
        assertEquals(
            "TalkType 0.1.45 (46) · debug",
            AppVersion.versionLabel(AppVersion.Info("0.1.45", 46), debug = true),
        )
    }

    @Test
    fun `null version name - placeholder question mark, code still shown`() {
        assertEquals(
            "TalkType ? (46)",
            AppVersion.versionLabel(AppVersion.Info(null, 46), debug = false),
        )
    }

    @Test
    fun `empty version name - placeholder question mark, code still shown`() {
        assertEquals(
            "TalkType ? (46)",
            AppVersion.versionLabel(AppVersion.Info("", 46), debug = false),
        )
    }
}
