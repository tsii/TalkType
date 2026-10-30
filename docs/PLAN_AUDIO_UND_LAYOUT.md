# Plan: echter Mikrofonpfad im Emulator · Layout-Fixes · Debug-Info-Teilen

**Stand:** 2026-10-28 · **Rolle:** Plan (keine Code-/Build-Änderung) · Reihenfolge = Priorität.
Abschnitt 1 ist das Herzstück: die App hat noch **nie** über `AudioRecord` bewiesen, dass sie aufnimmt.

## 1. Emulator mit echtem Mikrofon-Pfad

### 1.1 Entscheidung: PulseAudio-User-Daemon + Null-Sink/Remap-Source, Emulator **ohne** `-no-audio`
`pulseaudio --start` (User-Modus, kein System-Daemon), `module-null-sink` als virtuelles Ziel,
`module-remap-source` vom **Monitor** als Aufnahmequelle, Emulator mit `QEMU_AUDIO_DRV=pa` neu starten
(weiter `-no-window`). **Verworfen:** `-allow-host-audio` (in 37.2.12 keine eigene Semantik),
QEMU-`-audiodev` direkt (kollidiert mit den Emulator-eigenen `-audiodev`-Argumenten),
GUI-Mikrofon (headless nicht vorhanden). Der Null-Sink/Remap-Weg ist der einzige, bei dem
**jedes** Programm deterministisch einspeist (`paplay`) → reproduzierbar, CI-tauglich.

### 1.2 Befehle
```bash
export XDG_RUNTIME_DIR=/tmp/pa-rt; mkdir -p "$XDG_RUNTIME_DIR"; chmod 700 "$XDG_RUNTIME_DIR"
pulseaudio --start --exit-idle-time=-1 --log-target=file:/tmp/pa.log; pactl info
pactl load-module module-null-sink sink_name=vkmic sink_properties=device.description=VKMic
pactl load-module module-remap-source master=vkmic.monitor source_name=vkmic_src \
      source_properties=device.description=VKMicSrc
pactl set-default-source vkmic_src
adb -s <device> emu kill                       # NUR 5560, 5558 ist fremd
QEMU_AUDIO_DRV=pa "$ANDROID_HOME/emulator/emulator" -avd vk-api36 -port 5560 \
      -no-window -no-snapshot -no-boot-anim -gpu swiftshader_indirect &
adb -s <device> wait-for-device
adb -s <device> shell dumpsys media.audio_flinger | grep -i -m5 "input\|source"   # Input da?
paplay -d vkmic testdata/de_smoke.wav               # bekanntes Signal einspeisen
```

### 1.3 Beweise (Testfälle, instrumentiert, App vorher `install -r` + `ime enable/set`)
| # | Testname | Assertion |
|---|---|---|
| M1 | `mic_capture_stateInitialized` | `AudioRecord.state == STATE_INITIALIZED` **nach** `start()` |
| M2 | `mic_readYieldsSamplesWithRms` | `read()` **> 0** Frames, **RMS ≥ 200** (int16 ≈ −44 dBFS), **Peak ≥ 1000** |
| M3 | `mic_realRecordingMatchesGolden` | Mikrofon-Lauf von `de_smoke` gegen §4.1: **Wort-Identität ≥ 95 %**, **CED ≤ 2 %**, kein englischer Output (Toleranz wie R3/R4 — Gast resampelt) |
| M4 | `mic_levelListenerEmits` | `levelListener` liefert während RECORDING **≥ 3** Werte, pegelkorreliert |
| M5 | `mic_stopHasNoRestSamples` | nach `stop()`/EOS **kein** weiterer `read()` (Zähler in Fake-`AudioRecord`) |
| M6 | `mic_permissionDeniedMapsToClassC` | ohne `RECORD_AUDIO`: Klasse (c) „Mikrofon-Berechtigung fehlt", kein Crash, Tippen bleibt möglich |

