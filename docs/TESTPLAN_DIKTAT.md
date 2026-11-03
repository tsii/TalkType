# Testplan Diktat – verbindliche Sol-Abnahme je Version

**Stand: v0.1.23; zehn Kriterien K01–K10.**

## 1. Zweck, Umfang und Regeln

Regression: Capture endet, Anzeige bleibt „Diktat“, UP zeigt kurz Verarbeitung,
danach „Bereit“, aber weder Text noch dauerhafter Fehler. Dieser Ablauf darf nie still bestehen.
Sol prüft Diktat künftig bei jeder Abnahme; grüne Tastatur-/Layouttests ersetzen es nicht.
Basis ist die Standardengine A, warm geladen, konstante Konfiguration und definierte Audioquelle.
Kaltstart zusätzlich prüfen; Start-/Ladezeit separat messen, nicht in warmen Latenzen verstecken.
Andere Engines erhalten eigene Ergebnisse und dürfen A-Erfolge nicht übernehmen.
Alle nachfolgenden Grenzwerte sind Sollwerte, keine hier neu erbrachten Messungen.
Keine Audioinhalte, Hypothesen oder Transkripte in diesem Plan/Protokoll/Diagnoseexport.
Nur kontrollierte Testsignale bzw. freigegebene Offline-Fixtures; Zieltexte bleiben lokal im Test.
Kein Rohlogcat, Ziel-App-Name, Inhalts-Screenshot oder Audioexport im Abnahmeprotokoll.
Screenshots nur auf Status/Bedienelemente zuschneiden; editorseitig nur Zähler/Urteil erfassen.

## 2. Messvertrag und Statuswahrheit

Zeitmessung monoton; Fake-Uhr für JVM, echte Zeit am Gerät. Session/Engine eindeutig zuordnen.
Bei jeder Session Startwerte zurücksetzen; Vorher-/Nachherwerte nicht aus verschiedenen Läufen mischen.
`status_shown` ist ein von der IME gesetzter Diagnosecode, kein unabhängiger UI-Beweis.
Deshalb immer Controller-/Ereignisspur **und tatsächlich sichtbare TextView** vergleichen.
Der erwartete Code ist eine Zustandsabbildung, nicht rohe Stringgleichheit von IDLE und ready:
- RECORDING und Capture aktiv → `dictat`; FINALIZING → `finalizing`.
- IDLE nach erfolgreichem Abschluss → `ready`; sichtbarer/sticky Fehler → `error`.
- Fehler hat Vorrang vor IDLE; STARTING wird als Start/Laden erkennbar, nicht als aktive Aufnahme.
- Stille mit weiter eintreffenden PCM-Blöcken ist **kein Capture-Stall**.
UI muss jeden Übergang spätestens **500 ms** nach dem Controller-/Fehlerereignis nachvollziehen.
Ein Fehler bleibt nach UP mindestens **5 s ohne neue Nutzeraktion** sichtbar (R-2).
Ein Zeit-/RTF-Zähler allein zählt nicht als erklärender Statuswechsel bei fehlendem Text.
Native Inferenz darf weder UP noch den Capture-Watchdog oder UI-Fehleranzeige blockieren.

## 3. Zehn Akzeptanzkriterien

