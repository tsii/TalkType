package de.voicekeyboard.model

import java.io.File

/**
 * Typisierte Modellfehler (versiegelt), damit der Controller/Setup-Screen sie
 * unterscheiden kann (IMPLEMENTATION_PLAN M7: „Hash-Mismatch; freier Speicher;
 * Resume nach ETag-Wechsel" — je Fehler eigene UI-Antwort).
 */

sealed class ModelError(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause) {

    /** Verbindung/Timeout/DNS — Retry sinnvoll. */
    class Network(val detail: String, cause: Throwable? = null) :
        ModelError("Netzwerkfehler: $detail", cause)

    /** HTTP-Fehlerstatus — Retry nur bei 5xx sinnvoll, 4xx endgültig. */
    class Http(val statusCode: Int, val detail: String) :
        ModelError("HTTP $statusCode: $detail")

    /** Heruntergeladene Datei passt nicht zum Manifest — endgültig, .part löschen. */
    class HashMismatch(val fileName: String, val expected: String, val actual: String) :
        ModelError("SHA-256-Mismatch für '$fileName': erwartet $expected, erhalten $actual")

    /** Platte voll / kein Platz / Schreibfehler — Speicherfreigabe anbieten. */
    class Storage(detail: String, cause: Throwable? = null) :
        ModelError("Speicherfehler: $detail", cause)

    /** Nutzerabbruch — kein Fehlerbanner, Fortschritt einfrieren. */
    class Cancelled(val fileName: String) :
        ModelError("Download abgebrochen: $fileName")

    /**
     * D1: Kurze, ungefaehre Technik-Zeile fuer den Nutzer (dictierbar, z. B.
     * "HTTP 403 - Datei 2/4"). PRIVACY (Regel 9): niemals URLs, Query-Parameter
     * (Signature/Expires/xip) oder Token — nur Fehlerklassen und Zahlen.
     * Wird zusaetzlich zur normalen Meldung angezeigt (SetupActivity, State.Failed).
     */
    fun detailForUser(): String {
        // D1-01: Allowlist statt Blacklist. Tokenweise: URL-/Token-Fragmente
        // fallen ganz raus, Pfade kuerzen auf den Dateinamen (files/…/name → name,
        // via File(name).name), dann Zeichen-Allowlist (Buchstaben, Ziffern,
        // . , _ - : · ( ) % und Leerzeichen) + 48-Zeichen-Grenze. Schema-lose
        // Hostnamen, Header-Reste und URL-kodierte Fragmente kommen so nie durch.
        fun scrub(s: String): String {
            val cleaned = s.split(Regex("\\s+")).mapNotNull { tok ->
                when {
                    tok.contains("://") || tok.contains("Signature") ||
                        tok.contains("Bearer") || tok.startsWith("?") -> null
                    else -> java.io.File(tok.substringBefore('?')).name
                }
            }.joinToString(" ")
            return cleaned.filter { ch ->
                ch.isLetterOrDigit() || ch == '.' || ch == ',' || ch == '_' ||
                    ch == '-' || ch == ':' || ch == '·' ||
                    ch == '(' || ch == ')' || ch == '%' || ch == ' '
            }.take(48).trim()
        }
        return when (this) {
            is Http -> "HTTP $statusCode" +
                (detail.removePrefix("für ").takeIf { it.isNotEmpty() }?.let { " · ${scrub(it)}" } ?: "")
            is Network -> scrub(detail)
            is HashMismatch -> "Hash · ${scrub(fileName)}"
            is Storage -> "Speicher"
            is Cancelled -> "abgebrochen · ${scrub(fileName)}"
        }
    }

    companion object {}
}
