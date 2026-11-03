package de.voicekeyboard.ime

import de.voicekeyboard.asr.AsrEngine
import de.voicekeyboard.asr.Hypothesis
import de.voicekeyboard.audio.AudioSource
import de.voicekeyboard.audio.PcmBlock
import de.voicekeyboard.audio.PcmWindow
import de.voicekeyboard.editor.EditorSink
import de.voicekeyboard.editor.OwnershipTracker
import kotlinx.coroutines.cancel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * AP7-E1 (docs/PLAN_C2_FABLE_R2.md §0.2.5 P1, vc33-T1-Emulatorbeweis):
 * `restartInput()` der Ziel-App auf unseren ERSTEN Partial-Schrieb tötete die
 * Session ungeachtet restarting=true (onStartInput → onEditorChanged →
 * abort("editor_change")) — die bestätigte Nutzer-Todesursache (~2 s, erstes
 * grammatikalisch geschlossenes Fragment). Vertrag nach dem Fix:
 *  (a) restartInput während RECORDING ⇒ Session lebt, Hypothese schreibt
 *      danach WEITER, kein Abort;
 *  (b) echter Editorwechsel (anderes Feld/Paket) ⇒ Abort wie bisher;
 *  (c) restart + von App gelöschter Text ⇒ kontrollierter Abort (kein
 *      Geisterschreiben in einen veränderten Editor);
 *  (d) Zähler restart_inputs/rebases für den 🐞-Export.
 *
 * Regel 9: Fixture-Texte sind Testkonstanten, nie Nutzerdaten.
 */
class EditorRestartSurvivalTest {

    /** Direkte Quelle: laute Blöcke, WANDEUHR-PACED (≈12× Echtzeit: 10 ms je
     *  Read) — INTEG-1-Wurzelbefund: ohne Drossel lief die Quelle µs-schnell,
     *  jeder Flush dekodierte den wachsenden Whole-Buffer (2× copyOf) ⇒
     *  Heap-Churn ⇒ Test-JVM-Tod mid-Run (7 leere Suite-XMLs) und Timing-
     *  Flakes in LAST-abhängigen Fremd-Suiten (Lauf-2: SegmentGolden/
     *  HoldGate rot bei grüner Substanz).
     *  [maxBlocks]: danach 0-Blöcke (ScriptSource-Muster) — der Puffer friert
     *  ein; nötig für Wanduhr-Pausen im Test (E1b-Fenster > 400 ms). */
    private class FakeSource(private val maxBlocks: Int = Int.MAX_VALUE) : AudioSource {
        var reads = 0
        override fun start(sessionId: Long): Boolean = true
        override fun read(dst: ShortArray, timeoutMs: Int): Int {
            if (reads >= maxBlocks) {
                Thread.sleep(1) // kein Block, keine Allokation (ERR_STALL: 10 s fern)
                return 0
            }
            Thread.sleep(10) // Pacing: Heap-Churn der Whole-Buffer-Decodes begrenzen
            reads++
            val n = minOf(4_000, dst.size)
            java.util.Arrays.fill(dst, 0, n, 12_000.toShort()) // laut
            return n
        }
        override fun stopAndDrain(): List<PcmBlock> = emptyList()
        override fun release() {}
    }

    private class FakeEngine : AsrEngine {
        var nextText = "hallo"
        var calls = 0
            private set
        override fun load(modelDir: File) {}
        override suspend fun transcribe(window: PcmWindow, sessionId: Long, runId: Long): Hypothesis {
            calls++
            return Hypothesis(
                IntArray(0), nextText, IntArray(0), sessionId, runId,
                window.samples.size, incomplete = false,
            )
        }
        override fun close() {}
    }

    /** Echter Schreib-Sink (Besitz-Kette real, nie Inhalte geloggt). */
    private class RecordingSink : EditorSink {
        var text: String = ""
        override fun replaceBeforeCursor(replaceLength: Int, newText: String) {
            text = text.dropLast(replaceLength) + newText
        }
        override fun textBeforeCursor(maxChars: Int): CharSequence? =
            text.takeLast(minOf(maxChars, text.length))
        override fun ownLength(): Int = text.length
        // AP7-E1c: Fixture-Text ist komplett eigener Span (wie ownSpan im
        // Produktions-Sink nach unseren Writes).
        override fun ownText(): String = text
    }

