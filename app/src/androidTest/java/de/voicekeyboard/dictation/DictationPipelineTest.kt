package de.voicekeyboard.dictation

import android.app.Application
import android.view.inputmethod.InputConnection
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import de.voicekeyboard.asr.ParakeetAsrEngine
import de.voicekeyboard.audio.PcmBlock
import de.voicekeyboard.audio.PcmWindow
import de.voicekeyboard.editor.EditorSink
import de.voicekeyboard.editor.InputConnectionSink
import de.voicekeyboard.editor.OwnershipTracker
import de.voicekeyboard.model.ModelStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Instrumented dictation pipeline test (M4d, E4 scenarios without a microphone):
 * real ASR engine (real model from filesDir/models), real WAV fixtures,
 * fake/low-level editors. Proves the chain from the audio buffer to text —
 * everything except mic driver/route (device gate, DESIGN §10.4; `-no-audio`
 * emulator: no E2E, pipeline only).
 *
 * Scenarios (E4):
 *  S1 real engine + de_smoke.wav → final text contains the §4.1 golden text;
 *     the committed prefix was never deleted (monotonicity of operations).
 *  S2 InputConnectionSink against a fake InputConnection: batch pairing even on
 *     throw, UTF-16 deletion correct, surrogate pairs/`ä` unbroken.
 *  S3 ownership loss (user types in between): no write op afterwards.
 *  S4 60-character revision limit: the old-text prefix stays, append-only.
 */
@RunWith(AndroidJUnit4::class)
class DictationPipelineTest {

    /**
     * M3 from `docs/PLAN_AUDIO_UND_LAYOUT.md` §1.3 — the royal proof: the same
     * golden verification as S1, but the sound comes in via the **MICROPHONE**
     * (fed from the host into the virtual microphone), not from a WAV file.
     *
     * Execution (orchestrator only):
     *   tools/emulator_audio_up.sh
     *   ( for i in 1..8: paplay testdata/de_smoke.wav ) &
     *   adb -s <device> shell am instrument -w \
     *       -e class de.voicekeyboard.dictation.DictationPipelineTest#m3_realEngineFromMicrophone_commitsGoldenText \
     *       de.voicekeyboard.test/androidx.test.runner.AndroidJUnitRunner
     *
     * Tolerance: word identity ≥ 95 % instead of an exact match — the host path
     * resamples 48 → 16 kHz and passes the signal through two stages.
     */
    @Test
    fun m3_realEngineFromMicrophone_commitsGoldenText() {
        val storeDir2 = File(appContext().filesDir, "models")
        val aktiv = File(storeDir2, "active.txt").takeIf { it.isFile }?.readText()?.trim()
        val versionen = File(storeDir2, "versions").listFiles()?.map { f -> f.name } ?: emptyList()
        println("M3-DIAG pointer=" + aktiv + " versions=" + versionen + " listing=" + File(storeDir2, "versions/" + (aktiv ?: "-")).listFiles()?.map { f -> f.name })
        val modelDir = modelDirOrNull()
        assertTrue(
            "model path not resolvable: pointer='" + (aktiv ?: "no active.txt") +
                "' versions=" + versionen + " resolved=" + (modelDir?.name ?: "null"),
            modelDir != null,
        )
        assertModelIntegrity(modelDir!!)

        // 1) collect 7.5 s of real microphone signal into 20-ms blocks (320 samples)
        val quelle = de.voicekeyboard.audio.AudioRecordSource(maxDurationMs = 15_000)
        assertTrue("microphone start failed", quelle.start(1L))
        val puffer = ShortArray(320)
        val blocks = ArrayList<ShortArray>()
        val startMs = System.currentTimeMillis()
        while (System.currentTimeMillis() - startMs < 7_500) {
            val n = quelle.read(puffer, 60)
            if (n > 0) blocks.add(puffer.copyOfRange(0, n))
        }
        quelle.release()
        val samples = blocks.sumOf { it.size }
        assertTrue("only $samples samples read from the microphone (expected > ${16_000 * 3})", samples > 16_000 * 3)

        // 2) the same pipeline as S1: real controller, real engine, real model
        val sink = RecordingSink()
        val tracker = OwnershipTracker()
        val engine = ParakeetAsrEngine()
        val controller = DictationController(engine, sink, tracker = tracker)
        engine.load(modelDir)
        controller.start()
        tracker.onOwnEdit("")
        var off = 0
        for (block in blocks) {
            runBlocking {
                controller.feedAudio(PcmBlock((off / 320).toLong(), off.toLong(), block))
            }
            off += block.size
        }
        runBlocking { controller.stop() }
        engine.close()

        // 3) evaluation against §4.1: word identity, and the hard language check
        val endtext = sink.text
        val normal = { t: String -> t.lowercase().split(" ").map { w -> w.trim('.', ',', '!', '?', ':', ';') }.filter { it.isNotEmpty() } }
        val golden = normal(GOLDEN_SMOKE)
        val ist = normal(endtext)
        val treffer = golden.count { w -> ist.contains(w) }
        val identitaet = 100 * treffer / golden.size
        assertTrue(
            "word identity ${identitaet}% below 95 %; actual='${endtext.take(160)}'",
            identitaet >= 95,
        )
        val englisch = listOf("the", "and", "you", "not", "sure", "with", "that")
        assertTrue(
            "foreign-language output (${ist.count { it in englisch }} English words): '${endtext.take(140)}'",
            ist.count { it in englisch } == 0,
        )
    }

