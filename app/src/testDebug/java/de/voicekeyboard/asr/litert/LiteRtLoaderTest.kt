package de.voicekeyboard.asr.litert

import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * V10 (L1-01 fix): loader tests without 648 MB —
 *  (a) broken mini file ⇒ typed error, no unhandled exception
 *  (b) size check against the manifest BEFORE open (flat name, no path)
 *  (c) source test: the loader contains NO readBytes (cheap, immovable)
 */
class LiteRtLoaderTest {

    private fun tempFile(name: String, content: String): File =
        createTempFile(name.removeSuffix(".tflite"), ".tflite").apply {
            writeText(content)
            deleteOnExit()
        }

    @Test
    fun `broken tflite yields a typed error without heap bubbles`() {
        val f = tempFile("broken", "dies ist kein tflite-modell, nur text")
        try {
            LiteRtLoader.open(f, 1)
            fail("LiteRtLoadException expected")
        } catch (e: LiteRtLoader.LiteRtLoadException) {
            // typed, controlled — the message contains the file-name class/cause, no path
            assertTrue(e.message!!.isNotEmpty())
            assertTrue("no path in the message: ${e.message}", '/' !in e.message!!)
            assertTrue("no path in the message", '\\' !in e.message!!)
        }
    }

    @Test
    fun `size check before open reports the flat file name`() {
        val f = tempFile("wrongsize", "x")
        try {
            // the expected size is deliberately wrong (1 instead of 2 bytes)
            LiteRtLoader.open(f, 1, expectedSizeBytes = 2L)
            fail("LiteRtLoadException due to size expected")
        } catch (e: LiteRtLoader.LiteRtLoadException) {
            assertTrue(e.message!!.contains(f.name)) // flat file name
            assertTrue("no path: ${e.message}", '/' !in e.message!!.substringAfter(f.name))
            assertTrue(e.message!!.contains("Größe abweichend"))
        }
    }

    @Test
    fun `missing file throws in a controlled way`() {
        val missing = File(createTempDirectory(), "gibtsnicht.tflite")
        try {
            LiteRtLoader.open(missing, 1)
            fail("controlled error expected")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("fehlt"))
        }
    }

    /**
     * Source test (deliberately cheap and unmovable): the loader must NOT contain
     * `readBytes` — exactly that was the blocker (the 648-MB heap copy).
     * Also no readAllBytes/large ByteArray allocation pattern.
     */
    @Test
    fun `loader source contains no readbytes`() {
        // V13: the LiteRT sources live in the DEBUG source set
        val source = File("src/debug/java/de/voicekeyboard/asr/litert/LiteRtLoader.kt")
            .readText()
        assertTrue("source file found", source.contains("object LiteRtLoader"))
        assertTrue(
            "LiteRtLoader must not contain readBytes (L1-01: 648-MB heap copy)",
            !source.contains("readBytes"),
        )
        assertTrue(
            "LiteRtLoader must not contain readAllBytes",
            !source.contains("readAllBytes"),
        )
        assertTrue(
            "LiteRtLoader must use Interpreter(File, ...) (the mmap path)",
            source.contains("Interpreter(file, opts)"),
        )
    }

    private fun createTempDirectory(): File =
        kotlin.io.path.createTempDirectory("v10loader").toFile().apply { deleteOnExit() }
}
