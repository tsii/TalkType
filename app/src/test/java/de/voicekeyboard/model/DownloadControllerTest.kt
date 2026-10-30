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
 * Reine JVM-Tests für [DownloadController] mit Fakes (kein Netzwerk):
 * (a) Happy path → READY, (b) Abbruch → CANCELLED, (c) Fehler → FAILED,
 * Staging bleibt, keine Aktivierung, (d) Resume nach FAILED, (e) Doppel-start,
 * (f) Aktivierung schlägt fehl → FAILED.
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

    /** Manifest: Hash passt zu ByteArray(size){7} — der Fake schreibt genau das. */
    private fun manifest(vararg sizes: Pair<String, Long>): List<ModelFile> =
        sizes.map { (name, size) -> ModelFile(name, size, sha(ByteArray(size.toInt()) { 7 })) }

    /** Synchroner Executor: start() läuft in gleicher Thread, deterministisch. */
    private class DirectExecutor : Executor {
        val runs = java.util.concurrent.atomic.AtomicInteger(0)
        override fun execute(command: Runnable) {
            runs.incrementAndGet()
            command.run()
        }
    }

    /**
     * Fake-DownloadOp: schreibt hash-korrekte Bytes in die .part des Stores,
     * kann bei einem Namen canceln (ModelError.Cancelled) oder fehlschlagen
     * (ModelError.Network). Fortschritts-Listener wird real bedient.
     */
    private fun fakeOp(store: ModelStore): DownloadController.DownloadOp =
        object : DownloadController.DownloadOp {
            override fun download(file: ModelFile, listener: DownloadProgressListener?): Boolean {
                if (file.storeName == cancelAt) throw ModelError.Cancelled(file.storeName)
                if (file.storeName == failureAt && file.storeName !in seenFail) {
                    seenFail += file.storeName
                    throw ModelError.Network("fake failure für ${file.storeName}")
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

    // (a) Happy path: alle Dateien, Aktivierung, READY, Reihenfolge kleinste zuerst
    @Test fun happyPathActivatesAndReachesReady() {
        val files = manifest("encoder.onnx" to 40L, "decoder.onnx" to 30L, "nemo128.onnx" to 20L, "vocab.txt" to 10L)
        val (store, controller, ex) = makeController(files)
        controller.start()
        assertEquals(DownloadController.State.Ready, controller.state)
        assertTrue(store.isReady())
        assertEquals(1, ex.runs.get())
        // Reihenfolge: kleinste zuerst
        val downloading = states.filterIsInstance<DownloadController.State.Downloading>()
        assertEquals(listOf("vocab.txt", "nemo128.onnx", "decoder.onnx", "encoder.onnx"),
            downloading.map { it.currentFileName }.distinct())
        assertEquals(4, downloading.last().totalFiles)
    }

    // (b) Abbruch bei Datei 2 → CANCELLED, .part von Datei 1 wurde verschoben
    @Test fun cancelKeepsPriorPartAndGoesCancelled() {
        val files = manifest("a.bin" to 10L, "b.bin" to 20L, "c.bin" to 30L)
        cancelAt = "b.bin"
        val (store, controller, _) = makeController(files)
        controller.start()
        assertEquals(DownloadController.State.Cancelled, controller.state)
        // a.bin fertig verschoben (kein .part mehr), keine Aktivierung:
        assertFalse(store.partFile("a.bin").exists())
        assertNull(store.activeVersion())
        cancelAt = null
    }

    // (c) Fehler bei Datei 3 → FAILED, bereits verschobene Dateien bleiben im Staging,
    //     Aktivierung unterbleibt
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
        assertNull(store.activeVersion()) // keine Aktivierung
        failureAt = null
    }

    // (d) erneutes start() nach FAILED nimmt die Reihenfolge wieder auf (Resume)
    @Test fun restartAfterFailureResumes() {
        val files = manifest("a.bin" to 10L, "b.bin" to 20L)
        failureAt = "b.bin"
        val (store, controller, _) = makeController(files)
        controller.start()
        assertTrue(controller.state is DownloadController.State.Failed)
        failureAt = null // „Netz wieder da"
        controller.start()
        assertEquals(DownloadController.State.Ready, controller.state)
        assertTrue(store.isReady())
    }

    // (e) Doppel-start() = ein Lauf
    @Test fun doubleStartIsOneRun() {
        val files = manifest("a.bin" to 10L)
        val (store, controller, ex) = makeController(files)
        controller.start()
        controller.start()
        assertEquals(1, ex.runs.get())
        assertEquals(DownloadController.State.Ready, controller.state)
    }

    // (f) Aktivierung schlägt fehl → FAILED statt READY
    @Test fun activationFailureYieldsFailed() {
        // Manifest-Hash falsch → Fake-Download „ok", activate() lehnt ab
        val files = listOf(ModelFile("a.bin", 10L, "0".repeat(64)))
        val (store, controller, _) = makeController(files)
        controller.start()
        assertTrue(controller.state is DownloadController.State.Failed)
        assertFalse(store.isReady())
        assertNull(store.activeVersion())
    }
}
