package de.voicekeyboard.ime

import android.view.inputmethod.EditorInfo
import de.voicekeyboard.correct.RegionReplacer
import de.voicekeyboard.correct.TypedRegionTracker.ResetReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * T23g (PLAN_V0147 §7/§8): service seam of the correction row — feeds from
 * the key/commit paths into TypedRegionTracker/RegionReplacer, debounce +
 * generation, wand dispatch. All seams are injected fakes (JVM-pure); the
 * Robolectric runner is needed for the log-capture assert (rule 9: never
 * typed text in logs) and the service-wiring test (pattern:
 * ModelSwitchServiceWiringTest). Fixture words are test constants (rule 9).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CorrectionWiringTest {

    /** Records schedule calls; delay<=0 runs inline (production: worker). */
    private class FakeScheduler {
        val pending = ArrayList<Pair<Long, () -> Unit>>()
        var cancels = 0
        var clearOnCancel = true

        fun schedule(delayMs: Long, gen: Long, run: () -> Unit) {
            if (delayMs <= 0) run() else pending.add(gen to run)
        }

        fun cancel() {
            cancels++
            if (clearOnCancel) pending.clear()
        }

        fun fire(gen: Long) = pending.firstOrNull { it.first == gen }?.second?.invoke()
    }

    /** Fake editor: suffix proof configured via [before]. */
    private class FakeEditor(var before: String = "") : RegionReplacer.Editor {
        val log = ArrayList<String>()
        var beforeCursorRequests = 0
        var hasSelectionFlag = false

        override fun beginBatchEdit() {
            log.add("begin")
        }

        override fun deleteSurroundingText(beforeLength: Int, afterLength: Int) {
            log.add("del:$beforeLength")
            before = before.dropLast(beforeLength) // behave like the real editor
        }

        override fun commitText(text: String, newCursorPosition: Int) {
            log.add("commit:${text.length}")
            before += text
        }

        override fun endBatchEdit() {
            log.add("end")
        }

        override fun getTextBeforeCursor(length: Int): String? {
            beforeCursorRequests++
            return before.takeLast(length)
        }

        override fun hasSelection(): Boolean = hasSelectionFlag
    }

    private class Harness {
        val scheduler = FakeScheduler()
        val editor = FakeEditor()
        var computeCalls = 0
        var lastRegion: String? = null
        val rowUpdates = ArrayList<Triple<String?, String?, Boolean>>()
        val undoModes = ArrayList<Boolean>()

        val wiring = CorrectionWiring(
            // VK-8 (EMU_DUTY_V0147_ADDENDUM): FAKE compute — the REAL asset path
            // (registry -> word-list bin) is covered end-to-end by the VK-8
            // tests at the bottom of this class.
            compute = { region ->
                computeCalls++
                lastRegion = region
                if (region == "morgem ") "morgen " else null
            },
            editor = { editor },
            dictationIdle = { true },
            schedule = scheduler::schedule,
            cancelScheduled = scheduler::cancel,
            postToMain = { it() }, // inline: deterministic publish
            onRowUpdate = { original, corrected, hasSuggestion, _ ->
                rowUpdates.add(Triple(original, corrected, hasSuggestion))
            },
            onUndoMode = { undoModes.add(it) },
        )
    }

    @Before
    fun resetCounters() {
        CorrCounters.reset()
    }

    // ---------------------------------------------------------------- debounce

    @Test
    fun `debounce - 2 keys within 300ms produce exactly ONE compute`() {
        val h = Harness()
        h.wiring.onFieldStart(0)

        h.wiring.feedText("mor")
        h.wiring.feedText("gem")
        // both non-separator: only pending 300-ms runs, NO immediate compute;
        // the second feed CANCELS the first pending run (production semantics)
        assertEquals("nothing computed before the debounce", 0, h.computeCalls)
        assertTrue("second feed cancels the first pending run", h.scheduler.cancels >= 1)
        assertEquals("exactly ONE pending debounce run", 1, h.scheduler.pending.size)

        h.scheduler.fire(h.scheduler.pending.last().first)
        assertEquals("exactly ONE compute for both keys", 1, h.computeCalls)
        assertEquals("region = both keys merged", "morgem", h.lastRegion)
    }

    @Test
    fun `generation counter discards the stale run`() {
        val h = Harness()
        h.wiring.onFieldStart(0)
        h.scheduler.clearOnCancel = false // keep BOTH runs pending like a lagging queue

        h.wiring.feedText("mor") // gen 1
        h.wiring.feedText("gem") // gen 2 (cancel ignored by the fake)
        h.scheduler.fire(1L)
        assertEquals("stale generation discarded", 0, h.computeCalls)
        h.scheduler.fire(h.scheduler.pending.last().first)
        assertEquals("newest generation computes", 1, h.computeCalls)
        assertEquals("region follows the LATEST feed", "morgem", h.lastRegion)
    }

    @Test
    fun `separator computes IMMEDIATELY without debounce`() {
        val h = Harness()
        h.wiring.onFieldStart(0)

        h.wiring.feedText("morgem ")
        assertEquals("separator -> synchronous compute", 1, h.computeCalls)
        assertTrue("no 300-ms pending left", h.scheduler.pending.isEmpty())
        assertEquals("region ends at the separator", "morgem ", h.lastRegion)
    }

    // ------------------------------------------------------------ reset feeds

    @Test
    fun `reset feeds - field start, selection, non-empty selection, editor change, dictation`() {
        val h = Harness()
        h.wiring.feedText("a") // unanchored: no-op (service seeds via onFieldStart)
        assertEquals("no buffer without anchor", "", h.wiring.tracker.buffer)

        h.wiring.onFieldStart(10)
        assertEquals(ResetReason.FIELD_START, h.wiring.tracker.lastResetReason)
        assertEquals(10, h.wiring.tracker.anchorAbs)

        h.editor.before = "hi " // suffix proof for the publish after the feed
        h.wiring.feedText("hi ")
        assertEquals(13, h.wiring.tracker.expectedCursor)

        h.wiring.onSelectionMove(99)
        assertEquals(ResetReason.SELECTION_MOVE, h.wiring.tracker.lastResetReason)
        assertEquals("buffer cleared on selection move", "", h.wiring.tracker.buffer)
        assertEquals(99, h.wiring.tracker.anchorAbs)

        h.wiring.onNonEmptySelection()
        assertEquals(ResetReason.NON_EMPTY_SELECTION, h.wiring.tracker.lastResetReason)

        h.wiring.onEditorChange()
        assertEquals(ResetReason.EDITOR_CHANGE, h.wiring.tracker.lastResetReason)

        h.wiring.onDictationCommit()
        assertEquals(ResetReason.DICTATION_COMMIT, h.wiring.tracker.lastResetReason)
        assertTrue("every reset cancels the pending debounce", h.scheduler.cancels >= 5)
    }

    // ------------------------------------------------------------- wand press

    @Test
    fun `wand APPLY - replacer replace, tracker reset WAND_PRESS, counter applied`() {
        val h = Harness()
        h.wiring.onFieldStart(0)
        h.editor.before = "morgem "
        h.wiring.feedText("morgem ") // separator -> immediate compute + publish
        assertEquals("row got the suggestion", Triple("morgem ", "morgen ", true), h.rowUpdates.last())
        assertEquals(1, CorrCounters.rowsShown)

        h.wiring.onWandPress(RegionReplacer.WandPress.APPLY)
        assertEquals("display proof + replace proof + post-batch re-verify", 3, h.editor.beforeCursorRequests)
        assertEquals(
            "batch = delete buffer + commit corrected",
            listOf("del:7", "commit:7"),
            h.editor.log.filter { it != "begin" && it != "end" },
        )
        assertEquals(ResetReason.WAND_PRESS, h.wiring.tracker.lastResetReason)
        assertEquals("anchor after corrected (UTF-16)", 7, h.wiring.tracker.anchorAbs)
        assertEquals(1, CorrCounters.applied)
        assertTrue("undo glyph after apply", h.undoModes.last())
    }

    @Test
    fun `wand APPLY blocked while dictation runs - no editor batch`() {
        val editor = FakeEditor(before = "morgem ")
        var calls = 0
        val wiring = CorrectionWiring(
            compute = { region -> calls++; if (region == "morgem ") "morgen " else null },
            editor = { editor },
            dictationIdle = { false },
            schedule = { d, _, r -> if (d <= 0) r() },
            cancelScheduled = {},
            postToMain = { it() },
            onRowUpdate = { _, _, _, _ -> },
            onUndoMode = {},
        )
        wiring.onFieldStart(0)
        wiring.feedText("morgem ")
        assertEquals(1, calls)
        wiring.onWandPress(RegionReplacer.WandPress.APPLY)
        assertTrue("dictation busy: NO editor batch", editor.log.isEmpty())
        assertEquals(0, CorrCounters.applied)
    }

    @Test
    fun `wand UNDO restores the original once`() {
        val h = Harness()
        h.wiring.onFieldStart(0)
        h.editor.before = "morgem "
        h.wiring.feedText("morgem ")
        h.wiring.onWandPress(RegionReplacer.WandPress.APPLY)
        h.editor.before = "morgen " // field content after the apply
        h.wiring.onWandPress(RegionReplacer.WandPress.UNDO)
        assertEquals(
            "undo batch = delete corrected + commit original",
            listOf("del:7", "commit:7", "del:7", "commit:7"),
            h.editor.log.filter { it != "begin" && it != "end" },
        )
        assertEquals(1, CorrCounters.undone)
        assertFalse("single-level undo: glyph off after undo", h.undoModes.last())
        h.wiring.onWandPress(RegionReplacer.WandPress.UNDO)
        assertEquals("no double undo", 1, CorrCounters.undone)
    }

    @Test
    fun `next keystroke invalidates the undo level`() {
        val h = Harness()
        h.wiring.onFieldStart(0)
        h.editor.before = "morgem "
        h.wiring.feedText("morgem ")
        h.wiring.onWandPress(RegionReplacer.WandPress.APPLY)
        h.editor.before = "morgen x"
        h.wiring.feedText("x") // next keystroke -> undo window ends
        assertFalse("undo glyph off after the next key", h.undoModes.last())
    }

    // ---------------------------------------------------------------- privacy

    /** Rule 9: never typed/corrected text in ANY log line of the flow. */
    @Test
    fun `logs never contain the typed or corrected text`() {
        val h = Harness()
        h.wiring.onFieldStart(0)
        h.editor.before = "morgem "
        h.wiring.feedText("morgem ")
        h.wiring.onWandPress(RegionReplacer.WandPress.APPLY)
        h.editor.before = "morgen "
        h.wiring.onWandPress(RegionReplacer.WandPress.UNDO)

        for (entry in org.robolectric.shadows.ShadowLog.getLogs()) {
            assertFalse("log leaks typed text: ${entry.tag}", entry.msg.contains("morgem"))
            assertFalse("log leaks corrected text: ${entry.tag}", entry.msg.contains("morgen"))
        }
    }

    // ------------------------------------------------------- service wiring

    /** The service wires the row: wand callback, pref supplier, mode pref. */
    @Test
    fun `service onCreateInputView wires wand press and pref suppliers`() {
        val service = Robolectric.buildService(VoiceKeyboardService::class.java).get()
        service.onCreate()
        assertNotNull("wiring exists after onCreate", service.corrWiring)

        val view = service.onCreateInputView() as ImeRootView
        assertNotNull("view.onWandPress wired", view.onWandPress)
        assertTrue("corr_row_enabled default ON", view.typingSuggestionsSupplier())

        service.getSharedPreferences(VoiceKeyboardService.LAYOUT_PREFS, android.content.Context.MODE_PRIVATE)
            .edit()
            .putBoolean(VoiceKeyboardService.PREF_CORR_ROW_ENABLED, false)
            .commit()
        assertFalse("supplier reads the pref live", view.typingSuggestionsSupplier())

        service.onDestroy()
    }

    /** onStartInput routes EDITOR_CHANGE into the tracker (safe lifecycle). */
    @Test
    fun `onStartInput feeds EDITOR_CHANGE into the wiring`() {
        val service = Robolectric.buildService(VoiceKeyboardService::class.java).get()
        service.onCreate()
        val info = EditorInfo().apply {
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_VARIATION_NORMAL
        }
        service.onStartInput(info, false)
        assertEquals(
            ResetReason.EDITOR_CHANGE,
            service.corrWiring?.tracker?.lastResetReason,
        )
        service.onDestroy()
    }

    // ================================================= REVIEW_V0147 fix round

    /**
     * VK-1 (P0): ALL_ACTIVE with current=de-de + en-us active must seed EVERY
     * layout this computation's corrector may load — current (WordCorrector
     * loads by layoutId) AND rulesLang (en-'i' matrix flips it to en-us). The
     * JVM walk-up loader must NEVER be the source (it crashes the process on
     * Android): walkUpLoads == 0 proves seed coverage.
     */
    @Test
    fun `corrComputeRegion seeds current and rulesLang - zero walk-up loads`() {
        val service = Robolectric.buildService(VoiceKeyboardService::class.java).get()
        service.onCreate()
        service.getSharedPreferences(VoiceKeyboardService.LAYOUT_PREFS, android.content.Context.MODE_PRIVATE)
            .edit()
            .putString(VoiceKeyboardService.KEY_ACTIVE_LANGUAGES, "de-de,en-us")
            .putString(VoiceKeyboardService.KEY_CURRENT_LANGUAGE, "de-de")
            .putString(
                VoiceKeyboardService.PREF_CORR_LANG_MODE,
                de.voicekeyboard.correct.LanguageModePolicy.VALUE_ALL,
            )
            .commit()
        de.voicekeyboard.correct.NeighbourModel.resetSeedsForTest()
        val corrected = service.corrComputeRegion("morgem Welt ")
        // VK-8 sharpened (was rules-only-blind: caps alone satisfied != null):
        // the LEXICON fix needs the REAL word lists over the registry.
        assertEquals(
            "en rules capitalize + de list fixes morgem — lists really loaded",
            "Morgen Welt ",
            corrected,
        )
        assertTrue("current layout (de-de) seeded", de.voicekeyboard.correct.NeighbourModel.isSeeded("de-de"))
        assertTrue("rulesLang layout (en-us) seeded", de.voicekeyboard.correct.NeighbourModel.isSeeded("en-us"))
        assertEquals(
            "no JVM walk-up load — the only production source is seed()",
            0,
            de.voicekeyboard.correct.NeighbourModel.walkUpLoads,
        )
        service.onDestroy()
    }

    /** VK-1 net: a compute crash must NEVER kill the worker/process — the row
     *  clears (structured rejection), and NO log line carries text (rule 9). */
    @Test
    fun `compute crash clears the row and never propagates`() {
        val h = Harness()
        val crashing = CorrectionWiring(
            compute = { throw IllegalStateException("boom") },
            editor = { h.editor },
            dictationIdle = { true },
            schedule = h.scheduler::schedule,
            cancelScheduled = h.scheduler::cancel,
            postToMain = { it() },
            onRowUpdate = { original, corrected, hasSuggestion, _ ->
                h.rowUpdates.add(Triple(original, corrected, hasSuggestion))
            },
            onUndoMode = { h.undoModes.add(it) },
        )
        crashing.onFieldStart(0)
        crashing.feedText("morgem ") // separator -> immediate compute -> crash
        assertEquals("row cleared after the crash", Triple(null, null, false), h.rowUpdates.last())
        for (entry in org.robolectric.shadows.ShadowLog.getLogs()) {
            assertFalse("crash log leaks text: ${entry.tag}", entry.msg.contains("morgem"))
        }
    }

    /**
     * VK-3 (P1): APPLY within the open debounce must NEVER replace the NEWER
     * buffer with the OLD suggestion — the suggestion is bound to its region:
     * a buffer change voids it; the wand press is ignored and the compute
     * re-planned instead of deleting fresh keystrokes.
     */
    @Test
    fun `APPLY on a stale suggestion is ignored and re-plans the compute`() {
        val h = Harness()
        h.wiring.onFieldStart(0)
        h.editor.before = "morgem "
        h.wiring.feedText("morgem ") // separator -> publish, suggestion bound to "morgem "
        assertEquals(1, h.computeCalls)

        h.wiring.feedText(" w") // debounce OPEN: buffer "morgem  w" != suggestion region
        h.wiring.onWandPress(RegionReplacer.WandPress.APPLY)
        assertTrue("no editor batch for a stale suggestion", h.editor.log.isEmpty())
        assertEquals(0, CorrCounters.applied)
        assertTrue("compute re-planned after the stale APPLY", h.scheduler.pending.isNotEmpty())

        h.editor.before = "morgem  w"
        h.scheduler.fire(h.scheduler.pending.last().first)
        assertEquals("the re-plan computed the FRESH region", 2, h.computeCalls)
        assertEquals("morgem  w", h.lastRegion)
    }

    // ================================================= EMU_DUTY VK-8 regression

    /**
     * VK-8 (P0, EMU_DUTY_V0147_ADDENDUM §1): END-TO-END over the REAL service
     * lambda + REAL APK assets — the word lists MUST load through the registry
     * (the old double-prefixed path opened "wordlists/wordlists/de-de.bin.bin"
     * => FileNotFound => empty map => correct() ALWAYS null on the device; the
     * JVM masked it threefold). The EXACT assertion fails on rules-only output:
     * "Heute morgem " is already correctly capitalized — only the LEXICON fix
     * produces "Heute morgen ".
     */
    @Test
    fun `corrComputeRegion fixes a typo with the REAL word lists end to end`() {
        val service = Robolectric.buildService(VoiceKeyboardService::class.java).get()
        service.onCreate()
        val corrected = service.corrComputeRegion("Heute morgem ")
        assertEquals("lexicon fix needs the real de list via the registry", "Heute morgen ", corrected)
        service.onDestroy()
    }

    /** VK-8 (b): the registry loads BOTH shipped lists over the SERVICE
     *  lambda (real Robolectric assets) — size 2 fails on the empty-map bug. */
    @Test
    fun `registry lambda loads both shipped word lists from the real assets`() {
        val service = Robolectric.buildService(VoiceKeyboardService::class.java).get()
        service.onCreate()
        val lists = service.corrRegistry.listsFor(listOf("de-de", "en-us"))
        assertEquals("both shipped lists reachable over the asset path", 2, lists.size)
        assertTrue("de list usable", lists.getValue("de-de").contains("morgen", java.util.Locale.ROOT))
        service.onDestroy()
    }
}
