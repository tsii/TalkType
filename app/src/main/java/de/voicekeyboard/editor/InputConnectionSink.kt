package de.voicekeyboard.editor

import android.view.inputmethod.InputConnection

/** InputConnection nicht (mehr) verfügbar — geordneter Abbruch statt Crash. */
class EditorUnavailableException(message: String) : IllegalStateException(message)

/** Editor hat eine Teilschritt des Edits abgelehnt — kein blindes Retry (DESIGN §6). */
class EditorWriteException(message: String) : IllegalStateException(message)

/**
 * Android-Anbindung von [EditorSink] über die [InputConnection] der laufenden
 * IME-Sitzung (DESIGN.md §6).
 *
 * - [replaceBeforeCursor] klappt `beginBatchEdit` … `endBatchEdit` um
 *   `deleteSurroundingText` + `commitText` (kein `setComposingText`-Missbrauch,
 *   kein `setText`, keine Zwischenablage).
 * - `deleteSurroundingText` zählt **UTF-16-Codeeinheiten**; die Löschlänge wird
 *   zusätzlich per [Graphemes] an Graphemclustergrenzen zurückgestuft, damit nie
 *   ein `ä` (a + U+0308), Surrogatpaar oder Emoji-ZWJ zerteilt wird. Der
 *   ungelöschte Rest wird dem neuen Text vorangestellt — das Ergebnis ist identisch.
 * - Null-InputConnection (Editor weg/App-Wechsel): definierter Fehler
 *   ([EditorUnavailableException]) bzw. null bei Lesern — kein Crash.
 * - Keine Logs von Textinhalten (Privatsphäre, DESIGN §6).
 */
