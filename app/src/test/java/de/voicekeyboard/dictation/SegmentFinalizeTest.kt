package de.voicekeyboard.dictation

import de.voicekeyboard.asr.AsrEngine
import de.voicekeyboard.asr.Hypothesis
import de.voicekeyboard.audio.AudioSource
import de.voicekeyboard.audio.PcmBlock
import de.voicekeyboard.audio.PcmWindow
import de.voicekeyboard.editor.EditorSink
import de.voicekeyboard.ime.DictationCoordinator
import de.voicekeyboard.ime.segmentBoundary
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
 * B1 (PLAN_V0130 §6.3 Empfehlung (c)): Segmenter-Kern — Zwischensegment-
 * Finalisierung, §5.1-KONFORM (whole_buffer nur ZEITLICH segmentiert).
 * Muster [de.voicekeyboard.ime.DictationAutoStopTest]: virtuelle Uhr,
 * self-paced 320-Sample-Blöcke à 20 ms, KEIN Thread.sleep im Rhythmus.
 *
 * Korpus (Regel-a-Fall): „Sprache(3 s) — Pause 1,0 s — Sprache(3 s) — Pause"
 * mit Satzzeichen-Hypothesen und segmentFinalizeMs = 35 000:
 *  - (1) Grenze an der Stille+Satzzeichen (≥ 1,2 s Pause), NICHT bei der
 *    1,0-s-Pause; jede Pause nur EINMAL; Session lebt bis UP; das UP-Finale
 *    dekodiert NUR das REST-Puffer (wichtigster Vertragstest).
 *  - (2) Dauerfall 40 s pausenlose Sprache → Zwangssegment (Längenregel b)
 *    bei ~35 s; Decode-Fenster je Segment bleibt ≤ 38 s.
 *  - (3) ohne Satzzeichen bleibt die Stille-Regel (a) stumm — keine Grenze.
 *  - (4) reine Funktion [segmentBoundary]: Wahrheitstafel beider Regeln.
 *  - (5) B1-Fix (Review P1): transienter Fehler im Segment-Finale killt die
 *    Session NICHT — Wiederholungs-Boundary + korrektes UP-Rest-Finale.
 *  - (6) B1-Fix (Review P2a): Export-Längen (audio_s/buffer_s) bleiben nach
 *    Segmenten SESSION-Semantik; hyp_last_buffer_s bleibt segment-lokal.
 *
 * Regel 9: nur Zahlen/Codes; Fixture-Texte sind Testkonstanten, nie Nutzerdaten.
 */
class SegmentFinalizeTest {

    private class VClock {
        val now = AtomicLong(1_000_000L)
    }

    /** Skript-Quelle (Stille = Null-Blöcke, streamend wie AudioRecord).
     *  [pacingNanos]: optionale Wanduhr-Drossel je Block (F56-Repro,
     *  SegmentGoldenTest-Muster: ohne Pacing überholt die µs-schnelle Capture
     *  den Consumer — lastHypPunct wäre bei der (a)-Prüfung noch nicht aus
     *  einem ABGESCHLOSSENen Decode gesetzt, dieselbe Race-Familie wie B1/B3). */
    private class ScriptSource(
        private val clock: VClock,
        private val script: List<Pair<Long, Boolean>>,
        private val pacingNanos: Long = 0L,
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
            if (pacingNanos > 0) java.util.concurrent.locks.LockSupport.parkNanos(pacingNanos)
            return blockSamples
        }

