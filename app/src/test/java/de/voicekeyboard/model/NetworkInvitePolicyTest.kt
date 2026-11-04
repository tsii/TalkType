package de.voicekeyboard.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure JVM tests for [NetworkInvitePolicy] — the one-time system-level network
 * revoke invite after a model download (docs/PLAN_V0145.md T20a).
 *
 * Matrix (PLAN V0145 T20):
 * (a) Ready + never shown for this modelSetId  -> show
 * (b) Ready + already shown for this modelSetId -> no
 * (c) Ready + new modelSetId (old one shown)   -> show
 * (d) Failed/Cancelled/Downloading/Verifying/Idle -> no
 * (e) cold start READY + never shown           -> show once (same decision (a))
 * (b2) "Later" click and settings click both use markShown(id) -> shownFor=id
 */
class NetworkInvitePolicyTest {

    private val modelSetId = "sha256:abc123"

    // (a) Ready after a download run, never shown -> show
    @Test fun readyNeverShownShows() {
        assertTrue(NetworkInvitePolicy.shouldShowInvite(DownloadController.State.Ready, modelSetId, null))
    }

    // (b) Ready, already shown for exactly this modelSetId -> no (one-time invite)
    @Test fun readyShownForSameIdDoesNotShow() {
        assertFalse(NetworkInvitePolicy.shouldShowInvite(DownloadController.State.Ready, modelSetId, modelSetId))
    }

    // (c) Ready, a NEW modelSetId arrived (old one was shown) -> show again
    @Test fun readyWithNewModelSetIdShows() {
        assertTrue(NetworkInvitePolicy.shouldShowInvite(DownloadController.State.Ready, "sha256:new456", modelSetId))
    }

    // (d) every non-Ready state never shows, regardless of shownFor
    @Test fun nonReadyStatesNeverShow() {
        val states = listOf(
            DownloadController.State.Idle,
            DownloadController.State.Downloading(0, 2, "f.bin", 0.5f),
            DownloadController.State.Verifying,
            DownloadController.State.Failed(ModelError.Network("timeout")),
            DownloadController.State.Cancelled,
        )
        for (s in states) {
            assertFalse("state $s should not show", NetworkInvitePolicy.shouldShowInvite(s, modelSetId, null))
            assertFalse("state $s with shown id should not show", NetworkInvitePolicy.shouldShowInvite(s, modelSetId, modelSetId))
        }
    }

    // (e) cold start READY + never shown -> same pure decision shows once
    @Test fun coldStartReadyNeverShownShowsOnce() {
        assertTrue(NetworkInvitePolicy.shouldShowInvite(DownloadController.State.Ready, modelSetId, null))
        // after persisting shown_for = id (T20b caller duty) the same call is silent
        assertFalse(NetworkInvitePolicy.shouldShowInvite(DownloadController.State.Ready, modelSetId, modelSetId))
    }

    // (b2) both buttons ("Later" and settings click) use the same pure markShown(id)
    @Test fun markShownReturnsIdForBothButtons() {
        assertEquals(modelSetId, NetworkInvitePolicy.markShown(modelSetId))
        assertEquals("sha256:other", NetworkInvitePolicy.markShown("sha256:other"))
    }

    // determinism: same inputs -> same outputs on repeated calls
    @Test fun deterministicDecision() {
        val first = NetworkInvitePolicy.shouldShowInvite(DownloadController.State.Ready, modelSetId, null)
        val second = NetworkInvitePolicy.shouldShowInvite(DownloadController.State.Ready, modelSetId, null)
        assertEquals(first, second)
    }

    // intent strings: ACTION_APPLICATION_DETAILS_SETTINGS + package:<pkg> (pkg as parameter)
    @Test fun inviteIntentActionAndData() {
        assertEquals(
            "android.settings.APPLICATION_DETAILS_SETTINGS",
            NetworkInvitePolicy.inviteIntentAction(),
        )
        assertEquals(
            "package:de.voicekeyboard.talktype",
            NetworkInvitePolicy.inviteIntentData("de.voicekeyboard.talktype"),
        )
    }

    // fallback if no app-details activity exists: plain system settings
    @Test fun fallbackIntentAction() {
        assertEquals("android.settings.SETTINGS", NetworkInvitePolicy.fallbackIntentAction())
    }
}
