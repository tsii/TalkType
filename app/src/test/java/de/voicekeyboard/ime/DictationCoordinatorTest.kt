package de.voicekeyboard.ime

import de.voicekeyboard.asr.AsrEngine
import de.voicekeyboard.asr.Hypothesis
import de.voicekeyboard.audio.AudioErrorListener
import de.voicekeyboard.audio.AudioSource
import de.voicekeyboard.audio.PcmBlock
import de.voicekeyboard.audio.PcmWindow
import de.voicekeyboard.dictation.DictationState
import de.voicekeyboard.editor.EditorSink
import kotlinx.coroutines.CoroutineScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** Fake: spielt vorab definierte Blöcke asynchron ab; optional mit Fehler. */
private class FakeAudioSource(
    blocks: List<ShortArray>,
    private val failOnStart: Boolean = false,
    private val errorAfterBlocks: Boolean = false,
) : AudioSource {
    val startCalls = AtomicInteger(0)
    val released = AtomicInteger(0)
    val queue = ConcurrentLinkedQueue<ShortArray>()
    private val started = AtomicBoolean(false)
    private val stopping = AtomicBoolean(false)
    var errorListener: AudioErrorListener? = null

    init {
        blocks.forEach { queue.add(it) }
    }

    override fun start(sessionId: Long): Boolean {
        if (failOnStart) return false
        if (!started.compareAndSet(false, true)) return false
        startCalls.incrementAndGet()
        if (errorAfterBlocks) {
            Thread {
                Thread.sleep(120)
                errorListener?.onError("invalidOperation", "test")
            }.apply { isDaemon = true }.start()
        }
        return true
    }

    private var pending: ShortArray? = null

    override fun read(dst: ShortArray, timeoutMs: Int): Int {
        if (stopping.get()) return -1
        var head = pending
        if (head == null) head = queue.poll()
        if (head != null) {
            val n = minOf(dst.size, head.size)
            System.arraycopy(head, 0, dst, 0, n)
            pending = if (n < head.size) head.copyOfRange(n, head.size) else null
            return n
        }
        Thread.sleep(2)
        return 0 // Timeout/keine Daten
    }

    override fun stopAndDrain(): List<PcmBlock> {
        stopping.set(true)
        val out = mutableListOf<PcmBlock>()
        while (true) {
            val rest = queue.poll() ?: break
            out.add(PcmBlock(-1, -1, rest))
        }
        return out
    }

    override fun release() {
        released.incrementAndGet()
    }
}

/** Fake-Engine: zählt Aufrufe, sieht die Audio-Fenster. */
private class FakeAsrEngine : AsrEngine {
    val transcribeCalls = AtomicInteger(0)
    val seenSampleCounts = CopyOnWriteArrayList<Int>()
    @Volatile var failOnTranscribe = false
    override fun load(modelDir: File) {}
    override suspend fun transcribe(window: PcmWindow, sessionId: Long, runId: Long): Hypothesis {
        transcribeCalls.incrementAndGet()
        if (failOnTranscribe) throw IllegalStateException("engine-boom")
        seenSampleCounts.add(window.samples.size)
        val text = "x".repeat((window.audioSeconds * 3).toInt().coerceAtLeast(1))
        return Hypothesis(
            IntArray(1), text, IntArray(1), sessionId, runId,
            window.samples.size, incomplete = false,
        )
    }

    val closeCalls = java.util.concurrent.atomic.AtomicInteger(0)
    override fun close() {
        closeCalls.incrementAndGet()
    }
}

/** Fake-Sink: protokolliert alle Schreibvorgänge. */
private class FakeSink : EditorSink {
    val writes = CopyOnWriteArrayList<String>()
    override fun replaceBeforeCursor(replaceLength: Int, newText: String) {
        writes.add("repl:$replaceLength:$newText")
    }

    override fun textBeforeCursor(maxChars: Int): CharSequence? =
        writes.joinToString(separator = "").takeLast(maxChars).ifEmpty { null }

    override fun ownLength(): Int = 0
}

