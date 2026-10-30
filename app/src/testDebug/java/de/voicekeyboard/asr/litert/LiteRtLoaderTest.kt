package de.voicekeyboard.asr.litert

import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * V10 (L1-01-Fix): Loader-Tests ohne 648 MB —
 *  (a) kaputte Mini-Datei ⇒ typisierter Fehler, keine unbehandelte Exception
 *  (b) Größenprüfung gegen das Manifest VOR dem open (flacher Name, kein Pfad)
 *  (c) Quelltext-Test: der Loader enthält KEIN readBytes (billig, unverrückbar)
 */
class LiteRtLoaderTest {

    private fun tempFile(name: String, content: String): File =
        createTempFile(name.removeSuffix(".tflite"), ".tflite").apply {
            writeText(content)
            deleteOnExit()
        }

    @Test
    fun `kaputte tflite liefert typisierten fehler ohne heap_blasen`() {
        val f = tempFile("broken", "dies ist kein tflite-modell, nur text")
        try {
            LiteRtLoader.open(f, 1)
            fail("LiteRtLoadException erwartet")
        } catch (e: LiteRtLoader.LiteRtLoadException) {
            // typisiert, kontrolliert — Meldung enthaelt Dateinamen-Klasse/Ursache, keinen Pfad
            assertTrue(e.message!!.isNotEmpty())
            assertTrue("kein Pfad in der Meldung: ${e.message}", '/' !in e.message!!)
            assertTrue("kein Pfad in der Meldung", '\\' !in e.message!!)
        }
    }

    @Test
    fun `groessenpruefung vor open meldet flachen dateinamen`() {
        val f = tempFile("wrongsize", "x")
        try {
            // erwartete Groesse absichtlich falsch (1 statt 2 Bytes)
            LiteRtLoader.open(f, 1, expectedSizeBytes = 2L)
            fail("LiteRtLoadException wegen Groesse erwartet")
        } catch (e: LiteRtLoader.LiteRtLoadException) {
            assertTrue(e.message!!.contains(f.name)) // flacher Dateiname
            assertTrue("kein Pfad: ${e.message}", '/' !in e.message!!.substringAfter(f.name))
            assertTrue(e.message!!.contains("Größe abweichend"))
        }
    }

    @Test
    fun `fehlende datei wirft kontrolliert`() {
        val missing = File(createTempDirectory(), "gibtsnicht.tflite")
        try {
            LiteRtLoader.open(missing, 1)
            fail("Kontrollierter Fehler erwartet")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("fehlt"))
        }
    }

    /**
     * Quelltext-Test (bewusst billig und unVERRÜCKbar): der Loader darf KEIN
     * `readBytes` enthalten — genau das war der Blocker (648-MB-Heap-Kopie).
     * Auch kein readAllBytes/großes ByteArray-Allokationsmuster.
     */
    @Test
    fun `loader_quelltext_enthält_kein_readbytes`() {
        // V13: LiteRT-Quellen leben im DEBUG-Source-Set
        val source = File("src/debug/java/de/voicekeyboard/asr/litert/LiteRtLoader.kt")
            .readText()
        assertTrue("Quelltext-Datei gefunden", source.contains("object LiteRtLoader"))
        assertTrue(
            "LiteRtLoader darf readBytes nicht enthalten (L1-01: 648-MB-Heap-Kopie)",
            !source.contains("readBytes"),
        )
        assertTrue(
            "LiteRtLoader darf readAllBytes nicht enthalten",
            !source.contains("readAllBytes"),
        )
        assertTrue(
            "LiteRtLoader muss Interpreter(File, ...) nutzen (mmap-Weg)",
            source.contains("Interpreter(file, opts)"),
        )
    }

    private fun createTempDirectory(): File =
        kotlin.io.path.createTempDirectory("v10loader").toFile().apply { deleteOnExit() }
}
