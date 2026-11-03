package de.voicekeyboard.dictation

import de.voicekeyboard.asr.AsrEngine
import de.voicekeyboard.asr.Hypothesis
import de.voicekeyboard.audio.AudioSource
import de.voicekeyboard.audio.PcmBlock
import de.voicekeyboard.audio.PcmWindow
import de.voicekeyboard.editor.EditorSink
import de.voicekeyboard.ime.DictationCoordinator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.atomic.AtomicLong

/**
 * F35 „Weg D" (docs/KORPUS_DIKTAT_TESTPLAN.md §1 D / §3): 30-/60-s-Takt-Lauf mit
 * Korpus-Fixture über die Coordinator-Naht (AudioSource → Consumer → feedAudio,
 * 320-Sample-Blöcke, Muster FakeWavAudioSource/StallTestSource) und Stub-Engine
 * (kein ONNX) mit konstanter 150-ms-Latenz auf injizierbarer virtueller Uhr —
 * KEIN Thread.sleep im Takt. Sieben Assertions (a)–(g) je Lauf; Regel 9: nur
 * Zähler/Dauern, nie Audio-/Textinhalte (auch nicht in Meldungen).
 *
 * Zeitmodell: Die virtuelle Uhr treibt Block-Fälligkeit und Watchdogs (nowMs);
 * die Hypothesen-/Decode-Uhr des Controllers ist echte nanoTime (über den
 * Coordinator nicht injizierbar) — decode_ms_* misst daher Realzeit, die
 * 150-ms-Stublatenz ist dort unsichtbar (Ist-Befund, gemeldet).
 */
class KorpusTaktTest {

    /** Virtuelle Uhr (ms); Start ≠ 0 (0 = „nie gestartet"-Sentinel des Watchdogs). */
    private class VClock {
        val now = AtomicLong(1_000_000L)
    }

    /**
     * Zeitgetreue Fixture-Quelle (FakeWavAudioSource-Muster): Block i ist fällig
     * bei Sessionstart + i×20 ms VIRTUELLER Zeit; liegt die Uhr durch Decode-
     * Latenz schon weiter vor, werden fällige Blöcke sofort nachgeholt (Capture
     * läuft parallel zur Inferenz — Rückstau-Modell). Nach dem Fixture-Ende
     * liefert read 0 OHNE Uhrfortschritt (kein künstlicher ERR_STALL).
     */
    private class KorpusSource(
        private val samples: ShortArray,
        private val clock: VClock,
    ) : AudioSource {
        private val blockMs = KorpusFixture.BLOCK_SAMPLES * 1000L / KorpusFixture.SAMPLE_RATE
        private var nextIdx = 0L
        private var baseMs = 0L
        @Volatile private var released = false

        override fun start(sessionId: Long): Boolean {
            baseMs = clock.now.get()
            return true
        }

        override fun read(dst: ShortArray, timeoutMs: Int): Int {
            if (released) return -1
            val from = (nextIdx * KorpusFixture.BLOCK_SAMPLES).toInt()
            if (from >= samples.size) {
                Thread.sleep(1) // Fixture zu Ende: kein Block, virtuelle Uhr steht
                return 0
            }
            val due = baseMs + nextIdx * blockMs
            while (true) { // Capture-Takt: Uhr auf das Fälligkeitsdatum bringen (CAS-Sicher)
                val cur = clock.now.get()
                if (cur >= due || clock.now.compareAndSet(cur, due)) break
            }
            val n = minOf(dst.size, KorpusFixture.BLOCK_SAMPLES, samples.size - from)
            System.arraycopy(samples, from, dst, 0, n)
            nextIdx++
            return n
        }

        override fun stopAndDrain(): List<PcmBlock> = emptyList()
        override fun release() {
            released = true
        }
    }

