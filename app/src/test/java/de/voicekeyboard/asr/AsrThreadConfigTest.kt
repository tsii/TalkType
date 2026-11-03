package de.voicekeyboard.asr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * S12: Reine, JVM-testbare ASR-Laufzeit-Konfiguration.
 * Hebel 1: Thread-/Quota-Setting des CPU-EP (AsrThreadConfig.forDevice).
 * Hebel 2: Chunk-/Hypothesen-Intervall (chunkMs, hypothesisMinIntervalMs).
 *
 * Defaults = heutige Ist-Werte (OnnxSessions: intra=min(4,cores), inter=1;
 * Coordinator: tick 1 s = chunkMs 1000; MIN_AUDIO_SECONDS_PARTIALS 2 s),
 * damit Standardverhalten identisch bleibt. TDD: Test vor Implementation.
 */
class AsrThreadConfigTest {

    // ---- Hebel 1: forDevice clamping ----

    @Test
    fun `forDevice clampt intra nach unten und oben`() {
        // Mittelbereich: cores=6 → intra=4 (heutiger Ist: min(4, cores)), inter=1
        assertEquals(4, AsrThreadConfig.forDevice(cores = 6, ramMb = 4096).intraOpThreads)
        assertEquals(1, AsrThreadConfig.forDevice(cores = 6, ramMb = 4096).interOpThreads)
        // clamp nach oben: cores=64 → intra=max 6
        assertEquals(6, AsrThreadConfig.forDevice(cores = 64, ramMb = 8192).intraOpThreads)
        // exotisch klein: cores=1 → intra bleibt im gueltigen Bereich (min 2 nach clamp)
        assertEquals(2, AsrThreadConfig.forDevice(cores = 1, ramMb = 512).intraOpThreads)
        // ramMb=0 (unbekannt): Default-Verhalten wie heute (kein Absturz)
        val c = AsrThreadConfig.forDevice(cores = 8, ramMb = 0)
        assertTrue(c.intraOpThreads in 2..6)
        assertTrue(c.interOpThreads in 1..2)
    }

    @Test
    fun `forDevice defaults sind die heutigen ist-werte`() {
        // Heutiger Ist-Stand: intra=min(4, cores), inter=1 → forDevice muss bei
        // typischen 4-8-Kern-Geraeten dieselben Werte liefern.
        assertEquals(4, AsrThreadConfig.forDevice(cores = 4, ramMb = 4096).intraOpThreads)
        assertEquals(1, AsrThreadConfig.forDevice(cores = 4, ramMb = 4096).interOpThreads)
    }

    // ---- Hebel 1: Anwendung nachweisbar (Fake-Receiver) ----

    @Test
    fun `applyTo setzt genau die konfigurierten werte am fake`() {
        val fake = FakeSessionOptionReceiver()
        val config = AsrThreadConfig(intraOpThreads = 3, interOpThreads = 2)
        config.applyTo(fake)
        assertEquals(3, fake.intra)
        assertEquals(2, fake.inter)
        // Default-Config: gleiche Werte wie heute (min(4,..)/1)
        val default = AsrThreadConfig.forDevice(cores = 4, ramMb = 4096)
        default.applyTo(fake)
        assertEquals(4, fake.intra)
        assertEquals(1, fake.inter)
    }

    /** Test-Doppel fuer die ORT-Optionen-Schnittstelle (kein ORT-Interna-Reflexion). */
    private class FakeSessionOptionReceiver : AsrThreadConfig.OptionReceiver {
        var intra = -1
        var inter = -1
        override fun setIntraOpThreads(n: Int) { intra = n }
        override fun setInterOpThreads(n: Int) { inter = n }
    }

    // ---- Hebel 2: Chunk-/Intervall-Logik (rein, ohne Audio) ----

