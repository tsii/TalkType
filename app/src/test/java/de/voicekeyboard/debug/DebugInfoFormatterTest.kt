package de.voicekeyboard.debug

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A14/T17 §5: die 8 geforderten JVM-Tests für [DebugInfoFormatter].
 * Reine Logik, kein Android, kein Robolectric — der Formatter ist
 * absichtlich Android-frei gebaut, damit genau diese Tests laufen.
 */
class DebugInfoFormatterTest {

    private val full = DebugSnapshot(
        versionName = "0.1.4",
        versionCode = 5,
        debugBuild = false,
        apiLevel = 36,
        modelStatus = "READY",
        modelVariant = "ONNX_CPU",
        modelVersionShort = "v-1730abcdef",
        modelBytes = 670_619_706L,
        lastError = "audioInit:SecurityException",
        rtf = 0.31,
        firstHypothesisMs = 2400,
        intraOpThreads = 8,
        interOpThreads = 1,
        permRecordAudio = true,
        permPostNotifications = true,
        peakPercent = 47,
        audioRoute = "intern",
        audioSource = "VOICE_RECOGNITION",
        peakDbfs = -34.2,
        // F13b2: Langzeit-Abbruch-Beweisfelder (C1) — nur Zahlen/Zustandsschlüssel
        ctlState = "RECORDING",
        ctlFailed = false,
        droppedBlocks = 0,
        hypCount = 12,
        hypGapMs = 800,
        audioSeconds = 9.0,
        lastStartMs = 2144,
        starting = false,
        // F17b/C2: Decode-Arbeitslast — nur Zahlen (Regel 9)
        decodeMsMax = 900,
        decodeMsP95 = 850,
        bufferSeconds = 8.5,
        // F21: cost-aware Takt — nur Zahlen (Regel 9)
        decodePaceMs = 4000,
        decodeSkips = 7,
    )

    private val empty = DebugSnapshot()

    // 1. FELDZAHL (F31→AP7-E1→A4a→W-vc34): Kopf + 68 Zeilen = 69 — die Drift-Pruefung im Formatter
    // (`check(lines.size == KEYS.size + 1)`) muss jede Feld-Aenderung anfassen.
    @Test
    fun `format hat exakt 69 Zeilen - Kopf plus 68 Felder`() {
        for (s in listOf(full, empty)) {
            val lines = DebugInfoFormatter.format(s).lines()
            assertEquals("Kopf + KEYS.size(68) Felder", 69, lines.size)
            assertTrue("zu wenige Zeilen: ${lines.size}", lines.size >= 5)
        }
    }

    // 2. jede Zeile (nach dem Kopf) matcht `^[a-z0-9_]+: .*$`
    @Test
    fun `every line matches key-value pattern`() {
        val lines = DebugInfoFormatter.format(full).lines()
        assertEquals("Kopfzeile fest", "# VK-DEBUG v1 keine inhalte", lines.first())
        val pattern = Regex("^[a-z0-9_]+: .*$")
        for (line in lines.drop(1)) {
            assertTrue("Zeile matcht nicht key: value: '$line'", pattern.matches(line))
        }
        // feste Reihenfolge (Determinismus über die Keys)
        val keys = lines.drop(1).map { it.substringBefore(":") }
        assertEquals(
            listOf("app_version", "build", "api_level", "model_status", "model_variant",
                "model_version", "model_bytes", "last_error", "rtf", "first_hyp_ms",
                "threads", "permissions", "peak_pct", "route", "src", "peak_dbfs",
                "ctl_state", "ctl_failed", "dropped", "hyp_count", "hyp_gap_ms",
                "audio_s", "start_ms", "starting",
                "decode_ms_max", "decode_ms_p95", "buffer_s",
                "decode_pace_ms", "decode_skips", "cap_blocks", "cap_stall_ms",
                "abort_reason", "hyp_last_buffer_s", "mic_rel", "status_shown", "status_res", "loud_s",
                "had_output", "output_chars", "first_loud_ms",
                "first3s_loud_blocks", "first3s_peak_dbfs", "fgs_stopped_by",
                "stop_cause", "start_src", "gate_at_stop", "owner_lost_at_s",
                "segment_finalizations", "max_pause_ms", "restart_inputs", "sel_unexpected",
                "gate_foreign_resets", "rebases",
                // A4a (PLAN_V0133 §A4a): +10 Schreib-/Decode-Diagnose-Felder am Ende.
                "last_decode_ms", "hyp_len", "current_len", "committed_len", "write_reason",
                "write_same", "write_w1", "write_w3d", "write_delta", "last_write_audio_s",
                // W-Paket (vc34, Debugger-Empfehlung): +5 Engine-Warming-Felder am Ende.
                "engine_load_ms", "engine_resident", "warm_hit", "load_trigger", "engine_loads"),
            keys,
        )
    }

