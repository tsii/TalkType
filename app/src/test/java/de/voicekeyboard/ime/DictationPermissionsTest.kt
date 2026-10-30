package de.voicekeyboard.ime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * V16a: Notification-Berechtigung ist WERBEND, aber NIEMALS SPERREND.
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
}
