# Eric: Setup-ANR und Modellimport

## Ergebnis / Beweisgrenzen

Debug-Volltest: **989 Tests, 0 Failures, 0 Errors, 0 Skipped**, 124 XML-Suites.
Befehl: `./gradlew :app:testDebugUnitTest --rerun-tasks`.
Erfolgreicher Lauf: `/tmp/eric-full.log` (4m34s), XML: `/tmp/v152_debug/`.
Keine APK gebaut, kein git, kein adb ausgeführt. Version unverändert 0.1.51/51;
die bereits installierte APK enthält diese Änderungen deshalb noch NICHT.

Der gemeldete ANR ist belegt. Seine konkrete historische Ursache ist mit dem
vorhandenen Snapshot **nicht eindeutig beweisbar**: `/tmp/anr_erik.txt:1`
nennt den FocusEvent-Timeout; Zeilen 24–58 zeigen PID 11062 bereits Runnable
in Drawable/Ripple/Choreographer. Der spätere Kernel-Snapshot nennt
`futex_wait_queue`. Keiner davon enthält einen blockierenden App-Aufruf.
Keine Behauptung, dass Ripple oder ein bestimmter Lock den ANR verursacht hat.

## Nachgewiesene blockierende Codepfade und struktureller Fix

Vorher in `SetupActivity.kt`:
- `onResume → refreshUi → render(null) → render(Idle)` erzeugte einen neuen
  `ModelStore` und rief `status()` auf.
- `refreshUi → updateNetInviteCard → inviteState` rief ebenfalls `status(active)` auf.
- `ModelStore.status → verifyInstalled → Sha256.ofFile` liest und hasht jedes
  installierte Modell vollständig. Der Prozess-Cache verhindert diese Prüfung
  in `status()` nicht. Neue Stores wurden außerdem ständig erzeugt.
- `renderModelCards/renderU2Card` führten Pointer-/Dateigrößen-/Verzeichnis-I/O,
  Store-Konstruktor-`mkdirs`, `filesDir.usableSpace` auf Main aus.
- Beide Sprach-/Typing-Sektionen führten `assets.list("wordlists")` aus;
  Preference-Kaltstart konnte auf Main auf XML-Laden warten. Versionslookup
  (`PackageManager.getPackageInfo`, kein vollständiger Paket-Scan) ebenfalls Main.
- Delete löschte rekursiv auf Main. Die Idle-Rekursion war endlich, kein
  nachgewiesener endloser Rekursionsfehler.

Jetzt: `loadStorage()` in `SetupActivity.kt` läuft ausschließlich auf
`vk-setup-io`, mit expliziter Main-Thread-Sperre. Ein Snapshot enthält
`statusQuick` für alle Varianten, Set-IDs, freien Speicher, RAM, Assettext und
Version. Preferences werden dort vorgewärmt; dynamische Sektionen erst nach
dieser Vorarbeit gebaut. Renderpfade verwenden ausschließlich den Snapshot
und vorgewärmte Preferences. Kein vollständiger Modellhash im Setup-Refresh.
Integrity-Hashes bleiben bei Import/Aktivierung im Worker erhalten.
Refreshes werden zusammengefasst und bei Änderungen während eines laufenden
Refreshs nachgeholt. Späte Ergebnisse rendern keine zerstörte Activity.
Delete und Permission-Marker-Schreiben laufen ebenfalls im Hintergrund.

## Entscheidung: b3

Bestehenden Parakeet-Download unverändert behalten; Qwen/U2 **Import-only**,
solange ihre URLs leer sind. Keine öffentliche Quelle mit exakt diesen
Export-Dateien und SHA256 belegt; keine URL erfunden und keine neue
Hosting-Zusage. Insbesondere die lokal erzeugten Qwen/U2-Exports sind nicht
mit einem beliebigen gleichnamigen HuggingFace-Modell austauschbar.
Die vorhandene Parakeet-URL wurde in dieser Arbeit nicht erneut online auditiert.

Qwen/U2 zeigen nun explizit „No download host is configured… Import folder…“
(bei RAM-Warnung bleibt diese Warnung vorrangig); keine Download-Schaltfläche
für die unkonfigurierten Sets. Import funktioniert auch auf kleineren Geräten,
ist aber keine Zusage ausreichenden RAMs für die spätere Inferenz.

