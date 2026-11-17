package de.voicekeyboard.model

import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * V0.1.54e (restart bug): the service's controller is a process SINGLETON —
 * a finished run (READY/FAILED) used to swallow every later ACTION_START of
 * ANOTHER set (`controller ?: createController` discarded the new wiring and
 * `start()` no-op'd on READY). Pinned here via the injectable controller
 * factory (NO network: inert/blocking fake controllers):
 *  (1) ACTION_START with another variant after a TERMINAL run → NEW
 *      controller + run;
 *  (2) single-run stays: REUSE for the same variant while Downloading,
 *      BUSY (no new run) for another variant while Downloading;
 *  (3) the pure [controllerReuseDecision] matrix.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ModelDownloadServiceRestartTest {

    @get:Rule val tmp = TemporaryFolder()

    @After fun tearDown() {
        ModelDownloadService.resetControllerForTest()
        ModelDownloadService.controllerFactoryOverride = null
    }

    private fun startIntent(variantId: String): Intent =
        Intent(ApplicationProvider.getApplicationContext(), ModelDownloadService::class.java)
            .setAction(ModelDownloadService.ACTION_START)
            .putExtra(ModelDownloadService.EXTRA_VARIANT, variantId)

    private fun service(): ModelDownloadService =
        Robolectric.buildService(ModelDownloadService::class.java).get()

    // ---------------------------------------------------------- fake factories

    /** Inert controller (empty manifest): start() ends FAILED without any op. */
    private fun inertController(variant: ModelVariant): DownloadController {
        val bytes = byteArrayOf(1, 2, 3)
        val manifest = listOf(ModelFile("model.bin", bytes.size.toLong(), Sha256.ofBytes(bytes)))
        val store = ModelStore(tmp.newFolder(), emptyList()) // guard: empty manifest
        return DownloadController(
            store = store,
            downloaderFactory = { throw AssertionError("inert controller: no op may run") },
            manifest = emptyList(),
            executor = DirectExecutor(),
            variant = variant,
        )
    }

    /** Controller whose single download op BLOCKS on a latch (state = Downloading). */
    private fun blockingController(variant: ModelVariant, latch: CountDownLatch): DownloadController {
        val bytes = byteArrayOf(1, 2, 3)
        val manifest = listOf(ModelFile("model.bin", bytes.size.toLong(), Sha256.ofBytes(bytes)))
        val store = ModelStore(tmp.newFolder(), manifest)
        return DownloadController(
            store = store,
            downloaderFactory = {
                object : DownloadController.DownloadOp {
                    override fun download(file: ModelFile, listener: DownloadProgressListener?): Boolean {
                        latch.await()
                        return true // part stays empty -> staging move fails -> FAILED
                    }

                    override fun cancel() {}
                }
            },
            manifest = manifest,
            executor = Executors.newSingleThreadExecutor(),
            variant = variant,
        )
    }

    private class DirectExecutor : Executor {
        override fun execute(command: Runnable) = command.run()
    }

    private fun awaitState(controller: DownloadController, expected: Class<out DownloadController.State>) {
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            if (expected.isInstance(controller.state)) return
            Thread.sleep(20)
        }
        throw AssertionError("state never reached ${expected.simpleName} (was ${controller.state})")
    }

    // ------------------------------------------------- (1) NEW after terminal

    @Test
    fun `ACTION_START with ANOTHER variant after a terminal run starts a NEW controller`() {
        val created = mutableListOf<ModelVariant>()
        ModelDownloadService.controllerFactoryOverride = { wiring ->
            created += wiring.variant
            inertController(wiring.variant)
        }
        val service = service()

        // 1st run: the inert 1.7B set — terminal (FAILED) WITHOUT any network.
        service.onStartCommand(startIntent("qwen3-asr-1.7b-int8"), 0, 1)
        val first = ModelDownloadService.controller
        assertEquals(ModelVariant.QWEN3_ASR_17B, first?.variant)
        assertTrue("first run is terminal (FAILED, no op ran)", first?.state is DownloadController.State.Failed)

        // 2nd start, ANOTHER variant: the terminal singleton must NOT swallow it.
        service.onStartCommand(startIntent("bart-spelling-en-int8"), 0, 2)
        val second = ModelDownloadService.controller
        assertNotEquals("a NEW controller owns the new run", first, second)
        assertEquals(ModelVariant.BART_SPELLING_EN, second?.variant)
        assertTrue("the new run really started (its own inert terminal state)", second?.state is DownloadController.State.Failed)
        assertEquals("factory created exactly the two requested controllers", listOf(ModelVariant.QWEN3_ASR_17B, ModelVariant.BART_SPELLING_EN), created)
    }

    // -------------------------------------------- (2) single-run stays (BUSY/REUSE)

    @Test
    fun `single run stays - REUSE same variant, BUSY for another while Downloading`() {
        val latch = CountDownLatch(1)
        val created = mutableListOf<ModelVariant>()
        ModelDownloadService.controllerFactoryOverride = { wiring ->
            created += wiring.variant
            blockingController(wiring.variant, latch)
        }
        val service = service()

        service.onStartCommand(startIntent("onnx-cpu"), 0, 1)
        val first = ModelDownloadService.controller
        awaitState(first!!, DownloadController.State.Downloading::class.java)

        // same variant again while Downloading: REUSE — no new controller.
        service.onStartCommand(startIntent("onnx-cpu"), 0, 2)
        assertTrue("same controller keeps the single run", ModelDownloadService.controller === first)

        // ANOTHER variant while Downloading: BUSY — nothing starts.
        service.onStartCommand(startIntent("bart-spelling-de-int8"), 0, 3)
        assertTrue("busy keeps the RUNNING controller", ModelDownloadService.controller === first)
        assertEquals("exactly ONE controller created", listOf(ModelVariant.ONNX_CPU), created)

        // release: the run ends terminal (FAILED via the failing staging move)
        latch.countDown()
        awaitState(first, DownloadController.State.Failed::class.java)
    }

    // --------------------------------------------- (3) pure decision matrix

    @Test
    fun `controllerReuseDecision matrix - NEW terminal or none, REUSE same, BUSY other`() {
        val dl = DownloadController.State.Downloading(0, 1, "model.bin", 0f)
        val verifying = DownloadController.State.Verifying
        val ready = DownloadController.State.Ready
        val failed = DownloadController.State.Failed(ModelError.Storage("x"))
        val cancelled = DownloadController.State.Cancelled
        val idle = DownloadController.State.Idle

        // no controller yet: always NEW
        assertEquals(ControllerReuse.NEW, controllerReuseDecision(null, null, ModelVariant.ONNX_CPU))
        // terminal states (any variant): NEW — the old run is over
        for (state in listOf(idle, ready, failed, cancelled)) {
            assertEquals("terminal ${state.javaClass.simpleName} -> NEW", ControllerReuse.NEW,
                controllerReuseDecision(ModelVariant.ONNX_CPU, state, ModelVariant.BART_SPELLING_EN))
        }
        // same variant, non-terminal: REUSE
        assertEquals(ControllerReuse.REUSE, controllerReuseDecision(ModelVariant.ONNX_CPU, dl, ModelVariant.ONNX_CPU))
        assertEquals(ControllerReuse.REUSE, controllerReuseDecision(ModelVariant.ONNX_CPU, verifying, ModelVariant.ONNX_CPU))
        // other variant, non-terminal: BUSY — never two parallel downloads
        assertEquals(ControllerReuse.BUSY, controllerReuseDecision(ModelVariant.ONNX_CPU, dl, ModelVariant.BART_SPELLING_EN))
        assertEquals(ControllerReuse.BUSY, controllerReuseDecision(ModelVariant.ONNX_CPU, verifying, ModelVariant.QWEN3_ASR))
    }
}