class InputConnectionSink(
    private val connectionProvider: () -> InputConnection?,
    /** Führt Editor-Operationen aus (Produktion: Main-Thread; Tests: Direkt). N-1. */
    private val editExecutor: java.util.concurrent.Executor = MAIN_EXECUTOR,
) : EditorSink {

    /** Standard-Executor: Main-Thread über den Haupt-Looper (N-1, DESIGN §2.3-1).
     *  Invariante: JEDE InputConnection-Operation läuft auf diesem Executor;
     *  der ASR-Worker dekodiert nur und ruft den Editor-Sync. JVM-Fallback inline,
     *  damit der Sink auch ohne Android-Looper (Unit-Test) funktioniert. */
    companion object {
        // lazy: im Unit-Test ohne Android-Runner wird Looper nicht berührt
        private val MAIN_EXECUTOR: java.util.concurrent.Executor by lazy {
            runCatching { android.os.Looper.getMainLooper() }.getOrNull()?.let { looper ->
                java.util.concurrent.Executor { runnable -> android.os.Handler(looper).post(runnable) }
            } ?: java.util.concurrent.Executor { it.run() } // JVM-Fallback (kein Looper)
        }

        /** Test-Direkt-Executor: gleiche Aufrufsemantik ohne Looper. */
        val DIRECT_EXECUTOR: java.util.concurrent.Executor = java.util.concurrent.Executor { it.run() }

        /** S5-2: endliche Wartefrist für den Editor-Sync (siehe onEditExecutor). */
        private const val EDIT_TIMEOUT_MS = 2_000L
    }

    /** Führt [block] auf dem Edit-Executor aus und WARTET (EditorSink ist synchron).
     *
     *  S5-2 Deadlock-Schutz: Ruft der Aufrufer bereits auf dem Main-Looper, wird
     *  der Block INLINE ausgeführt (ein Post an denselben Looper würde nie
     *  abgearbeitet, bevor wir mit await() blockieren → garantiertes Deadlock).
     *  Andernfalls Post + endliches Warten: EDIT_TIMEOUT_MS = 2000 ms — bemessen
     *  als Vielfaches der beobachteten Edit-Dauer (µs-Bereich) plus Reserve für
     *  einen blockierten Main-Thread unter Last (ANR-Schwelle liegt bei 5 s,
     *  wir melden also deutlich vor dem System). Bei Timeout typisierter Fehler
     *  (IllegalStateException), niemals unbegrenztes await().
     *
     *  A1 Geisterschreiben (REVIEW_STAGE6): Der gepostete Block ist per Handler
     *  nicht stornierbar — nach dem Timeout läuft er später auf dem Main-Thread.
     *  Deshalb prüft er VOR jeder Editorberührung das pro-Aufruf-[abandoned]-Flag
     *  und wird zum No-op, wenn der Aufrufer aufgegeben hat.
     *
     *  Invariante (A1, verbindlich): Nach Timeout wird der Block zum No-op; es
     *  gibt GENAU NULL Editorberührungen nach dem Aufgeben (kein
     *  deleteSurroundingText, kein commitText, kein getTextBeforeCursor, kein
     *  connectionProvider()-Aufruf — der Block liest dann nichts mehr).
     *  JEDE Wartezeit bleibt endlich; ein hängender Main-Thread führt zu einem
     *  sichtbaren Fehler, nie zu einem eingefrorenen Worker-Thread. */
    private fun <T> onEditExecutor(block: () -> T): T {
        if (isOnMainLooper()) return block() // (a) Reentranz: inline statt Post
        val latch = java.util.concurrent.CountDownLatch(1)
        val abandoned = java.util.concurrent.atomic.AtomicBoolean(false)
        var result: T? = null
        var error: Throwable? = null
        editExecutor.execute {
            try {
                if (abandoned.get()) {
                    // A1: Aufrufer hat aufgegeben → NULL Editorberührungen
                    android.util.Log.w("VK-IME", "edit abandoned (timeout) — no-op")
                    return@execute
                }
                result = block()
            } catch (t: Throwable) {
                if (!abandoned.get()) error = t // Fehler nach Aufgeben: niemand wartet
            } finally {
                latch.countDown()
            }
        }
        // (b) endliche Frist statt latch.await() ohne Grenze
        if (!latch.await(EDIT_TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS)) {
            abandoned.set(true) // A1: VOR dem Werfen markieren — Block wird No-op
            throw IllegalStateException(
                "Editor-Sync Timeout: Main-Thread hat den Edit nicht in " +
                    EDIT_TIMEOUT_MS + " ms ausgeführt (blockiert/überlastet?)"
            )
        }
        error?.let { throw it }
        @Suppress("UNCHECKED_CAST")
        return result as T
    }

    private fun isOnMainLooper(): Boolean {
        val looper = runCatching { android.os.Looper.getMainLooper() }.getOrNull() ?: return false
        val mine = runCatching { android.os.Looper.myLooper() }.getOrNull()
        return mine != null && mine === looper
    }

    /** Eigener Span in UTF-16-Codeeinheiten (nur Länge/Struktur, nie geloggt).
     *  AP7-E1c: @Volatile — ownText() wird auch außerhalb des Edit-Threads
     *  gelesen (Re-Baseline-Diagnosefelder); der Schreibsatz selbst bleibt
     *  Edit-Thread-serialisiert. */
    @Volatile private var ownSpan: String = ""

    /** A5 (v0.1.34): Sitzungswechsel — ownSpan-Leiche beseitigen (Toggle-W1:
     *  Coordinator+Sink werden über Sitzungen hinweg wiederverwendet, die
     *  Spanne der Vorgänger-Sitzung fälscht ab der zweiten Hypothese der
     *  Folgesitzung den Besitzbeweis in DeltaTyper.ownershipVerified).
     *  Direkte @Volatile-Zuweisung, synchron — es gibt keine Ableitungen von
     *  ownSpan; der Schreibsatz selbst bleibt Edit-Thread-serialisiert, und
     *  der Aufrufer (DictationController.start()) serialisiert die
     *  Sitzungsgrenze ohnehin (ein Orchestrierungs-Thread). */
    override fun resetSession() {
        ownSpan = ""
    }

    override fun replaceBeforeCursor(replaceLength: Int, newText: String) {
        onEditExecutor { replaceBeforeCursorSync(replaceLength, newText) }
    }

    private fun replaceBeforeCursorSync(replaceLength: Int, newText: String) {
        val ic = connectionProvider()
            ?: throw EditorUnavailableException("InputConnection nicht verfügbar")
        // Löschlänge an Graphemgrenze ausrichten (UTF-16-Einheiten bleiben erhalten,
        // nur die Grenze verschiebt sich ggf. nach vorne; Rest wandert in den Text).
        var deleteLength = replaceLength
        while (deleteLength > 0 && !Graphemes.isBoundary(ownSpan, ownSpan.length - deleteLength)) {
            deleteLength--
        }
        val keptPrefix = if (deleteLength < replaceLength) {
            ownSpan.substring(ownSpan.length - replaceLength, ownSpan.length - deleteLength)
        } else {
            ""
        }
        val effectiveText = keptPrefix + newText

        ic.beginBatchEdit() // gruppiert die Anzeige, ist keine Transaktion (DESIGN §6)
        try {
            if (deleteLength > 0 && !ic.deleteSurroundingText(deleteLength, 0)) {
                throw EditorWriteException("deleteSurroundingText wurde abgelehnt")
            }
            if (effectiveText.isNotEmpty() && !ic.commitText(effectiveText, 1)) {
                throw EditorWriteException("commitText wurde abgelehnt")
            }
            ownSpan = ownSpan.dropLast(replaceLength) + newText
        } finally {
            ic.endBatchEdit()
        }
    }

    override fun textBeforeCursor(maxChars: Int): CharSequence? =
        onEditExecutor {
            val ic = connectionProvider() ?: return@onEditExecutor null
            // 0 = keine Text-Flags: roher Text, kein Meta-Text
            ic.getTextBeforeCursor(maxChars, 0)
        }

    override fun ownLength(): Int = ownSpan.length

    // AP7-E1c: synchroner Eigen-Text aus der commit-Runde (siehe EditorSink).
    override fun ownText(): String = ownSpan
}
