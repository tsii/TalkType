package de.voicekeyboard.ime

import de.voicekeyboard.asr.AsrEngine
import de.voicekeyboard.asr.Hypothesis
import de.voicekeyboard.audio.FakeWavAudioSource
import de.voicekeyboard.audio.PcmWindow
import de.voicekeyboard.dictation.DictationState
import de.voicekeyboard.dictation.StopOutcome
import de.voicekeyboard.editor.EditorSink
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 * F30 (docs/EMULATOR_DIKTAT_HARNESS.md, Option C): Vier deterministische
 * Abbruch-Szenarien mit der Debug-only [FakeWavAudioSource] (debug-Quellordner,
 * daher steht dieser Test in testDebug, nicht in test — testReleaseUnitTest
 * dürfte die Debug-Klasse nicht referenzieren) und dem ECHTEN
 * DictationCoordinator; die Engine ist ein Test-Double (kein ONNX, kein
 * Android-Audio).
 *
 * Zeit: virtuelle ms-Uhr, die Quelle UND Coordinator (nowMs) teilen. Der
 * Test-Thread "pumpt" die Uhr; die Quelle liefert 10-ms-Blöcke ab ihrem
 * Fälligkeitsdatum — KEIN Thread.sleep im Rhythmus.
 *
 * Hinweis (ehrlich): Die Hypothesen-Intervall-Gate (2 s) im Controller nutzt
 * die Echtzeit-nanoTime (nicht über den Coordinator injizierbar) — Szenario (a)
 * pumpt daher ~4 s REALZEIT, damit ≥ 2 Intervalle deterministisch überschritten
 * werden; die restlichen Szenarien brauchen keine Partials.
 */
class FakeAudioScenarioTest {

    /** Virtuelle ms-Uhr (Start != 0: 0 ist das Coordinator-Sentinel "nie gestartet"). */
    private class VirtualClock(startMs: Long = 1_000_000L) {
        private val lock = Any()
        @Volatile private var t = startMs
        fun now(): Long = synchronized(lock) { t }
        fun advanceTo(targetMs: Long) {
            synchronized(lock) { if (targetMs > t) t = targetMs }
        }
    }

    /** Fake-Engine: zählt Decodes, liefert konstant Text (keine Inhalte relevant). */
    private class FakeAsrEngine : AsrEngine {
        val transcribeCalls = AtomicInteger(0)
        override fun load(modelDir: File) {}
        override suspend fun transcribe(window: PcmWindow, sessionId: Long, runId: Long): Hypothesis {
            transcribeCalls.incrementAndGet()
            val text = "x".repeat((window.audioSeconds * 3).toInt().coerceAtLeast(1))
            return Hypothesis(
                IntArray(1), text, IntArray(1), sessionId, runId,
                window.samples.size, incomplete = false,
            )
        }

        override fun close() {}
    }

    private class FakeSink : EditorSink {
        override fun replaceBeforeCursor(replaceLength: Int, newText: String) {}
        override fun textBeforeCursor(maxChars: Int): CharSequence? = null
        override fun ownLength(): Int = 0
    }

    private fun wav(): File {
        val candidates = listOf(
            File("testdata/de_smoke.wav"),
            File("../testdata/de_smoke.wav"),
            File(System.getProperty("user.dir"), "testdata/de_smoke.wav"),
        )
        return candidates.firstOrNull { it.isFile }
            ?: error("de_smoke.wav nicht gefunden (user.dir=${System.getProperty("user.dir")})")
    }

    /** Virtuelle Zeit in Schritten pumpt (Realzeit-Verzögerung lässt die
     *  Capture-/Consumer-Threads laufen; der Rhythmus bleibt virtual). */
    private suspend fun VirtualClock.pumpTo(targetMs: Long, stepMs: Long, realDelayMs: Long) {
        while (now() < targetMs) {
            advanceTo(now() + stepMs)
            delay(realDelayMs)
        }
    }

    private fun newScope() = CoroutineScope(Dispatchers.Default)