### 1.4 Code-Verdrahtung als TDD-Aufträge (≤ 4 Dateien je Auftrag)
| # | Test ZUERST | Datei | Erwartung |
|---|---|---|---|
| T1 | `LevelMeterTest` (JVM) | `audio/LevelMeter.kt` (neu) | Peak/RMS aus `ShortArray`; leeres Array = 0; Clipping erkannt |
| T2 | `mic_levelListenerEmits` | `audio/AudioRecordSource.kt` | `levelListener` bei jedem Read mit Peak/RMS aufgerufen |
| T3 | `MicLevelWiringTest` | `ime/VoiceKeyboardService.kt` (**Z.362** `levelListener = null` → echte Zuweisung) | Pegel erreicht Statuszeile; `null`-Setzen entfällt |
| T4 | `AudioRecordFactoryTest` | `audio/AudioRecordSource.kt` | `AudioRecord` über injizierbare Factory (testbar ohne echtes Mikro) |
| T5 | `DebugInfoTest` (JVM) | `debug/DebugInfo.kt` (neu, §3) | Peak/RMS/RTF-Felder befüllbar, keine Inhalte |

### 1.5 Vorab-Checks (je ≤ 2 min)
1. `emulator -help-audio | head -20` — akzeptiert 37.2.12 `pa`? (sonst `-audio auto` + Env weglassen)
2. `pactl load-module module-null-sink …` → Modul-ID > 0 (sonst Rechte/Daemon)
3. Nach Neustart: `dumpsys media.audio_flinger | grep -i input` nicht leer
4. `parec -d vkmic.monitor --raw | wc -c` > 0
5. `uptime`-Load < 2.0 (sonst verschiebt sich alles)

### 1.6 Rückbau (Pflicht für alle anderen Tests)
```bash
adb -s <device> emu kill
pactl unload-module <remap-id>; pactl unload-module <null-sink-id>; pulseaudio --kill
"$ANDROID_HOME/emulator/emulator" -avd vk-api36 -port 5560 -no-window -no-snapshot \
      -no-audio -gpu swiftshader_indirect &
adb -s <device> wait-for-device
adb -s <device> install -r app/build/outputs/apk/debug/app-debug.apk
adb -s <device> shell ime enable de.voicekeyboard/.ime.VoiceKeyboardService
adb -s <device> shell ime set    de.voicekeyboard/.ime.VoiceKeyboardService
# NICHT: pm clear / clearPackageData — Modellbestand (671 MB) bleibt
```

### 1.7 Risiken → Vorab-Check
Headless ohne Audio-Backend (C1+C3, Fallback `-audio auto`) · PulseAudio braucht `XDG_RUNTIME_DIR`
(C2) · Gast-Resampling verfälscht Golden (M3 mit Toleranz, nicht exakt) ·
`connectedDebugAndroidTest` deinstalliert (nach jedem Lauf §1.6) · 5558 verwechseln (**immer** `-s <device>`).

## 2. Tastatur-Layout

### 2.1 Diagnose (`ANNAHME:` bis Messtest)
`KeyboardView.onMeasure` setzt Höhe = `max(240dp, model.heightPx)` — eine **Inhalts**höhe ohne Bezug
auf den verfügbaren Platz; `ImeRootView` ist eine vertikale `LinearLayout` (Status + Keyboard +
Emoji-Block `height=0`/Gewicht 1) → ist die Summe größer als das IME-Fenster, wird **unten**
abgeschnitten (letzte Reihe, Space). Der Rebuild-Guard vergleicht `lastMeasuredHeight != measuredHeight`,
also die **eigene** letzte Messung mit der eigenen → beim **zweiten** Öffnen „gleich", Modell bleibt auf
der Geometrie des ersten Fensters. **Fix:** Höhe aus `min(model.heightPx, verfügbar − Status − Footer)`,
Reihenhöhe aus dem Platz verteilen, Guard auf `(width, availableHeight)`, `requestLayout` bei Insets.

