# Review Stufe 10 — Download (D1: Fehlerklassifikation, 416/200-Resume, Retry, Fern-Diagnose)

**Reviewer:** DeepSeek V4 Pro 0813 · **Datum:** 2026-10-28
**Gegenstand:** `DownloadPolicy.userMessageKey`, `ModelError.detailForUser`, `ModelDownloader` (416/200/Retry), `SetupActivity`, `strings.xml`. **Kein Build/Emulator** (Orchestrator: 242 Tests/0 Fehler; SHA des CDN = Manifest, Range/206 bedient).

---

## Befundtabelle

| ID | Sev | Datei:Zeile | Befund | Evidenz | Fix |
|---|---|---|---|---|---|
| D1-01 | **P2** | `ModelError.kt:47-51` (`scrub`) | **Scrub ist nicht exhaustiv (Defense-in-depth-Lücke, kein aktueller Leak).** `scrub` entfernt nur `https?://\S+` und `[?&](Signature|Expires|xip)=\S*` — ein URL **ohne** Schema (Bare-Hostname/Pfad, `ftp://`) oder ein Dateisystempfad unter `files/` bliebe stehen; auch URL-dekodierte Reste (`%2F`) nicht behandelt. In **dieser** Codebasis gelangt aber nie eine URL in ein Detail: `Http.detail="für <filename>"`, `HashMismatch.fileName` (whitelisted), `Storage` → fest „Speicher", nur `Network.detail = IOException.message` (systemgeneriert, meist „Connection refused/newtwork unreachable", ohne URL). Ein künftiger `opener`, der URLs in den Exception-Text schreibt, würde vom Scrub nur teils gefangen. | `scrub = s.replace(Regex("https?://\\S+"),"").replace(…Signature…Expires…xip…,"").trim().take(48)`. | Scrub härten: auch `ftp://`, `\S+://`, bare `\S+/\S+`-Pfade und `files/…`-Muster entfernen; zusätzlich „nur Wörter/Zahlen/-/·"-Allowlist statt Blacklist. |
| D1-02 | Note | `DownloadPolicy.kt:64` (`in 400..499 -> "server"`) | **`Http(416)` läuft unter „server" und liest sich als „Server hat die Datei abgelehnt" — irreführend** für ein Range-Satisfiability-/Resume-Problem. 416 erreicht den Nutzer nur nach dem Ein-Restart-Fallback (frischer Range-loser Request lieferte trotzdem 416 = echte Server-Anomalie), also selten — aber „abgelehnt" passt nicht zur Kausalität. | `when(statusCode){ 408,429->"warte"; in 400..499->"server"; else->"netz" }` (416 ∈ 400..499). | 416 explizit vorziehen (eigener Schlüssel „datei"/`setup_error_range` oder weiter „neues versuchen"), damit die Meldung nicht „Server verweigert" suggeriert. |
| D1-03 | Note | `ModelDownloader.kt` (Retry-Loop + Backoff) | **Bis zu 5 Versuche (Initial + 4 Retries) × 652 MB bei instabiler Leitung.** Backoff 1/2/4/8 s; die `.part` wird über Versuche wiederverwendet (kein Neustart, außer `200`/`>sizeBytes` truncate) — Fortschritt resettet also nur bei echter Kürzung, was korrekt ist. Summe kann bei dauerhaft kippender Verbindung ~5+ min „Retry ohne sichtbaren Fortschritt" ergeben; kein Endloslauf (`attempt<=maxRetries`), `Cancelled` nie retry-bar. | `while (attempt <= maxRetries)`, `Thread.sleep(backoffBaseMs shl (attempt-1).coerceAtMost(4))`. | Optional: Fortschritts-Listener mit „Versuch n/5"-Signal füttern, damit die UI nicht still steht. |
| D1-04 | Note | `ModelDownloader.kt` (`else { written = part.length() }`) | **Toter Zweig.** `input` ist nach der frühen 416-Behandlung nie mehr `null` (416 kehrt vorher zurück; 200/206 liefern immer `inputStream`) — der `else`-Zweig mit `written = part.length()` ist Restcode. Harmlos, aber verwirrend. | `val input = connection.inputStream` (nie null nach 416-Rückkehr); `else { written = part.length() }`. | Zweig entfernen oder bewusst leer lassen. |
| D1-05 | ✓ | `ModelDownloader.kt` (200/416/`>sizeBytes`) | **Integrität hält in allen Ästen.** 200-statt-206 ⇒ `RandomAccessFile.setLength(0)` (Kein Anhängen) + Digest ab 0; 416 vollständig ⇒ Direkt-Hash; 416 kleiner ⇒ ein Neustart, dann `Http(416)`; `written > sizeBytes` ⇒ `.part` auf 0 + `Network` (retry-bar). End-zu-Ende-Hash läuft **in jedem** Pfad (Prefix eingelesen + Body im Digest, `written != sizeBytes` → Fehler). Keine gekürzte `.part` gilt je als fertig. | `RandomAccessFile(part,"rw").use{ it.setLength(0) }` bei `!rangeAccepted`; `raf.setLength(0); throw Network(...)` bei `written>sizeBytes`. | — |
| D1-06 | ✓ | `DownloadPolicy.kt:56-68` + `SetupActivity.kt:291-299` | **Klassifikationsrichtung korrekt + vollständig verdrahtet.** 408/429 → „warte" (temporär), 400..499 → „server" (dauerhaft 4xx), 5xx/Rest → „netz". Der tote `if(>=500) "netz" else "netz"`-Zweig ist beseitigt. `SetupActivity` mappt `"server"→setup_error_server`, `"warte"→setup_error_wait`, `"netz"→setup_error_network` und zeigt zusätzlich `detailForUser()` (keine Fallback-auf-„abgebrochen"-Regression). | Z.295-296 `"server"/"warte"`; Z.299 `detailForUser()`. | — |
| D1-07 | ✓ | `ModelError.kt:56-66` (`detailForUser`) | **Fern-Diagnose kurz + diktierbar.** `"HTTP 403 · encoder-model.int8.onnx"` (≤48 Z., ohne URL/Token); `Network` nur gescrubbtes `detail`; `Storage`/`Cancelled` feste Wörter. Enthält nichts, das per Ferndiktat gefährlich wäre. | `take(48)`, `removePrefix("für ")`. | — |