    // 3. keine Pfade/URLs/E-Mails/Paketnamen (kein '/', 'http', '@', '\')
    @Test
    fun `contains no paths urls emails or package names`() {
        for (s in listOf(full, empty)) {
            val out = DebugInfoFormatter.format(s)
            assertFalse("Pfad-Slash im Export", out.contains("/"))
            assertFalse("http im Export", out.contains("http"))
            assertFalse("E-Mail/@ im Export", out.contains("@"))
            assertFalse("Backslash im Export", out.contains("\\"))
            assertFalse("Paketname im Export", out.contains("de.voicekeyboard"))
        }
    }

    // 4. Version + versionCode erscheinen unverändert
    @Test
    fun `version and code appear verbatim`() {
        val out = DebugInfoFormatter.format(full)
        assertTrue("versionName fehlt", out.contains("app_version: 0.1.4 (5)"))
    }

    // 5. fehlende Werte → `-`
    @Test
    fun `unknown values render as dash`() {
        val out = DebugInfoFormatter.format(empty)
        val lines = out.lines().drop(1)
        assertTrue("alle Werte eines leeren Snapshots sind '-'",
            lines.isNotEmpty() && lines.all { it.endsWith(": -") })
    }

    // 6. Werte mit Umbruch/Doppelpunkt werden sanitisiert
    @Test
    fun `values with newline or colon are sanitized`() {
        val dirty = full.copy(
            modelVersionShort = "v-1\n2:3\r\nx",
            lastError = "audioInit:Security\nException:secret",
        )
        val out = DebugInfoFormatter.format(dirty)
        assertEquals("keine mehrzeilige Ausgabe", 69, out.lines().size)
        for (line in out.lines().drop(1)) {
            val value = line.substringAfter(": ")
            assertFalse("Doppelpunkt im Wert: '$value'", value.contains(":"))
            assertFalse("Umbruch im Wert", value.contains("\n"))
        }
        assertTrue("leerer/schmutziger Wert kollabiert zu '-'",
            out.lines().any { it.startsWith("model_version: ") })
    }

    // 7. Modellstatus und Byte-Summe stehen 1:1 im Export
    @Test
    fun `model status and byte sum match input`() {
        val out = DebugInfoFormatter.format(full)
        assertTrue(out.contains("model_status: READY"))
        assertTrue(out.contains("model_bytes: 670619706"))
    }

    // F42 (F41-Fehlende-Messpunkte): Ausgabe-/Stille-/FGS-Forensik — nur
    // Zahlen/Codes, niemals Textinhalte (Regel 9); fehlende Werte = '-'.
    @Test
    fun `F42-Felder had_output output_chars first_loud_ms first3s fgs_stopped_by`() {
        val out = DebugInfoFormatter.format(
            DebugSnapshot(
                hadOutput = true,
                outputChars = 13,
                firstLoudMs = 2_140L,
                first3sLoudBlocks = 42,
                first3sPeakDbfs = -23.4,
                fgsStoppedBy = "abort:audio_error:silence",
            ),
        )
        assertTrue("had_output 0/1", out.lines().contains("had_output: 1"))
        assertTrue("output_chars nur Zahl", out.lines().contains("output_chars: 13"))
        assertTrue("first_loud_ms", out.lines().contains("first_loud_ms: 2140"))
        assertTrue("first3s_loud_blocks", out.lines().contains("first3s_loud_blocks: 42"))
        assertTrue("first3s_peak_dbfs 1 Dezimale", out.lines().contains("first3s_peak_dbfs: -23.4"))
        // Doppelpunkte im Code werden sanitisiert (abort=audio_error=silence)
        assertTrue("fgs_stopped_by Code sanitisiert", out.lines().contains("fgs_stopped_by: abort=audio_error=silence"))
        // Leere Werte: '-' statt Geraten (nie laut / kein Block / kein FGS-Stopp).
        val none = DebugInfoFormatter.format(DebugSnapshot()).lines()
        assertTrue("nie laut -> -", none.contains("first_loud_ms: -"))
        assertTrue("kein Block -> -", none.contains("first3s_peak_dbfs: -"))
        assertTrue("kein FGS-Stop -> -", none.contains("fgs_stopped_by: -"))
    }

