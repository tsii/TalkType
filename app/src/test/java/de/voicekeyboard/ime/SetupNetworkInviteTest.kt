package de.voicekeyboard.ime

import android.content.Context
import android.view.View
import androidx.test.core.app.ApplicationProvider
import de.voicekeyboard.R
import de.voicekeyboard.model.DownloadController
import de.voicekeyboard.model.ModelStore
import de.voicekeyboard.model.NetworkInvitePolicy
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

/**
 * T20b (v0.1.45, PLAN_V0145 T20 clarification, binding): the app NEVER revokes
 * or touches the network itself. After a download ONE invite card offers the
 * SYSTEM-level revoke (directly the app-details settings, package:<pkg>,
 * fallback ACTION_SETTINGS) — exactly once per model set
 * ([NetworkInvitePolicy.shouldShowInvite], persisted under "net_invite_shown_for"
 * = modelSetId; "Later" AND the settings click both persist it).
 * The E2 app switch is REMOVED — the app has no in-app network control anymore.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35]) // project pattern: SDK 36 needs Java 21, the test JVM is 17
class SetupNetworkInviteTest {

    /** modelSetId = stable manifest hash (T20b); disk-independent. */
    private fun modelSetId(context: Context): String =
        ModelStore(File(context.filesDir, "models")).manifestSetId()

    /** Activity driven to the given controller state (inviteStateOverride = test seam). */
    private fun buildWithState(state: DownloadController.State?): SetupActivity {
        val controller = Robolectric.buildActivity(SetupActivity::class.java)
        controller.get().inviteStateOverride = state
        controller.setup()
        return controller.get()
    }

    private fun shownFor(context: Context): String? =
        context.getSharedPreferences("vk_permissions", Context.MODE_PRIVATE)
            .getString("net_invite_shown_for", null)

    // (a) policy 'show' -> card VISIBLE
    @Test
    fun `T20b policy show - invite card VISIBLE for Ready and never shown`() {
        val activity = buildWithState(DownloadController.State.Ready)
        val card = activity.findViewById<View>(R.id.setup_net_invite)
        assertEquals("card VISIBLE for Ready + never shown", View.VISIBLE, card.visibility)
        assertEquals("nothing persisted yet (one-time only after interaction)",
            null, shownFor(activity))
    }

    // (b) shownFor == modelSetId -> card GONE (never nag again)
    @Test
    fun `T20b shown for this model - invite card GONE`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val id = modelSetId(context)
        context.getSharedPreferences("vk_permissions", Context.MODE_PRIVATE)
            .edit().putString("net_invite_shown_for", id).commit()

        val activity = buildWithState(DownloadController.State.Ready)
        assertEquals("card GONE for shown modelSetId", View.GONE,
            activity.findViewById<View>(R.id.setup_net_invite).visibility)
    }

    // (d) policy 'no' -> card GONE
    @Test
    fun `T20b non-Ready state - invite card GONE`() {
        val activity = buildWithState(DownloadController.State.Downloading(0, 2, "f.bin", 0.5f))
        assertEquals("card GONE while downloading", View.GONE,
            activity.findViewById<View>(R.id.setup_net_invite).visibility)
    }

    // (b) revoke button: DIRECT app-details deep link + persist shown
    @Test
    fun `T20b revoke button - app details intent with package data and shown persisted`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val activity = buildWithState(DownloadController.State.Ready)

        activity.findViewById<View>(R.id.setup_net_invite_button).performClick()

        val started = Shadows.shadowOf(context as android.app.Application).nextStartedActivity
        assertEquals("ACTION_APPLICATION_DETAILS_SETTINGS deep link",
            NetworkInvitePolicy.inviteIntentAction(), started.action)
        assertEquals("data = package:<app package>",
            "package:de.voicekeyboard", started.data.toString())
        assertEquals("click persists net_invite_shown_for = modelSetId",
            modelSetId(context), shownFor(context))
        assertEquals("card gone after the click", View.GONE,
            activity.findViewById<View>(R.id.setup_net_invite).visibility)
    }

    // "Later": persist only, NO settings intent
    @Test
    fun `T20b later button - no intent, shown persisted, card gone`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val activity = buildWithState(DownloadController.State.Ready)

        activity.findViewById<View>(R.id.setup_net_invite_later).performClick()

        assertNull("'Later' starts NO settings intent",
            Shadows.shadowOf(context as android.app.Application).nextStartedActivity)
        assertEquals("'Later' persists net_invite_shown_for = modelSetId",
            modelSetId(context), shownFor(context))
        assertEquals("card gone after 'Later'", View.GONE,
            activity.findViewById<View>(R.id.setup_net_invite).visibility)
    }

    // E2 regression: the in-app network switch is REMOVED (no view, no id)
    @Test
    fun `T20b E2 regression - setup_network_switch removed from the layout`() {
        val activity = buildWithState(null)
        val id = activity.resources.getIdentifier("setup_network_switch", "id", activity.packageName)
        assertEquals("E2 switch id no longer exists", 0, id)
    }

    // determinism of the persisted id: same manifest -> same modelSetId (both reads)
    @Test
    fun `T20b modelSetId stable - same manifest hash on repeated reads`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        assertEquals(modelSetId(context), modelSetId(context))
        assertEquals(NetworkInvitePolicy.markShown(modelSetId(context)), modelSetId(context))
    }
}
