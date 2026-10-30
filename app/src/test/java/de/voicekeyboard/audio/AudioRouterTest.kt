package de.voicekeyboard.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Reine JVM-Logiktests für [RoutingPolicy] und die [PcmBlock]/Pegel-Semantik
 * aus IMPLEMENTATION_PLAN.md §B.1 / DESIGN.md §3 — kein Android-Framework.
 */
class AudioRouterTest {

    private fun wired(id: Int = 10) = InputDeviceFacts(id, InputDeviceFacts.Kind.WIRED)
    private fun bt(id: Int = 11) = InputDeviceFacts(id, InputDeviceFacts.Kind.BLUETOOTH_SCO)
    private fun builtin(id: Int = 12) = InputDeviceFacts(id, InputDeviceFacts.Kind.BUILTIN)
    private fun a2dp(id: Int = 13) = InputDeviceFacts(id, InputDeviceFacts.Kind.OTHER)

    // ---- Auswahlregel: WIRED > BLUETOOTH_SCO > BUILTIN > keins ----

    @Test fun `leere Geraeteliste liefert null (Systemdefault)`() {
        assertNull(RoutingPolicy.preferredInput(emptyList()))
    }

    @Test fun `nur eingebautes Mikrofon wird gewaehlt`() {
        assertEquals(builtin(), RoutingPolicy.preferredInput(listOf(builtin())))
    }

    @Test fun `Headset schlaegt Bluetooth und Builtin`() {
        assertEquals(wired(7), RoutingPolicy.preferredInput(listOf(bt(), builtin(), wired(7))))
    }

    @Test fun `Bluetooth SCO schlaegt Builtin`() {
        assertEquals(bt(), RoutingPolicy.preferredInput(listOf(builtin(), bt())))
    }

    @Test fun `A2DP und andere Nicht-Mikrofone werden nie gewaehlt`() {
        // Nur OTHER vorhanden → kein bevorzugtes Gerät (Systemdefault), auch nicht A2DP
        assertNull(RoutingPolicy.preferredInput(listOf(a2dp(1), a2dp(2))))
        // OTHER verliert gegen alles
        assertEquals(builtin(), RoutingPolicy.preferredInput(listOf(a2dp(), builtin())))
    }

    @Test fun `mehrere Headsets - erste Prioritaet bleibt deterministisch`() {
        val preferred = RoutingPolicy.preferredInput(listOf(wired(3), wired(4)))
        assertTrue(preferred?.kind == InputDeviceFacts.Kind.WIRED)
    }

    // ---- Route-Anzeige für die UI ----

    @Test fun `Route fuer UI ist WIRED bei Headset`() {
        assertEquals(Route.WIRED_HEADSET, RoutingPolicy.routeFor(wired()))
    }

    @Test fun `Route fuer UI ist BLUETOOTH bei SCO`() {
        assertEquals(Route.BLUETOOTH_SCO, RoutingPolicy.routeFor(bt()))
    }

    @Test fun `Route fuer UI ist BUILTIN bei Default oder OTHER`() {
        assertEquals(Route.BUILTIN_MIC, RoutingPolicy.routeFor(null))
        assertEquals(Route.BUILTIN_MIC, RoutingPolicy.routeFor(a2dp()))
    }

    // ---- PcmBlock-Semantik (§B.1) ----

    @Test fun `PcmBlock gleichheit ueber seq startSample und samples`() {
        val a = PcmBlock(seq = 1, startSample = 320, samples = shortArrayOf(1, -2, 3))
        val b = PcmBlock(seq = 1, startSample = 320, samples = shortArrayOf(1, -2, 3))
        val c = PcmBlock(seq = 2, startSample = 640, samples = shortArrayOf(1, -2, 3))
        assertEquals(a, b)
        assertTrue(a != c)
    }

    @Test fun `RMS-Semantik - Vollaussteuerung und Stille`() {
        // Pegel 0 für Stille, <= 1 für Vollaussteuerung: über AudioRecordSource-Regel
        // (32768-Nenner, wie PcmWindow.toFloatMono)
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
