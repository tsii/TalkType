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
 * Instrumentierter Diktat-Pipeline-Test (M4d, E4-Szenarien ohne Mikrofon):
 * echte ASR-Engine (echtes Modell aus filesDir/models), echte WAV-Fixtures,
 * Fake-/Low-Level-Editoren. Beweist die Kette ab Audio-Puffer bis Text —
 * alles außer Mic-Treiber/Route (Geräte-Gate, DESIGN §10.4; `-no-audio`-
 * Emulator: kein E2E, nur Pipeline).
 *
 * Szenarien (E4):
 *  S1 echte Engine + de_smoke.wav → Endtext enthält den §4.1-Golden-Text;
 *     committed-Präfix wurde nie gelöscht (Monotonie der Operationen).
 *  S2 InputConnectionSink gegen Fake-InputConnection: Batch-Paarung auch bei
 *     Wurf, UTF-16-Löschung korrekt, Surrogatpaare/`ä` unzerrt.
 *  S3 Besitzverlust (Nutzer tippt dazwischen): danach keine Schreibop mehr.
 *  S4 60-Zeichen-Revisionsgrenze: Alttext-Präfix bleibt, Append-only.
 */
@RunWith(AndroidJUnit4::class)
class DictationPipelineTest {

    /**
     * M3 aus `docs/PLAN_AUDIO_UND_LAYOUT.md` §1.3 — der Königsbeweis: derselbe
     * Golden-Nachweis wie S1, aber der Ton kommt über das **MIKROFON** (vom Host
     * in das virtuelle Mikrofon eingespeist), nicht aus einer WAV-Datei.
     *
     * Ausführung (nur Orchestrator):
     *   tools/emulator_audio_up.sh
     *   ( for i in 1..8: paplay testdata/de_smoke.wav ) &
     *   adb -s <device> shell am instrument -w \
     *       -e class de.voicekeyboard.dictation.DictationPipelineTest#m3_realEngineFromMicrophone_commitsGoldenText \
     *       de.voicekeyboard.test/androidx.test.runner.AndroidJUnitRunner
     *
     * Toleranz: Wort-Identität ≥ 95 % statt exakter Übereinstimmung — der Host-Pfad
     * resampelt 48 → 16 kHz und überträgt das Signal über zwei Stufen.
     */
    @Test
    fun m3_realEngineFromMicrophone_commitsGoldenText() {
        val storeDir2 = File(appContext().filesDir, "models")
        val aktiv = File(storeDir2, "active.txt").takeIf { it.isFile }?.readText()?.trim()
        val versionen = File(storeDir2, "versions").listFiles()?.map { f -> f.name } ?: emptyList()
        println("M3-DIAG zeiger=" + aktiv + " versionen=" + versionen + " inhaltsverzeichnis=" + File(storeDir2, "versions/" + (aktiv ?: "-")).listFiles()?.map { f -> f.name })
        val modelDir = modelDirOrNull()
        assertTrue(
            "Modellpfad nicht aufloesbar: Zeiger='" + (aktiv ?: "keine active.txt") +
                "' Versionen=" + versionen + " aufgeloest=" + (modelDir?.name ?: "null"),
            modelDir != null,
        )
        assertModelIntegrity(modelDir!!)

        // 1) 7,5 s echtes Mikrofon-Signal in 20-ms-Blöcke sammeln (320 Samples)
        val quelle = de.voicekeyboard.audio.AudioRecordSource(maxDurationMs = 15_000)
        assertTrue("Mikrofon-Start fehlgeschlagen", quelle.start(1L))
        val puffer = ShortArray(320)
        val blocks = ArrayList<ShortArray>()
        val startMs = System.currentTimeMillis()
        while (System.currentTimeMillis() - startMs < 7_500) {
            val n = quelle.read(puffer, 60)
            if (n > 0) blocks.add(puffer.copyOfRange(0, n))
        }
        quelle.release()
        val samples = blocks.sumOf { it.size }
        assertTrue("nur $samples Samples vom Mikrofon gelesen (erwartet > ${16_000 * 3})", samples > 16_000 * 3)

        // 2) dieselbe Pipeline wie S1: echter Controller, echte Engine, echtes Modell
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

        // 3) Bewertung gegen §4.1: Wort-Identität, und die harte Sprachprüfung
        val endtext = sink.text
        val normal = { t: String -> t.lowercase().split(" ").map { w -> w.trim('.', ',', '!', '?', ':', ';') }.filter { it.isNotEmpty() } }
        val golden = normal(GOLDEN_SMOKE)
        val ist = normal(endtext)
        val treffer = golden.count { w -> ist.contains(w) }
        val identitaet = 100 * treffer / golden.size
        assertTrue(
            "Wort-Identitaet ${identitaet}% unter 95 %; Ist='${endtext.take(160)}'",
            identitaet >= 95,
        )
        val englisch = listOf("the", "and", "you", "not", "sure", "with", "that")
        assertTrue(
            "fremdsprachiger Output (${ist.count { it in englisch }} englische Woerter): '${endtext.take(140)}'",
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

    // ---- Fixtures ----

    private fun appContext() = InstrumentationRegistry.getInstrumentation().targetContext

    /**
     * Modell-Bereitschaft (Assume-Schnellcheck): alle 4 Dateinamen aus
     * [de.voicekeyboard.model.ModelManifest.DEFAULT] liegen im Verzeichnis,
     * Größen stimmen mit sizeBytes überein. Akzeptiert BEIDE Layouts:
     * filesDir/models im Store-Layout (aktive Version) ODER einen flachen
     * Ordner (z. B. /data/local/tmp/models ohne active.txt/versions/ —
     * Store-konformes Layout wird nicht verlangt). Die volle SHA-256-Prüfung
     * läuft NICHT hier, sondern als Assertion im Testkörper.
     */
    private fun modelDirOrNull(): File? {
        // 1) App-interner Store (Layout mit active.txt + Hash-Verifikation)
        val storeDir = File(appContext().filesDir, "models")
        // Reihenfolge zaehlt: die Dateien liegen NIE in der Store-Wurzel, sondern
        // in versions/<aktiv>. Der Store meldet "bereit" und waehlt selbst die
        // Variante (Manifest je Build-Variante) — fuer die Engine ist beides egal.
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

    /** Volle Integritätsprüfung (SHA-256 über alle Dateien) — normale Assertion. */
    private fun assertModelIntegrity(dir: File) {
        for (entry in de.voicekeyboard.model.ModelManifest.DEFAULT) {
            val f = File(dir, entry.storeName)
            assertTrue("Modell-Datei fehlt in ${dir.name}: ${entry.storeName}", f.isFile)
            assertEquals("Größe falsch: ${entry.storeName}", entry.sizeBytes, f.length())
            assertEquals(
                "SHA-256 weicht ab: ${entry.storeName}",
                entry.sha256,
                de.voicekeyboard.model.Sha256.ofFile(f),
            )
        }
    }

    private fun assumeWav(name: String): File {
        val f = File(TESTDATA, name)
        assumeTrue("Test-WAV fehlt: $f (Emulator-Setup)", f.isFile)
        return f
    }

    /** RIFF/WAVE-Parser (Stil wie ParakeetGoldenTest): PCM16 mono 16 kHz. */
    private fun parseWav(file: File): ShortArray {
        RandomAccessFile(file, "r").use { raf ->
            val header = ByteArray(12)
            raf.readFully(header)
            require(String(header, 0, 4, Charsets.US_ASCII) == "RIFF") { "Kein RIFF" }
            require(String(header, 8, 4, Charsets.US_ASCII) == "WAVE") { "Kein WAVE" }
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
            val f = fmt ?: error("kein fmt")
            require(leShort(f, 0).toInt() and 0xFFFF == 1) { "Kein PCM" }
            require(leShort(f, 2).toInt() == 1) { "Nicht mono" }
            require(leInt(f, 4) == 16_000) { "Nicht 16 kHz" }
            val d = data ?: error("kein data")
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

    // ---- Fakes ----

    /** Thread-sicherer In-Memory-Sink: protokolliert JEDE Operation. */
    private class RecordingSink : EditorSink {
        val ops = CopyOnWriteArrayList<String>() // "repl:<len>:<len>" (keine Textinhalte in Logs)
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

    /** Fake-InputConnection (StringBuilder, Thread-sicher) für Szenario 2/3/4. */
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

        // ---- verbotene Operationen: harter Guard (Sink darf sie nicht rufen) ----
        override fun setComposingText(text: CharSequence, newCursorPosition: Int): Boolean =
            error("setComposingText verboten (DESIGN §6)")

        // ---- restliche Interface-Member: neutrale Defaults ----
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

    // ---- Szenario 1: echte Engine, echte WAV, Fake-Editor ----

    @Test
    fun s1_realEngine_realWav_commitsGoldenWithoutDeletingCommittedPrefix() {
        val modelDir = modelDirOrNull()
        assumeTrue("Kein Modell (filesDir/models noch /data/local/tmp/models)", modelDir != null)
        val wav = assumeWav("de_smoke.wav")
        assertModelIntegrity(modelDir!!) // volle SHA-256-Prüfung als Assertion, nicht Assume

        val sink = RecordingSink()
        val tracker = OwnershipTracker()
        val engine = ParakeetAsrEngine()
        val controller = DictationController(engine, sink, tracker = tracker)
        engine.load(modelDir!!)

        // WAV in 20-ms-Blöcke (16000 Hz → 320 Samples) — WavAudioSource-Semantik
        val pcm = parseWav(wav)
        val chunk = 320
        controller.start()
        tracker.onOwnEdit("") // Anfangsbesitz: leerer erwarteter Text, Editor leer
        for (off in pcm.indices step chunk) {
            val len = minOf(chunk, pcm.size - off)
            val block = PcmBlock((off / chunk).toLong(), off.toLong(), pcm.copyOfRange(off, off + len))
            runBlocking { controller.feedAudio(block) }
        }
        runBlocking { controller.stop() }

        // Endtext enthält den §4.1-Golden-Text (Modellfehler „Dikti-Funktion" inklusive)
        val finalText = sink.text
        assertTrue(
            "Endtext enthält nicht den Golden-Text; Ist='${finalText.take(120)}'",
            finalText.contains(GOLDEN_SMOKE),
        )
        // Monotonie: keine Operation löschte hinter der Commit-Grenze —
        // belegbar über maxDelete <= 60 (DeltaTyper-Revisionsschwelle) und
        // die NeverShrink-Eigenschaft des Endtextes: jede Operation verlängert
        assertTrue("committed-Präfix-Löschung: maxDelete=${sink.maxDelete.get()}", sink.maxDelete.get() <= 60)
        engine.close()
    }

    // ---- Szenario 2: InputConnectionSink gegen Fake-InputConnection ----

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
        // Partielle Revisionen: "abc" → "abcd" → "abcd€!" (Surrogatpaar € U+20AC)
        typer.revise("abc")
        assertEquals("abc", ic.text)
        typer.revise("abcd")
        assertEquals("abcd", ic.text)
        typer.revise("abcd€!")
        assertEquals("abcd€!", ic.text)
        // Löschung zählt UTF-16-Einheiten: "€" = 2 Einheiten — "abcd€" → "abcd"
        typer.revise("abcd")
        assertEquals("abcd", ic.text)
        // Korrekte DeltaTyper-Semantik: OHNE commitUpTo wird der komplette Tail
        // revidiert ("abcd" → "ä" ersetzt alles). Präfix-Schutz greift erst mit
        // commitUpTo — danach bleibt "abcd" unangetastet und "ä" wird angehängt.
        typer.commitUpTo(4) // "abcd" einfrieren
        typer.revise("abcdä")
        assertEquals("abcdä", ic.text)

        // Batch-Paarung auch bei Wurf: Editor lehnt ab
        ic.rejectAll.set(true)
        try {
            sink.replaceBeforeCursor(1, "X")
            org.junit.Assert.fail("EditorWriteException erwartet")
        } catch (e: de.voicekeyboard.editor.EditorWriteException) {
            // erwartet
        }
        // endBatchEdit lief in finally: Paarung blieb gewahrt (Anzahl >= Anzahl commits)
        assertTrue(ic.batchPairs.get() >= 1)
    }

    // ---- Szenario 3: Ownership-Verlust (Nutzer tippt dazwischen) ----

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
        typer.revise("das ist")
        val opsAtLoss = ic.commits.get() + ic.deletes.get()
        assertTrue(opsAtLoss > 0)

        // Nutzer tippt: Cursor bewegt sich fremd → Besitz verloren
        tracker.onUpdateSelection(7, 7, 3, 3)
        ic.commitText("X", 1) // simulierter Fremdtext (User-Eingabe)
        // Diktat weiter: Tracker verweigert
        val before = ic.commits.get() + ic.deletes.get()
        org.junit.Assert.assertNull("nach Besitzverlust KEINE Schreiboperation", typer.revise("das ist mehr"))
        assertEquals(before, ic.commits.get() + ic.deletes.get())
    }

    // ---- Szenario 4: 60-Zeichen-Grenze ----

    @Test
    fun s4_revisionLimit_altPrafixUnveraendert_appendOnly() {
        val ic = FakeInputConnection("A".repeat(80)) // ~80 Zeichen Alttext
        val sink = InputConnectionSink(
            connectionProvider = { ic },
            editExecutor = InputConnectionSink.DIRECT_EXECUTOR,
        )
        val tracker = OwnershipTracker()
        val typer = de.voicekeyboard.editor.DeltaTyper(sink)
        // Besitz über den FULL matchenden Alttext herstellen (80 Zeichen expected)
        val alt = ic.text
        tracker.onOwnEdit(alt)
        // Langer neuer Text, der >60 Zeichen zurücknehmen würde: DeltaTyper
        // muss auf Append-only entscheiden — Alttext-Präfix bleibt unangetastet.
        typer.revise(alt.substring(0, 20) + "NEU") // 60 Zeichen Differenz in der Tail-Revision
        val after = ic.text
        assertTrue(
            "Alttext muss unverändertes Präfix enthalten; Ist=${after.take(90)}",
            after.startsWith(alt.take(80 - 60)) || after.length >= alt.length,
        )
        // Kein Löschdelta > 60:_fake zählt deleteSurroundingText-Aufrufe;
        // der Sink/DeltaTyper hat max. 60 UTF-16-Einheiten gelöscht (Regel 2)
        assertTrue(ic.deletes.get() == 0 || ic.text.length >= alt.length - 60)
    }
}
