package de.voicekeyboard.editor

/**
 * Besitz-Protokoll für den Diktat-Text im Editor (DESIGN.md §6).
 *
 * Erkennt, ob der eingefügte Text noch „unser" ist, aus:
 * - dem erwarteten eigenen Text (nach jedem erfolgreichen eigenen Edit gesetzt),
 * - „ausstehenden eigenen Selektions-Callbacks" (unser Edit löst selbst
 *   `onUpdateSelection` aus — das ist KEINE Fremdeingabe),
 * - der Editor-Generation (Editor-/App-Wechsel hebt den Besitz auf).
 *
 * Nach einer erkannten Fremdeingabe (Nutzer tippt/verschiebt Cursor, App-Wechsel)
 * wird der Besitz **dauerhaft** für diese Sitzung entzogen — [isOwned] liefert
 * danach immer false; erst [reset] (neue Diktat-Sitzung) stellt ihn wieder her.
 *
 * Frameworkfrei: die absoluten Selektionspositionen stammen vom Aufrufer
 * (InputMethodService); ausgewertet werden Muster (Range-Selektion,
 * Callback-Reihenfolge), nicht absolute Offsets — die Cursor-Endpositionsprüfung
 * übernimmt die Android-Schicht (InputConnectionSink, Teil 3).
 */
class OwnershipTracker {

    var generation: Long = 0L
        private set

    private val lock = Any()
    private var expectedText: String = ""
    private var ownEditPending = false
    private var baselineTaken = false
    private var lost = false

    /** true, sobald der Besitz verloren/durch Fremdeingabe abgegeben wurde. */
    val ownershipLost: Boolean
        get() = synchronized(lock) { lost }

    /** Neue Diktat-Sitzung im selben Editor: Erwartungen zurücksetzen, Besitz zurück. */
    fun reset() = synchronized(lock) {
        expectedText = ""
        ownEditPending = false
        baselineTaken = false
        lost = false
    }

    /**
     * Editor-/App-Wechsel: neue Generation, Besitz gilt als verloren, bis eine
     * neue Sitzung mit [reset] startet (Text des alten Editors ist irrelevant).
     */
    fun onEditorChanged() = synchronized(lock) {
        generation++
        lost = true
    }

    /**
     * Nach einem **erfolgreichen eigenen Edit**: neuer erwarteter Text; der damit
     * ausgelöste eigene `onUpdateSelection`-Callback wird als solcher erwartet.
     */
    fun onOwnEdit(newExpectedText: String) = synchronized(lock) {
        expectedText = newExpectedText
        ownEditPending = true
    }

    /**
     * `onUpdateSelection`-artiges Signal (UI-Thread). Reihenfolge entscheidet:
     * 1. wir erwarten den Callback unseres eigenen Edits → konsumieren (kein
     *    Besitzverlust); eine Range-Selektion ist trotzdem Fremdeingabe,
     * 2. vor dem ersten eigenen Edit → Baseline (System-Callback beim Start),
     * 3. alles Weitere ohne erwarteten eigenen Callback → **Fremdeingabe**,
     *    Besitz dauerhaft verloren.
     */
    fun onUpdateSelection(
        oldSelStart: Int,
        oldSelEnd: Int,
        newSelStart: Int,
        newSelEnd: Int,
    ) = synchronized(lock) {
        if (newSelStart != newSelEnd) {
            // Selektionsbereich kommt nie von unserem Edit (commitText setzt Cursor)
            lost = true
            ownEditPending = false
            return
        }
        when {
            ownEditPending -> ownEditPending = false // unser eigener Callback
            !baselineTaken -> baselineTaken = true    // System-Baseline vor dem 1. Edit
            else -> lost = true                       // Nutzer hat dazwischengegriffen
        }
    }

    /**
     * Besitzprüfung vor jedem Schreiben: [expectedText] muss das Suffix des
     * tatsächlichen Texts vor dem Cursor sein (Nutzertext VOR unserem Bereich ist
     * erlaubt, dahinter geschriebener/abweichender Text nicht).
     *
     * @param expectedText erwarteter eigener Text (z. B. DeltaTyper.currentText).
     * @param actualBeforeCursor aktueller Text vor dem Cursor (gleiche Länge wie
     *   erwartet, vom EditorSink gelesen) oder null, wenn nicht lesbar.
     */
    fun isOwned(expectedText: String, actualBeforeCursor: CharSequence?): Boolean =
        synchronized(lock) {
            if (lost) return false
            if (expectedText.isEmpty()) return true
            if (actualBeforeCursor == null) return false
            val actual = actualBeforeCursor.toString()
            actual.length >= expectedText.length && actual.regionMatches(
                actual.length - expectedText.length,
                expectedText,
                0,
                expectedText.length,
            )
        }
}
