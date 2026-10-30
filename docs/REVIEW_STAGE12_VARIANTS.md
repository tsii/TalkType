# Review Stufe 12 — Modell-Varianten (V0/V1/V2)

**Reviewer:** DeepSeek V4 Pro 0813 · **Datum:** 2026-10-28
**Gegenstand:** `ModelVariant`, `EngineFactory`, `ModelManifest` (je Variante), `ModelStore` (variantengetrennt), S12-01-Fix (`deviceRamMb`). **Kein Build/Emulator** (Orchestrator: 259/0).

---

## Befundtabelle

| ID | Sev | Datei:Zeile | Befund | Evidenz | Fix |
|---|---|---|---|---|---|
| V1-01 | **P1** | `ModelManifest.kt:79` (`name="assets/token_vocab.bin"`) + `ModelStore.kt` `safeChild`/`safeName` | **Varianten-B ist nicht speicherbar/aktivierbar — zwei strukturelle Brüche.** (a) Der Manifest-Name `assets/token_vocab.bin` **enthält `/`**; `safeName` (`'/' !in name`) und `safeChild` verbieten das als Traversal-Schutz → `partFile`/`movePartToStaging` werfen `require("…nicht whitelistet")` bzw. „Pfad-Traversal". (b) `safeName`/`safeChild`/`safePart` validieren **nur** über `isAllowedName(name)` (single-arg = konstruktor-`manifest`, Default **A/ONNX**) — die variantenbewusste `isAllowedName(name, variant)` wird nirgends in den Store-Pfaden genutzt. Folglich werden **alle** B-Namen (auch die 4 slug-losen `.tflite`) abgelehnt, sofern der Store mit Default-Manifest gebaut ist. ⇒ der LITERT-Download kann nie in die Staging-Version wandern und `activate(staging, LITERT)` wirft statt `false` zu liefern. | `manifest.any { it.name == name }` (A) in `isAllowedName`; `'/' !in name` in `safeName`/`safeChild`; `assets/token_vocab.bin` mit `/`. | Speichernamen von der URL-Pfadstruktur trennen: Manifest-Name = `token_vocab.bin` (flach), URL-Prefix `assets/` separat im Downloader; `ModelStore` mit dem **Varianten**-Manifest bauen (oder `safeName` auf `isAllowedName(name, variant)` umstellen). |
| V1-02 | Note | `ModelStore.kt` `activate(staging, variant)` | **Inkonsistenz Verify-vs-Pfad.** `activate(LITERT)` prüft gegen `manifestFor(LITERT)` (5 B-Dateien), baut die Pfade aber via `safeChild` gegen die **A**-Whitelist — die Prüfung läuft also gegen B, der Pfadbau gegen A. Selbst nach V1-01-Fix muss die Whitelist-Quelle konsistent sein, sonst `activate` wirft eine ungefangene `IllegalArgumentException` statt `return false`. | `for (entry in manifestFor(variant)) { val f = safeChild(…, entry.name) … }`. | `safeChild` die Menge `manifestFor(variant)` übergeben; Fehlerfall (`require`) auf `return false` abbilden, damit `activate` nie aus einem Datenfehler crasht. |
| V1-03 | ✓ | `ModelStore.kt:70-80` (`pointerFile`) | **Abwärtskompatibilität intakt (kein P0).** `pointerFile(ONNX_CPU)` nutzt `active-onnx-cpu.txt`, **falls vorhanden**, sonst das Legacy-`active.txt` (gilt als A). Ein Bestandsgerät mit `versions/v-test` + `active.txt` meldet weiter `READY` (kein 671-MB-Zwangs-Download). | `if (v1.isFile) v1 else legacy`. | — |
| V0-01 | ✓ | `ModelVariant.kt` (`fromId`), `EngineFactory.kt:22` | **Verhaltensneutralität für A-Nutzer.** Default = `ONNX_CPU`, `fromId(null/unbekannt)→ONNX_CPU`, `create(variant)=ParakeetAsrEngine()` (V0: beide Varianten → bestehende ONNX-Engine). Ohne ausdrückliche Wahl bleibt Engine-Typ, Modellpfad (`modelDir()`=A), Download-Größe und Statusanzeige identisch. | `fun create(variant) = ParakeetAsrEngine()`. | — |
| V2-01 | ✓ | `AsrThreadConfig.kt:73` + `OnnxSessions.kt:52` + `VoiceKeyboardService.kt:159-160` | **S12-01-Fix korrekt.** `deviceRamMb` wird aus `ActivityManager.getMemoryInfo().totalMem` (Geräte-RAM) gesetzt; `OnnxSessions` liest nur noch `AsrThreadConfig.deviceRamMb`. Kein `Runtime.maxMemory()`-Rest in Produktionspfaden (nur Kommentar). `@Volatile var` (0 = unbekannt) ist für „einmal setzen, mehrfach lesen" ausreichend; ein ASR-Load vor dem Setzen sieht 0 ⇒ keine Deckelung ⇒ identisch zum Altverhalten (`min(4, cores)`), kein Stale-Wert über Nutzerumgebungen (prozess-skopiert). | `deviceRamMb = memInfo.totalMem / 1MB`; `ramMb = AsrThreadConfig.deviceRamMb`. | — |

