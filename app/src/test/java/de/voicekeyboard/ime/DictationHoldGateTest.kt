package de.voicekeyboard.ime

import de.voicekeyboard.asr.AsrEngine
import de.voicekeyboard.asr.Hypothesis
import de.voicekeyboard.audio.AudioSource
import de.voicekeyboard.audio.PcmBlock
import de.voicekeyboard.audio.PcmWindow
import de.voicekeyboard.dictation.StopOutcome
import de.voicekeyboard.editor.EditorSink
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.atomic.AtomicLong

/**
 * F49a (PLAN_V0130 §1 A1, Minimal-Repro): Hold-Gate für den F43-Auto-Stop am
 * ECHTEN Korpus-Verlauf — „Sprache — 2,0 s Pause — Sprache — 3,0 s Pause —
 * Sprache" (je 2 s laut) × {Gate=false, Gate=true}:
 *
 *  - Gate=false: die 2-s-Pause übersteht (< 2,5 s), die 3-s-Pause beendet
 *    automatisch REGULÄR (Orakel: Auto-Stop 2,5 s in die Pause, die dritte
 *    Sprache wird nie gefüttert), lastError null.
 *  - Gate=true: KEIN Auto-Stop — beide Pausen überstanden, Lauf bis zum
 *    Quellen-Ende (11 s), expliziter Stopp finalisiert regulär.
 *  - Beide: Besitz nie verloren (Schreibweg + hyp_count) und Stille≠Stall-
 *    Vertrag intakt (streamende Stille löst NIE ERR_STALL/ERR_SILENT aus).
 *
 * Muster [DictationAutoStopTest]: virtuelle Uhr, self-paced 320-Sample-Blöcke
 * à 20 ms, KEIN Thread.sleep im Rhythmus. Regel 9: nur Zahlen/Codes.
 */
class DictationHoldGateTest {

    private class VClock {
        val now = AtomicLong(1_000_000L)
    }