| ID | Prüffall | Messbares Soll / Fehlerbedingung |
|---|---|---|
| K01 | **30-s-Dauerdiktat**, kontinuierliches kontrolliertes Signal | Capture über 30 s aktiv, `cap_blocks` steigt; im Erfolgsfall mindestens **8 sichtbare Textzunahmen**. Keine Textlücke >**3000 ms** ohne erklärende Verzögerungs-/Fehleranzeige binnen weiterer **500 ms**; ein bloß laufender Timer genügt nicht. Aufnahmestatus und Verzögerung dürfen sich nicht widersprechen. |
| K02 | **2-s-Kurzdiktat**, danach UP | Innerhalb **3000 ms nach UP** mindestens eine erfolgreiche sichtbare Texteingabe **oder** dauerhafter verständlicher Fehler; bei absehbar längerem Finalisieren rechtzeitig Verarbeitung anzeigen und spätestens **5000 ms nach UP** Erfolg/Fehler. Nie leerer Abschluss mit nur `ready`; auch Timeout/Leerhypothese testen. |
| K03 | **Statuswahrheit in jedem Übergang** | Start, Aufnahme, UP, Finalisierung, Fehler, Abbruch und neuer Start gemäß §2; **0** veraltete Anzeigen länger als 500 ms. Nach Session-Ende nie weiter `dictat`; nach Fehler kein reines `ready` ohne Hinweis. Letzte Momentaufnahme reicht nicht. |
| K04 | **Keine Audioblöcke >3000 ms** | Bei 3000 ms noch kein vorzeitiger Stall; nach Überschreitung Watchdog, sichtbarer Fehler und Capture beendet spätestens bei **3500 ms seit letztem positiven Block**. Genau ein Abbruch; gültiger Stall-Code in `abort_reason`. Null-Reads **und dauerhaft blockierten Read** testen; UP ist dafür nicht nötig. |
| K05 | **POST_NOTIFICATIONS fehlt, API ≥33** | Produktpolitik: vor Capture sichtbare Begründung/Einrichtungshinweis binnen **500 ms**; kein heimlicher Aufnahmestart, **0** positive Capture-Blöcke. Nach Gewährung neuer bewusster Start möglich; Verweigerung blockiert normale Tastatureingabe nicht. |
| K06 | **UP während STARTING / wantedStop** | Verzögerten Start und UP vor/während/nach Rückkehr durchspielen: nach erfolgreichem Start genau **1 Stop**, **0 zusätzliche Aborts**, höchstens **1 Finalauftrag**; keine spätere Zombie-Aufnahme. CANCEL separat: genau **1 Abort**, **0 Finals**; Startfehler separat ohne erfundenen Erfolgs-Stop. |
| K07 | **Fokus-/App-/IME-Wechsel beim Diktat** | Definierter Sicherheitsabschluss: **Abort ohne späte Finalausgabe**, Mic spätestens **500 ms** nach Wechsel freigegeben, `abort_reason` kontrolliert gesetzt, z. B. `editor_change`/`ime_destroy`. Vorhandenen Text erhalten, **0** Edits im neuen/verlorenen Editor; Neustart nur bewusst. |
| K08 | **15-Minuten-Dauerlauf Speicher/Decode** | Mindestens **900 s aktive Aufnahme insgesamt**, unveränderte Parameter: wegen KONZEPT-Limit **3×300 s**, kein heimliches 900-s-Einzeldiktat. Jeder Decode inkl. Final **<1000 ms**; kein wachsender Rückstau, kein stilles Session-Ende, kein OOM. Nach gleicher Beruhigungszeit letzter Idle-PSS ≤ erster warmer Idle-PSS + **max(20 MiB,10 %)**; vergleichbares letztes Session-p95 höchstens **10 %** über erstem. |

### Ergänzung v0.1.23 – Diagnosezugang und Signalverlust

| Kriterium | Erwartung / Messgröße | Testart | Rolle |
|---|---|---|---|
| **K09 (i) Statuszeile + 🐞 immer sichtbar** | Im Grundzustand und während Diktat sowie nach erneutem Öffnen existieren **statusText, Mic-Taste und Debug-Taste**, jeweils **VISIBLE**, gemessene Höhe **>0 dp** und tatsächlich sichtbare Fläche **>0**; kein versteckender Parent oder Abschneiden. Debug-Taste bedienbar; Export liefert `status_shown` aus **ready/dictat/finalizing/error**, gemäß K03 passend. **0** verschwundene Diagnoseleisten wie in 0.1.20. | Unit/Robolectric: Root nach Layout messen, Kinder/Parent-Sichtbarkeit/Bounds und Exportcode prüfen; manuell Grundzustand/Diktat/Zweitöffnung sichtbar vergleichen. | Coder automatisiert Root-/Exportregression in CI; **Sol** prüft Sichtbarkeit/Bedienbarkeit am Gerät und protokolliert Soll/Ist. |
| **K10 (j) Stille/Aussetzer sichtbar statt stiller Hänger** | **Fall silence:** positive Blöcke ohne Signal **>3000 ms**, Meldung **„Kein Mikrofon-Signal …“**, **ERR_SILENT/silence**, Session beendet. **Fall stall:** keine positiven Blöcke **>3000 ms**, **ERR_STALL**, sichtbarer Fehler/Session-Ende. Jeweils Ende/Fehler spätestens **3500 ms** nach Beginn des Ausfalls, danach kein `dictat`; Fehler nach UP ≥5 s stabil. Belege **cap_blocks, loud_s, cap_stall_ms, abort_reason**. | Unit mit vorhandener **FakeWavAudioSource** und Fake-Uhr: PCM-Nullsignal von ausbleibenden/0-Read-Blöcken trennen, 2999/3000/3001 ms und Signalrückkehr prüfen; manuell beide Fehlerpfade. | Coder automatisiert beide unabhängigen Watchdogfälle in CI; **Sol** prüft Meldung, Ende und 🐞-Zuordnung am Gerät. |