## Import und P1-Races

- Neuer `SafTreeImportSource`: DocumentsProvider darf SQL-Selektion ignorieren;
  deshalb Verzeichnis vollständig lesen und **lokal nach Display-Namen filtern**.
  Der alte Adapter konnte schlicht den ersten statt des gewünschten Kindes nehmen.
- Exakter verschachtelter Pfad hat Vorrang. Flacher Dateiname nur bei eindeutiger
  Zuordnung im vollständigen Variantenmanifest; dieses wird als dritter Parameter
  übergeben. Keine rekursive unscharfe Suche. Doppelte Namen/Typfehler sind Fehler.
- Opaque Dokument-IDs, null-Cursor/null-Stream und Zugriffsfehler abgefangen.
  Datei-I/O-Fehler nennen den relativen Manifest-Dateinamen, technische Details
  werden nicht ungefiltert angezeigt. Staging-Erstellung liegt jetzt innerhalb
  der Fehlerbehandlung. Fehlgeschlagener Import lässt das alte Set unangetastet.
- Picker-Ergebnis vor Abschluss der asynchronen Initialisierung bleibt als URI
  **und** Variante gespeichert (auch savedInstanceState) und wird anschließend
  einmal gestartet, statt still verworfen zu werden.
- Delete sperrt Auswahl seines Zielsets sofort; Worker liest die aktive Variante
  erneut. Ein inzwischen ausgewähltes Set wird nicht gelöscht.
- Import-Lock/Executor pro Prozess verhindern parallele Imports nach Rotation;
  eine neu erstellte Setup-Activity bekommt den Abschluss-Refresh.

## TDD / Tests

- Neue Setup-I/O-Regression zunächst rot (fehlende Executor-/Status-Seams),
  danach grün. Paused-Looper beweist: Discovery wird gequeued; Worker allein
  rendert nicht; Main-Idle publiziert Snapshot. Zusätzliche Tests für zerstörte
  Activity und Verhinderung versehentlichen Main-I/O.
- Beide P1-Regressionsfälle zunächst **5 Tests / 2 fehlgeschlagen**:
  `/tmp/eric-races-red.log`; danach **5/0**: `/tmp/eric-races-green.log`.
  Picker-Test prüft frühes Ergebnis samt savedInstanceState. Delete-Test prüft
  Auswahl während queued Delete und externe Pref-Änderung vor Worker-Ausführung.
- Import/Provider: zunächst **19 Tests / 12 fehlgeschlagen**, danach **19/0**
  (`/tmp/import-red.log`, `/tmp/import-green.log`), einschließlich echtem
  Fake-DocumentsProvider unter Robolectric, nicht nur einem ImportSource-Mock.
- Bestehende Setup-Tests warten jetzt explizit auf Worker und Main-Looper.
- Vollsuite abschließend **989/0**, keine Skips, XML `/tmp/v152_debug/`.

Vor jedem Gradle-Aufruf wurde `pgrep` geprüft. Hinweis zur Ausführungstransparenz:
Der erste Red-Compile-Aufruf wurde trotz angezeigtem fremden Wrapper gestartet
(Fehler meiner Startbedingung). Danach ausschließlich bedingter Start bei freiem
Mutex, auch andere Projekte berücksichtigt. Ein erster Volltest-Aufruf wurde
nach 200s vom Tool beendet; der abschließende vollständige Lauf lief mit höherem
Tool-Zeitbudget erfolgreich. Kein Anwendungs-Timeout als ANR-Workaround eingebaut.

## Geänderte Dateien

Produktiv:
- `app/src/main/java/de/voicekeyboard/ime/SetupActivity.kt`
- `app/src/main/java/de/voicekeyboard/ime/SafTreeImportSource.kt` (neu)
- `app/src/main/java/de/voicekeyboard/model/ModelStore.kt`
- `app/src/main/res/values/strings.xml`