### 2.2 Umzüge/Größen (alle 15 Assets, Touch-Ziel ≥ 48 dp)
| Änderung | Geometrie | Betroffen |
|---|---|---|
| **Backspace oben rechts** | Reihe 2, letzte Position, `width 1.5` | §1.4 + Assets |
| **Enter in alte Backspace-Position** | Reihe 3, letzte Position, `width 1.5` | dito |
| **Große Mic-Taste unten rechts** | unterste Reihe, letzte Position, `width 2.0`, Label `🎤` | dito |
| **Space** | `5.0` → **`3.5`** (Reihensumme bleibt 11.0) | dito |
| **Teilenschaste links oben** | Reihe 1, Position 0, `width 1.0`, `🐞`, `kind:"action"`, **neue** `value:"share-debug"` (Parser-Allowlist!) | §1.4-Aktion |
| Reihenhöhe | 44–48 dp (heute zu hoch), Statuszeile 24 dp | `ImeRootView` |

**Reihenfolge:** (1) `KeyboardFitTest` instrumented — alle Reihen vollständig in der IME-Fläche,
`VK-HIT`-Geometriebeweis bleibt grün; (2) Höhen-Fix; (3) Umzüge + Skript über 15 Assets;
(4) `LAYOUT_REQUIREMENTS` Regel 4a/9 nachziehen; (5) Sichtprüfung (2× öffnen, Feldwechsel).
**Sol-Gegenzeichnung:** Geometrie-Tabelle 2.2 + neue Aktion `share-debug`.

## 3. Debug-Informationen teilen/kopieren

**Felder (≤ 25 Zeilen, kopierfreundlich, keine Inhalte/URLs/Pfade):** App-Version + versionCode ·
Build-Typ · Android-API + Gerätemarke/Modell (`ANNAHME:` Marke ok, Seriennummer nicht) ·
Modellvariante + Status (`READY/BROKEN/ABSENT`) + Dateigrößen in Bytes · letzter Fehler**code**
(nie `message`) · Erstwort-Latenz (ms) · RTF · Threads (`intra_op`) + Hypothesen-Intervall +
`min_audio_s` · Berechtigungen (ja/nein) · freier Speicher (GB) + Modellgröße · Sprache/Layout-ID ·
Runtime-Version (ONNX/LiteRT).
**Format:** `key: value` je Zeile, ASCII, Kopf `# VK-DEBUG v1`, max. 25 Zeilen; **kein** Logcat,
**keine** Stacktraces, **keine** Audio-/Textinhalte. **Ohne Netzwerk:** nur Text erzeugen, dann
`Intent.ACTION_SEND` (`text/plain`) oder Zwischenablage; offline voll funktionsfähig.
**TDD:** D1 `DebugInfoFormatterTest` (JVM: 25-Zeilen-Grenze, Regex-Assertion gegen `/`, `http`, `@`,
deterministische Reihenfolge) · D2 `DebugInfoPrivacyTest` (ein vorhandener Transkripttext taucht
**nicht** auf) · D3 `ShareDebugIntentTest` (instrumented: `ACTION_SEND`/`text/plain`, Clipboard-Fallback
ohne Handler) · D4 `DebugInfoErrorCodeTest` (nur Codes).
**Sol-Gegenzeichnung:** Feldliste (Privacy, Regel 9).

## ANNAHME-Zeilen
- `ANNAHME: 37.2.12 akzeptiert PulseAudio via QEMU_AUDIO_DRV=pa — Vorab-Check 1 klärt es in <2 min.`
- `ANNAHME: Abschneiden = Inhaltshöhe ohne Available-Height-Bezug + selbstreferenzieller Guard;`
  `KeyboardFitTest beweist/widerlegt es.`
- `ANNAHME: §4.1 + Toleranz genügt für M3; keine 10 neuen Sätze nötig.`
