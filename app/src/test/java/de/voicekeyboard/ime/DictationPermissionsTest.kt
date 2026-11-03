package de.voicekeyboard.ime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * V16a: the notification permission is PROMOTED, but below api 33 NEVER
 * BLOCKING.
 *
 * F28c (export #2, api 37, notif=no, recording ended silently after 1.0 s):
 * from api 33 POST_NOTIFICATIONS is a dictation condition — the FGS type
 * microphone can be stopped silently by the system without it. The V16a
 * tests below run with the default apiLevel=29 and remain valid; the api-33
 * guard is proven in the test `F28c api33 without notif permission …`.
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

    // F28c (export #2: api 37, notif=no, recording ended silently after 1.0 s):
    // from api 33 POST_NOTIFICATIONS is a dictation condition. Guard logic:
    // without it nextStep returns Ask instead of Ready — VoiceKeyboardService
    // aborts in ensurePermissions() BEFORE startFgs()/AudioRecord.startRecording()
    // and shows a visible error (new string) instead of starting silently.
    @Test fun `F28c api33 without notif permission - no start, Ask instead of Ready`() {
        // mic granted, notification NOT: below api 33 Ready (V16a preserved)
        assertEquals(
            DictationPermissions.Decision.Ready,
            DictationPermissions.nextStep(setOf(mic), emptySet(), 32),
        )
        // from api 33 with mic OR without: Ask(POST_NOTIFICATIONS) — dictation blocked
        assertEquals(
            DictationPermissions.Decision.Ask(notif),
            DictationPermissions.nextStep(setOf(mic), emptySet(), 33),
        )
        assertEquals(
            DictationPermissions.Decision.Ask(notif),
            DictationPermissions.nextStep(setOf(mic), emptySet(), 37),
        )
        // permanently denied: no second dialog, path to the settings
        assertEquals(
            DictationPermissions.Decision.PermanentlyDenied(notif),
            DictationPermissions.nextStep(setOf(mic), setOf(notif), 33),
        )
        // with the notification granted: Ready — dictation may start (again)
        assertEquals(
            DictationPermissions.Decision.Ready,
            DictationPermissions.nextStep(setOf(mic, notif), emptySet(), 33),
        )
        // isReady reflects the same guard
        assertFalse("api 33 without notif = not ready", DictationPermissions.isReady(setOf(mic), emptySet(), 33))
        assertTrue("api 33 with notif = ready", DictationPermissions.isReady(setOf(mic, notif), emptySet(), 33))
        assertTrue("api 32 without notif = ready (V16a)", DictationPermissions.isReady(setOf(mic), emptySet(), 32))
        // order: the mic is asked BEFORE the notification (mic missing + api 33)
        assertEquals(
            DictationPermissions.Decision.Ask(mic),
            DictationPermissions.nextStep(emptySet(), emptySet(), 33),
        )
    }
}