K10: Positive stille PCM-Blöcke erhöhen `cap_blocks`, aber nicht `loud_s`; ihr `cap_stall_ms` bleibt klein.
Ohne Blöcke bleibt `cap_blocks` konstant und `cap_stall_ms` steigt; das ist kein silence-Fall.
`loud_s` summiert laute Audiodauer, misst nicht die letzte Stillephase: Zählerdeltas plus monotone Uhr nötig.
Die vorhandene Signal-Schwelle verwenden, keine neue Test-only-Schwelle; 2-s-Kurzdiktate bleiben K02 unterstellt.

K01 benötigt echte Editor-Zunahmen; `hyp_count` allein beweist keine Ausgabe/Commitannahme.
K02: Eine fortlaufende Verarbeitungsanzeige rechtfertigt kein unbegrenztes Warten.
K04: Ein Watchdog nur hinter einem nie zurückkehrenden read erfüllt den Blockierfall nicht.
Ohne ersten Block zählt die Frist ab tatsächlichem Capture-Start, nicht ab langsamem Modellladen.
Auch Fake-Uhrstart 0 und nie eintreffenden ersten Block testen; kein Sentinel darf den Stall verbergen.
K05 ist eine bewusste App-Vorgabe; Android macht POST_NOTIFICATIONS nicht generell zur FGS-Startpflicht.
K06 Ereignisse mit Zählern belegen; `mic_rel` hält nur den letzten Code, keine Ereignisanzahl.
K07 passt KONZEPTs Finalisierung beim normalen UP an den verlorenen Android-Editorbesitz an.
K08 beginnt warm nach Modellinitialisierung; gleiche Speicher-Abwartezeit jeweils **30 s**.
K08-Peaks und p95 bei denselben Audio-/Pufferständen vergleichen, nicht kurze gegen lange Buffer.
Ein laufender Decode ≥1000 ms ist bereits Fehler, nicht wegen fehlender fertiger Probe „0 ms“.
Bei Unterbrechung/fehlender Probe Urteil „nicht bewiesen“, nicht „bestanden“.

## 4. Testarten und CI-Matrix – Auftrag an den Coder

U = reine JVM mit Fakes/Uhr; R = Robolectric für Service-/TextView-/Permissionadapter.
I = androidTest mit echtem Editor, Fake-Audio und Fake-/optional echter Engine.
G = physisches GrapheneOS-Gerät mit realem Mikrofon; keine Emulator-Funktionsannahme.
Alle neuen Tests zunächst rot gegen die Regression, dann grün; dieser Plan implementiert nichts.

