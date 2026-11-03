# A14 — Feldkatalog für den Debug-Info-Export

**Stand:** 2026-10-28 · **Rolle:** Analyse/Plan (keine Code-/Build-Änderung)
**Ziel:** Nutzer tippt die Info-Taste → Text in Zwischenablage/Teilen-Dialog → Ferndiagnose
**ohne** Logcat. Alle Quellen Zeilennummern sind **lesend geprüft** (Stand dieses Commits).

## 0. Vorab: was schon existiert (nicht neu bauen)

| Baustein | Ort | Status |
|---|---|---|
| Pegel-Spitze (nur Max, keine Kurve) | `debug/LevelPeak.kt:12` (`note`, `percent`, `reset`) | **fertig**, rein JVM |
| Erst-Hypothese-Latenz | `ime/DictationCoordinator.kt:70` `firstHypothesisLatencyMs` | fertig |
| RTF-Zähler | `DictationCoordinator.kt:74` `processingMs`, `:78` `audioMs`, RTF-Formel `:96` | fertig |
| Kurz-Diagnosezeile | `DictationCoordinator.kt:94` `diagnosticsLine(threads)` | fertig (**nur** `erst/RTF/T/Peak`) |
| Level-Verdrahtung | `ime/VoiceKeyboardService.kt:363` `levelListener = AudioLevelListener { rms, _ -> coordinator?.noteLevel(rms) }` | **schon verdrahtet** (P1-Plan T3 ist erledigt) |
| Fehlercodes | `DictationCoordinator.kt:105/116` `lastError`/`visibleError`; Codes `:174` `audioInit:`, `:185` `startFailed`, `:64` `oom`, `:165` `model:`; `ModelError.kt:11-33` | fertig |
| Modellstatus/-varianten | `model/ModelStore.kt:136/138/140/143` (`isReady`/`status`, je `ModelVariant`), `:98` `modelDir(variant)` | fertig |
| Versionen | `app/build.gradle.kts:14` `versionCode = 5`, `:15` `versionName = "0.1.4"` — **kein** `buildFeatures{buildConfig=true}` ⇒ **BuildConfig ist NICHT verfügbar** | Lücke → PackageManager nutzen |
| Teilen-Taste | existiert nicht (weder Statusleiste noch Q-Reihe) | Lücke |

## 1. Feldkatalog

