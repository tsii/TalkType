package de.voicekeyboard.model

import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Orchestrates the overall download (M5b-1): [ModelStore] + downloader,
 * abort-safe and UI-free (JDK/Kotlin only).
 *
 * Sequence per file (fixed order, smallest first — an early abort
 * discards as little work as possible: vocab → nemo128 → decoder_joint → encoder):
 * `markDownloading → download → movePartToStaging`;
 * after all files: `markVerifying → activate`.
 *
 * - [ModelError.Cancelled] → CANCELLED, `.part` stays (resume possible).
 * - Other [ModelError] → FAILED, remaining `.part` stays for resuming
 *   (the hash-mismatch delete is done by the downloader itself).
 * - No logs of contents; tag "VK-DL" only with file names.
 */
class DownloadController(
    private val store: ModelStore,
    private val downloaderFactory: () -> DownloadOp,
    private val manifest: List<ModelFile> = ModelManifest.DEFAULT,
    private val executor: Executor,
) {

    /** Narrow download port: production = ModelDownloader::download; tests = fakes. */
    fun interface DownloadOp {
        /** Throws [ModelError] (incl. Cancelled); true = complete + hash-correct. */
        fun download(file: ModelFile, listener: DownloadProgressListener?): Boolean

        /** Abort request (best effort, throws ModelError.Cancelled in download()). */
        fun cancel() {}
    }

    /** UI state model (no polling: changes via [listener]). */
    sealed class State {
        data object Idle : State()
        data class Downloading(
            val currentIndex: Int,      // 0-based, in fixed order
            val totalFiles: Int,
            val currentFileName: String,
            val fraction: Float,        // progress over all bytes [0,1]
            /** V12 (D1-03): 1-based retry attempt; 0/1 = normal first run. */
            val attempt: Int = 0,
            /** V12: total attempts for this file (0 = not emitted). */
            val maxAttempts: Int = 0,
        ) : State()

        data object Verifying : State()
        data object Ready : State()
        data class Failed(val error: ModelError) : State()
        data object Cancelled : State()
    }

    /** Fixed order: smallest file first. */
    private val order: List<ModelFile> = manifest.sortedBy { it.sizeBytes }
    private val sizeByName: Map<String, Long> = manifest.associate { it.storeName to it.sizeBytes }
    private val totalBytes: Long = order.sumOf { it.sizeBytes }

    @Volatile var state: State = State.Idle
        private set

    /** Called on every state change (caller-provided, thread-safe). */
    @Volatile var listener: ((State) -> Unit)? = null

    private val running = AtomicBoolean(false)

    @Volatile private var currentOp: DownloadOp? = null

    /** Starts; no-op during DOWNLOADING/VERIFYING/READY, resumes after FAILED/CANCELLED. */
    fun start() {
        when (state) {
            is State.Downloading, is State.Verifying, is State.Ready -> return
            else -> {}
        }
        if (!running.compareAndSet(false, true)) return // double start = one run
        executor.execute(::run)
    }

    /** Abort: the current download() throws ModelError.Cancelled (best effort). */
    fun cancel() {
        currentOp?.cancel()
    }

    private fun setState(s: State) {
        state = s
        listener?.invoke(s)
    }

    private fun run() {
        try {
            val staging = store.newStagingDir()
            var completedBytes = 0L
            for ((index, file) in order.withIndex()) {
                store.markDownloading()
                setState(State.Downloading(index, order.size, file.storeName, fraction(completedBytes)))
                val op = downloaderFactory()
                currentOp = op
                val ok = try {
                    op.download(file, DownloadProgressListener { p ->
                        setState(
                            State.Downloading(
                                index, order.size, file.storeName,
                                fraction(completedBytes + p.bytesDone),
                                attempt = p.attempt,
                                maxAttempts = p.maxAttempts,
                            )
                        )
                    })
                } catch (e: ModelError.Cancelled) {
                    setState(State.Cancelled)
                    return
                } catch (e: ModelError) {
                    setState(State.Failed(e))
                    return
                } finally {
                    currentOp = null
                }
                if (!ok) {
                    setState(State.Failed(ModelError.Storage("download ended without success: ${file.storeName}")))
                    return
                }
                if (!store.movePartToStaging(file.storeName, staging)) {
                    setState(State.Failed(ModelError.Storage("Staging move failed: ${file.storeName}")))
                    return
                }
                completedBytes += sizeByName.getValue(file.storeName)
            }
            store.markVerifying()
            setState(State.Verifying)
            if (!store.activate(staging)) {
                setState(State.Failed(ModelError.Storage("activation failed (staging incomplete/hash)")))
                return
            }
            setState(State.Ready)
        } catch (e: ModelError) {
            setState(State.Failed(e))
        } catch (e: Exception) {
            setState(State.Failed(ModelError.Storage(e.message ?: e.javaClass.simpleName, e)))
        } finally {
            running.set(false)
        }
    }

    private fun fraction(bytes: Long): Float =
        if (totalBytes <= 0) 0f else (bytes.toDouble() / totalBytes).toFloat().coerceIn(0f, 1f)

    companion object {
        /** Production: bind the real ModelDownloader to the port. */
        fun opOf(downloader: ModelDownloader): DownloadOp = object : DownloadOp {
            override fun download(file: ModelFile, listener: DownloadProgressListener?): Boolean =
                downloader.download(file, listener)

            override fun cancel() = downloader.cancel()
        }
    }
}
