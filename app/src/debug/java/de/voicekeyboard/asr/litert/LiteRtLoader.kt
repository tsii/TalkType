package de.voicekeyboard.asr.litert

import org.tensorflow.lite.Interpreter
import java.io.File

/**
 * V10 (L1-01-Fix): load routine for LiteRT models — WITHOUT a heap copy.
 *
 * The interpreter is built from the FILE (`Interpreter(File, Options)`),
 * so that LiteRT uses mmap — the 2.3.2 API offers this constructor
 * (signature `Interpreter(java.io.File, Interpreter$Options)`, see the
 * javap output of the class). NO file-into-heap copy is made
 * (the previous approach copied the 648 MB encoder as a ByteArray into the
 * Java heap => OOM; proven by the source-text test in LiteRtLoaderTest).
 *
 * Size check against the manifest BEFORE `open` (wrong size ⇒ typed error
 * with a flat file name, no path — rule 9).
 * `Throwable` boundary: even errors (e.g. OutOfMemoryError) flow in a controlled
 * way into the typed error with its own, readable branch.
 */
object LiteRtLoader {

    /** Loaded model: interpreter + flat file name (diagnostics, rule 9). */
    data class LoadedModel(val interpreter: Interpreter, val fileName: String)

    /** Typed load error with a scrubbed cause. */
    class LiteRtLoadException(message: String, cause: Throwable? = null) :
        IllegalStateException(message, cause)

    /**
     * Opens [file] as a LiteRT interpreter with [threads] CPU threads.
     * Size check against [expectedSizeBytes] (0 = no check).
     */
    fun open(file: File, threads: Int, expectedSizeBytes: Long = 0L): LoadedModel {
        check(file.isFile) { "model file missing: ${file.name}" }
        if (expectedSizeBytes > 0 && file.length() != expectedSizeBytes) {
            throw LiteRtLoadException(
                "size differs: ${file.name} (${file.length()} != $expectedSizeBytes bytes)",
            )
        }
        val opts = Interpreter.Options().apply { setNumThreads(threads) }
        return try {
            LoadedModel(Interpreter(file, opts), file.name)
        } catch (e: OutOfMemoryError) {
            // Own readable branch (V10-2): heap is not sufficient.
            throw LiteRtLoadException(
                "Not enough memory for this model.", e,
            )
        } catch (t: Throwable) {
            // Throwable boundary: other errors too, controlled and typed
            throw LiteRtLoadException(
                "Could not open LiteRT model (${t.javaClass.simpleName}: " +
                    scrub(t.message) + ")",
                t,
            )
        }
    }

    /** Rule 9: trim tokens with path separators to flat file names, max. 120 characters. */
    private fun scrub(msg: String?): String {
        if (msg.isNullOrEmpty()) return "(no message)"
        val sb = StringBuilder()
        for (tok in msg.split(Regex("[\\s]+"))) {
            if (sb.isNotEmpty()) sb.append(' ')
            val cut = if (tok.contains('/') || tok.contains('\\')) {
                tok.substringAfterLast('/').substringAfterLast('\\')
            } else tok
            sb.append(if (cut.isNotEmpty()) cut else tok)
        }
        return sb.toString().take(120)
    }
}