    /** Minimaler s16le-mono-WAV-Writer (44-Byte-Header + data) fuer die
     *  Stille-/Misch-Testdateien (Regel 9: Testdateien lokal, keine Inhalte geloggt). */
    private fun writeWav(samples: ShortArray): File {
        val data = ByteArray(samples.size * 2)
        for (i in samples.indices) {
            // Int-Op (die Short-Bitop-Overload ist in dieser Kotlin-Umgebung
            // nicht auflösbar — empirisch an Compile-Fehlern belegt):
            data[i * 2] = (samples[i].toInt() and 0xff).toByte()
            data[i * 2 + 1] = (samples[i].toInt() shr 8).toByte()
        }
        val file = File.createTempFile("vk_f31", ".wav")
        file.outputStream().use { out ->
            fun le32(v: Int) {
                out.write(v and 0xff); out.write((v shr 8) and 0xff)
                out.write((v shr 16) and 0xff); out.write((v shr 24) and 0xff)
            }
            out.write("RIFF".toByteArray()); le32(36 + data.size)
            out.write("WAVE".toByteArray())
            out.write("fmt ".toByteArray()); le32(16)
            out.write(1); out.write(0)             // PCM
            out.write(1); out.write(0)             // mono
            out.write(16); out.write(0); out.write(0); out.write(0) // 16000 Hz
            le32(32000)                             // ByteRate
            out.write(2); out.write(0)              // BlockAlign
            out.write(16); out.write(0)             // Bittiefe
            out.write("data".toByteArray()); le32(data.size)
            out.write(data)
        }
        return file
    }

    /** Stille WAV: Nullsamples (Blöcke laufen, RMS = 0 — der F31-Fall). */
    private fun silentWav(seconds: Int): File =
        writeWav(ShortArray(seconds * 16_000))

    /** Misch-WAV aus Segmenten (ms, laut?). "Laut" = Sinus 200 Hz, Amplitude
     *  12000 ≈ −9 dBFS Peak → Block-RMS ≈ −12 dBFS, weit über der −40-Schwelle. */
    private fun segmentWav(segments: List<Pair<Int, Boolean>>): File {
        val n = segments.sumOf { it.first } * 16
        val s = ShortArray(n)
        val omega = 2.0 * Math.PI * 200.0 / 16_000.0
        var offsetMs = 0
        for ((ms, loud) in segments) {
            if (loud) {
                for (i in offsetMs * 16 until (offsetMs + ms) * 16) {
                    s[i] = (Math.sin(omega * i) * 12_000).toInt().toShort()
                }
            }
            offsetMs += ms
        }
        return writeWav(s)
    }

    /** Mischung: [silentMs] Stille, dann [loudMs] Sinus (bestehende F31-Tests). */
    private fun mixWav(silentMs: Int, loudMs: Int): File =
        segmentWav(listOf(silentMs to false, loudMs to true))

