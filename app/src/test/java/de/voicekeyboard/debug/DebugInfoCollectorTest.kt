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
 * F17c: chain test Controller → Coordinator → Collector → Snapshot.
 * DebugInfoFormatterTest builds its own DebugSnapshot — if someone deletes a
 * collector line (e.g. `decodeMsMax = coordinator?.decodeMsMax()`), the
 * formatter test stays green and the 🐞 export shows `-` permanently. This
 * test catches exactly that: a real coordinator with a real decode run, a
 * real collector call, claim: the three C2 fields are set (not null)
 * and bufferSeconds > 0.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35]) // project pattern: SDK 36 needs Java 21, the test JVM is 17
class DebugInfoCollectorTest {

    /** Fake source: delivers 3 blocks of 16000 samples each (3.0 s) and ends. */
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

    /** Fake engine: counts, returns short text — the window is decoded as if real. */
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
    fun `collector fills decode_ms_max p95 and buffer_s from the coordinator`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val engine = CountingEngine()
        val scope = CoroutineScope(Dispatchers.Default)
        val coordinator = DictationCoordinator(
            scope,
            { QueueSource() },
            { engine },
            NopSink(),
            // F28b1: FROZEN virtual clock (not 0 — that is the "never started"
            // sentinel). QueueSource delivers 3 s of audio at once and then goes
            // silent; with a real-time clock the stall watchdog (3 s without a
            // block) would abort the session in the middle of the 3 s wait. This
            // test checks the collector chain, not a stall.
            nowMs = { 1_000L },
        )
        try {
            assertTrue(coordinator.start())
            // wait until 3.0 s have flowed (the final decode runs at the latest in stop()).
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
            assertTrue("at least one decode run", engine.calls.get() >= 1)

            // chain (1): the coordinator passes through to the controller.
            assertTrue(
                "bufferSeconds must show the decode buffer: ${coordinator.bufferSeconds()}",
                coordinator.bufferSeconds() > 0.0,
            )

            // chain (2): real collector call — if someone deleted one of the three
            // lines in the collector, the field would be null and this test RED.
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
            assertNotNull("decodeMsMax passed through", snapshot.decodeMsMax)
            assertNotNull("decodeMsP95 passed through", snapshot.decodeMsP95)
            assertNotNull("bufferSeconds passed through", snapshot.bufferSeconds)
            // F21: pace fields — same chain, otherwise the F17c error again
            assertNotNull("decodePaceMs passed through", snapshot.decodePaceMs)
            assertNotNull("decodeSkips passed through", snapshot.decodeSkips)
            assertTrue("bufferSeconds > 0 after a real decode", snapshot.bufferSeconds!! > 0.0)
            assertTrue("decodeMsMax >= 0", snapshot.decodeMsMax!! >= 0L)

            // chain (3): the formatted export shows numbers, no dashes.
            val out = DebugInfoFormatter.format(snapshot)
            assertTrue("decode_ms_max in the export", Regex("decode_ms_max: \\d+").containsMatchIn(out))
            assertTrue("decode_ms_p95 in the export", Regex("decode_ms_p95: \\d+").containsMatchIn(out))
            assertTrue("buffer_s in the export", Regex("buffer_s: [0-9]").containsMatchIn(out))
            assertTrue("decode_pace_ms in the export", Regex("decode_pace_ms: \\d+").containsMatchIn(out))
            assertTrue("decode_skips in the export", Regex("decode_skips: \\d+").containsMatchIn(out))
        } finally {
            coordinator.shutdown()
            scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
        }
    }

    // A4a (PLAN_V0133 §A4a): collector pass-through of the A2b write diagnostics —
    // window values from the A2b diagnostics object (lastDecodeWindow), session
    // counters via the coordinator pass-through. If someone deletes ONE collector
    // assignment, the field is null and this test RED. Numbers/codes only (rule 9).
    @Test
    fun `collector passes through the A2b write diagnostics`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val engine = CountingEngine()
        val scope = CoroutineScope(Dispatchers.Default)
        val coordinator = DictationCoordinator(
            scope,
            { QueueSource() },
            { engine },
            NopSink(),
            nowMs = { 1_000L }, // frozen clock — see test above (no stall abort)
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
            assertTrue("at least one decode run", engine.calls.get() >= 1)
            assertTrue("at least one write DELTA", coordinator.writeDeltaCount() >= 1)

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
            // window values (A2b diagnostics object) — all set after a real decode:
            assertNotNull("lastDecodeMs passed through", snapshot.lastDecodeMs)
            assertNotNull("hypLen passed through", snapshot.hypLen)
            assertNotNull("currentLen passed through", snapshot.currentLen)
            assertNotNull("committedLen passed through", snapshot.committedLen)
            assertNotNull("writeReason passed through", snapshot.writeReason)
            assertTrue(
                "writeReason is a closed code",
                snapshot.writeReason in setOf("same", "w1", "w3d", "delta"),
            )
            // session counters + last write position — never null with a coordinator:
            assertNotNull("writeSame passed through", snapshot.writeSame)
            assertNotNull("writeW1 passed through", snapshot.writeW1)
            assertNotNull("writeW3d passed through", snapshot.writeW3d)
            assertTrue("writeDelta >= 1", snapshot.writeDelta!! >= 1)
            assertNotNull("lastWriteAudioS passed through", snapshot.lastWriteAudioS)
            assertTrue("after DELTA >= 0", snapshot.lastWriteAudioS!! >= 0.0)

            // export shows numbers/codes instead of dashes (effective in release, main source):
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
