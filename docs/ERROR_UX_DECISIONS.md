# Fehler-UX- und Setup-Entscheidungen (E5–E7) — verbindlich

**Stand:** 2026-10-27 · **Rolle:** Analyse/Regeln (keine Code-/Resource-Änderung)
**Grundlage:** `docs/REVIEW_STAGE5.md` (S5-2, S5-3, S5-4, S5-6),
`app/src/main/res/values/strings.xml`, `ime/SetupActivity.kt`,
`audio/AudioRecordSource.kt`, `ime/VoiceKeyboardService.kt`,
`docs/SETUP_UI_DECISIONS.md` E3, `docs/KONZEPT.md` §2.1 („Tastatur blockiert nie").

---

## E5 — Fehlerdifferenzierung beim Diktat-Start

### Ist-Zustand (belegt)

- `VoiceKeyboardService.onMicDown()` schreibt bei drei völlig verschiedenen Ursachen
  denselben Text: `updateStatus("Start fehlgeschlagen")` (Z.156) bzw.
  `"Start fehlgeschlagen (${e.javaClass.simpleName})"` (Z.163) — letzteres ist eine
  **Entwickler-Klasse im Nutzertext** (`IllegalStateException`).
- `updateStatus()` (Z.304) rendert `c.visibleError` zusätzlich als
  `"Fehler: Mikrofon/Route (<code>)"` — auch das ist ein Rohcode
  (`audioInit:IllegalStateException`, `model:<msg>`, `startFailed`).
- Vorhandene, bereits differenzierte Pfade: Modell nicht bereit
  (`!store.isReady()` → „Modell nicht installiert — Setup öffnen", Z.144),
  Berechtigung (Z.247/262).
- Verfügbare Signale: `DictationCoordinator.lastError`/`visibleError`
  (`startFailed`, `audioInit:<Klasse>`, `model:<msg>`, `audio:<code>:<detail>`),
  `AudioRecordSource.ERR_INIT | ERR_INVALID_OP | ERR_SYSTEM_STOP | ERR_MAX_DURATION`,
  `ModelStore.status()` = `ABSENT | DOWNLOADING | VERIFYING | READY | BROKEN`,
  `AudioRouter.routeListener`/`currentRoute` (`WIRED > BLUETOOTH_SCO > BUILTIN`).

### Entscheidung: 7 Nutzerklassen + 1 Fallback (Texte ≤ 45 Zeichen)

| Klasse | Nutzertext (max. 45 Z.) | Zeichen | Handlungsoption im UI | Technisch unterscheidbar? |
|---|---|---|---|---|
| **(a)** kein Mikrofon | `Kein Mikrofon gefunden – Gerät prüfen` | 37 | keine Aktion in der App; Hinweiszeile „Mikrofon anschließen/System prüfen", Mic bleibt gesperrt | **Ja, bedingt**: `AudioManager.getDevices(GET_DEVICES_INPUTS)` leer bzw. `RoutingPolicy.preferredInput()` = `null`. Sonst fällt der Fall in (d). |
| **(b)** Mikrofon belegt | — **kein eigener Text** → (d) | — | — | **Nein.** Andere Aufnahmen sind für normale Apps nicht einsehbar (`getActiveRecordingConfigurations` ist privilegiert); „belegt" wäre eine geratene Diagnose. Erlaubt ist nur der *Hinweis* in der Erläuterung, nicht die Behauptung. |
| **(c)** Berechtigung fehlt/entzogen | `Mikrofon-Berechtigung fehlt` | 27 | Button „Berechtigung erteilen" (In-App-Request); bei `denied_forever` → „Systemeinstellungen öffnen" | **Ja**: `checkSelfPermission(RECORD_AUDIO)` vor dem Start **oder** `lastError == "audioInit:SecurityException"`. |
| **(d)** Audio-Init-/Systemfehler (+ b) | `Audio-Start fehlgeschlagen – erneut versuchen` | 45 | Mic-Taste erneut drücken (kein Auto-Retry-Loop); Erläuterung: „Mögliche Ursachen: Mikrofon von einer anderen App belegt, Systemfehler." | **Ja als Klasse**: `startFailed`, `audioInit:<nicht SecurityException>`, `ERR_INIT`, `ERR_INVALID_OP`, `ERR_SYSTEM_STOP` **ohne** gleichzeitigen Routenwechsel. |
| **(e)** Modell nicht geladen | `Modell nicht geladen – Setup öffnen` | 35 | Tap → `SetupActivity` | **Ja**: `store.isReady() == false` **und** `status ∈ {ABSENT, DOWNLOADING, VERIFYING}`, oder `lastError == "model:<…>"` (Engine-Ladefehler, C-2/R-1). |
| **(f)** Modell defekt/BROKEN | `Modelldatei defekt – neu herunterladen` | 38 | Tap → Setup, dort „Erneut prüfen/laden" (Hash-Reparatur) | **Ja**: `store.status() == BROKEN` (Pointer vorhanden, `verifyInstalled()` false). |
| **(g)** Speicher knapp (Runtime) | `Zu wenig Speicher – App neu starten` | 35 | nur Hinweis + Prozess-Neustart durch Nutzer; kein Auto-Recovery | **Teilweise**: `OutOfMemoryError` (muss als `Error`/`Throwable` **explizit** gefangen werden — `catch (e: Exception)` sieht ihn nicht) und ORT-Meldungen mit `alloc`/`memory`/`bad_alloc`. Alles andere ist nicht klassifizierbar → (d). |
| **(h)** Bluetooth-SCO verloren | `Bluetooth-Mikrofon getrennt` | 27 | Aufnahme **kontrolliert beenden**, Hinweis „über Gerätemikrofon fortsetzen?"; kein automatischer Neustart | **Ja**: `state == RECORDING` **und** `routeListener` meldet Wechsel von `BLUETOOTH_SCO` auf `BUILTIN`/`WIRED`/`null` (bzw. `ERR_SYSTEM_STOP` genau in diesem Moment). |
| **Fallback** | `Diktat-Start fehlgeschlagen` | 27 | erneut versuchen; Erläuterung „Details im Systemprotokoll" (kein Rohcode im Text) | Wenn keine Regel greift (Pflicht: die einzige Stelle ohne Diagnoseversprechen). |

**Verbindliche Regeln (Coder):**

1. **Rohcodes nie im Nutzertext.** `visibleError` wird **gemappt**, nicht gedruckt
   (`audioInit:IllegalStateException`, `startFailed`, `model:…` dürfen nur im Log
   landen, nicht in der Statuszeile).
2. **Prioritätsreihenfolge** der Auswertung (erste Trefferregel gewinnt):
   Berechtigung (c) → Modell (f BROKEN vor e) → kein Gerät (a) → SCO-Verlust (h,
   nur während RECORDING) → Speicher (g) → Audio-Init (d) → Fallback.
3. **Sticky-Semantik bleibt** (R-2): Der Fehler steht bis zur nächsten bewussten
   Aktion; `onMicUp` löscht ihn nicht.
4. **Kein Diagnoseversprechen ohne Beleg:** (b) existiert als Text **nicht**; die
   Erläuterung zu (d) nennt Ursachen als Möglichkeit („Mögliche Ursachen: …"), nie
   als Feststellung.
5. **Tastatur blockiert nie** (KONZEPT §2.1): Jede Fehlerklasse betrifft nur die
   Sprachfunktion; Tippen bleibt unverändert möglich, keine modale Sperre.
6. **Kein Auto-Retry** bei (a)/(d)/(g); bei (h) kein automatischer Aufnahme-Neustart
   (Mikrofon-Route könnte erneut wechseln → Flackern).
7. **String-Ressourcen:** alle sieben Texte (plus Fallback) als
   `dictation_error_*` in `strings.xml`; **keine** zusammengebauten Sätze im Code
   (Übersetzbarkeit). Vorschlag der Schlüssel:
   `dictation_error_no_mic`, `_permission`, `_audio_start`, `_model_missing`,
   `_model_broken`, `_out_of_memory`, `_bt_lost`, `_unknown`.

### Was wir heute *nicht* können (bewusst)

- **(b) nicht** — Android gibt anderen Apps keinen Einblick in fremde Aufnahmen.
- **(g) nur grob** — OOM ist fangbar, native ORT-Allocator-Fehler sind nur über
  Meldungsmuster erkennbar; beides ist best-effort und fällt sonst in (d).
- **(a) nur bedingt** — ein leerer Geräte-Katalog ist eindeutig; ein vorhandenes,
  aber funktionsuntüchtiges Gerät (z. B. Emulator `-no-audio`) landet in (d).
  Genau deshalb ist (d) die **Sammelklasse** und nicht (a).

---

## E6 — Datenschutztext-Präzisierung (S5-4)

**Fakten:** `INTERNET`/`ACCESS_NETWORK_STATE` bleiben dauerhaft im Manifest
(Permissions sind bei Android nicht zur Laufzeit ab-/zuschaltbar); die Abschaltung
nach dem Download ist der **App-Schalter** (`network_for_downloads`), der den
Download-Start gatet. Zusätzlich kann der Nutzer auf GrapheneOS die
Netzwerkberechtigung im System entziehen.

**Variante A (empfohlen, minimal-invasiv, 2 Sätze):**

> „Nach dem Download deaktiviert die App ihren Download-Schalter automatisch; es
> findet dann kein weiterer Netzwerkzugriff statt. Sie können den Schalter jederzeit
> wieder aktivieren oder der App in den Android-Einstellungen die
> Netzwerkberechtigung ganz entziehen — die Tastatur funktioniert danach unverändert
> offline."

**Variante B (expliziter über die Berechtigung, 3 Sätze):**

> „Die Netzwerkberechtigung bleibt im App-Manifest, wird aber ausschließlich für
> diesen einen Download genutzt. Danach schaltet die App ihren Download-Schalter
> automatisch ab, sodass kein weiterer Netzwerkzugriff stattfindet. Sie können den
> Schalter jederzeit wieder aktivieren oder der App in den Android-Einstellungen die
> Netzwerkberechtigung ganz entziehen — die Tastatur funktioniert weiterhin offline."

**Empfehlung: Variante A.** Begründung: Sie beseitigt die einzige Unschärfe
(„schaltet den Netzwerkzugriff ab" → „deaktiviert ihren Download-Schalter") und
behält den zweiten Satz als Eskalationspfad (OS-Entzug). Variante B nennt
„App-Manifest" — Entwicklerjargon, der beim Nutzer nur Unsicherheit erzeugt und
nichts entscheidbar macht; die technisch relevante Zusage („kein weiterer
Netzwerkzugriff") steht in A genauso. **Rest des Textes bleibt unverändert**
(Sätze 1–2 zum lokalen Verbleib von Audio/Text, Abschluss zu „ohne Modell").
`docs/TECH_FACTS.md` §3 beschreibt die Manifest-Seite bereits korrekt.

---

## E7 — `dataSync`-FGS auf API < 34 (S5-6)

**Korrektur der Review-Prämisse:** `ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC`
**und** das Manifest-Attribut `android:foregroundServiceType` gibt es **seit API 29**
(minSdk = 29) — es sind *keine* API-34-Konzepte. Neu ab API 34 ist nur: der Typ ist
**Pflicht**, jede Typklasse hat eine eigene `FOREGROUND_SERVICE_*`-Permission, und
Verstöße werfen `MissingForegroundServiceTypeException`/`SecurityException`.
*(Quelle: SDK-Konstanten-Doku „Added in API level 29"; im Container nicht per
Gradle gegengeprüft.)* Die praktische Konsequenz bleibt trotzdem: **kein
ungeschützter Start**, sondern defensives Starten mit Fallback.

**Verbindliche Regel (Coder):**

1. **Primär:** `startForeground(NOTIFICATION_ID, notif,
   ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)` (3-Arg-Form, API ≥ 29) — kein
   `SDK_INT`-Branch um den Typ selbst nötig.
2. **Fallback-Kaskade** genau einmal, in dieser Reihenfolge:
   `IllegalArgumentException` (Typ nicht im Manifest) → `SecurityException`
   (Permission fehlt, nur API ≥ 34 möglich) →
   `ForegroundServiceStartNotAllowedException` (API ≥ 31, Start aus dem Hintergrund)
   ⇒ **einmal** `startForeground(NOTIFICATION_ID, notif)` (2-Arg, Typ = aus Manifest,
   auf API 29–33 ohne Zeitlimit) versuchen.
3. **Wenn auch das scheitert:** `stopSelf()`, Download bleibt `Idle`
   (`.part` unberührt) und die Setup-Seite zeigt den bestehenden Text
   `setup_error_fgs_blocked` („Download konnte nicht gestartet werden. Öffnen Sie
   die App erneut und versuchen Sie es noch einmal."). Kein Crash, keine stille
   Weiterführung ohne Notification.
4. **Kein eigener Text für „altes Android":** Auf API 29–33 läuft der Download mit
   dem 2-Arg-Fallback unverändert (nur ohne Typsemantik/6-h-Limit). Der Nutzer
   braucht dort **keine** Meldung. `onTimeout` (API ≥ 35) bleibt als
   `override`-Methode vorhanden; auf älteren APIs wird sie vom System nie gerufen.
5. **Testbarkeit ohne Altgerät:** Der Fallback-Pfad ist als Unit-Test über einen
   injizierbaren „Foreground-Starter" prüfbar (Fake, der beim 3-Arg-Aufruf wirft);
   Emulator (API 36) deckt ihn **nicht** ab — deshalb Pflicht-Test, nicht
   Emulator-Beobachtung.

---

## Anhang: Prüfliste (Merge-Gate für S5-3/S5-4/S5-6)

- [ ] Acht `dictation_error_*`-Strings vorhanden, jeder ≤ 45 Zeichen.
- [ ] Kein Rohcode (`startFailed`, `audioInit:…`, `model:…`) erscheint in der
      Statuszeile — nur Log.
- [ ] `store.status() == BROKEN` erzeugt den Defekt-Text, **nicht** den
      „nicht installiert"-Text.
- [ ] `OutOfMemoryError` wird gefangen (nicht nur `Exception`).
- [ ] SCO-Verlust während RECORDING beendet die Aufnahme und zeigt `_bt_lost`.
- [ ] Datenschutztext entspricht Variante A (wörtlich).
- [ ] 3-Arg-`startForeground` mit Fallback-Kaskade + Unit-Test für den Wurf-Fall.
- [ ] Tippen bleibt in allen Fehlerklassen möglich (keine Sperre, kein Modal).