    @Test
    fun `chunks werden genau an den chunk-grenzen geschnitten`() {
        // F21: chunkMs-GEOMETRIE ist unveraendlich — Test pinnt chunkMs=1000 fest
        // (der DEFAULT ist seit F21 = 2000, gehört aber zu den Default-Asserts).
        val cfg = AsrThreadConfig(intraOpThreads = 4, interOpThreads = 1, chunkMs = 1000)
        // 1 s Chunk = 16000 Samples bei 16 kHz; 5 s Audio → Grenzen 1..5 s
        val boundaries = cfg.chunkBoundaries(totalSamples = 16000 * 5)
        assertEquals(listOf(16000, 32000, 48000, 64000, 80000), boundaries)
        // Reste: 2,5 s → Grenzen bei 1 s und 2 s (Rest 0,5 s im letzten Chunk)
        assertEquals(listOf(16000, 32000), cfg.chunkBoundaries(totalSamples = 40000))
    }

    @Test
    fun `hypothesen nicht oefter als intervall aber mindestens einmal pro chunk`() {
        val cfg = AsrThreadConfig.DEFAULT // chunkMs=1000, hypothesisMinIntervalMs=2000
        // Ticks je 1 s: Hypothese nur alle >= 2 s, erste spaetestens nach 2 s
        val emissionTimes = cfg.hypothesisEmissionTimes(tickTimesMs = listOf(1000, 2000, 3000, 4000, 5000))
        // erste Hypothese sofort beim ersten gueltigen Tick (mindestens einmal pro Chunk)
        assertEquals(listOf(1000, 3000, 5000), emissionTimes)
        var last = Long.MIN_VALUE / 2 // Long-Basis: kein Int-Overflow beim ersten Element
        for (t in emissionTimes) {
            assertTrue("Intervall verletzt bei $t (letzte $last)",
                t - last >= cfg.hypothesisMinIntervalMs)
            last = t.toLong()
        }
        assertTrue("mindestens eine Emission", emissionTimes.isNotEmpty())
    }

    @Test
    fun `defaults fuer chunk und intervall entsprechen den aktuellen soll-werten`() {
        // F21 (Nutzerfreigabe 2026-10-30): Coordinator-Tick 2 s (chunkMs=2000),
        // Partials ab 2 s (Intervall 2000) — Grund: quadratisches Neudekodieren.
        assertEquals(2000, AsrThreadConfig.DEFAULT.chunkMs)
        assertEquals(2000, AsrThreadConfig.DEFAULT.hypothesisMinIntervalMs)
        assertEquals(4, AsrThreadConfig.DEFAULT.intraOpThreads)
        assertEquals(1, AsrThreadConfig.DEFAULT.interOpThreads)
        // F21: Takt-Klammern des cost-aware Decode-Takts (DecodePace)
        assertEquals(2000L, AsrThreadConfig.DEFAULT.decodePaceBaseMs)
        assertEquals(6000L, AsrThreadConfig.DEFAULT.decodePaceMaxMs)
    }

    // ---- S12b: Defaults bitgenau = heutiges Coordinator-Verhalten ----

    @Test
    fun `S12b default tick entspricht exakt zwei TICK_SAMPLES_1S`() {
        // F21: tickSamples = chunkMs * 16_000 / 1000 = 32_000 bei Default (chunkMs 2000)
        val tick = AsrThreadConfig.DEFAULT.chunkMs * 16_000 / 1000
        assertEquals(2 * de.voicekeyboard.ime.DictationCoordinator.TICK_SAMPLES_1S, tick)
    }

