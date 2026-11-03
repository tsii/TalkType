package de.voicekeyboard.dictation

import de.voicekeyboard.asr.AsrEngine
import de.voicekeyboard.asr.Hypothesis
import de.voicekeyboard.audio.AudioSource
import de.voicekeyboard.audio.PcmBlock
import de.voicekeyboard.audio.PcmWindow
import de.voicekeyboard.editor.EditorSink
import de.voicekeyboard.editor.OwnershipTracker
import de.voicekeyboard.ime.DictationCoordinator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.atomic.AtomicLong

/**
 * B3 (PLAN_V0130 §6.3-B3): GOLDEN-NACHWEIS JE SEGMENT — die Verkettung der
 * Segment-Commits ist NAHT-INVARIANT exakt golden, egal WOHIN die (a)-Naht
 * fällt (Consumer-Lag ist scheduling-abhängig, B1-Befund).
 *
 * Deterministischer Transkriptions-Stub ([RunScannerEngine]): das Audio
 * kodiert Wörter als Amplituden-Läufe (6000→"alpha.", 9000→"bravo.",
 * 12000→"charlie." — Punkt im Wort, naht-invariant); der Stub transkribiert
 * ein Fenster, indem er Läufe scannt:
 *  - ein Wort-Lauf, der das FENSTERENDE berührt, ist SCHNITT-VERDÄCHTIG und
 *    wird VERWORFEN (das Folge-Segment emittiert ihn als führenden Rest) —
 *    genau das macht die Kette verlust- und duplikatfrei über ALLE Naht-
 *    positionen; Korpus-Start und -Ende sind mit Stille gepolstert, sodass
 *    nur geschnittene Läufe am Rand liegen können.
 *  - Cut-Positionen sind 2-s-quantisiert (Flush-Takt) — die Wortgrenzen des
 *    Korpus liegen absichtlich NICHT auf 2-s-Multipeln.
 *
 * Szenarien (segmentFinalizeMs = 35 000, Hold-Gate: F43 aus):
 *  - (a) Kette „Alpha—Pause 1,5 s—Bravo—Pause 1,5 s—Charlie": Verkettung ==
 *    "alpha. bravo. charlie." EXAKT (genau ein Leerzeichen je Naht, keine
 *    Duplikate/Verluste — unabhängig von der (racy) (a)-Naht-Position).
 *  - (b) UP finalisiert NUR den Rest (Charlie) — Gesamttext unverändert
 *    korrekt, FinalRan.
 *  - (c) (b)-Zwangssegment bei 36 s ohne Satzpausen (Trade-off dokumentiert):
 *    genau ein Leerzeichen an der (b)-Naht, keine Wort-Verdopplung.
 *  - (d) 2× identischer Korpus ⇒ identische Verkettung (Determinismus).
 *
 * Regel 9: Fixture-Wörter sind Testkonstanten, nie Nutzerdaten.
 */
class SegmentGoldenTest {

    private class VClock {
        val now = AtomicLong(1_000_000L)
    }

    /** Skript-Quelle: Abschnitte (ms, Amplitude); 0 = Stille; 320er-Blöcke.
     *  [paceUs]: TEST-HARNESS-Pacing je Block — ohne ihn überholt der Capture
     *  den Consumer im Self-Paced-Betrieb µs-schnell, die (a)-Pause ist vorbei,
     *  BEVOR die erste Dekodierung lastHypPunct gesetzt hat (B1-Befund,
     *  SegmentGolden-a-Lauf: segments=0). Mit ~500 µs/Block dekodiert der
     *  Consumer (2-s-Flush ≈ 50 ms real) VOR der Satzpause (~120 ms real) —
     *  die (a)-Kette wird deterministisch scharf. */
    private class AmpScriptSource(
        private val clock: VClock,
        private val script: List<Pair<Long, Short>>,
        private val paceUs: Long = 500L,
    ) : AudioSource {
        private val blockMs = 20L
        private val blockSamples = 320
        private val totalMs = script.sumOf { it.first }
        private var base = -1L
        private var nextIdx = 0L
        @Volatile private var released = false

        private fun ampAt(ms: Long): Short {
            var t = 0L
            for ((d, a) in script) {
                t += d
                if (ms < t) return a
            }
            return 0
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
            if (paceUs > 0) java.util.concurrent.locks.LockSupport.parkNanos(paceUs * 1_000)
            val amp = ampAt(t)
            java.util.Arrays.fill(dst, 0, blockSamples, amp)
            nextIdx++
            return blockSamples
        }

        override fun stopAndDrain(): List<PcmBlock> = emptyList()
        override fun release() {
            released = true
        }
    }