| ID | U/R: was automatisiert werden muss | I: Integration | Sol/G und CI |
|---|---|---|---|
| K01 | Fake-Audio kontinuierlich, Engine stockt/endet unerwartet; Editzähler und Statusdeadline | WAV-Injektion, echte InputConnection, Zeitstempel erfolgreicher Ausgabe ohne Inhalt | Sol 30 s sprechen, Textwachstum zählen; U/R und Fake-I in CI, echtes Modell nur opt-in gecacht |
| K02 | 2-s-Fake, leer/Erfolg/Exception/niemals fertiges Final; Deadlines | Reale Anzeige/Editor nach UP, kein leerer stiller Erfolg | Sol mehrere kurze Läufe inkl. Kaltstart; CI U/R und Fake-I |
| K03 | Zustandsfolgen inkl. ERROR→UP→IDLE, verspätete Callbacks, neue Generation | Tatsächliche TextView/Capturezustand je Ereignis abgleichen | Sol Statusvideo/Statuscrops; CI U/R und I, nicht nur Settercode testen |
| K04 | Fake-Uhr 2999/3000/3001 ms; Quelle liefert 0 bzw. blockiert; Watchdog unabhängig | Capture-Abbruch/Fehleranzeige trotz laufender Fake-Inferenz | Sol Realpfad/Privacy-/Quellenunterbrechung gesondert; CI U/R und Fake-I, monotone Uhr |
| K05 | API 32/33/aktuell, gewährt/verweigert/dauerhaft verweigert; keine Capture-Factory bei Ablehnung | Permission- und Setup-Wechsel im Emulator, danach echtes erneutes DOWN | Sol auf Ziel-OS bestätigen; CI R/I ohne Mikrofon möglich, Systempolitik nicht nur mocken |
| K06 | Start-Latch blockieren, UP/CANCEL/Fehler in allen Reihenfolgen; Zähler/Generationen | Touch auf Mic während künstlich langsamer Initialisierung | Sol Finger vor Aufnahmebeginn loslassen; CI U/R und I |
| K07 | Editor-/Window-Hide in RECORDING/FINALIZING/STARTING; EditSink lehnt Altgeneration ab | Zwei Host-Editoren/Activities, kein Edit nach Wechsel | Sol echte App-/Screen-off-Wechsel und Mic-Indikator; CI I nur kontrollierte Hosts |
| K08 | Lange virtuelle Sessionfolgen, Ressourcen-/Queuezähler und Timingstatistik korrekt | Opt-in Soak mit WAV/echter Engine; PSS und Gesamt-Decode-Max erfassen | Sol 3 reale 300-s-Sessions; nur dedizierte Geräte-/Performance-CI als belastbarer Zeit-/Thermalbeweis |
| K09 | Root-View mit Layoutdurchlauf: alle drei Elemente/Eltern sichtbar, Höhen/Visible-Rects >0, gültiger Statuscode | Optional zweite Öffnung/aktive Aufnahme gegen echte View-Hierarchie prüfen | Sol sichtbare Leiste und bedienbaren Export prüfen; R in normaler CI |
| K10 | FakeWavAudioSource: positive stille PCM vs. fehlende Blöcke, Fake-Uhr und genau ein Fehler/Abort | Optional gleiche Fehler über Anzeige-/Controlleradapter injizieren | Sol silence/stall samt Export unterscheiden; U in CI, reale Quelle bleibt Gerätebeweis |

Robolectric ist für Zustands-/UIadapter machbar, nicht als Beweis für AudioRecord,
Mikrofontreiber, native ASR-Geschwindigkeit, echte FGS-Lebensdauer oder thermischen Verlauf.
Unbegrenzte Fake-Zeit belegt Logik, keine 15 realen Minuten oder native Speicherfreiheit.
Normale PR-CI: U/R und modellfreie I, keine Großdownloads und kein echtes Mikrofon erforderlich.
Optionaler Modelljob: gecachte, geprüfte Artefakte und bekannte Fixtures, keine Netzpflicht je Lauf.

## 5. Rolle, Beweisfelder und Grenzen

**Coder:** erstellt U/R/I-Tests, monotone Ereigniszähler und nötige kontrollierte Fehler-Injektionen.
**Sol:** führt G-Abnahme je Version durch, vergleicht Soll/Ist, urteilt pro K01–K10.
**Orchestrator:** stellt identische APK/Engine/Parameter, Freigaben und Gerätezugriff bereit.
Sol markiert technische Grenzen als „nicht bewiesen“; kein grünes Urteil aus Unit-Testanzahl.

