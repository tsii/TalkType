package de.voicekeyboard.editor

/**
 * Frameworkfreier Zugang zum Editor des Diktats (DESIGN.md §6, IMPLEMENTATION_PLAN.md B.2).
 *
 * Bewusst ohne Android-Typen (kein `InputConnection`), damit Commit-/Delta-Logik
 * rein auf der JVM testbar ist. Die Android-Implementierung `InputConnectionSink`
 * (Stufe 4, Teil 2b) übersetzt diese Operationen in `deleteSurroundingText` /
 * `commitText`.
 *
 * Vertrag:
 * - [replaceBeforeCursor] wirkt nur auf den **eigenen** Bereich vor dem Cursor.
 *   Der Aufruf gilt als atomar: bei einer Exception ist er nicht ausgeführt worden.
 * - Besitz ist nicht bewiesen, wenn Kontext/Länge nicht mit den Erwartungen
 *   übereinstimmen (`null` / `-1`).
 */
interface EditorSink {

    /**
     * Ersetzt die letzten [replaceLength] Zeichen vor dem Cursor durch [newText].
     * Cursor steht danach hinter dem neuen Text. Wirft bei Fehler (Fehler nach oben,
     * KONZEPT.md §2.7); interner Zustand des Aufrufers bleibt konsistent.
     */
    fun replaceBeforeCursor(replaceLength: Int, newText: String)

    /**
     * Bis zu [maxChars] Zeichen Text vor dem Cursor (Kontextabgleich vor jeder
     * Revision, DESIGN.md §6) oder `null`, wenn der Editor ihn nicht liefert
     * (Besitz nicht bewiesen).
     */
    fun textBeforeCursor(maxChars: Int): CharSequence?

    /**
     * Länge des eigenen, eingefügten Bereichs in Zeichen — oder `-1`, wenn
     * unbekannt. Dient der Besitzprüfung (erwartete Länge vs. Ist-Länge).
     */
    fun ownLength(): Int

    /**
     * AP7-E1c (vc33-Abnahme, P0-Blocker): der eigene, eingefügte Text —
     * synchron zum Schreibvorgang aktuell (der Sink führt Edits auf dem
     * Editor-Thread aus; ein `restartInput` der App feuert INNERHALB unserer
     * commitText-Runde, bevor der Worker seine Tracker-Erwartung aktualisieren
     * kann — diese Quelle ist dann dem Worker-Blick voraus).
     * Default leer: Implementierungen ohne eigene Textführung degradiert auf
     * die Tracker-Erwartung.
     */
    fun ownText(): String = ""

    /**
     * A5 (v0.1.34): Sitzungswechsel — die eigene Spanne der VORHERIGEN
     * Diktation-Sitzung ist im neuen Sitzungskontakt nicht mehr bewiesen
     * (Besitz vererbt sich NICHT über die Sitzungsgrenze; die Sink-Instanz
     * selbst wird wiederverwendet). Implementierungen mit eigener Textführung
     * verwerfen sie hier. Default-No-op: frameworkfreie Fakes/Tests bleiben
     * unverändert.
     * Aufrufer: `DictationController.start()` (dieselbe Stelle wie der
     * Typer-/Tracker-Reset); NICHT bei apply-Fehlpfaden — ein fehlgeschlagener
     * Edit gilt als nicht ausgeführt, die Spanne bleibt dann unverändert.
     */
    fun resetSession() {}
}
