package de.voicekeyboard.ime

import android.content.Context
import de.voicekeyboard.model.ModelVariant
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

/**
 * T22g (PLAN_V0146 §3): service-near seam of the model switch — the pref
 * listener wiring in VoiceKeyboardService (pattern: ImeRootViewLayoutTest
 * builds the IME service with Robolectric). The coordinator integration
 * (Apply with a real dictation, Deferred applied at the idle report) is
 * device duty (T22j); the pure decision is pinned in ModelSwitchPolicyTest.
 *
 * Pins here: the listener is registered in onCreate, a pref change to a
 * NOT-READY set logs the rejection (VK-MODEL, rule 9: codes only) and never
 * builds a coordinator eagerly; onDestroy unregisters without crashing.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35]) // project pattern: SDK 36 needs Java 21, the test JVM is 17
class ModelSwitchServiceWiringTest {

    @Test
    fun `pref change to a not-ready set is rejected and logged - no eager build`() {
        val service = Robolectric.buildService(VoiceKeyboardService::class.java).get()
        service.onCreate() // registers the vk_layout/model_variant listener (T22g)

        // Robolectric device has no qwen model => statusQuick != READY => Rejected
        service.getSharedPreferences(VoiceKeyboardService.LAYOUT_PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(ModelVariant.PREFS_KEY, ModelVariant.QWEN3_ASR.id)
            .commit() // synchronous => the listener runs on this thread

        val rejected = Shadows.shadowOf(org.robolectric.RuntimeEnvironment.getApplication())
            .let { org.robolectric.shadows.ShadowLog.getLogs() }
            .any {
                it.type == android.util.Log.INFO &&
                    it.tag == "VK-MODEL" &&
                    it.msg.contains("switch_rejected reason=not_ready") &&
                    it.msg.contains("target=qwen3-asr-0.6b-int8")
            }
        assertTrue("rejection must be visible in the VK-MODEL log", rejected)

        // lifecycle: unregisters the listener, no crash
        service.onDestroy()
    }
}