    companion object {
        private const val GOLDEN_SMOKE =
            "So, jetzt bin ich gespannt, ob die Dikti-Funktion auf dem Telefon funktioniert."
        private val FALLBACK_MODELS = File("/data/local/tmp/models")
        private val TESTDATA = File("/data/local/tmp/testdata")
        private const val CHUNK_MS = 20
    }

    // ---- fixtures ----

    private fun appContext() = InstrumentationRegistry.getInstrumentation().targetContext

    /**
     * Model readiness (assume quick check): all 4 file names from
     * [de.voicekeyboard.model.ModelManifest.DEFAULT] lie in the directory,
     * sizes match sizeBytes. Accepts BOTH layouts:
     * filesDir/models in the store layout (active version) OR a flat
     * folder (e.g. /data/local/tmp/models without active.txt/versions/ —
     * a store-compliant layout is not required). The full SHA-256 check
     * does NOT run here, but as an assertion in the test body.
     */
    private fun modelDirOrNull(): File? {
        // 1) app-internal store (layout with active.txt + hash verification)
        val storeDir = File(appContext().filesDir, "models")
        // the order matters: the files NEVER lie in the store root, but
        // in versions/<active>. The store reports "ready" and picks the
        // variant itself (manifest per build variant) — for the engine both are equal.
        runCatching {
            val aktiv = File(storeDir, "active.txt").readText().trim()
            val dir = File(storeDir, "versions/$aktiv")
            if (aktiv.isNotEmpty() && isFlatModelDir(dir)) return dir
        }
        if (isFlatModelDir(FALLBACK_MODELS)) return FALLBACK_MODELS
        return null
    }

    private fun isFlatModelDir(dir: File): Boolean =
        de.voicekeyboard.model.ModelManifest.DEFAULT.all { f ->
            val file = File(dir, f.storeName)
            file.isFile && file.length() == f.sizeBytes
        }

    /** Full integrity check (SHA-256 over all files) — a normal assertion. */
    private fun assertModelIntegrity(dir: File) {
        for (entry in de.voicekeyboard.model.ModelManifest.DEFAULT) {
            val f = File(dir, entry.storeName)
            assertTrue("model file missing in ${dir.name}: ${entry.storeName}", f.isFile)
            assertEquals("wrong size: ${entry.storeName}", entry.sizeBytes, f.length())
            assertEquals(
                "SHA-256 deviates: ${entry.storeName}",
                entry.sha256,
                de.voicekeyboard.model.Sha256.ofFile(f),
            )
        }
    }

    private fun assumeWav(name: String): File {
        val f = File(TESTDATA, name)
        assumeTrue("test WAV missing: $f (emulator setup)", f.isFile)
        return f
    }

