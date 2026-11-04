package de.voicekeyboard.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure JVM tests for [DownloadPolicy] (E2/E3 rules). */
class DownloadPolicyTest {

    private val plenty = DownloadPolicy.MIN_FREE_BYTES + 1

    // (a) network switch off → no start, even with enough space
    @Test fun networkDisabledBlocksStart() {
        assertEquals(
            DownloadPolicy.Decision.NetworkDisabled,
            DownloadPolicy.decide(networkEnabled = false, freeBytes = plenty, status = ModelStatus.ABSENT),
        )
    }

    // (b) free storage < 2.5 GB → InsufficientSpace with a concrete number
    @Test fun insufficientSpaceBlocksStart() {
        val d = DownloadPolicy.decide(true, freeBytes = 800_000_000L, status = ModelStatus.ABSENT)
        assertTrue(d is DownloadPolicy.Decision.InsufficientSpace)
        assertEquals(800_000_000L, (d as DownloadPolicy.Decision.InsufficientSpace).freeBytes)
    }

    // normal case: switch on, space available, model missing → start
    @Test fun absentWithSpaceAndNetworkStarts() {
        assertEquals(
            DownloadPolicy.Decision.Start,
            DownloadPolicy.decide(true, plenty, ModelStatus.ABSENT),
        )
    }

    // (c) already READY → no start (not even with the switch off)
    @Test fun readyNeverStarts() {
        assertEquals(
            DownloadPolicy.Decision.AlreadyReady,
            DownloadPolicy.decide(true, plenty, ModelStatus.READY),
        )
        assertEquals(
            DownloadPolicy.Decision.AlreadyReady,
            DownloadPolicy.decide(false, 0L, ModelStatus.READY),
        )
    }

    // (e) error classes → short user-text keys
    @Test fun errorClassesMapToUserKeys() {
        assertEquals("netz", DownloadPolicy.userMessageKey(ModelError.Network("timeout")))
        assertEquals("hash", DownloadPolicy.userMessageKey(ModelError.HashMismatch("f", "a", "b")))
        assertEquals("platz", DownloadPolicy.userMessageKey(ModelError.Storage("voll")))
        assertEquals("abgebrochen", DownloadPolicy.userMessageKey(ModelError.Cancelled("f")))
        assertEquals("netz", DownloadPolicy.userMessageKey(ModelError.Http(503, "x")))
        // D1 real taxonomy: 4xx is not a "network error"
        assertEquals("server", DownloadPolicy.userMessageKey(ModelError.Http(403, "for encoder-model.int8.onnx")))
        assertEquals("server", DownloadPolicy.userMessageKey(ModelError.Http(404, "for x")))
        assertEquals("warte", DownloadPolicy.userMessageKey(ModelError.Http(429, "for x")))
        assertEquals("warte", DownloadPolicy.userMessageKey(ModelError.Http(408, "for x")))
        assertEquals("hash", DownloadPolicy.userMessageKey(ModelError.HashMismatch("f", "a", "b")))
        // D1-02: 416 is a resume problem, not a server rejection
        assertEquals("fortsetzung", DownloadPolicy.userMessageKey(ModelError.Http(416, "range not satisfiable")))
    }

    @Test fun `D1 detailForUser never urls query or token`() {
        // HTTP 403: code + file name, but NO query string
        val e403 = ModelError.Http(403, "for encoder-model.int8.onnx")
        val d = e403.detailForUser()
        assertTrue("HTTP code present", d.contains("403"))
        assertTrue("file name present", d.contains("encoder-model.int8.onnx"))
        assertFalse("no URL", d.contains("http"))
        // counter-check: even if the detail text contains a signature URL,
        // it must NEVER appear in detailForUser() (rule 9)
        val poisoned = ModelError.Http(403,
            "for x?Signature=abc123&Expires=999&xip=1.2.3.4 https://us.aws.cdn.hf.co/xet-bridge-us/secret")
        val d2 = poisoned.detailForUser()
        assertFalse("Signature= must never occur: $d2", d2.contains("Signature="))
        assertFalse("Expires= must never occur", d2.contains("Expires="))
        assertFalse("xip= must never occur", d2.contains("xip="))
        assertFalse("https:// must never occur", d2.contains("https://"))
        // network error: the detail is scrubbed
        val netPoisoned = ModelError.Network("read timeout https://evil.example/path?Signature=zzz")
        val d3 = netPoisoned.detailForUser()
        assertFalse(d3.contains("Signature="))
        assertFalse(d3.contains("https://"))
        // D1-01: allowlist — poisoned input with a URL, a files/ path and a bearer token
        val poisonedAll = ModelError.Network(
            "files/models/versions/v1/encoder-model.int8.onnx https://host/x?Signature=ABC Bearer abc123")
        val d4 = poisonedAll.detailForUser()
        assertFalse("no http", d4.contains("http"))
        assertFalse("no Signature", d4.contains("Signature"))
        assertFalse("no files/", d4.contains("files/"))
        assertFalse("no Bearer", d4.contains("Bearer"))
        assertFalse("no /", d4.contains("/"))
        assertTrue("file name stays", d4.contains("encoder-model.int8.onnx"))
        // the legitimate case stays visible
        val ok = ModelError.Http(403, "for encoder-model.int8.onnx")
        val d5 = ok.detailForUser()
        assertTrue(d5.contains("HTTP 403"))
        assertTrue(d5.contains("encoder-model.int8.onnx"))
    }

    // resume after CANCELLED/FAILED: PARTIAL-like states allow a start
    @Test fun partialStateAllowsRestart() {
        assertEquals(
            DownloadPolicy.Decision.Start,
            DownloadPolicy.decide(true, plenty, ModelStatus.DOWNLOADING),
        )
        assertEquals(
            DownloadPolicy.Decision.Start,
            DownloadPolicy.decide(true, plenty, ModelStatus.BROKEN),
        )
    }

    // a second start (READY) is a no-op — AlreadyReady dominates over space/switch
    @Test fun alreadyReadyDominatesOtherRules() {
        assertEquals(
            DownloadPolicy.Decision.AlreadyReady,
            DownloadPolicy.decide(networkEnabled = false, freeBytes = 0L, status = ModelStatus.READY),
        )
    }
}
