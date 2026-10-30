package de.voicekeyboard.model

import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Orchestriert den Gesamtdownload (M5b-1): [ModelStore] + Downloader,
 * abbruchsicher und UI-frei (nur JDK/Kotlin).
 *
 * Ablauf pro Datei (feste Reihenfolge, kleinste zuerst — ein früher Abbruch
 * verwirft möglichst wenig Arbeit: vocab → nemo128 → decoder_joint → encoder):
 * `markDownloading → download → movePartToStaging`;
 * nach allen Dateien: `markVerifying → activate`.
 *
 * - [ModelError.Cancelled] → CANCELLED, `.part` bleibt (Resume möglich).
 * - Anderer [ModelError] → FAILED, Rest-`.part` bleibt für Resume
 *   (Hash-Mismatch-Delete macht der Downloader selbst).
 * - Keine Logs von Inhalten; Tag "VK-DL" nur mit Dateinamen.
 */
class DownloadController(
    private val store: ModelStore,
    private val downloaderFactory: () -> DownloadOp,
    private val manifest: List<ModelFile> = ModelManifest.DEFAULT,
    private val executor: Executor,
) {

    /** Schmaler Download-Port: Produktion = ModelDownloader::download; Tests = Fakes. */
    fun interface DownloadOp {
        /** Wirft [ModelError] (inkl. Cancelled); true = vollständig + hash-korrekt. */
        fun download(file: ModelFile, listener: DownloadProgressListener?): Boolean

        /** Abbruchwunsch (Best-Effort, wirft in download() ModelError.Cancelled). */
        fun cancel() {}
    }

    /** UI-Zustandsmodell (kein Pollen: Änderungen via [listener]). */
    sealed class State {
        data object Idle : State()
        data class Downloading(
            val currentIndex: Int,      // 0-basiert, in fester Reihenfolge
            val totalFiles: Int,
            val currentFileName: String,
            val fraction: Float,        // Fortschritt über alle Bytes [0,1]
            /** V12 (D1-03): 1-basierter Retry-Versuch; 0/1 = normaler erster Lauf. */
            val attempt: Int = 0,
            /** V12: Gesamtversuche dieser Datei (0 = nicht emittiert). */
            val maxAttempts: Int = 0,
        ) : State()

        data object Verifying : State()
        data object Ready : State()
        data class Failed(val error: ModelError) : State()
        data object Cancelled : State()
    }

    /** Feste Reihenfolge: kleinste Datei zuerst. */
    private val order: List<ModelFile> = manifest.sortedBy { it.sizeBytes }
    private val sizeByName: Map<String, Long> = manifest.associate { it.storeName to it.sizeBytes }
    private val totalBytes: Long = order.sumOf { it.sizeBytes }

    @Volatile var state: State = State.Idle
        private set

    /** Wird bei jeder Zustandsänderung aufgerufen (Aufrufer thread-fest). */
    @Volatile var listener: ((State) -> Unit)? = null

    private val running = AtomicBoolean(false)

    @Volatile private var currentOp: DownloadOp? = null

    /** Startet; No-Op während DOWNLOADING/VERIFYING/READY, Resume nach FAILED/CANCELLED. */
    fun start() {
        when (state) {
            is State.Downloading, is State.Verifying, is State.Ready -> return
            else -> {}
        }
        if (!running.compareAndSet(false, true)) return // Doppel-start = ein Lauf
        executor.execute(::run)
    }

    /** Abbruch: der aktuelle download() wirft ModelError.Cancelled (Best-Effort). */
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
                    setState(State.Failed(ModelError.Storage("Download endete ohne Erfolg: ${file.storeName}")))
                    return
                }
                if (!store.movePartToStaging(file.storeName, staging)) {
                    setState(State.Failed(ModelError.Storage("Staging-Move fehlgeschlagen: ${file.storeName}")))
                    return
                }
                completedBytes += sizeByName.getValue(file.storeName)
            }
            store.markVerifying()
            setState(State.Verifying)
            if (!store.activate(staging)) {
                setState(State.Failed(ModelError.Storage("Aktivierung fehlgeschlagen (Staging unvollständig/Hash)")))
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
        /** Produktion: echten ModelDownloader an den Port binden. */
        fun opOf(downloader: ModelDownloader): DownloadOp = object : DownloadOp {
            override fun download(file: ModelFile, listener: DownloadProgressListener?): Boolean =
                downloader.download(file, listener)

            override fun cancel() = downloader.cancel()
        }
    }
}