    // 8. zweimal formatieren ⇒ identisch (Determinismus)
    @Test
    fun `output is deterministic`() {
        val a = DebugInfoFormatter.format(full)
        val b = DebugInfoFormatter.format(full.copy())
        assertEquals(a, b)
        assertEquals(DebugInfoFormatter.format(empty), DebugInfoFormatter.format(DebugSnapshot()))
    }

    // F10: Aufnahmeweg/-eingang/Pegel in dBFS — ohne diese Zeilen ist ein
    // Telefonbefund wie „peak_pct: 2“ nicht deutbar (docs/DEBUG_INFO_FIELDS.md).
    @Test
    fun `neue Felder route src peak_dbfs erscheinen und fehlen sauber als Strich`() {
        val fullText = DebugInfoFormatter.format(full)
        assertTrue(fullText.contains("route: intern"))
        assertTrue(fullText.contains("src: VOICE_RECOGNITION"))
        assertTrue(fullText.contains("peak_dbfs: -34.2"))
        val emptyText = DebugInfoFormatter.format(empty)
        assertTrue(emptyText.contains("route: -"))
        assertTrue(emptyText.contains("src: -"))
        assertTrue(emptyText.contains("peak_dbfs: -"))
        // Der Exporttext enthaelt weiterhin keine Pfade (Regel 9).
        assertFalse(fullText.contains("/"))
    }

    // F52 (PLAN_V0130 A3, 🐞: status_res): Code/Ressourcen-NAME des zuletzt
    // gezeigten Statustexts — NIE der Text selbst (Regel 9); Doppelpunkte im
    // Code werden sanitisiert (wie fgs_stopped_by); fehlt → '-'.
    @Test
    fun `F52-Feld status_res nur Code nie Inhalt`() {
        val out = DebugInfoFormatter.format(
            DebugSnapshot(statusRes = "error:dictation_error_editor_busy"),
        )
        assertTrue(
            "status_res mit Ressourcen-Name",
            out.lines().contains("status_res: error=dictation_error_editor_busy"),
        )
        // Arten ohne Doppelpunkt bleiben unverändert.
        val plain = DebugInfoFormatter.format(DebugSnapshot(statusRes = "starting")).lines()
        assertTrue(plain.contains("status_res: starting"))
        // Nie Inhalt: der Statustext selbst darf nicht erscheinen.
        assertFalse(out.contains("Starte"))
        // Fehlt → '-', nie Geraten.
        val none = DebugInfoFormatter.format(DebugSnapshot()).lines()
        assertTrue(none.contains("status_res: -"))
    }