/** F28b1-Fake: jede read verschiebt eine virtuelle Uhr um [stepMs]; liefert
 *  optional pro read einen Block (Takt-Fall) oder dauerhaft 0 (Stall-Fall). */
private class StallTestSource(
    private val deliver: Boolean,
    private val stepMs: Long,
    private val advance: (Long) -> Unit,
    private val silent: Boolean = false, // F34: stille Blöcke (RMS 0 < SILENCE_RMS)
) : AudioSource {
    val reads = AtomicInteger(0)
    // F31: Block-Lautstärke über der SILENCE-Schwelle (0,01) — dieser Test prüft
    // den Takt (Stall), nicht die Stille; stille Blöcke würden jetzt den
    // F31-Stille-Abbruch auslösen und den Takt-Fall untestbar machen.
    // F34: silent=true liefert Nullen (RMS 0) für den ERR_SILENT-Fall.
    private val data: ShortArray =
        if (silent) ShortArray(1600) { 0 } else ShortArray(1600) { 12_000 }

    override fun start(sessionId: Long): Boolean = true

    override fun read(dst: ShortArray, timeoutMs: Int): Int {
        reads.incrementAndGet()
        advance(stepMs)
        if (!deliver) return 0 // Timeout/keine Daten (Stall-Fall)
        val n = minOf(dst.size, data.size)
        System.arraycopy(data, 0, dst, 0, n)
        Thread.sleep(2)
        return n
    }

    override fun stopAndDrain(): List<PcmBlock> = emptyList()
    override fun release() {}
}

/** A1 (PLAN_V0133 §1): Kaltstart-Fake-Quelle — read #0 liefert KEINEN Block
 *  (Startlatenz [firstStepMs] nach src.start()); danach liefern die nächsten
 *  [deliverReads] reads je einen LAUTEN Block (RMS > SILENCE_RMS) im
 *  [stepMs]-Takt; deliverReads <= 0 = endlos. Nach der Lieferphase kehrt die
 *  Quelle dauerhaft 0 zurück (Quelltod) — JEDE read schiebt die injizierte
 *  Uhr, auch im Tod (Watchdog-Fenster läuft virtuell). */
private class ColdStartSource(
    private val firstStepMs: Long,
    private val stepMs: Long,
    private val deliverReads: Int,
    private val advance: (Long) -> Unit,
) : AudioSource {
    private val index = AtomicInteger(0)
    private val data = ShortArray(1600) { 12_000 } // laut: RMS ≈ 0,37 > SILENCE_RMS

    override fun start(sessionId: Long): Boolean = true

    override fun read(dst: ShortArray, timeoutMs: Int): Int {
        val i = index.getAndIncrement()
        advance(if (i == 0) firstStepMs else stepMs)
        val deliver = i >= 1 && (deliverReads <= 0 || i < 1 + deliverReads)
        if (!deliver) {
            Thread.sleep(2)
            return 0 // Timeout/keine Daten (Startlatenz bzw. Quelltod)
        }
        val n = minOf(dst.size, data.size)
        System.arraycopy(data, 0, dst, 0, n)
        Thread.sleep(2)
        return n
    }

    override fun stopAndDrain(): List<PcmBlock> = emptyList()
    override fun release() {}
}

/** A1 (PLAN_V0133 §1): Fake-Engine, deren load() die injizierte Uhr um
 *  [loadMs] schiebt — modelliert den Kaltstart (Modell-Load ~4,5 s) auf der
 *  Watchdog-Uhr. transcribe/close wie FakeAsrEngine (nur Längen, Regel 9). */
private class SlowLoadEngine(
    private val loadMs: Long,
    private val advance: (Long) -> Unit,
) : AsrEngine {
    override fun load(modelDir: File) {
        advance(loadMs)
    }

    override suspend fun transcribe(window: PcmWindow, sessionId: Long, runId: Long): Hypothesis {
        val text = "x".repeat((window.audioSeconds * 3).toInt().coerceAtLeast(1))
        return Hypothesis(
            IntArray(1), text, IntArray(1), sessionId, runId,
            window.samples.size, incomplete = false,
        )
    }

    override fun close() {}
}

