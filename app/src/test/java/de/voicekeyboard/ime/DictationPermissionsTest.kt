package de.voicekeyboard.ime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * V16a: the notification permission is PROMOTED, but below api 33 NEVER
 * BLOCKING.
 *
 * F28c history: from api 33 POST_NOTIFICATIONS was a dictation condition
 * (export #2: api 37, notif=no, "recording dead after 1.0 s").
 * T21a measurement 2026-11-04 (PLAN_V0145 §T21b, binding): 3x60 s revoked
 * + 2x60 s granted clean on api36 — the OS does NOT stop the FGS mic
 * without POST_NOTIFICATIONS; the F28c cause was the app-side silence guard
 * (audio_error:silence), not the OS. POST_NOTIFICATIONS is NEVER a dictation
 * condition again (V16a semantics at EVERY api level); it stays OPTIONAL
 * (Setup asks once; the 🐞 export field permPostNotifications stays).
 * The T21a debug bridges (notifGateBypassed/silentGuardBypassed) remain as
 * measurement technique.
 *
 * Core error from live operation: REQUIRED_IN_ORDER contained
 * POST_NOTIFICATIONS — if it was missing, nextStep never returned Ready and
 * ensurePermissions() aborted before startFgs() ⇒ dictation impossible,
 * even though the microphone was granted. Per the
 * CaptureForegroundService documentation the FGS runs EVEN WITHOUT
 * POST_NOTIFICATIONS (the notification is just invisible). The old tests that
 * asserted "both required" were the WRONG expectation and have been pulled to
 * the new, correct semantics (V16a): only RECORD_AUDIO is the core condition;
 * POST_NOTIFICATIONS is optional (question only on the setup page).
 */
class DictationPermissionsTest {

    private val mic = android.Manifest.permission.RECORD_AUDIO
    private val notif = android.Manifest.permission.POST_NOTIFICATIONS

    // (a) THE user case: mic granted + notification denied ⇒ Ready
    @Test fun `V16a mic granted notification denied - Ready`() {
        assertEquals(
            DictationPermissions.Decision.Ready,
            DictationPermissions.nextStep(granted = setOf(mic), permanentlyDenied = setOf(notif)),
        )
        // also with a normal (not permanent) denial:
        assertEquals(
            DictationPermissions.Decision.Ready,
            DictationPermissions.nextStep(granted = setOf(mic), permanentlyDenied = emptySet()),
        )
    }

    // (b) mic denied ⇒ Ask(RECORD_AUDIO)
    @Test fun `V16a mic denied - asks only for the microphone`() {
        assertEquals(
            DictationPermissions.Decision.Ask(mic),
            DictationPermissions.nextStep(emptySet(), emptySet()),
        )
    }

    // (c) mic permanently denied ⇒ PermanentlyDenied(RECORD_AUDIO)
    @Test fun `V16a mic permanently denied - PermanentlyDenied only for the mic`() {
        assertEquals(
            DictationPermissions.Decision.PermanentlyDenied(mic),
            DictationPermissions.nextStep(emptySet(), setOf(mic)),
        )
    }

    // (d) isReady independent of POST_NOTIFICATIONS
    @Test fun `V16a isReady independent of POST_NOTIFICATIONS`() {
        assertTrue(DictationPermissions.isReady(setOf(mic)))
        assertTrue(DictationPermissions.isReady(setOf(mic, notif)))
        assertFalse(DictationPermissions.isReady(emptySet()))
        assertFalse(DictationPermissions.isReady(setOf(notif))) // the notification alone never suffices
    }

    // (e) OPTIONAL_IN_ORDER contains the notification; nextStep never returns it as a blocking reason
    @Test fun `V16a optional contains the notification and never blocks`() {
        assertEquals(listOf(notif), DictationPermissions.OPTIONAL_IN_ORDER)
        // nextStep does NOT know the optional permission as a decision path:
        for (granted in listOf(emptySet<String>(), setOf(notif), setOf(mic))) {
            val d = DictationPermissions.nextStep(granted, setOf(notif))
            assertFalse(
                "nextStep must never deliver optional permissions: $d",
                d is DictationPermissions.Decision.Ask && d.permission == notif ||
                    d is DictationPermissions.Decision.PermanentlyDenied && d.permission == notif,
            )
        }
    }

    // stock semantics, pinned as a mandatory feature: order stable
    @Test fun `V16a REQUIRED contains only the microphone`() {
        assertEquals(listOf(mic), DictationPermissions.REQUIRED_IN_ORDER)
    }

    // T21b (measurement 2026-11-04: 3x60 s rev + 2x60 s grant clean on api36;
    // F28c cause was the app-side silence guard (audio_error:silence), NOT
    // the OS) — INVERTED F28c expectation: the notification NEVER blocks,
    // at any api level, even when permanently denied.
    @Test fun `T21b api33 without notif permission - Ready, notif never blocks`() {
        // mic granted, notification NOT: Ready at every api level
        assertEquals(
            DictationPermissions.Decision.Ready,
            DictationPermissions.nextStep(setOf(mic), emptySet(), 32),
        )
        assertEquals(
            DictationPermissions.Decision.Ready,
            DictationPermissions.nextStep(setOf(mic), emptySet(), 33),
        )
        assertEquals(
            DictationPermissions.Decision.Ready,
            DictationPermissions.nextStep(setOf(mic), emptySet(), 37),
        )
        // permanently denied notif: STILL Ready — the settings path for the
        // notification (dictation_error_permission_notification) is gone
        assertEquals(
            DictationPermissions.Decision.Ready,
            DictationPermissions.nextStep(setOf(mic), setOf(notif), 33),
        )
        // with the notification granted: Ready as before
        assertEquals(
            DictationPermissions.Decision.Ready,
            DictationPermissions.nextStep(setOf(mic, notif), emptySet(), 33),
        )
        // isReady reflects the same semantics at every level
        assertTrue("api 33 mic only = ready", DictationPermissions.isReady(setOf(mic), emptySet(), 33))
        assertTrue("api 37 mic only = ready", DictationPermissions.isReady(setOf(mic), emptySet(), 37))
        assertTrue("api 32 mic only = ready (V16a)", DictationPermissions.isReady(setOf(mic), emptySet(), 32))
        // the microphone stays the only condition: missing -> Ask(mic)
        assertEquals(
            DictationPermissions.Decision.Ask(mic),
            DictationPermissions.nextStep(emptySet(), emptySet(), 33),
        )
    }

    // T21b: requiredForApi is mic-only at EVERY api level (apiLevel parameter
    // kept for call-site stability; the notif never enters the required set)
    @Test fun `T21b requiredForApi - only the microphone at every api level`() {
        for (api in listOf(28, 32, 33, 35, 36)) {
            assertEquals("api $api", listOf(mic), DictationPermissions.requiredForApi(api))
            assertFalse(
                "api $api: POST_NOTIFICATIONS not in the required set",
                notif in DictationPermissions.requiredForApi(api),
            )
        }
    }

    // ==================================================================
    // T21a (v0.1.45, measurement bridge, PLAN_V0145 §T21a): on the DEBUG
    // build the F28c app gate may release POST_NOTIFICATIONS so that the
    // OS behavior (FGS mic alive/dead) can be measured with the permission
    // revoked. Release is NEVER bypassed — the pure matrix pins that.
    // ==================================================================

    // (1) pure matrix: debuggable AND setting exactly 1
    @Test fun `T21a notifGateBypassed matrix - only debuggable AND setting 1`() {
        // release (debuggable=false): NEVER bypassed, regardless of the setting
        assertFalse(DictationPermissions.notifGateBypassed(debuggable = false, setting = 0))
        assertFalse(DictationPermissions.notifGateBypassed(debuggable = false, setting = 1))
        assertFalse(DictationPermissions.notifGateBypassed(debuggable = false, setting = 2))
        // debug: ONLY setting == 1 bypasses
        assertTrue("debug + setting 1 = bypass", DictationPermissions.notifGateBypassed(debuggable = true, setting = 1))
        assertFalse("debug + setting 0 = no bypass", DictationPermissions.notifGateBypassed(debuggable = true, setting = 0))
        assertFalse("debug + setting 2 = no bypass", DictationPermissions.notifGateBypassed(debuggable = true, setting = 2))
    }

    // (2) T21b: with POST_NOTIFICATIONS never required, the bypass parameter is
    // IRRELEVANT for the decision (the debug bridge stays as measurement
    // technique and must not change the outcome) — mic stays the only condition.
    @Test fun `T21b decision api35 mic only - Ready regardless of the bypass parameter`() {
        val granted = setOf(mic) // RECORD_AUDIO only, notification revoked
        for (bypass in listOf(false, true)) {
            assertEquals(
                "bypass=$bypass: dictation may start",
                DictationPermissions.Decision.Ready,
                DictationPermissions.nextStep(granted, emptySet(), 35, bypassNotif = bypass),
            )
            // bypass releases ONLY the notification, never the microphone:
            assertEquals(
                "bypass=$bypass: mic stays mandatory",
                DictationPermissions.Decision.Ask(mic),
                DictationPermissions.nextStep(emptySet(), emptySet(), 35, bypassNotif = bypass),
            )
            assertTrue(
                DictationPermissions.isReady(granted, emptySet(), 35, bypassNotif = bypass),
            )
        }
    }

    // T21a measurement bridge, part 2 (silent guard): the app's own ERR_SILENT
    // start watchdog aborts a dead mic source (emulator -no-audio) after ~3 s —
    // BEFORE the OS-FGS layer is observable. Debug-only bypass via the SAME
    // operator switch (vk_skip_notif_gate): one switch arms the whole bridge.
    @Test fun `T21a silentGuardBypassed matrix - only debuggable AND setting 1`() {
        // release (debuggable=false): NEVER bypassed, regardless of the setting
        assertFalse(DictationPermissions.silentGuardBypassed(debuggable = false, setting = 0))
        assertFalse(DictationPermissions.silentGuardBypassed(debuggable = false, setting = 1))
        assertFalse(DictationPermissions.silentGuardBypassed(debuggable = false, setting = 2))
        // debug: ONLY setting == 1 bypasses
        assertTrue("debug + setting 1 = bypass", DictationPermissions.silentGuardBypassed(debuggable = true, setting = 1))
        assertFalse("debug + setting 0 = no bypass", DictationPermissions.silentGuardBypassed(debuggable = true, setting = 0))
        assertFalse("debug + setting 2 = no bypass", DictationPermissions.silentGuardBypassed(debuggable = true, setting = 2))
    }
}
