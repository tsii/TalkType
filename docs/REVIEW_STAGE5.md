# Review Stufe 5 — Modell-Download (dataSync-FGS, Controller, Setup-UI) + M5b

**Reviewer:** DeepSeek V4 Pro 0813 · **Datum:** 2026-10-27
**Gegenstand:** `model/{ModelStore,ModelDownloader,DownloadController,ModelDownloadService,DownloadPolicy,…}`, `SetupActivity` + Layout/Strings, Manifest (INTERNET wieder drin, zwei FGS). **Gradle-Lock belegt → kein Build** (reine Lektüre + Bewertung der Orchestrator-Empirie).

---

## Befundtabelle

| ID | Sev | Datei:Zeile | Befund | Evidenz | Fix |
|---|---|---|---|---|---|
| S5-1 (R-3) | **P2** | `SetupActivity.kt:176-179` + `strings.xml:19` | **Netzschalter AUS ⇒ Meldung „Netzwerkfehler" schreibt die Schuld der Verbindung zu.** Bei `DownloadPolicy.Decision.NetworkDisabled` (Nutzer hat den Schalter selbst aus- bzw. nach READY automatisch ausgeschaltet) wird `setup_error_network` gezeigt („Prüfen Sie die Verbindung…"). Das ist irreführend: die Verbindung ist (meist) in Ordnung, der Schalter steht auf AUS. | `DownloadPolicy.decide`: `!networkEnabled → NetworkDisabled`; `SetupActivity.kt:178` `stateView.text = getString(R.string.setup_error_network)`. | Neuer String `setup_network_disabled` („Netzwerkzugriff ist deaktiviert — aktivieren Sie den Schalter für Downloads.") und für `NetworkDisabled` verwenden. |
| S5-2 | P2 | `InputConnectionSink.kt:49-62` | **`onEditExecutor` nutzt `latch.await()` ohne Timeout — Deadlock bei Aufruf vom Main-Thread.** Der Block wird auf den `MAIN_EXECUTOR` (`Handler(main).post`) gelegt und dann synchron `latch.await()` geblockt. Ruft je ein Aufrufer `replaceBeforeCursor`/`textBeforeCursor` vom Main-Thread (z. B. künftiger Tastatur-Commit-Pfad), blockiert `await()` den Main-Looper, der gepostete Block kann nie laufen ⇒ Deadlock/ANR. Heute nicht erreicht (Sink wird nur vom ASR-Worker auf `Dispatchers.Default` aufgerufen), aber die Invariante („JEDE InputConnection-Operation läuft auf diesem Executor") macht den Sink zu einem Main-Thread-Semaphor. | Z.52 `editExecutor.execute { try { r=block() } finally { latch.countDown() } }`; Z.62 `latch.await()` (kein Timeout). | Reentranz-Guard: wenn bereits auf dem Main-Looper, `block` direkt inline ausführen; sonst `await(timeout, …)` mit Fail-fast (`EditorWriteException("Editor-Edit lief in Timeout")`). |
| S5-3 | P2 | `VoiceKeyboardService.kt` `onMicDown` („Start fehlgeschlagen") | **„Start fehlgeschlagen" ist nicht differenziert.** Gerät ohne Mikrofon, Audio-Init-Fehler und `startFailed` kollabieren auf dieselbe Meldung; `coordinator.lastError` (`audioInit:…`, `startFailed`) wird nicht in einen Nutzertext übersetzt. Der Orchestrator-Befund („kein Mikrofon ⇒ Start fehlgeschlagen, kein Crash") bestätigt: funktional sauber, aber der Nutzer weiß nicht, ob es am Gerät, an der Berechtigung (die ist zwar vorab geprüft) oder am Audio-Subsystem liegt. | `updateStatus("Start fehlgeschlagen")` ohne Auswertung von `c.lastError`. | Drei Klassen abbilden: (1) „Kein Mikrofon gefunden" (AudioRecord-Gerät fehlt/routet null), (2) „Audio-Start fehlgeschlagen" (`startFailed`/`audioInit`), (3) „Modell nicht bereit" (bereits separat). `lastError`-Code → String-Schlüssel mappen. |
| S5-4 | Note | `strings.xml:29` vs. Manifest `:9-10` | **„…schaltet die App den Netzwerkzugriff für sich selbst ab" ist präziser zu formulieren.** `INTERNET`/`ACCESS_NETWORK_STATE` bleiben **dauerhaft** im Manifest; „abschalten" ist nur das App-interne Flag `network_for_downloads` (gated den Download-Start), keine OS-Permission. Der Folgesatz („in den Android-Einstellungen die Netzwerkberechtigung komplett entziehen") korrigiert das — deshalb keine harte Falschaussage, aber die Formulierung „für sich selbst ab" ist technisch unscharf (die App hat danach weiterhin faktisch keine andere Netzwerknutzung: Telemetrie-Provider entfernt). | Manifest Z.9-10 dauerhaft; `SetupActivity.networkFlagKey()` nur in `DownloadPolicy.decide` konsumiert. | Text präzisieren: „…deaktiviert die App ihren Download-Schalter automatisch; es findet kein weiterer Netzwerkzugriff statt." |
| S5-5 | Note | `ModelDownloadService.kt:48` (`observer` statisch) | **Statische `observer`-Referenz hält die Activity (Lambda-Capture) — leak-anfällig.** Zwischen `onCreate` (`observer = { … runOnUiThread { render } }`) und `onDestroy` (`observer = null`) hält das Companion-`@Volatile observer` den Activity-Kontext. Rotation ruft `onDestroy` ⇒ Freigabe ✓; aber ein Prozess-Kill der Activity ohne `onDestroy` hält sie ungewollt. Kein aktueller Leak, aber fragiles Muster. | `SetupActivity.onDestroy` → `observer = null`; `ModelDownloadService.observer` statisch, captures `this`. | Schwache Referenz (`WeakReference`) oder ein `Flow`/Bound-Service statt statischem Callback; `onFinish` anstelle von rein `onDestroy` zusätzlich absichern. |
| S5-6 | Note | `ModelDownloadService.kt:78` (`FOREGROUND_SERVICE_TYPE_DATA_SYNC`) | **`dataSync`-Typ unguarded für API<34 bei `minSdk=29`.** `startForeground(…, FOREGROUND_SERVICE_TYPE_DATA_SYNC)` und `android:foregroundServiceType="dataSync"` sind API-34-Konzepte; auf Android 10–13 (minSdk 29) ist der Typ `0x100` unbekannt und kann einen `IllegalArgumentException`/`SecurityException` beim Start werfen. Emulator ist API 36 → unentdeckt. | `ModelDownloadService.kt:78` `ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC` ohne `Build.VERSION.SDK_INT >= 34`-Guard; Manifest `:62` `foregroundServiceType="dataSync"`. | Typ bei `SDK_INT < 34` auf einen existierenden Fallback (z. B. `FOREGROUND_SERVICE_TYPE_MANIFEST`=0) setzen bzw. `startForeground(id, notif)` (2-Arg) verwenden. |

---

## Punktgenaue Antworten

1. **dataSync-FGS:** `onTimeout` → `controller.cancel(); stopSelf()` ✓ (6-h/24-h-Limit, TECH_FACTS §4.1); `startForeground` sofort in `onStartCommand` ✓; `START_NOT_STICKY` + null-Intent→`stopSelf` ✓ (kein Auto-Restart); bei Activity-Zerstörung läuft der Download im Service weiter (Notification-Only) — **gewünscht** (Download soll nicht an die Ui-Activity koppeln; kein Privacy-Konflikt, keine Inhalte in der Notification). Compatibility-Lücke API<34 (S5-6).
2. **dataSync + API 36:** einmaliger Nutzer-Download (~90 s, deutlich < 6 h) ist mit dem `dataSync`-Zeitlimit kompatibel; `onTimeout` ist trotzdem vorhanden. Einzige Einschränkung = S5-6 (ältere Geräte).
3. **DownloadController/Service-Singleton:** Doppel-START abgesichert (`running`-AtomicBoolean in `DownloadController.start()`, No-Op bei Downloading/Verifying/Ready) ✓; Listener-Aggregator trennt `controller.listener` (Notification + Observer) und statischen `observer` (UI) korrekt; kein Überschreiben ✓. Leak-Risiko S5-5.
4. **`denied_forever`-Marker (Fehlklick-Risiko):** **KORREKT, kein P1.** Die Sorge, „erste Ablehnung ⇒ schon `false`", ist invertiert: nach der **ersten** Ablehnung liefert `shouldShowRequestPermissionRationale` **true** (marker wird *nicht* gesetzt); erst nach **zweiter** Ablehnung bzw. gesetztem „nie wieder fragen" (API 11+ verhält sich bei Doppelablehnung so) liefert sie `false` ⇒ Marker + Settings-Pfad. Genau dann erscheint der Dialog ohnehin nicht mehr, der Settings-Button (`setup_open_settings`, in `refreshUi` via `anyPermissionDeniedForever()` auf VISIBLE) ist erreichbar. Restrisiko (unauffällig): Gerätepolicies können `false` auch ohne Nutzeraktion liefern — nicht handlungsrelevant.
5. **Privacy-Text vs. Realität:** INTERNET+ACCESS_NETWORK_STATE dauerhaft im Manifest, aber „keine Telemetrie" stimmt (Provider entfernt). „Netz für sich selbst abschalten" = App-Flag, nicht Permission — Folgesatz korrigiert es. S5-4 zur Präzisierung.
6. **Strings:** `setup_error_network` bei Netz-AUS fehlleitend (S5-1/R-3); `setup_error_cancelled/hash/space/fgs_blocked/denied_forever` sind zutreffend.

---

## Bewertung der Orchestrator-Befunde

- **R-3 (P2):** Bestätigt und im Code belegt (`NetworkDisabled → setup_error_network`, SetupActivity.kt:178). Korrekte Meldung = eigener String, z. B. `setup_network_disabled`: „Netzwerkzugriff ist deaktiviert. Aktivieren Sie den Schalter „Netzwerk für Downloads erlauben", um das Modell herunterzuladen."
- **`denied_forever`:** korrekt (siehe Punkt 4) — Settings-Button ist im Zustand „BERECHTIGUNGEN ERTEILEN" tatsächlich sichtbar, sobald der Marker geschrieben wurde (`anyPermissionDeniedForever()` → VISIBLE).
- **Kein Mikrofon ⇒ „Start fehlgeschlagen":** nicht differenziert genug (S5-3). Drei für den Nutzer unterscheidbare Klassen: (1) kein Mikrofon-Gerät, (2) Audio-Start-/Init-Fehler, (3) Modell/Berechtigung (bereits separat).
- **Abbruch-Button** (`setup_download_cancel`, SetupActivity.kt:146 → `ACTION_CANCEL`): `onStartCommand` → `controller?.cancel()` → `stopSelf()`; `DownloadController.cancel()` ruft `currentOp?.cancel()` → `ModelDownloader.cancel()` setzt `@Volatile cancelled` → der Read-Loop wirft `ModelError.Cancelled` → `State.Cancelled`. Sichtbarkeit: `cancelButton` nur bei Downloading/Verifying VISIBLE (render), bei Idle/Cancelled/Failed/Ready GONE ✓. Reentranz bei Doppeltipp: `cancel()` ist idempotent (`currentOp?.cancel()` wirft keinen Zustand), `stopSelf()` mehrfach harmlos. Kein Fund.

---

## Verdikt

**Merge freigeben** — **0 P0, 0 P1.** Der Download läuft empirisch korrekt (671 MB, klein→groß, `.part`/`.etag`, atomare Aktivierung, Hashes exakt, FGS sauber beendet, Netz automatisch aus). Die fünf Befunde sind P2/Notes (UX-Präzision und latente Robustheit), keiner blockiert den Merge.

## Restrisiken

1. S5-2: Deadlock, falls der `EditorSink` je vom Main-Thread getrieben wird (heute nicht der Fall).
2. S5-6: `dataSync`-FGS kann auf Android < 14 (minSdk 29) beim Start werfen — auf dem API-36-Bezug (GrapheneOS) symptomlos.
3. S5-5: statischer Activity-Callback — unter normalen Lifecycle-Wechseln freigegeben, aber ein fragiler Pfad.

## Top-5-Aktionen

1. **S5-1 (R-3)** — eigenen String `setup_network_disabled` einführen und bei `NetworkDisabled` anzeigen.
2. **S5-2** — Reentranz-Guard/Timeout in `onEditExecutor` (`if (mainLooper) block() else await(timeout)+fail-fast`).
3. **S5-3** — „Start fehlgeschlagen" nach `coordinator.lastError` differenzieren (kein-Mikrofon / Audio-Init / Modell).
4. **S5-6** — `FOREGROUND_SERVICE_TYPE_DATA_SYNC` hinter `SDK_INT >= 34` guarden (Fallback für ältere Geräte).
5. **S5-4/S5-5** — Privacy-Text präzisieren; `observer` über `WeakReference`/`Flow` entschärfen.