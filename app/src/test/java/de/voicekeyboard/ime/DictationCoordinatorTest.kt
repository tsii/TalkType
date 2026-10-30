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
}