    /** Wartet (nur im Test!) bis der asynchrone stop()-Worker IDLE erreicht. */
    private fun awaitIdle(c: DictationCoordinator, timeoutMs: Long = 5000) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (c.state != DictationState.IDLE && System.currentTimeMillis() < deadline) {
            Thread.sleep(10)
        }
        assertEquals("stop()-Worker muss nach IDLE enden", DictationState.IDLE, c.state)
    }

    // (a) Durchgehend 10 s: kein Stall, cap_blocks > 0, hyp wächst; UP → final.
    @Test
    fun `a durchgehend 10 s - kein Stall, cap_blocks positiv, hyp waechst`() = runBlocking {
        val clock = VirtualClock()
        val t0 = clock.now()
        val src = FakeWavAudioSource(wav(), nowMs = { clock.now() })
        val engine = FakeAsrEngine()
        val scope = newScope()
        val c = DictationCoordinator(scope, { src }, { engine }, FakeSink(), nowMs = { clock.now() })
        assertTrue("start muss gelingen", c.start())
        // 10 s Virtualzeit in ~4 s Realzeit (2-s-Intervall-Gate läuft auf Echtzeit).
        clock.pumpTo(t0 + 10_000L, stepMs = 25L, realDelayMs = 10L)
        assertEquals("vor dem Stop keine Abbruch-Ursache (kein ERR_STALL)", "-", c.lastAbortReason)
        assertTrue("cap_blocks > 0 (sind ${c.capBlocks()})", c.capBlocks() > 500)
        assertTrue("hyp wächst (sind ${c.hypCount})", c.hypCount >= 2)
        c.stop() // "UP" nach 10 s
        awaitIdle(c)
        assertEquals("Nutzerfall: finaler Stop", "final", c.lastAbortReason)
        scope.cancel()
    }

    // (b) Stille ab Sessionstart: nach 3 s Watchdog → ERR_STALL "no_audio",
    //     Session beendet, 0 empfangene Blöcke.
    @Test
    fun `b Stille ab Start - nach 3 s ERR_STALL no_audio und Session beendet`() = runBlocking {
        val clock = VirtualClock()
        val t0 = clock.now()
        val src = FakeWavAudioSource(wav(), silenceFromMs = t0, nowMs = { clock.now() })
        val scope = newScope()
        val c = DictationCoordinator(scope, { src }, { FakeAsrEngine() }, FakeSink(), nowMs = { clock.now() })
        assertTrue("start muss gelingen", c.start())
        clock.pumpTo(t0 + 3_400L, stepMs = 100L, realDelayMs = 10L)
        assertFalse("Session muss nach dem Watchdog beendet sein", c.isActive)
        assertEquals("Watchdog-Grund (stall/no_audio-Pfad)", "audio_error:stall", c.lastAbortReason)
        assertEquals("in Stille keine Blöcke empfangen", 0, c.capBlocks())
        scope.cancel()
    }

    // (c) Aussetzer 3,5 s (1000..4500 ms): Watchdog feuert GENAU EINMAL —
    //     im Gap, aber VOR dem Gap-Ende (die Source hätte ab 4500 weiter-
    //     geliefert; die Session ist dann schon tot).
    @Test
    fun `c Aussetzer 3 5 s - ERR_STALL genau einmal, noch vor Gap-Ende`() = runBlocking {
        val clock = VirtualClock()
        val t0 = clock.now()
        val src = FakeWavAudioSource(
            wav(), gapFromMs = t0 + 1_000L, gapToMs = t0 + 4_500L, nowMs = { clock.now() },
        )
        val scope = newScope()
        val c = DictationCoordinator(scope, { src }, { FakeAsrEngine() }, FakeSink(), nowMs = { clock.now() })
        assertTrue("start muss gelingen", c.start())
        clock.pumpTo(t0 + 900L, stepMs = 25L, realDelayMs = 5L)
        assertTrue("vor dem Gap liefern Blöcke (sind ${c.capBlocks()})", c.capBlocks() >= 80)
        assertEquals("vor dem Gap noch kein Abbruch", "-", c.lastAbortReason)
        // Gap-Phase: Stopp-Limit 3000 → Feuern zwischen t0+4000 und t0+4500.
        clock.pumpTo(t0 + 4_200L, stepMs = 100L, realDelayMs = 10L)
        assertFalse("Session im Gap beendet (vor Gap-Ende 4500)", c.isActive)
        assertEquals("audio_error:stall", c.lastAbortReason)
        // "Genau einmal": der Capture-Loop ist zurückgekehrt — weiterspumpen
        // ändert nichts (kein zweites Feuern, kein Weiterlaufen).
        clock.pumpTo(t0 + 6_000L, stepMs = 100L, realDelayMs = 5L)
        assertEquals("zweites Feuern unmöglich", "audio_error:stall", c.lastAbortReason)
        assertFalse("Session bleibt beendet", c.isActive)
        scope.cancel()
    }

    // (d) Ende nach 1,2 s + UP: der NUTZERFALL aus der Analyse — abort_reason
    //     "final", audio_s ≈ 1,2 s.
    @Test
    fun `d Ende nach 1 2 s - abort_reason final und audio_s rund 1 2 (Nutzerfall)`() = runBlocking {
        val clock = VirtualClock()
        val t0 = clock.now()
        val src = FakeWavAudioSource(wav(), stopAfterMs = t0 + 1_200L, nowMs = { clock.now() })
        val scope = newScope()
        val c = DictationCoordinator(scope, { src }, { FakeAsrEngine() }, FakeSink(), nowMs = { clock.now() })
        assertTrue("start muss gelingen", c.start())
        clock.pumpTo(t0 + 1_200L, stepMs = 20L, realDelayMs = 5L)
        c.stop() // "UP" — 1,2 s gehalten, wie im Export
        awaitIdle(c)
        assertEquals("finaler Stop (Nutzerfall)", "final", c.lastAbortReason)
        assertTrue("audio_s ≈ 1,2 (ist ${c.audioMs} ms)", c.audioMs in 1_150L..1_250L)
        assertTrue("Finale lief durch", c.lastStopOutcome is StopOutcome.FinalRan)
        scope.cancel()
    }

    // (e) F31: Blöcke laufen durchgehend, sind aber STILL (−56-dBFS-Nutzerfall):
    //     nach 3000 ms genau EIN ERR_SILENT, Session beendet, cap_blocks > 0
    //     (im Gegensatz zu ERR_STALL: hier KOMMEN Blöcke).
    @Test
    fun `e durchgehend still - nach 3 s genau ein ERR_SILENT, Session beendet`() = runBlocking {
        val clock = VirtualClock()
        val t0 = clock.now()
        val src = FakeWavAudioSource(silentWav(8), nowMs = { clock.now() })
        val scope = newScope()
        val c = DictationCoordinator(scope, { src }, { FakeAsrEngine() }, FakeSink(), nowMs = { clock.now() })
        assertTrue("start muss gelingen", c.start())
        clock.pumpTo(t0 + 3_400L, stepMs = 100L, realDelayMs = 10L)
        assertFalse("Session muss nach dem Stille-Watchdog beendet sein", c.isActive)
        assertEquals("Stille-Watchdog-Grund", "audio_error:silence", c.lastAbortReason)
        assertTrue("Blöcke WAREN da (Stille ≠ Stall, sind ${c.capBlocks()})", c.capBlocks() > 0)
        // Genau einmal: Capture-Loop ist zurückgekehrt — Weiterspumpen ändert nichts.
        clock.pumpTo(t0 + 6_000L, stepMs = 100L, realDelayMs = 5L)
        assertEquals("zweites Feuern unmöglich", "audio_error:silence", c.lastAbortReason)
        assertFalse("Session bleibt beendet", c.isActive)
        scope.cancel()
    }

    // (f) F31: 1,5 s Stille, dann LAUT (Sinus): kein ERR_SILENT — die ersten
    //     1,5 s sind unter der 3-s-Grenze, danach hält der laute Stream die
    //     Stille-Uhr zurück. loud_s zählt die laute Zeit mit.
    @Test
    fun `f still dann laut - kein ERR_SILENT, loud_s waechst`() = runBlocking {
        val clock = VirtualClock()
        val t0 = clock.now()
        val src = FakeWavAudioSource(mixWav(silentMs = 1_500, loudMs = 5_000), nowMs = { clock.now() })
        val scope = newScope()
        val c = DictationCoordinator(scope, { src }, { FakeAsrEngine() }, FakeSink(), nowMs = { clock.now() })
        assertTrue("start muss gelingen", c.start())
        clock.pumpTo(t0 + 1_500L, stepMs = 50L, realDelayMs = 10L)
        assertEquals("in den ersten 1,5 s Stille: noch kein Abbruch (< 3 s)", "-", c.lastAbortReason)
        clock.pumpTo(t0 + 6_500L, stepMs = 100L, realDelayMs = 20L)
        assertTrue("Session läuft weiter (laute Blöcke halten die Stille-Uhr)", c.isActive)
        assertEquals("kein ERR_SILENT nach dem Signal", "-", c.lastAbortReason)
        assertTrue("loud_s zählt die laute Zeit (ist ${c.loudS()} s)", c.loudS() in 3.0..5.2)
        c.stop()
        awaitIdle(c)
        assertEquals("finaler Stop", "final", c.lastAbortReason)
        scope.cancel()
    }

    // (g) F31-Abgrenzung: KEINE Blöcke (Quelle tot) → weiterhin ERR_STALL
    //     (nicht ERR_SILENT) — die capBlocks > 0-Bedingung trennt die Fälle.
    @Test
    fun `g keine Blcke - weiterhin ERR_STALL, kein ERR_SILENT`() = runBlocking {
        val clock = VirtualClock()
        val t0 = clock.now()
        val src = FakeWavAudioSource(wav(), silenceFromMs = t0, nowMs = { clock.now() })
        val scope = newScope()
        val c = DictationCoordinator(scope, { src }, { FakeAsrEngine() }, FakeSink(), nowMs = { clock.now() })
        assertTrue("start muss gelingen", c.start())
        clock.pumpTo(t0 + 3_400L, stepMs = 100L, realDelayMs = 10L)
        assertFalse("Session beendet", c.isActive)
        assertEquals("ohne Blöcke bleibt es ERR_STALL", "audio_error:stall", c.lastAbortReason)
        assertEquals("keine Blöcke → keine Stille-Messung", 0, c.capBlocks())
        scope.cancel()
    }

    // (h) F31-Messung: durchgehend lauter Stream (Sinus) → loud_s ≈ Sendedauer.
    @Test
    fun `h lauter Stream - loud_s misst die laute Zeit`() = runBlocking {
        val clock = VirtualClock()
        val t0 = clock.now()
        val src = FakeWavAudioSource(mixWav(silentMs = 0, loudMs = 3_000), nowMs = { clock.now() })
        val scope = newScope()
        val c = DictationCoordinator(scope, { src }, { FakeAsrEngine() }, FakeSink(), nowMs = { clock.now() })
        assertTrue("start muss gelingen", c.start())
        clock.pumpTo(t0 + 3_000L, stepMs = 50L, realDelayMs = 5L)
        assertTrue("Blöcke liefen (sind ${c.capBlocks()})", c.capBlocks() >= 280)
        // Fälligkeits-Off-by-One: Block i ist AB sessionStart+i×10 fällig (inklusive)
        // → am Pump-Ende sind 301 Blöcke möglich (3,01 s); Band dahinter abgeleitet.
        assertTrue("loud_s ≈ 3 (ist ${c.loudS()} s)", c.loudS() in 2.9..3.05)
        c.stop()
        awaitIdle(c)
        scope.cancel()
    }

    // (i) F31b/REVIEW-P1 → F41/F43: 4 s LAUT, dann streamende Stille →
    //     KEIN ERR_SILENT (die Stille-ERKENNUNG startet nach dem ersten lauten
    //     Block nie — F31b bleibt), ABER die Aufnahme beendet sich nach
    //     TRAILING_SILENCE_MS = 2,5 s Pause AUTOMATISCH REGULÄR (finaler Decode,
    //     kein ERR_*) — vorher musste der Nutzer sie manuell beenden (F41-Repro
    //     Symptom 1: 30 s Stille, Session lief weiter, FGS ebenfalls).
    @Test
    fun `i 4 s laut dann 5 s Pause - auto-final nach 2,5 s Stille, kein ERR_SILENT`() = runBlocking {
        val clock = VirtualClock()
        val t0 = clock.now()
        val src = FakeWavAudioSource(segmentWav(listOf(4_000 to true, 5_000 to false)), nowMs = { clock.now() })
        val scope = newScope()
        val c = DictationCoordinator(scope, { src }, { FakeAsrEngine() }, FakeSink(), nowMs = { clock.now() })
        assertTrue("start muss gelingen", c.start())
        clock.pumpTo(t0 + 4_000L, stepMs = 50L, realDelayMs = 10L)
        assertEquals("nach 4 s Laut: kein Abbruch", "-", c.lastAbortReason)
        clock.pumpTo(t0 + 6_400L, stepMs = 100L, realDelayMs = 20L)
        assertTrue("Pause < 2,5 s: Session läuft weiter (F43)", c.isActive)
        assertEquals("kein ERR_SILENT nach dem ersten lauten Block", "-", c.lastAbortReason)
        clock.pumpTo(t0 + 9_000L, stepMs = 100L, realDelayMs = 20L)
        awaitIdle(c)
        assertFalse("Pause ≥ 2,5 s: automatischer regulärer Abschluss (F43)", c.isActive)
        assertEquals("auto-final, kein ERR_SILENT/ERR_STALL", "final", c.lastAbortReason)
        assertTrue("auto-final: Outcome FinalRan", c.lastStopOutcome is StopOutcome.FinalRan)
        scope.cancel()
    }

    // (j) F31b→F41/F43: 2,5 s still, 0,5 s LAUT, dann 5 s still → KEIN ERR_SILENT
    //     (das everLoud-FLAG gate-ete und gate-et die Stille-ERKENNUNG weiter
    //     dauerhaft), aber die streamende Stille beendet die Session jetzt
    //     REGULÄR nach 2,5 s (F43-auto-final) statt ewig weiterzulaufen.
    @Test
    fun `j still laut still - kein ERR_SILENT, Stille endet automatisch regulaer (F43)`() = runBlocking {
        val clock = VirtualClock()
        val t0 = clock.now()
        val src = FakeWavAudioSource(
            segmentWav(listOf(2_500 to false, 500 to true, 5_000 to false)), nowMs = { clock.now() },
        )
        val scope = newScope()
        val c = DictationCoordinator(scope, { src }, { FakeAsrEngine() }, FakeSink(), nowMs = { clock.now() })
        assertTrue("start muss gelingen", c.start())
        clock.pumpTo(t0 + 3_000L, stepMs = 100L, realDelayMs = 10L)
        assertTrue("an der 3-s-Grenze: Session läuft (lauter Block bei 2,5 s)", c.isActive)
        clock.pumpTo(t0 + 5_400L, stepMs = 100L, realDelayMs = 20L)
        assertTrue("Stille < 2,5 s: Session läuft weiter (F43)", c.isActive)
        clock.pumpTo(t0 + 8_000L, stepMs = 100L, realDelayMs = 20L)
        awaitIdle(c)
        assertFalse("Stille ≥ 2,5 s: automatischer regulärer Abschluss (F43)", c.isActive)
        assertEquals("kein ERR_SILENT (Flag-gated) — reguläres auto-final", "final", c.lastAbortReason)
        assertTrue("auto-final: Outcome FinalRan", c.lastStopOutcome is StopOutcome.FinalRan)
        scope.cancel()
    }

    // (k) F31b-Timing: durchgehend still (NIE laut) — UNTER der 3-s-Grenze läuft
    //     die Session weiter, DARNACH genau ein ERR_SILENT (Grenze bei ~3 s).
    @Test
    fun `k nie laut - unter 3 s laeuft die Session, danach genau ein ERR_SILENT`() = runBlocking {
        val clock = VirtualClock()
        val t0 = clock.now()
        val src = FakeWavAudioSource(silentWav(8), nowMs = { clock.now() })
        val scope = newScope()
        val c = DictationCoordinator(scope, { src }, { FakeAsrEngine() }, FakeSink(), nowMs = { clock.now() })
        assertTrue("start muss gelingen", c.start())
        clock.pumpTo(t0 + 2_900L, stepMs = 50L, realDelayMs = 5L)
        assertTrue("unter der 3-s-Grenze: Session läuft weiter", c.isActive)
        assertEquals("unter der Grenze: kein Abbruch", "-", c.lastAbortReason)
        clock.pumpTo(t0 + 3_400L, stepMs = 100L, realDelayMs = 10L)
        assertFalse("über der Grenze: Session beendet", c.isActive)
        assertEquals("ERR_SILENT (nie laut, Blöcke laufen)", "audio_error:silence", c.lastAbortReason)
        // Genau einmal:
        clock.pumpTo(t0 + 5_000L, stepMs = 100L, realDelayMs = 5L)
        assertEquals("zweites Feuern unmöglich", "audio_error:silence", c.lastAbortReason)
        scope.cancel()
    }
}
