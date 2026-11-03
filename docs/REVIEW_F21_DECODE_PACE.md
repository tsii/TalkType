# Review F21 — cost-aware Decode-Takt (DecodePace, chunkMs 2000, Kein-Schiebefenster)

**Reviewer:** DeepSeek V4 Pro 0813 · **Datum:** 2026-10-30
**Umfang:** `dictation/DecodePace.kt` (neu), `DictationController` (Gate + Zähler), `DictationCoordinator:130`, `AsrThreadConfig` (chunkMs 2000 + Pace-Klammern), `DebugInfoFormatter`/`Collector` (+2 Felder), Tests. Kein Build/Emulator.

---

## Befundtabelle

| Sev | Datei:Zeile | Befund | Minimaländerung |
|---|---|---|---|
| **P2** | `docs/DEBUG_INFO_FIELDS.md:181` (+ fehlende Zeilen) | **Katalog-Drift:** die zwei F21-Schlüssel `decode_pace_ms`/`decode_skips` fehlen; die Zählung „→ 27 Felder" (Z.181) ist veraltet — `KEYS` ist inzwischen **29** (`DebugInfoFormatter.kt:115-116`: …`decode_ms_*`,`buffer_s`,`decode_pace_ms`,`decode_skips`). | Zwei Tabellenzeilen nachtragen und Z.181 auf „→ 29 Felder" korrigieren: `decode_pace_ms` — aktuelles Partial-Intervall in ms (`decodePaceMs()`; `0` = Pacing aus) · `decode_skips` — wegen Takt übersprungene Partial-Decodes dieser Session (`decodeSkips()`). |

---

## Antworten A–H

- **A) Saturierung/Ränder korrekt.** `intervalMs` (`DecodePace.kt:30-34`): `base=min(base,max)` ⇒ `base>max` ergibt immer `max`; `bufferMs<base` ⇒ `base`; `doubled` saturiert (`>MAX/2 ⇒ MAX`, negatives `*2` via `coerceAtLeast(0)` ⇒ `base`), `coerceIn(base,max)`. Nachgerechnet: 0→base, 1→base, base/2→base, base→2·base, max/2→max, max→max, max·3→max, MAX→max, negativ→base. Immer in `[min(base,max), max]`, nie Überlauf, nie negativ.
- **B) Hebel AN, Kette vollständig.** `DictationCoordinator:130` setzt `decodePacing=true`; `DictationController.PACE_BASE_MS/MAX_MS` referenzieren `AsrThreadConfig.DEFAULT.decodePaceBaseMs/MaxMs` (Z.465-466) — **eine** Quelle, kein Doppel-Hardcode. Gate `paceAllowsDecode()` (Z.256-259) ist aktiv. Kein „für die Tonne"-Zustand.
- **C) Takt ist audio-fortschrittsbasiert (tiefschläf-sicher).** `paceAllowsDecode` nutzt `secs − lastDecodeBufferSeconds` (Pufferwachstum), **nicht** `clock()` — im Deep-Sleep wächst kein Audio ⇒ kein Decode desselben Puffers. Ein 6000-ms-Ausreißer ⇒ `2·6000 → coerceIn → 6000 ms`, d. h. nächster Partial nach 6 s neuen Audios; `maxMs=6000` ist die beabsichtigte Klammer und steht in `AsrThreadConfig.DEFAULT_DECODE_PACE_MAX_MS` + DecodePace-KDoc. ✓
- **D) Finale unabhängig (kein P0).** Das Gate (Z.256) liegt ausschließlich im Partial-Pfad `feedAudio`; `stop()`/Finale ruft `engine.transcribe` (Z.332-333) **ohne** `paceAllowsDecode` — Textverlust ausgeschlossen.
- **E) chunkMs 1000→2000 ohne Latenz-/Queue-Regression.** `tickSamples = chunkMs·16000/1000` = 32000 (2 s); `Channel.UNLIMITED` unverändert; `pcm` wächst frei; `dropped_blocks` hängt nicht an der Blockgröße. Erster Partial weiter bei **2,0 s** (der `minAudioSecondsForPartials=2,0 s`-Guard dominiert; mit 1-s-Tick war der 1-s-Feed ohnehin unterhalb des Guards) — keine spätere Erstausgabe.
- **F) Testqualität gut.** `DictationControllerTest` beweist das **Überspringen** (`decodeSkips()==5`, Z.359) und `decodePaceMs()==6000` (Z.353) bei langsamer Fake-Engine sowie „Pacing aus ⇒ 0/0" (Z.382-383) — ein Rückdrehen von `decodePacing=true→false` macht diese **rot**. `DebugInfoCollectorTest` assertet zusätzlich `assertNotNull(decodePaceMs/decodeSkips)` (Z.139-140) — nur nicht den Wert (würde ein Resets nicht allein fangen, aber der Controller-Test tut es). `AsrThreadConfigTest` pinnt `chunkMs=2000` und testet die **Geometrie** weiter mit explizit `1000` — sauber getrennt (Default-Wert vs. Funktion), verschleiert nichts.
- **G) Regel 9 sauber:** `decode_pace_ms` (0..6000) und `decode_skips` (Zähler) sind reine Zahlen, keine Inhalte/Pfade.
- **H) Regel 2 erfüllt:** `DecodePace.kt`-KDoc benennt explizit „kein Schiebefenster, das ganze Fenster bleibt, einziger Hebel ist WANN" (Verweis auf KONZEPT §5.1) — kein Sliding-Window-Eingriff. Zusätzlich geprüft: `AsrThreadConfig.forDevice` (Z.83-90) ist **unverändert** (`min(4, cores)` für ≤8 Kerne); kein kassierter `intra=6`-Sollwert.

---

## SUMME

**P0 = 0 · P1 = 0 · P2 = 1** (nur Doku-Drift in `DEBUG_INFO_FIELDS.md`: zwei fehlende Keys + veraltete „27 Felder").

**Offen (zum Belegen bräuchte ich):** einen echten `./gradlew test`-Lauf (385/0 + Compile-Exit 0), den Volltext der neuen `DictationControllerTest`-Methode (per grep nur die Asserts gesehen, nicht die Fake-Engine-Taktung), und einen Gerätelauf, ob der 6 s-Partial-Takt auf langsamer Hardware subjektiv erträglich bleibt (reiner UX-Wert, kein Codefehler).