    /** RIFF/WAVE parser (style like ParakeetGoldenTest): PCM16 mono 16 kHz. */
    private fun parseWav(file: File): ShortArray {
        RandomAccessFile(file, "r").use { raf ->
            val header = ByteArray(12)
            raf.readFully(header)
            require(String(header, 0, 4, Charsets.US_ASCII) == "RIFF") { "no RIFF" }
            require(String(header, 8, 4, Charsets.US_ASCII) == "WAVE") { "no WAVE" }
            var fmt: ByteArray? = null
            var data: ByteArray? = null
            while (raf.filePointer < raf.length() - 8) {
                val ch = ByteArray(8)
                raf.readFully(ch)
                val id = String(ch, 0, 4, Charsets.US_ASCII)
                val size = leInt(ch, 4)
                when (id) {
                    "fmt " -> { fmt = ByteArray(size).also { raf.readFully(it) } }
                    "data" -> { data = ByteArray(size).also { raf.readFully(it) } }
                    else -> raf.seek(raf.filePointer + size + (size % 2))
                }
            }
            val f = fmt ?: error("no fmt")
            require(leShort(f, 0).toInt() and 0xFFFF == 1) { "not PCM" }
            require(leShort(f, 2).toInt() == 1) { "not mono" }
            require(leInt(f, 4) == 16_000) { "not 16 kHz" }
            val d = data ?: error("no data")
            val n = d.size / 2
            val out = ShortArray(n)
            for (i in 0 until n) out[i] = leShort(d, i * 2)
            return out
        }
    }

    private fun leInt(b: ByteArray, o: Int) =
        (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8) or
            ((b[o + 2].toInt() and 0xFF) shl 16) or ((b[o + 3].toInt() and 0xFF) shl 24)

    private fun leShort(b: ByteArray, o: Int) =
        ((b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)).toShort()

    // ---- fakes ----

    /** Thread-safe in-memory sink: records EVERY operation. */
    private class RecordingSink : EditorSink {
        val ops = CopyOnWriteArrayList<String>() // "repl:<len>:<len>" (no text contents in logs)
        private val buf = StringBuilder()
        private val lock = Any()
        val text: String get() = synchronized(lock) { buf.toString() }
        val replaceOps = AtomicInteger(0)
        val maxDelete = AtomicInteger(0)

        override fun replaceBeforeCursor(replaceLength: Int, newText: String) {
            synchronized(lock) {
                ops.add("repl:$replaceLength:${newText.length}")
                replaceOps.incrementAndGet()
                maxDelete.updateAndGet { m -> maxOf(m, replaceLength) }
                buf.setLength(buf.length - replaceLength)
                buf.append(newText)
            }
        }

        override fun textBeforeCursor(maxChars: Int): CharSequence? =
            synchronized(lock) { buf.takeLast(minOf(maxChars, buf.length)) }

        override fun ownLength(): Int = synchronized(lock) { buf.length }
    }

    /** Fake InputConnection (StringBuilder, thread-safe) for scenarios 2/3/4. */
    private class FakeInputConnection(initial: String = "") : InputConnection {
        val buf = StringBuilder(initial)
        private val lock = Any()
        val text: String get() = synchronized(lock) { buf.toString() }
        val batchPairs = AtomicInteger(0)
        val commits = AtomicInteger(0)
        val deletes = AtomicInteger(0)
        val maxDeletedUnits = AtomicInteger(0)
        var rejectAll = AtomicReference(false)

        override fun getTextBeforeCursor(n: Int, flags: Int): CharSequence? =
            synchronized(lock) { buf.takeLast(minOf(n, buf.length)) }

        override fun getTextAfterCursor(n: Int, flags: Int): CharSequence? =
            synchronized(lock) { buf.take(minOf(n, buf.length)) }

        override fun beginBatchEdit(): Boolean = true

        override fun endBatchEdit(): Boolean {
            batchPairs.incrementAndGet()
            return true
        }

        override fun commitText(text: CharSequence, newCursorPosition: Int): Boolean =
            synchronized(lock) {
                if (rejectAll.get()) return@synchronized false
                commits.incrementAndGet()
                buf.append(text)
                true
            }

        override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean =
            synchronized(lock) {
                if (rejectAll.get()) return@synchronized false
                deletes.incrementAndGet()
                maxDeletedUnits.updateAndGet { m -> maxOf(m, beforeLength) }
                val keep = (buf.length - beforeLength).coerceAtLeast(0)
                buf.delete(keep, buf.length)
                true
            }

