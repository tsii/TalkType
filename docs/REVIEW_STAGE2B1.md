# Review Stufe 2 B.1 — ASR-Engine (Hypothesis / OnnxSessions / ParakeetAsrEngine)

**Reviewer:** DeepSeek V4 Pro 0813 · **Datum:** 2026-10-27
**Gegenstand:** `asr/Hypothesis.kt`, `asr/OnnxSessions.kt`, `asr/ParakeetAsrEngine.kt`
(+ `app/build.gradle.kts`, `AndroidManifest.xml`), geprüft gegen
`ASR_CONTRACT.md` §2/§3, `IMPLEMENTATION_PLAN.md` B.1, `DESIGN.md`, `TECH_FACTS.md` §3.
**Nicht ausgeführt:** `./gradlew` (paralleler Build). Nur Lese-Zugriff + diese eine Datei.

**Gesamtverifikation (Gutteil):** Der Tensorvertrag (§2) und der Greedy-Pfad (§3) sind
**exakt** umgesetzt — Namen/Dtypes/Ränge stimmen, `(a)/(b)/(c)` (State-Advance nur bei
Non-Blank, `t += dur` auch bei Blank, Pro-Frame-Limit 10) sind korrekt, Budget (§6) passt.

---

## Befundtabelle

| ID | Sev | Datei:Zeile | Befund | Evidenz | Konkreter Fix |
|---|---|---|---|---|---|
| SB1-01 | **P1** | `asr/TdtGreedy.kt:90` + `asr/AsrContract.kt:49` | **Sondertoken-Filter unvollständig.** Der Greedy-Loop filtert nur `token !in SPECIAL_TOKEN_IDS` mit `SPECIAL_TOKEN_IDS = 0..4`. ASR_CONTRACT §2.4/§7-4 verlangt: **alle** IDs mit Muster `<...>` filtern (0..233 und 244..273) **plus** `ID 8192` — und IDs 234..243 (Ziffern) behalten. Konsequenz: Sprachtokens wie `<\|de\|>` (78), `<\|0\|>`…`<\|10\|>`, `<\|spkN\|>`, `<\|spltokenN\|>` (244..273) werden emittiert und landen in `Hypothesis.tokenIds` **und** `text` (`Vocabulary.detokenize` filtert nicht). Der vollständige Filter existiert bereits (`Vocabulary.isSpecial`, Vocabulary.kt:52-61) und ist korrekt getestet (`VocabularyTest` prüft 78/244), wird aber im Decode-Pfad **nicht** aufgerufen. | `TdtGreedy.kt:90` `if (token !in AsrContract.SPECIAL_TOKEN_IDS)`; `AsrContract.kt:49` `SPECIAL_TOKEN_IDS = 0..4`; `TdtGreedyTest.specialTokensNotEmitted` testet nur 0/3/4 (nicht 78/244). | In `TdtGreedy.decode` den Emittier-Filter auf `Vocabulary.isSpecial(id)` umstellen (bzw. den `<...>`+8192-Test in den Loop legen) und den Test um IDs 78/244 ergänzen. |
| SB1-02 | P2 | `asr/OnnxSessions.kt:56-58,65` vs. `IMPLEMENTATION_PLAN.md:308` | **Fehlerabbildung ohne eigenen Typ.** Der Plan verlangt „NaN/Dim-Fehler ⇒ `ModelError`"; es gibt keine solche Klasse — die Engine wirft generisches `IllegalStateException`/`IllegalArgumentException` (`check`/`require`). Der DictationController kann Modellfehler nicht typisiert abfangen (kein `ModelError`-catch). | `grep ModelError` → nur `IMPLEMENTATION_PLAN.md:308`, keine Klasse; `OnnxSessions.checkIo` wirft `IllegalStateException`. | `ModelError`-Klasse (z. B. `sealed`) im `asr`-Paket definieren und an den Fehlstellen (Session-Validierung, NaN, Dim-Fehler) werfen; Controller-Catch darauf. |
| SB1-03 | P2 | `OnnxSessions.kt:56-58` | **Partieller Session-Leak beim Laden.** `createSession` für mel/enc/dec läuft ohne try/finally; wirft `createSession(decoder)` (korrupte Datei), bleiben mel+enc ungeschlossen (Encoder ≈ 848 MB nativ, ASR_CONTRACT §5). Der `s.close()`-Try/Catch (Z.64-66) deckt nur die **Validierung** ab, nicht die Session-Erzeugung. | Z.56-58 drei `env.createSession(...)` ohne Umfassung; `catch { s.close(); throw }` erst ab Z.64 (validiert `validateMel` etc.). | Sequenzielles try/finally: jede bereits erzeugte Session bei Folgefehler schließen (oder erst alle erzeugen, dann in einem `use`-Block validieren). |
| SB1-04 | P2 | `ParakeetAsrEngine.kt:44,118-122` | **`transcribe` blockiert den Aufrufer-Thread.** `suspend` enthält keinen Aufhängepunkt und keinen `withContext(Dispatchers.*)`: `mel.run`/`encoder.run`/`decoder.run` (Zeile 122, native Aufrufe) laufen synchron auf dem Aufruf-Thread. DESIGN §2.3-4 verlangt einen dedizierten ASR-Worker — diese Datei erzwingt das nicht; ruft der (noch fehlende) `AsrScheduler` direkt vom Main-Thread, friert die UI ein. | Z.44 `suspend fun transcribe`; kein `withContext`/`Dispatchers` im Rumpf, `TdtGreedy.decode` (Z.122) blockierend. | Im `AsrScheduler` (nächste Stufe) zwingend `withContext(Dispatchers.Default)` um `transcribe`; alternativ hier per Konvention dokumentieren/erzwingen. |
| SB1-05 | P2 | `ParakeetAsrEngine.kt:233,236-237` | **Allokationen im Frame-Loop.** Pro Joint-Schritt neu allokiert: `logits` (8198 floats ≈ 32 KB), `o1`/`o2` (je 1280 floats), plus `copyOf`/`copyOfRange` und 5 `OnnxTensor` + Buffer-Wraps. Bei 124 Frames (9,9 s) ≈ ~8 MB+ Churn; widerspricht der Speicher-/GC-Sorgfalt aus DESIGN §2.3 („Gesamtdiktat nicht ständig als Float duplizieren"). `frame`/`targets`/`targetLength` sind bereits als Felder vorgehalten, `logits`/`o1`/`o2` nicht. | Z.233 `FloatArray(LOGIT_COUNT)` in `step()`; Z.236/237 `FloatArray(2*PRED_HIDDEN)` je Aufruf. | `logits`/`o1`/`o2` als wiederverwendete Felder der `EncoderJointStep` (wie `frame`), `FloatBuffer`/`LongBuffer` einmal vorhalten. |
| SB1-06 | P2 | `ParakeetAsrEngine.kt:126-131` (nach `:122`) | **NaN-Guard läuft nach dem Decode.** `encOut` wird erst nach `TdtGreedy.decode(...)` auf `isFinite()` geprüft — ein NaN-Encoder-Output durchläuft zuerst den kompletten Decoder (Argmax auf NaN → Index 0, Zeitverschwendung), dann wird geworfen. Funktional kein falsches Ergebnis (es wird geworfen), aber Guard steht an falscher Stelle. | Z.122 `TdtGreedy.decode`; Z.126-131 die `isFinite()`-Schleife danach. | Guard vor `TdtGreedy.decode` ausführen (direkt nach Encoder-Run), damit kein Decode auf kaputtem Input läuft. |
| SB1-07 | Note | `AndroidManifest.xml:10-15` vs. `TECH_FACTS.md §3 Konzept 3` | **Telemetry-Provider nicht entfernt.** INTERNET/ACCESS_NETWORK_STATE sind korrekt via `tools:node="remove"` entfernt, aber der vom ORT-AAR eingeschleuste `ai.onnxruntime.TelemetryInitializer`-Provider fehlt. TECH_FACTS §3-3: der Provider **muss** zusätzlich entfernt werden, sonst gilt die Zusage „keine Netzverbindung" nicht vollständig (der 1DS-Client wird initialisiert; mangels INTERNET sendet er zwar nicht, meldet aber Telemetry-Totlauf). | Manifest enthält nur die zwei `uses-permission`-Removals; kein `<provider … tools:node="remove">`. | `<provider android:name="ai.onnxruntime.TelemetryInitializer" tools:node="remove" />` ergänzen. (INTERNET muss beim Modell-Download wieder rein, TECH_FACTS §3.) |
| SB1-08 | Note | `build.gradle.kts` `abiFilters` | **x86_64 im Basis-ABI-Set.** TECH_FACTS §3 Konzept 1 stuft `x86_64` als „nur für Emulator-Builds" ein; im Basis-`defaultConfig.ndk` erhöht es die Release-APK um ~41 MB native Lib. Für Sideload akzeptabel, aber nicht die Referenz-Empfehlung. | `ndk { abiFilters += listOf("x86_64", "arm64-v8a") }`. | `x86_64` in eine Emulator-/Debug-Variante auslagern (Release nur `arm64-v8a`). |
| SB1-09 | Note | `ParakeetAsrEngine.kt` (ganz) | **`min_audio_s` korrekt NICHT im Engine.** Der 2,0-s-Guard wird bewusst nicht angewendet — §7-8 legt ihn in die Pipeline (nur automatische Partials), der finale UP-Decode muss jede Länge verarbeiten. Leeres Audio (`nSamples==0`) liefert sauber leer (`ParakeetAsrEngine.kt:55-57`). Kein Fehler. | — | Unverändert; Guard gehört in den (bereits geplanten) `DictationController`/`AsrScheduler`. |
| SB1-10 | Note | `OnnxSessions.kt:74-76` | **Fragiles `zip` über ein `Set`.** `expectedNames.zip(expected)` paart Set-Elemente mit Listeneinträgen; korrekt nur, weil Kotlins `toSet()` eine `LinkedHashSet` (Insertion-Order) liefert. Bei Umstellung des Sets auf Hash-Set würde die Dtype/Rang-Zuordnung still falsch geprüft. | Z.74 `for ((name, exp) in expectedNames.zip(expected))` mit `expectedNames = expected.map{it.first}.toSet()` (Z.73). | Direkt über `expected.forEach { (name, type, rank) -> … }` iterieren statt zip. |
| SB1-11 | Note | `ParakeetAsrEngine.kt:100-102` | **Mel-Buffer wird vollständig gelesen.** `features` (FloatArray über gesamten `floatBuffer.remaining()`) wird mit `melTime` aus `features_lens` als T re-framed. Stimmt nur, solange `features_lens == T` (kein Padding); bei gepaddetem Mel-Output gäbe es einen Buffer/Shape-Mismatch in `createTensor(…, longArrayOf(1,128,melTime))`. Gemessener Export hat kein Padding (§2) → derzeit korrekt, aber implizit. | Z.100-102 `FloatArray(featTensor.floatBuffer.remaining())` + Encoder-Input-Shape `[1,128,melTime]`. | T aus `shape[2]` des Features nutzen oder explizit `require(features_lens == shape[2])`. |

---

## Punktgenaue Prüfung der Pflichtfragen (Kurzform)

1. **Tensornamen/Dtypes/Ränge §2:** **exakt.** Alle 16 Namen (mel 4, encoder 4, decoder 8) stimmen mit `AsrContract`/§2 überein, inkl. Dtypes (`FLOAT`/`INT64`/`INT32`) und Ränge. `checkIo` lehnt zusätzliche Tensoren ab („keine Toleranz"). **Keine** erfundenen/fehlenden Namen.
2. **Decoder-Pfad §3:** **exakt.** `(a)` States nur bei Non-Blank (`TdtGreedy.kt:83-88`), `(b)` `t += dur` auch bei Blank, `(c)` Pro-Frame-Limit 10 mit `emitted`-Reset (`TdtGreedy.kt:92-101`). Logit-Split 8193/5 korrekt (`ParakeetAsrEngine.kt:238-239`), `frames = min(encoded_lengths[0], outputs.shape[2])` (`ParakeetAsrEngine.kt:114`).
3. **Budget §6:** `TokenBudget.budgetTokens = max(256, min(4096, ceil(12s)+64))` exakt; `incomplete=true`+`reason="budget"` bei Treffer (`TdtGreedy.kt:91-93`). ✔
4. **Threading/Perf:** `OrtEnvironment.getEnvironment()` überall Singleton (kein Env-Leak); `close()` schließt mel→enc→dec per try/finally; alle `OnnxTensor` in `.use { }`. Lücken: SB1-04 (kein Dispatcher), SB1-05 (Frame-Allokationen), SB1-03 (partieller Session-Leak).
5. **Fehlerbehandlung:** leeres Audio ✔, `min_audio_s` korrekt abwesend ✔, NaN-Guard vorhanden (aber spät, SB1-06). `ModelError`-Typ fehlt (SB1-02).
6. **Privacy:** keine Audio-/Text-Logs (nur Dauern/Kennzahlen, `ParakeetAsrEngine.kt:134-142`), keine Persistenz; INTERNET/ACCESS_NETWORK_STATE entfernt ✔. Provider-Entfernung fehlt (SB1-07).
7. **abiFilters/ORT:** `arm64-v8a`+`x86_64` da, ORT `1.31.1` ✔ (x86_64-Note SB1-08).
8. **Baubarkeit:** verwendete ORT-APIs (`NodeInfo`, `TensorInfo.shape`, `OnnxTensor.createTensor`, `SessionOptions.addCPU/setOptimizationLevel/addConfigEntry`) und Kotlin-Stdlib (`Float.isFinite`, `LongArray.size`) existieren; kein offensichtlich nicht-existentes Symbol gefunden.

---

## Verdikt

**Nur mit Fixes freigeben.**

Der Kern (Tensorvertrag, §3-Decoder, Budget, Privacy-Logging, Tensor-Lifecycle) ist
solide und referenzkonform — **kein P0**. Vor Release muss **SB1-01 (P1)** behoben werden:
der Sondertoken-Filter verletzt die verbindliche §2.4-Regel und kann Sprachtoken wie `<|de|>`
in `tokenIds`/`text` emittieren (der korrekte Filter `Vocabulary.isSpecial` existiert bereits,
ist aber nicht verdrahtet). Die P2 (SB1-02…06) sind Quality-/Robustheitsposten und gehören
vor Release bzw. mit dem `AsrScheduler` erledigt.

---

## Top-5-Aktionen

1. **SB1-01 (P1)** — `TdtGreedy.decode` auf `Vocabulary.isSpecial(id)` (Muster `<...>`+8192)
   umstellen; `TdtGreedyTest` um Sondertoken-IDs 78/244 erweitern.
2. **SB1-02 (P2)** — `ModelError`-Typ im `asr`-Paket einführen und an Validierungs-/NaN-/Dim-
   Fehlstellen werfen (Fehlerabbildung gemäß `IMPLEMENTATION_PLAN.md` B.1/Tests).
3. **SB1-03 (P2)** — Session-Erzeugung in `OnnxSessions.load` mit try/finally absichern
   (partiellen nativen Leak bei korruptem Modell verhindern).
4. **SB1-07 (P2)** — `ai.onnxruntime.TelemetryInitializer`-Provider per Manifest-Merger
   entfernen (TECH_FACTS §3-3); INTERNET-Entfernung beim Modell-Download wieder aufnehmen.
5. **SB1-04/SB1-05 (P2)** — `AsrScheduler` zwingend `Dispatchers.Default` um `transcribe`
   legen und die Per-Frame-Allokationen (`logits`/`o1`/`o2`) als wiederverwendete Felder
   der `EncoderJointStep` vorhalten.