    /** Stub-Engine (kein ONNX): konstante [latencyMs] je transcribe auf einer
     *  EIGENEN injizierbaren Uhr + monoton wachsender Aufrufzähler (nur Zähler,
     *  keine Inhalte).
     *
     *  F36-Reparatur (nur Test-Modell): Die Latenz darf NICHT die Watchdog-Uhr des
     *  Coordinators fortschreiben — in Produktion läuft die Inferenz PARALLEL zur
     *  Echtzeit-Capture (Audio kommt während des Decodes weiter an, der Stall-
     *  Watchdog sieht keine Lücke). Schriebe der Stub die Latenz auf die geteilte
     *  Uhr, modelle er „Audio stockt während des Decodes“ — der ERR_STALL-
     *  Watchdog flaggt das dann zu Recht (Ist-Befund 60-s-Fall: Capture pumpte
     *  die Fixture vor dem Consumer leer, 30 Decodes x 150 ms = 4 500 ms virtuelle
     *  Stille > STALL_LIMIT_MS). Die 150 ms sind daher auf [latencyClock]
     *  deterministisch prüfbar: Fortschritt == latencyMs x Aufrufe. */
    private class KorpusStubEngine(
        private val latencyClock: VClock,
        private val latencyMs: Long = 150L,
        /** B1 (600-s-Lauf): Latenz α·T je Fenster (I3: 38,5 ms pro Audio-
         *  Sekunde) statt konstanter 150 ms — modeliert den KOSTEN-progressiven
         *  whole_buffer-Decode je SEGMENT (Puffer ≤ ~37 s). 0 = konstant. */
        private val alphaPerAudioSecond: Double = 0.0,
    ) : AsrEngine {
        var calls = 0
            private set

        override fun load(modelDir: File) {}

        override suspend fun transcribe(
            window: PcmWindow, sessionId: Long, runId: Long,
        ): Hypothesis {
            val latency =
                if (alphaPerAudioSecond > 0.0) {
                    (alphaPerAudioSecond * window.samples.size / 16_000.0).toLong()
                } else {
                    latencyMs
                }
            latencyClock.now.addAndGet(latency)
            calls++
            return Hypothesis(
                IntArray(0), "k", IntArray(0), sessionId, runId,
                window.samples.size, incomplete = false,
            )
        }

        override fun close() {}
    }

    /** Minimal-Sink: zählt nur; NIE textgeloggt (Regel 9). */
    private class RecordingSink : EditorSink {
        val text = StringBuilder()
        override fun replaceBeforeCursor(replaceLength: Int, newText: String) {
            text.delete(text.length - replaceLength, text.length)
            text.append(newText)
        }
        override fun textBeforeCursor(maxChars: Int): CharSequence = text.takeLast(maxOf(0, maxChars))
        override fun ownLength(): Int = text.length
    }

    @Test fun korpusTakt30s() = korpusLauf(30)

    @Test fun korpusTakt60s() = korpusLauf(60)

    // ---- B1 (PLAN_V0130 §6.3 (c)): 600-s-Korpus mit Segmenter (35-s-Deckel) ----

