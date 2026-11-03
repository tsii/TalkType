package de.voicekeyboard.ime

import de.voicekeyboard.asr.AsrEngine
import de.voicekeyboard.asr.Hypothesis
import de.voicekeyboard.audio.AudioSource
import de.voicekeyboard.audio.PcmBlock
import de.voicekeyboard.audio.PcmWindow
import de.voicekeyboard.dictation.DictationState
import de.voicekeyboard.editor.EditorSink
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 * W-Paket (vc34, Debugger-Empfehlung): Engine-Warming — Vorwärmung beim ersten
 * IME-Show (W1), Residenz bis shutdown (W2) und die 🐞-Export-Felder
 * engine_load_ms / engine_resident / warm_hit / load_trigger / engine_loads.
 *
 *  (a) erster Show ⇒ preload GENAU 1× (Fake-Engine zählt loads); zweiter Show
 *      ⇒ 0 weitere; parallele preload() ⇒ Single-Flight (GENAU 1 load);
 *  (b) Diktat-Start nach Preload ⇒ warm_hit=1, load_trigger=ime_show,
 *      start_ms OHNE Ladezeit (Fake-load dauert künstlich); Kaltstart im
 *      Vergleich lädt selbst (mic) und schließt die Ladezeit ein;
 *  (c) fehlgeschlagener Preload blockiert den späteren Start NICHT (lazy-
 *      Fallback im Diktat-Start bleibt unangetastet).
 *
 * Regel 9: nur Zahlen/Codes — nie Modellpfade/-inhalte. Reine JVM-Tests
 * (Coordinator ist frameworkfrei; der Service-Trigger selbst ist die reine
 * Entscheidung [preloadDue], Muster editorRestartAction).
 */
class EnginePreloadTest {

    /** Fake-Engine: zählt load()-Läufe, wirft beim Zweitaufruf (Single-Flight-
     *  Kanarienvogel), kann beim ersten load() scheitern und es künstlich
     *  verzögern (Kaltstart-Messung). transcribe liefert leer (0 Samples). */
    private class CountingEngine(
        private val loadSleepMs: Long = 0,
        private val failFirstLoad: Boolean = false,
    ) : AsrEngine {
        val loadCalls = AtomicInteger(0)
        @Volatile private var failedOnce = false
        @Volatile private var loaded = false

        override fun load(modelDir: File) {
            loadCalls.incrementAndGet()
            if (loaded) error("doppeltes load() — Single-Flight verletzt")
            if (failFirstLoad && !failedOnce) {
                failedOnce = true
                throw IllegalStateException("load-boom")
            }
            if (loadSleepMs > 0) Thread.sleep(loadSleepMs)
            loaded = true
        }

        override suspend fun transcribe(
            window: PcmWindow,
            sessionId: Long,
            runId: Long,
        ): Hypothesis = Hypothesis(
            tokenIds = IntArray(0),
            text = "",
            frameTimesMs = IntArray(0),
            sessionId = sessionId,
            runId = runId,
            audioSamples = window.samples.size,
            incomplete = false,
        )

        override fun close() {}
    }

    /** Kürzeste lebende Quelle: start ok, nie Daten (Session endet per stop). */
    private class SilentSource : AudioSource {
        override fun start(sessionId: Long): Boolean = true
        override fun read(dst: ShortArray, timeoutMs: Int): Int = 0
        override fun stopAndDrain(): List<PcmBlock> = emptyList()
        override fun release() {}
    }

    /** Trivial-Sink: Start/Stop ohne Output braucht keinen Editor. */
    private class NoopSink : EditorSink {
        override fun replaceBeforeCursor(replaceLength: Int, newText: String) {}
        override fun textBeforeCursor(maxChars: Int): CharSequence? = null
        override fun ownLength(): Int = -1
    }

    private fun newScope(): CoroutineScope = CoroutineScope(Dispatchers.Default)

    private fun shutdown(scope: CoroutineScope) {
        (scope.coroutineContext[kotlinx.coroutines.Job])?.cancel()
    }

    /** Modellverzeichnis (Fake-Engine liest es nicht — nur Nicht-null nötig). */
    private fun dir(): File = File(System.getProperty("java.io.tmpdir"), "vk-preload-model")

    private fun coordinator(
        scope: CoroutineScope,
        engine: CountingEngine,
    ) = DictationCoordinator(
        scope, { SilentSource() }, { engine }, NoopSink(), modelDir = dir(),
    )

    /** stop() + Warten auf das asynchrone Finale (Muster awaitIdle). */
    private fun stopAndWait(c: DictationCoordinator) {
        c.stop()
        val deadline = System.currentTimeMillis() + 3000
        while (c.state != DictationState.IDLE && System.currentTimeMillis() < deadline) {
            Thread.sleep(10)
        }
        assertEquals("stop()-Worker muss nach IDLE enden", DictationState.IDLE, c.state)
    }

    // --- W4 (a): erster Show ⇒ preload GENAU 1×; zweiter Show ⇒ 0 weitere;
    //     parallele Aufrufe ⇒ Single-Flight (GENAU 1 load, kein Zweitaufruf). ---