---

## Punktgenaue Antworten

1. **Privatsphäre:** `detailForUser` liefert im Ist-Code nie eine URL/Pfad/Header — `Http` trägt nur den Dateinamen, `HashMismatch` ebenso, `Storage`→„Speicher"; einzige offene Stelle ist `Network.detail` (IOException-Text), der systemgeneriert und URL-frei ist. Der Regex-Scrub ist **nicht** exhaustiv (Schema-lose URLs/Bare-Pfade schlüpfen durch), daher P2 als Härtungslücke, kein aktuelles Leak (D1-01).
2. **Klassifikation:** Richtung korrekt (4xx dauerhaft, 408/429 temporär, 5xx transcient); 416 fällt als 4xx unter „server" und klingt („Datei abgelehnt") fürs Resume-Szenario leicht falsch (D1-02).
3. **Integrität:** Kein Pfad, in dem eine gekürzte `.part` ohne Hash als fertig gilt; 200-Trunkierung und `>sizeBytes` sind abgedeckt (D1-05).
4. **Retry:** begrenzt (5 Versuche, Backoff), Resume erhalten, kein Endloslauf; nur Dauer ~5+ min möglich (D1-03).
5. **Regressionen:** Cancel nie retry-bar ✓, alle neuen Schlüssel verdrahtet (D1-06), keine neuen Permissions/Netz-Nebeneffekte erkennbar.
6. **Fern-Diagnose:** kurz, diktierbar, ohne Sensibles (D1-07).

---

## Verdikt

**freigabefähig für eine Diagnose-/Release-APK** — **0 P0, 0 P1, 2 P2** (D1-01 Scrub-Härtung, D1-02 416-Labeling) + 2 Notes. Die D1-Echtklassifikation behebt den gemeldeten „Netzwerkfehler bei Datei 2"-Bug korrekt; Integrität und Retry sind solide.

**Nicht geprüft (kein echtes CDN hinter VPN):** tatsächliches 403/429-Verhalten des CDN, Range-Redirect (200-statt-206) am echten Endpoint, WireGuard-Route-Interaktion, Fortschrittswahrnehmung über mehrere 652-MB-Retries.

## Top-3

1. **D1-01 (P2)** — `scrub` auf Allowlist umstellen (nur Wörter/Zahlen/`·`/`-`), damit auch Schema-lose URLs/Bare-Pfade künftig ausgeschlossen sind.
2. **D1-02 (Note)** — `Http(416)` einen eigenen Schlüssel geben (kein „Server abgelehnt").
3. **D1-04 (Note)** — toten `else`-Zweig in `downloadAttempt` entfernen.