    /** B1: 600 s Diktat mit Zwischensegment-Finalisierung — die virtuelle Uhr
     *  läuft wie beim 30/60-s-Lauf; die Stub-Latenz ist α·T JE SEGMENT-Fenster
     *  (I3-Kurve: α = 38,5 ms/s, Fenster ≤ ~37 s ⇒ Latenz ≤ ~1,4 s).
     *  Assertions (virtuell): kein Abbruch, hyp_gap ≤ 2000 ms, Segmentanzahl
     *  ≈ ceil(600/35) ± Toleranz, Decode-Fenster je Segment begrenzt. */
    @Test fun korpusTakt600sSegmentiert() {
        val totalSeconds = 600
        val totalSamples = totalSeconds * KorpusFixture.SAMPLE_RATE
        val fixture = KorpusFixture.build(totalSamples)
        assertEquals("Fixture exakt Ziellänge", totalSamples, fixture.size)
        val clock = VClock()
        val latencyClock = VClock()
        val engine = KorpusStubEngine(latencyClock, alphaPerAudioSecond = 38.5)
        val scope = CoroutineScope(Dispatchers.Default)
        val coordinator = DictationCoordinator(
            scope, { KorpusSource(fixture, clock) }, { engine }, RecordingSink(),
            nowMs = { clock.now.get() },
            autoStopSuppressed = { true }, // Hold-Vertrag: kein F43-Auto-Stop
            segmentFinalizeMs = 35_000L, // B1: harter 35-s-Segment-Deckel
        )
        try {
            assertTrue(coordinator.start())
            var lastHyp = -1
            var gapMaxMs = -1L
            var maxBufferS = 0.0
            val deadline = System.currentTimeMillis() + 180_000
            while (System.currentTimeMillis() < deadline) {
                Thread.sleep(5)
                val hyp = coordinator.hypCount
                assertTrue("(a) hyp_count monoton", hyp >= lastHyp)
                lastHyp = hyp
                if (hyp > 0) gapMaxMs = maxOf(gapMaxMs, coordinator.hypGapMs())
                // B1-Fix (P2a): buffer_s ist SESSION-basiert — das segment-
                // lokale Decode-Fenster zeigt hyp_last_buffer_s.
                maxBufferS = maxOf(maxBufferS, coordinator.lastHypothesisBufferSeconds().toDouble())
                assertEquals("(b) kein Abbruch während des Laufs", "-", coordinator.lastAbortReason)
                assertNull("(c) kein ERR_STALL/ERR_SILENT", coordinator.lastError)
                assertTrue(
                    "(d) Decode-Fenster je Segment <= 40 s (Ist=${coordinator.lastHypothesisBufferSeconds()})",
                    coordinator.lastHypothesisBufferSeconds() <= 40.0,
                )
                if (coordinator.audioMs >= totalSeconds * 1000L - 1 || !coordinator.isActive) break
            }
            // Session-Mass: audioMs (gefuetterte Session-ms) — coordinator.audioSeconds
            // ist nach B1 Segment-LOKAL (Controller-Puffer, je Mini-Finale neu).
            assertEquals(
                "Fixture vollständig gefüttert (Session-audio_ms)", (totalSeconds * 1000L).toDouble(),
                coordinator.audioMs.toDouble(), 100.0,
            )
            coordinator.stop()
            val endDeadline = System.currentTimeMillis() + 30_000
            while (coordinator.lastAbortReason == "-" && System.currentTimeMillis() < endDeadline) {
                Thread.sleep(5)
            }
            assertTrue(
                "(e) Hyp-Latenzvertrag: hyp_gap_ms <= 2000 virtuell (Ist=$gapMaxMs)",
                gapMaxMs in 0L..2000L,
            )
            val segments = coordinator.segmentFinalizations()
            assertTrue(
                "(f) Segmentanzahl ≈ ceil(600/35)=18 ± Toleranz (Ist=$segments)",
                segments in 15..19,
            )
            assertTrue(
                "(g) Decode-Fenster je Segment <= 40 s über den Lauf (Ist=$maxBufferS)",
                maxBufferS <= 40.0,
            )
            assertTrue("(h) kein Abbruch am Ende", coordinator.lastAbortReason == "final")
            assertNull("(i) kein ERR", coordinator.lastError)
            assertTrue(
                "(j) Session-Zähler monoton: hyp_count > 0", coordinator.hypCount > 0,
            )
            System.out.println(
                "KORPUSIST case=600s-segmentiert segments=$segments" +
                    " audio_s=${"%.1f".format(coordinator.audioMs / 1000.0)}" +
                    " seg_window_s_max=$maxBufferS gap_max_ms=$gapMaxMs" +
                    " hyp_count=${coordinator.hypCount} engine_calls=${engine.calls}" +
                    " cap_blocks=${coordinator.capBlocks()}",
            )
        } finally {
            (scope.coroutineContext[Job])?.cancel()
        }
    }