    @Test fun `preload laedt genau einmal - zweiter Show und parallele Aufrufe starten kein weiteres load`() {
        val scope = newScope()
        val engine = CountingEngine()
        val c = coordinator(scope, engine)
        try {
            // erster Show (Service: preloadDue(false) ⇒ genau EIN preload-Aufruf)
            assertTrue("erster Show-Preload lädt", preloadDue(false))
            assertTrue(c.preload())
            assertEquals("genau 1 load beim ersten Show", 1, engine.loadCalls.get())
            assertTrue("Engine resident (W2: bis shutdown)", c.engineResident)
            assertEquals("ime_show", c.loadTrigger)

            // zweiter Show: Service-Trigger bleibt No-Op (Flag), Coordinator
            // ebenfalls resident-idempotent — 0 weitere loads.
            assertFalse("zweiter Show: Trigger bleibt No-Op", preloadDue(true))
            assertTrue("zweiter Show: resident, kein weiterer load", c.preload())
            assertEquals("kein weiterer load beim zweiten Show", 1, engine.loadCalls.get())

            // Parallele preload() (8 Threads): Single-Flight-Gate — GENAU 1 load;
            // die Fake-Engine wirft beim Zweitaufruf (Kanarienvogel oben).
            val engine2 = CountingEngine()
            val c2 = coordinator(scope, engine2)
            try {
                val threads = (1..8).map { kotlin.concurrent.thread { c2.preload() } }
                threads.forEach { it.join() }
                assertEquals("parallele load() unmöglich (Single-Flight)", 1, engine2.loadCalls.get())
                assertTrue("alle Aufrufer sehen resident", c2.engineResident)
            } finally {
                c2.shutdown()
            }
        } finally {
            c.shutdown()
            shutdown(scope)
        }
    }

    // --- W4 (b): Diktat-Start nach Preload ist WARM — warm_hit=1,
    //     load_trigger=ime_show, start_ms OHNE Ladezeit (Fake-load 500 ms). ---

    @Test fun `Diktat-Start nach Preload ist warm - warm_hit, trigger ime_show, start_ms ohne Ladezeit`() {
        val scope = newScope()
        val engine = CountingEngine(loadSleepMs = 500)
        val c = coordinator(scope, engine)
        try {
            assertTrue(c.preload())
            assertEquals(1, engine.loadCalls.get())
            assertTrue("engine_load_ms misst die Fake-Ladezeit (war ${c.engineLoadMs})", c.engineLoadMs >= 500)

            assertTrue("warmer Start", c.start())
            assertTrue(
                "start_ms OHNE Ladezeit — Engine war resident (war ${c.lastStartMs})",
                c.lastStartMs < 500,
            )
            assertEquals("kein weiterer load im warmen Start", 1, engine.loadCalls.get())
            assertTrue("warm_hit: Start fand resident vor", c.warmHit)
            assertEquals("load_trigger bleibt ime_show", "ime_show", c.loadTrigger)
            assertEquals("engine_loads zählt den einen load", 1, c.engineLoads)
            stopAndWait(c)

            // Vergleichs-KALTSTART: ohne Preload lädt der Start selbst
            // (lazy-Fallback unangetastet, Trigger mic) — start_ms SCHLIESST
            // die Ladezeit ein.
            val engine2 = CountingEngine(loadSleepMs = 500)
            val c2 = coordinator(scope, engine2)
            try {
                assertTrue(c2.start())
                assertFalse("Kaltstart ist nicht warm", c2.warmHit)
                assertEquals("mic", c2.loadTrigger)
                assertTrue(
                    "Kaltstart schließt die Ladezeit ein (war ${c2.lastStartMs})",
                    c2.lastStartMs >= 500,
                )
                assertEquals("engine_loads zählt den Start-load", 1, c2.engineLoads)
                stopAndWait(c2)
            } finally {
                c2.shutdown()
            }
        } finally {
            c.shutdown()
            shutdown(scope)
        }
    }

    // --- W4 (c): fehlgeschlagener Preload blockiert den späteren Start NICHT —
    //     der lazy-Fallback im Diktat-Start lädt nach (Trigger mic). ---

    @Test fun `fehlgeschlagener Preload blockiert den spaeteren Diktat-Start nicht`() {
        val scope = newScope()
        val engine = CountingEngine(failFirstLoad = true)
        val c = coordinator(scope, engine)
        try {
            assertFalse("Preload scheitert kontrolliert (false, keine Exception nach außen)", c.preload())
            assertFalse("Engine nicht resident", c.engineResident)
            assertEquals("Zähler zählt nur ERFOLGREICHE loads", 0, c.engineLoads)

            // Der spätere Diktat-Start lädt lazy nach — der Fehlschlag blockiert NICHT.
            assertTrue("Start wird vom Preload-Fehlschlag NICHT blockiert", c.start())
            assertEquals("zweiter load-Lauf (der erste scheiterte)", 2, engine.loadCalls.get())
            assertTrue("nach dem Start resident", c.engineResident)
            assertEquals("Trigger des ERFOLGREICHEN load() = mic", "mic", c.loadTrigger)
            assertFalse("Kaltstart: kein warm_hit", c.warmHit)
            assertEquals("engine_loads zählt nur den Erfolg", 1, c.engineLoads)
            stopAndWait(c)
        } finally {
            c.shutdown()
            shutdown(scope)
        }
    }
}
