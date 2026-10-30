package de.voicekeyboard.asr.litert

import org.tensorflow.lite.Interpreter
import java.io.File

/**
 * V10 (L1-01-Fix): Laderoutine für LiteRT-Modelle — OHNE Heap-Kopie.
 *
 * Der Interpreter wird aus der DATEI gebaut (`Interpreter(File, Options)`),
 * damit LiteRT mmap nutzt — die 2.3.2-API bietet diesen Konstruktor
 * (Signature `Interpreter(java.io.File, Interpreter$Options)`, siehe
 * javap-Ausgabe der Klasse). Es wird KEINE Datei-in-Heap-Kopie gemacht
 * (der bisherige Weg kopierte den 648-MB-Encoder als ByteArray ins
 * Java-Heap => OOM; belegt durch den Quelltext-Test in LiteRtLoaderTest).
 *
 * Größenprüfung gegen das Manifest VOR dem `open` (falsche Größe ⇒
 * typisierter Fehler mit flachem Dateinamen, kein Pfad — Regel 9).
 * `Throwable`-Grenze: auch Errors (z. B. OutOfMemoryError) laufen kontrolliert
 * in den typisierten Fehler mit eigenem, deutsch lesbarem Zweig.
 */
object LiteRtLoader {

    /** Geladenes Modell: Interpreter + flacher Dateiname (Diagnose, Regel 9). */
    data class LoadedModel(val interpreter: Interpreter, val fileName: String)

    /** Typisierter Laderfehler mit bereinigter Ursache. */
    class LiteRtLoadException(message: String, cause: Throwable? = null) :
        IllegalStateException(message, cause)

    /**
     * Öffnet [file] als LiteRT-Interpreter mit [threads] CPU-Threads.
     * Größenprüfung gegen [expectedSizeBytes] (0 = keine Prüfung).
     */
    fun open(file: File, threads: Int, expectedSizeBytes: Long = 0L): LoadedModel {
        check(file.isFile) { "Modell-Datei fehlt: ${file.name}" }
        if (expectedSizeBytes > 0 && file.length() != expectedSizeBytes) {
            throw LiteRtLoadException(
                "Größe abweichend: ${file.name} (${file.length()} != $expectedSizeBytes Bytes)",
            )
        }
        val opts = Interpreter.Options().apply { setNumThreads(threads) }
        return try {
            LoadedModel(Interpreter(file, opts), file.name)
        } catch (e: OutOfMemoryError) {
            // Eigener, deutsch lesbarer Zweig (V10-2): Heap reicht nicht.
            throw LiteRtLoadException(
                "Der Arbeitsspeicher reicht für dieses Modell nicht.", e,
            )
        } catch (t: Throwable) {
            // Throwable-Grenze: auch sonstige Errors kontrolliert typisiert
            throw LiteRtLoadException(
                "LiteRT-Modell konnte nicht geöffnet werden (${t.javaClass.simpleName}: " +
                    scrub(t.message) + ")",
                t,
            )
        }
    }

    /** Regel 9: Token mit Pfadtrennern auf flachen Dateinamen kürzen, max. 120 Zeichen. */
    private fun scrub(msg: String?): String {
        if (msg.isNullOrEmpty()) return "(keine Meldung)"
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
