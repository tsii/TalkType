package de.voicekeyboard.ime

import android.content.Context
import android.view.View
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import de.voicekeyboard.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * T19 (v0.1.45): the Setup header shows the app version, read via the ONE
 * shared path [AppVersion.read] (also used by DebugInfoCollector — no second
 * read path). Proves: the setup_version view exists and is VISIBLE, and its
 * text is EXACTLY `versionLabel(read(context))` with the versionName/versionCode
 * of the test package (real PackageManager), plus the debug variant through
 * the injectable seam (T9-style FLAG_DEBUGGABLE read).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35]) // project pattern: SDK 36 needs Java 21, the test JVM is 17
class SetupActivityVersionTest {

    @Test
    fun `T19 setup_version VISIBLE and text equals the shared reader label`() {
        val controller = Robolectric.buildActivity(SetupActivity::class.java)
        controller.get().debugBuildOverride = false
        controller.setup()

        val versionView = controller.get().findViewById<TextView>(R.id.setup_version)
        assertEquals("setup_version VISIBLE", View.VISIBLE, versionView.visibility)

        val context = ApplicationProvider.getApplicationContext<Context>()
        val text = versionView.text.toString()
        val expected = AppVersion.versionLabel(AppVersion.read(context), debug = false)
        assertEquals("label from the ONE shared read path", expected, text)
        assertTrue("TalkType prefix", text.startsWith("TalkType"))

        // versionName/code of the TEST package (real PackageManager under Robolectric)
        val pkg = context.packageManager.getPackageInfo(context.packageName, 0)
        if (!pkg.versionName.isNullOrEmpty()) {
            assertTrue(
                "text contains the versionName of the test package",
                text.contains(pkg.versionName!!),
            )
        }
    }

    @Test
    fun `T19 injectable debug seam - debug flag appends the debug suffix`() {
        val controller = Robolectric.buildActivity(SetupActivity::class.java)
        controller.get().debugBuildOverride = true
        controller.setup()

        val versionView = controller.get().findViewById<TextView>(R.id.setup_version)
        val context = ApplicationProvider.getApplicationContext<Context>()
        val text = versionView.text.toString()
        assertEquals(
            "debug label via the seam",
            AppVersion.versionLabel(AppVersion.read(context), debug = true),
            text,
        )
        assertTrue("debug suffix", text.endsWith(" · debug"))
    }
}