    /**
     * Deterministischer Transkriptions-Stub: Amplituden-Läufe → Wörter.
     * Ein Lauf, der das FENSTERENDE berührt, gilt als geschnitten und wird
     * verworfen (das Folge-Segment liefert den Rest als führenden Lauf) —
     * dadurch ist die SEGMENT-VERKETTUNG über alle Naht-Positionen exakt.
     */
    private class RunScannerEngine(
        private val words: Map<Short, String>,
        private val minRunSamples: Int = 4_800, // 0,3 s — Filter für Split-Reste
    ) : AsrEngine {
        var calls = 0
            private set
        val windowSeconds = ArrayList<Double>()

        override fun load(modelDir: File) {}

        override suspend fun transcribe(
            window: PcmWindow, sessionId: Long, runId: Long,
        ): Hypothesis {
            calls++
            val s = window.samples
            windowSeconds.add(s.size / 16_000.0)
            val out = StringBuilder()
            var i = 0
            var runStart = 0
            var runAmp = if (s.isNotEmpty()) s[0] else 0
            var appended = 0
            while (i < s.size) {
                if (kotlin.math.abs(s[i].toInt() - runAmp.toInt()) > 100) {
                    // Lauf [runStart, i) beendet — KEIN Fensterende-Berühren
                    if (i - runStart >= minRunSamples) {
                        words[runAmp]?.let { w ->
                            if (appended > 0) out.append(' ')
                            out.append(w)
                            appended++
                        }
                    }
                    runStart = i
                    runAmp = s[i]
                }
                i++
            }
            // Letzter Lauf berührt zwingend das FENSTERENDE und wird NICHT
            // emittiert (Schnitt-verdächtig: das Folge-Segment liefert den
            // Rest als führenden Lauf) — das Korpus-End-Polster sorgt dafür,
            // dass im FINALFenster der letzte Lauf Stille ist und die Wörter
            // davor durch Amplitudenwechsel geschlossen (und damit emittiert)
            // werden.
            val text = out.toString()
            return Hypothesis(
                IntArray(0), text, IntArray(0), sessionId, runId,
                s.size, incomplete = false,
            )
        }

        override fun close() {}
    }

    /** Echter Schreib-Sink: spiegelt den Editor-Textstand (nie geloggt, Regel 9). */
    private class RecordingSink : EditorSink {
        val text = StringBuilder()
        override fun replaceBeforeCursor(replaceLength: Int, newText: String) {
            text.delete(text.length - replaceLength, text.length)
            text.append(newText)
        }
        override fun textBeforeCursor(maxChars: Int): CharSequence = text.takeLast(maxOf(0, maxChars))
        override fun ownLength(): Int = text.length
    }

    private class Ctx(
        val coordinator: DictationCoordinator,
        val engine: RunScannerEngine,
        val sink: RecordingSink,
        val scope: CoroutineScope,
        val totalMs: Long,
    )

    /** Wörter als Amplituden — Punkt im Wort (naht-invariant). */
    private val words = mapOf(
        6_000.toShort() to "alpha.",
        9_000.toShort() to "bravo.",
        12_000.toShort() to "charlie.",
    )

    /** (a)/(b)/(d)-Korpus: Pad—Alpha—Pause—Bravo—Pause—Charlie—Pad (~13,6 s). */
    private val chainScript = listOf(
        500L to 0.toShort(),          // Start-Polster: kein Lauf am Rand
        3_200L to 6_000.toShort(),    // alpha 0,5–3,7 s
        1_500L to 0.toShort(),        // Pause 1 (Satzpause, Regel a)
        2_800L to 9_000.toShort(),    // bravo 5,2–8,0 s — Grenze NICHT auf 2-s-Raster
        1_600L to 0.toShort(),        // Pause 2
        2_900L to 12_000.toShort(),   // charlie 9,6–12,5 s
        500L to 0.toShort(),          // End-Polster: letzter Lauf im Fenster = Stille
    )

    /** (c)-Korpus: 46 s Monolog ohne Satzpausen (nur Amplitudenwechsel). */
    private val monologScript = listOf(
        500L to 0.toShort(),
        15_000L to 6_000.toShort(),   // alpha 0,5–15,5
        15_000L to 9_000.toShort(),   // bravo 15,5–30,5
        15_000L to 12_000.toShort(),  // charlie 30,5–45,5 (36-s-Schnitt mittendrin)
        500L to 0.toShort(),
    )

    private fun runChain(
        script: List<Pair<Long, Short>>,
        stopWhenAudioMs: Long? = null,
    ): Ctx {
        val totalMs = script.sumOf { it.first }
        val clock = VClock()
        val src = AmpScriptSource(clock, script)
        val engine = RunScannerEngine(words)
        val sink = RecordingSink()
        val scope = CoroutineScope(Dispatchers.Default)
        val coordinator = DictationCoordinator(
            scope, { src }, { engine }, sink,
            tracker = OwnershipTracker(),
            nowMs = { clock.now.get() },
            autoStopSuppressed = { true }, // Hold-Vertrag: F43 aus — Naht nur über Segmenter
            segmentFinalizeMs = 35_000L,
        )
        assertTrue(coordinator.start())
        val ctx = Ctx(coordinator, engine, sink, scope, totalMs)
        val target = stopWhenAudioMs ?: totalMs
        val deadline = System.currentTimeMillis() + 30_000
        while (System.currentTimeMillis() < deadline) {
            Thread.sleep(5)
            if (coordinator.audioMs >= target - 1 || !coordinator.isActive) break
        }
        return ctx
    }