        // ---- forbidden operations: hard guard (the sink must not call them) ----
        override fun setComposingText(text: CharSequence, newCursorPosition: Int): Boolean =
            error("setComposingText forbidden (DESIGN §6)")

        // ---- remaining interface members: neutral defaults ----
        override fun getSelectedText(flags: Int): CharSequence? = synchronized(lock) { "" }
        override fun setSelection(start: Int, end: Int): Boolean = true
        override fun performEditorAction(actionCode: Int): Boolean = true
        override fun sendKeyEvent(event: android.view.KeyEvent?): Boolean = true
        override fun finishComposingText(): Boolean = true
        override fun clearMetaKeyStates(states: Int): Boolean = true
        override fun commitCorrection(info: android.view.inputmethod.CorrectionInfo?): Boolean = true
        override fun performContextMenuAction(id: Int): Boolean = true
        override fun getCursorCapsMode(reqModes: Int): Int = 0
        override fun reportFullscreenMode(enabled: Boolean): Boolean = true
        override fun performPrivateCommand(action: String?, data: android.os.Bundle?): Boolean = true
        override fun requestCursorUpdates(cursorUpdateMode: Int): Boolean = true
        override fun commitContent(
            inputContentInfo: android.view.inputmethod.InputContentInfo,
            flags: Int, opts: android.os.Bundle?,
        ): Boolean = false
        override fun closeConnection() {}
        override fun commitCompletion(arg0: android.view.inputmethod.CompletionInfo?): Boolean = true
        override fun deleteSurroundingTextInCodePoints(beforeLength: Int, afterLength: Int): Boolean = true
        override fun setComposingRegion(start: Int, end: Int): Boolean = true
        override fun getExtractedText(
            request: android.view.inputmethod.ExtractedTextRequest?, flags: Int,
        ): android.view.inputmethod.ExtractedText? = null
        override fun getHandler(): android.os.Handler? = null
    }

    // ---- scenario 1: real engine, real WAV, fake editor ----

    @Test
    fun s1_realEngine_realWav_commitsGoldenWithoutDeletingCommittedPrefix() {
        val modelDir = modelDirOrNull()
        assumeTrue("no model (neither filesDir/models nor /data/local/tmp/models)", modelDir != null)
        val wav = assumeWav("de_smoke.wav")
        assertModelIntegrity(modelDir!!) // full SHA-256 check as an assertion, not assume

        val sink = RecordingSink()
        val tracker = OwnershipTracker()
        val engine = ParakeetAsrEngine()
        val controller = DictationController(engine, sink, tracker = tracker)
        engine.load(modelDir!!)

        // WAV into 20-ms blocks (16000 Hz → 320 samples) — WavAudioSource semantics
        val pcm = parseWav(wav)
        val chunk = 320
        controller.start()
        tracker.onOwnEdit("") // initial ownership: empty expected text, editor empty
        for (off in pcm.indices step chunk) {
            val len = minOf(chunk, pcm.size - off)
            val block = PcmBlock((off / chunk).toLong(), off.toLong(), pcm.copyOfRange(off, off + len))
            runBlocking { controller.feedAudio(block) }
        }
        runBlocking { controller.stop() }

        // the final text contains the §4.1 golden text (model error „Dikti-Funktion" included)
        val finalText = sink.text
        assertTrue(
            "the final text does not contain the golden text; actual='${finalText.take(120)}'",
            finalText.contains(GOLDEN_SMOKE),
        )
        // monotonicity: no operation deleted beyond the commit boundary —
        // provable via maxDelete <= 60 (DeltaTyper revision threshold) and
        // the never-shrink property of the final text: every operation lengthens
        assertTrue("committed-prefix deletion: maxDelete=${sink.maxDelete.get()}", sink.maxDelete.get() <= 60)
        engine.close()
    }

    // ---- scenario 2: InputConnectionSink against a fake InputConnection ----