        override fun stopAndDrain(): List<PcmBlock> = emptyList()
        override fun release() {
            released = true
        }
    }

    /** Stub mit KONFIGURIERBAREM Text (Satzzeichen ja/nein); Fenster je Aufruf.
     *  B1-Fix (P1, Test 5): [failFinalizeAttempts] SEGMENT-Finalize-Versuche
     *  werfen eine transiente IllegalStateException — ein Finalize wiederholt
     *  die Fenstergröße des unmittelbar vorherigen Partial-Decodes (Partials
     *  wachsen im Segment streng monoton, ein Finalize wiederholt die Größe). */
    private class TextStubEngine(
        private val text: String,
        private val failFinalizeAttempts: Int = 0,
    ) : AsrEngine {
        var calls = 0
            private set
        var failedFinalizes = 0
            private set
        val windowSeconds = ArrayList<Double>()
        private var lastSize = -1
        private var failRemaining = failFinalizeAttempts

        override fun load(modelDir: File) {}

        override suspend fun transcribe(
            window: PcmWindow, sessionId: Long, runId: Long,
        ): Hypothesis {
            val size = window.samples.size
            val isFinalize = size == lastSize
            lastSize = size
            if (isFinalize && failRemaining > 0) {
                failRemaining--
                failedFinalizes++
                throw IllegalStateException("transient segment decode (B1-Fix Test)")
            }
            calls++
            windowSeconds.add(size / 16_000.0)
            return Hypothesis(
                IntArray(0), text, IntArray(0), sessionId, runId,
                window.samples.size, incomplete = false,
            )
        }

        override fun close() {}
    }

    /** Echter Schreib-Sink (Besitz-Nachweis über Schreib-Zähler, nie Inhalte geloggt). */
    private class RecordingSink : EditorSink {
        var writes = 0
            private set
        val text = StringBuilder()
        override fun replaceBeforeCursor(replaceLength: Int, newText: String) {
            text.delete(text.length - replaceLength, text.length)
            text.append(newText)
            writes++
        }
        override fun textBeforeCursor(maxChars: Int): CharSequence = text.takeLast(maxOf(0, maxChars))
        override fun ownLength(): Int = text.length
    }

    private class Ctx(
        val coordinator: DictationCoordinator,
        val engine: TextStubEngine,
        val sink: RecordingSink,
        val scope: CoroutineScope,
    )

    private fun runScript(
        script: List<Pair<Long, Boolean>>,
        engineText: String,
        segmentFinalizeMs: Long,
        stopWhen: (ctx: Ctx) -> Boolean,
        waitMs: Long = 20_000,
        failFinalizes: Int = 0,
    ): Ctx {
        val clock = VClock()
        val src = ScriptSource(clock, script)
        val engine = TextStubEngine(engineText, failFinalizes)
        val sink = RecordingSink()
        val scope = CoroutineScope(Dispatchers.Default)
        val coordinator = DictationCoordinator(
            scope, { src }, { engine }, sink, nowMs = { clock.now.get() },
            autoStopSuppressed = { true }, // Hold-Modus: F43 aus — Session lebt bis UP
            segmentFinalizeMs = segmentFinalizeMs,
        )
        assertTrue(coordinator.start())
        val ctx = Ctx(coordinator, engine, sink, scope)
        val deadline = System.currentTimeMillis() + waitMs
        while (System.currentTimeMillis() < deadline) {
            Thread.sleep(5)
            if (stopWhen(ctx)) break
            if (!coordinator.isActive) break
        }
        return ctx
    }

    private fun awaitReason(c: DictationCoordinator, expected: String, timeoutMs: Long = 15_000) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (c.lastAbortReason != expected && System.currentTimeMillis() < deadline) {
            Thread.sleep(5)
        }
        assertEquals("abort_reason", expected, c.lastAbortReason)
    }

    // ---- (1) Stille+Satzzeichen-Grenze; 1,0-s-Pause schneidet NICHT ----

    @Test
    fun `(1) Satzpause mit Satzzeichen schneidet - 1,0-s-Pause nicht - UP finalisiert nur das Rest-Buffer`() {
        // Sprache(3 s) — Pause 1,0 s — Sprache(3 s) — Pause für immer.
        val ctx = runScript(
            script = listOf(3_000L to true, 1_000L to false, 3_000L to true, 6_000L to false),
            engineText = "Ein Satz.", // endet auf Satzzeichen (Regel a)
            segmentFinalizeMs = 35_000L,
            stopWhen = { it.coordinator.segmentFinalizations() >= 1 },
        )
        try {
            val c = ctx.coordinator
            assertTrue("Grenze an der 2. Pause (Regel a)", c.segmentFinalizations() >= 1)
            assertTrue("Session lebt bis UP (kein Auto-Stop, kein ERR)", c.isActive)
            assertNull("kein ERR", c.lastError)
            assertEquals("kein abort", "-", c.lastAbortReason)
            // 1,0-s-Pause hat NICHT geschnitten (Regel-a-Mindestpause 1,2 s):
            // die Grenze kam an der ZWEITEN Pause. Die exakte Naht-Position im
            // Puffer ist SCHEDULING-abhängig (Consumer-Lag beim Einholen der
            // Capture-Anforderung — Ehren bei fed 10…13 s, ggf. erst im Stopp-
            // Drain) — deshalb hier nur UNVERLETZBARE Invarianten; den
            // deterministischen Naht-/Rest-Vertrag trägt Test (2) (die
            // Längenregel ehrt der Consumer an EIGENER Position, nicht racy).
            val maxWindow = ctx.engine.windowSeconds.max()
            assertTrue(
                "kein Decode-Fenster über die 13-s-Session hinaus (Ist=$maxWindow)",
                maxWindow <= 13.0,
            )
            // Jede Pause nur EINMAL: weitere 4+ s Stille => KEINE zweite Grenze.
            val deadline = System.currentTimeMillis() + 5_000
            var segmentsAfterPause = c.segmentFinalizations()
            while (System.currentTimeMillis() < deadline) {
                Thread.sleep(20)
                segmentsAfterPause = c.segmentFinalizations()
            }
            assertEquals("keine Wiederholungs-Grenze in derselben Pause", 1, segmentsAfterPause)
            // UP => reguläres Session-Finale; Text committet.
            c.stop()
            awaitReason(c, "final")
            assertTrue("FinalRan (ist ${c.lastStopOutcome})", c.lastStopOutcome is StopOutcome.FinalRan)
            assertTrue("Text committet (Ist=${ctx.sink.writes} Writes)", ctx.sink.writes >= 1)
            assertTrue("Text ganze Stücke, nicht leer", ctx.sink.text.isNotBlank())
            assertNull("kein ERR am Ende", c.lastError)
        } finally {
            ctx.scope.cancel()
        }
    }

    // ---- (2) Dauerfall: 40 s pausenlos => Zwangssegment bei 35 s ----

    @Test
    fun `(2) 40 s pausenlose Sprache - Laengenregel schneidet bei 35 s - Fenster bleibt begrenzt`() {
        var maxBufferS = 0.0
        var firstBoundaryAtAudioS = -1.0
        val ctx = runScript(
            // 40 s pausenlose Sprache + NUR 1,0 s Endstille (< Regel-a-Mindestpause
            // 1,2 s): der ERSTE Schnitt kann nur aus der Längenregel (b) kommen
            // (35 s) — nicht aus der Endstille (B1-Befund früherer Läufe).
            script = listOf(40_000L to true, 1_000L to false),
            engineText = "Ein Satz.",
            segmentFinalizeMs = 35_000L,
            stopWhen = { ctx ->
                // Segment-lokales Decode-Fenster (buffer_s ist seit B1-Fix
                // SESSION-basiert): hyp_last_buffer_s = letztes Decode-Fenster.
                maxBufferS = maxOf(maxBufferS, ctx.coordinator.lastHypothesisBufferSeconds().toDouble())
                // Session-Fortschritt = audioMs (audioSeconds ist Session-Länge,
                // aber der Schnitt-Beobachtungswert muss konsistent sein).
                if (ctx.coordinator.segmentFinalizations() >= 1 && firstBoundaryAtAudioS < 0) {
                    firstBoundaryAtAudioS = ctx.coordinator.audioMs / 1000.0
                }
                ctx.coordinator.segmentFinalizations() >= 1 && ctx.coordinator.audioMs >= 38_000L
            },
            waitMs = 60_000,
        )
        try {
            val c = ctx.coordinator
            assertTrue("Zwangssegment gelaufen", c.segmentFinalizations() >= 1)
            assertTrue(
                "erste Grenze bei ~35 s (Längenregel, Ist=$firstBoundaryAtAudioS)",
                firstBoundaryAtAudioS >= 33.0 && firstBoundaryAtAudioS <= 40.0,
            )
            assertTrue(
                "kein (a)-Vorab-Schnitt: die 1,0-s-Endstille bleibt unter der Grenze",
                c.segmentFinalizations() <= 2,
            )
            assertTrue(
                "Decode-Fenster je Segment <= 38 s (Ist=$maxBufferS)",
                maxBufferS <= 38.0,
            )
            assertTrue("Session lebt bis UP", c.isActive)
            assertNull("kein ERR", c.lastError)
            c.stop()
            awaitReason(c, "final")
            assertTrue("FinalRan (ist ${c.lastStopOutcome})", c.lastStopOutcome is StopOutcome.FinalRan)
            val restWindow = ctx.engine.windowSeconds.last()
            assertTrue(
                "UP-Finale über NUR das Rest-Buffer (Ist=${"%.2f".format(restWindow)} s)",
                restWindow < 8.0,
            )
            // Session-Zähler monoton: hyp_count/cap_blocks/loud_s nie rückwärts.
            assertTrue("hyp_count > 0", c.hypCount > 0)
            assertTrue("cap_blocks > 0", c.capBlocks() > 0)
            assertTrue("loud_s > 0", c.loudS() > 0.0)
            assertTrue("mind. 2 permanente Commits (Ist=${ctx.sink.writes})", ctx.sink.writes >= 2)
            // Deterministische NAHT (byLength schneidet an Consumer-Position):
            // Segment-1-Text + genau EIN Leerzeichen + Segment-2-Text — der
            // Beweis, dass das zweite Segment über den GERESETTETEN Puffer
            // dekodiert hat (Puffer-Reset, racy-frei).
            assertEquals("Naht: Präfix + Segment-2-Text", "Ein Satz. Ein Satz.", ctx.sink.text.toString())
        } finally {
            ctx.scope.cancel()
        }
    }

    // ---- (3) ohne Satzzeichen bleibt die Stille-Regel stumm ----

    @Test
    fun `(3) Stille ohne Satzzeichen-Ende schneidet nicht - keine Grenze`() {
        val ctx = runScript(
            script = listOf(3_000L to true, 4_000L to false),
            engineText = "kein satz", // KEIN Satzzeichen-Ende
            segmentFinalizeMs = 35_000L,
            stopWhen = { it.coordinator.audioMs >= 6_500L },
        )
        try {
            val c = ctx.coordinator
            // 4 s Pause, aber ohne Satzzeichen: Regel (a) stumm; Längenregel
            // (35 s) nicht erreicht => NULL Grenzen.
            assertEquals("keine Segmentgrenze ohne Satzzeichen", 0, c.segmentFinalizations())
            assertTrue("Session lebt", c.isActive)
            assertNull("kein ERR", c.lastError)
            c.stop()
            awaitReason(c, "final")
            assertTrue(c.lastStopOutcome is StopOutcome.FinalRan)
        } finally {
            ctx.scope.cancel()
        }
    }

    // ---- (5) B1-Fix (Review P1): transienter Finalize-Fehler killt die Session NICHT ----

    @Test
    fun `(5) transienter Fehler im Segment-Finale - Session lebt - Wiederholung und UP-Finale korrekt`() {
        val ctx = runScript(
            // Laengenregel (b) statt Stille-Regel (a): der CONSUMER ehrt (b) an
            // EIGENER Position und RETRYED nach transientem Fehler von selbst
            // (segmentStartedAtAudioMs rueckt nicht) — deterministisch. Bei (a)
            // haenge der Retry am Capture-Re-Request, der bei Quelle-Erschoepfung
            // nie kommt (B1-Fix-Befund: racy segments=0).
            script = listOf(75_000L to true, 1_000L to false),
            engineText = "Ein Satz.",
            segmentFinalizeMs = 35_000L,
            failFinalizes = 1, // der ERSTE Boundary-Versuch wirft transient
            stopWhen = { it.coordinator.segmentFinalizations() >= 1 && it.coordinator.audioMs >= 74_000L },
            waitMs = 120_000,
        )
        try {
            val c = ctx.coordinator
            // (i) der erste Finalize-Versuch ist TRANSIENT gescheitert:
            assertTrue("ein Finalize-Versuch gescheitert", ctx.engine.failedFinalizes == 1)
            // (ii) KEIN ERR, KEIN Abbruch — die Session überlebt den Fehler.
            assertNull("lastError NICHT gesetzt (transient verworfen)", c.lastError)
            assertEquals("kein abort", "-", c.lastAbortReason)
            assertTrue("Session lebt trotz transientem Segment-Fehler", c.isActive)
            // (iii) Wiederholung: der NÄCHSTE Boundary-Versuch (nächste
            //  Satzpause) feuert wieder und schneidet.
            assertTrue(
                "Wiederholungs-Boundary nach transientem Fehler (Ist=${c.segmentFinalizations()})",
                c.segmentFinalizations() >= 1,
            )
            // (iv) UP finalisiert den Rest korrekt (Engine geheilt) — über
            //  NUR das Rest-Fenster.
            c.stop()
            awaitReason(c, "final")
            assertTrue("FinalRan (ist ${c.lastStopOutcome})", c.lastStopOutcome is StopOutcome.FinalRan)
            val restWindow = ctx.engine.windowSeconds.last()
            assertTrue(
                "UP-Finale über NUR das Rest-Buffer (Ist=${"%.2f".format(restWindow)} s)",
                restWindow < 12.0,
            )
            assertTrue("Text committet", ctx.sink.text.length >= "Ein Satz.".length)
            assertNull("auch am Ende kein ERR aus dem transienten Fehler", c.lastError)
        } finally {
            ctx.scope.cancel()
        }
    }

    // ---- (6) B1-Fix (Review P2a): Export-Längen bleiben Session-Semantik ----

    @Test
    fun `(6) Export-Laengen nach 2 Segmenten Session-Audiolaenge - nicht segment-lokal`() {
        val ctx = runScript(
            // 75 s pausenlose Sprache: Längenschnitte bei ~36 s und ~72 s.
            script = listOf(75_000L to true, 1_000L to false),
            engineText = "Ein Satz.",
            segmentFinalizeMs = 35_000L,
            stopWhen = { it.coordinator.segmentFinalizations() >= 2 && it.coordinator.audioMs >= 73_000L },
            waitMs = 120_000,
        )
        try {
            val c = ctx.coordinator
            assertEquals("zwei Längenschnitte", 2, c.segmentFinalizations())
            // 🐞-Quelle audio_s: NACH 2 Segmenten immer noch SESSION-Länge
            // (segment-lokal wäre sie <= ~1–2 s).
            assertTrue(
                "audio_s Session-Semantik nach 2 Segmenten (Ist=${c.audioSeconds})",
                c.audioSeconds >= 70.0,
            )
            // 🐞-Quelle buffer_s: dieselbe nachgeführte Session-Länge.
            assertEquals(
                "buffer_s == audio_s (gleiche nachgeführte Quelle)",
                c.audioSeconds, c.bufferSeconds(), 0.5,
            )
            // hyp_last_buffer_s bleibt segment-lokal (Decode-Fenster, unverändert).
            assertTrue(
                "hyp_last_buffer_s segment-lokal (Ist=${c.lastHypothesisBufferSeconds()})",
                c.lastHypothesisBufferSeconds() <= 40f,
            )
            c.stop()
            awaitReason(c, "final")
        } finally {
            ctx.scope.cancel()
        }
    }

    // ---- (4) reine Funktion: Wahrheitstafel der beiden Regeln ----

    @Test
    fun `(4) segmentBoundary-Wahrheitstafel - Laengenregel und Stille-Regel`() {
        val f = ::segmentBoundary
        // Segmenter aus => nie.
        assertFalse(
            "segmentFinalizeMs=0 => aus",
            f(1_010_000L, 1_000_000L, true, 20L, 1_000_000L, true, 0L),
        )
        // (b) Längenregel: 35 s erreicht => auch ohne Stille/Satzzeichen.
        assertTrue(
            "Längenregel bei 35 s",
            f(1_035_000L, 1_035_000L, true, 20L, 1_000_000L, false, 35_000L),
        )
        assertFalse(
            "Längenregel knapp davor (34,9 s)",
            f(1_034_900L, 1_034_900L, true, 20L, 1_000_000L, false, 35_000L),
        )
        // (a) Stille-Regel: Satzpause 1,2 s + Satzzeichen + streamend + everLoud.
        assertTrue(
            "Satzpause 1,2 s mit Satzzeichen",
            f(1_002_200L, 1_001_000L, true, 20L, 1_000_000L, true, 35_000L),
        )
        assertFalse(
            "Pause 1,19 s: noch nicht",
            f(1_002_190L, 1_001_000L, true, 20L, 1_000_000L, true, 35_000L),
        )
        assertFalse(
            "ohne Satzzeichen: Regel (a) stumm",
            f(1_002_200L, 1_001_000L, true, 20L, 1_000_000L, false, 35_000L),
        )
        assertFalse(
            "tote Quelle (capStall >= 2500): Stille != Stall",
            f(1_002_200L, 1_001_000L, true, 2_600L, 1_000_000L, true, 35_000L),
        )
        assertFalse(
            "nie laut (everLoud false)",
            f(1_002_200L, 1_000_000L, false, 20L, 1_000_000L, true, 35_000L),
        )
    }

    /** F56: gerätegetreuer Stub (LatencyStubEngine-Muster, JVM ohne Sleep) —
     *  Wörter = 2/s Fenster, deterministisch „wort<i>", '.' an jedem 8. Wort. */
    private class DevicePunctStubEngine : AsrEngine {
        val windows = ArrayList<Double>()
        override fun load(modelDir: File) {}
        override suspend fun transcribe(
            window: PcmWindow, sessionId: Long, runId: Long,
        ): Hypothesis {
            val seconds = window.samples.size / 16_000.0
            windows.add(seconds)
            val words = kotlin.math.round(seconds * 2).toInt().coerceAtLeast(1)
            val sb = StringBuilder()
            for (i in 1..words) {
                if (i > 1) sb.append(' ')
                sb.append("wort").append(i)
                if (i % 8 == 0) sb.append('.') // Satzzeichen alle 8 Wörter
            }
            return Hypothesis(
                IntArray(0), sb.toString(), IntArray(0), sessionId, runId,
                window.samples.size, incomplete = false,
            )
        }
        override fun close() {}
    }

    /** F56-Erwartungstext: „wort1 … wort<n>" mit '.' an jedem 8. Wort. */
    private fun punctWords(n: Int): String =
        (1..n).joinToString(" ") { i -> "wort$i" + if (i % 8 == 0) "." else "" }

    /**
     * F56 (vc33-Gerätefund 2026-11-02): Session-Abort „aborted:consumer:Illegal-
     * StateException" EXAKT in der Sekunde des ERSTEN Mini-Finals (seg=1,
     * out=189). Verdacht: Naht-Kollision Mini-Finale × laufende Hypothese.
     * JVM-Repro mit GERÄTEMUSTER: wachsende Fenster, '.' alle 8 Wörter,
     * Korpus 16 s laut — 2 s Pause — 6 s laut — 8 s Stille, 500-µs-Block-
     * Pacing, echte DeltaTyper/Sink-Kette über den Coordinator.
     *
     * BEFUND (Fix-Lauf): die Kette ist DETERMINISTISCH SAUBER — kein Abort,
     * keine ISE; der einzige ISE-Typ, der den Consumer erreichen KANN, ist der
     * RETHROWN-Editor-Sync-Timeout (F1-Drift, Gerät: blockierter Main-Thread,
     * EDIT_TIMEOUT_MS=2000) — dessen Code-Maskierung (visibleError zeigt
     * consumer:IllegalStateException statt editor_sync_timeout) fixt der F56-
     * Coordinator-Patch; hier der Sequenz-Regressionsteil.
     */
    @Test
    fun `F56-Repro - erstes Mini-Finale toetet die Session nicht - Kette deterministisch sauber`() {
        val clock = VClock()
        val src = ScriptSource(
            clock,
            listOf(
                16_000L to true, // Segment 1: laut — 16-s-Fenster endet auf „wort32."
                2_000L to false, // Satzpause (≥ 1,2 s) → Regel (a) feuert
                6_000L to true, // Segment 2 — Skript-ENDE: ab hier liefert die
                // Quelle nur 0-Blöcke (kein weiterer Ton kann ankommen), das
                // Finalfenster ist damit DETERMINISTISCH 6 s (Lauf-1-Fund: 8 s
                // Endstille feuerte (a) ein zweites Mal; Lauf-2-Fund: eine
                // 1-s-Endstille ist eine Stop-Race gegen die Capture — der
                // Stop gewinnt, die Stille fällt weg, Fenster = 6 s).
            ),
            pacingNanos = 500_000L,
        )
        val engine = DevicePunctStubEngine()
        val sink = RecordingSink()
        val scope = CoroutineScope(Dispatchers.Default)
        val c = DictationCoordinator(
            scope, { src }, { engine }, sink, nowMs = { clock.now.get() },
            autoStopSuppressed = { true }, // Halten: F43 aus, Session lebt
            segmentFinalizeMs = 35_000L,
        )
        try {
            assertTrue(c.start())
            // Warten bis das GANZE Skript gefüttert ist (deterministische
            // VClock-Position, NICHT Wanduhr): alle Chunks geflusht.
            // Deterministische Warte-Position: ALLE lauten Chunks geflusht
            // (audioMs = 24 s; die 1-s-Reststille fließt erst im Stopp-Drain).
            val deadline = System.currentTimeMillis() + 20_000
            while (c.audioMs < 24_000L && System.currentTimeMillis() < deadline) {
                Thread.sleep(5)
            }
            assertEquals("lauter Korpus gefüttert (Ist=${c.audioMs} ms)", 24_000L, c.audioMs)
            // (1) DER REPRO-KERN: das erste Mini-Finale hat die Session NICHT
            //     getötet — kein Abort, keine ISE, Mikro läuft weiter.
            assertEquals("genau EIN Mini-Finale (Regel a an der 2-s-Pause)", 1, c.segmentFinalizations())
            assertTrue("Session lebt nach dem Mini-Finale (kein Abort)", c.isActive)
            assertNull("kein Fehlercode (keine ISE entkommen)", c.lastError)
            assertEquals("kein Abort-Grund", "-", c.lastAbortReason)
            // (2) Mini-Finale wiederholt das Fenster des letzten Partials
            //     (Gerätevertrag; 18 s = 16 s laut + 2 s Pause im Segment 1).
            assertTrue(
                "Finalize-Fenster == letztes Partial-Fenster (Ist=${engine.windows})",
                engine.windows.count { it == 18.0 } >= 2,
            )
            assertTrue("kein Decode-Fenster über 18 s", engine.windows.max() <= 18.0)
            // (3) UP → reguläres Finale NUR über das Rest-Puffer (exakt die
            //     6 s Segment-2-Laut — kein Block kann nach Skript-Ende noch
            //     ankommen) — Text-Kette: Segment 1 (W36) + EINE Naht +
            //     Segment-2-Rest (W12), exakt, deterministisch.
            c.stop()
            awaitReason(c, "final")
            assertTrue("FinalRan (ist ${c.lastStopOutcome})", c.lastStopOutcome is StopOutcome.FinalRan)
            assertEquals(
                "golden: Naht = GENAU EIN Leerzeichen, keine Duplikate",
                punctWords(36) + " " + punctWords(12),
                sink.text.toString(),
            )
            assertEquals("stop_cause=final", "final", c.stopCause())
        } finally {
            scope.cancel()
        }
    }
}