    // F13b2 (a-d): die 8 neuen Diagnosefelder — exakte Schluessel, exakte Werte,
    // feste Reihenfolge am Ende, fehlende Werte als Strich (nie als Inhalt).
    @Test
    fun `neue Diagnosefelder ctl_state bis starting erscheinen exakt und fehlen als Strich`() {
        val fullText = DebugInfoFormatter.format(full)
        // (d) exakte Schluesselnamen und Werte im Voll-Snapshot:
        assertTrue(fullText.contains("ctl_state: RECORDING"))
        assertTrue(fullText.contains("ctl_failed: 0"))
        assertTrue(fullText.contains("dropped: 0"))
        assertTrue(fullText.contains("hyp_count: 12"))
        assertTrue(fullText.contains("hyp_gap_ms: 800"))
        assertTrue(fullText.contains("audio_s: 9.0"))
        assertTrue(fullText.contains("start_ms: 2144"))
        assertTrue(fullText.contains("starting: 0"))
        // (b) fehlende Werte → "-", NIE ein Inhalt (Regel 9):
        val emptyText = DebugInfoFormatter.format(empty)
        for (key in listOf("ctl_state", "ctl_failed", "dropped", "hyp_count",
                "hyp_gap_ms", "audio_s", "start_ms", "starting")) {
            assertTrue("$key muss als Strich fehlen", emptyText.contains("$key: -"))
        }
        // (c) Reihenfolge exakt wie KEYS — die Beweisfelder stehen AM ENDE
        // (F28b1: Capture-Stall-Felder vor den Abort-Feldern; F42: +6 Forensik-
        // Felder hinter loud_s; W-vc34: +5 Engine-Warming dahinter).
        val keys = fullText.lines().drop(1).map { it.substringBefore(":") }
        assertEquals(
            listOf("ctl_state", "ctl_failed", "dropped", "hyp_count", "hyp_gap_ms",
                "audio_s", "start_ms", "starting",
                "decode_ms_max", "decode_ms_p95", "buffer_s",
                "decode_pace_ms", "decode_skips", "cap_blocks", "cap_stall_ms",
                "abort_reason", "hyp_last_buffer_s", "mic_rel", "status_shown", "status_res", "loud_s",
                "had_output", "output_chars", "first_loud_ms",
                "first3s_loud_blocks", "first3s_peak_dbfs", "fgs_stopped_by",
                // AP3 (Export v2 Teil 1): +8 Forensik-Felder am Ende.
                "stop_cause", "start_src", "gate_at_stop", "owner_lost_at_s",
                "segment_finalizations", "max_pause_ms", "restart_inputs", "sel_unexpected",
                "gate_foreign_resets", "rebases",
                // A4a: +10 — die Bestandsschlüssel bleiben VOR den neuen stehen.
                "last_decode_ms", "hyp_len", "current_len", "committed_len", "write_reason",
                "write_same", "write_w1", "write_w3d", "write_delta", "last_write_audio_s"),
            keys.dropLast(5).takeLast(47),
        )
        // F17b+F21: exakte Werte im Voll-Snapshot, Strich wenn fehlend (nur Zahlen, Regel 9):
        assertTrue(fullText.contains("decode_ms_max: 900"))
        assertTrue(fullText.contains("decode_ms_p95: 850"))
        assertTrue(fullText.contains("buffer_s: 8.5"))
        assertTrue(fullText.contains("decode_pace_ms: 4000"))
        assertTrue(fullText.contains("decode_skips: 7"))
        for (key in listOf("decode_ms_max", "decode_ms_p95", "buffer_s",
                "decode_pace_ms", "decode_skips")) {
            assertTrue("$key muss als Strich fehlen", emptyText.contains("$key: -"))
        }
        // ctl_failed/starting sind 0/1, nie yes/no oder true/false:
        val flagged = full.copy(ctlFailed = true, starting = true)
        val flaggedText = DebugInfoFormatter.format(flagged)
        assertTrue(flaggedText.contains("ctl_failed: 1"))
        assertTrue(flaggedText.contains("starting: 1"))
        assertFalse(flaggedText.contains("ctl_failed: yes"))
        assertFalse(flaggedText.contains("starting: true"))
    }

    // AP3 (PLAN_C2_FABLE_R2 §4, Export v2 Teil 1): Session-Forensik-Felder —
    // nur Codes/Zahlen (Regel 9); Doppelpunkt im Wert wird sanitisiert (=).
    @Test
    fun `AP3-Felder stop_cause start_src gate_at_stop owner_lost_at_s und weitere`() {
        val out = DebugInfoFormatter.format(
            DebugSnapshot(
                stopCause = "audio_error=silence".replace("=", ":"), // Wert mit ':' -> '='
                startSrc = "hold",
                gateAtStop = 1,
                ownerLostAtS = 42.5,
                segmentFinalizations = 3,
                maxPauseMs = 8100L,
                restartInputs = 2,
                selUnexpected = 5,
            ),
        )
        val lines = out.lines()
        assertTrue("stop_cause sanitisiert", lines.contains("stop_cause: audio_error=silence"))
        assertTrue(lines.contains("start_src: hold"))
        assertTrue(lines.contains("gate_at_stop: 1"))
        assertTrue("owner_lost_at_s 1 Dezimale", lines.contains("owner_lost_at_s: 42.5"))
        assertTrue(lines.contains("segment_finalizations: 3"))
        assertTrue(lines.contains("max_pause_ms: 8100"))
        assertTrue(lines.contains("restart_inputs: 2"))
        assertTrue(lines.contains("sel_unexpected: 5"))
        // nie verloren: fehlende Werte kollabieren zu '-'
        val none = DebugInfoFormatter.format(DebugSnapshot()).lines()
        assertTrue("owner_lost_at_s fehlt -> -", none.contains("owner_lost_at_s: -"))
        assertTrue("stop_cause fehlt -> -", none.contains("stop_cause: -"))
    }

