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
 * wird der Besitz für diese Sitzung entzogen — [isOwned] liefert danach false;
 * [reset] (neue Diktat-Sitzung) stellt ihn wieder her, und A2b
 * (PLAN_V0133 §A2b) öffnet [rebaseline] die Sperre wieder, nachdem ein
 * POSITIVER Suffixbeweis die eigene Spanne erneut belegt hat.
 *
 * Frameworkfrei: die absoluten Selektionspositionen stammen vom Aufrufer
 * (InputMethodService); ausgewertet werden Muster (Range-Selektion,
 * Callback-Reihenfolge), nicht absolute Offsets — die Cursor-Endpositionsprüfung
 * übernimmt die Android-Schicht (InputConnectionSink, Teil 3).
 */
class OwnershipTracker(
    /** AP7-E1b: injizierte Uhr (VClock-Muster, JVM-testbar); Default Wanduhr. */
    private val nowMs: () -> Long = { System.currentTimeMillis() },
) {

    var generation: Long = 0L
        private set

    private val lock = Any()
    private var expectedText: String = ""
    private var ownEditPending = false
    private var baselineTaken = false
    private var lost = false

    /** AP7-E1b: Zeitpunkt des letzten eigenen Edits (0 = noch keins) —
     *  Basis des Frische-Fensters gegen restartInput MITTEN in unserer
     *  eigenen delete/commit-Sequenz. */
    @Volatile private var lastOwnEditMs: Long = 0L

    /** true, sobald der Besitz verloren/durch Fremdeingabe abgegeben wurde. */
    val ownershipLost: Boolean
        get() = synchronized(lock) { lost }

    /** AP7-E1: letzter eigener Soll-Text (onOwnEdit) — nur lesbar, nie Inhalt in Logs (Regel 9). */
    val expectedOwnText: String
        get() = synchronized(lock) { expectedText }

    /** AP7-E1: Anzahl ausgeführter Re-Baselines nach restartInput (🐞 rebases). */
    var rebases: Int = 0
        private set

    /** Neue Diktat-Sitzung im selben Editor: Erwartungen zurücksetzen, Besitz zurück. */
    fun reset() = synchronized(lock) {
        expectedText = ""
        ownEditPending = false
        baselineTaken = false
        lost = false
    }

    /**
     * AP7-E1d: Selektions-Baseline EINMAL neu öffnen, ohne die Erwartungen
     * anzufassen — für den Retry-Fenster eines null-Lesefensters: der
     * Re-Bind-Callback der App muss die Baseline STILL nehmen (sonst setzt er
     * lost und der Retry rettet die Session in den stillen Schreibstopp
     * (H-E2-Familie)).
     */
    fun reopenSelectionBaseline() = synchronized(lock) {
        baselineTaken = false
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
        lastOwnEditMs = nowMs() // AP7-E1b: Frische-Fenster starten je Write
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
     * AP7-E1 (Fable §0.2.5 P1, vc33-T1-Beweis): Re-Baseline nach
     * `InputMethodManager.restartInput()` — viele Ziel-Apps (Chrome/WebView,
     * Compose < 1.6, Telegram, Rich-Text-Notizen) rufen restartInput auf JEDE
     * Textänderung, d. h. auf unseren ERSTEN Partial-Schrieb; vorher tötete
     * das ungeachtete restarting=true den Diktatlauf über onEditorChanged →
     * abort("editor_change") (Nutzer-Todesursache: ~2 s, erstes Fragment).
     *
     * Neuertrag: Besitz bleibt, wenn der eigene committete Text
     * ([expectedOwnText]) noch SUFFIX des tatsächlichen Texts vor dem Cursor
     * ist (gleiche Prüfung wie [isOwned] — Nutzertext VOR unserem Bereich ist
     * erlaubt). Dann wird die Selektions-Baseline NEU geöffnet
     * (baselineTaken=false): die Callbacks des App-Re-Binds nehmen die
     * Baseline still, statt `lost=true` zu setzen; ein offener
     * ownEditPending wird verbraucht (der Commit-Callback der alten
     * Verbindung kommt nie).
     *
     * Rückgabe false ⇒ App hat unseren Text gelöscht/ersetzt ⇒ Aufrufer bricht
     * kontrolliert ab (kein Geisterschreiben in einen veränderten Editor).
     *
     * A2b (PLAN_V0133 §A2b): `lost` wird ausschließlich nach POSITIVEM
     * Suffixbeweis gelöscht — NIE bei `actual==null`, Fremdtext oder bloß
     * frischem eigenen Edit (der ownEditFresh-Sonderfall allein gilt NICHT
     * als Beweis); der RE-SYNC des frischen Edits bleibt zugesagt (true),
     * ohne die Sperre zu öffnen.
     *
     * AP7-E1c (vc33-Abnahme, P0-Blocker `editor_rebaseline_lost` bei 8,5 s in
     * restart:20): `restartInput` feuert in der afterTextChanged UNSERES
     * commits — der Service sieht hier eine Tracker-Erwartung, die UM EINEN
     * Schreibzyklus veraltet ist (onOwnEdit läuft worker-seitig ERST nach dem
     * Sink-Roundtrip), während der Editor längst den längeren neuen Stand hat.
     * Der Suffix-Test gegen die veraltete Erwartung fälscht dann VERLUST.
     * [fresherExpected] (Sink-eigener Text, synchron zur commit-Runde) hebt die
     * Erwartung an, wenn sie Präfix-Verhältnis zum Tracker-Stand steht — in
     * beide Richtungen (Anhängen wie W3c-Rollback). Kein Präfix-Verhältnis ⇒
     * misstrauisch: Tracker-Stand bleibt maßgeblich (kein Fremder-Ersetzen).
     */
    fun rebaseline(actualBeforeCursor: CharSequence?, fresherExpected: CharSequence? = null): Boolean = synchronized(lock) {
        rebases += 1
        // AP7-E1c: Erwartung auf den frischeren Präfix-Verwandten anheben.
        if (fresherExpected != null && fresherExpected.isNotEmpty()) {
            val fe = fresherExpected.toString()
            val cur = expectedText
            if ((fe.length > cur.length && fe.startsWith(cur)) ||
                (cur.length > fe.length && cur.startsWith(fe))
            ) {
                expectedText = fe
            }
        }
        // AP7-E1b (vc33-Endbau-Abnahme, restart:20-Zelle): restartInput feuert
        // MITTEN in unserer eigenen delete/commit-Sequenz — der App-Watcher
        // zählt UNSEREN Write, der Ist-Text ist HALB (delete done, commit
        // offen). Der Suffix-Test fällte darüber eine FALSHE Verlust-
        // Entscheidung (5. Restart ⇒ editor_rebaseline_lost bei laufender
        // Session). Frische eigene Edit-Sequenz ⇒ RE-SYNC statt Verlust:
        // Ist-Text ist der NEUE Anker, lost wird NICHT gesetzt; unsere
        // nachziehende commit-Sequenz vollendet den Text, Weiterschreiben
        // läuft ab dem re-ankerten Stand.
        // A2b (PLAN_V0133 §A2b): der POSITIVE Suffixbeweis — EINE Prüfung,
        // beiden Zweigen gemeinsam; nur er öffnet die Sperre wieder. Ohne ihn:
        // RE-SYNC des frischen Edits (unten) oder Ablehnung — nie ein
        // still-lost nach bewiesenem Besitz (H-E2: Schreibstopp-Familie).
        val suffixProven = actualBeforeCursor != null && expectedText.isNotEmpty() &&
            actualBeforeCursor.length >= expectedText.length &&
            actualBeforeCursor.toString().endsWith(expectedText)
        if (suffixProven) {
            baselineTaken = false // nächster Callback = NEUE Baseline, kein lost
            ownEditPending = false // Commit-Callback der ALTEN Verbindung ist obsolet
            lost = false // A2b: Beweis erbracht — Sperre aufheben
            return true
        }
        // AP7-E1b (vc33-Endbau-Abnahme, restart:20-Zelle): restartInput feuert
        // MITTEN in unserer eigenen delete/commit-Sequenz — der App-Watcher
        // zählt UNSEREN Write, der Ist-Text ist HALB (delete done, commit
        // offen). Frische eigene Edit-Sequenz ⇒ RE-SYNC statt Verlust:
        // Ist-Text ist der NEUE Anker. A2b: KEIN lost-Löschen ohne Beweis.
        val ownEditFresh = ownEditPending ||
            (lastOwnEditMs > 0 && nowMs() - lastOwnEditMs <= OWN_EDIT_FRESH_MS)
        if (ownEditFresh) {
            if (!actualBeforeCursor.isNullOrEmpty()) {
                expectedText = actualBeforeCursor.toString() // Ist-Text = Anker
            }
            baselineTaken = false // nächster Callback = NEUE Baseline, kein lost
            ownEditPending = false // Sequenz-Antrag verbraucht (kommt nie)
            return true
        }
        val stillOurs = expectedText.isEmpty()
        if (stillOurs) {
            baselineTaken = false // nächster Callback = NEUE Baseline, kein lost
            ownEditPending = false
        }
        stillOurs
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

    private companion object {
        /** AP7-E1b: Frische-Fenster eigener Edits — innerhalb dessen gilt ein
         *  restartInput als MITTEN in unserer Sequenz (RE-SYNC, kein Verlust). */
        const val OWN_EDIT_FRESH_MS = 400L
    }
}
