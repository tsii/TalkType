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
     * Optionale Berechtigungen (V16a): einmalig in der SetupActivity fragen.
     * F28c: ab api 33 ist POST_NOTIFICATIONS KEINE reine Anzeigeberechtung mehr:
     * ohne sie kann das System den FGS-Typ microphone stumm stoppen (Export #2:
     * api 37, notif=no, Aufnahme nach 1,0 s tot) — daher in [requiredForApi].
     */
    val OPTIONAL_IN_ORDER = listOf(
        android.Manifest.permission.POST_NOTIFICATIONS,
    )

    /** Api-Level, ab dem der FGS-Typ microphone die Notification-Berechtigung
     *  zur Diktat-Bedingung macht (F28c; POST_NOTIFICATIONS gibt es ab 33). */
    const val NOTIF_REQUIRED_SINCE_API = 33

    /** F28c: Diktat-Bedingungen als Funktion des Api-Levels — unter 33 nur das
     *  Mikrofon (V16a), ab 33 zusätzlich die Notification-Berechtigung. */
    fun requiredForApi(apiLevel: Int): List<String> =
        if (apiLevel >= NOTIF_REQUIRED_SINCE_API) REQUIRED_IN_ORDER + OPTIONAL_IN_ORDER
        else REQUIRED_IN_ORDER

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
     * @param apiLevel    Device-Api-Level; Default 29 = V16a-Semantik (Anzeige
     *                    blockiert nie). Ab [NOTIF_REQUIRED_SINCE_API] wird
     *                    POST_NOTIFICATIONS zur Diktat-Bedingung (F28c).
     * @return nächster Schritt in der Reihenfolge von [requiredForApi]
     */
    fun nextStep(
        granted: Set<String>,
        permanentlyDenied: Set<String>,
        apiLevel: Int = 29,
    ): Decision {
        for (permission in requiredForApi(apiLevel)) {
            if (permission in granted) continue
            return if (permission in permanentlyDenied) {
                Decision.PermanentlyDenied(permission)
            } else {
                Decision.Ask(permission)
            }
        }
        return Decision.Ready
    }

    /** Kurzform: ist Diktat ohne weitere Dialoge möglich? (F28c: api-Levelabhängig.) */
    fun isReady(granted: Set<String>, permanentlyDenied: Set<String> = emptySet(), apiLevel: Int = 29): Boolean =
        nextStep(granted, permanentlyDenied, apiLevel) is Decision.Ready
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
     * F28c: NUN alle bekannten Diktat-Berechtigungen (inkl. POST_NOTIFICATIONS)
     * — vorher nur REQUIRED_IN_ORDER, wodurch das 🐞-Feld `permPostNotifications`
     * strukturell immer false war (Export #2: notif=no trotz möglicher Erteilung).
     */
    fun grantedSet(): Set<String> =
        (DictationPermissions.REQUIRED_IN_ORDER + DictationPermissions.OPTIONAL_IN_ORDER)
            .filter { checkPermission(it) }.toSet()

    fun permanentlyDeniedSet(): Set<String> =
        (DictationPermissions.REQUIRED_IN_ORDER + DictationPermissions.OPTIONAL_IN_ORDER)
            .filter { !checkPermission(it) && !shouldShowRationale(it) }
            .toSet()
}
