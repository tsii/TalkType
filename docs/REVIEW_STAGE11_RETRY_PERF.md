# Review Stufe 11 — D2 (Download-Härtung) + S12 (Performance-Hebel)

**Reviewer:** DeepSeek V4 Pro 0813 · **Datum:** 2026-10-28
**Gegenstand A:** `DownloadPolicy` (416→fortsetzung), `ModelError.detailForUser` (Allowlist), `SetupActivity`, Nachdownload `ModelDownloader`-Retry. **B:** `asr/AsrThreadConfig` + Verdrahtung `OnnxSessions`. **Kein Build/Emulator.**

---

## Auftrag 1 — D2

| ID | Sev | Datei:Zeile | Befund | Evidenz | Fix |
|---|---|---|---|---|---|
| D2-01 | **P2** | `ModelError.kt:41-48` (`scrub`) | **URL-kodierte Reste schlüpfen durch.** Der Token-Filter erkennt nur **literales** `://`, `Signature`, `Bearer`, `?`-Prefix; ein URL-kodiertes Fragment `%3A%2F%2Fcdn.example.com%2F…` enthält kein literales `://` und `%` steht **in** der Zeichen-Allowlist → es überlebt `File(...).name` (kein `/`) unverändert und passiert den Char-Filter (Buchstaben/Ziffern/`%`). Ebenso überlebt ein IPv6-Leerwert `2001:db8::1` (Ziffern/`:`) — `:` ist erlaubt. Kein aktueller Leak (die Ist-Details tragen nur Dateinamen/IOException-Texte), aber die Allowlist ist an dieser Stelle löchrig. | `tok.contains("://")` nur literal; `cleaned.filter { … ch=='%' … }`. | `%`-Hex-Paare (`%[0-9A-Fa-f]{2}`) und `::`-Brackets explizit verwerfen; `%` nur nach `[0-9]` zulassen („100%") oder ganz aus der Allowlist nehmen. |
| D2-02 | Note | `ModelError.kt:45` (`File(name).name`) | **`File(name).name` kann leer laufen.** Ein Token, das nur aus `/` oder einer `/`-Endung besteht, ergibt `File("/").name == ""` → für `Network` ist `detailForUser()` dann die **leere** Zeile (die Hauptmeldung bleibt). HTTP-Code und `HashMismatch.fileName` bleiben dagegen immer lesbar (der Code ist ein separater `Int`-Zweig). | `java.io.File(tok.substringBefore('?')).name`. | Leeren Scrub auf null abbilden (keine leere Detailzeile rendern). |
| D2-03 | ✓ | `DownloadPolicy.kt:62-64` | **`416 → "fortsetzung"` korrekt.** `Http(416)` erreicht den Nutzer nur im Doppel-416-Fall (frischer Range-loser Request lieferte erneut 416); der **Vollfall** (komplette `.part`) kehrt im Downloader als Erfolg zurück und erzeugt keine Meldung. „fortsetzung" trifft also nur echte Resume-Anomalien. HTTP-Code bleibt in `detailForUser` immer sichtbar (`is Http -> "HTTP $statusCode"`). | `418/429 → "warte"; 400..499 → "server"` mit `416` vorher; `ModelDownloader` 416-Vollfall `return true`. | — |
| D2-04 | ✓ | `ModelError.detailForUser` | **Retry-Text/Verhalten konsistent.** 4xx (inkl. 408/429/416) sind **nicht** retry-bar im Downloader (`retryable = Network || Http>=500`); alle drei Texte („warte"/„fortsetzung"/„server") verlangen daher korrekt einen **manuellen** erneuten Versuch, keiner suggeriert einen laufenden Auto-Retry. 5xx → „netz" (dort läuft der interne Retry tatsächlich). | `ModelDownloader.download` Retry-Bedingung. | — |

**Auftrag 1 Verdikt: freigabefähig** — 0 P0, 0 P1, 1 P2 (D2-01) + 1 Note.

---

## Auftrag 2 — S12

| ID | Sev | Datei:Zeile | Befund | Evidenz | Fix |
|---|---|---|---|---|---|
| S12-01 | **P1** | `OnnxSessions.kt:47-51` | **`ramMb = Runtime.maxMemory()` ist der App-Qedek-Heap, nicht der Geräte-RAM → intra wird auf JEDEM Gerät auf 2 gezwungen.** `forDevice` wertet `ramMb in 1..2047` als „wenig RAM ⇒ intra=2". `Runtime.getRuntime().maxMemory()` liefert aber das Java-Heap-Limit (typisch 256–512 MB, nie ≥ 2 GB ohne `largeHeap`) — nicht das physische RAM. Folge: **jedes** Android-Gerät (auch 8 GB/8 Kerne) landet im Low-Memory-Zweig ⇒ intra 4→2 ⇒ ASR-Inferenz **langsamer als vorher** (`min(4, cores)` = 4). Der Default-Pfad ist damit **nicht** identisch zum Altverhalten — er ist eine Perf-Regression statt eines Hebels. | `AsrThreadConfig.forDevice(cores=availableProcessors(), ramMb=maxMemory()/1MB)`; `forDevice`: `if (ramMb in 1..2047) intra = intra.coerceAtMost(2)`. | Geräte-RAM aus `ActivityManager.getMemoryInfo().totalMem` (B) statt `maxMemory()` übergeben; die 2-GB-Schwelle auf echtes Low-End (1–2 GB RAM) zielen, nicht auf den ohnehin kleinen App-Heap. |
| S12-02 | Note | `AsrThreadConfig.kt` (chunkMs/hypothesisMinIntervalMs) | **Hebel 2 noch nicht verdrahtet (S12b offen).** Aktuell Defaults `chunkMs=1000`/`hypothesisMinIntervalMs=2000`, identisch zu `TICK_SAMPLES_1S`/`MIN_AUDIO_SECONDS_PARTIALS`. Bei S12b muss `chunkMs` zwingend mit der Coordinator-`tickSamples`-Aggregation synchronisiert werden, sonst schneiden Audio-Aggregation und Transkriptions-Chunk an verschiedenen Stellen (Risse/Doppelfütterung). `hypothesisMinIntervalMs` < 2000 verletzt E8 **nicht** (Commit ist zähl- und wortkantenbasiert, nicht zeitbasiert; der Same-Audio-Dedup-Guard bleibt wirksam). | Defaults; `chunkBoundaries`/`hypothesisEmissionTimes` reine Logik. | Beim S12b-Verdrahten einen Chunk/A-Tick-Abgleichtest schreiben. |
| S12-03 | ✓ | `AsrThreadConfig.kt:30-34` (`applyTo`) | **`applyTo` ist einmalig & sicher.** Reiner Aufruf `setIntraOpNumThreads`/`setInterOpNumThreads` über den Receiver; kein Null-Receiver (non-null), kein Doppel-Setzen (wird in `OnnxSessions` genau einmal pro `SessionOptions` aufgerufen). Clamp 2..6 korrekt. | `applyTo(receiver)` + `OnnxSessions` Einmalaufruf. | — |

**Auftrag 2 Verdikt: nicht freigabefähig** — 1 P1 (S12-01 perf-Regression: intra 4→2 überall).

---

## Gesamt-Verdikt

- **Auftrag 1 (D2): freigabefähig** (0 P0, 0 P1, 1 P2).
- **Auftrag 2 (S12): nicht freigabefähig** (1 P1 — `maxMemory()` als RAM-Proxy drosselt die Threads pauschal auf 2 und macht Diktat langsamer).

**Nicht geprüft (kein Gerät):** echtes CDN hinter VPN (403/429/Range-Redirect), tatsächliche ORT-Thread-Skalierung auf ARM64 unter 2 vs. 4 Threads, `ActivityManager.getMemoryInfo`-Korrektheit im Sideload-Kontext.

## Top-3

1. **S12-01 (P1)** — Geräte-RAM statt `Runtime.maxMemory()` übergeben (bzw. die Low-RAM-Schwelle auf echte 1–2-GB-Geräte zielen); sonst bleibt der Default identisch zu `min(4, cores)`.
2. **D2-01 (P2)** — `%`-Hex-Paare (`%3A%2F%2F`) und IPv6-Brackets im Scrub verwerfen (Allowlist nachschärfen).
3. **S12-02 (Note)** — beim S12b-Wiring chunkMs↔tickSamples-Abgleich + Test.