    @Test
    fun `S12b geaenderte konfig schneidet und emittiert entsprechend ohne doppeltext`() {
        // chunkMs=500, hypothesisMinIntervalMs=1000: Grenzen alle 500 ms,
        // Emissionen hoechstens alle 1000 ms — keine doppelte Ausgabe über
        // Chunk-Grenzen (Emissionszeitpunkte sind strikt monoton steigend
        // und paarweise >= Intervall entfernt).
        val cfg = AsrThreadConfig(intraOpThreads = 4, interOpThreads = 1,
            chunkMs = 500, hypothesisMinIntervalMs = 1000)
        assertEquals(listOf(8000, 16000, 24000), cfg.chunkBoundaries(totalSamples = 24000))
        val ticks = (1..10).map { it * 500 } // 10 Ticks je 500 ms
        val emissions = cfg.hypothesisEmissionTimes(ticks)
        assertEquals(listOf(500, 1500, 2500, 3500, 4500), emissions)
        // Invariante: keine zwei Emissionen in demselben Chunk-Fenster
        var lastChunk = -1
        for (e in emissions) {
            val chunkIdx = (e - 1) / 500
            assertTrue("Doppel-Emission in Chunk $chunkIdx", chunkIdx != lastChunk)
            lastChunk = chunkIdx
        }
    }

    // ---- S12-01-FIX: Geraete-RAM statt Java-Heap ----

    @Test
    fun `S12-01 ram-schwelle ist echte spezifikation kein heap-durchblicker`() {
        // Kleiner GERAETE-RAM (384 MB, z. B. altes Einsteigergert) => Reduktion auf 2
        assertEquals(2, AsrThreadConfig.forDevice(cores = 8, ramMb = 384).intraOpThreads)
        // Unbekannt (0) => KEINE Reduktion: 4 auf normalen Geraeten
        assertEquals(4, AsrThreadConfig.forDevice(cores = 8, ramMb = 0).intraOpThreads)
        // Modernes Flaggschiff (<device>, 16 GB) => 4 (nicht 6: bis 8 Kerne Formel)
        assertEquals(4, AsrThreadConfig.forDevice(cores = 8, ramMb = 15_936).intraOpThreads)
        // Heap-Werte (256-512 MB) waeren hier NICHT uebergeben: die Schwelle 1..2047
        // trifft nur echten Kleinst-RAM. Produktion setzt deviceRamMb aus ActivityManager.
    }

    @Test
    fun `S12-01 produktionsquelle ist deviceRamMb nie Runtime maxMemory`() {
        // Der Service setzt deviceRamMb aus ActivityManager.totalMem (Fake hier),
        // OnnxSessions und die Diagnose lesen NUR AsrThreadConfig.deviceRamMb —
        // der Java-Heap (maxMemory) kommt an keiner Stelle in forDevice().
        AsrThreadConfig.deviceRamMb = 15_936L // z. B. ActivityManager.totalMem 16 GB
        assertEquals(4, AsrThreadConfig.forDevice(cores = 8, ramMb = AsrThreadConfig.deviceRamMb).intraOpThreads)
        // Kleines Geraet: ActivityManager.totalMem 384 MB
        AsrThreadConfig.deviceRamMb = 384L
        assertEquals(2, AsrThreadConfig.forDevice(cores = 8, ramMb = AsrThreadConfig.deviceRamMb).intraOpThreads)
        // Unbekannt (Initialwert): keine Reduktion
        AsrThreadConfig.deviceRamMb = 0L
        assertEquals(4, AsrThreadConfig.forDevice(cores = 8, ramMb = AsrThreadConfig.deviceRamMb).intraOpThreads)
    }

    @Test fun `V12 controller-guard entspricht der config-intervall-quelle`() {
        // Verhalten bei Defaults identisch: MIN_AUDIO_SECONDS_PARTIALS (2.0 s)
        // kommt jetzt aus AsrThreadConfig.DEFAULT.hypothesisMinIntervalMs.
        assertEquals(
            2.0,
            de.voicekeyboard.dictation.DictationController.MIN_AUDIO_SECONDS_PARTIALS,
            1e-9,
        )
        assertEquals(
            de.voicekeyboard.dictation.DictationController.MIN_AUDIO_SECONDS_PARTIALS,
            AsrThreadConfig.DEFAULT.hypothesisMinIntervalMs / 1000.0,
            1e-9,
        )
    }
}
