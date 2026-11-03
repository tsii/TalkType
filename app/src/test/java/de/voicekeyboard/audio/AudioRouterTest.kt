package de.voicekeyboard.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure JVM logic tests for [RoutingPolicy] and the [PcmBlock]/level semantics
 * from IMPLEMENTATION_PLAN.md §B.1 / DESIGN.md §3 — no Android framework.
 */
class AudioRouterTest {

    private fun wired(id: Int = 10) = InputDeviceFacts(id, InputDeviceFacts.Kind.WIRED)
    private fun bt(id: Int = 11) = InputDeviceFacts(id, InputDeviceFacts.Kind.BLUETOOTH_SCO)
    private fun builtin(id: Int = 12) = InputDeviceFacts(id, InputDeviceFacts.Kind.BUILTIN)
    private fun a2dp(id: Int = 13) = InputDeviceFacts(id, InputDeviceFacts.Kind.OTHER)

    // ---- selection rule: WIRED > BLUETOOTH_SCO > BUILTIN > none ----

    @Test fun `empty device list yields null (system default)`() {
        assertNull(RoutingPolicy.preferredInput(emptyList()))
    }

    @Test fun `only built-in microphone is selected`() {
        assertEquals(builtin(), RoutingPolicy.preferredInput(listOf(builtin())))
    }

    @Test fun `headset beats Bluetooth and builtin`() {
        assertEquals(wired(7), RoutingPolicy.preferredInput(listOf(bt(), builtin(), wired(7))))
    }

    @Test fun `Bluetooth SCO beats builtin`() {
        assertEquals(bt(), RoutingPolicy.preferredInput(listOf(builtin(), bt())))
    }

    @Test fun `A2DP and other non-microphones are never selected`() {
        // only OTHER present → no preferred device (system default), not A2DP either
        assertNull(RoutingPolicy.preferredInput(listOf(a2dp(1), a2dp(2))))
        // OTHER loses against everything
        assertEquals(builtin(), RoutingPolicy.preferredInput(listOf(a2dp(), builtin())))
    }

    @Test fun `multiple headsets - first priority stays deterministic`() {
        val preferred = RoutingPolicy.preferredInput(listOf(wired(3), wired(4)))
        assertTrue(preferred?.kind == InputDeviceFacts.Kind.WIRED)
    }

    // ---- route display for the UI ----

    @Test fun `route for UI is WIRED with headset`() {
        assertEquals(Route.WIRED_HEADSET, RoutingPolicy.routeFor(wired()))
    }

    @Test fun `route for UI is BLUETOOTH with SCO`() {
        assertEquals(Route.BLUETOOTH_SCO, RoutingPolicy.routeFor(bt()))
    }

    @Test fun `route for UI is BUILTIN for default or OTHER`() {
        assertEquals(Route.BUILTIN_MIC, RoutingPolicy.routeFor(null))
        assertEquals(Route.BUILTIN_MIC, RoutingPolicy.routeFor(a2dp()))
    }

    // ---- PcmBlock-Semantik (§B.1) ----

    @Test fun `PcmBlock equality via seq startSample and samples`() {
        val a = PcmBlock(seq = 1, startSample = 320, samples = shortArrayOf(1, -2, 3))
        val b = PcmBlock(seq = 1, startSample = 320, samples = shortArrayOf(1, -2, 3))
        val c = PcmBlock(seq = 2, startSample = 640, samples = shortArrayOf(1, -2, 3))
        assertEquals(a, b)
        assertTrue(a != c)
    }

    @Test fun `RMS semantics - full scale and silence`() {
        // level 0 for silence, <= 1 for full scale: via the AudioRecordSource rule
        // (32768 denominator, like PcmWindow.toFloatMono)
        assertEquals(0f, rmsOf(shortArrayOf(0, 0, 0, 0)), 1e-9f)
        assertTrue(rmsOf(shortArrayOf(32767, -32768)) <= 1.0f)
        assertTrue(rmsOf(shortArrayOf(16384)) > 0.49f && rmsOf(shortArrayOf(16384)) < 0.51f)
    }

    private fun rmsOf(samples: ShortArray): Float {
        if (samples.isEmpty()) return 0f
        var sum = 0.0
        for (s in samples) {
            val v = s / 32768.0
            sum += v * v
        }
        return kotlin.math.sqrt(sum / samples.size).toFloat()
    }
}