    private fun finishAndText(ctx: Ctx): String {
        ctx.coordinator.stop()
        val deadline = System.currentTimeMillis() + 15_000
        while (ctx.coordinator.lastAbortReason != "final" && System.currentTimeMillis() < deadline) {
            Thread.sleep(5)
        }
        assertEquals("abort_reason", "final", ctx.coordinator.lastAbortReason)
        return ctx.sink.text.toString()
    }

    // ---- (a) Verkettung == Golden, exakt, naht-invariant ----

    @Test
    fun `(a) Segment-Verkettung ist exakt golden - ein Leerzeichen je Naht, keine Duplikate`() {
        val ctx = runChain(chainScript)
        try {
            val c = ctx.coordinator
            // Mindestens eine (a)-Grenze an den Satzpausen; die exakte Anzahl
            // ist naht-positionabhängig (1–2 Schnitte decken die Kette).
            assertTrue(
                "mind. eine Segmentgrenze (Ist=${c.segmentFinalizations()})",
                c.segmentFinalizations() in 1..2,
            )
            assertNull("kein ERR", c.lastError)
            val text = finishAndText(ctx)
            assertEquals(
                "Verkettung == Golden-Gesamttext (exakt, ein Leerzeichen je Naht)",
                "alpha. bravo. charlie.",
                text,
            )
            assertFalse("keine Doppelleerzeichen (Naht-Regel)", text.contains("  "))
        } finally {
            ctx.scope.cancel()
        }
    }

    // ---- (b) UP finalisiert nur den Rest — Gesamttext unverändert korrekt ----

    @Test
    fun `(b) UP finalisiert nur das Rest-Buffer - Gesamttext bleibt golden`() {
        val ctx = runChain(chainScript)
        try {
            val c = ctx.coordinator
            val text = finishAndText(ctx)
            assertTrue("FinalRan (ist ${c.lastStopOutcome})", c.lastStopOutcome is StopOutcome.FinalRan)
            assertEquals("Gesamttext unverändert korrekt", "alpha. bravo. charlie.", text)
            // Das UP-Final-Fenster ist NIE die ganze Session (Rest-Vertrag);
            // (ist die Naht ganz am Ende, wird das leere Rest übersprungen —
            // das letzte Fenster ist dann das Mini-Finale, ebenfalls < Session.)
            val lastWindow = ctx.engine.windowSeconds.last()
            assertTrue(
                "UP/Mini-Fenster segment-lokal (Ist=$lastWindow)",
                lastWindow <= 13.6,
            )
            assertNull("kein ERR", c.lastError)
        } finally {
            ctx.scope.cancel()
        }
    }

    // ---- (c) (b)-Zwangssegment ohne Satzpausen: Naht-Trade-off beherrscht ----

    @Test
    fun `(c) b-Zwangssegment bei 36 s - ein Leerzeichen an der Naht, keine Verdopplung`() {
        // Trade-off (dokumentiert, PLAN §6.3 (b)-Spalte): der Zwangsschnitt
        // trifft KEINE Satzpause — die Naht fällt mitten ins Wort-Feld. Der
        // Scanner verwirft den Schnitt-Rest im alten Segment und emittiert ihn
        // im neuen als führenden Lauf: genau EIN Leerzeichen an der (b)-Naht,
        // keine Wort-Verdopplung, kein Verlust.
        val ctx = runChain(monologScript)
        try {
            val c = ctx.coordinator
            val text = finishAndText(ctx)
            assertEquals(
                "Zwangssegment: (b)-Naht mit genau einem Leerzeichen",
                "alpha. bravo. charlie.",
                text,
            )
            assertEquals("genau EIN (b)-Schnitt (36 s, deterministisch)", 1, c.segmentFinalizations())
            assertFalse("keine Doppelleerzeichen", text.contains("  "))
            assertNull("kein ERR", c.lastError)
        } finally {
            ctx.scope.cancel()
        }
    }

    // ---- (d) Determinismus: 2× identischer Korpus ⇒ identische Verkettung ----

    @Test
    fun `(d) zweimal identischer Korpus - identische Verkettung (Determinismus)`() {
        val text1 = runChain(chainScript).let { ctx ->
            try {
                finishAndText(ctx)
            } finally {
                ctx.scope.cancel()
            }
        }
        val text2 = runChain(chainScript).let { ctx ->
            try {
                finishAndText(ctx)
            } finally {
                ctx.scope.cancel()
            }
        }
        assertEquals("2. Lauf == 1. Lauf (Determinismus)", text1, text2)
        assertEquals("beide Läufe == Golden", "alpha. bravo. charlie.", text1)
    }
}