| Belegfeld | Wofür belastbar / zusätzliche Beobachtung |
|---|---|
| `cap_blocks`, `cap_stall_ms`, `loud_s` | Blockzufluss vs. technischer Signalverlust: positive stille Blöcke zählen, laute Audiodauer bleibt konstant. Stallzeit kann nach Abort weiterlaufen: Auslösezeit separat messen. |
| `mic_rel`, `abort_reason` | Ursache/letztes Endereignis; UP/CANCEL/Fokus/Stall unterscheidbar, keine freien Fehlertexte. Für „genau einmal“ separate Testzähler nötig. |
| `status_shown`, ergänzend `ctl_state`, `ctl_failed`, `starting` | Erwarteter Status; echte TextView separat prüfen, da Export nur zuletzt gesetzten Code hält. |
| `hyp_count`, ergänzend `hyp_gap_ms` | Enginefortschritt, nicht Editor-Commit; Texteingabezähler und Zeit letzter sichtbarer Änderung separat erforderlich. |
| `decode_ms_max`, ergänzend `decode_ms_p95` | Decodekosten des exportierten Statistikfensters. Resets/Ringfenster beachten: Test benötigt zusätzlich laufweiten Maximalwert über alle drei Sessions. |

Start/Ende und bei Abweichung Diagnose-Snapshot nur auf bewusste Aktion erfassen.
Info-Tap/Share kann Fokus verlieren: Zeit-/Statusbeweise zuerst, Export danach;
ein dadurch verursachter Fokusabort darf den ursprünglichen Fehler nicht verschleiern.
Nur letzter Sessionexport genügt weder für Zustandsübergänge noch für 900-s-Maximum.
PSS ist zusätzlicher Laufmesswert, keines der oben genannten Exportfelder ersetzt ihn.

## 6. Abnahmeprotokoll-Vorlage je Version

Kopf: Version/Buildkennung, Engine, API, Geräteklasse, Quelle/Route, Threads, Warm/Kalt,
Freigaben, Datum, Prüfer; keine Seriennummer, Ziel-App-Namen, Inhalte oder Dateipfade.
Urteile: **bestanden / fehlgeschlagen / nicht bewiesen**; offen zählt nicht als grün.

| Kriterium | Erwartung | Messwert | Urteil |
|---|---|---|---|
| K01 30 s | ≥8 Zunahmen; keine unerklärte Lücke >3 s | __ | __ |
| K02 2 s | Text/Fehler fristgerecht; kein stilles ready | __ | __ |
| K03 Status | Zustandsabbildung ≤500 ms, Fehler ≥5 s stabil | __ | __ |
| K04 Stall | >3000 ms erkannt, Ende/Fehler ≤3500 ms | __ | __ |
| K05 Notification | Hinweis ≤500 ms, 0 Capture ohne Freigabe | __ | __ |
| K06 wantedStop | 1 Stop/0 Doppel-Aborts; CANCEL 1 Abort/0 Finals | __ | __ |
| K07 Fokus | Mic frei ≤500 ms, Grund gesetzt, 0 Fremd-Edits | __ | __ |
| K08 Soak | 900 s gesamt, Decode <1000 ms, Speicher/p95 innerhalb Grenze | __ | __ |
| K09 Leiste/🐞 | statusText/Mic/Debug VISIBLE, Höhe/Fläche >0, gültiger status_shown | __ | __ |
| K10 silence/stall | >3 s getrennt erkannt, Fehler/Ende ≤3500 ms, cap_blocks/loud_s/Stallzeit/Grund passend | __ | __ |

## 7. Dauerauftrag und heutige Automatisierungsgrenze

Jede neue Diktat-/Lifecycle-/Statusänderung wiederholt K01–K10; Fehlerrekonstruktion
anhand Export #3 ergänzt diese Tests, ersetzt aber keines der unabhängigen Kriterien.
Alle zehn Logikfälle sind automatisierbar; vollständige reale K01/K02/K04/K05/K07/K08/K10
sind **heute nicht durch normale CI belegt**: Mikrofon/OS/FGS, echter Fokus und native
Leistung/Thermik erfordern bereitgestellte Hardware und passende Messinstrumentierung.
Auch K03/K06 brauchen zur Gerätefreigabe echte UI-/Touchbeweise, ihre Kernlogik ist Fake-testbar.
Kein grundsätzlich unautomatisierbarer Fall: Geräte-I kann künftig auch diese Läufe ausführen.
Dieser Auftrag liefert nur den Plan; keine Tests ausgeführt, keine Implementierung/Build/Git-Arbeit.
