package de.voicekeyboard.ime

import de.voicekeyboard.model.ModelVariant
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * T22g (PLAN_V0146 §3): PURE switch decision [ModelSwitchPolicy] — no Android
 * types, no store access. Highest ruling (plan §3): a RUNNING dictation is
 * NEVER aborted — a switch during dictation becomes Deferred and is applied
 * at the idle report (onDictationStopped).
 */
class ModelSwitchPolicyTest {

    private val a = ModelVariant.ONNX_CPU
    private val q = ModelVariant.QWEN3_ASR

    @Test
    fun `ready and idle applies immediately`() {
        assertEquals(
            ModelSwitchPolicy.Decision.Apply,
            ModelSwitchPolicy.policy(target = q, current = a, targetReady = true, dictationActive = false),
        )
    }

    @Test
    fun `ready but dictation active defers - never aborts`() {
        assertEquals(
            "a running dictation must NEVER be aborted by a switch",
            ModelSwitchPolicy.Decision.Deferred,
            ModelSwitchPolicy.policy(target = q, current = a, targetReady = true, dictationActive = true),
        )
    }

    @Test
    fun `not ready is rejected - idle or active alike`() {
        assertEquals(
            ModelSwitchPolicy.Decision.Rejected(ModelSwitchPolicy.Reason.NOT_READY),
            ModelSwitchPolicy.policy(target = q, current = a, targetReady = false, dictationActive = false),
        )
        assertEquals(
            "not-ready dominates even an active dictation (nothing is ever applied)",
            ModelSwitchPolicy.Decision.Rejected(ModelSwitchPolicy.Reason.NOT_READY),
            ModelSwitchPolicy.policy(target = q, current = a, targetReady = false, dictationActive = true),
        )
    }

    @Test
    fun `target equals current is a no-op - pref is not even written`() {
        for (active in listOf(false, true)) {
            for (ready in listOf(false, true)) {
                assertEquals(
                    "NoOp regardless of ready=$ready active=$active",
                    ModelSwitchPolicy.Decision.NoOp,
                    ModelSwitchPolicy.policy(target = q, current = q, targetReady = ready, dictationActive = active),
                )
            }
        }
        assertEquals(
            ModelSwitchPolicy.Decision.NoOp,
            ModelSwitchPolicy.policy(target = a, current = a, targetReady = true, dictationActive = false),
        )
    }
}