    // ---- A4a (PLAN_V0133 §A4a): +10 Schreib-/Decode-Diagnose-Felder ----

    // A4a-Rote-Test 1: exakt die zehn neuen Schlüssel, hinter rebases angehängt
    // (Bestandsreihenfolge unangetastet), keine Duplikate. W-vc34: die +5
    // Engine-Warming-Felder stehen HINTER den A4a-Feldern (dropLast(5)).
    @Test
    fun `A4a enthaelt exakt die zehn neuen Schluessel hinter rebases`() {
        val keys = DebugInfoFormatter.format(DebugSnapshot()).lines().drop(1).map { it.substringBefore(":") }
        assertEquals(
            listOf("last_decode_ms", "hyp_len", "current_len", "committed_len", "write_reason",
                "write_same", "write_w1", "write_w3d", "write_delta", "last_write_audio_s"),
            keys.dropLast(5).takeLast(10),
        )
        assertEquals("68 Felder", 68, keys.size)
        assertEquals("keine Duplikat-Schlüssel", keys.size, keys.toSet().size)
    }

    // A4a-Rote-Test 2: fehlende Werte → '-'; KEINE Schreibung ergibt
    // last_write_audio_s=-1 (nie '-'); ':' im Wert wird zu '=' sanitisiert.
    @Test
    fun `A4a fehlende Werte Strich - last_write_audio_s -1 - Doppelpunkt sanitisiert`() {
        val empty = DebugInfoFormatter.format(DebugSnapshot()).lines()
        for (k in listOf("last_decode_ms", "hyp_len", "current_len", "committed_len", "write_reason",
                "write_same", "write_w1", "write_w3d", "write_delta", "last_write_audio_s")) {
            assertTrue("$k muss als Strich fehlen", empty.contains("$k: -"))
        }
        // keine Schreibung: last_write_audio_s = -1 (Zahl, NIE '-')
        val noWrite = DebugInfoFormatter.format(
            DebugSnapshot(lastWriteAudioS = -1.0, writeReason = "w1", writeSame = 1, writeW1 = 1),
        ).lines()
        assertTrue("-1 vor erstem DELTA", noWrite.contains("last_write_audio_s: -1"))
        assertTrue(noWrite.contains("write_reason: w1"))
        assertTrue(noWrite.contains("write_same: 1"))
        assertTrue(noWrite.contains("write_w1: 1"))
        // ':' im Wert wird zu '=' sanitisiert (Regel 9/5)
        val dirty = DebugInfoFormatter.format(DebugSnapshot(writeReason = "same:x")).lines()
        assertTrue("Doppelpunkt sanitisiert", dirty.contains("write_reason: same=x"))
        // letzte Schreibung: 1 Dezimale wie audio_s; alle zehn Felder mit Werten
        val wrote = DebugInfoFormatter.format(
            DebugSnapshot(
                lastDecodeMs = 427L, hypLen = 55, currentLen = 55, committedLen = 48,
                writeReason = "delta", writeDelta = 3, writeW3d = 1, lastWriteAudioS = 12.34,
            ),
        ).lines()
        assertTrue("1 Dezimale", wrote.contains("last_write_audio_s: 12.3"))
        assertTrue(wrote.contains("write_delta: 3"))
        assertTrue(wrote.contains("write_w3d: 1"))
        assertTrue("Dauer des letzten Decode-Laufs", wrote.contains("last_decode_ms: 427"))
        assertTrue("Zeichenlängen, nie Inhalte (Regel 9)", wrote.contains("hyp_len: 55"))
        assertTrue(wrote.contains("current_len: 55"))
        assertTrue(wrote.contains("committed_len: 48"))
        assertTrue("geschlossener Schreib-Code", wrote.contains("write_reason: delta"))
    }

