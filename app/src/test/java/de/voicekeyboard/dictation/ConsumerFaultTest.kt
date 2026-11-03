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
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * F13d (docs/REVIEW_F14_DIKTAT_KETTE.md): P1 — stiller Consumer-Tod (Editor-
 * Sync-Timeout aus feedAudio tötete den Consumer-Job, Capture lief weiter,
 * der unbegrenzte Kanal wuchs, keine Meldung); P2-1 — onDictationStopped las
 * LIVE-Controller-Zustand, den der nächste start() nullt; P2-2 — hyp_gap_ms
 * konnte bei Rückwärts-Uhr negativ werden.
 */
class ConsumerFaultTest {

    private class RecordingSink : EditorSink {
        var text: String = ""
        override fun replaceBeforeCursor(replaceLength: Int, newText: String) {
            text = text.dropLast(replaceLength) + newText
        }
        override fun textBeforeCursor(maxChars: Int): CharSequence? =
            text.takeLast(minOf(maxChars, text.length))
        override fun ownLength(): Int = text.length
    }

    private class FakeEngine : AsrEngine {
        var failNextWithTimeout = false
        var nextText = "hallo"

        override fun load(modelDir: File) {}

        override suspend fun transcribe(window: PcmWindow, sessionId: Long, runId: Long): Hypothesis {
            if (failNextWithTimeout) {
                failNextWithTimeout = false
                throw IllegalStateException("Editor-Sync Timeout: test")
            }
            return Hypothesis(
                tokenIds = IntArray(0), text = nextText, frameTimesMs = IntArray(0),
                sessionId = sessionId, runId = runId,
                audioSamples = window.samples.size, incomplete = false,
            )
        }

        override fun close() {}
    }

    private class FakeSource : AudioSource {
        val released = AtomicBoolean(false)
        private val samplesPerRead = 4000
        override fun start(sessionId: Long): Boolean = true
        override fun read(dst: ShortArray, timeoutMs: Int): Int {
            val n = minOf(samplesPerRead, dst.size)
            // F31: Block INHALT laut füllen (Amplitude 12000 ≈ −9 dBFS) — ohne
            // Inhalt wäre dst (0) still und der F31-Stille-Watchdog hätte bei
            // Last>3 s einen neuen (falschen) Abbruchpfad in diesen Test gebracht.
            java.util.Arrays.fill(dst, 0, n, 12_000.toShort())
            return n // sofort lieferbar — treibt den Consumer zügig an
        }
        override fun stopAndDrain(): List<PcmBlock> = emptyList()
        override fun release() { released.set(true) }
    }

    private fun seconds(n: Int): PcmBlock = PcmBlock(0, 0, ShortArray(n * 16_000))

