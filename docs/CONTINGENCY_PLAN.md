# Contingency-Plan (Betriebs-Protokoll für den Orchestrator)

**Stand:** 2026-10-27 · **Grundlage:** 11 protokollierte Incidents (I1–I11) +
verifizierte Harness-Fakten (unten, mit Fundstelle). **Zweck:** Stillstand
vermeiden, Arbeit nicht verlieren, Verifikation nicht aufschieben.
**Geltung:** verbindlich für jeden Orchestrator-Lauf in diesem Projekt.

## 0. Verifizierte Harness-Fakten (Hebel, keine Vermutungen)

| Fakt | Wert / Verhalten | Fundstelle |
|---|---|---|
| Tool-Watchdog | **20 min** pro Tool-Call, via `PI_WEB_TOOL_TIMEOUT_MS` änderbar (ms, `0` = aus) | `pi-web-ui/dist/server/client-state.js` `DEFAULT_TOOL_WATCHDOG_TIMEOUT_MS` |
| Watchdog-Aufschub durch bash | `bash` mit `args.timeout` (Sekunden) setzt den Watchdog auf `timeout + 5 s`, **wenn** das größer ist als die Basis | `client-state.js` `effectiveToolWatchdogMs()` |
| Hard-Abort | Wenn `session.abort()` kein `agent_end` liefert: nach **15 s** `forceResetConversation` → Runtime weg, Konversation von Platte neu | `agent-service.js` `HARD_ABORT_TIMEOUT_MS = 15_000`, `interruptRun()` |
| Dangling-Tool-Reparatur | Force-Reset verwirft das Tool-Result im RAM → `toolCall` ohne `toolResult` → synthetischer Text wird angehängt | `pi-web-ui/dist/server/dangling-tools.js` `DANGLING_TOOL_RESULT_TEXT` |
| Subagent-Limit | **16** gleichzeitig *in der Konversations-Map* lebende Subagenten (`isSubagent`); `stop` **abortet nur**, es **entfernt nicht** → Slot bleibt belegt | `agent-service.js` `MAX_SUBAGENTS = 16`; `stopSubagent` prüft nur `isStreaming/isIdle` |
| Slot-Freigabe | Nur **Dismiss/Entfernen** der Subagent-Konversation (UI/`collectSubagentDescendantIds`) gibt den Slot frei | `subagents.js` Modulkopf „完成即移出 (= dismiss)" |
| Persistente Subagenten | `persist=true` erzeugt eine **normale** Konversation, eigener Deckel **8** pro Projekt | `agent-service.js` `MAX_OPEN_CONVERSATIONS = 8` |
| Kompaktierungs-Checkpoint | Extension schreibt `state/compaction/<sha256(sessionId)[:24]>.latest.json` mit Snapshots von `HANDOFF.md`, `reminders/ALWAYS.md`, `state/progress.md`, `reports/NEXT.md`, `reports/PROGRESS.md`, `state/project.json` — **nur wenn `AGENTS.md` ODER `HANDOFF.md` im Projekt-Root existiert** | `~/.pi/agent/compaction-handoff.mjs` (`FILES`, früher `return`) |
| Ist-Zustand Projekt-Root | **kein** `AGENTS.md`, **kein** `HANDOFF.md`, **kein** `state/`, `reports/`, `reminders/` → Checkpoint wurde nie geschrieben | `ls` in `<workdir>` |
| `schedule_task` | Cron oder Delay (min. **60 s**), one-shot/recurring, liefert den Prompt in **dieselbe** Konversation zurück, rebindet nach Kompaktierung | Tool-Schema |
| `wait_all` | max. **60 s** pro Aufruf (kein Dauerblockieren) | Tool-Schema |
| `conversation_read` | `status` = letzter Tool-Call + letzte Assistant-Text + offene Frage; `files` = erzeugte/geänderte Dateien | Tool-Beschreibung |
| `claim_files` | advisory, first-wins, TTL 30 min, Konflikte werden gemeldet, nie blockierend | Tool-Beschreibung |

> Nicht verifiziert (**Vermutung**): ob das bash-Tool einen eigenen Default-Timeout
> unterhalb des Watchdogs hat. Für den Plan irrelevant, weil `sleep` im Turn
> ohnehin verboten ist (§2.5).

## 1. (a) RCA-Tabelle