    private class Ctx(
        val coordinator: DictationCoordinator,
        val engine: FakeEngine,
        val sink: RecordingSink,
        val scope: kotlinx.coroutines.CoroutineScope,
    )

    /** [maxBlocks]: Quelle friert danach ein (0-Blöcke) — nur für Tests mit
     *  Wanduhr-Pause ((c): E1b-Fenster > 400 ms), sonst wächst der Whole-
     *  Buffer bei µs-schneller Quelle bis zum Heap-OOOM. (a)/(e) brauchen die
     *  WEITERlaufende Quelle für die Folge-Hypothese ⇒ Default unbegrenzt. */
    private fun startSession(maxBlocks: Int = Int.MAX_VALUE): Ctx {
        val engine = FakeEngine()
        val sink = RecordingSink()
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default)
        val coordinator = DictationCoordinator(
            scope, { FakeSource(maxBlocks) }, { engine }, sink,
            tracker = OwnershipTracker(),
            tickSamples = 4_000, // jeder Read ⇒ sofortiger Decode-Tick
            autoStopSuppressed = { true }, // Hold: kein F43-Rauschen
        )
        assertTrue(coordinator.start())
        // Erster Partial-Schrieb ist deterministisch da (2 s min + 4 s Block):
        val deadline = System.currentTimeMillis() + 10_000
        while (sink.text.isEmpty() && System.currentTimeMillis() < deadline) {
            Thread.sleep(5)
        }
        assertTrue("erster Partial geschrieben (Ist='${sink.text.length} Zeichen')", sink.text.isNotEmpty())
        return Ctx(coordinator, engine, sink, scope)
    }

    // (a) restartInput während RECORDING: Re-Baseline, Session lebt, die
    //     NÄCHSTE Hypothese schreibt weiter — der Kernvertrag des Fixes.
    @Test
    fun `a) restartInput waehrend RECORDING - Session lebt und schreibt weiter`() {
        val ctx = startSession()
        try {
            val c = ctx.coordinator
            val before = ctx.sink.text
            // Service-Aktion bei restarting=true, gleiches Feld (AP7-E1):
            assertTrue("Re-Baseline haelt Besitz (Text unangetastet)", c.rebaselineOwnership())
            assertEquals("rebases gezaehlt", 1, c.rebases())
            // Der Re-Bind-Callback der App: offene Baseline nimmt ihn STILL
            // (kein lost). WEITERE unerwartete Callbacks bleiben H-E2/P2 und
            // damit AP7-E2 (Suffix-Resync) — hier bewusst EIN Callback.
            c.onUpdateSelection(0, 0, 0, 0)
            // Nächste Hypothese (App hat restartInput, wir schreiben weiter):
            ctx.engine.nextText = before + " welt"
            val deadline = System.currentTimeMillis() + 10_000
            while (ctx.sink.text == before && System.currentTimeMillis() < deadline) {
                Thread.sleep(5)
            }
            assertEquals("Hypothese nach Restart WEITERgeschrieben", before + " welt", ctx.sink.text)
            // DER REPRO-KERN: kein Abort, Session RECORDING bis zum Stop.
            assertTrue("kein Abort — Session lebt", c.isActive)
            assertEquals("kein Abort-Grund", "-", c.lastAbortReason)
            c.stop()
        } finally {
            // KEIN Leak: FakeSource endet nie und scope.cancel() stoppt den
            // Capture-Thread NICHT — ohne shutdown() pumpte der weiter in den
            // totenglaubten Kanal (unbeschaenkt) und riss den Test-JVM-Heap
            // (Lauf-1: OOM, FakeAudioScenarioTest nicht ausfuehrbar).
            ctx.coordinator.shutdown()
            ctx.scope.cancel()
        }
    }

    // (b) echter Editorwechsel ⇒ Abort wie bisher — die reine Entscheidung.
    @Test
    fun `b) echter Paket-Feld-Wechsel bleibt Abort - reine Entscheidung`() {
        // restarting=true + IDENTISCHES Feld ⇒ REBASELINE (restartInput der App):
        assertEquals(
            EditorRestartAction.REBASELINE,
            editorRestartAction(
                restarting = true, sessionActive = true,
                baselinePkg = "com.app", baselineFieldId = 42,
                infoPkg = "com.app", infoFieldId = 42,
            ),
        )
        // restarting=true + ANDERES Feld ⇒ ABORT (echter Editorwechsel):
        assertEquals(
            EditorRestartAction.ABORT,
            editorRestartAction(
                restarting = true, sessionActive = true,
                baselinePkg = "com.app", baselineFieldId = 42,
                infoPkg = "com.app", infoFieldId = 77,
            ),
        )
        // restarting=true + ANDERES Paket ⇒ ABORT:
        assertEquals(
            EditorRestartAction.ABORT,
            editorRestartAction(
                restarting = true, sessionActive = true,
                baselinePkg = "com.app", baselineFieldId = 42,
                infoPkg = "com.other", infoFieldId = 42,
            ),
        )
        // restarting=false (echtes Neubinden) ⇒ ABORT — konservativ:
        assertEquals(
            EditorRestartAction.ABORT,
            editorRestartAction(
                restarting = false, sessionActive = true,
                baselinePkg = "com.app", baselineFieldId = 42,
                infoPkg = "com.app", infoFieldId = 42,
            ),
        )
        // keine aktive Session ⇒ NOTHING (kosmetischer Pfad, kein Abort nötig):
        assertEquals(
            EditorRestartAction.NOTHING,
            editorRestartAction(
                restarting = true, sessionActive = false,
                baselinePkg = "com.app", baselineFieldId = 42,
                infoPkg = "com.app", infoFieldId = 42,
            ),
        )
    }

    // AP7-E1-P2 (Review): fieldId-Sentinels — BEIDE Seiten ≤ 0
    // („unbekannt"; Android liefert je Editor 0/-1) ⇒ NUR der Paket-Match
    // entscheidet: gleiche App ⇒ REBASELINE (sonst wäre -1 vs 0 ein
    // FALSCHER Abort in echten Apps).
    @Test
    fun `P2 beide fieldId Sentinels - Paket entscheidet REBASELINE`() {
        assertEquals(
            "beide ≤ 0, gleiches Paket ⇒ REBASELINE (kein -1-vs-0-Falschabort)",
            EditorRestartAction.REBASELINE,
            editorRestartAction(
                restarting = true, sessionActive = true,
                baselinePkg = "com.app", baselineFieldId = 0,
                infoPkg = "com.app", infoFieldId = -1,
            ),
        )
    }

    // AP7-E1-P2: EIN Sentinel blockiert nicht (unklare Seite ⇒ Paket-Match
    // genügt); ein ANDERES Paket bleibt ABORT — auch mit Sentinel-Feldern.
    @Test
    fun `P2 ein Sentinel blockiert nicht - anderes Paket bleibt Abort`() {
        assertEquals(
            "eine Seite ≤ 0, andere > 0, gleiches Paket ⇒ REBASELINE",
            EditorRestartAction.REBASELINE,
            editorRestartAction(
                restarting = true, sessionActive = true,
                baselinePkg = "com.app", baselineFieldId = 0,
                infoPkg = "com.app", infoFieldId = 42,
            ),
        )
        assertEquals(
            "Paket anders ⇒ ABORT — auch mit Sentinel-Feldern",
            EditorRestartAction.ABORT,
            editorRestartAction(
                restarting = true, sessionActive = true,
                baselinePkg = "com.app", baselineFieldId = 0,
                infoPkg = "com.other", infoFieldId = -1,
            ),
        )
    }

    // (c) restart + von App gelöschter Text ⇒ kontrollierter Abort, kein
    //     Geisterschreiben (Re-Baseline erkennt den Verlust am Suffix).
    @Test
    fun `c) Restart mit geloeschtem Text - kontrollierter Abort kein Geisterschreiben`() {
        val ctx = startSession(maxBlocks = 64) // Quelle friert fuer die 450-ms-Pause ein
        try {
            val c = ctx.coordinator
            val before = ctx.sink.text
            // AP7-E1b: KEIN frischer eigener Edit — Sequenz-Antrag verbrauchen
            // (App-Callback auf unseren Write) + Frische-Fenster altern lassen:
            c.onUpdateSelection(0, 0, 0, 0)
            Thread.sleep(450) // > OWN_EDIT_FRESH_MS (400) — testseitig okay
            // Die App hat im restartInput unseren Text GELÖSCHT:
            ctx.sink.text = ""
            assertFalse("Re-Baseline verweigert (unser Text ist weg, kein frischer Edit)", c.rebaselineOwnership())
            // Service-Pfad: kontrollierter Abort, kein weiteres Schreiben:
            c.abort("editor_rebaseline_lost")
            assertFalse("Session kontrolliert beendet", c.isActive)
            assertEquals("Abort-Grund fein (stop_cause-Familie)", "editor_rebaseline_lost", c.lastAbortReason)
            assertEquals("kein Geisterschreiben — Editor bleibt leer", "", ctx.sink.text)
            assertTrue("vorheriger Text war da (Beleg-Plausibilität)", before.isNotEmpty())
        } finally {
            // KEIN Leak: FakeSource endet nie und scope.cancel() stoppt den
            // Capture-Thread NICHT — ohne shutdown() pumpte der weiter in den
            // totenglaubten Kanal (unbeschaenkt) und riss den Test-JVM-Heap
            // (Lauf-1: OOM, FakeAudioScenarioTest nicht ausfuehrbar).
            ctx.coordinator.shutdown()
            ctx.scope.cancel()
        }
    }

    // (e) AP7-E1b (vc33-Endbau, restart:20-Zelle): restartInput feuert MITTEN
    //     in unserer eigenen delete/commit-Sequenz (App-Watcher zählt UNSEREN
    //     Write; Ist-Text halb: delete done, commit offen). KEIN Fehl-Abort —
    //     RE-SYNC; unsere commit-Sequenz vollendet, die NÄCHSTE Hypothese
    //     schreibt korrekt weiter. Der 5.-Restart-Killer ist damit zu.
    @Test
    fun `e) Restart mitten in eigener Edit-Sequenz - RE-SYNC Session lebt und schreibt weiter`() {
        val ctx = startSession()
        try {
            val c = ctx.coordinator
            // Mid-Sequenz-Ist: delete done („hallo" → „hal"), commit offen —
            // ownEditPending der Session steht (kein App-Callback kam).
            assertTrue("Sequenz-Antrag steht (Mid-Edit-Marke)", ctx.engine.calls >= 1)
            ctx.sink.text = "hal"
            assertTrue("E1b: frischer eigener Edit ⇒ RE-SYNC statt Verlust", c.rebaselineOwnership())
            assertTrue("Session lebt (kein Fehl-Abort)", c.isActive)
            assertEquals("kein Abort-Grund", "-", c.lastAbortReason)
            // Unsere commit-Sequenz vollendet den Text (nachziehender Write):
            ctx.sink.text = ctx.sink.text + "lo"
            val before = ctx.sink.text
            // Nächste Hypothese schreibt ab dem re-ankerten Stand weiter:
            ctx.engine.nextText = before + " welt"
            val deadline = System.currentTimeMillis() + 10_000
            while (ctx.sink.text == before && System.currentTimeMillis() < deadline) {
                Thread.sleep(5)
            }
            assertEquals("Hypothese nach RE-SYNC korrekt weitergeschrieben", before + " welt", ctx.sink.text)
            assertTrue("Session lebt bis zum Stop", c.isActive)
            c.stop()
        } finally {
            ctx.coordinator.shutdown()
            ctx.scope.cancel()
        }
    }

    // (f) AP7-E1b-Grenze: Fremdverlust OHNE frischen eigenen Edit ⇒ Abort
    //     bleibt (Tracker-Ebene, injizierte Uhr — 400-ms-Fenster exakt).
    @Test
    fun `f) Fremdverlust ohne frischen eigenen Edit - Abort bleibt`() {
        val clock = java.util.concurrent.atomic.AtomicLong(1_000_000L)
        val t = de.voicekeyboard.editor.OwnershipTracker(nowMs = { clock.get() })
        t.onUpdateSelection(0, 0, 0, 0) // System-Baseline
        t.onOwnEdit("hallo")            // unser Write — Fenster läuft
        t.onUpdateSelection(0, 0, 0, 0) // AP7-E1b: Sequenz-Antrag VERBRAUCHEN
        clock.addAndGet(500)            // > 400 ms: NICHT mehr frisch
        assertFalse("Suffix weg + kein frischer Edit ⇒ Verlust bleibt", t.rebaseline(""))
        // Zum Gegenbeweis: INNERHALB des Fensters ⇒ RE-SYNC (E1b):
        val t2 = de.voicekeyboard.editor.OwnershipTracker(nowMs = { clock.get() })
        t2.onUpdateSelection(0, 0, 0, 0)
        t2.onOwnEdit("hallo")
        t2.onUpdateSelection(0, 0, 0, 0) // Sequenz-Antrag verbraucht
        clock.addAndGet(100)            // ≤ 400 ms: noch frisch
        assertTrue("frisch (100 ms) ⇒ RE-SYNC trotz halbem Ist-Text", t2.rebaseline("hal"))
        assertFalse("kein lost nach RE-SYNC", t2.ownershipLost)
        // ownEditPending (ohne Verbrauch) reicht ebenfalls — Mid-Sequenz:
        val t3 = de.voicekeyboard.editor.OwnershipTracker(nowMs = { clock.get() })
        t3.onUpdateSelection(0, 0, 0, 0)
        t3.onOwnEdit("hallo")
        assertTrue("ownEditPending ⇒ RE-SYNC", t3.rebaseline("hal"))
        assertFalse(t3.ownershipLost)
    }

    // (g) AP7-E1c (vc33-Abnahme P0-Blocker): restartInput feuert INNERHALB
    //     unseres commits — der Sink (ownSpan, Edit-Thread) ist schon auf dem
    //     neuen Stand, der Tracker (worker-seitig, onOwnEdit NACH dem
    //     Roundtrip) hinkt um genau diesen Write nach. Ohne E1c: Suffix-Test
    //     gegen veraltete Erwartung ⇒ Falsch-Abort `editor_rebaseline_lost`
    //     (live beobachtet: 5. Restart, t=8,5 s, restart:20).
    @Test
    fun `g) Restart im commit-Fenster bei veraltetem Tracker - E1c Re-Sync ueber Sink-ownText`() {
        // Harness-Sicherung (gleiche Disziplin wie (c)): die 450-ms-Wanduhr-
        // Pause braucht eine EINGEFRORENE Quelle, sonst Heap-OOOM.
        val ctx = startSession(maxBlocks = 64)
        try {
            val c = ctx.coordinator
            val written = ctx.sink.text
            // E1b-Frische deliberately ausschalten (wie (c)/(f)) — nur E1c
            // kann hier noch retten: Sequenz-Antrag verbrauchen,
            // Frische-Fenster altern lassen.
            c.onUpdateSelection(0, 0, 0, 0)
            // NÄCHSTER commit liegt bereits im Sink (ownSpan aktualisiert),
            // worker-seitiges onOwnEdit ist NICHT gelaufen (Tracker alt):
            ctx.sink.text = written + " welt"
            Thread.sleep(450) // > OWN_EDIT_FRESH_MS — kein E1b-Notnagel
            assertTrue(
                "E1c: Sink-Stand (Präfix-Verwandter, länger) hebt die Erwartung an — kein Falsch-Abort",
                c.rebaselineOwnership(),
            )
            assertTrue("Session lebt", c.isActive)
            assertEquals("kein Abort-Grund", "-", c.lastAbortReason)
            // Gegenbeweis Tracker-Ebene (ohne Sink-Stand): gleiche Lage war der
            // Killer — Präfix-Verwandtschaft BOTH-Richtungen (W3c-Rollback):
            val clock = java.util.concurrent.atomic.AtomicLong(1_000_000L)
            val t = de.voicekeyboard.editor.OwnershipTracker(nowMs = { clock.get() })
            t.onUpdateSelection(0, 0, 0, 0)
            t.onOwnEdit("hallo")
            t.onUpdateSelection(0, 0, 0, 0)
            clock.addAndGet(500)
            assertFalse("alt (E1c-los): verlängerter Ist-Text ⇒ Falsch-Verlust", t.rebaseline("hallo welt"))
            val t2 = de.voicekeyboard.editor.OwnershipTracker(nowMs = { clock.get() })
            t2.onUpdateSelection(0, 0, 0, 0)
            t2.onOwnEdit("hallo welt")
            t2.onUpdateSelection(0, 0, 0, 0)
            clock.addAndGet(500)
            assertTrue("Rollback-Richtung: kürzerer Sink-Stand, Präfix ⇒ RE-SYNC", t2.rebaseline("hallo", "hallo"))
            c.stop()
        } finally {
            ctx.coordinator.shutdown()
            ctx.scope.cancel()
        }
    }

    // (d) Zähler: rebases je Re-Baseline, monoton; restart_inputs-Zähler des
    //     Coordinators bleibt unberührt (NoteRestartInput, AP3).
    @Test
    fun `d) rebases-Zaehler monoton - restart_inputs unberuehrt`() {
        val ctx = startSession()
        try {
            val c = ctx.coordinator
            assertEquals(0, c.rebases())
            assertEquals(0, c.restartInputs())
            assertTrue(c.rebaselineOwnership())
            assertTrue(c.rebaselineOwnership())
            assertEquals("zwei Re-Baselines gezaehlt", 2, c.rebases())
            assertEquals("restart_inputs nur ueber onStartInput (AP3)", 0, c.restartInputs())
            c.noteRestartInput()
            assertEquals(1, c.restartInputs())
        } finally {
            // KEIN Leak: FakeSource endet nie und scope.cancel() stoppt den
            // Capture-Thread NICHT — ohne shutdown() pumpte der weiter in den
            // totenglaubten Kanal (unbeschaenkt) und riss den Test-JVM-Heap
            // (Lauf-1: OOM, FakeAudioScenarioTest nicht ausfuehrbar).
            ctx.coordinator.shutdown()
            ctx.scope.cancel()
        }
    }

    // (h) AP7-E1d: Lesefenster ins Re-Bind (act=-1) ist KEIN Verlustbeweis —
    //     reine Retry-Entscheidung + Tracker-Sequenz: Baseline sofort offen,
    //     Re-Bind-Callback STILL, Retry mit lebender Verbindung hält Besitz.
    @Test
    fun `h) Null-Lesefenster - Retry-Entscheidung rein und Baseline bleibt rein`() {
        // reine Entscheidung: nur (nicht-kept AND act=-1 AND aktiv) retryt:
        assertTrue(rebaseRetryDue(kept = false, actLen = -1, sessionActive = true))
        assertFalse("echter Verlust (act gelesen) bricht sofort ab", rebaseRetryDue(false, 75, true))
        assertFalse("gehaltene Baseline braucht keinen Retry", rebaseRetryDue(true, -1, true))
        assertFalse("inaktive Session retryt nicht", rebaseRetryDue(false, -1, false))
        // Tracker-Sequenz: null-Read ⇒ ablehnbar, aber Baseline reopen ⇒
        // Re-Bind-Callback ohne lost ⇒ Retry-Read hält Besitz:
        val t = de.voicekeyboard.editor.OwnershipTracker(nowMs = { 0L }) // alt: nie frisch
        t.onUpdateSelection(0, 0, 0, 0) // System-Baseline
        t.onOwnEdit("hallo")
        t.onUpdateSelection(0, 0, 0, 0) // eigener Callback verbraucht
        assertFalse("null-Read: weiterhin ablehnbar", t.rebaseline(null))
        t.reopenSelectionBaseline()
        t.onUpdateSelection(0, 0, 0, 0) // Re-Bind-Callback: STILL verbraucht
        assertFalse("kein lost durch Re-Bind im Retry-Fenster", t.ownershipLost)
        assertTrue("Retry mit lebender Verbindung haelt Besitz", t.rebaseline("hallo"))
        assertFalse(t.ownershipLost)
    }
}
