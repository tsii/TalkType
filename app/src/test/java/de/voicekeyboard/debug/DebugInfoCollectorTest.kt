package de.voicekeyboard.debug

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import de.voicekeyboard.asr.AsrEngine
import de.voicekeyboard.asr.AsrThreadConfig
import de.voicekeyboard.asr.Hypothesis
import de.voicekeyboard.audio.AudioSource
import de.voicekeyboard.audio.PcmBlock
import de.voicekeyboard.audio.PcmWindow
import de.voicekeyboard.dictation.DictationState
import de.voicekeyboard.editor.EditorSink
import de.voicekeyboard.ime.AndroidPermissionsRequester
import de.voicekeyboard.ime.DictationCoordinator
import de.voicekeyboard.model.ModelStore
import de.voicekeyboard.model.ModelVariant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

/**
 * F17c: Kettentest Controller → Coordinator → Collector → Snapshot.
 * DebugInfoFormatterTest baut sein DebugSnapshot selbst — löscht man eine
 * Collector-Zeile (z. B. `decodeMsMax = coordinator?.decodeMsMax()`), bleibt
 * der Formatter-Test grün und der 🐞-Export zeigt dauerhaft `-`. Genau das
 * fängt dieser Test: echter Coordinator mit echtem Decode-Lauf, echter
 * Collector-Aufruf, Behauptung: die drei C2-Felder sind gesetzt (nicht null)
 * und bufferSeconds > 0.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35]) // Projekt-Muster: SDK 36 braucht Java 21, Test-JVM ist 17
class DebugInfoCollectorTest {

    /** Fake-Quelle: liefert 3 Blöcke à 16000 Samples (3,0 s) und endet. */
    private class QueueSource : AudioSource {
        private val queue = ConcurrentLinkedQueue(
            listOf(ShortArray(16_000), ShortArray(16_000), ShortArray(16_000)),
        )
        private var stopping = false
        override fun start(sessionId: Long) = true
        override fun read(dst: ShortArray, timeoutMs: Int): Int {
            if (stopping) return -1
            val head = queue.poll() ?: return 0
            val n = minOf(dst.size, head.size)
            System.arraycopy(head, 0, dst, 0, n)
            return n
        }

        override fun stopAndDrain(): List<PcmBlock> {
            stopping = true
            val drained = mutableListOf<ShortArray>()
            while (true) {
                val head = queue.poll() ?: break
                drained.add(head)
            }
            return drained.map { PcmBlock(-1, -1, it) }
        }

        override fun release() {}
    }

    /** Fake-Engine: zählt, gibt kurzen Text — das Fenster wird wie echt dekodiert. */
    private class CountingEngine : AsrEngine {
        val calls = AtomicInteger(0)
        override fun load(modelDir: File) {}
        override suspend fun transcribe(
            window: PcmWindow, sessionId: Long, runId: Long,
        ): Hypothesis {
            calls.incrementAndGet()
            return Hypothesis(
                IntArray(1), "x".repeat(window.audioSeconds.toInt().coerceAtLeast(1)),
                IntArray(1), sessionId, runId, window.samples.size, incomplete = false,
            )
        }

        override fun close() {}
    }

    private class NopSink : EditorSink {
        override fun replaceBeforeCursor(replaceLength: Int, newText: String) {}
        override fun textBeforeCursor(maxChars: Int): CharSequence? = null
        override fun ownLength(): Int = 0
    }

    @Test
    fun `collector fuellt decode_ms_max p95 und buffer_s aus dem coordinator`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val engine = CountingEngine()
        val scope = CoroutineScope(Dispatchers.Default)
        val coordinator = DictationCoordinator(
            scope,
            { QueueSource() },
            { engine },
            NopSink(),
            // F28b1: GEFRORENE virtuelle Uhr (nicht 0 — das ist das "nie gestartet"
            // -Sentinel). QueueSource liefert 3 s Audio auf einmal und geht dann
            // in Stille; mit der Echtzeit-Uhr würde der Stall-Watchdog (3 s ohne
            // Block) mitten in der 3-s-Wartezeit die Session abbrechen. Dieser
            // Test prüft die Collector-Kette, keinen Stall.
            nowMs = { 1_000L },
        )
        try {
            assertTrue(coordinator.start())
            // Warten, bis 3,0 s geflossen sind (Final-Decode läuft spätestens in stop()).
            val deadline = System.currentTimeMillis() + 3000
            while (coordinator.audioSeconds < 3.0 && System.currentTimeMillis() < deadline) {
                Thread.sleep(10)
            }
            coordinator.stop()
            val idleDeadline = System.currentTimeMillis() + 3000
            while (coordinator.state != DictationState.IDLE &&
                System.currentTimeMillis() < idleDeadline
            ) {
                Thread.sleep(10)
            }
            assertEquals(DictationState.IDLE, coordinator.state)
            assertTrue("mind. ein Decode-Lauf", engine.calls.get() >= 1)

            // Kette (1): Coordinator reicht auf den Controller durch.
            assertTrue(
                "bufferSeconds muss Decode-Puffer zeigen: ${coordinator.bufferSeconds()}",
                coordinator.bufferSeconds() > 0.0,
            )

            // Kette (2): echter Collector-Aufruf — Löschte jemand eine der drei
            // Zeilen im Collector, wäre das Feld null und dieser Test ROT.
            val snapshot = DebugInfoCollector(context).collect(
                modelStore = ModelStore(
                    File(System.getProperty("java.io.tmpdir"), "vk-f17c-${System.nanoTime()}")
                        .apply { mkdirs() },
                ),
                modelVariant = ModelVariant.ONNX_CPU,
                coordinator = coordinator,
                threadConfig = AsrThreadConfig.DEFAULT,
                permissions = AndroidPermissionsRequester({ false }, { false }),
            )
            assertNotNull("decodeMsMax durchgereicht", snapshot.decodeMsMax)
            assertNotNull("decodeMsP95 durchgereicht", snapshot.decodeMsP95)
            assertNotNull("bufferSeconds durchgereicht", snapshot.bufferSeconds)
            // F21: Takt-Felder — gleiche Kette, sonst wieder der F17c-Fehler
            assertNotNull("decodePaceMs durchgereicht", snapshot.decodePaceMs)
            assertNotNull("decodeSkips durchgereicht", snapshot.decodeSkips)
            assertTrue("bufferSeconds > 0 nach echtem Decode", snapshot.bufferSeconds!! > 0.0)
            assertTrue("decodeMsMax >= 0", snapshot.decodeMsMax!! >= 0L)

            // Kette (3): im formatierten Export stehen Zahlen, keine Striche.
            val out = DebugInfoFormatter.format(snapshot)
            assertTrue("decode_ms_max im Export", Regex("decode_ms_max: \\d+").containsMatchIn(out))
            assertTrue("decode_ms_p95 im Export", Regex("decode_ms_p95: \\d+").containsMatchIn(out))
            assertTrue("buffer_s im Export", Regex("buffer_s: [0-9]").containsMatchIn(out))
            assertTrue("decode_pace_ms im Export", Regex("decode_pace_ms: \\d+").containsMatchIn(out))
            assertTrue("decode_skips im Export", Regex("decode_skips: \\d+").containsMatchIn(out))
        } finally {
            coordinator.shutdown()
            scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
        }
    }

    // A4a (PLAN_V0133 §A4a): Collector-Durchreichung der A2b-Schreib-Diagnose —
    // Fensterwerte aus dem A2b-Diagnoseobjekt (lastDecodeWindow), Session-Zähler
    // über die Coordinator-Durchreiche. Löscht jemand EINE Collector-Zuordnung,
    // ist das Feld null und dieser Test ROT. Nur Zahlen/Codes (Regel 9).
    @Test
    fun `collector reicht die A2b-Schreib-Diagnose durch`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val engine = CountingEngine()
        val scope = CoroutineScope(Dispatchers.Default)
        val coordinator = DictationCoordinator(
            scope,
            { QueueSource() },
            { engine },
            NopSink(),
            nowMs = { 1_000L }, // gefrorene Uhr — s. Test oben (kein Stall-Abbruch)
        )
        try {
            assertTrue(coordinator.start())
            val deadline = System.currentTimeMillis() + 3000
            while (coordinator.audioSeconds < 3.0 && System.currentTimeMillis() < deadline) {
                Thread.sleep(10)
            }
            coordinator.stop()
            val idleDeadline = System.currentTimeMillis() + 3000
            while (coordinator.state != DictationState.IDLE &&
                System.currentTimeMillis() < idleDeadline
            ) {
                Thread.sleep(10)
            }
            assertTrue("mind. ein Decode-Lauf", engine.calls.get() >= 1)
            assertTrue("mind. ein Schreib-DELTA", coordinator.writeDeltaCount() >= 1)

            val snapshot = DebugInfoCollector(context).collect(
                modelStore = ModelStore(
                    File(System.getProperty("java.io.tmpdir"), "vk-a4a-${System.nanoTime()}")
                        .apply { mkdirs() },
                ),
                modelVariant = ModelVariant.ONNX_CPU,
                coordinator = coordinator,
                threadConfig = AsrThreadConfig.DEFAULT,
                permissions = AndroidPermissionsRequester({ false }, { false }),
            )
            // Fensterwerte (A2b-Diagnoseobjekt) — nach echtem Decode alle gesetzt:
            assertNotNull("lastDecodeMs durchgereicht", snapshot.lastDecodeMs)
            assertNotNull("hypLen durchgereicht", snapshot.hypLen)
            assertNotNull("currentLen durchgereicht", snapshot.currentLen)
            assertNotNull("committedLen durchgereicht", snapshot.committedLen)
            assertNotNull("writeReason durchgereicht", snapshot.writeReason)
            assertTrue(
                "writeReason ist geschlossener Code",
                snapshot.writeReason in setOf("same", "w1", "w3d", "delta"),
            )
            // Session-Zähler + letzte Schreibposition — mit Coordinator nie null:
            assertNotNull("writeSame durchgereicht", snapshot.writeSame)
            assertNotNull("writeW1 durchgereicht", snapshot.writeW1)
            assertNotNull("writeW3d durchgereicht", snapshot.writeW3d)
            assertTrue("writeDelta >= 1", snapshot.writeDelta!! >= 1)
            assertNotNull("lastWriteAudioS durchgereicht", snapshot.lastWriteAudioS)
            assertTrue("nach DELTA >= 0", snapshot.lastWriteAudioS!! >= 0.0)

            // Export zeigt Zahlen/Codes statt Striche (Release-wirksam, main-source):
            val out = DebugInfoFormatter.format(snapshot)
            assertTrue(Regex("write_delta: \\d+").containsMatchIn(out))
            assertTrue(Regex("hyp_len: \\d+").containsMatchIn(out))
            assertTrue(Regex("last_write_audio_s: -?\\d+\\.\\d").containsMatchIn(out))
            assertTrue(Regex("last_decode_ms: \\d+").containsMatchIn(out))
        } finally {
            coordinator.shutdown()
            scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
        }
    }
}
