package de.voicekeyboard.ime

/**
 * Reine Entscheidungslogik für Diktat-Berechtigungen (frameworkfrei, JVM-testbar):
 * welche Berechtigungen fehlen, in welcher Reihenfolge gefragt wird und was bei
 * dauerhaft abgelehnter Berechtigung zu tun ist ("nicht erneut fragen").
 *
 * Android-Dünnschicht: [androidRequester] startet die Systemdialoge — im
 * Produktionscode per ActivityStarter/PendingIntent (hier Lambda, damit die
 * Logik ohne Robolectric prüfbar ist).
 */
object DictationPermissions {

    /**
     * Kern-Berechtigung für Diktat (V16a): NUR das Mikrofon.
     * POST_NOTIFICATIONS sperrte bisher fälschlich das Diktat (Live-Fehler:
     * FGS läuft laut CaptureForegroundService auch ohne sie — Notification
     * nur unsichtbar). Die Anzeige-Frage gehört in die Setup-Seite.
     */
    val REQUIRED_IN_ORDER = listOf(
        android.Manifest.permission.RECORD_AUDIO,
    )

    /**
     * Optionale Berechtigungen (V16a): einmalig in der SetupActivity fragen;
     * sie haben KEINE Auswirkung auf Ready — nextStep/isReady ignorieren sie.
     */
    val OPTIONAL_IN_ORDER = listOf(
        android.Manifest.permission.POST_NOTIFICATIONS,
    )

    sealed class Decision {
        /** Alles vorhanden — Diktat darf starten. */
        data object Ready : Decision()

        /** Diese Berechtigung fehlt und muss (noch) gefragt werden. */
        data class Ask(val permission: String) : Decision()

        /** Dauerhaft abgelehnt: Systemdialog würde nicht mehr kommen — Nutzer
         *  muss in die App-Einstellungen; Diktat bleibt gesperrt. */
        data class PermanentlyDenied(val permission: String) : Decision()
    }

    /**
     * Entscheidet den nächsten Schritt. Parameter sind bewusst injizierte
     * Fakten (kein Framework):
     *
     * @param granted     aktuell erteilte Berechtigungen (Menge)
     * @param permanentlyDenied Berechtigungen, die der Nutzer "nie wieder" abgelehnt hat
     * @return nächster Schritt in der REQUIRED_IN_ORDER-Reihenfolge
     */
    fun nextStep(
        granted: Set<String>,
        permanentlyDenied: Set<String>,
    ): Decision {
        for (permission in REQUIRED_IN_ORDER) {
            if (permission in granted) continue
            return if (permission in permanentlyDenied) {
                Decision.PermanentlyDenied(permission)
            } else {
                Decision.Ask(permission)
            }
        }
        return Decision.Ready
    }

    /** Kurzform: ist Diktat ohne weitere Dialoge möglich? */
    fun isReady(granted: Set<String>, permanentlyDenied: Set<String> = emptySet()): Boolean =
        nextStep(granted, permanentlyDenied) is Decision.Ready
}

/** Android-Dünnschicht: löst die Anfrage im Namen der sichtbaren Activity/IME aus. */
class AndroidPermissionsRequester(
    private val checkPermission: (String) -> Boolean,
    private val shouldShowRationale: (String) -> Boolean,
) {
    /**
     * Berechnet die Fakten für die reine Logik:
     * Nach einer Ablehnung meldet `shouldShowRequestPermissionRationale` false,
     * wenn der Nutzer "nicht erneut fragen" gewählt hat (oder die Policy es
     * verweigert) — dann führt kein Weg an den App-Einstellungen vorbei.
     */
    fun grantedSet(): Set<String> =
        DictationPermissions.REQUIRED_IN_ORDER.filter { checkPermission(it) }.toSet()

    fun permanentlyDeniedSet(): Set<String> =
        DictationPermissions.REQUIRED_IN_ORDER
            .filter { !checkPermission(it) && !shouldShowRationale(it) }
            .toSet()
}