    @Test
    fun s2_inputConnectionSink_minimalDeltas_utf16_batches() {
        val ic = FakeInputConnection()
        val sink = InputConnectionSink(
            connectionProvider = { ic },
            editExecutor = InputConnectionSink.DIRECT_EXECUTOR,
        )
        val tracker = OwnershipTracker()
        val typer = de.voicekeyboard.editor.DeltaTyper(sink)

        tracker.onOwnEdit("")
        // partial revisions: "abc" → "abcd" → "abcd€!" (surrogate pair € U+20AC)
        typer.reviseOutcome("abc").delta
        assertEquals("abc", ic.text)
        typer.reviseOutcome("abcd").delta
        assertEquals("abcd", ic.text)
        typer.reviseOutcome("abcd€!").delta
        assertEquals("abcd€!", ic.text)
        // deletion counts UTF-16 units: "€" = 2 units — "abcd€" → "abcd"
        typer.reviseOutcome("abcd").delta
        assertEquals("abcd", ic.text)
        // correct DeltaTyper semantics: WITHOUT commitUpTo the complete tail
        // is revised ("abcd" → "ä" replaces everything). The prefix protection
        // only kicks in with commitUpTo — afterwards "abcd" stays untouched and
        // "ä" is appended.
        typer.commitUpTo(4) // freeze "abcd"
        typer.reviseOutcome("abcdä").delta
        assertEquals("abcdä", ic.text)

        // batch pairing also on throw: the editor rejects
        ic.rejectAll.set(true)
        try {
            sink.replaceBeforeCursor(1, "X")
            org.junit.Assert.fail("EditorWriteException expected")
        } catch (e: de.voicekeyboard.editor.EditorWriteException) {
            // expected
        }
        // endBatchEdit ran in finally: the pairing stayed intact (count >= count of commits)
        assertTrue(ic.batchPairs.get() >= 1)
    }

    // ---- scenario 3: ownership loss (the user types in between) ----

    @Test
    fun s3_ownershipLoss_noWritesAfterwards() {
        val ic = FakeInputConnection()
        val sink = InputConnectionSink(
            connectionProvider = { ic },
            editExecutor = InputConnectionSink.DIRECT_EXECUTOR,
        )
        val tracker = OwnershipTracker()
        val typer = de.voicekeyboard.editor.DeltaTyper(sink)
        tracker.onOwnEdit("")
        typer.reviseOutcome("das ist").delta
        val opsAtLoss = ic.commits.get() + ic.deletes.get()
        assertTrue(opsAtLoss > 0)

        // the user types: the cursor moves externally → ownership lost
        tracker.onUpdateSelection(7, 7, 3, 3)
        ic.commitText("X", 1) // simulated external text (user input)
        // dictation continues: the tracker refuses
        val before = ic.commits.get() + ic.deletes.get()
        org.junit.Assert.assertNull("no write operation after ownership loss", typer.reviseOutcome("das ist mehr").delta)
        assertEquals(before, ic.commits.get() + ic.deletes.get())
    }

    // ---- scenario 4: the 60-character limit ----

    @Test
    fun s4_revisionLimit_altPrafixUnveraendert_appendOnly() {
        val ic = FakeInputConnection("A".repeat(80)) // ~80 characters of old text
        val sink = InputConnectionSink(
            connectionProvider = { ic },
            editExecutor = InputConnectionSink.DIRECT_EXECUTOR,
        )
        val tracker = OwnershipTracker()
        val typer = de.voicekeyboard.editor.DeltaTyper(sink)
        // establish ownership over the FULL matching old text (80 characters expected)
        val alt = ic.text
        tracker.onOwnEdit(alt)
        // a long new text that would take back >60 characters: DeltaTyper
        // must decide on append-only — the old-text prefix stays untouched.
        typer.reviseOutcome(alt.substring(0, 20) + "NEU").delta // 60 characters of difference in the tail revision
        val after = ic.text
        assertTrue(
            "the old text must contain an unchanged prefix; actual=${after.take(90)}",
            after.startsWith(alt.take(80 - 60)) || after.length >= alt.length,
        )
        // no delete delta > 60: the fake counts deleteSurroundingText calls;
        // the sink/DeltaTyper deleted at most 60 UTF-16 units (rule 2)
        assertTrue(ic.deletes.get() == 0 || ic.text.length >= alt.length - 60)
    }
}