Tests:
- `app/src/test/java/de/voicekeyboard/ime/SetupIoRegressionTest.kt` (neu)
- `app/src/test/java/de/voicekeyboard/ime/SetupTestIo.kt` (neu)
- `app/src/test/java/de/voicekeyboard/ime/SafTreeImportSourceTest.kt` (neu)
- `app/src/test/java/de/voicekeyboard/model/ModelStoreImportTest.kt`
- Bestehende Setup-Tests: `SetupActivityVersionTest`, `SetupCorrectionSwitchTest`,
  `SetupImportCardTest`, `SetupLanguageAvailabilityTest`, `SetupModelChoiceTest`,
  `SetupNetworkInviteTest`, `SetupPermissionsButtonTest`, `U2SetupCardTest`.

Dokumentation: diese Datei, `docs/MODEL_IMPORT.md`, `docs/SESSION_LOG.md`.

## Emulator-Verifikation — ausschließlich durch Eric auszuführen

**Nicht hier ausgeführt. Gerätetest bleibt offen.** Die installierte alte APK
reicht nicht: zuerst selbst neue Debug-APK bauen. Nach eigenem Build im Projekt:

```sh
adb -s emulator-5566 install -r app/build/outputs/apk/debug/app-debug.apk
adb -s emulator-5566 shell mkdir -p /sdcard/Download/vk-models
adb -s emulator-5566 push /home/dev/models/qwen-asr-export /sdcard/Download/vk-models/qwen
adb -s emulator-5566 push /home/dev/models/bart-export/en-merged /sdcard/Download/vk-models/u2-en
adb -s emulator-5566 push /home/dev/models/bart-export/de-merged /sdcard/Download/vk-models/u2-de
adb -s emulator-5566 logcat -c
adb -s emulator-5566 shell am force-stop de.voicekeyboard
adb -s emulator-5566 shell am start -W -n de.voicekeyboard/.ime.SetupActivity
```

1. Sofort „GRANT PERMISSIONS“ tippen. Erwartet: Systemdialog oder Toast
   „all granted“, nicht scheinbar toter Tap; scrollen bleibt möglich.
2. Qwen: kein Download, klare Import-Ansage (oder RAM-Warnung), „Import folder…“.
   Picker: Downloads → vk-models → qwen → „Use this folder“ bestätigen.
   **Den Ordner wählen, in dem encoder.int8.onnx direkt liegt**, nicht vk-models.
   Während Import „Importing and checking…“, Setup bleibt bedienbar. Nach allen
   Größen/Hashes „Ready“; „Use this model“/Select wählt Qwen, danach „Active“.
3. U2: Englisch als aktuelle Tastatursprache einstellen, dann u2-en wählen;
   Deutsch für u2-de. Richtige Sprache steht in der U2-Karte. „Ready“ erwartet.
4. Flat-Fallback: u2-en kopieren und shared_weights.bin/tokenizer-Dateien direkt
   in den gewählten Set-Root verschieben. Eindeutige Namen akzeptiert, Hashes
   bleiben Pflicht. Zuvor ggf. das nicht aktive U2-Set löschen.
5. Fehler: separaten Testordner mit fehlender oder verkürzter Datei wählen.
   Erwartet: sichtbarer Toast mit relativem Dateinamen, kein Ready und kein
   Verlust bereits installierter anderer Sets. Nicht die Host-Originale verändern.
6. Während Picker offen ist Activity/Emulator drehen, dann gültigen Ordner wählen:
   Import darf nicht verschwinden. Während Import drehen: kein zweiter Import.
7. Beide ASR-Sets installiert: nicht aktives Set löschen und sofort dessen
   Auswahl versuchen. Löschen sperrt das Ziel; aktives Set bleibt erhalten.
8. Cold start mit installiertem Qwen/Parakeet wiederholen: keine Sekundenpause
   durch Vollhash mehr. Abschlussbeobachtung durch Eric:

```sh
adb -s emulator-5566 logcat -b events -d | grep -E 'am_anr|de.voicekeyboard'
adb -s emulator-5566 logcat -d -s VK-MODEL
```

Erwartet: kein neuer `am_anr` für SetupActivity; Import `result=Ready` bzw.
benannter Fehler-Toast. Bestehende alte Events mit Zeitstempel unterscheiden.
Modellgrößen, vollständige Dateilisten und SHA256 stehen in `docs/MODEL_IMPORT.md`.
Cloud-Provider müssen ACTION_OPEN_DOCUMENT_TREE unterstützen; andernfalls die
Dateien erst lokal kopieren. Kein Gerätetest oder Download-Erfolg vorgetäuscht.