---

## Punktgenaue Antworten

1. **Neutralität:** kein Pfad zu anderem Verhalten ohne Wahl (V0-01). ✓
2. **Manifest B:** Größen/SHA256 laut Vorgabe eingetragen — **aber** der `assets/`-Pfadanteil wird als **Speichername** geführt (V1-01) und kollidiert mit dem Traversal-Guard + der A-Whitelist.
3. **Abwärtskompatibilität:** Bestands-A bleibt READY (V1-03); kein P0.
4. **Whitelist je Variante:** nicht verdrahtet — `isAllowedName(name, variant)` existiert, wird aber in `safeName`/`safeChild`/`safePart` nicht benutzt (V1-01/V1-02). Kein Überschreiben von A-Dateien erkennbar (getrennte Varianten-Zeiger), aber B ist schlicht funktionslos.
5. **V2-Fix:** korrekt (V2-01); Diagnosezeile `T=` konnte ich mangels Lesezeit nicht gegen die gesetzte Threadzahl prüfen (`ANNAHME:` konsistent, da `forDevice` nur aus `deviceRamMb`+`availableProcessors` speist).
6. **Regeln:** keine Dateien außerhalb der Liste; kein `maxMemory()`-Rest; Privacy (Regel 9) in neuen Pfaden unverletzt (nur Konstanten/Dateinamen geloggt).

---

## Verdikt

- **V0: freigabefähig** (0 P0, 0 P1).
- **V1: nicht freigabefähig** (1 P1 — B-Variante ist durch `assets/`-Name + variantenblinde Whitelist nicht herunterladbar/aktivierbar).
- **V2: freigabefähig** (0 P0, 0 P1; S12-01 sauber).

**Gesamt: nicht freigabefähig** (V1-P1 blockiert die gelieferte Varianten-Funktion).

**Nicht geprüft:** echtes Laden der LiteRT-`.tflite`-Dateien, `LiteRtAsrEngine` (V3, noch nicht Teil hier), `T=`-Diagnosezeile, HF-URL-Struktur von `assets/token_vocab.bin` am Live-Endpoint.

## Top-3

1. **V1-01 (P1)** — Speichernamen von der URL-Pfadstruktur trennen (`token_vocab.bin` flach; `assets/` nur als URL-Präfix) und `ModelStore` mit dem Varianten-Manifest bauen bzw. `safeName` variantenbewusst machen.
2. **V1-02 (Note)** — `activate` darf aus einem Namens-/Datenfehler nie werfen (`require`→`return false`), Pfadbau und -prüfung auf dieselbe Whitelist-Quelle stellen.
3. **V2 (Note)** — beim nächsten Lauf die `T=`-Zeile gegen `forDevice`-Ergebnis verifizieren.