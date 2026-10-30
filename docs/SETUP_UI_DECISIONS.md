# Setup-/Download-Entscheidungen (E1–E4) — verbindlich für den Coder

**Stand:** 2026-10-27 · **Rolle:** Analyst/Design (keine Code-, Manifest- oder Asset-Änderung)
**Grundlage:** `docs/DESIGN.md` §8 (UX/Datenschutz) + §2.2, `docs/TECH_FACTS.md` §3/§4.1,
`docs/IMPLEMENTATION_PLAN.md` M4/M5/M7, `docs/REVIEW_STAGE4B.md` B-4,
`model/{ModelStore,ModelDownloader,DownloadController}.kt`, `ime/*`, `HANDOFF.md`.

---

## E1 — Foreground-Service-Typ für den 671-MB-Download (API 36)

### Entscheidung

**`dataSync`-Foreground-Service in einem eigenen `ModelDownloadService`.**
Kein Download im Activity-Lebenszyklus, kein `specialUse`, kein „download"-Typ
(den gibt es nicht).

### Begründung (Optionen einzeln)

| Option | Bewertung |
|---|---|
| (a) kein FGS, an `SetupActivity` gebunden | **Reicht nicht.** Zwar überlebt der Prozess, solange die Activity sichtbar ist — aber sobald der Nutzer wegswitcht oder der Screen ausgeht, ist der Prozess nur *cached* und unter Speicherdruck (671 MB Download + ONNX-Runtime im selben Prozess!) killbar. Zusätzlich greifen ohne FGS die **Doze-Netzrestriktionen** für idle Apps: der Transfer kann mitten im Lauf einschlafen. Abbruch ist dank `.part`+ETag zwar *reparierbar*, aber der Nutzer sieht einen stehenbleibenden Balken ohne Grund. |
| (b) `dataSync` | **Gewählt.** Kanonischer Typ für Datentransfer; hält den Prozess am Leben, exempt vom Doze-Netz-Throttling, sichtbare Notification mit Fortschritt und Cancel. Preis: **6 h pro 24 h** ab `targetSdk 35` und `onTimeout()`-Pflicht (TECH_FACTS §4.1) — bei real 5–20 min Downloadzeit irrelevant, aber **defensiv implementieren** (Regel L7). Die Play-Store-Erklärung für `dataSync` („data transfer use case") betrifft nur Store-Distribution; wir sideloaden (GrapheneOS) → **nicht** relevant, aber im Manifest-Kommentar festhalten. |
| (c) `specialUse` | **Abgelehnt.** Verlangt `FOREGROUND_SERVICE_SPECIAL_USE` **und** die Property `PROPERTY_SPECIAL_USE_FGS_SUBTYPE` mit Begründung; Nutzen gegenüber `dataSync` = null, dafür mehr Manifest-/Review-Aufwand und ein Typ, der semantisch falsch ist. |
| (d) `download`-Typ | **Existiert nicht.** API-36-Typen: camera, connectedDevice, dataSync, health, location, mediaPlayback, mediaProcessing, mediaProjection, microphone, phoneCall, remoteMessaging, shortService, specialUse, systemExempted. `shortService` ist auf ~3 min begrenzt → für 671 MB unbrauchbar. |

**Warum das zu `DownloadController` passt:** Der Controller ist bereits UI-frei,
Executor-basiert, cancelbar und resume-fähig über `.part` (Klassenkommentar:
„CANCELLED, `.part` bleibt (Resume möglich)"). Der Service ist damit **nur** ein
Prozess-/Notification-Wirt — keine zweite Zustandsmaschine:

- Der Service **besitzt** die Controller-Instanz (nicht die Activity).
- Die Activity **beobachtet** `DownloadController.state` über `listener` und zeigt
  ihn; sie startet/stoppt nichts außer per Intent.
- Fortschritt/Fehler kommen aus `DownloadProgress`/`ModelError` — der Service
  mappt sie 1:1 auf Notification + `State`. Keine neue Persistenz nötig: der
  Wahrheitsstand nach Prozess-Tod ist `ModelStore.status()` (Absent/Partial/Ready).

### Minimale Manifest-Zeilen

```xml
<!-- Netz + FGS dataSync (E2: INTERNET dauerhaft, siehe unten) -->
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
<!-- Basis-Permission ist bereits vorhanden (Diktat-FGS) -->
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_DATA_SYNC" />

<!-- im <application>: -->
<service
    android:name=".model.ModelDownloadService"
    android:exported="false"
    android:foregroundServiceType="dataSync" />
```

Kein `onTimeout`-Extra im Manifest nötig; `Service.onTimeout(int, int)` wird ab
`targetSdk 35` vom System aufgerufen (TECH_FACTS §4.1). Die bisherige
`TelemetryInitializer`-Removal bleibt **unverändert** bestehen.

### Lifecycle-Regeln (Regeltext für den Coder — verbindlich)

- **L1 Start:** Nur auf expliziten Tap „Modell herunterladen" **in einer RESUMED
  `SetupActivity`** (`ContextCompat.startForegroundService`). Aus dem Hintergrund
  nicht starten (Android-12+-Verbot; ein `ForegroundServiceStartNotAllowedException`
  wird gefangen und als „App erneut öffnen" angezeigt, nie als Crash).
- **L2 `startForeground`:** im Service sofort (≪ 5 s) mit
  `FOREGROUND_SERVICE_TYPE_DATA_SYNC` (API ≥ 29), Kanal `model_download`
  (`IMPORTANCE_LOW`), Text „Modell wird geladen · <x> % · <MB>/<MB>".
- **L3 Activity-Destroy:** **kein** Cancel. Der Download läuft weiter; beim
  Zurückkehren zeigt die Activity den Zustand aus `DownloadController.state` bzw.
  `ModelStore.status()`.
- **L4 Abbruch:** ausschließlich durch expliziten Nutzerwunsch (Button oder
  Notification-Action) → `controller.cancel()`; `State.Cancelled`, `.part` bleibt,
  Service ruft `stopSelf()`.
- **L5 Terminierung:** bei `Ready` / `Failed` / `Cancelled` ruft der Service
  `stopSelf()` (innerhalb ~1 s) und entfernt die Notification. `Ready` löst
  zusätzlich „Modell aktiv, Mikrofon freigeschaltet" im UI aus.
- **L6 Prozess-Tod/Reboot:** Kein Aufräumzwang. Beim nächsten Start entscheidet
  `ModelStore.status()`: `ABSENT` → Download-Button; `PARTIAL` → „Fortsetzen"
  (Resume über `.part` + ETag); `READY` → Setup gilt als erledigt.
- **L7 `onTimeout()`** (API 35+): sofort `controller.cancel()`, `stopSelf()`,
  Zustand `Paused` mit Hinweis „Download pausiert (Systemlimit) — fortsetzen".
  **Nie** ohne `stopSelf()` weiterlaufen (sonst interne Exception).
- **L8 Notification-Permission:** `POST_NOTIFICATIONS` verweigert ⇒ Download
  **trotzdem** starten (Service läuft, Notification unsichtbar); kein Blocker,
  aber Hinweis in der Setup-UI. Kein `WAKE_LOCK` nötig — FGS ist vom
  Doze-Netz-Throttling ausgenommen, Screen darf ausgehen.
- **L9 Kein Parallelbetrieb:** `DownloadController.start()` ist bereits idempotent;
  der Service darf höchstens eine Instanz halten (`START_NOT_STICKY`, kein
  `START_REDELIVER_INTENT` — der Zustand kommt aus dem Store, nicht aus Intents).

---

## E2 — INTERNET-Permission-Politik

### Entscheidung

**Option (a): `INTERNET` + `ACCESS_NETWORK_STATE` dauerhaft im Manifest, plus
appinterner Schalter „Netzwerkzugriff für Downloads erlauben" (Default: an bis
Setup abgeschlossen, danach aus).** `tools:node="remove"` **wird gestrichen**
(B-4), die `TelemetryInitializer`-Entfernung **bleibt**.

### Begründung

- **Es gibt genau eine APK** (Sideload, M6). Ein Flavor/„Download-Build" (Option b)
  würde zwei Artefakte erzeugen, von denen das verteilte kein Modell laden könnte —
  und die Modellinstallation ist Pflichtbestandteil der Ersteinrichtung
  (M7: „Setup installiert 671 MB aus lokalem Testserver").
- **Option (c) „nur Kommentar"** ist ehrlich, aber nicht redlich genug: Sie sagt dem
  Nutzer nicht, *dass* er den Zugriff abstellen kann und *was* dann passiert.
- Der appinterne Schalter ist **kein** Berechtigungs-Ersatz (Android-`INTERNET` ist
  normal und zur Laufzeit nicht entziehbar) — er verhindert, dass die App nach dem
  Setup überhaupt Requests **baut**. Genau das steht schon in DESIGN §8 („appinterner
  Netzwerk-Aus-Schalter verhindert zusätzlich sämtliche neuen Downloadrequests").
- Auf GrapheneOS kann der Nutzer die Netzwerk-Berechtigung **im System** entziehen
  (Netzwerk-Schalter pro App). Beides zusammen ist die glaubwürdige Zusage:
  Manifestrecht + App-Schalter + OS-Entzug.

**Regeltext (Coder):**
1. `INTERNET`/`ACCESS_NETWORK_STATE` stehen im Manifest; `tools:node="remove"` für
   beide **entfernen**, Manifest-Kommentar auf „nur Modelldownload" umschreiben.
2. `ModelDownloader` wird **nur** über `PromptedDownloadGate` (o. ä.) aufgerufen:
   `settings.allowNetworkDownloads && store.status() != READY && !userOptOut`.
   Ist der Schalter aus, startet kein Request — die UI zeigt „Download deaktiviert".
3. Nach erfolgreichem `Ready`: Schalter automatisch auf **aus**, sichtbarer Hinweis
   „Netzwerkzugriff für Downloads ist jetzt aus".
4. Kein zweiter Netzwerkpfad: keine Update-Checks, keine Telemetrie, keine
   Crash-Uploads, keine Cloud-ASR. `SecurityException`/`UnknownHostException` werden
   als eigene `ModelError`-Art (B-4) abgebildet, nicht als `Storage`.

**Datenschutztext der Setup-Seite (3–5 Sätze, wörtlich übernehmbar):**

> „Ihre Sprache und Ihr Text bleiben auf diesem Gerät. Die App nutzt das Netzwerk
> ausschließlich für den einmaligen Download des Spracherkennungsmodells (ca. 671 MB)
> von der angegebenen Adresse; es gibt keine Telemetrie, keine Konten und keine
> Cloud-Erkennung. Nach dem Download schaltet die App den Netzwerkzugriff für sich
> selbst ab; Sie können ihn hier jederzeit wieder erlauben oder dauerhaft aus lassen.
> Zusätzlich können Sie der App in den Android-Einstellungen die Netzwerkberechtigung
> komplett entziehen — die Tastatur funktioniert danach unverändert offline.
> Ohne installiertes Modell bleibt die Tastatur nutzbar, die Sprachfunktion ist dann
> deaktiviert."

**Auswirkung auf `docs/TECH_FACTS.md`:** §3 Punkt 3 ist inhaltlich weiter richtig
(„INTERNET bleibt nur für den Modell-Download drin"), muss aber ergänzt werden um:
(i) Permission ist **dauerhaft** drin (kein Staging mehr, B-4 erledigt),
(ii) appinterner Schalter + OS-Entzug als zweite/erste Ebene,
(iii) `FOREGROUND_SERVICE_DATA_SYNC` als neue, betrieblich begründete Permission.
Die Zusage „keine Netzverbindung" wird präzise zu **„keine Netzverbindung außer dem
einmaligen Modelldownload"**. DESIGN §8 Zeile „GrapheneOS-Netzwerkzugriff nach dem
Download kann der Nutzer entziehen" bleibt unverändert gültig.

---

## E3 — Startpfad für Nutzer ohne Modell

### Entscheidung

**Die Tastatur blockiert nie.** Sie tippt immer. Nur die **Sprachfunktion** ist ohne
Modell deaktiviert und führt in die Einrichtung (DESIGN §8: „Ohne Modell: Mic öffnet
Einrichtung statt Download im Hintergrund zu verstecken").

### Prüfbare UX-Regeln

1. **Start:** App-Start (Launcher `SetupActivity`) zeigt den Modellstatus aus
   `ModelStore.status()` und genau eine Primäraktion („Modell herunterladen" /
   „Fortsetzen" / „Fertig").
2. **IME ohne Modell:** Die Tastatur erscheint und tippt normal. Die Mic-Taste zeigt
   den Zustand „Modell fehlt" (ausgegraut + Label/Icon, `contentDescription`
   „Sprachfunktion: Modell nicht installiert") und ist **nicht** stumm: Tap öffnet
   `SetupActivity` (`FLAG_ACTIVITY_NEW_TASK`), kein Download startet automatisch.
3. **Kein Hintergrund-Download:** Ein Download beginnt ausschließlich durch
   expliziten Tap in der sichtbaren Setup-Seite (E1/L1). Nie beim IME-Start, nie per
   Timer, nie nach Reboot.
4. **Abbruch:** Zustand `Cancelled`, `.part` bleibt, UI bietet „Fortsetzen"; die
   bereits geladene Menge wird angezeigt (kein Neustart bei 0 %).
5. **Netzwerkfehler:** `ModelError.Network` → „Keine Verbindung. Erneut versuchen",
   automatischer Retry mit Backoff (max. 3, nur im Vordergrund/FGS), danach manuell.
   Bei appinternem Netzwerk-Aus: Hinweis + Schalter zum Einschalten, **kein** Request.
6. **Hash-Mismatch:** `ModelError.Hash` → betroffene Datei wird verworfen und **nur
   diese** neu geladen; die aktive Version bleibt unangetastet (`ModelStore`
   Atomarität), Meldung „Datei war beschädigt, wird erneut geladen".
7. **Wenig Speicher:** Vor dem Start Plausibilitätsprüfung (Empfehlung: **≥ 2,5 GB**
   frei, also ~3,7× Modellgröße wegen Staging + `.part`); bei zu wenig Platz
   **vor** dem ersten Byte abbrechen mit konkreter Zahl („2,5 GB benötigt, 0,8 GB
   frei"). Während des Downloads `ModelError.Storage` genauso behandeln.
8. **Doze/Akku/Prozess-Tod:** Download läuft nur im `dataSync`-FGS (E1); Screen-off
   ist erlaubt. Wird der Dienst vom System beendet, zeigt die Setup-Seite beim
   nächsten Öffnen „unterbrochen — fortsetzen" und lädt per Range weiter
   (kein Datenverlust, kein Fortschritt bei 0).
9. **Nach Erfolg:** Ohne App-Neustart wird die Mic-Taste aktiv (Engine lädt lazy,
   vgl. C-2/R-1: Ladefehler müssen in `lastError` sichtbar werden, nie „Bereit"
   bei fehlendem Modell).

---

## E4 — M4d-Teststrategie „Diktat" ohne echtes Mikrofon (`<device>`, `-no-audio`)

### Entscheidung

**Ein instrumentierter Test (`DictationWavInstrumentedTest`) mit zwei Injektionen:
`AudioSource` → `WavAudioSource` (Fixture aus `/data/local/tmp/testdata/`) und
`InputConnectionSink` gegen echte Host-Editoren in einer Test-Activity.** Bewiesen
wird damit die **gesamte** Kette ab Audio-Puffer bis zum Text im Feld — alles außer
Mikrofon-Treiber/Route (das bleibt Geräte-Gate, DESIGN §10.4).

**Messpunkte, die der Test protokollieren muss** (TextWatcher-Log + `EditorSink`-Spy):
exakte Textendstände, Anzahl/Position der `commitText`/`deleteSurroundingText`-Aufrufe,
Reihenfolge (nie Löschen hinter der Commit-Grenze), kein `setText`, kein
`setComposingText`, kein Schreiben nach Besitzverlust.

### Die 3 wertvollsten Szenarien

| # | Editorvariante | Erwartete Text-Edit-Abfolge | Was es beweist |
|---|---|---|---|
| S1 | `EditText`, einzeilig, leer, `inputType=textCapSentences` | Append-Blöcke monoton: `"Das ist "` → `"Das ist der "` → … → Endtext **exakt** `de_pause`-Golden (§4.1 ASR_CONTRACT); kein Löschen vor der jeweiligen Commit-Grenze; Gesamt-Backspaces nur im Tail | Golden-Text-Identität (M5) **plus** DeltaTyper-Verträglichkeit im echten Feld; trennt Decode- von Tippfehlern |
| S2 | `EditText`, mehrzeilig, **Cursor mitten im Text** gesetzt (Nutzer klickt), danach weiter Diktat | Nach dem Klick: **kein** `commitText`/`deleteSurroundingText` mehr (Ownership verloren, `Rejected`), Text bleibt unverändert; erst ein neues Diktat schreibt wieder | Besitz-/Fremdtext-Schutz (M4-Gate: „kein fremder Text gelöscht") unter realistischer Unterbrechung |
| S3 | `EditText`, bereits ~80 Zeichen Inhalt (über `max_revision_chars=60`), `textNoSuggestions` | Kein Rückschreiben > 60 Zeichen: entweder Append-only oder `Rejected`; Nie ein Löschdelta > 60; Endtext enthält den Alttext unverändert als Präfix | 60-Zeichen-Revisionsgrenze + „kein Zerstören fremden/alten Textes" im echten Editor |

**Zusatz (billig, hoher Wert):** S1 mit **`de_pause.wav`** (Pause ≥ 4 s) belegt
zugleich die Pausen-Regression; S1 mit **0,3-s-Stille-Fixture** muss ein **leeres**
Ergebnis liefern (kein `"Yeah."` → Guard wirkt auch im Feld).

**Randbedingungen für den Auftrag:** eigener Instrumented-Auftrag (kein Feature-Code),
`-no-audio` ist zulässig und wird im Testbericht als „nicht E2E (Mic-Route ungetestet)"
ausgewiesen; WAV vorher per `adb push` nach `/data/local/tmp/testdata/`; keine
`connectedDebugAndroidTest`-Kombination mit Feature-Arbeit (CONTINGENCY_PLAN §2.1).

---

## Anhang: Prüfliste vor dem Merge von M5b-2 / M7

- [ ] `INTERNET` + `ACCESS_NETWORK_STATE` im gemergten Manifest sichtbar
      (`aapt2 dump permissions`), `TelemetryInitializer` weiterhin entfernt.
- [ ] `FOREGROUND_SERVICE_DATA_SYNC` vorhanden; Service mit
      `foregroundServiceType="dataSync"`, `exported=false`.
- [ ] Notification-Kanal `model_download` existiert; `startForeground` < 5 s.
- [ ] Cancel → `State.Cancelled`, `.part` bleibt, Resume lädt nicht bei 0 %.
- [ ] `onTimeout` implementiert (Code-Pfad vorhanden, per Unit-Test/Fake aufrufbar).
- [ ] Hash-Fehler lädt nur die betroffene Datei neu; aktive Version unverändert.
- [ ] Platzprüfung vor dem ersten Byte, konkrete Zahlen in der Meldung.
- [ ] IME ohne Modell: tippt, Mic öffnet Setup, kein Auto-Download.
- [ ] Datenschutztext der Setup-Seite entspricht E2 (wörtlich).
- [ ] `docs/TECH_FACTS.md` §3 um Permission-Dauerhaftigkeit + Schalter ergänzt.