    // (a) P1: feedAudio wirft Editor-Sync-Timeout-IllegalStateException → der
    //     Coordinator endet KONTROLLIERT: Fehlercode sichtbar, Capture läuft
    //     nicht mehr (active=false), der Ausgang ist als Outcome sichtbar.
    @Test
    fun `Editor-Sync-Timeout toetet den Consumer nicht still - Coordinator endet kontrolliert`() = runBlocking {
        val engine = FakeEngine()
        engine.failNextWithTimeout = true // Erste Transkription (nach 2 s Audio) reißt den Sync
        val sink = RecordingSink()
        val routeCalls = AtomicInteger(0)
        val scope = CoroutineScope(Dispatchers.Default)
        val coordinator = DictationCoordinator(
            scope = scope,
            audioSourceFactory = { FakeSource() },
            engineFactory = { engine },
            sink = sink,
            tracker = OwnershipTracker(),
            tickSamples = 4_000, // jeder Fake-Read löst sofort einen flush aus
        )
        // routeObserver zählt mit, dass die Route nach dem kontrollierten Ende freigegeben wird
        val coordWithRoute = DictationCoordinator(
            scope = scope,
            audioSourceFactory = { FakeSource() },
            engineFactory = { FakeEngine().apply { failNextWithTimeout = true } },
            sink = RecordingSink(),
            tracker = OwnershipTracker(),
            tickSamples = 4_000,
            routeObserver = { on -> if (on) routeCalls.incrementAndGet() else routeCalls.decrementAndGet() },
        )

        assertTrue(coordinator.start())
        // Warten, bis der Fehler durch die Kette gelaufen ist (bis 5 s, dann rot):
        withTimeout(5_000) {
            while (coordinator.isActive) kotlinx.coroutines.delay(20)
        }
        assertFalse("Capture darf nach dem Consumer-Fehler nicht weiterlaufen", coordinator.isActive)
        assertTrue(
            "Fehlercode muss sichtbar sein (Regel: jeder gefangene Fall setzt einen Grund)",
            coordinator.visibleError != null,
        )
        // Flaky-Entscheidung 2026-11-01 (BAU 23/24, F31, F31b, BAU 26): das Outcome
        // wird Worker-seitig gesetzt und ist dem Assert um ein paar Coroutine-
        // Dispatches hinterher — unter paralleler Suite-Last las der Sofort-Check
        // (rot bei 0,012 s Testzeit) zu früh. Deterministisch: bis zu 2 s in
        // 10-ms-Schritten auf das Outcome pollen. Bleibt es länger null, ist das
        // ein ECHTER Produkt-Fehler — die Frist wird bewusst NICHT erhöht.
        // F50 (Last-Flaky-Stabilisierung): der alte Poll pruefte auf null —
        // lastStopOutcome traegt aber bereits ab Konstruktion den Sentinel
        // IdleNoOp (NIE null), der Poll war ein No-Op und das Assert rannte
        // unter Suite-Last dem NoFinal-Schreibvorgang im Consumer-Catch davon
        // (dann las es den initialen IdleNoOp). Deterministisch: auf den
        // ERWARTETEN Endzustand pollen (Fake-Synchronisation ueber Zustand,
        // nicht Echtzeit-Geduld); Assertions unveraendert, Frist unveraendert
        // (2 s — ein echter Produktfehler timeoutet weiterhin rot).
        withTimeout(2_000) {
            while (coordinator.lastStopOutcome !is StopOutcome.NoFinal) {
                kotlinx.coroutines.delay(10)
            }
        }
        // Der Ausgang ist als StopOutcome sichtbar — kein stummer Tod:
        val outcome = coordinator.lastStopOutcome
        assertTrue("Outcome muss NoFinal mit Fehlercode tragen", outcome is StopOutcome.NoFinal)
        assertEquals("editor_sync_timeout", (outcome as StopOutcome.NoFinal).errorCode)
        // F56 (vc33-Gerätefund): der FEINE Drift-Code darf vom generischen
        // consumer:<Klasse> NICHT maskiert werden — visibleError und
        // lastAbortRule (stop_cause) tragen editor_sync_timeout (F1-Vertrag:
        // mapVisibleError zeigt die echte Ursache; vorher Gerätelog
        // „visibleError=consumer:IllegalStateException" → Else-Fallback).
        assertEquals("F56: visibleError unmaskiert", "editor_sync_timeout", coordinator.visibleError)
        assertEquals("F56: Abort-Grund fein (stop_cause-Familie)", "editor_sync_timeout", coordinator.lastAbortReason)

        // Zweiter Coordinator mit routeObserver: Route wird am Ende freigegeben
        assertTrue(coordWithRoute.start())
        withTimeout(5_000) { while (coordWithRoute.isActive) kotlinx.coroutines.delay(20) }
        assertEquals("routeObserver muss nach dem kontrollierten Ende auf off stehen", 0, routeCalls.get())
        coordWithRoute.shutdown()
        coordinator.shutdown()
    }

    // (b) Nach dem Fehler liefert stop() ein Outcome, das den Fehler trägt —
    //     KEIN IdleNoOp, kein stummes IDLE.
    @Test
    fun `stop nach Editor-Sync-Timeout liefert NoFinal mit Fehlercode`() = runBlocking {
        val f = FakeEngine().apply { failNextWithTimeout = true }
        val controller = DictationController(f, RecordingSink())
        assertTrue(controller.start())
        var thrown: IllegalStateException? = null
        try {
            controller.feedAudio(seconds(3))
        } catch (e: IllegalStateException) {
            thrown = e // der Wurf ist Vertrag: der Coordinator kann reagieren
        }
        assertTrue(thrown != null)
        assertEquals(DictationState.IDLE, controller.state)
        assertTrue("fail() muss den Fehler bis zur Sichtbarkeit bewahren", controller.failedThisSession)

        val outcome = controller.stop() // Loslassen nach dem Fail
        assertTrue("kein stummes No-Op", outcome !is StopOutcome.IdleNoOp)
        assertTrue(outcome is StopOutcome.NoFinal)
        assertEquals("editor_sync_timeout", (outcome as StopOutcome.NoFinal).errorCode)
    }