    // A4a-Rote-Test 3: die bestehenden 53 KEYS bleiben in REIHENFOLGE unverändert;
    // Kopf + 68 = 69 Zeilen (W-vc34: +5 Engine-Warming-Felder), deterministisch
    // bei leerem und vollem Snapshot.
    @Test
    fun `A4a Bestandsschluessel unverändert - 69 Zeilen deterministisch leer und voll`() {
        val old53 = listOf("app_version", "build", "api_level", "model_status", "model_variant",
            "model_version", "model_bytes", "last_error", "rtf", "first_hyp_ms",
            "threads", "permissions", "peak_pct", "route", "src", "peak_dbfs",
            "ctl_state", "ctl_failed", "dropped", "hyp_count", "hyp_gap_ms",
            "audio_s", "start_ms", "starting",
            "decode_ms_max", "decode_ms_p95", "buffer_s",
            "decode_pace_ms", "decode_skips", "cap_blocks", "cap_stall_ms",
            "abort_reason", "hyp_last_buffer_s", "mic_rel", "status_shown", "status_res", "loud_s",
            "had_output", "output_chars", "first_loud_ms",
            "first3s_loud_blocks", "first3s_peak_dbfs", "fgs_stopped_by",
            "stop_cause", "start_src", "gate_at_stop", "owner_lost_at_s",
            "segment_finalizations", "max_pause_ms", "restart_inputs", "sel_unexpected",
            "gate_foreign_resets", "rebases")
        for (s in listOf(full, empty)) {
            val lines = DebugInfoFormatter.format(s).lines()
            assertEquals("Kopf + 68 Felder", 69, lines.size)
            assertEquals("bestehende 53 KEYS unverändert", old53, lines.drop(1).map { it.substringBefore(":") }.dropLast(15))
        }
        assertEquals(
            DebugInfoFormatter.format(DebugSnapshot()),
            DebugInfoFormatter.format(DebugSnapshot()),
        )
        assertEquals(DebugInfoFormatter.format(full), DebugInfoFormatter.format(full.copy()))
    }

    // ---- W-Paket (vc34, Debugger-Empfehlung): +5 Engine-Warming-Felder ----

    // W4c: exakte Schlüssel/Werte am Ende, Kopf+68=69 Zeilen, '-/default' bei
    // leerem Zustand (Regel 9: nur Zahlen/Codes mic|ime_show|'-', nie Inhalte).
    @Test
    fun `W-Felder engine_load_ms bis engine_loads exakt - 69 Zeilen - Strich bei leer`() {
        val out = DebugInfoFormatter.format(
            DebugSnapshot(
                engineLoadMs = 4531L,
                engineResident = true,
                warmHit = true,
                loadTrigger = "ime_show",
                engineLoads = 1,
            ),
        ).lines()
        assertTrue("reine load-Dauer", out.contains("engine_load_ms: 4531"))
        assertTrue("resident 0/1", out.contains("engine_resident: 1"))
        assertTrue("warmer Start 0/1", out.contains("warm_hit: 1"))
        assertTrue("Auslöser-Code", out.contains("load_trigger: ime_show"))
        assertTrue("Load-Zähler", out.contains("engine_loads: 1"))
        // Kopf + 68 Felder = 69 Zeilen (die Drift-Prüfung im Formatter sichert die Zahl)
        assertEquals(69, out.size)
        // Letzte fünf Schlüssel exakt in fester Reihenfolge (hinter last_write_audio_s):
        assertEquals(
            listOf("engine_load_ms", "engine_resident", "warm_hit", "load_trigger", "engine_loads"),
            out.drop(1).map { it.substringBefore(":") }.takeLast(5),
        )
        // leerer Zustand: '-' statt Geraten (kein Coordinator => alles Strich)
        val none = DebugInfoFormatter.format(DebugSnapshot()).lines()
        for (k in listOf("engine_load_ms", "engine_resident", "warm_hit", "load_trigger", "engine_loads")) {
            assertTrue("$k muss als Strich fehlen", none.contains("$k: -"))
        }
        assertEquals(69, none.size)
        // 0/1-Kodierung, nie true/false (wie ctl_failed/starting)
        val zero = DebugInfoFormatter.format(
            DebugSnapshot(engineResident = false, warmHit = false),
        ).lines()
        assertTrue(zero.contains("engine_resident: 0"))
        assertTrue(zero.contains("warm_hit: 0"))
    }
}