class DictationCoordinatorTest {

    private fun newScope() = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.Dispatchers.Default,
    )

    private fun shutdown(scope: CoroutineScope) {
        (scope.coroutineContext[kotlinx.coroutines.Job])?.cancel()
    }

    private fun block(value: Byte, n: Int) = ShortArray(n) { ((value + it % 3).toInt()).toShort() }

    // (1) Doppel-Start ist idempotent (kein Neustart der Quelle)
    @Test fun `Doppel-Start ist idempotent`() {
        val src = FakeAudioSource(listOf(ShortArray(16000)))
        val scope = newScope()
        val c = DictationCoordinator(scope, { src }, { FakeAsrEngine() }, FakeSink())
        assertTrue(c.start())
        assertFalse("zweiter start() muss false liefern", c.start())
        assertEquals("Quelle darf nur 1x gestartet sein", 1, src.startCalls.get())
        c.stop()
        awaitIdle(c)
        shutdown(scope)
    }

    // (2) Stop ohne Start ist ein No-Op
    @Test fun `Stop ohne Start ist NoOp`() {
        val c = DictationCoordinator(newScope(), { FakeAudioSource(emptyList()) }, { FakeAsrEngine() }, FakeSink())
        c.stop()
        assertEquals(DictationState.IDLE, c.state)
        assertNull(c.lastError)
    }

    // (F26a) abort-Grund wird gemerkt; ein spaeteres Stop ueberschreibt ihn NICHT
    @Test fun `abort-Grund wird gemerkt und vom Stop nicht ueberschrieben`() {
        val scope = newScope()
        val c = DictationCoordinator(scope, { FakeAudioSource(listOf(ShortArray(16000))) }, { FakeAsrEngine() }, FakeSink())
        assertEquals("-", c.lastAbortReason)
        assertTrue(c.start())
        assertEquals("start() setzt auf -", "-", c.lastAbortReason)
        c.abort("editor_change")
        assertEquals("editor_change", c.lastAbortReason)
        c.stop() // active=false — darf den Abort-Grund nicht drueberschreiben
        assertEquals("editor_change", c.lastAbortReason)
        awaitIdle(c)
        shutdown(scope)
    }

    /** Wartet (nur im Test!) bis der asynchrone stop()-Worker fertig ist. */
    private fun awaitIdle(c: DictationCoordinator, timeoutMs: Long = 3000) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (c.state != DictationState.IDLE && System.currentTimeMillis() < deadline) {
            Thread.sleep(10)
        }
        assertEquals("stop()-Worker muss nach IDLE enden", DictationState.IDLE, c.state)
    }

    // (3) Blöcke erreichen den Controller in Reihenfolge — nachweisbar an den
    //     Sample-Zahlen der transcribe-Fenster (monoton wachsend, vollständig)
    @Test fun `Bloecke erreichen den Controller vollständig in Reihenfolge`() {
        // 3 Blöcke à 8000 → Ticker 8000: ein feedAudio je Block-Aggregat
        val src = FakeAudioSource(listOf(block(1, 8000), block(2, 8000), block(3, 8000)))
        val engine = FakeAsrEngine()
        val c = DictationCoordinator(newScope(), { src }, { engine }, FakeSink(), tickSamples = 8000)
        assertTrue(c.start())
        // Partials brauchen laut Controller ≥2 s → präventiv Total füllen:
        // einfach warten, bis der Consumer alles konsumiert und dann stop() (Finale).
        val deadline = System.currentTimeMillis() + 2000
        while (System.currentTimeMillis() < deadline && !src.queue.isEmpty()) Thread.sleep(10)
        c.stop()
        awaitIdle(c)
        // Finale transkribiert den GESAMTPUFFER: 24000 Samples in EINEM Fenster
        assertTrue("mind. ein transcribe", engine.transcribeCalls.get() >= 1)
        assertEquals(
            "Finale muss über alle 3 Blöcke laufen (24000 Samples)",
            24000,
            engine.seenSampleCounts.max(),
        )
        assertNull(c.lastError)
        shutdown(newScope())
    }

    // (4) Stop nach < 2 s Audio final-dekodiert trotzdem (Guard nur für Partials)
    @Test fun `Stop nach kurzer Aufnahme final-dekodiert trotzdem`() {
        val src = FakeAudioSource(listOf(ShortArray(8000))) // 0,5 s
        val engine = FakeAsrEngine()
        val c = DictationCoordinator(newScope(), { src }, { engine }, FakeSink())
        assertTrue(c.start())
        val before = engine.transcribeCalls.get()
        Thread.sleep(200) // kein Partial möglich (< 2 s Guard)
        assertEquals("kein Partial vor 2 s", before, engine.transcribeCalls.get())
        c.stop()
        awaitIdle(c)
        assertTrue("Final-Decode muss laufen", engine.transcribeCalls.get() > before)
        assertEquals(DictationState.IDLE, c.state)
        shutdown(newScope())
    }

    // (5) Fehler der AudioSource → abort-Zustand, Editor unverändert
    @Test fun `AudioSource-Fehler fuehrt zu abort ohne Schreiben`() {
        val src = FakeAudioSource(emptyList(), errorAfterBlocks = true)
        val sink = FakeSink()
        val c = DictationCoordinator(newScope(), { src }, { FakeAsrEngine() }, sink)
        src.errorListener = c.errorListener // Verdrahtung wie in der IME
        assertTrue(c.start())
        val latch = CountDownLatch(1)
        val watcher = Thread {
            val deadline = System.currentTimeMillis() + 3000
            while (c.lastError == null && System.currentTimeMillis() < deadline) Thread.sleep(10)
            latch.countDown()
        }.apply { start() }
        latch.await(5, TimeUnit.SECONDS)
        assertEquals(DictationState.IDLE, c.state)
        assertNotEquals("Fehlercode muss gesetzt sein", null, c.lastError)
        assertEquals("Editor darf nichts erhalten haben", 0, sink.writes.size)
        shutdown(newScope())
    }

    // (6) Editorwechsel während RECORDING → kein Schreiben danach
    @Test fun `Editorwechsel waehrend RECORDING stoppt Schreiben`() {
        // 2,5 s Audio, Ticker 8000 → Partials schreiben
        val src = FakeAudioSource(listOf(ShortArray(20000)))
        val engine = FakeAsrEngine()
        val sink = FakeSink()
        val c = DictationCoordinator(newScope(), { src }, { engine }, sink, tickSamples = 8000)
        assertTrue(c.start())
        val deadline = System.currentTimeMillis() + 3000
        while (sink.writes.isEmpty() && System.currentTimeMillis() < deadline) Thread.sleep(10)
        c.onEditorChanged() // Besitz verwerfen
        val writesAtChange = sink.writes.size
        // weitere Partials dürfen NICHT mehr schreiben
        val deadline2 = System.currentTimeMillis() + 800
        while (System.currentTimeMillis() < deadline2) Thread.sleep(50)
        assertEquals("nach Editorwechsel kein Schreiben", writesAtChange.toLong(), sink.writes.size.toLong())
        c.abort()
        shutdown(newScope())
    }

    // (7) R-2-Fehlerzustand bleibt nach stop()/Loslassen erhalten (visibleError sticky)
    @Test fun `Fehlerzustand bleibt nach stop sichtbar`() {
        // Engine wirft bei jedem transcribe ⇒ controller.lastErrorCode gesetzt
        val src = FakeAudioSource(listOf(ShortArray(16000))) // 1 s → Partial-Guard greift nicht,
        // aber feedAudio dekodiert erst ab 2 s — wir erzwingen den Fehler über das Finale:
        val engine = FakeAsrEngine().apply { failOnTranscribe = true }
        val c = DictationCoordinator(newScope(), { src }, { engine }, FakeSink(), tickSamples = 16000)
        assertTrue(c.start())
        c.stop()
        awaitIdle(c)
        assertTrue("Fehler muss sichtbar sein", c.visibleError != null)
        // „Loslassen" (erneutes updateStatus-Äquivalent): visibleError bleibt aktiv
        assertTrue("sticky: Fehler bleibt bis zur nächsten bewussten Aktion", c.visibleError != null)
    }

    // (8) Erfolg nach Fehler löscht den Fehler (neue Session, keine Exception)
    @Test fun `Erfolg nach Fehler loescht visibleError`() {
        val engine = FakeAsrEngine().apply { failOnTranscribe = true }
        val c = DictationCoordinator(newScope(), { FakeAudioSource(listOf(ShortArray(16000))) }, { engine }, FakeSink())
        assertTrue(c.start())
        c.stop()
        awaitIdle(c)
        assertTrue(c.visibleError != null)
        // Fehlerursache beheben + neue bewusste Aktion (Mic-Down → start)
        engine.failOnTranscribe = false
        val c2start = c.start() // neue Session, neues Audio
        if (c2start) {
            c.stop()
            awaitIdle(c)
        }
        // bei Erfolg ist visibleError (controller.lastErrorCode) wieder null
        assertTrue("nach erfolgreichem Finale kein Fehler", c.visibleError == null)
    }

    // (9) shutdown() ist idempotent und schließt genau einmal (N-3/C-4)
    @Test fun `shutdown ist idempotent und schliesst genau einmal`() {
        val engine = FakeAsrEngine()
        val c = DictationCoordinator(newScope(), { FakeAudioSource(emptyList()) }, { engine }, FakeSink())
        c.shutdown()
        c.shutdown() // zweiter Aufruf: No-Op
        c.shutdown() // dritter Aufruf: No-Op
        assertEquals("Engine genau 1x geschlossen", 1, engine.closeCalls.get())
    }

    // (10) Editor-Write läuft über den injizierten Executor (N-1, ohne Looper belegbar)
    @Test fun `EditorWrites laufen ueber den injizierten Executor`() {
        val executorThread = java.util.concurrent.atomic.AtomicReference<String?>(null)
        val directExecutor = java.util.concurrent.Executor { runnable ->
            executorThread.set(Thread.currentThread().name)
            runnable.run()
        }
        val sink = de.voicekeyboard.editor.InputConnectionSink(
            connectionProvider = { null }, // Editor weg → EditorUnavailableException, aber der Executor lief
            editExecutor = directExecutor,
        )
        try {
            sink.replaceBeforeCursor(0, "x")
            org.junit.Assert.fail("EditorUnavailableException erwartet")
        } catch (e: de.voicekeyboard.editor.EditorUnavailableException) {
            // belegt: replaceBeforeCursor wurde ueber den Executor ausgefuehrt
            org.junit.Assert.assertNotNull("Executor muss gelaufen sein", executorThread.get())
        }
    }

    // F28b1 (a): keine Blöcke > 3000 ms → stall-Fehler GENAU EINMAL + sauberes
    // Session-Ende. (Genau-einmal strukturell: Single-Thread-Loop, return nach
    // dem Feuer — der Read-Zähler belegt, dass die Loop beendet ist und kein
    // zweiter Fehler mehr entstehen kann.)
    @Test fun `F28b1 ohne Bloecke ueber 3000 ms loest genau einen stall-Aus`() {
        // Virtuelle Uhr startet bei 1 000 000 (NICHT 0 — 0 ist das
        // "nie gestartet"-Sentinel von lastBlockAtMs; die Produktionsuhr
        // (nanoTime/1e6) liefert nie 0).
        val base = 1_000_000L
        val now = AtomicLong(base)
        val src = StallTestSource(deliver = false, stepMs = 1000, advance = { now.addAndGet(it) })
        val scope = newScope()
        val c = DictationCoordinator(scope, { src }, { FakeAsrEngine() }, FakeSink(), nowMs = { now.get() })
        assertTrue(c.start())
        val deadline = System.currentTimeMillis() + 3000
        while (c.lastAbortReason != "audio_error:stall" && System.currentTimeMillis() < deadline) {
            Thread.sleep(5)
        }
        assertEquals("Stall muss die Session abbrechen", "audio_error:stall", c.lastAbortReason)
        assertEquals("kontrollierter Fehlercode, keine Audio-Inhalte", "stall:no_audio", c.lastError)
        // abort() setzt den Grund BEVOR controller.abort() IDLE macht — kurz warten.
        val deadline2 = System.currentTimeMillis() + 3000
        while (c.state != DictationState.IDLE && System.currentTimeMillis() < deadline2) {
            Thread.sleep(5)
        }
        assertEquals(DictationState.IDLE, c.state)
        assertFalse("Session muss beendet sein", c.isActive)
        assertTrue("Stall-Dauer nach Abbruch lesbar (Export)", c.capStallMs() >= 3000)
        assertEquals("keine Blöcke in dieser Session", 0, c.capBlocks())
        val readsAtAbort = src.reads.get()
        Thread.sleep(50)
        assertEquals("Capture-Loop beendet — kein wiederholter Fehler", readsAtAbort, src.reads.get())
        shutdown(scope)
    }

    // F28b1 (b): Blöcke im 500-ms-Takt → Stall-Uhr wird ständig zurückgesetzt,
    // kein stall-Fehler, die Session übersteht virtual weit mehr als 3000 ms.
    @Test fun `F28b1 Blöcke im 500 ms Takt loesen keinen stall`() {
        // Virtuelle Uhr startet bei 1 000 000 (s. Test a) — 0 ist reserviert.
        val base = 1_000_000L
        val now = AtomicLong(base)
        val src = StallTestSource(deliver = true, stepMs = 500, advance = { now.addAndGet(it) })
        val scope = newScope()
        val c = DictationCoordinator(scope, { src }, { FakeAsrEngine() }, FakeSink(), nowMs = { now.get() })
        assertTrue(c.start())
        val deadline = System.currentTimeMillis() + 3000
        while (now.get() < base + 10_000 && System.currentTimeMillis() < deadline) Thread.sleep(10)
        assertTrue("virtuelle Zeit muss 3 s weit uebertreffen", now.get() >= base + 10_000)
        assertFalse("kein stall-Abbruch", c.lastAbortReason == "audio_error:stall")
        assertNull("kein Fehlercode", c.lastError)
        assertTrue("Session läuft weiter", c.isActive)
        assertTrue("Stall bleibt unter der Grenze (500-ms-Takt)", c.capStallMs() <= 1000)
        assertTrue("Blöcke werden gezählt", c.capBlocks() >= 15)
        c.stop()
        awaitIdle(c)
        shutdown(scope)
    }

    // F28b2 (a): start() setzt die Session-Ende-Diagnose frisch — der Export
    // zeigt nie Ereignisse/Zustände der VORHERIGEN Session.
    @Test fun `F28b2 start loescht mic_rel und setzt status_shown auf ready`() {
        val scope = newScope()
        val c = DictationCoordinator(scope, { FakeAudioSource(listOf(ShortArray(16000))) }, { FakeAsrEngine() }, FakeSink())
        // alte Werte simulieren (z. B. ueberlebte Felder aus der letzten Session)
        c.setMicRel("holdcancel:999")
        c.setStatusShown("error")
        assertTrue(c.start())
        assertEquals("mic_rel wird bei start() geleert", "", c.micRel())
        assertEquals("status_shown wird bei start() auf ready gesetzt", "ready", c.statusShown())
        c.stop()
        awaitIdle(c)
        shutdown(scope)
    }

    // F28b2 (b): Setter/Getter-Runde — die kontrollierten Codes kommen
    // unverändert im Export an (Regel 9: nur Codes/ms, keine Inhalte).
    @Test fun `F28b2 Setter-Getter-Runde liefert die Codes unveraendert`() {
        val c = DictationCoordinator(newScope(), { FakeAudioSource(emptyList()) }, { FakeAsrEngine() }, FakeSink())
        c.setMicRel("up:1234")
        assertEquals("up:1234", c.micRel())
        c.setMicRel("holdcancel:5000")
        assertEquals("holdcancel:5000", c.micRel())
        for (code in listOf("ready", "dictat", "finalizing", "error")) {
            c.setStatusShown(code)
            assertEquals(code, c.statusShown())
        }
        // sessionStartedAtMs: 0 ohne Session, > 0 nach start()
        assertEquals(0L, c.sessionStartedAtMs)
    }

    // F34/P2-1 (REVIEW_F29_F33, Nutzer-Bug „grün hängt“): ein Watchdog-Abbruch
    // (ERR_STALL) muss den Service-Observer (onAborted) feuern — der setzt die
    // Mic-View auf neutral. Testet den Coordinator-Grenzübergang: Observer
    // feuert genau einmal mit dem Grund, danach ist die Session IDLE, und die
    // View-Ableitung (micUiRecording — was VKS syncMicUi liest) ist neutral.
    @Test fun `F34 ERR_STALL-Abbruch feuert onAborted und der Mic-Zustand ist neutral`() {
        val base = 1_000_000L
        val now = AtomicLong(base)
        val src = StallTestSource(deliver = false, stepMs = 1000, advance = { now.addAndGet(it) })
        val scope = newScope()
        val c = DictationCoordinator(scope, { src }, { FakeAsrEngine() }, FakeSink(), nowMs = { now.get() })
        val aborts = java.util.concurrent.CopyOnWriteArrayList<String>()
        c.onAborted = { aborts.add(it) }
        assertTrue(c.start())
        val deadline = System.currentTimeMillis() + 3000
        while (c.lastAbortReason != "audio_error:stall" && System.currentTimeMillis() < deadline) {
            Thread.sleep(5)
        }
        assertEquals("Stall muss die Session abbrechen", "audio_error:stall", c.lastAbortReason)
        assertEquals("onAborted feuert genau einmal mit dem Grund", listOf("audio_error:stall"), aborts)
        assertEquals("Session ist IDLE", DictationState.IDLE, c.state)
        assertFalse("Mic-View-Ableitung nach Watchdog-Abbruch ist neutral", micUiRecording(c.state))
        shutdown(scope)
    }

    // F34/P2-1 (b): ERR_SILENT (Session war nie laut, Blöcke laufen) — gleicher
    // Endpfad wie ERR_STALL: Observer feuert, View-Ableitung wird neutral.
    // (Blöcke sind laut geliefert, aber alle unter der SILENCE-Schwelle.)
    @Test fun `F34 ERR_SILENT-Abbruch (nie laut) feuert onAborted und der Mic-Zustand ist neutral`() {
        val base = 1_000_000L
        val now = AtomicLong(base)
        val src = StallTestSource(deliver = true, stepMs = 500, silent = true, advance = { now.addAndGet(it) })
        val scope = newScope()
        val c = DictationCoordinator(scope, { src }, { FakeAsrEngine() }, FakeSink(), nowMs = { now.get() })
        val aborts = java.util.concurrent.CopyOnWriteArrayList<String>()
        c.onAborted = { aborts.add(it) }
        assertTrue(c.start())
        val deadline = System.currentTimeMillis() + 3000
        while (c.lastAbortReason != "audio_error:silence" && System.currentTimeMillis() < deadline) {
            Thread.sleep(5)
        }
        assertEquals("Silence muss die Session abbrechen", "audio_error:silence", c.lastAbortReason)
        assertTrue("Blöcke liefen (also Silence, kein Stall)", c.capBlocks() >= 5)
        assertEquals("onAborted feuert genau einmal mit dem Grund", listOf("audio_error:silence"), aborts)
        assertEquals("Session ist IDLE", DictationState.IDLE, c.state)
        assertFalse("Mic-View-Ableitung nach Stille-Abbruch ist neutral", micUiRecording(c.state))
        shutdown(scope)
    }

    // A1 (PLAN_V0133 §1, Rote-Test 1): Kaltstart — engine.load() schiebt die
    // injizierte Uhr um 4 500 ms (Modell-Load), src.start() ist erfolgreich,
    // der erste PCM-Puffer kommt erst 100 ms später. Die Watchdog-Baselines
    // müssen am ECHTEN Capture-Start hängen: isActive=true, lastError=null,
    // KEIN audio_error:stall und KEIN audio_error:silence (vor dem Fix:
    // ERR_STALL im ersten leeren Poll, weil die Uhren vor engine.load()
    // genullt wurden).
    @Test fun `A1 Kaltstart mit 4500ms load und 100ms Startlatenz bleibt aktiv ohne stall und silence`() {
        val base = 1_000_000L // NICHT 0 — 0 ist lastBlockAtMs-Sentinel "nie gestartet"
        val now = AtomicLong(base)
        val src = ColdStartSource(firstStepMs = 100, stepMs = 500, deliverReads = 0, advance = { now.addAndGet(it) })
        val engine = SlowLoadEngine(loadMs = 4500, advance = { now.addAndGet(it) })
        val scope = newScope()
        val c = DictationCoordinator(
            scope, { src }, { engine }, FakeSink(),
            modelDir = File("vk-test-model"), // Fake-Engine ignoriert das Verzeichnis
            nowMs = { now.get() },
        )
        assertTrue(c.start())
        // virtuell > 4 s über den Capture-Start hinaus laufen lassen — jenseits
        // der 3-s-Stall- UND 3-s-Stille-Fenster (real durch Deadline begrenzt)
        val deadline = System.currentTimeMillis() + 3000
        while (now.get() < base + 4500 + 4000 && System.currentTimeMillis() < deadline) Thread.sleep(10)
        assertTrue("virtuelle Zeit muss den Capture-Start um >3 s uebertreffen", now.get() >= base + 4500 + 4000)
        assertTrue("Session muss nach Kaltstart aktiv sein", c.isActive)
        assertNull("kein Fehlercode nach Startlatenz", c.lastError)
        assertEquals("kein Abort-Grund — weder stall noch silence", "-", c.lastAbortReason)
        assertTrue("erster PCM-Puffer muss angekommen sein", c.capBlocks() >= 1)
        c.stop()
        awaitIdle(c)
        shutdown(scope)
    }

    // A1 (PLAN_V0133 §1, Rote-Test 2): Kaltstart mit 4 500 ms load — die Quelle
    // liefert nach erfolgreichem Start zunächst Blöcke und dann 3 000+ ms
    // keine mehr (echter Quelltod). Erwartung: GENAU EIN audio_error:stall,
    // onAborted genau 1x, Controller IDLE — und der Abort kommt erst nach dem
    // Quelltod, nicht schon im ersten leeren Poll nach dem Modell-Load
    // (vor dem Fix: ERR_STALL vor dem ersten Block, capBlocks=0).
    @Test fun `A1 Quelltod 3000ms nach Capture-Start loest genau einen stall-Abbruch aus`() {
        val base = 1_000_000L
        val now = AtomicLong(base)
        val src = ColdStartSource(firstStepMs = 100, stepMs = 500, deliverReads = 3, advance = { now.addAndGet(it) })
        val engine = SlowLoadEngine(loadMs = 4500, advance = { now.addAndGet(it) })
        val scope = newScope()
        val c = DictationCoordinator(
            scope, { src }, { engine }, FakeSink(),
            modelDir = File("vk-test-model"),
            nowMs = { now.get() },
        )
        val aborts = CopyOnWriteArrayList<String>()
        c.onAborted = { aborts.add(it) }
        assertTrue(c.start())
        val deadline = System.currentTimeMillis() + 3000
        while (c.lastAbortReason != "audio_error:stall" && System.currentTimeMillis() < deadline) {
            Thread.sleep(5)
        }
        assertEquals("Quelltod muss die Session ueber den Stall-Pfad beenden", "audio_error:stall", c.lastAbortReason)
        assertEquals("kontrollierter Fehlercode, keine Audio-Inhalte", "stall:no_audio", c.lastError)
        assertEquals("onAborted feuert genau einmal mit dem Grund", listOf("audio_error:stall"), aborts)
        assertEquals("Controller ist IDLE", DictationState.IDLE, c.state)
        assertFalse("Session ist beendet", c.isActive)
        assertTrue("Quelle muss vor dem Tod Bloecke geliefert haben (kein Frueh-Abbruch)", c.capBlocks() >= 1)
        shutdown(scope)
    }
}