    // (c) P2-1: Snapshot — nach stop() wird start() erneut aufgerufen (Zustand
    //     genullt); die Beurteilung der ALTEN Sitzung bleibt im Outcome richtig.
    @Test
    fun `Outcome ist ein Snapshot - neuer Start verfaelscht die alte Beurteilung nicht`() = runBlocking {
        val f = FakeEngine().apply { failNextWithTimeout = true }
        val controller = DictationController(f, RecordingSink())
        assertTrue(controller.start())
        try {
            controller.feedAudio(seconds(3))
        } catch (e: IllegalStateException) {
            // Vertrag: Wurf nach oben
        }
        val oldOutcome = controller.stop() // NoFinal mit Fehlercode (Snapshot A)

        // „Schnell loslassen, sofort neu drücken": Sitzung B startet und NULLT
        // den Live-Zustand (failedThisSession=false, lastErrorCode=null):
        assertTrue(controller.start())
        assertFalse(controller.failedThisSession)
        assertEquals(null, controller.lastErrorCode)

        // Die Beurteilung von Sitzung A bleibt korrekt — NUR der Snapshot zählt:
        assertTrue(oldOutcome is StopOutcome.NoFinal)
        assertTrue("kein falsches Haekchen für die alte, gescheiterte Sitzung",
            oldOutcome !is StopOutcome.FinalRan)
        assertEquals("editor_sync_timeout", (oldOutcome as StopOutcome.NoFinal).errorCode)
        assertTrue("Fehler bleibt im Snapshot sichtbar", (oldOutcome as StopOutcome.NoFinal).failedWith)
    }

    // (d) P2-2: hyp_gap_ms wird nie negativ — injizierte rückwärtslugende Uhr.
    @Test
    fun `hyp_gap_ms ist nie negativ auch bei rueckwaertslaufender Uhr`() = runBlocking {
        val ticks = AtomicInteger(0)
        // Rückwärtsuhr: erste Messung (Hypothese) 2e9 ns, danach 1e9 ns.
        val backwardsClock = { if (ticks.incrementAndGet() == 1) 2_000_000_000L else 1_000_000_000L }
        val controller = DictationController(FakeEngine(), RecordingSink(), clock = backwardsClock)
        assertTrue(controller.start())
        controller.feedAudio(seconds(3)) // erzeugt eine Hypothese → lastHypAt = 2e9
        val gap = controller.currentHypGapMs()
        assertTrue("Diagnosewert muss plausibel sein, nie negativ (war $gap)", gap >= 0)
        assertEquals(" coerceAtLeast(0) klemmt den Rückwärtsstand auf 0", 0L, gap)
    }

    // (e) Normalpfad unverändert: Finale läuft, FinalRan ohne Fehler.
    @Test
    fun `Normalpfad unveraendert - FinalRan ohne Fehler und mit Text`() = runBlocking {
        val sink = RecordingSink()
        val controller = DictationController(FakeEngine(), sink)
        assertTrue(controller.start())
        controller.feedAudio(seconds(3))
        val outcome = controller.stop()
        assertTrue(outcome is StopOutcome.FinalRan)
        assertFalse((outcome as StopOutcome.FinalRan).failedWith)
        assertEquals(null, outcome.finalError)
        assertTrue("Text muss geschrieben sein", sink.text.contains("hallo"))
    }

    // (f) F13e: editor_sync_timeout bildet auf den NEUEN Schlüssel
    //     dictation_error_editor_busy ab (fester Soll-Schlüssel, via R-Reflection
    //     geprüft — JVM ohne Android-Runtime); unbekannte Codes fallen weiter
    //     auf den generischen Fallback (mapErrorTextRes == null).
    @Test
    fun `editor_sync_timeout bildet auf dictation_error_editor_busy ab - Unbekannte bleiben generisch`() {
        val res = de.voicekeyboard.ime.VoiceKeyboardService.mapErrorTextRes("editor_sync_timeout")
        assertEquals(de.voicekeyboard.R.string.dictation_error_editor_busy, res)
        // Fester Soll-Schlüssel: der R-Konstante gehört der NAME
        // dictation_error_editor_busy (nicht irgendein generischer Text).
        val field = Class.forName("de.voicekeyboard.R\$string").fields
            .first { it.getInt(null) == res }
        assertEquals("dictation_error_editor_busy", field.name)
        // Unbekannte Codes bleiben beim generischen Fallback (kein Editor-Busy):
        assertEquals(null, de.voicekeyboard.ime.VoiceKeyboardService.mapErrorTextRes("asr_partial_error"))
        assertEquals(null, de.voicekeyboard.ime.VoiceKeyboardService.mapErrorTextRes("unknown"))
        assertEquals(null, de.voicekeyboard.ime.VoiceKeyboardService.mapErrorTextRes("consumer=IllegalStateException"))
    }
}
