package de.voicekeyboard.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Reine JVM-Tests für [DownloadPolicy] (E2/E3-Regeln). */
class DownloadPolicyTest {

    private val plenty = DownloadPolicy.MIN_FREE_BYTES + 1

    // (a) Netzwerk-Schalter aus → kein Start, auch bei genug Platz
    @Test fun networkDisabledBlocksStart() {
        assertEquals(
            DownloadPolicy.Decision.NetworkDisabled,
            DownloadPolicy.decide(networkEnabled = false, freeBytes = plenty, status = ModelStatus.ABSENT),
        )
    }

    // (b) freier Speicher < 2,5 GB → InsufficientSpace mit konkreter Zahl
    @Test fun insufficientSpaceBlocksStart() {
        val d = DownloadPolicy.decide(true, freeBytes = 800_000_000L, status = ModelStatus.ABSENT)
        assertTrue(d is DownloadPolicy.Decision.InsufficientSpace)
        assertEquals(800_000_000L, (d as DownloadPolicy.Decision.InsufficientSpace).freeBytes)
    }

    // Normalfall: Schalter an, Platz da, Modell fehlt → Start
    @Test fun absentWithSpaceAndNetworkStarts() {
        assertEquals(
            DownloadPolicy.Decision.Start,
            DownloadPolicy.decide(true, plenty, ModelStatus.ABSENT),
        )
    }

    // (c) bereits READY → kein Start (auch nicht bei Schalter aus)
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

    // (d) bei READY soll die UI den Netz-Schalter ausstellen
    @Test fun readyTurnsNetworkOff() {
        assertTrue(DownloadPolicy.shouldTurnNetworkOff(ModelStatus.READY))
        assertFalse(DownloadPolicy.shouldTurnNetworkOff(ModelStatus.ABSENT))
        assertFalse(DownloadPolicy.shouldTurnNetworkOff(ModelStatus.DOWNLOADING))
    }

    // (e) Fehlerklassen → kurze Nutzertext-Schlüssel
    @Test fun errorClassesMapToUserKeys() {
        assertEquals("netz", DownloadPolicy.userMessageKey(ModelError.Network("timeout")))
        assertEquals("hash", DownloadPolicy.userMessageKey(ModelError.HashMismatch("f", "a", "b")))
        assertEquals("platz", DownloadPolicy.userMessageKey(ModelError.Storage("voll")))
        assertEquals("abgebrochen", DownloadPolicy.userMessageKey(ModelError.Cancelled("f")))
        assertEquals("netz", DownloadPolicy.userMessageKey(ModelError.Http(503, "x")))
        // D1-Echttaxonomie: 4xx ist kein "Netzwerkfehler"
        assertEquals("server", DownloadPolicy.userMessageKey(ModelError.Http(403, "für encoder-model.int8.onnx")))
        assertEquals("server", DownloadPolicy.userMessageKey(ModelError.Http(404, "für x")))
        assertEquals("warte", DownloadPolicy.userMessageKey(ModelError.Http(429, "für x")))
        assertEquals("warte", DownloadPolicy.userMessageKey(ModelError.Http(408, "für x")))
        assertEquals("hash", DownloadPolicy.userMessageKey(ModelError.HashMismatch("f", "a", "b")))
        // D1-02: 416 ist ein Fortsetzungsproblem, keine Server-Ablehnung
        assertEquals("fortsetzung", DownloadPolicy.userMessageKey(ModelError.Http(416, "Range nicht erfüllbar")))
    }

    @Test fun `D1 detailForUser nie urls query oder token`() {
        // HTTP 403: Code + Dateiname, aber KEIN Query-String
        val e403 = ModelError.Http(403, "für encoder-model.int8.onnx")
        val d = e403.detailForUser()
        assertTrue("HTTP-Code vorhanden", d.contains("403"))
        assertTrue("Dateiname vorhanden", d.contains("encoder-model.int8.onnx"))
        assertFalse("keine URL", d.contains("http"))
        // Gegenprobe: selbst wenn der Detail-Text eine Signatur-URL enthaelt,
        // darf sie NIE in detailForUser() auftauchen (Regel 9)
        val poisoned = ModelError.Http(403,
            "für x?Signature=abc123&Expires=999&xip=1.2.3.4 https://us.aws.cdn.hf.co/xet-bridge-us/secret")
        val d2 = poisoned.detailForUser()
        assertFalse("Signature= darf nie vorkommen: $d2", d2.contains("Signature="))
        assertFalse("Expires= darf nie vorkommen", d2.contains("Expires="))
        assertFalse("xip= darf nie vorkommen", d2.contains("xip="))
        assertFalse("https:// darf nie vorkommen", d2.contains("https://"))
        // Network-Fehler: Detail wird geschrubbt
        val netPoisoned = ModelError.Network("read timeout https://evil.example/path?Signature=zzz")
        val d3 = netPoisoned.detailForUser()
        assertFalse(d3.contains("Signature="))
        assertFalse(d3.contains("https://"))
        // D1-01: Allowlist — vergiftete Eingabe mit URL, files/-Pfad und Bearer-Token
        val poisonedAll = ModelError.Network(
            "files/models/versions/v1/encoder-model.int8.onnx https://host/x?Signature=ABC Bearer abc123")
        val d4 = poisonedAll.detailForUser()
        assertFalse("kein http", d4.contains("http"))
        assertFalse("keine Signature", d4.contains("Signature"))
        assertFalse("kein files/", d4.contains("files/"))
        assertFalse("kein Bearer", d4.contains("Bearer"))
        assertFalse("kein /", d4.contains("/"))
        assertTrue("Dateiname bleibt", d4.contains("encoder-model.int8.onnx"))
        // Legitimer Fall bleibt sichtbar
        val ok = ModelError.Http(403, "für encoder-model.int8.onnx")
        val d5 = ok.detailForUser()
        assertTrue(d5.contains("HTTP 403"))
        assertTrue(d5.contains("encoder-model.int8.onnx"))
    }

    // Resume nach CANCELLED/FAILED: PARTIAL-ähnliche Zustände erlauben Start
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

    // zweiter Start (READY) ist No-Op — AlreadyReady dominiert vor Platz/Schalter
    @Test fun alreadyReadyDominatesOtherRules() {
        assertEquals(
            DownloadPolicy.Decision.AlreadyReady,
            DownloadPolicy.decide(networkEnabled = false, freeBytes = 0L, status = ModelStatus.READY),
        )
    }
}
