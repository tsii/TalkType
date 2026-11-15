package de.voicekeyboard.ime

import android.Manifest
import android.app.Application
import android.content.Context
import android.view.View
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import de.voicekeyboard.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowToast

/**
 * V0.1.51 (docs/PLAN_V0151.md §C): the "Grant permissions" button reacts in
 * EVERY state — user finding: requestPermissionsNow silently returned when
 * nothing was missing, the button looked dead.
 *
 * State matrix (Robolectric, no device needed — the permission state is set
 * via the ShadowApplication grant/deny seams, the launched system dialog via
 * the recorded start intent):
 *
 *  - something missing        → click starts the SYSTEM dialog
 *    (android.content.pm.action.REQUEST_PERMISSIONS), no toast;
 *  - nothing missing          → click shows the "All permissions granted"
 *    toast, NO dialog; hint line = setup_permissions_hint_ok;
 *  - denied forever (marker)  → setup_open_settings VISIBLE + hint line
 *    setup_permissions_denied_forever; the permission is still missing, so
 *    the click keeps starting the system dialog.
 *
 * The decision itself is the pure [PermissionAction] (same file as
 * SetupActivity) — pinned first, the UI tests below only prove the wiring.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35]) // project pattern: SDK 36 needs Java 21, the test JVM is 17
class SetupPermissionsButtonTest {

    private val app: Application get() = ApplicationProvider.getApplicationContext()

    // ==================================================================
    // 1) pure decision (PermissionAction — no views)
    // ==================================================================

    @Test
    fun `PermissionAction decide - every state reacts, never silent`() {
        assertEquals(
            "missing permission -> system dialog",
            PermissionAction.Action.REQUEST_DIALOG, PermissionAction.decide(1),
        )
        assertEquals(
            "several missing -> system dialog",
            PermissionAction.Action.REQUEST_DIALOG, PermissionAction.decide(2),
        )
        assertEquals(
            "nothing missing -> all-granted toast (NOT the old silent return)",
            PermissionAction.Action.ALL_GRANTED_TOAST, PermissionAction.decide(0),
        )
    }

    @Test
    fun `PermissionAction hint - matrix missing x denied-forever marker`() {
        assertEquals(
            "missing, no marker -> ask for the permissions",
            PermissionAction.Hint.NEEDS_PERMISSIONS, PermissionAction.hint(1, deniedForeverMarker = false),
        )
        assertEquals(
            "missing + marker -> denied-forever hint (system settings path)",
            PermissionAction.Hint.DENIED_FOREVER, PermissionAction.hint(1, deniedForeverMarker = true),
        )
        assertEquals(
            "several missing + marker -> denied-forever hint",
            PermissionAction.Hint.DENIED_FOREVER, PermissionAction.hint(2, deniedForeverMarker = true),
        )
        assertEquals(
            "nothing missing -> all-granted hint",
            PermissionAction.Hint.ALL_GRANTED, PermissionAction.hint(0, deniedForeverMarker = false),
        )
        assertEquals(
            "stale marker (granted afterwards) -> all-granted hint wins",
            PermissionAction.Hint.ALL_GRANTED, PermissionAction.hint(0, deniedForeverMarker = true),
        )
    }

    // ==================================================================
    // 2) UI wiring (state matrix at the real activity)
    // ==================================================================

    /** Required permissions of the SDK-35 test (RECORD_AUDIO + POST_NOTIFICATIONS). */
    private val required = listOf(
        Manifest.permission.RECORD_AUDIO,
        Manifest.permission.POST_NOTIFICATIONS,
    )

    private fun grantAll() = Shadows.shadowOf(app).grantPermissions(*required.toTypedArray())

    private fun deny(vararg permissions: String) =
        Shadows.shadowOf(app).denyPermissions(*permissions)

    private fun writeDeniedForeverMarker(permission: String) =
        app.getSharedPreferences("vk_permissions", Context.MODE_PRIVATE)
            .edit()
            .putBoolean("denied_forever_$permission", true)
            .commit()

    private fun buildResumed(): SetupActivity =
        Robolectric.buildActivity(SetupActivity::class.java).setup().resume().get().awaitSetupIo()

    private fun hintOf(activity: SetupActivity): String =
        activity.findViewById<TextView>(R.id.setup_permissions_hint).text.toString()

    private fun openSettingsVisibility(activity: SetupActivity): Int =
        activity.findViewById<View>(R.id.setup_open_settings).visibility

    // ---- case 1: something missing -> SYSTEM dialog, no toast ----

    @Test
    fun `missing - button click opens the system dialog, no toast, ask hint, settings hidden`() {
        grantAll() // deterministic base state, then ONE permission is missing
        deny(Manifest.permission.RECORD_AUDIO)
        val activity = buildResumed()

        assertEquals(
            "hint asks for the missing permission",
            activity.getString(R.string.setup_permissions_hint), hintOf(activity),
        )
        assertEquals("no marker -> open-settings hidden", View.GONE, openSettingsVisibility(activity))

        activity.findViewById<View>(R.id.setup_request_permissions).performClick()

        val started = Shadows.shadowOf(activity).nextStartedActivity
        assertEquals(
            "click starts the SYSTEM permission dialog",
            "android.content.pm.action.REQUEST_PERMISSIONS", started.action,
        )
        assertEquals(
            "exactly the missing permission is requested",
            listOf(Manifest.permission.RECORD_AUDIO),
            started.getStringArrayExtra("android.content.pm.extra.REQUEST_PERMISSIONS_NAMES")?.toList(),
        )
        assertNull("no toast in the dialog case", ShadowToast.getTextOfLatestToast())
    }

    // ---- case 2: nothing missing -> all-granted TOAST, no dialog ----

    @Test
    fun `all granted - button click shows the all-granted toast, hint_ok, no dialog`() {
        grantAll()
        val activity = buildResumed()

        assertEquals(
            "hint shows the all-granted state (V0.1.51)",
            activity.getString(R.string.setup_permissions_hint_ok), hintOf(activity),
        )
        assertEquals("open-settings stays hidden", View.GONE, openSettingsVisibility(activity))

        activity.findViewById<View>(R.id.setup_request_permissions).performClick()

        assertEquals(
            "all-granted toast instead of the dead click",
            activity.getString(R.string.setup_permissions_all_granted),
            ShadowToast.getTextOfLatestToast(),
        )
        assertNull(
            "no system dialog launched (nothing missing)",
            Shadows.shadowOf(activity).nextStartedActivity,
        )
    }

    // ---- case 3: denied forever -> settings button + denied-forever hint ----

    @Test
    fun `denied forever - open-settings VISIBLE, denied hint, click still opens the dialog`() {
        grantAll()
        deny(Manifest.permission.RECORD_AUDIO)
        writeDeniedForeverMarker(Manifest.permission.RECORD_AUDIO)
        val activity = buildResumed()

        assertEquals(
            "hint names the system-settings path",
            activity.getString(R.string.setup_permissions_denied_forever), hintOf(activity),
        )
        assertEquals(
            "denied-forever marker -> open-settings button VISIBLE (V0.1.51)",
            View.VISIBLE, openSettingsVisibility(activity),
        )

        // The permission is STILL missing -> the button keeps offering the
        // system dialog (the marker does not block the retry).
        activity.findViewById<View>(R.id.setup_request_permissions).performClick()
        assertEquals(
            "click still starts the system dialog",
            "android.content.pm.action.REQUEST_PERMISSIONS",
            Shadows.shadowOf(activity).nextStartedActivity.action,
        )
        assertNull("no toast in the dialog case", ShadowToast.getTextOfLatestToast())
    }

    // ---- regression: the marker alone (permission granted afterwards) ----

    @Test
    fun `stale marker with all granted - hint_ok wins, settings button follows the marker`() {
        grantAll()
        writeDeniedForeverMarker(Manifest.permission.RECORD_AUDIO)
        val activity = buildResumed()

        assertEquals(
            "all granted -> all-granted hint even with a stale marker",
            activity.getString(R.string.setup_permissions_hint_ok), hintOf(activity),
        )
        // The button follows the MARKER (the only way back from a permanently
        // denied permission is the settings page — the marker stays honest).
        assertEquals(
            "marker exists -> open-settings stays reachable",
            View.VISIBLE, openSettingsVisibility(activity),
        )
        activity.findViewById<View>(R.id.setup_request_permissions).performClick()
        assertEquals(
            "click shows the all-granted toast",
            activity.getString(R.string.setup_permissions_all_granted),
            ShadowToast.getTextOfLatestToast(),
        )
    }
}