    /** Ein Lauf: Fixture zeitgetreu einspeisen, 7 Assertions (a)–(g), Ist-Zeile drucken. */
    private fun korpusLauf(totalSeconds: Int) {
        val totalSamples = totalSeconds * KorpusFixture.SAMPLE_RATE
        val fixture = KorpusFixture.build(totalSamples)
        assertEquals("Fixture exakt Ziellänge", totalSamples, fixture.size)
        val clock = VClock()
        val latencyClock = VClock()
        val engine = KorpusStubEngine(latencyClock)
        val scope = CoroutineScope(Dispatchers.Default)
        val coordinator = DictationCoordinator(
            scope, { KorpusSource(fixture, clock) }, { engine }, RecordingSink(),
            nowMs = { clock.now.get() },
        )
        try {
            assertTrue(coordinator.start())
            var lastHyp = -1
            var gapMaxMs = -1L
            val deadline = System.currentTimeMillis() + 60_000
            while (System.currentTimeMillis() < deadline) {
                Thread.sleep(2)
                val hyp = coordinator.hypCount
                assertTrue("(a) hyp_count monoton: $hyp < $lastHyp", hyp >= lastHyp)
                lastHyp = hyp
                if (hyp > 0) gapMaxMs = maxOf(gapMaxMs, coordinator.hypGapMs())
                assertEquals("(f) abort_reason während des Laufs offen", "-", coordinator.lastAbortReason)
                assertNull("(g) kein ERR_STALL/ERR_SILENT während des Laufs", coordinator.lastError)
                if (coordinator.audioSeconds >= totalSeconds - 0.001 || !coordinator.isActive) break
            }
            assertEquals(
                "Fixture vollständig gefüttert (audio_s)", totalSeconds.toDouble(),
                coordinator.audioSeconds, 0.001,
            )
            coordinator.stop()
            val endDeadline = System.currentTimeMillis() + 30_000
            while (coordinator.lastAbortReason == "-" && System.currentTimeMillis() < endDeadline) {
                Thread.sleep(5)
            }
            // ---- sieben Assertions (Endzustand; während des Laufs: s. Schleife) ----
            assertTrue("(a) hyp_count > 0: ${coordinator.hypCount}", coordinator.hypCount > 0)
            assertTrue("(a) hyp_count monoton am Ende", coordinator.hypCount >= lastHyp)
            assertTrue("(b) hyp_gap_ms < 4000 im Lauf: Ist=$gapMaxMs", gapMaxMs in 0L..3999L)
            assertEquals(
                "(c) buffer_s == audio_s ± 0,5", coordinator.audioSeconds,
                coordinator.bufferSeconds(), 0.5,
            )
            assertTrue("(d) decode_ms_max < 1000: Ist=${coordinator.decodeMsMax()}", coordinator.decodeMsMax() < 1000)
            assertTrue("(e) cap_blocks > 0: Ist=${coordinator.capBlocks()}", coordinator.capBlocks() > 0)
            assertTrue("(e) loud_s > 0: Ist=${coordinator.loudS()}", coordinator.loudS() > 0.0)
            assertEquals("(f) abort_reason erst am Ende gesetzt", "final", coordinator.lastAbortReason)
            assertNull("(g) kein ERR_STALL/ERR_SILENT", coordinator.lastError)
            // Stub-Vertrag: konstante 150-ms-Latenz je Decode auf der eigenen Uhr
            // (deterministisch, nur Zahlen) — Startwert 1 000 000 abzüglich.
            assertEquals(
                "Stub-Latenz = 150 ms x Aufrufe",
                150L * engine.calls,
                latencyClock.now.get() - 1_000_000L,
            )
            // Ist-Zeile (nur Zahlen) — landet im Test-XML, NICHT in Produktlogs.
            System.out.println(
                "KORPUSIST case=${totalSeconds}s parts=" +
                    KorpusFixture.partSeconds().joinToString("/") { "%.4f".format(it) } +
                    " audio_s=${"%.1f".format(coordinator.audioSeconds)}" +
                    " buffer_s=${"%.1f".format(coordinator.bufferSeconds())}" +
                    " hyp_count=${coordinator.hypCount} gap_max_ms=$gapMaxMs" +
                    " decode_ms_max=${coordinator.decodeMsMax()}" +
                    " cap_blocks=${coordinator.capBlocks()}" +
                    " loud_s=${"%.1f".format(coordinator.loudS())} engine_calls=${engine.calls}",
            )
        } finally {
            (scope.coroutineContext[Job])?.cancel()
        }
    }
}
