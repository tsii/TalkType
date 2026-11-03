package de.voicekeyboard.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Pure JVM tests for [Sha256] against known vectors (FIPS 180-2 and
 * computed binary fixtures — no invented hashes).
 */
class Sha256Test {

    @Test fun `empty string - FIPS vector`() {
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            Sha256.ofBytes(ByteArray(0)),
        )
    }

    @Test fun `abc - FIPS vector`() {
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            Sha256.ofBytes("abc".toByteArray()),
        )
    }

    @Test fun `multi-block text (fox)`() {
        assertEquals(
            "d7a8fbb307d7809469ca9abcb0082e4f8d5651e46d3cdb762d02d0bf37c9e592",
            Sha256.ofBytes("The quick brown fox jumps over the lazy dog".toByteArray()),
        )
    }

    @Test fun `binary fixture bytes 0 to 255`() {
        val bytes = ByteArray(256) { it.toByte() }
        assertEquals(
            "40aff2e9d2d8922e47afd4648e6967497158785fbd1da870e7110266bf944880",
            Sha256.ofBytes(bytes),
        )
    }

    @Test fun `ofFile == ofBytes for a small file`() {
        val f = File.createTempFile("sha", ".bin").apply {
            writeBytes(byteArrayOf(1, 2, 3, 4, 5))
            deleteOnExit()
        }
        assertEquals(Sha256.ofBytes(byteArrayOf(1, 2, 3, 4, 5)), Sha256.ofFile(f))
    }

    @Test fun `constantTimeEquals - equality and difference`() {
        assertTrue(Sha256.constantTimeEquals("aa", "aa"))
        assertFalse(Sha256.constantTimeEquals("aa", "ab"))
        assertFalse(Sha256.constantTimeEquals("aa", "aaa"))
    }
}
