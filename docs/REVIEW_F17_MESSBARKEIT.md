# Review F17b/F17c — Decode-Messbarkeit (decode_ms_max/p95, buffer_s)

**Reviewer:** DeepSeek V4 Pro 0813 · **Datum:** 2026-10-30
**Umfang:** `DictationController` (Decode-Ring), `DebugInfoFormatter`/`Collector` (+3 Felder), `DictationCoordinator`-Durchreiche, `DebugInfoCollectorTest`. Kein Build/Emulator.

---

## Befundtabelle

| Sev | Datei:Zeile | Befund | Minimaländerung |
|---|---|---|---|
| **P2** | `DictationController.kt:115-119` (`decodeTimesMs`/`decodeSeq`/`decodeSamples`) + `lastDecodeBufferSeconds` | **Data-Race (Diagnose): Worker schreibt, Main liest ohne `@Volatile`/Fence.** `decodeTimesMs[i]=ms; decodeSeq+=1; decodeSamples+=1` läuft im Worker (transcribe→noteDecode), `decodeMsMax()`/`decodeMsP95()`/`bufferSeconds()` lesen auf dem 🐞-Main-Thread. Folgen sind **nicht** negativ/gerissen (`coerceAtLeast(0)`, arm64-64-Bit-Long = atomar), aber **stale** — ein 🐞-Read kann trotz erfolgter Decodes `0`/„nie dekodiert" zeigen (fehlende Sichtbarkeit des Counts). | `@Volatile` auf `decodeSamples` (und `lastDecodeBufferSeconds`); die Schreibreihenfolge „Array-Slot zuerst, Count zuletzt" beibehalten — die Volatile-Write/Read auf den Count erzeugt den Happens-before für die Array-Inhalte. |
| **P2** | `docs/DEBUG_INFO_FIELDS.md` (Katalog) | **Feldkatalog-Drift:** die drei neuen Schlüssel fehlen in der Doku (KEYS ist inzwischen 27). B-`grep decode_ms_max/decode_ms_p95/buffer_s` → leer. | Folgende drei Zeilen in den Katalog nachtragen: `decode_ms_max  — größte Decode-Laufzeit eines transcribe-Aufrufs in ms (0 = nie dekodiert)` · `decode_ms_p95 — p95 der letzten ≤64 Decode-Läufe in ms` · `buffer_s — Pufferlänge (Sekunden) beim letzten Dekodierlauf (korreliert mit decode_ms)` . |
| Note | `DictationController.kt:132-139` (`decodeMsP95`) | **„p95" ist die p95 der letzten ≤64 Ring-Samples, nicht der Sitzung.** Benennung leicht irreführend; als Diagnose-Stichprobe ok (KDoc nennt „deterministische Stichprobe"). Kein Fehler. | KDoc/Feldname ggf. „decode_ms_p95_last64" oder Vermerk „Stichprobe" — rein dokumentarisch. |

---

## Antworten A–G

- **A) Messung korrekt.** `ms = ((clock()−start)/1e6).coerceAtLeast(0)`: 0-ms-Lauf → 0, `nanoTime`-Wrap → 0 (nie negativ), leere Historie → max==p95==0, ein Lauf → max==p95 (idx = `(1*95+99)/100−1 = 0`). Ring 64: `decodeSamples` deckelt bei 64, ältere Werte werden korrekt überschrieben, und `decodeMsMax/P95` iterieren genau die 64 gültigen Slots.
- **B) Harmloser, aber echter Race (P2 s. o.):** keine negativen/gerissenen Werte (Clamp + 64-Bit), aber **stale** `0`-Anzeige möglich. Minimalfix = `@Volatile decodeSamples` + „Array-vor-Count"-Schreibordnung.
- **C) `bufferSeconds()` = Puffer des **letzten** Dekodierlaufs** (`lastDecodeBufferSeconds = audioSeconds` in `noteDecode`, also inkl. des Finals mit vollem Puffer). Konsistent mit dem Export-Anspruch („beim letzten Dekodierlauf", nicht aktuelle Aufnahmedauer). ✓
- **D) Kein Verhaltens-/Allokations-/Blockier-Effekt:** `noteDecode` = 1 Array-Write (0 Allokation pro Decode); `decodeMsP95` allokiert `copyOf(≤64)` nur beim 🐞-Read. `window()` bleibt unverändert (`PcmWindow(pcm.copyOf(totalSamples), …)`, Z.407). ✓
- **E) Feldzahl-Drift:** im **Formatter** korrekt (KEYS 24→27, Ausgabe 28 Zeilen = 1 Header + 27 Felder, `check(lines.size == KEYS.size + 1)` Z.137 aktiv). **Doku-Drift** in `docs/DEBUG_INFO_FIELDS.md` = P2 (s. o.).
- **F) Testqualität — gut, echte Kette.** `DebugInfoCollectorTest` (Z.95-145) baut echten `DictationCoordinator` (+ `QueueSource` + Fake-Engine mit `calls`), wartet ≤3 s, `stop()`, wartet auf IDLE, assertet `engine.calls >= 1` **und** `bufferSeconds > 0.0` (echter Final-Decode, kann nicht mit Puffer 0 bestehen) und `assertNotNull(snapshot.decodeMsMax/P95/bufferSeconds)` (Z.135-137) — ein Löschen der Collector-Zeilen 101-103 macht genau diese Asserts **rot**. Zusätzlich Regex-Asserts auf die Exportzeilen (Z.143-145).
- **G) Regel 9 sauber:** nur Zahlen (`decode_ms_max/p95` ms, `buffer_s` s) — keine Inhalte/Pfade/Personenbezug.

---

## SUMME

**P0 = 0 · P1 = 0 · P2 = 2** (Worker/Main-Sichtbarkeit ohne `@Volatile`; fehlende drei Keys in `docs/DEBUG_INFO_FIELDS.md`) · 1 Note (p95-Benennung).

**Offen (zum Belegen bräuchte ich):** einen echten Lauf (`./gradlew test`) für die 376/0-Behauptung und den Testgrün-Beweis; einen 32-Bit-Emulator für die Torn-Long-Frage (theoretisch, arm64 irrelevant); den `QueueSource`-Volltext, um „liefert wirklich ≥1 Block" zu bestätigen (per grep nur Start/Stop/Deadline gesehen).

---

## Nachtrag F19 (p95-Index explicit + AsrThreadConfig-Gegenprobe)

- **p95-Index nachgerechnet** (`DictationController.kt:136` `idx = (size*95 + 99)/100 - 1`): n=1 → 0 ✓ (max==p95); n=2 → 1 (der größere Wert) ✓; n=64 → 60 (= ceil(0,95·64)−1 = 61. Index, ~95. Perzentil) ✓. Tiny: das `+99`-Vorziehen ist `ceil(0,95·n)`; bei n=64 ergibt 60 (0-basiert), korrekt.
- **G) AsrThreadConfig UNVERÄNDERT** (`AsrThreadConfig.kt:83-90`): `intra = if (safeCores <= 8) minOf(4, safeCores) else minOf(MAX_THREADS, safeCores)`, clamp 2..6, RAM-Dekkel `≤2 GB → 2`, `deviceRamMb` unverändert, `DEFAULT=intra=4`. Der kassierte Sollwert `forDevice(cores=6)→intra=6` ist **nicht** enthalten — keine Verhaltensänderung, Bestandsregel `min(4, cores)` intakt.