    /** Skript-Quelle (Stille = Null-Blöcke, streamend wie AudioRecord). */
    private class ScriptSource(
        private val clock: VClock,
        private val script: List<Pair<Long, Boolean>>,
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
            if (t >= totalMs) {
                Thread.sleep(1) // Skript-Ende: kein Block, virtuelle Uhr steht
                return 0
            }
            val due = base + t
            while (true) {
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

    private class RecordingEngine : AsrEngine {
        var calls = 0
            private set

        override fun load(modelDir: File) {}

        override suspend fun transcribe(
            window: PcmWindow, sessionId: Long, runId: Long,
        ): Hypothesis {
            calls++
            return Hypothesis(
                IntArray(0), "k", IntArray(0), sessionId, runId,
                window.samples.size, incomplete = false,
            )
        }

        override fun close() {}
    }

    /** Echter Schreib-Sink (Besitz-Nachweis: committete Zeichen, nie Inhalte). */
    private class RecordingSink : EditorSink {
        val text = StringBuilder()
        override fun replaceBeforeCursor(replaceLength: Int, newText: String) {
            text.delete(text.length - replaceLength, text.length)
            text.append(newText)
        }
        override fun textBeforeCursor(maxChars: Int): CharSequence = text.takeLast(maxOf(0, maxChars))
        override fun ownLength(): Int = text.length
    }

    /** Korpus: 2 s Sprache — 2,0 s Pause — 2 s Sprache — 3,0 s Pause — 2 s Sprache
     *  (GESAMT 11 s; 16 kHz = 176 000 Samples; 5 volle 2-s-Ticks = 10 s gefüttert,
     *  der 1-s-Rest kommt mit dem expliziten Stopp). */
    private val corpus = listOf(
        2_000L to true, 2_000L to false, 2_000L to true, 3_000L to false, 2_000L to true,
    )

    private class Ctx(
        val coordinator: DictationCoordinator,
        val engine: RecordingEngine,
        val sink: RecordingSink,
        val scope: CoroutineScope,
    )

    /** Fährt den Korpus mit dem gegebenen F49-Gate bis Auto-Finale (Gate=false)
     *  oder Quellen-Ende (Gate=true; 5 Ticks à 2 s = 10 s gefüttert, Rest beim Stopp). */
    private fun runCorpus(suppressed: Boolean): Ctx {
        val clock = VClock()
        val src = ScriptSource(clock, corpus)
        val engine = RecordingEngine()
        val sink = RecordingSink()
        val scope = CoroutineScope(Dispatchers.Default)
        val c = DictationCoordinator(
            scope, { src }, { engine }, sink, nowMs = { clock.now.get() },
            autoStopSuppressed = { suppressed },
        )
        assertTrue("start muss gelingen", c.start())
        val deadline = System.currentTimeMillis() + 20_000
        while (System.currentTimeMillis() < deadline) {
            Thread.sleep(5)
            if (c.lastAbortReason == "final") break // Auto-Finale (Gate=false) oder expliziter Stopp
            if (c.audioSeconds >= 9.99) break // Quellen-Ende: 5 Ticks gefüttert (11-s-Korpus)
            if (!c.isActive) break // Kill-Pfad (darf hier nicht passieren)
        }
        return Ctx(c, engine, sink, scope)
    }

    private fun awaitReason(c: DictationCoordinator, expected: String, timeoutMs: Long = 15_000) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (c.lastAbortReason != expected && System.currentTimeMillis() < deadline) {
            Thread.sleep(5)
        }
        assertEquals("abort_reason", expected, c.lastAbortReason)
    }

    // ---- (1) Gate=false: Orakel — Auto-Stop an der 3-s-Pause ----

    @Test
    fun `gate=false - Auto-Stop an der 3-s-Pause, 2-s-Pause uebersteht, kein ERR`() {
        val ctx = runCorpus(suppressed = false)
        try {
            val c = ctx.coordinator
            // Auto-Finale ist bereits eingetreten (kein expliziter Stopp nötig).
            assertEquals("reguläres Auto-Finale", "final", c.lastAbortReason)
            assertTrue("Outcome FinalRan (ist ${c.lastStopOutcome})", c.lastStopOutcome is StopOutcome.FinalRan)
            assertNull("kein ERR (Stille ist keine Stall-/Silence-Falle)", c.lastError)
            // Orakel: Auto-Stop 2,5 s IN die 3-s-Pause (nach 8 s Korpus), die
            // dritte Sprache (ab 9 s) wird NIE gefüttert.
            assertTrue(
                "Auto-Stop nach der 3. Sprache, in der Pause (audio_s=${c.audioSeconds})",
                c.audioSeconds >= 8.0 && c.audioSeconds <= 9.5,
            )
            assertTrue("kein ERR_STALL/ERR_SILENT-Abbruch", c.lastAbortReason.startsWith("audio_error").not())
        } finally {
            ctx.scope.cancel()
        }
    }

    // ---- (2) Gate=true: kein Auto-Stop, Lauf bis Quellen-Ende ----

    @Test
    fun `gate=true - kein Auto-Stop, beide Pausen ueberstanden, bis Quellen-Ende`() {
        val ctx = runCorpus(suppressed = true)
        try {
            val c = ctx.coordinator
            assertTrue("Gate zu: Session läuft nach 2-s- UND 3-s-Pause weiter", c.isActive)
            assertEquals("kein abort bis Quellen-Ende", "-", c.lastAbortReason)
            assertNull("kein ERR trotz 3-s-Pause", c.lastError)
            assertTrue(
                "ganzer Korpus gefüttert (audio_s=${c.audioSeconds})",
                c.audioSeconds >= 9.99,
            )
            c.stop() // regulärer Abschluss
            awaitReason(c, "final")
            assertTrue("expliziter Stopp: FinalRan (ist ${c.lastStopOutcome})", c.lastStopOutcome is StopOutcome.FinalRan)
            assertTrue(
                "Reste beim Stopp nicht verloren: audio_s=${c.audioSeconds} ≈ 11,0",
                c.audioSeconds >= 10.9,
            )
        } finally {
            ctx.scope.cancel()
        }
    }

    // ---- (3) beide Gates: Besitz nie verloren ----

    @Test
    fun `beide Gates - Besitz nie verloren (Schreibweg laeuft, hyp_count positiv)`() {
        for (suppressed in listOf(false, true)) {
            val ctx = runCorpus(suppressed = suppressed)
            try {
                val c = ctx.coordinator
                if (suppressed) { // erst regulär beenden, dann bewerten
                    c.stop()
                    awaitReason(c, "final")
                }
                assertTrue(
                    "gate=$suppressed: Hypothesen erzeugt (hyp_count=${c.hypCount})",
                    c.hypCount >= 1 && ctx.engine.calls >= 1,
                )
                assertTrue(
                    "gate=$suppressed: Schreibweg hat committet (${ctx.sink.text.length} Zeichen, nur Zahl)",
                    ctx.sink.text.isNotEmpty(),
                )
            } finally {
                ctx.scope.cancel()
            }
        }
    }

    // ---- (4) beide Gates: Stille≠Stall-Vertrag intakt ----

    @Test
    fun `beide Gates - streamende Stille loest nie ERR_STALL und capStall bleibt unter dem Limit`() {
        for (suppressed in listOf(false, true)) {
            val ctx = runCorpus(suppressed = suppressed)
            try {
                val c = ctx.coordinator
                if (suppressed) {
                    c.stop()
                    awaitReason(c, "final")
                }
                assertNull("gate=$suppressed: kein ERR_STALL/ERR_SILENT (lastError=${c.lastError})", c.lastError)
                assertTrue(
                    "gate=$suppressed: capStallMs=${c.capStallMs()} < STALL_LIMIT (Blöcke strömten)",
                    c.capStallMs() < DictationCoordinator.STALL_LIMIT_MS,
                )
            } finally {
                ctx.scope.cancel()
            }
        }
    }
}