| Feld | Quelle (Datei:Zeile) | Nutzen für Ferndiagnose | Bedenken |
|---|---|---|---|
| `app_version`, `app_version_code` | `PackageManager.getPackageInfo(packageName,0)` (weil BuildConfig fehlt; `build.gradle.kts:14-15`) | ordnet Bericht einer APK zu; „welcher Build?" | keine (öffentlich) |
| `build_type` | `BuildConfig.DEBUG` **fehlt** ⇒ `ApplicationInfo.FLAG_DEBUGGABLE` | debug vs. release (Log-/Prüfpfad) | keine |
| `model_status` (`READY/BROKEN/ABSENT/…`) | `ModelStore.status(variant)` `:143` | häufigste Ursache „Diktat stumm" | keine |
| `model_variant` (`ONNX_CPU`/…) | `ModelVariant` (Enum), `ModelStore.kt:98` | trennt A/B-Berichte | keine |
| `model_files`, `model_bytes` (Summe) | `ModelStore.modelDir(variant).listFiles()` + `length()` | erkennt halbe/fehlende Downloads | nur Zahlen, keine Namen |
| `model_version_short` | `ModelStore.activeVersion(variant)` `:106` (z. B. `v-1730…`) | Resume/Doppel-Install erkennen | **Kurzname ja, Pfad nein** (§2) |
| `last_error` (Code) | `DictationCoordinator.visibleError` `:116`; Codes `:174/185/64/165` | Kern der Diagnose (E5-Klassen) | **nie** `message`/Stacktrace |
| `rtf` | `processingMs/audioMs` `:74/:78`, Formel `:96` | Hardware-/Thread-Bewertung | keine |
| `first_hypothesis_s` | `firstHypothesisLatencyMs` `:70` | „erst nach 8 s Text" | keine |
| `audio_last_s` | `audioMs` `:78` (bzw. `DictationController` `totalSamples`) | trennt „nichts gesprochen" von „nicht erkannt" | Metadatum (Dauer) — nur ganzzahlig |
| `peak_level_pct` | `LevelPeak.percent()` `debug/LevelPeak.kt:32` | „kommt überhaupt Ton an?" (Mikrofon stumm vs. Engine) | siehe §2 (Rückschluss) |
| `threads_intra`, `threads_inter` | `AsrThreadConfig` `:19-20` | erklärt RTF-Unterschiede | keine |
| `chunk_ms`, `hypothesis_min_interval_ms` | `AsrThreadConfig.kt:61/62` | Latenz/Takt-Erklärung | keine |
| `min_audio_s` | `DictationController.MIN_AUDIO_SECONDS_PARTIALS` | „warum kein Text unter 2 s?" | keine |
| `perm_record_audio`, `perm_post_notifications` | `DictationPermissions.grantedSet()` `:83`, `permanentlyDeniedSet()` `:86` | häufigste Ursache Nr. 2 | keine |
| `api_level` | `Build.VERSION.SDK_INT` | Plattform-Regressionen (FGS, Insets) | keine |
| `device_class` | `Configuration.smallestScreenWidthDp` (<600 = phone, ≥600 = tablet) | Layout-/Insets-Fehler | **kein** Gerätename, keine Seriennummer |
| `soc_model` (optional) | `Build.SOC_MODEL` (API 31+, `ANNAHME:` vorhanden) | NPU-/CPU-Vergleich (Pixel vs. andere) | kein Gerätename |
| `locale`, `layout_id` | `currentLanguage()` / `LayoutRepository` | „falsche Sprache/falsches Layout" | keine |
| `runtime` | `OrtEnvironment`-Version (bzw. LiteRT, falls B) | Versions-Mismatch | keine |
| **`route`** | `VoiceKeyboardService.kt:408` `audioRoute = routeLabel(router?.currentRoute ?: Route.BUILTIN_MIC)`; Labels `:468-472` (`intern` / `BT-Headset` / `Kabel-Headset`); Quelle `audio/AudioRouter.kt:17-20` | sagt, **aus welchem Mikrofon** aufgenommen wurde (Routing-Fehler = stummer/leiser Eingang) | drei deutsche Kurznamen, kein geschlossenes Enum im Export |
| **`src`** | `VoiceKeyboardService.kt:409` `audioSource = AudioRecordSource.SOURCE_NAME`; Konstante `audio/AudioRecordSource.kt:42` = `"VOICE_RECOGNITION"`; real genutzt `:93` `MediaRecorder.AudioSource.VOICE_RECOGNITION` | sagt, **welcher Android-AudioSource** aktiv ist → erklärt gerätespezifische Dämpfung (NR/AGC) | nur der Name, keine Parameter (NR/AGC sind nicht abfragbar) |
| **`peak_dbfs`** | `debug/LevelPeak.kt:41-44` `dbfs()` = `20·log10(maxRMS)`, `null` bei Stille (`max <= 0`); gesetzt in `debug/DebugInfoCollector.kt:89`; formatiert `%.1f` (`DebugInfoFormatter.format`) | **löst den Pegel auf**, wo `peak_pct` auf 0/2/3 rundet: −34 dBFS ist sofort als „viel zu leise" erkennbar | nur ein Maximalwert je Sitzung, keine Kurve (Regel 9) |

## 2. Regel 9 — Entscheidungen (begründet)

**Regel 9 verbietet Audio-/Textinhalte, URLs und Pfade.**

