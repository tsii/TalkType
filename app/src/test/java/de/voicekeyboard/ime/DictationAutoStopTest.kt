package de.voicekeyboard.ime

import de.voicekeyboard.asr.AsrEngine
import de.voicekeyboard.asr.Hypothesis
import de.voicekeyboard.audio.AudioSource
import de.voicekeyboard.audio.PcmBlock
import de.voicekeyboard.audio.PcmWindow
import de.voicekeyboard.dictation.DictationState
import de.voicekeyboard.dictation.StopOutcome
import de.voicekeyboard.editor.EditorSink
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * F43/F44 (F41-Repro /tmp/vk_repro_f41.txt): Kontrollpfad-Tests für den
 * automatischen Stopp nach Sprechpause und den FGS-Kill-Pfad-Vertrag.
 *
 *  (1) laut → 2,5 s Stille → AUTOMATISCHER regulärer Abschluss (final, kein ERR_*),
 *      finales Fenster umfasst das ganze Loud-Audio (keine Zeichen verloren);
 *  (2) laut → 1 s Pause → laut: läuft WEITER (kein Auto-Stopp, keine Fehler);
 *  (3) nie laut → ERR_SILENT nach dem 3-s-Startfenster (Bestandsschutz F31b),
 *      kill path feuert onAborted (FGS-Stop-Trigger, F44);
 *  (4) Kill-Pfade des Consumers (Engine-Fehler, OOM) feuern onAborted — der
 *      Service stoppt darüber den Mikro-FGS (fgs_stopped_by), vorher Leak ~58 s.
 *
 * Zeitmodell: virtuelle injizierbare Uhr (Coordinator-nowMs), 320-Sample-Blöcke
 * à 20 ms, self-paced (FakeWavAudioSource-Muster) — KEIN Thread.sleep im
 * Rhythmus. Regel 9: nur Zahlen/Codes, nie Audio-/Textinhalte.
 *
 * Robolectric (F43-Freigabe): Der OOM-Kill-Pfad des Coordinators ruft
 * android.util.Log.w — im plain-JVM-UnitTest nicht gemockt (die RuntimeException
 * fliegt aus dem Catch-Block, bevor notifyAborted läuft). Robolectric mockt das
 * Framework (Projekt-Standard, wie LayoutCompletenessTest).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DictationAutoStopTest {

    /** Virtuelle ms-Uhr; Start ≠ 0 (0 = lastBlockAtMs-Sentinel „nie gestartet"). */
    private class VClock {
        val now = AtomicLong(1_000_000L)
    }

    /**
     * Skript-Quelle: [script] = (Dauer ms, laut?)-Segmente; danach liefert sie
     * bei [silentForever] weitere NULL-Blöcke (Stille wie AudioRecord — Watchdog-
     * Material), sonst endet die Lieferung (Uhr steht, kein künstlicher Stall).
     * Laut = Amplitude 12 000 (RMS ≈ 0,37 ≥ SILENCE_RMS 0,01), still = exakt 0.
     */
    private class ScriptSource(
        private val clock: VClock,
        private val script: List<Pair<Long, Boolean>>,
        private val silentForever: Boolean,
    ) : AudioSource {
        private val blockMs = 20L
        private val blockSamples = 320
        private val totalMs = script.sumOf { it.first }
        private var base = -1L
        private var nextIdx = 0L
        @Volatile private var released = false

        private fun loudAt(ms: Long): Boolean {
            var t = 0L
            for ((d, loud) in script) {
                t += d
                if (ms < t) return loud
            }
            return false
        }

        override fun start(sessionId: Long): Boolean {
            base = clock.now.get()
            return true
        }

        override fun read(dst: ShortArray, timeoutMs: Int): Int {
            if (released) return -1
            val t = nextIdx * blockMs
            if (t >= totalMs && !silentForever) {
                Thread.sleep(1) // Skript-Ende: kein Block, virtuelle Uhr steht
                return 0
            }
            val due = base + t
            while (true) { // self-paced: Uhr auf das Fälligkeitsdatum bringen
                val cur = clock.now.get()
                if (cur >= due || clock.now.compareAndSet(cur, due)) break
            }
            val amp: Short = if (loudAt(t)) 12_000 else 0
            for (j in 0 until blockSamples) dst[j] = amp
            nextIdx++
            return blockSamples
        }

        override fun stopAndDrain(): List<PcmBlock> = emptyList()
        override fun release() {
            released = true
        }
    }

    /** Stub-Engine: Zähler + Fensterlänge des letzten Decodes (nur Zahlen, Regel 9);
     *  [failWith] wirft beim nächsten transcribe (Kill-Pfad-Material). */
    private class RecordingEngine : AsrEngine {
        var calls = 0
            private set
        var lastWindowSamples = 0
            private set
        var failWith: Throwable? = null

        override fun load(modelDir: File) {}

        override suspend fun transcribe(
            window: PcmWindow, sessionId: Long, runId: Long,
        ): Hypothesis {
            failWith?.let { throw it }
            calls++
            lastWindowSamples = window.samples.size
            return Hypothesis(
                IntArray(0), "k", IntArray(0), sessionId, runId,
                window.samples.size, incomplete = false,
            )
        }

        override fun close() {}
    }

    /** Stiller Editor-Sink (Inhalte irrelevant, Regel 9). */
    private class SilentSink : EditorSink {
        override fun replaceBeforeCursor(replaceLength: Int, newText: String) {}
        override fun textBeforeCursor(maxChars: Int): CharSequence? = null
        override fun ownLength(): Int = 0
    }

    // ---- (1) laut → 2,5 s Stille → automatischer regulärer Abschluss ----

    @Test
    fun `(1) laut dann 2,5 s Stille beendet automatisch mit regulaerem Final`() {
        val clock = VClock()
        val src = ScriptSource(clock, listOf(3_000L to true), silentForever = true)
        val engine = RecordingEngine()
        val aborts = CopyOnWriteArrayList<String>()
        val scope = CoroutineScope(Dispatchers.Default)
        val c = DictationCoordinator(
            scope, { src }, { engine }, SilentSink(), nowMs = { clock.now.get() },
        )
        c.onAborted = { aborts.add(it) } // F45: Auto-Stop darf KEIN Abort sein
        try {
            assertTrue("start muss gelingen", c.start())
            awaitReason(c, "final")
            assertTrue(
                "auto-final: Outcome muss FinalRan sein (ist ${c.lastStopOutcome})",
                c.lastStopOutcome is StopOutcome.FinalRan,
            )
            assertTrue(
                "F45: Auto-Stop ist ein REGULÄRES Finale — onAborted darf NICHT feuern (ist $aborts)",
                aborts.isEmpty(),
            )
            assertNull("auto-final ist KEIN Fehlerpfad: lastError muss null sein", c.lastError)
            assertEquals(DictationState.IDLE, c.state)
            assertFalse("Session endet", c.isActive)
            assertTrue("finaler Decode lief (engine_calls=${engine.calls})", engine.calls >= 1)
            assertTrue(
                "keine gesprochenen Zeichen verloren: finales Fenster >= 3 s Loud-Audio " +
                    "(ist ${engine.lastWindowSamples} Samples)",
                engine.lastWindowSamples >= 3 * 16_000,
            )
        } finally {
            scope.cancel()
        }
    }

    // ---- (2) laut → 1 s Pause → laut: läuft weiter ----

    @Test
    fun `(2) laut 1 s Pause laut laeuft weiter - kein Auto-Stopp`() {
        val clock = VClock()
        val src = ScriptSource(
            clock, listOf(3_000L to true, 1_000L to false, 2_000L to true),
            silentForever = false,
        )
        val engine = RecordingEngine()
        val scope = CoroutineScope(Dispatchers.Default)
        val c = DictationCoordinator(
            scope, { src }, { engine }, SilentSink(), nowMs = { clock.now.get() },
        )
        try {
            assertTrue(c.start())
            // Ganzes Skript (6 s) durchfüttern: audio_s misst die gefütterten Ticks.
            val deadline = System.currentTimeMillis() + 15_000
            while (c.audioSeconds < 5.99 && System.currentTimeMillis() < deadline) {
                Thread.sleep(5)
            }
            assertEquals("Skript vollständig gefüttert", 6.0, c.audioSeconds, 0.01)
            assertEquals("(f) kein Auto-Stopp: abort_reason bleibt offen", "-", c.lastAbortReason)
            assertNull("kein ERR_STALL/ERR_SILENT", c.lastError)
            assertTrue("Session läuft weiter (active)", c.isActive)
            assertEquals(DictationState.RECORDING, c.state)
            assertTrue("(a) Partials gelaufen (hyp_count=${c.hypCount})", c.hypCount >= 1)
            assertTrue("kein Finale gelaufen", c.lastStopOutcome is StopOutcome.IdleNoOp)
        } finally {
            c.abort("test_end")
            scope.cancel()
        }
    }

    // ---- (3) nie laut → ERR_SILENT nach 3-s-Startfenster (Bestandsschutz F31b) ----

    @Test
    fun `(3) nie laut bleibt ERR_SILENT nach 3 s - und feuert onAborted (F44)`() {
        val clock = VClock()
        val src = ScriptSource(clock, emptyList(), silentForever = true) // nur Stille
        val engine = RecordingEngine()
        val aborts = CopyOnWriteArrayList<String>()
        val scope = CoroutineScope(Dispatchers.Default)
        val c = DictationCoordinator(
            scope, { src }, { engine }, SilentSink(), nowMs = { clock.now.get() },
        )
        c.onAborted = { aborts.add(it) }
        try {
            assertTrue(c.start())
            awaitReason(c, "audio_error:silence")
            // F56-Lauf-Fund (F50b-Familie): abort() schreibt lastAbortReason
            // FIRST — der Zustandsübergang (controller.abort()) läuft danach im
            // selben Aufruf; unter Suite-Last kehrte der Grund-Poll zurück, bevor
            // IDLE stand. Auf den TERMINALEN Zustand pollen (Erwartung unveraendert):
            val stateDeadline = System.currentTimeMillis() + 15_000
            while (c.state != DictationState.IDLE && System.currentTimeMillis() < stateDeadline) {
                Thread.sleep(5)
            }
            assertEquals(DictationState.IDLE, c.state)
            assertEquals("silence:no_signal", c.lastError)
            assertFalse(c.isActive)
            assertTrue(
                "F44: Kill-Pfad feuert onAborted (FGS-Stop-Trigger), ist $aborts",
                aborts.contains("audio_error:silence"),
            )
        } finally {
            scope.cancel()
        }
    }

    // ---- (4) F44: Kill-Pfade des Consumers feuern onAborted (FGS-Stop-Trigger) ----
    @Test
    fun `(4a) Engine-Fehler-Killpfad feuert onAborted mit audio_error-Code`() {
        val clock = VClock()
        val src = ScriptSource(clock, listOf(4_000L to true), silentForever = false)
        val engine = RecordingEngine().apply { failWith = IllegalStateException("boom") }
        val aborts = CopyOnWriteArrayList<String>()
        val scope = CoroutineScope(Dispatchers.Default)
        val c = DictationCoordinator(
            scope, { src }, { engine }, SilentSink(), nowMs = { clock.now.get() },
        )
        c.onAborted = { aborts.add(it) }
        try {
            assertTrue(c.start())
            awaitReason(c, "audio_error:asr_partial_error")
            assertEquals(DictationState.IDLE, c.state)
            assertFalse("kill path beendet die Session", c.isActive)
            assertTrue(
                "F44: onAborted gefeuert (FGS-Stop-Trigger), ist $aborts",
                aborts.contains("audio_error:asr_partial_error"),
            )
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `(4b) OOM-Killpfad feuert onAborted mit oom-Code`() {
        val clock = VClock()
        val src = ScriptSource(clock, listOf(4_000L to true), silentForever = false)
        val engine = RecordingEngine().apply { failWith = OutOfMemoryError() }
        val aborts = CopyOnWriteArrayList<String>()
        val scope = CoroutineScope(Dispatchers.Default)
        val c = DictationCoordinator(
            scope, { src }, { engine }, SilentSink(), nowMs = { clock.now.get() },
        )
        c.onAborted = { aborts.add(it) }
        try {
            assertTrue(c.start())
            awaitContains(aborts, "oom")
            assertTrue(
                "OOM-Pfad sichtbar (lastError=${c.lastError})",
                c.lastError?.startsWith("oom:") == true,
            )
            // AP7-W1-Lauf-Fund (F50b-Familie): der OOM-Catch setzt lastStopOutcome
            // NACH notifyAborted — der Callback-Poll kehrt zurück, bevor die
            // Zuweisung lief (IdleNoOp-Sentinel, nie null). Poll auf den
            // TERMINALEN Zustand, dann asserten (Erwartung unveraendert):
            val deadline = System.currentTimeMillis() + 15_000
            while (c.lastStopOutcome !is StopOutcome.NoFinal &&
                System.currentTimeMillis() < deadline
            ) {
                Thread.sleep(5)
            }
            assertTrue(
                "OOM-Pfad meldet NoFinal (ist ${c.lastStopOutcome})",
                c.lastStopOutcome is StopOutcome.NoFinal,
            )
            assertFalse(c.isActive)
            assertEquals(DictationState.IDLE, c.state)
        } finally {
            scope.cancel()
        }
    }

    // ---- F45 (Retest F41b, P1): FGS-Stop im Auto-Stop-Finale ----

    @Test
    fun `F45 Auto-Stop-Finale stoppt den FGS mit autostop - Nutzer-Stopp unberuehrt`() {
        // Läuft der FGS im onStopped-Finale NOCH, war es der Auto-Stop (kein
        // Nutzer-Stopp davor) → stopFgs("autostop"), fgs_stopped_by="autostop".
        assertEquals(
            "Auto-Stop: FGS läuft noch → Grund autostop",
            "autostop",
            fgsStopOnDictationStopped(fgsRunning = true),
        )
        // Nutzer-Stopp: stopFgs("stop") lief schon synchron → hier KEIN zweiter
        // Grund (kein Überschreiben von fgs_stopped_by).
        assertNull(
            "Nutzer-Stopp: FGS schon gestoppt, kein autostop-Grund",
            fgsStopOnDictationStopped(fgsRunning = false),
        )
    }

    // ---- F49 (PLAN_V0130 §1 A1): Hold-Gate für den F43-Auto-Stop ----

    @Test
    fun `(5) Hold-Gate true unterdrueckt Auto-Stop bis Quellen-Ende`() {
        val clock = VClock()
        // 3 s laut, dann 6 s streamende Stille (> 2,5 s!) — Skript-Ende bei 9 s.
        val src = ScriptSource(clock, listOf(3_000L to true, 6_000L to false), silentForever = false)
        val engine = RecordingEngine()
        val scope = CoroutineScope(Dispatchers.Default)
        val c = DictationCoordinator(
            scope, { src }, { engine }, SilentSink(), nowMs = { clock.now.get() },
            autoStopSuppressed = { true }, // F49: Halte-Geste "noch nicht fertig"
        )
        try {
            assertTrue(c.start())
            // Skript komplett liefern lassen (4 Ticks à 2 s = 8 s gefüttert;
            // der 1-s-Rest kommt mit dem expliziten Stopp). 6 s Stille > 2,5 s
            // MUESSEN unterdrückt werden — kein Auto-Stop, kein ERR.
            val deadline = System.currentTimeMillis() + 15_000
            while (c.audioSeconds < 7.99 && System.currentTimeMillis() < deadline) {
                Thread.sleep(5)
            }
            assertTrue("Skript bis zum Ende geliefert (audio_s=${c.audioSeconds})", c.audioSeconds >= 7.99)
            assertTrue("Gate zu: KEIN Auto-Stop trotz 6 s Stille", c.isActive)
            assertEquals("kein abort", "-", c.lastAbortReason)
            assertNull("kein ERR trotz Stille > 2,5 s", c.lastError)
            c.stop() // regulärer Abschluss wie vom Nutzer
            awaitReason(c, "final")
            assertTrue("expliziter Stopp: FinalRan (ist ${c.lastStopOutcome})", c.lastStopOutcome is StopOutcome.FinalRan)
            assertTrue("Reste beim Stopp nicht verloren (audio_s=${c.audioSeconds})", c.audioSeconds >= 8.9)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `(6) Hold-Gate oeffnet wieder - Auto-Stop feuert nach Oeffnung`() {
        val clock = VClock()
        val src = ScriptSource(clock, listOf(3_000L to true), silentForever = true)
        val engine = RecordingEngine()
        val gate = AtomicBoolean(true)
        val scope = CoroutineScope(Dispatchers.Default)
        val c = DictationCoordinator(
            scope, { src }, { engine }, SilentSink(), nowMs = { clock.now.get() },
            autoStopSuppressed = { gate.get() },
        )
        try {
            assertTrue(c.start())
            // Stille > 2,5 s laufen lassen (Ticks füttern weiter) — Gate zu.
            val deadline = System.currentTimeMillis() + 15_000
            while (c.audioSeconds < 4.0 && System.currentTimeMillis() < deadline) {
                Thread.sleep(5)
            }
            assertTrue("unterdrückt: läuft trotz Stille > 2,5 s weiter", c.isActive)
            assertEquals("kein abort bei geschlossenem Gate", "-", c.lastAbortReason)
            gate.set(false) // F49-Toggle: Loslassen-Vorbereitung — Gate öffnet
            awaitReason(c, "final")
            assertTrue("nach Öffnung: FinalRan (ist ${c.lastStopOutcome})", c.lastStopOutcome is StopOutcome.FinalRan)
            assertNull("reguläres Finale, kein ERR", c.lastError)
        } finally {
            scope.cancel()
        }
    }

    // ---- Helfer (nur im Test gepollt; kein sleep im Produktions-Rhythmus) ----

    /** Wartet bis lastAbortReason == [expected] (Frist hart, kein Lockern). */
    private fun awaitReason(c: DictationCoordinator, expected: String, timeoutMs: Long = 15_000) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (c.lastAbortReason != expected && System.currentTimeMillis() < deadline) {
            Thread.sleep(5)
        }
        assertEquals("abort_reason (F43/F44)", expected, c.lastAbortReason)
    }

    /** Wartet bis [list] [expected] enthält (Kill-Pfad ohne eigenen abort-Grund). */
    private fun awaitContains(list: CopyOnWriteArrayList<String>, expected: String, timeoutMs: Long = 15_000) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!list.contains(expected) && System.currentTimeMillis() < deadline) {
            Thread.sleep(5)
        }
        assertTrue("onAborted-Grund '$expected' fehlt (ist $list)", list.contains(expected))
    }
}
