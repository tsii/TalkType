package de.voicekeyboard.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.Executor

/**
 * Pure JVM tests for [DownloadController] with fakes (no network):
 * (a) happy path → READY, (b) cancel → CANCELLED, (c) failure → FAILED,
 * staging stays, no activation, (d) resume after FAILED, (e) double start,
 * (f) activation fails → FAILED.
 */
class DownloadControllerTest {

    @get:Rule val tmp = TemporaryFolder()

    private val seenCancel = mutableSetOf<String>()
    private val seenFail = mutableSetOf<String>()
    private var cancelAt: String? = null
    private var failureAt: String? = null
    private val states = mutableListOf<DownloadController.State>()

    private fun sha(b: ByteArray): String =
        java.security.MessageDigest.getInstance("SHA-256").digest(b)
            .joinToString("") { "%02x".format(it) }

    /** Manifest: hash matches ByteArray(size){7} — the fake writes exactly that. */
    private fun manifest(vararg sizes: Pair<String, Long>): List<ModelFile> =
        sizes.map { (name, size) -> ModelFile(name, size, sha(ByteArray(size.toInt()) { 7 })) }

    /** Synchronous executor: start() runs on the same thread, deterministic. */
    private class DirectExecutor : Executor {
        val runs = java.util.concurrent.atomic.AtomicInteger(0)
        override fun execute(command: Runnable) {
            runs.incrementAndGet()
            command.run()
        }
    }

    /**
     * Fake DownloadOp: writes hash-correct bytes into the store's .part,
     * can cancel on one name (ModelError.Cancelled) or fail
     * (ModelError.Network). The progress listener is served for real.
     */
    private fun fakeOp(store: ModelStore): DownloadController.DownloadOp =
        object : DownloadController.DownloadOp {
            override fun download(file: ModelFile, listener: DownloadProgressListener?): Boolean {
                if (file.storeName == cancelAt) throw ModelError.Cancelled(file.storeName)
                if (file.storeName == failureAt && file.storeName !in seenFail) {
                    seenFail += file.storeName
                    throw ModelError.Network("fake failure for ${file.storeName}")
                }
                val bytes = ByteArray(file.sizeBytes.toInt()) { 7 }
                store.partFile(file.storeName).writeBytes(bytes)
                listener?.onProgress(DownloadProgress(file.storeName, bytes.size.toLong(), bytes.size.toLong(), 1L))
                return true
            }
        }

    private fun makeController(
        files: List<ModelFile>,
        root: File = tmp.newFolder(),
    ): Triple<ModelStore, DownloadController, DirectExecutor> {
        val store = ModelStore(root, files)
        val ex = DirectExecutor()
        val controller = DownloadController(store, { fakeOp(store) }, files, ex)
        controller.listener = { states += it }
        return Triple(store, controller, ex)
    }

    private fun stagedFiles(store: ModelStore): List<File> =
        File(store.modelDirSafe(), "versions").walkTopDown()
            .filter { it.isFile }
            .toList()

    private fun ModelStore.modelDirSafe(): File = partFile("a.bin").parentFile!!.parentFile!!

    // (a) happy path: all files, activation, READY, order smallest first
    @Test fun happyPathActivatesAndReachesReady() {
        val files = manifest("encoder.onnx" to 40L, "decoder.onnx" to 30L, "nemo128.onnx" to 20L, "vocab.txt" to 10L)
        val (store, controller, ex) = makeController(files)
        controller.start()
        assertEquals(DownloadController.State.Ready, controller.state)
        assertTrue(store.isReady())
        assertEquals(1, ex.runs.get())
        // order: smallest first
        val downloading = states.filterIsInstance<DownloadController.State.Downloading>()
        assertEquals(listOf("vocab.txt", "nemo128.onnx", "decoder.onnx", "encoder.onnx"),
            downloading.map { it.currentFileName }.distinct())
        assertEquals(4, downloading.last().totalFiles)
    }

    // (b) cancel at file 2 → CANCELLED, file 1's .part was moved
    @Test fun cancelKeepsPriorPartAndGoesCancelled() {
        val files = manifest("a.bin" to 10L, "b.bin" to 20L, "c.bin" to 30L)
        cancelAt = "b.bin"
        val (store, controller, _) = makeController(files)
        controller.start()
        assertEquals(DownloadController.State.Cancelled, controller.state)
        // a.bin fully moved (no .part left), no activation:
        assertFalse(store.partFile("a.bin").exists())
        assertNull(store.activeVersion())
        cancelAt = null
    }

    // (c) failure at file 3 → FAILED, already moved files stay in staging,
    //     activation is skipped
    @Test fun failureKeepsStagingAndDoesNotActivate() {
        val files = manifest("a.bin" to 10L, "b.bin" to 20L, "c.bin" to 30L)
        failureAt = "c.bin"
        val (store, controller, _) = makeController(files)
        controller.start()
        val failed = controller.state
        assertTrue(failed is DownloadController.State.Failed)
        assertTrue((failed as DownloadController.State.Failed).error is ModelError.Network)
        val staged = stagedFiles(store)
        assertTrue(staged.any { it.name == "a.bin" })
        assertTrue(staged.any { it.name == "b.bin" })
        assertNull(store.activeVersion()) // no activation
        failureAt = null
    }

    // (d) a repeated start() after FAILED picks up the order again (resume)
    @Test fun restartAfterFailureResumes() {
        val files = manifest("a.bin" to 10L, "b.bin" to 20L)
        failureAt = "b.bin"
        val (store, controller, _) = makeController(files)
        controller.start()
        assertTrue(controller.state is DownloadController.State.Failed)
        failureAt = null // "network is back"
        controller.start()
        assertEquals(DownloadController.State.Ready, controller.state)
        assertTrue(store.isReady())
    }

    // (e) double start() = one run
    @Test fun doubleStartIsOneRun() {
        val files = manifest("a.bin" to 10L)
        val (store, controller, ex) = makeController(files)
        controller.start()
        controller.start()
        assertEquals(1, ex.runs.get())
        assertEquals(DownloadController.State.Ready, controller.state)
    }

    // (f) activation fails → FAILED instead of READY
    @Test fun activationFailureYieldsFailed() {
        // manifest hash wrong → fake download "ok", activate() rejects
        val files = listOf(ModelFile("a.bin", 10L, "0".repeat(64)))
        val (store, controller, _) = makeController(files)
        controller.start()
        assertTrue(controller.state is DownloadController.State.Failed)
        assertFalse(store.isReady())
        assertNull(store.activeVersion())
    }
}
