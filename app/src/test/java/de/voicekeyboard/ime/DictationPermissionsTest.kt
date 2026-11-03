package de.voicekeyboard.ime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * V16a: Notification-Berechtigung ist WERBEND, aber unter api 33 NIEMALS
 * SPERREND.
 *
 * F28c (Export #2, api 37, notif=no, Aufnahme nach 1,0 s stumm beendet):
 * AB api 33 ist POST_NOTIFICATIONS Diktat-Bedingung — der FGS-Typ microphone
 * kann ohne sie vom System stumm gestoppt werden. Die V16a-Tests unten laufen
 * mit dem Default apiLevel=29 und bleiben gültig; der api-33-Guard ist im
 * Test `F28c api33 ohne notif-Permission …` belegt.
 *
 * Kern-Fehler aus dem Live-Betrieb: REQUIRED_IN_ORDER enthielt
 * POST_NOTIFICATIONS — fehlte sie, gab nextStep nie Ready und
 * ensurePermissions() brach vor startFgs() ab ⇒ Diktat unmöglich,
 * obwohl das Mikrofon erteilt war. Der FGS läuft laut
 * CaptureForegroundService-Dokumentation AUCH ohne POST_NOTIFICATIONS
 * (Notification nur unsichtbar). Die alten Tests, die "beide nötig"
 * behaupteten, waren die FALSCHE Erwartung und sind auf die neue,
 * richtige Semantik gezogen (V16a): nur RECORD_AUDIO ist Kern-Bedingung;
 * POST_NOTIFICATIONS ist optional (Frage nur in der Setup-Seite).
 */
class DictationPermissionsTest {

    private val mic = android.Manifest.permission.RECORD_AUDIO
    private val notif = android.Manifest.permission.POST_NOTIFICATIONS

    // (a) DER Nutzer-Fall: Mikro erteilt + Anzeige verweigert ⇒ Ready
    @Test fun `V16a Mikro erteilt Anzeige verweigert - Ready`() {
        assertEquals(
            DictationPermissions.Decision.Ready,
            DictationPermissions.nextStep(granted = setOf(mic), permanentlyDenied = setOf(notif)),
        )
        // auch bei normaler (nicht dauerhafter) Verweigerung:
        assertEquals(
            DictationPermissions.Decision.Ready,
            DictationPermissions.nextStep(granted = setOf(mic), permanentlyDenied = emptySet()),
        )
    }

    // (b) Mikro verweigert ⇒ Ask(RECORD_AUDIO)
    @Test fun `V16a Mikro verweigert - fragt nur das Mikrofon`() {
        assertEquals(
            DictationPermissions.Decision.Ask(mic),
            DictationPermissions.nextStep(emptySet(), emptySet()),
        )
    }

    // (c) Mikro dauerhaft verweigert ⇒ PermanentlyDenied(RECORD_AUDIO)
    @Test fun `V16a Mikro dauerhaft verweigert - PermanentlyDenied nur fuer Mikro`() {
        assertEquals(
            DictationPermissions.Decision.PermanentlyDenied(mic),
            DictationPermissions.nextStep(emptySet(), setOf(mic)),
        )
    }

    // (d) isReady unabhängig von POST_NOTIFICATIONS
    @Test fun `V16a isReady unabhaengig von POST_NOTIFICATIONS`() {
        assertTrue(DictationPermissions.isReady(setOf(mic)))
        assertTrue(DictationPermissions.isReady(setOf(mic, notif)))
        assertFalse(DictationPermissions.isReady(emptySet()))
        assertFalse(DictationPermissions.isReady(setOf(notif))) // Anzeige allein reicht nie
    }

    // (e) OPTIONAL_IN_ORDER enthält die Anzeige; nextStep liefert sie nie als Hinderungsgrund
    @Test fun `V16a optional enthaelt Anzeige und blockiert nie`() {
        assertEquals(listOf(notif), DictationPermissions.OPTIONAL_IN_ORDER)
        // nextStep kennt die optionale Berechtigung als Entscheidungsweg NICHT:
        for (granted in listOf(emptySet<String>(), setOf(notif), setOf(mic))) {
            val d = DictationPermissions.nextStep(granted, setOf(notif))
            assertFalse(
                "nextStep darf optionale Berechtigungen nie liefern: $d",
                d is DictationPermissions.Decision.Ask && d.permission == notif ||
                    d is DictationPermissions.Decision.PermanentlyDenied && d.permission == notif,
            )
        }
    }

    // Bestands-Semantik, gezogen auf ein Pflicht-Merkmal: Reihenfolge stabil
    @Test fun `V16a REQUIRED enthaelt nur das Mikrofon`() {
        assertEquals(listOf(mic), DictationPermissions.REQUIRED_IN_ORDER)
    }

    // F28c (Export #2: api 37, notif=no, Aufnahme nach 1,0 s stumm beendet):
    // ab api 33 ist POST_NOTIFICATIONS Diktat-Bedingung. Guard-Logik: ohne sie
    // liefert nextStep Ask statt Ready — VoiceKeyboardService.bricht in
    // ensurePermissions() BEVOR startFgs()/AudioRecord.startRecording() ab und
    // zeigt einen sichtbaren Fehler (neuer String), statt still zu starten.
    @Test fun `F28c api33 ohne notif-Permission - kein Start, Ask statt Ready`() {
        // Mikro erteilt, Anzeige NICHT: unter api 33 Ready (V16a bleibt erhalten)
        assertEquals(
            DictationPermissions.Decision.Ready,
            DictationPermissions.nextStep(setOf(mic), emptySet(), 32),
        )
        // ab api 33 mit Mikro ODER ohne: Ask(POST_NOTIFICATIONS) — Diktat gesperrt
        assertEquals(
            DictationPermissions.Decision.Ask(notif),
            DictationPermissions.nextStep(setOf(mic), emptySet(), 33),
        )
        assertEquals(
            DictationPermissions.Decision.Ask(notif),
            DictationPermissions.nextStep(setOf(mic), emptySet(), 37),
        )
        // dauerhaft abgelehnt: kein zweiter Dialog, Weg zu den Einstellungen
        assertEquals(
            DictationPermissions.Decision.PermanentlyDenied(notif),
            DictationPermissions.nextStep(setOf(mic), setOf(notif), 33),
        )
        // mit erteilter Anzeige: Ready — Diktat darf (wieder) starten
        assertEquals(
            DictationPermissions.Decision.Ready,
            DictationPermissions.nextStep(setOf(mic, notif), emptySet(), 33),
        )
        // isReady spiegelt denselben Guard
        assertFalse("api 33 ohne notif = nicht ready", DictationPermissions.isReady(setOf(mic), emptySet(), 33))
        assertTrue("api 33 mit notif = ready", DictationPermissions.isReady(setOf(mic, notif), emptySet(), 33))
        assertTrue("api 32 ohne notif = ready (V16a)", DictationPermissions.isReady(setOf(mic), emptySet(), 32))
        // Reihenfolge: Mikro wird VOR Anzeige gefragt (Mikro fehlt + api 33)
        assertEquals(
            DictationPermissions.Decision.Ask(mic),
            DictationPermissions.nextStep(emptySet(), emptySet(), 33),
        )
    }
}