| # | Incident | Ursache | Auswirkung | Detektion | Dauerhaft-Vermeidung | Restrisiko |
|---|---|---|---|---|---|---|
| I1 | GLM-Coder bricht nach 21–25 min mit „This operation was aborted"; 2× derselbe Auftrag, 3 Dateien + 1 Instrumented-Test | Auftrag zu groß (Feature + Instrumented-Verifikation); Modell-/Provider-Stream-Abbruch beendet die Session ohne Zwischenstand | **2× ~22 min + Stufe-2-Stillstand** bis zur Zerlegung; alle Arbeit weg | Kein Teilergebnis auf Platte; `conversation_read`-`status` endet mit Abort-Text | Auftrag ≤ 4 Dateien / ≤ 2 Builds / ≤ 10 min (§2.1); Instrumented-Test **nie** im Coder-Brief; Zwischenstand nach jedem Datei-Block auf Platte | Provider-Abbruch kann jeden Lauf treffen |
| I2 | Großauftrag 15 Layout-JSONs + Test, ~20 min, Abbruch ohne Ausgabe | Zu großer Batch (15 Dateien), ein einziger Verifikationslauf am Ende | ~20 min verloren; danach 4 parallele Kleinaufträge 4/4 grün | Keine Ausgabe, keine Dateien | Batch-Grenze 4 Dateien; gleichartige Dateien auf **parallele** Kurzläufe verteilen (§2.3) | 4 parallele Läufe → Slot-/Ressourcendruck |
| I3 | „Subagent limit reached (16 live)"; done/error-Slots werden nicht frei; `stop` hilft nicht; 5 min Warten/Kompaktierung helfen nicht; `spawn` + `delegate_task` blockiert | Limit zählt **lebende Konversationen**, nicht laufende; `stop` entfernt die Konversation nicht | **15–20 min Delegation unmöglich** | Fehlermeldung beim Spawn | Slot-Budget (§2.4): max. 6 gleichzeitig, Rest per `steer` recyceln; nach Übernahme **Dismiss** im UI veranlassen; `persist=false` bevorzugen | Ohne UI-Dismiss bleibt der Slot belegt; Recycling ist der Notausgang |
| I4 | Force-Termination-Meldungen bei 2× `steer` im selben Block, 1× `stop`, 1× `sleep 240` („Command aborted") | Watchdog/Hard-Abort + Dangling-Reparatur; `sleep` blockiert einen Turn im Tool-Call | Unklarheit „Arbeit verloren?" → teils Neu-Delegation, teils Wartezeit | Meldung im Transkript (`dangling-tools.js`) | Entscheidungsbaum §2.6; **nie** `sleep`; `stop`/`steer` einzeln absetzen | Force-Reset kann jeden langen Tool-Call treffen |
| I5 | Session `sa-0fe79…`/`sa-de264…` 30 min „running / (no output yet)", obwohl Edits seit 22:00 auf Platte lagen | `get_result` zeigt Output nur, wenn das Modell Text produziert; Datei-Edits laufen ohne Stream-Text | ~30 min Scheinwartezeit; „hung vs. arbeitend" unklar | `git status`/mtime-Delta vs. `conversation_read(status)` | Liveness wird **am Dateisystem** gemessen, nicht am Output (§2.5.4) | Reine Denkphase ohne Edits bleibt unsichtbar |
| I6 | Kein Wecker; blockierende `sleep` 300–580 s; Turn endet, Job läuft weiter, niemand schaut nach → „Term verpasst", Stillstand bis Nutzer fragt | Kein Wake-Up-Mechanismus genutzt (`schedule_task` nie verwendet) | **Häufigster Stillstand**: geschätzt 6–10 Fälle × 15–30 min = **2–4 h** | Turn-Ende ohne geplanten Wake-Up ist der Fehler selbst | §2.5: `schedule_task` statt `sleep`, Pflicht-Wake-Up vor jedem Turn-Ende mit laufendem Job | Wake-Up feuert erst ab 60 s; kein „sofort"-Signal |
| I7 | „Nur schreiben, kein gradle"; Kompilierung erst ~30 min später → rot (`ABSTENT` statt `ABSENT`) | Write-only-Auftrag ohne Sofort-Verifikation; Verifikation ohne Termin | ~30 min + Stillstand bis Mikro-Fix | Erst der späte Build | **Kopplungsregel**: Schreiben ohne Verifikation ist unzulässig; wenn Gradle-Lock belegt: Verifikation als `schedule_task` in ≤ 5 min terminieren (§2.2) | Fremd-Build kann Verifikation weiter verzögern |
| I8 | Zwei Agenten im selben Verzeichnis, Konflikte ad hoc; `claim_files` nie benutzt | Kein Datei-/Ressourcen-Protokoll; Gradle ist projektweit | Ad-hoc-Konflikte, Debug-Zeit, „kein gradle"-Improvisation (→ I7) | Harness-Hinweis „1 other run in same project directory" | §2.3: `claim_files` vor jedem Multi-Datei-Auftrag; **Gradle = globales Mutex** mit Ankündigung im Turn | Advisory: Claims blocken nichts |
| I9 | Emulator `-no-audio` → Diktat nicht E2E prüfbar; nach jedem Reinstall IME manuell setzen; Gboard deaktivieren; `adb reboot` nötig | Setup-Wissen nicht als Checkliste fixiert | Je 5–15 min, wiederkehrend | Setup-Schritt fehlt in den Notizen | Checkliste §3(iii), einmalig abarbeiten und Ergebnis in `state/progress.md` | Emulator-Instabilität bleibt |
| I10 | Nach Kompaktierung: „HANDOFF.md fehlt / state/progress.md fehlt / reports/NEXT.md fehlt / File-Checkpoint nicht vorhanden" | Checkpoint-Extension **opt-in** über `AGENTS.md`/`HANDOFF.md` im Projekt-Root — beides fehlte | Kontextverlust drohte; nur `docs/PROJECT_STATE.md` rettete | Systemmeldung bei Kompaktierung | §2.7: `HANDOFF.md` + `state/progress.md` + `reports/NEXT.md` **im Projekt-Root** anlegen und bei jedem Meilenstein fortschreiben | Extension schreibt nur Datei-Snapshots, keine Semantik |
| I11 | Referenzdecoder-Bug (PredNet-State bei Blank) → 3/4 Golden-Texte falsch, `min_audio_s`-Messrunde wiederholt | Referenz nicht gegen unabhängige Quelle (istupakov/onnx-asr, NeMo) verifiziert, bevor darauf aufgebaut wurde | Ganze Messrunde + Reviews + 2 Doku-Revisionen | Widerspruch zwischen Doku-Aussagen (2,8-s-Fenster „schlechter") und Reproduktion | §2.8: jede Referenz-Implementierung gegen Fremdimplementierung + 2 Gegenproben validieren, **bevor** abhängige Messungen starten | Externe Referenz kann selbst falsch sein |

**Zeitbilanz der Incidents (Schätzung, aus den Angaben):** I6 2–4 h · I1 ≈ 45 min ·
I2 ≈ 20 min · I7 ≈ 35 min · I5 ≈ 30 min · I3 ≈ 20 min · I9 ≈ 30–60 min gesamt ·
I4 ≈ 15 min · I8 ≈ 10 min · I10 ≈ 5 min (Glück) · I11 ≈ 40 min.
→ **Der größte Hebel ist nicht der Watchdog, sondern der fehlende Wecker (I6).**

## 2. (b) Verbindliches Betriebs-Protokoll

### 2.1 Auftragsgröße (harte Obergrenzen)

| Größe | Grenze | Begründung |
|---|---|---|
| Dateien pro Auftrag | **≤ 4** (3 Produktion + 1 Test), gleichartige Massendateien zählen als **1** *wenn* generiert (z. B. 15 Layout-JSONs per Skript) — sonst ≤ 4 | I1, I2 |
| Geänderte Zeilen | ≤ ~150 | I1 |
| Build-/Verifikationsläufe | **≤ 2** (1× Ziel-Test, max. 1× Retry) | I1, I7 |
| Erwartete Arbeitszeit | ≤ **8–10 min**; Abbruchmarke 15 min | 25 % Puffer zum 20-min-Watchdog |
| Verifikationstiefe | im Auftrag: `./gradlew :app:testDebugUnitTest --tests "<Klasse>"` (oder `test`), **nie** `connectedDebugAndroidTest` | I1 |
| Emulator/instrumented | eigener Auftrag, nur Instrumented-Test, kein Feature-Code | I1 |

### 2.2 Verifikationspflicht (Kopplung Schreiben ↔ Prüfen)

1. **Kein Write-only-Auftrag.** Wer schreibt, verifiziert — oder die Verifikation ist
   als `schedule_task` in ≤ 5 min terminiert (I7).
2. Nach jedem akzeptierten Auftrag prüft der **Orchestrator** selbst mindestens:
   `git status --porcelain -- <Pfade>` (Soll/Ist) **und** ein Build/Test-Kommando.
   „Coder sagt grün" ist kein Nachweis (I7).
3. Ist Gradle durch einen Fremdlauf belegt: Testkommando **einmal** anfordern, bei
   Blockade claimen und in ≤ 5 min nachholen; solange gilt der Auftrag als
   **nicht abgenommen** (Statuszeile in `state/progress.md`: `UNVERIFIZIERT`).

### 2.3 Parallelität (Datei- und Ressourcen-Scopes)

| Ressource | Regel |
|---|---|
| Dateien | Vor jedem Auftrag `claim_files` mit den exakten Pfaden + Grund. Konfliktmeldung ⇒ Auftrag **nicht** starten, Scope teilen. |
| Gradle | **globales Mutex**: genau ein Gradle-Lauf projektweit. Vor Start im Turn ankündigen (`Gradle-Lock: ich, Dauer ~Xs`), danach freigeben. Kein zweiter Agent parallel. |
| Emulator/adb | exklusiv; ebenfalls im Turn ankündigen. Kein `assembleDebugAndroidTest` parallel zu `connectedDebugAndroidTest`. |
| ASR-Modelle | Modellläufe (0,9 GB RSS) nie parallel zum Emulator. |
| Parallele Subagenten | max. **4** gleichzeitig (nicht 16), max. 2 im selben Paket |

### 2.4 Slot-Management (I3)

1. **Budget:** ≤ **6** lebende Subagenten gleichzeitig (Reserve für Retries).
2. **Nach Übernahme:** Ergebnis in `state/progress.md` festhalten → Session per
   **Dismiss im UI** entfernen lassen (dem Nutzer aktiv sagen: „bitte fertige
   Subagent-Konversationen schließen"), sonst bleibt der Slot belegt (Code schließt
   Dismiss nur über die Konversations-Entfernung frei, `stop` abortet lediglich).
3. **Recycling statt Spawn** ist der anerkannte Notausgang: `steer` auf eine
   `done`-Session mit neuem Brief (funktioniert, I3-Workaround) — aber **eine
   Carrier-Session pro Modell**, nicht beliebig viele.
4. Bei „Subagent limit reached": **sofort** (a) laufende `wait_all(60)` bis Ende,
   (b) Dismiss der fertigen Sessions anfordern, (c) bis dahin Arbeit selbst machen —
   nicht 5 min warten und nicht kompaktieren (beides nachweislich wirkungslos).

### 2.5 Poll-/Wake-Up-Strategie (I5, I6) — ersetzt `sleep` vollständig

**Grundsatz:** Ein Turn darf **nie** mit `sleep` warten. Warten = Turn beenden +
`schedule_task`. Der Nutzer sieht dann einen Zwischenstand statt eines Hängers.

| Situation | Aktion |
|---|---|
| Job gestartet, erwartete Dauer ≤ 2 min | max. **2×** `wait_all(timeoutSeconds=60)` im Turn (≈ 120 s), dann weiter mit 2.5 |
| Job gestartet, erwartete Dauer > 2 min | Turn beenden mit `schedule_task(schedule="in 4m", prompt="Check: <runId>, kannary §4 prüfen, Ergebnis in state/progress.md")` |
| Riskanter Job (Gradle/Emulator, > 10 min erwartet) | Wake-Up in **3 min**, danach recurring `*/4 * * * *` bis Abschluss |
| Laufender Job + Turnende | **Pflicht**-Wake-Up setzen (nie ohne) + Statuszeile an den Nutzer |
| Wake-Up feuert, Job läuft noch | Liveness-Check (2.5.4), dann neues Wake-Up in 4 min |
| Job fertig | Wake-Up canceln (`schedule_cancel`), Ergebnis verifizieren, `state/progress.md` fortschreiben |

**2.5.4 Liveness wird am Dateisystem gemessen, nicht am Output (I5):**
`conversation_read(id=<runId>, action=read)` → `status` (letzter Tool-Call/Text) und
`files` (geänderte Dateien); zusätzlich `git status --porcelain` + `find <scope> -newer <marker>`.
Kriterium „arbeitet": innerhalb der letzten **6 min** eine neue Datei-mtime **oder**
ein neuer Tool-Call. Sonst „hängt" → §2.6.

### 2.6 Force-Termination: Entscheidungsbaum (I4)

Sobald die Meldung „force-terminated / synthetic result" erscheint:

```
1. NICHTS neu delegieren.
2. get_result(runId)
   ├─ "still running"  → Liveness-Check (2.5.4): Datei-Delta?
   │     ├─ ja  → weiterlaufen lassen, Wake-Up 4 min, Status an Nutzer
   │     └─ nein→ Kanarienvogel §4 auslösen → stop(runId)
   └─ "done"/"error"   → Schritt 3
3. Soll/Ist am Dateisystem: git status --porcelain (nur erwartete Pfade?)
   + Stichprobe der geforderten Symbole (rg "class X|fun y")
   ├─ vollständig → Schritt 4
   └─ unvollständig → steer(runId, "repariere/vervollständige nur X") ; kein neuer spawn
4. Verifizieren (selbst): ./gradlew … --tests "…"  → grün?
   ├─ grün → übernehmen, state/progress.md, Wake-Up canceln, Slot dismissen
   └─ rot  → Mikro-Fix-Auftrag (≤ 2 Dateien, 1 Build)
```
**Merksatz:** Die Meldung ist ein *Transkript*-Defekt, kein Beweis über die Arbeit.
Ein `stop`/`steer` immer **einzeln** pro Tool-Block absetzen (I4a).

### 2.7 Vor JEDER Kompaktierung (I10) — Datei-Checkpoint

Pflichtdateien **im Projekt-Root** (`<workdir>/`), weil die Extension
sie nur dort findet (`compaction-handoff.mjs`, `FILES`):

| Datei | Pflicht | Inhalt (max. ~40 Zeilen) |
|---|---|---|
| `HANDOFF.md` | **ja — aktiviert den Checkpoint überhaupt** | aktueller Auftrag, nächster konkreter Schritt, offene Fragen, Fallstricke |
| `state/progress.md` | ja | Meilenstein-Tabelle mit Status + **Verifikationsstand** (grün/rot/UNVERIFIZIERT) |
| `reports/NEXT.md` | ja | die nächsten 3–5 Aufgaben in Reihenfolge |
| `reports/PROGRESS.md` | optional | Ist-Stand je Modul |
| `state/project.json` | optional | maschinenlesbare Kurzfassung (Phase, aktive Jobs, Locks) |
| `reminders/ALWAYS.md` | optional | nicht verhandelbare Regeln (z. B. „kein sleep") |

Ablauf: `compact_context` **proaktiv** aufrufen (mit `focus`), **nachdem**
HANDOFF/progress/NEXT geschrieben sind — nicht erst, wenn das System zwangsweise
kompaktiert. Vor jedem Turn-Ende mit laufendem Job: `HANDOFF.md`-Zeile „laufend:
<runId> <was> <seit wann>" ergänzen.

### 2.8 Referenz-/Gate-Regel (I11)

Bevor eine Messung/Entscheidung auf einer selbstgebauten Referenz basiert:
(a) fremde Referenzimplementierung benennen (Repo/Funktion), (b) **zwei**
Gegenproben mit erwartbarem Ergebnis definieren (eine muss fehlschlagen), (c) erst
dann Messreihen starten. Ergebnis der Validierung in `state/progress.md` notieren.

### 2.9 Stillstand aktiv melden (statt schweigen)

**Regel:** Endet ein Turn, während irgendein Job läuft, ist die Antwort **unvollständig**,
wenn nicht (1) ein `schedule_task` gesetzt ist **und** (2) der Nutzer eine Statuszeile
bekommt:

> **Status:** `<Job>` läuft seit `<mm:ss>`; letzte Dateiänderung `<mm:ss>`; nächste
> Nachschau `<HH:MM>` (automatisch). Ich melde mich mit Ergebnis oder Abbruch.

Zusätzlich bei jeder Verzögerung > 5 min ohne Fortschritt: Zwischenmeldung mit
Grund + Optionen (weiter warten / abbrechen / selbst übernehmen).

## 3. (c) Templates

### (i) Coder-Auftragsbrief (kopierfertig)

```markdown
AUFTRAG: <ein Satz, ein Deliverable>
SCOPE (genau diese Dateien, keine anderen):
  - <pfad/neu.kt>
  - <pfad/test.kt>
ZEITBUDGET: 10 min Arbeit / harte Abbruchmarke 15 min. Bei Überschreitung: stoppen und
  Zwischenstand melden (was fertig ist, was fehlt).
VERIFIKATION (genau 2 Läufe, in dieser Reihenfolge):
  1) ./gradlew :app:testDebugUnitTest --tests "*<Klasse>*"
  2) nur bei Fehlschlag: derselbe Lauf erneut, max. 1×
  VERBOTEN: connectedDebugAndroidTest, assembleDebugAndroidTest, clean, daemon-Neustart,
  Emulator, adb, Netzwerk-Downloads.
MUST DO:
  - <Regel 1, prüfbar>
  - <Regel 2, prüfbar>
  - Nach jeder Datei speichern (Teilergebnis muss auf Platte liegen, auch bei Abbruch).
MUST NOT DO:
  - Keine anderen Dateien anfassen. Keine Refactorings „nebenbei". Keine neuen Dependencies.
  - Keine Tests löschen/abschwächen.
OUTPUT: 5 Zeilen — geänderte Dateien, Testergebnis (exakt), offene Punkte, Risiken.
```

### (ii) `HANDOFF.md` + `state/progress.md` (Vorlagen)

```markdown
<!-- HANDOFF.md — max. 40 Zeilen, wird bei Kompaktierung gesnapshottet -->
## Aktueller Auftrag
<runId> · <was> · gestartet <HH:MM> · Scope: <pfade>
## Nächster konkreter Schritt
1. <Befehl/Datei/Entscheidung — so konkret, dass er ohne Kontext ausführbar ist>
## Offene Entscheidungen
- <Frage> → Optionen A/B, Empfehlung, blockiert <was>
## Fallstricke (aus Incidents)
- kein sleep im Turn · Gradle = globales Mutex · Instrumented-Test nie im Coder-Brief
```

```markdown
<!-- state/progress.md -->
| Meilenstein | Status | Verifikation | Beleg |
|---|---|---|---|
| M2a Commit/LocalAgreement | done | `test` grün 21:12 | commit=abc123 |
| M2b Editor-Delta | UNVERIFIZIERT | Build blockiert (Gradle-Lock) | — |
| M3 Layouts 15/15 | done | `LayoutCompletenessTest` grün | 15 Assets |
## Laufende Jobs
- <runId> <was> seit <HH:MM>, letzte Dateiänderung <HH:MM>, Wake-Up <HH:MM>
```

### (iii) Emulator-Checkliste (I9) — nach jedem Reinstall in dieser Reihenfolge

```bash
# 1) Emulator (nur für WAV-/UI-Tests; ohne -no-audio ist Diktat-E2E NICHT prüfbar)
$ANDROID_HOME/emulator/emulator -avd <avd> -no-snapshot -no-audio &
adb wait-for-device
# 2) Fremd-IMEs deaktivieren (sonst klaut Gboard/Google-Voice den Fokus)
adb shell ime list -s                       # zeigt aktive IMEs
adb shell ime disable com.google.android.inputmethod.latin
adb shell ime disable com.google.android.googlequicksearchbox
# 3) Eigene IME installieren und SETZEN (nach jedem Reinstall nötig!)
./gradlew :app:installDebug
adb shell ime enable de.voicekeyboard/.ime.VoiceKeyboardService
adb shell ime set    de.voicekeyboard/.ime.VoiceKeyboardService
adb shell settings get secure default_input_method     # muss de.voicekeyboard/… sein
# 4) Nur wenn die IME trotzdem nicht greift (bekannt aus I9): reboot
adb reboot && adb wait-for-device
# 5) Diktat-E2E: nur mit Audio möglich. Ohne -no-audio: Mic-Route prüfen.
#    Mit -no-audio: ausschließlich WavAudioSource-Injektion, Ergebnis als „nicht E2E" markieren.
```

Dokumentationspflicht: Ergebnis (welche Schritte nötig waren) in
`state/progress.md` unter „Gerät".

## 4. (d) Kanarienvögel — 5 harte Abbruchschwellen

| # | Schwellwert | Messung | Aktion |
|---|---|---|---|
| K1 | Tool-Call läuft **> 15 min** ohne Rückkehr (Watchdog bei 20 min) | Zeitstempel des Tool-Blocks vs. jetzt | `stop` + Entscheidungsbaum §2.6 |
| K2 | **> 6 min** keine Datei-mtime-Änderung im deklarierten Scope **und** kein neuer Tool-Call | `find <scope> -newer <marker>` + `conversation_read(status)` | `steer` mit „Status + nächster Schritt in 60 s", sonst `stop` |
| K3 | **> 2 Gradle-Läufe** im Auftrag **oder** ein Lauf **> 5 min** | Transkript/Build-Zeit | sofort `stop`, Auftrag halbieren |
| K4 | **> 60 %** des Zeitbudgets verbraucht und Verifikation **nicht begonnen** | Startzeit vs. jetzt | `stop`, Scope halbieren (I1/I7) |
| K5 | Datei-Set verlässt die vereinbarte Liste (**Fremdpfade** in `git status`) **oder** > 4 Dateien geändert | `git status --porcelain` | `stop` + `steer` „nur X/Y/Z" (I2/I8) |

Zusatz-Guard (kein Kanarienvogel, sondern Vorab-Prüfung): `subagent list` zeigt
**> 10** lebende Slots → keine neuen Spawns, erst Dismiss/Recycling (§2.4).

## 5. Priorisierung: die 3 Maßnahmen mit dem größten Zeitgewinn

1. **Wecker statt `sleep` (§2.5 + §2.9) — I6, ~2–4 h.**
   I6 war laut Protokoll der *häufigste* Stillstandsgrund: Turn endete, Job lief,
   niemand sah nach, bis der Nutzer fragte. Jeder verpasste Term kostete 15–30 min
   reine Totzeit. `schedule_task` (Delay ≥ 60 s, recurring) + Pflicht-Statuszeile
   beseitigt die Klasse vollständig und kostet 10 s pro Turn-Ende.
2. **Auftragsschnitt + Kopplung Schreiben↔Verifizieren (§2.1/§2.2) — I1, I2, I7, ~1,5 h.**
   I1 verbrannte denselben 22-min-Auftrag **zweimal** und hielt Stufe 2 an, bis
   zerlegt wurde; I2 verlor 20 min am 15-Dateien-Batch; I7 kostete 30 min Stillstand
   plus einen roten Build wegen zwei Tippfehlern. Die 4-Dateien/2-Builds-Regel plus
   sofortige Verifikation adressiert alle drei mit einer Regel.
3. **Slot-Recycling + Liveness am Dateisystem (§2.4/§2.5.4) — I3, I5, ~50 min.**
   I3 blockierte Delegation 15–20 min komplett (und die naheliegenden Gegenmittel
   `stop`, Warten, Kompaktieren helfen nachweislich nicht, weil der Slot erst beim
   Dismiss frei wird); I5 erzeugte 30 min Scheinwartezeit auf „(no output yet)",
   obwohl die Dateien längst geschrieben waren. Beides ist mit einem Blick auf
   `git status`/`files` in 20 s entscheidbar.

Danach (kleiner, aber billig): I10-Checkpoint anlegen (**5 min Einmalaufwand,
verhindert Kontextverlust**), I8-`claim_files` + Gradle-Mutex, I9-Checkliste,
I11-Referenz-Gate.

---

## 12. Harness-Abbrüche & blockierte Delegierungen (Nachtrag 2026-10-28, Incident I12)

**Fall I12 (belegt):** `subagent(steer)` für M8a um 01:47 UTC lieferte statt eines
Ergebnisses die Force-Termination-Meldung („tool timeout / hung model stream",
synthetisches Result). Die Ziel-Session lief weiter, schrieb `KeyboardView.kt` +
`tools/normalize_symbol_shift.py` (01:47) und alle 15 Layout-Assets (01:48), endete
dann aber mit einer **Rückfrage** („Ich frage kurz nach:"). Es wurde kein
`schedule_task` gesetzt; der letzte Wecker war 01:32 abgearbeitet. Ergebnis: 6 h
Stillstand bis zum manuellen `get_result` um 07:46.

**Wurzelursachen (drei, unabhängig):** (1) Force-Reset hat den Turn des Orchestrators
verworfen — der geplante Nachschau-Schritt existierte nur im RAM; (2) kein Wecker
gesetzt ⇒ niemand schaut nach; (3) der Auftrag erlaubte eine Rückfrage ⇒ die
Coder-Session parkte **wartend** (kein Rechenfortschritt, kein Timeout).

### R12.1 — Wecker ist Teil der Delegierung (Pflicht, keine Ausnahme)

**Kein Delegieren ohne Wecker.** Vor **jedem** `steer`/`delegate_task`/`spawn`:
(1) `schedule_list` aufrufen (siehe R12.9), (2) `schedule_task` mit
**Frist ≤ 6 min** setzen, Prompt = „Check <runId>: §4-Kanarien, `git status`-Delta,
letzter Output; Ergebnis in `state/progress.md`; bei Stillstand §2.6-Entscheidungsbaum".
Der Wecker muss auch dann auslösen, wenn der **aktuelle Turn stirbt** (er ist ein
persistenter Scheduler-Job, kein Turn-Konstrukt). Ein Turn, der mit laufendem Job
endet und **keinen** Wecker hat, ist ein Regelbruch (§2.9).
Nach Eingang des Ergebnisses: `schedule_cancel`.
Fristen: Coder-Auftrag **≤ 6 min**, Instrumented/Gradle **10 min** (bei Bedarf
zweiter Wecker), Recherche **≤ 6 min** — lieber zu früh nachsehen als 6 h warten.

### R12.2 — Aufträge enden nie offen (Rückfrage-Verbot)

Jeder Coder-Brief enthält die Zeile:
> „Triff bei Unklarheiten selbst eine Entscheidung nach den Regeln in
> `docs/LAYOUT_REQUIREMENTS.md` / `docs/SETUP_UI_DECISIONS.md`; dokumentiere die
> Annahme in deiner Schlussmeldung. **Stelle keine Rückfrage** — eine Rückfrage
> blockiert den Lauf und wird als Abbruch gewertet."

Bekannte Mehrdeutigkeiten werden **vor** dem Delegieren im Brief aufgelöst
(Pfade, Erwartungslisten, Verbote, Zielzustand). Was der Auftraggeber nicht vorab
entscheiden kann, wird als „entscheide selbst, melde Abweichung" delegiert.

### R12.3 — Lücken-Regel (was tut der Coder, wenn der Auftrag nicht reicht?)

Findet der Coder eine Lücke, die den Auftrag sprengt (strukturelle Änderung,
fehlende Taste, fehlende Datei, widersprüchliche Regel), gilt **nicht** fragen,
sondern:
1. die **Teilmenge** fertigstellen, die der Auftrag sicher abdeckt,
2. die Lücke als eigene Zeile `BLOCKER: <was> — <warum> — <vorgeschlagene Regel>`
   in die Schlussmeldung schreiben,
3. nichts außerhalb des Scopes ändern.
Der Orchestrator entscheidet danach in einem **neuen, kleinen** Auftrag.

### R12.4 — Scope-Komplexität pro Delegierung

| Größe | Grenze |
|---|---|
| Dateien (Produktion) | ≤ 3, dazu ≤ 1 Skript und ≤ 1 Testdatei |
| Massen-Assets (z. B. 15 Layouts) | nur per Skript; Skript muss **Dry-Run-Report** (was würde geändert) und Idempotenz haben |
| Erwartungsliste im Brief | ≤ 6 prüfbare Punkte; mehr = Auftrag teilen |
| Verifikationsläufe | ≤ 2 (1× Ziel-Test, max. 1 Retry) |
| Zeitbudget | ≤ 10 min Arbeit, Abbruchmarke 15 min |

### R12.5 — Regelfolge bei „synthetic result" (konkret für diesen Fall)

1. **Nicht** neu delegieren, **nicht** Auftrag wiederholen.
2. `subagent list` + `get_result(<runId>)` → Status und letzter Output.
3. Dateisystem-Soll/Ist: `git status --porcelain`, `ls -l --time-style=+%H:%M <scope>`
   gegen die im Brief genannten Pfade; erwartete Dateien vorhanden? mtime plausibel?
4. Erst **dann** selbst verifizieren (`./gradlew … --tests …`).
5. Entscheidung: **übernehmen** / `steer` (Lücke schließen, gleiche Session) / `stop`.
6. Ergebnis + Wake-Up-Status in `state/progress.md`, Wecker canceln.

### R12.6 — Blockierte Session erkennen und entblocken

**Signal:** `get_result` meldet `running`, aber (a) letzter Assistant-Text endet mit
einer Frage (`?`, „Ich frage kurz nach", „Soll ich …") **und** (b) seit > 5 min kein
neuer Tool-Call und keine Datei-mtime-Änderung im Scope. Das ist **kein Rechnen**,
sondern Warten auf eine Antwort — Warten kann beliebig lange dauern (kein Timeout).
**Reaktion:** `steer` mit der **Antwort** auf die Frage als knappen Auftrag
(„Entscheide: X. Setze nur den Test um. Keine Rückfrage."); bei fehlender
Entscheidungsgrundlage `stop` und Auftrag neu schneiden.

### R12.7 — Pfade nie aus dem Gedächtnis

Dateipfade im Brief werden vor dem Absenden mit `rg --files | grep <name>`
verifiziert. Ein falscher Pfad im Auftrag (hier: `keyboard/LayoutCompletenessTest.kt`
statt `layouts/LayoutCompletenessTest.kt`) ist ein **Orchestrator-Fehler** und
erzeugt beim Coder entweder eine Rückfrage (R12.2) oder eine falsche Datei.

### R12.8 — M8a-Nachtrag (Produktregel, bis zur Übernahme in `LAYOUT_REQUIREMENTS.md`)

**Befund (gemessen, reproduzierbar):** In `symbols` haben alle 15 Assets eine
⇧-Taste mit `value:"shift"` (die App behandelt `SHIFT` in `symbols`/`symbolsShift`
als Ebenen-Toggle, `KeyboardView.handleAction`). In `symbolsShift` fehlt diese Taste
in **5** Layouts: `cs-cz`, `pl-pl`, `ru-ru`, `sv-se`, `tr-tr` (die Orchestrator-Angabe
„8 von 15" ist mit dieser Messung **nicht** reproduzierbar; die 5 sind es:
`for f in app/src/main/assets/layouts/*.json; do grep -c '"value": "shift"' $f; done`
bzw. das Python-Scan-Skript). In diesen 5 trägt der obere linke Key die ID `shift`,
das Label `?123` und den Wert `layer-base` — also führt er in die **Buchstabenebene**
statt zurück nach `symbols`.

**Entscheidung (verbindlich): Variante A — Rückweg in `symbolsShift` ergänzen.**
Kein Umbau der Symbolebenen (Variante B), kein bewusstes Dead-End (Variante C:
verstößt gegen KONZEPT „vollständige Tastatur in 15 Sprachen").

**Regeltext für den Coder (ohne Rückfrage umsetzbar):**
1. In **jedem** Asset muss `symbolsShift` genau eine Taste mit `id:"shift"`,
   `kind:"action"`, `value:"shift"` und `label:"⇧"` enthalten — an derselben
   Position wie in den 10 bereits korrekten Layouts (obere linke Ecke, `width:1.5`).
2. Wo diese Taste heute `label:"?123"` / `value:"layer-base"` trägt (die 5 Fälle),
   werden **nur** diese beiden Felder geändert (`label`→`⇧`, `value`→`shift`); `id`,
   `width`, Position und alle anderen Tasten bleiben unverändert.
3. Ein `ABC`-Key (`value:"layer-base"`, `label:"ABC"`) in `symbolsShift` bleibt
   **zusätzlich** erhalten (direkter Weg in die Buchstabenebene, unabhängig vom
   Toggle-Zustand).
4. `symbols` behält seine ⇧-Taste mit `value:"shift"`; es wird **keine** zweite
   ⇧/⇩-Beschriftung eingeführt (die Bedeutung ⇧/⇩ ergibt sich aus der Ebene).
5. Der Layout-Test (`LayoutCompletenessTest.checkRequiredActionKeys`) muss die
   **Lücke schließen**: für die Ebenen `symbols` **und** `symbolsShift` gilt
   zusätzlich „genau 1× `value == "shift"`" (heute akzeptiert der Test
   `layer-symbols` **oder** `layer-base` und lässt damit ein Dead-End durch).
   Gegenprobe: ein Asset ohne die ⇧-Taste in `symbolsShift` muss **rot** werden.

### R12.9 — Wecker-Lage VOR jeder Delegierung prüfen (`schedule_list` ist Pflicht)

**Befund (07:47 UTC, `schedule_list`):** `task-b0pdlx` („M7-Endreview prüfen",
every 300 s, one-shot, Ziel `reports→c8`) stand **6 h überfällig** mit
`next 01:46:09` und Status **running**; `task-192pn2` (cron `*/5`, anderes Projekt,
`reports→c2`) ebenfalls mit Termin in der Vergangenheit. Beide wurden nie ausgeliefert.

**Mechanismus (verifiziert im Scheduler-Code, `pi-web-ui/dist/server/scheduler-tasks.js`):**
- `fire()` macht `running.add(id)` → `await executor(task)` → `running.delete(id)` →
  erst danach wird `next` neu berechnet (`recordRun`).
- Der Executor ruft `wakeConversation(...)` → `steerForPlugins` →
  `sendUserMessage(text, { deliverAs: "steer" })` und **wartet darauf**.
- Endet der laufende Turn der Ziel-Konversation nie (hängender Stream / abgebrochene
  Session ohne `agent_end`), **resolved der Wake nie** ⇒ `running` bleibt gesetzt ⇒
  der Job ist für immer „running", `next` bleibt in der Vergangenheit, keine
  Wiederholung, keine Zustellung.
- **Präzisierung der These:** Es gibt *keine* globale „Zustellungsschlange dieser
  Konversation". Blockiert ist der **einzelne Job** (sein `running`-Flag). `tick()`
  läuft als eigener Timer weiter (`setInterval 10 s`); andere Jobs werden dadurch nur
  um maximal einen Tick verzögert, nicht ausgehungert. Zwei gleichzeitig hängende
  Jobs (verschiedene Projekte) bedeuten **zwei** hängende Wakes, nicht eine Queue.

**Pflicht (vor jeder Delegierung, 20 s):**
1. `schedule_list` aufrufen und auf **überfällige** Jobs prüfen:
   `next` in der Vergangenheit **oder** `running` seit > 2 Intervallen **oder**
   `one-shot` mit `next` < jetzt.
2. Jeden solchen Job **aktiv ersetzen**: `schedule_cancel(id)` und einen **neuen**
   Verifikations-Wecker mit frischer Frist (≤ 6 min) anlegen. Nicht warten — ein
   hängender Job heilt sich nicht (kein Timeout im Scheduler).
3. Erst danach delegieren. Ein überfälliger Job im `schedule_list` ist ein
   **Abbruch-Signal** dafür, dass in dieser Konversation ein Turn starb.

### R12.10 — Reihenfolge NACH jeder Rückkehr aus einem Abbruch (verbindlich)

1. `schedule_list` → überfällige one-shots canceln und **neu** anlegen (R12.9);
   erst damit ist die Nachschau gesichert.
2. `subagent list` + `get_result(<runId>)` → Status, letzter Output (Frage-Muster? R12.6).
3. Dateisystem-Soll/Ist: `git status --porcelain`, `ls -l --time-style=+%H:%M <scope>`,
   erwartete Symbole per `rg`.
4. **Selbstverifikation** (`./gradlew … --tests …`) — „Coder sagt grün" zählt nicht.
5. Erst **dann** `steer` (Lücke schließen) oder `stop` (Auftrag neu schneiden).
6. Ergebnis + Wecker-Status in `state/progress.md`.

**Begründung:** Im Fall I12 lag der Schaden nicht in der Fehlermeldung, sondern im
Zeitfenster **01:48 → 07:46 = 5 h 58 min** ohne einen einzigen ausgelösten Wecker.
Der Nutzer musste manuell eingreifen. Genau dieses Fenster verhindert R12.1 (Frist
≤ 6 min) + R12.9/R12.10 (überfällige Jobs aktiv ersetzen).

### R12.11 — Rückfrage-Klausel war Mitursache (Auftragsformulierung, verbindlich)

**Bewertung:** Ja. Der Auftragstext bat sinngemäß „wenn unklar: frage kurz nach".
Der Coder hat genau das getan (Lücke: Tasten fehlen statt falscher Wert) — und damit
den Lauf **freiwillig beendet**, ohne `agent_end`-Fortschritt und ohne Ergebnis.
Die Formulierung ist damit eine der drei Ursachen (neben fehlendem Wecker und
hängendem Turn). Ein Auftrag darf nie mit einer offenen Rückfrage enden dürfen.

**Ersatz-Wortlaut (wörtlich in jeden Coder-Brief, ersetzt jede Rückfrage-Klausel):**

> „Entscheide bei Unklarheiten **selbst** nach den Regeln in
> `docs/LAYOUT_REQUIREMENTS.md`, `docs/COMMIT_RULE_DECISION_2.md` bzw.
> `docs/SETUP_UI_DECISIONS.md`. Dokumentiere jede getroffene Annahme in der
> Schlussmeldung als Zeile `ANNAHME: <was> — <warum>`. Wenn eine Lücke den Auftrag
> sprengt: Teilmenge fertigstellen und `BLOCKER: <was> — <warum> — <vorschlag>` in die
> Schlussmeldung schreiben. **Stelle keine Rückfrage** — eine Rückfrage beendet den
> Lauf ohne Ergebnis und gilt als Abbruch."

Zusätzlich gilt: Fragen, die der Orchestrator vorhersehen kann, werden **vor** dem
Absenden im Brief beantwortet (Zielzustand, Verbote, Pfade, Erwartungsliste).

---

## 13. Hängende Delegierungen: Verhinderung (U2, 2026-10-28)

**Anlass:** Nutzer sieht wiederholt „hängende" Subagenten, die „auf Antwort auf ihre
Tool-Requests warten". Zwei belegte Fälle: (Fall 1, siehe §12/I12) Coder stellt eine
Rückfrage und beendet den Lauf → `steer` erhält nie ein Ergebnis → 5 h 58 min
Stillstand; (Fall 2) Coder arbeitet M8b-2 ab und **wartet auf eine echte
Werkzeug-Freigabe**; erst der Klick des Nutzers setzt den Lauf fort (Ergebnis: 232
Tests, 0 Fehler, Emoji-Panel + L-02-Fix).

> **Korrektur (U2-R, Nutzerhinweis):** Die frühere These „das ist nur eine verwaiste
> Anzeige, die Arbeit war fertig" ist für Fall 2 **falsch**. Es gab eine echte,
> **blockierende Freigabe-Anfrage** (Bestätigung für einen Tool-Call), die unbegrenzt
> wartet, bis ein Mensch klickt. Dieser Zustand ist **legal, kein Regelverstoß und kein
> Fehler** — der Coder hat sich korrekt verhalten. „Fragen ist untersagt" gilt nur für
> **Inhalts-/Scope-Mehrdeutigkeiten**, nicht für Werkzeug-Freigaben.

### R13.1 — Vier Zustände, ein 10-Sekunden-Test (W1–W4)

Serverseitig gibt es genau zwei „pending"-Maps: `pendingApprovals` (Werkzeug-Freigabe)
und `pendingQuestions` (`ask_user_question`); die Harness-Funktion `isWaitingOnUser`
prüft beide und **nur dort existiert kein Timeout**. Entscheidend ist, **was** aussteht:
eine Freigabe (legal) oder eine Inhaltsfrage (verboten).

| # | Zustand | 10-Sekunden-Erkennung | Legal? | Richtige Reaktion |
|---|---|---|---|---|
| **W1** | **Wartet auf Werkzeug-Freigabe** (Bestätigung für einen Tool-Call) | `conversation_read(action="status")`/Session-Anzeige nennt eine **ausstehende Bestätigung** (Werkzeug + Befehl); Output endet **mitten im Tool-Call**; seit Minuten kein neuer Dateischreibvorgang; der Lauf ist nicht `done` | **ja, Dauerzustand** | **Nicht `stop`en, nicht neu delegieren.** (a) dem Nutzer die Freigabe **namentlich** nennen (Werkzeug, exakter Befehl, warum nötig); (b) darf der Orchestrator sie selbst erteilen, sofort erteilen; (c) Wecker-Text (R12.1) enthält immer den Zusatz „**Freigabe kann ausstehen** — dann NICHT stoppen, nur melden" |
| **W2** | **Inhalts-Rückfrage** des Coders („Ich frage kurz nach", `ask_user_question` für Inhalt/Scope/Design) | `conversation_read(status)` meldet pending **question** **oder** letzter Text endet mit „?"/„Soll ich …"; keine ausstehende Bestätigung | **nein** — von uns selbst erzeugt | Ein-Satz-`steer` mit der Entscheidung (R13.7) + „jetzt ABSCHLUSSPROTOKOLL"; bei `ask_user_question` im UI beantworten oder `stop` + neuer Brief mit der Entscheidung im Text |
| **W3** | Harness-Zwangsabbruch / synthetic result (U1-Fall) | Transkript enthält den Synthetic-Result-Text; Datei-mtime steht seit > 15 min; ggf. `running` ohne Aktivität | nein (Harness) | §2.6-Entscheidungsbaum: `get_result` → Datei-Soll/Ist → selbst verifizieren → `steer`/`stop` |
| **W4** | Fertig, nur nicht abgeholt (Anzeige „hängt") | `get_result` liefert **sofort** vollständigen Output, `git status` zeigt die Soll-Dateien, **keine** pending Bestätigung/Frage | ja | Ergebnis übernehmen, `state/progress.md`, Session dismissen |

**Merksatz:** „Wartet auf Tool-Antwort" beschreibt **zwei** verschiedene Dinge —
W1 (Mensch muss klicken, korrekt) und W3 (Harness hat den Call verloren, Fehler).
Der Test ist immer derselbe Dreiklang: `get_result` (Status + letzter Text) →
`conversation_read(status)` (**Bestätigung** oder **Frage**?) → `git status`/mtime.

### R13.2 — ABSCHLUSSPROTOKOLL (Pflichtbestandteil jedes Coder-Briefs, Wortlaut)

> **ABSCHLUSSPROTOKOLL (verbindlich).**
> 1. Der Lauf endet **immer** mit einer Ergebnis-Meldung, auch wenn er unvollständig
>    ist. Die letzten Zeilen haben exakt dieses Format:
>    `FERTIG: <was fertig ist, mit Dateipfaden>` / `OFFEN: <was fehlt>` /
>    `ANNAHME: <getroffene Annahme> — <warum>` (0..n) /
>    `BLOCKER: <was> — <warum> — <vorgeschlagener nächster Schritt>` (0..n) /
>    `VERIFIKATION: <exakter Befehl + Ergebnis>`.
> 2. **Inhalts-/Scope-Unklarheiten werden niemals als Frage formuliert.** Ein Satz, der
>    mit „?" endet, „Ich frage kurz nach", „Soll ich …" oder „Bitte bestätigen" enthält,
>    ist ein **Regelverstoß** und macht die Lieferung ungültig.
> 3. **Ausnahme — Werkzeug-Freigaben (W1):** Braucht ein Tool-Call eine Bestätigung des
>    Menschen, ist das **kein** Regelverstoß und **keine** Rückfrage. Dann gilt: eine
>    Zeile `FREIGABE_NOETIG: <werkzeug> — <exakter befehl> — <warum nötig>` schreiben
>    und den Call aufrufen; der Lauf darf warten, bis geklickt wird. Diese Zeile darf
>    **nicht** als `BLOCKER:` umetikettiert werden, um die Freigabe zu umgehen — sonst
>    blockiert der Coder still, statt sauber um Freigabe zu bitten.
> 3. Reicht die Zeit nicht: Teilmenge fertigstellen, `OFFEN:` + `BLOCKER:` schreiben,
>    **kein** Nachfragen, **kein** Stillstand.

**Warum R12.11 allein nicht reichte:** R12.11 sagte „keine Rückfrage", definierte aber
kein **Ausgabeformat** und keine Ausnahme für Freigaben. Der Coder hatte keinen legalen
Weg, seine *inhaltliche* Unsicherheit loszuwerden — also wählte er den einzigen, den er
kannte: fragen. Das Protokoll gibt ihm einen erlaubten Kanal (`ANNAHME:`/`BLOCKER:`),
der den Lauf **nicht** beendet — und trennt sauber davon die **Freigabe** (W1), die
weiterhin erlaubt und erwartet ist.

### R13.3 — Entscheidungsgrundlage + Vollmacht (macht Blockieren strukturell unnötig)

Jeder Brief nennt (i) die **Dokumente**, nach denen entschieden werden darf, und
(ii) diese Vollmacht-Klausel **wörtlich**:

> „Du hast **Entscheidungsvollmacht** für alles, was diese Dokumente regeln:
> `docs/LAYOUT_REQUIREMENTS.md`, `docs/COMMIT_RULE_DECISION_2.md`,
> `docs/SETUP_UI_DECISIONS.md`, `docs/LANGUAGE_EMOJI_DECISIONS.md`,
> `docs/ASR_CONTRACT.md`. Widerspricht der Auftrag einer dieser Regeln, gilt die
> **Doku**; du dokumentierst den Widerspruch als `BLOCKER:` und arbeitest nach der
> Doku weiter. Inhaltliche Fragen sind untersagt, Entscheiden ist Pflicht.
> **Ausgenommen sind Werkzeug-Freigaben**: braucht ein Befehl eine Bestätigung, meldest
> du sie mit `FREIGABE_NOETIG: …` und wartest — das ist korrekt."

Damit ist die Rangfolge eindeutig: **Doku > Auftragstext > eigene Vermutung**.

### R13.4 — Zeitmarke mit Zwangsabbruchmeldung

Der Brief enthält eine absolute Marke: „Erreiche ich Minute **8** ohne vollständige
Verifikation, beende ich mit `FERTIG:/OFFEN:/BLOCKER:` (Teilmenge) und **ohne** weitere
Recherche." Der Orchestrator setzt dazu den Wecker (R12.1, ≤ 6 min). Ein Lauf, der die
Marke überschreitet, wird von **außen** beendet (§13.6), nicht von innen erhofft.

### R13.5 — Prüfliste VOR jeder Delegierung (60 Sekunden, 5 Punkte)

1. **Ziel-Konflikt-Check:** Schließen sich zwei Anforderungen aus? (Negativbeispiel aus
   Fall 1: „Test muss ⇧ in `symbolsShift` verlangen" **+** „keine strukturellen
   Layoutänderungen" ⇒ unerfüllbar.) Bei Konflikt: Auftrag **nicht** absenden.
2. **Fakten-Check gegen eigene Messungen:** Jede „muss"-Aussage im Brief gegen den
   selbst gemessenen Ist-Zustand prüfen (Zahlen aus `git`/Skript in den Brief
   aufnehmen). Wenn der Brief eine Tatsache behauptet, die ich nicht gemessen habe:
   messen oder als `ANNAHME` kennzeichnen.
3. **Scope-Konsistenz:** Dateipfade mit `rg --files | grep <name>` verifizieren; die
   Dateien im Scope müssen die Dateien sein, die der geforderte Test berührt.
4. **Entscheidungsgrundlage:** 1–3 Doku-Dateien benennen (R13.3) + alle bekannten
   Mehrdeutigkeiten **im Brief** auflösen.
5. **Abschluss + Wecker:** ABSCHLUSSPROTOKOLL (R13.2), Zeitmarke (R13.4), Wecker
   (R12.1) und Rückfallpfad („wenn X nicht geht, tue Y") sind enthalten.

### R13.6 — Betriebsregel für hängende Sessions (Fristen + Stufen)

**Erkennung (eines von drei Signalen genügt):** (a) `conversation_read(status)` meldet
pending question; (b) letzter Output endet mit Frage und seit **> 5 min** keine neue
mtime/Tool-Aktivität; (c) Wecker überfällig oder `running` seit > 2 Intervallen.

**Für W1 (Werkzeug-Freigabe) gilt KEINE Stufenfolge mit `stop`.** Ein `stop` würde
Arbeit vernichten, die kurz vor der Lieferung steht — genau der M8b-2-Fall (nach dem
Klick: 232 Tests grün, Emoji-Panel + L-02-Fix). W1 ist ein legaler Dauerzustand.

| Stufe | Wann (ab Delegierung) | Aktion |
|---|---|---|
| 0 | sofort, immer | `get_result` + `conversation_read(status)` (Bestätigung? Frage?) + `git status`/mtime — **nie** blind neu delegieren |
| **W1** | **6 min** | **Nur informieren** (eine Zeile an den Nutzer: welches Werkzeug, welcher Befehl, warum nötig) und **weiter warten**; Wecker recurring (alle 6–10 min) erneuern, **nie** `stop`, **nie** neu delegieren |
| W2 | **6 min** | **Ein** `steer` mit genau einem Satz Entscheidung + „keine Rückfrage, jetzt ABSCHLUSSPROTOKOLL" |
| W2 | **11 min** | Zweiter `steer`: „Keine weitere Recherche. Antworte in 60 s mit `FERTIG:/OFFEN:/BLOCKER:`" |
| W2/W3 | **15 min** | `stop` → selbst verifizieren (`./gradlew … --tests …`) → Lücke als **neuen, kleineren** Auftrag (≤ 2 Dateien) |
| — | **pending question** (`ask_user_question`) | **Sofort** (keine Frist): im UI beantworten **oder** `stop` + neuer Brief mit der Entscheidung im Text — `steer` beantwortet keine pending question |

**Doppelarbeit verhindern:** Vor jedem neuen Auftrag `git status --porcelain` gegen die
Soll-Liste; gibt es bereits Änderungen, wird der Auftrag als **„vervollständige X"**
formuliert, nie als „implementiere X".

### R13.7 — Was der Orchestrator beim Entblocken schreibt (Ein-Satz-Antwort, Vorlage)

> „Entscheidung: <konkrete Regel/Antwort>. Arbeite danach weiter, stelle **keine**
> Rückfrage und schließe mit dem ABSCHLUSSPROTOKOLL (`FERTIG:/OFFEN:/BLOCKER:`)."

Beispiel für Fall 1: „Entscheidung: Die ⇧-Taste in `symbolsShift` ist **keine**
strukturelle Änderung — sie ist Regel 4a; ergänze sie in den 8 Assets per Skript und
schließe mit dem Protokoll ab."

### R13.8 — Ist das Problem vermeidbar? (ehrliche Bilanz)

| Anteil | Bewertung |
|---|---|
| **Harness-Mechanik** (A3 synthetic result, A4 falsche „hängt"-Anzeige, hängender Scheduler-Wake) | **Nicht** durch uns behebbar. A4 ist reine Anzeige und verschwindet erst mit `get_result`/Dismiss; A3 bleibt ein Restrisiko bei 20-min-Watchdog/Stream-Hängern. |
| **Auftragsqualität** (Fall 1: widersprüchlicher Brief) | **Vollständig** unser Einfluss — und der größte Hebel. R13.5 verhindert genau diesen Fall. |
| **Rollengestaltung** (Coder darf fragen) | **Vollständig** unser Einfluss — R13.2/R13.3 nehmen ihm die Notwendigkeit *und* die Legalität. |

**Ziel realistisch:** **0 Vorkommnisse sind nicht erreichbar** — W1 (Freigabe) ist ein
**gewollter** Dauerzustand, W3 (Watchdog) Harness-Mechanik, W4 Anzeige-Semantik.
Erreichbar und messbar ist: **Erkennung ≤ 6 min** (Wecker + R13.1-Dreiklang) und
**Meldung ≤ 6 min** (bei W1: Freigabe benannt; bei W2/W3: Entblockung eingeleitet)
⇒ **maximal ~12 min Stillstand** statt 5 h 58 min. Ein Versprechen „nie wieder
hängend" wäre Wunschdenken; „nie wieder **unbemerkt** hängend" ist eine prüfbare
Betriebsregel.

### R13.9 — Freigaben vorbeugen (welche Klassen treten hier auf, wie vermeidet man sie)

**Erfahrungswerte dieses Projekts + eingebaute Regel-Engine** (`approval-rules.js`,
Standard-Regeln mit `ask`): rekursives Löschen (`rm -rf`), destruktive Git-Operationen,
`chmod`, Schreiben in Systemverzeichnisse, `.env`/SSH-Keys/Shell-Profile und
**Schreiben außerhalb des Workspace** (`outside_workspace`). Dazu kommen
**client-seitig konfigurierte Regeln** (`<dataDir>/approval-rules.json`) — dort landen
typischerweise `sudo …`, `adb …`, `./gradlew …`, Netzwerk-Befehle (`curl`, `wget`),
`pip/npm install`. Genau diese sind im Projekt die häufigen Freigaben.

**Auftragsformulierungen, die Freigaben vermeiden:**

1. **Befehle exakt und vollständig ausschreiben** (kein `$(…)`, kein `|`, kein `&&`,
   kein `xargs`, keine Here-Docs) — der Regelabgleich sieht nur den Befehlsstring;
   verschachtelte Konstrukte werden als „unbekannt" behandelt und landen im `ask`.
2. **Keine Pfade außerhalb des Projekts** (`/tmp`, `$HOME`, `/sdcard`): Skripte,
   Testdaten und Artefakte gehören unter `tools/`, `testdata/`, `dist/`.
3. **Kein `sudo`** und keine Systempakete in Coder-Aufträgen; Werkzeuginstallation ist
   ein eigener Orchestrator-Schritt (einmalig, mit Ansage).
4. **Emulator/adb und `./gradlew`-Volläufe nicht im Coder-Brief** (bereits §2.1/§R12.4)
   — dort nur `./gradlew :app:testDebugUnitTest --tests "…"`, ein Aufruf.
5. **Dateischreibzugriffe im Workspace halten**; löschen/umbenennen nur über
   `git rm`/`mv` im Projekt, nie `rm -rf`.

**Was der Nutzer EINMALIG freigeben müsste** (damit diese Klassen nicht mehr
nachfragen): im Freigabe-Dialog „**gleiche Klasse erlauben**" wählen bzw. in
`approval-rules.json` je eine `allow`-Regel für die exakt verwendeten Befehlspräfixe
anlegen — z. B. `./gradlew ` (Präfix), `python3 tools/` (Präfix),
`adb shell ime ` (Präfix). Danach bleibt nur noch `sudo` und destruktives Git
freigabepflichtig — beides kommt in normalen Coder-Aufträgen nicht vor.

### R13.10 — Kurz-Tabelle „Was sehe ich, was tue ich"

| Was sehe ich | Was tue ich | Verboten |
|---|---|---|
| Session steht, **Bestätigung** für ein Tool ausstehend (W1) | Nutzer die Freigabe **namentlich** nennen (Werkzeug, Befehl, Zweck); ggf. selbst erteilen; weiter warten | `stop`, neu delegieren, als `BLOCKER` umdeuten |
| Session steht, letzter Text ist eine **Frage** (W2) | Ein `steer` mit einem Satz Entscheidung + „jetzt ABSCHLUSSPROTOKOLL" | zweite Rückfrage, Neu-Delegation ohne `stop` |
| Session `running`, aber Synthetic-Result im Transkript (W3) | `get_result` → Datei-Soll/Ist → selbst verifizieren → `steer`/`stop` | blind neu delegieren (Doppelarbeit) |
| Session „hängt", `get_result` liefert **sofort** fertigen Output (W4) | Ergebnis übernehmen, `state/progress.md`, Session dismissen | auf „running" starren, Auftrag wiederholen |