1. **Pfade: Version-Kurzname JA, vollständiger Pfad NEIN.** `files/models/versions/v-1730…`
   enthält den App-Sandbox-Pfad (`/data/user/0/de.voicekeyboard/…`) und die Ablage-Struktur.
   Der **Kurzname** genügt zur Diagnose („welche Version ist aktiv?") und ist ein opaker
   Token ohne Personenbezug. Also: Feld `model_version_short: v-1730…`, **niemals**
   `modelDir().absolutePath`. Gleiches gilt für `cacheDir`, `filesDir`, APK-Pfad.
2. **Peak-Wert rück-schließbar?** `LevelPeak` hält **einen** Maximalwert (0–100 %,
   ganzzahlig), keine Zeitreihe, keine Samples (`LevelPeak.kt:6-10` dokumentiert das).
   Aus einem einzelnen Prozentwert lässt sich **kein** Inhalt und keine Aussage über
   Sprache/Wörter rekonstruieren — nur „es gab Geräusch der Stärke X". Das ist
   Metadatum wie die Diktatdauer. **Zulässig**, aber: (a) nur ganzzahlig, (b) nur auf
   ausdrücklichen Knopfdruck (nie automatisch/telemetrisch), (c) nie mehrere Sitzungen
   (kein Verlauf).
3. **Nie exportieren:** Transkript-/Hypothesentext, Audio/Samples, `error.message`,
   Stacktraces, **Ziel-App-Paketname** (verrät, in welcher App diktiert wurde),
   Zwischenablage-Inhalt, Gerätename/Seriennummer/IMEI, Konten, URLs (Download-Host).
4. **Kein Logcat-Rohtext**: zu groß, enthält Fremdinhalte (andere Apps, Titel).

## 3. (a) Kurzformat (≤ 20 Zeilen, ein Block, `Schlüssel: wert`)

```
# VK-DEBUG v1 keine inhalte
app_version: 0.1.6 (7)
build: release
api_level: 37
model_status: READY
model_variant: ONNX_CPU
model_version: v-114124734651754
model_bytes: 670619706
last_error: -
rtf: 0.80
first_hyp_ms: 1116
threads: intra=4 inter=1
permissions: record=yes notif=no
peak_pct: 2
route: intern
src: VOICE_RECOGNITION
peak_dbfs: -34.0
```

**Ist-Format = 43 Zeilen** (1 Kopfzeile + **42** Felder; Stand F42 — davor 30 = 1+29
(F21-Ära), 29 = 1+28 (F28b), 31 = 1+30 (F31 `loud_s`), 35 = 1+34 (F26a/b + F28
`mic_rel`/`status_shown`), jetzt **43 = 1+42** (F42: +6 Felder). Die Reihenfolge ist fest und
wird von `format()` per `check(lines.size == KEYS.size + 1)` erzwungen.
Regeln: ASCII, `key: value`, feste Reihenfolge (deterministisch), **43 Zeilen**
(1 Kopf + 29 Felder; die frühere Obergrenze „≤ 20" ist mit F13b2/F17b entfallen), unbekannte
Werte `-`, **keine** Zeilenumbrüche in Werten (Sanitizing), Kopfzeile mit
`keine inhalte` als sichtbare Zusage. Sanitizing ersetzt `:` in Werten durch `=`
(deshalb `last_error: audioInit=SecurityException`).

## 4. (b) Was NICHT in den Teilen-Text gehört

Transkript/Hypothesen · Audio/Samples/Peak-Kurven · Fehler-`message`/Stacktrace ·
Dateipfade (nur Version-Kurzname) · URLs/Download-Host · Ziel-App-Paket · Gerätename/
Seriennummer · installierte App-Liste · Zwischenablage · Logcat.

## 5. (c) Wo der Knopf herkommt + welche Funktion den Text baut

- **Ort: IME-Statusleiste, nicht die Q-Reihe.** Grund (korrigiert den P1-Plan): die
  Q-Reihe ist mit 15 Layout-Assets + Parser-Allowlist verdrahtet; eine neue Aktion
  `share-debug` würde **alle** Assets + `LAYOUT_REQUIREMENTS` ändern (Regel 4/4a) und
  einen Parser-Test brechen. Die Statusleiste (`ImeRootView.statusText`, `:34/:48/:68`)
  ist reine UI, existiert bereits in jeder Ebene und ist immer erreichbar.
  Umsetzung: kleines `🐞`-`ImageButton` (48 dp Touch-Ziel) **rechts in der Statuszeile**,
  `contentDescription = "Diagnose kopieren/teilen"`.
- **Bau-Funktion (rein JVM, testbar):** `debug/DebugInfoFormatter.kt`
  `fun format(s: DebugSnapshot): String` + `data class DebugSnapshot(...)` (nur
  primitive Felder/Enums). Die Android-Dünnschicht `debug/DebugInfoCollector.kt`
  füllt `DebugSnapshot` (PackageManager, `Build`, `ModelStore`, `DictationCoordinator`)
  und ruft `ClipboardManager` bzw. `Intent.ACTION_SEND` (`text/plain`); ohne Handler →
  Clipboard-Fallback. Kein Netzwerk.
- **8 Unit-Tests (JVM, `DebugInfoFormatterTest`):**
  1. `format_hasAtMost20Lines` · 2. `everyLineMatchesKeyValuePattern`
  (`^[a-z0-9_]+: .*$`) · 3. `containsNoPathsUrlsEmails` (Regex gegen `/`, `http`, `@`, `\`)
  · 4. `versionAndCodeAppearVerbatim` · 5. `unknownValuesRenderAsDash`
  · 6. `valuesWithNewlineOrColonAreSanitized` · 7. `modelStatusAndByteSumMatchInput`
  · 8. `outputIsDeterministic` (zweimal formatieren ⇒ identisch).

## ANNAHME-Zeilen

- `ANNAHME: BuildConfig ist in diesem Modul deaktiviert (kein buildFeatures-Block in`
  `app/build.gradle.kts) → Version über PackageManager.`
- `ANNAHME: Build.SOC_MODEL liefert auf API 36 „Tensor G5"; falls leer, Feld weglassen.`
- `ANNAHME: Der Ziel-App-Paketname ist verzichtbar; die Fehlerklasse (E5) erklärt`
  `„Diktat stumm" hinreichend.`

---

## 6. Die drei Diagnosefelder im Detail (A19, nachgetragen 2026-10-28)

Quelle der Wahrheit ist die Implementierung (`debug/DebugInfoFormatter.kt`,
`debug/LevelPeak.kt`, `debug/DebugInfoCollector.kt`, `audio/AudioRecordSource.kt`),
nicht der Wunschkatalog aus §1.

| Feld | Bedeutung | Wertebereich / Format | Was der Wert im Fehlerfall verrät | Welchen Nutzerbefund er entscheidet |
|---|---|---|---|---|
| `route` | Aufnahme-**Eingangs**weg laut `AudioRouter` (nicht der Ausgang!) — `intern`, `BT-Headset`, `Kabel-Headset` | 3 Kurznamen aus `routeLabel()` (`VoiceKeyboardService.kt:468-472`), Fallback `intern`; `-` wenn kein Router | `BT-Headset` bei einem Nutzer **ohne** Headset ⇒ falsches/verwaistes SCO-Routing (Kommunikationsmikrofon, oft leiser); `Kabel-Headset` ohne Kabel ⇒ Routing hängt | „Diktat stumm/leise trotz Sprechen" — trennt **Mikrofonweg** von **Engine/Commit** |
| `src` | aktiver `MediaRecorder.AudioSource`-Name, heute konstant `VOICE_RECOGNITION` (`AudioRecordSource.kt:42`, genutzt `:93`) | String, derzeit nur ein Wert | dämpfende Geräte-DSP (Noise-Suppression/AEC/Limiter) hängen **am Source** „je nach Gerät" — im Code-Kommentar `:93` ausdrücklich so vermerkt | erlaubt den A/B-Vergleich `VOICE_RECOGNITION` ↔ `MIC` ↔ `UNPROCESSED` **ohne** Logcat: Wert ändert sich sichtbar im Export |
| `peak_dbfs` | Spitzen-**RMS** der Sitzung in dBFS = `20·log10(maxRMS)` (`LevelPeak.kt:41-44`) | `≤ 0,0` mit einer Nachkommastelle; `-` (**null**), wenn nie ein Pegel ankam (Stille/kein Read) | **`≤ −40 dBFS` ⇒ Eingang praktisch taub** (Mikrofon gedämpft, falscher Source, Routing). `−20…−10` = normaler Sprechabstand. `-` = es kam **nie** ein Block an (Aufnahme startete nicht) | **Telefonbefund `peak_pct: 2`**: dBFS macht daraus „−34 dB" — eindeutig zu leise, während unsere Referenz-WAVs bei RMS **0,067–0,095** (≈ −23,5 … −20,4 dBFS) liegen ⇒ **Faktor ~30** Pegelunterschied |
| `peak_pct` (alt) | `percent()` = `(maxRMS·100).toInt()` (`LevelPeak.kt:27`) | `0…100` ganzzahlig | rundet bei leiser Aufnahme auf `0/2/3` und ist dort **nicht mehr auflösend** — genau deshalb wurde `peak_dbfs` ergänzt (`LevelPeak.kt:34-37`: „peak_pct runt bei leiser Aufnahme auf 0..2") | nur noch als **grobe** Zweitmeinung; Entscheidungen über `peak_dbfs` |

**Rechenweg für den Nutzerfall:** `peak_pct: 2` ⇒ `maxRMS ≈ 0,02` ⇒
`20·log10(0,02) = −34,0 dBFS` ⇒ int16-RMS ≈ 655. Referenz-WAVs: RMS 0,067…0,095
⇒ −23,5 … −20,4 dBFS ⇒ int16-RMS ≈ 2 200 … 3 100. **Faktor ≈ 3 in der Amplitude
(≈ 10 dB) bis ≈ 30 in der Leistung** — zu viel für „leise gesprochen", plausibel für
eine gedämpfte Aufnahmekette (`src`/`route`).

## 6a. Decode-Pfad: C2-Arbeitslast + F21-Takt (5 Felder; nachtrag D20/D22)

**Zweck:** diese drei Felder belegen die **C2-Vermutung** — beim Teil-Dekodieren wird
pro Ticker das **gesamte bisherige Audio** neu dekodiert
(`dictation/DictationController.kt` `window()` = `PcmWindow(pcm.copyOf(totalSamples), …)`),
die Arbeit je Ticker wächst daher mit der Aufnahmedauer. Signatur im Export:
`buffer_s` steigt zusammen mit `decode_ms_max`/`decode_ms_p95`. **REGEL 9:** nur Zahlen
und Schlüsselnamen — keine Audio-/Textinhalte, keine Nutzerpfade/URLs.

| Feld | Bedeutung | Einheit | Sonderwerte |
|---|---|---|---|
| `decode_ms_max` | größte Decode-Laufzeit eines `transcribe`-Aufrufs bisher (`decodeMsMax()`) | ms (ganzzahlig) | `0` = nie dekodiert |
| `decode_ms_p95` | p95 der letzten ≤ 64 Decode-Läufe — Ring-Stichprobe, **nicht** die ganze Sitzung (`decodeMsP95()`: sortierte Stichprobe, Index `ceil(0.95·n)−1`) | ms (ganzzahlig) | `0` = nie dekodiert; ein Lauf ⇒ max == p95 |
| `buffer_s` | Pufferlänge beim **letzten** Dekodierlauf — nicht die aktuelle Aufnahmedauer (`bufferSeconds()`); korreliert direkt mit `decode_ms_*` | Sekunden, 1 Nachkommastelle | `0,0` = nie dekodiert |
| `decode_pace_ms` | aktuell geltendes Minimal-Intervall zwischen zwei Partial-Dekoden (`decodePaceMs()`; Regel in `dictation/DecodePace.kt`: Doppelte der letzten Decode-Zeit, geklemmt an `AsrThreadConfig.decodePaceBaseMs`/`MaxMs` = 2000/6000 ms) | ms (ganzzahlig) | `0` = Takt aus (Pacing deaktiviert) |
| `decode_skips` | Anzahl Partial-Läufe, die wegen dieses Takts übersprungen wurden — **kein Fehler, kein `fail()`** (`decodeSkips()`; Reset bei jedem Start) | Anzahl (ganzzahlig) | `0` = nie übersprungen oder Takt aus; wächst nur während Aufnahme |

**Zweck F21:** diese beiden Felder zusammen zeigen, ob die C2-Milderung wirkt — viele
`decode_skips` bei großem `decode_pace_ms` (nahe der Obergrenze 6000) bedeutet: die Engine
kann mit dem wachsenden Puffer nicht mithalten und wird durch den Takt gebremst.

## 7. Abgleich `KEYS` ↔ Doku (Ist-Stand, 42 Felder / 43 Zeilen)

`DebugInfoFormatter.KEYS` (Reihenfolge = Ausgabereihenfolge):

```
app_version, build, api_level, model_status, model_variant, model_version,
model_bytes, last_error, rtf, first_hyp_ms, threads, permissions,
peak_pct, route, src, peak_dbfs,
ctl_state, ctl_failed, dropped, hyp_count, hyp_gap_ms, audio_s, start_ms,
starting, decode_ms_max, decode_ms_p95, buffer_s,
decode_pace_ms, decode_skips, cap_blocks, cap_stall_ms,
abort_reason, hyp_last_buffer_s, mic_rel, status_shown, loud_s,
had_output, output_chars, first_loud_ms,           → 42 Felder (F42-Zubehör unten)
first3s_loud_blocks, first3s_peak_dbfs, fgs_stopped_by
```

### F42 — 6 neue Felder (Stand 0.1.28+, `DebugInfoFormatter.kt:159-160`,
`DebugInfoCollector.kt:44` u. a.; alle nur Zahlen/Codes, Regel 9)
| Feld | Bedeutung |
|---|---|
| `had_output` | 0/1 — mindestens ein Hypothesen-Commit dieser Sitzung (`DebugInfoFormatter.kt:118`) |
| `output_chars` | Brutto-Zeichenzahl der Commits (nur die Zahl, `:122`) |
| `first_loud_ms` | ms nach Sessionstart beim `everLoud`-Latch; `-1` = nie laut (`:126`) |
| `first3s_loud_blocks` | laute Blöcke (RMS ≥ `SILENCE_RMS`) in den ersten 3 s — Startfenster (`:129`) |
| `first3s_peak_dbfs` | Peak-Block-RMS der ersten 3 s in dBFS (`:132-134`) |
| `fgs_stopped_by` | Ursachencode des letzten Mikro-FGS-Stops (F44, `:136`; Werte u. a. `autostop`/`micup`/`hidden`) |

**Sanitizer:** Werte-Zeilen wandern `:`→`=` (`DebugInfoFormatter.kt:250`) — der erste
`: ` nach dem Schlüssel bleibt, spätere Doppelpunkte in Werten (z. B. `mic_rel =
up:1234`) werden zu `=`.

`format()` liefert `HEADER + 42` = **43 Zeilen** (per `check(lines.size == KEYS.size + 1)`
erzwungen; `DebugInfoFormatterTest` prüft ≤ 20).

### Abweichungen, die ich in der Implementierung gefunden habe

1. **Veralteter KDoc-Zähler:** Klassenkommentar und `KEYS`-Kommentar sagen „GENAU 13
   Zeilen" bzw. „gekürzt auf 13 Zeilen" — real sind es **16 Felder / 17 Zeilen**.
   Nur Kommentar, keine Funktionsabweichung (der `check` rechnet korrekt mit `KEYS.size`).
2. **`route` ist der Eingangs-, nicht der Ausgangsweg** und liefert **deutsche
   UI-Labels** (`intern`/`BT-Headset`/`Kabel-Headset`) statt eines Enums
   (`BUILTIN`/`BLUETOOTH_SCO`/`WIRED`). Für Ferndiagnose brauchbar, aber sprachabhängig —
   ein englisches Gerät würde weiterhin deutsche Labels zeigen (die Labels sind im Code
   fest, nicht aus Ressourcen).
3. **`last_error` erscheint mit `=`, nicht `:`** — das Sanitizing ersetzt `:` in Werten
   durch `=` (`AudioInit:SecurityException` → `audioInit=SecurityException`). Das ist
   gewollt (Zeilenformat bleibt eindeutig), muss aber in der Doku so stehen.
4. **§1-Katalog listet Felder, die es (noch) nicht gibt:** `soc_model`, `locale`,
   `layout_id`, `runtime`, `chunk_ms`, `min_audio_s`, `audio_last_s`, `model_files`.
   Sie sind **geplant**, nicht implementiert — wer den Export auswertet, darf sie nicht
   erwarten.
5. **`peak_pct` bleibt als Feld erhalten** (Kompatibilität der Nutzer-Exports), obwohl
   `peak_dbfs` es fachlich ersetzt; beide werden aus **demselben** `LevelPeak.max`
   berechnet, können also nie widersprechen.
6. **Kein `class`/`soc`** im Export (die A14-Idee „device_class") — der Export enthält
   heute nur `api_level`; das ist datenschutzfreundlich, erschwert aber die Zuordnung
   „Telefon vs. Emulator". `peak_dbfs`/`route`/`src` decken den praktischen Bedarf.

## ANNAHME-Zeilen (A19)

- `ANNAHME: Die drei Felder sind mit dem Stand dieses Commits in `KEYS` und `format()`
  verdrahtet; ein Export aus v0.1.6 (7) enthält sie bereits (der 🐞-Befund des Nutzers
  stammt aus einer Fassung ohne `peak_dbfs`).`
- `ANNAHME: `route` liefert bei fehlendem Router `intern` (Fallback im Aufruf
  `VoiceKeyboardService.kt:408`), nicht `-`.`
