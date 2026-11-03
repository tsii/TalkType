package de.voicekeyboard.model

import java.io.File

/**
 * Typed model errors (sealed), so the controller/setup screen can
 * distinguish them (IMPLEMENTATION_PLAN M7: „Hash-Mismatch; freier Speicher;
 * Resume nach ETag-Wechsel“ — a separate UI response per error).
 */

sealed class ModelError(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause) {

    /** Connection/timeout/DNS — retry sensible. */
    class Network(val detail: String, cause: Throwable? = null) :
        ModelError("Netzwerkfehler: $detail", cause)

    /** HTTP error status — retry only sensible for 5xx, 4xx final. */
    class Http(val statusCode: Int, val detail: String) :
        ModelError("HTTP $statusCode: $detail")

    /** Downloaded file does not match the manifest — final, delete .part. */
    class HashMismatch(val fileName: String, val expected: String, val actual: String) :
        ModelError("SHA-256-Mismatch für '$fileName': erwartet $expected, erhalten $actual")

    /** Disk full / no space / write error — offer storage cleanup. */
    class Storage(detail: String, cause: Throwable? = null) :
        ModelError("Speicherfehler: $detail", cause)

    /** User abort — no error banner, freeze the progress. */
    class Cancelled(val fileName: String) :
        ModelError("Download abgebrochen: $fileName")

    /**
     * D1: short, approximate technical line for the user (dictatable, e. g.
     * "HTTP 403 - Datei 2/4"). PRIVACY (rule 9): never URLs, query parameters
     * (Signature/Expires/xip) or tokens — only error classes and numbers.
     * Shown in addition to the normal message (SetupActivity, State.Failed).
     */
    fun detailForUser(): String {
        // D1-01: allowlist instead of blacklist. Token-wise: URL/token fragments
        // drop out completely, paths shorten to the file name (files/…/name → name,
        // via File(name).name), then character allowlist (letters, digits,
        // . , _ - : · ( ) % and spaces) + 48-character limit. Scheme-less
        // host names, header remnants and URL-encoded fragments never pass this way.
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
