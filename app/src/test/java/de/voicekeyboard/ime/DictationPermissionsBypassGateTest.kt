package de.voicekeyboard.ime

import android.content.Context
import android.content.pm.ApplicationInfo
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * T21a (v0.1.45, measurement bridge, PLAN_V0145 §T21a): the Android thin layer
 * [bypassNotifGate] — debug build ONLY (FLAG_DEBUGGABLE, same seam as the T9
 * status band) AND the operator switch in Settings.Global
 * "vk_skip_notif_gate" == 1 (settable on the device via
 * `adb shell settings put global vk_skip_notif_gate 1`).
 * Release is NEVER bypassed — that is pinned by the pure matrix in
 * [DictationPermissionsTest].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35]) // project pattern: SDK 36 needs Java 21, the test JVM is 17
class DictationPermissionsBypassGateTest {

    private val app: Context get() = ApplicationProvider.getApplicationContext()

    /** Same seam as T9: FLAG_DEBUGGABLE via applicationInfo.flags. */
    private fun setDebuggable(on: Boolean) {
        val info: ApplicationInfo = (app as android.app.Application).applicationInfo
        if (on) {
            info.flags = info.flags or ApplicationInfo.FLAG_DEBUGGABLE
        } else {
            info.flags = info.flags and ApplicationInfo.FLAG_DEBUGGABLE.inv()
        }
    }

    private fun putSetting(value: Int) {
        Settings.Global.putInt(app.contentResolver, "vk_skip_notif_gate", value)
    }

    @Test
    fun `T21a release - never bypassed, even with the operator switch on`() {
        setDebuggable(false)
        putSetting(1)
        assertFalse("release + setting 1 = no bypass", bypassNotifGate(app))
    }

    @Test
    fun `T21a debug - bypass only with setting exactly 1`() {
        setDebuggable(true)
        putSetting(1)
        assertTrue("debug + setting 1 = bypass", bypassNotifGate(app))

        putSetting(0)
        assertFalse("debug + setting 0 (default) = no bypass", bypassNotifGate(app))

        putSetting(2)
        assertFalse("debug + setting 2 = no bypass", bypassNotifGate(app))
    }

    @Test
    fun `T21a debug - no setting written at all (default 0) = no bypass`() {
        setDebuggable(true)
        // fresh resolver state: the switch is simply not set -> getInt default 0
        Settings.Global.putInt(app.contentResolver, "vk_skip_notif_gate", 0)
        assertFalse(bypassNotifGate(RuntimeEnvironment.getApplication()))
    }

    // T21a measurement bridge, part 2: the SILENT guard bypass (same operator
    // switch) — arms the coordinator's ERR_SILENT watchdog bypass on the
    // debug build so a dead mic source does not abort the dictation app-side.
    @Test
    fun `T21a release - silent guard never bypassed, even with the switch on`() {
        setDebuggable(false)
        putSetting(1)
        assertFalse("release + setting 1 = no silent bypass", bypassSilentGuard(app))
    }

    @Test
    fun `T21a debug - silent guard bypass only with setting exactly 1`() {
        setDebuggable(true)
        putSetting(1)
        assertTrue("debug + setting 1 = silent bypass", bypassSilentGuard(app))

        putSetting(0)
        assertFalse("debug + setting 0 = no silent bypass", bypassSilentGuard(app))

        putSetting(2)
        assertFalse("debug + setting 2 = no silent bypass", bypassSilentGuard(app))
    }
}
