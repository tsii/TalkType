# Review Stufe 13 — LiteRT-Anbindung (V3/V5/V6a/V6b/V8)

**Reviewer:** DeepSeek V4 Pro 0813 · **Datum:** 2026-10-29
**Gegenstand:** `LiteRtAsrEngine`, `LiteRtFeaturizer`, `FeaturizerStats`, `ModelFile` (storeName/urlPath), variantenbewusste Whitelist, `EngineFactory`-Fallback. **Kein Build/Emulator** (265/0 Unit, assembleDebugAndroidTest grün — übt NICHT den Encoder-Load).

---

## Befundtabelle

| ID | Sev | Datei:Zeile | Befund | Evidenz | Fix |
|---|---|---|---|---|---|
| L1-01 | **P1** | `LiteRtAsrEngine.kt:50-53` (`f.readBytes()` + `ByteBuffer.allocateDirect`) | **Encoder-Load OOMt den App-Heap.** `load()` führt für jede `.tflite` **`f.readBytes()`** aus — für den Encoder (648 784 320 B) ist das ein **648-MB-Java-Heap-`ByteArray`**, der den Android-App-Heap (256–512 MB, kein `largeHeap`) **garantiert** sprengt (`OutOfMemoryError`) — noch vor der Direct-Kopie. Selbst mit largeHeap entsteht ein Dreifach-Peak (648 MB Heap + 648 MB Direct + ~648 MB Interpreter-native). Der Instrumented-Test öffnet nur den 262-KB-Featurizer → der 648-MB-Pfad ist ungetestet. `catch (e: Exception)` fängt das `Error` **nicht**, d. h. ein OOM lässt bereits geöffnete Interpreter offen. | `val bytes = f.readBytes()` … `ByteBuffer.allocateDirect(bytes.size).put(bytes)`; `catch (e: Exception)`. | `Interpreter(File, options)` (mmap, keine Heap-Kopie) statt Byte-Array verwenden, oder per `FileChannel` direkt in den Direct-Buffer streamen; `OutOfMemoryError` zusätzlich fangen und Teileröffnung schließen. |
| L1-02 | P2 | `LiteRtAsrEngine.kt:118-131` (`openFeaturizerOnly`/`attachFeaturizer`) | **Öffentlicher Test-Hook umgeht die Manifest-Vollprüfung.** `openFeaturizerOnly(file)` prüft nur `file.isFile` (keine Größe/SHA256 gegen `ModelManifest`); beliebiger Pfad wird als Featurizer geladen. Produktiv heute unerreichbar (B ist nicht in die IME verdrahtet, `EngineFactory.create` nicht in `buildCoordinator` verwendet), aber ein **latentes** P1 beim Verdrahten — löse ich es jetzt nicht, kann ein nicht verifizierter Pfad per Inkarnation der Engine geladen werden. | `openFeaturizerOnly` `check(file.isFile)` ohne Manifest; `attachFeaturizer(file)` öffnet blind. | `@VisibleForTesting` annotieren (oder entfernen) und den Produktions-`attachFeaturizer`/`load` gegen `ModelManifest` (Größe+SHA) prüfen; Test-Hook nie aus Produktionscode aufrufbar. |
| L1-03 | P2 | `EngineFactory.kt:31-41` (`create(variant, store)`) | **Fallback auf A ist nicht sichtbar verdrahtet.** `Result.usedVariant` trägt die *tatsächlich* gestartete Engine, aber es existiert kein Konsument, der sie in die Statuszeile/die Debug-Zeile abbildet — der Nutzer würde B „gewählt" glauben, während A läuft. Die Anzeige muss `usedVariant` (nicht die Gewählte) zeigen. | `data class Result(engine, usedVariant)`; kein Aufrufer wertet `usedVariant` aus. | Beim Verdrahten die Status-/Diagnosezeile aus `usedVariant` speisen („Variante B nicht bereit — ONNX aktiv" statt stiller B-Anzeige). |
| L1-04 | Note | `LiteRtFeaturizer.kt` `scrubForUser` | **Regel-9-Bereinigung gut, aber hostname-lose Reste bleiben.** Tokenweise: `substringAfterLast('/')`/`('\')` kürzt Pfade/URLs auf den Dateinamen (kein Host/kein `files/`-Pfad) — korrekt. Ein Token **ohne** `/` `\` (z. B. ein nackter Hostname/IP wie `cdn.example.com` oder eine `Signature=`-Query als einzelnes Token ohne `/`) bliebe stehen; Modell-Inhalte erscheinen in LiteRT-Meldungen ohnehin nicht. | `scrubForUser`: `if (tok.contains('/')||tok.contains('\\')) else tok`; `take(160)`. | Zusätzlich `://`-haltige, `?`-/`=`-Query-förmige und Nicht-ASCII-Tokens verwerfen. |
| L1-05 | Note | `build.gradle.kts` (`-Xskip-metadata-version-check`) | **Risiko niedrig, but real am Rand.** litert 2.3.2 (Kotlin ≥ 2.4-Metadaten) an Compiler 2.0.21: die Flag setzt die Metadaten-Versionsprüfung außer Kraft. Erste Reibungspunkte wären (a) `inline`-/`value class`-Methoden aus litert-Kotlin-Code (Mangled-Names/VerifyError) und (b) `@JvmDefault`-Interface-Defaults / SAM-Konvertierungen — beide erst zur Laufzeit (`NoSuchMethodError`/`VerifyError`). litert ist überwiegend Java, daher `ANNAHME:` praktisch unkritisch; ein Laufzeit-Smoke-Test des Featurizer-Pfads (bereits instrumented grün) deckt den Hauptweg ab. | — | Kotlin-Version angleichen oder Laufzeit-Smoke für jede genutzte litert-API (bereits teilweise über `V6a`-Instrumented). |
| L1-06 | Note | (Featurizer-Resultat) | **Qualitäts-Gate (≥95 % Wortidentität, ≤2 % Distanz, Ankerwörter) ist noch nicht messbar.** B-`transcribe()` wirft `AsrNotReadyException` (V4/V9/V10 ausstehend); es fehlt ein Vergleichslauf A↔B auf denselben 4 WAVs gegen `ASR_CONTRACT §4.1`. Das Gate ist definiert, aber noch kein Testapparat. | `transcribe()` wirft; kein A/B-Harness. | Sobald B dekodiert: A↔B-Vergleichslauf auf die 4 WAVs + Golden gegen §4.1 als eigenständigen Test. |
| L1-07 | Note | `app/build.gradle.kts` `abiFilters` | **Sideload-Version sollte arm64-only sein.** Debug 82,6 MB → 123,5 MB (litert-Native ×2 ABIs). Für GrapheneOS-Pixel (arm64) ließe sich x86_64 streichen; der x86_64-Emulator-Nachweis benötigt den x86_64-Lib aber weiterhin → Aufteilung über einen Release-/Sideload-Flavor (`arm64-v8a`) vs. Debug (beide ABIs). | `abiFilters += x86_64 + arm64-v8a`; +41 MB. | Release-Sideload-Variante auf `arm64-v8a` begrenzen; Debug für den Emulator beide. |
| L1-08 | ✓ | `ModelManifest.kt:17-21,81-82` + `ModelStore.kt:119,215,271-278` | **V5 (Stage-12-P1) ist behoben.** `ModelFile` trennt `storeName` (flach, `token_vocab.bin`) von `urlPath` (`assets/token_vocab.bin`); `isAllowedName(storeName, variant)` ist variantenbewusst und verbietet `/`; `activate(staging, variant)` prüft gegen das Varianten-Manifest und liefert bei fehlender/hash-falscher Datei `false`. | `storeName="token_vocab.bin"`, `urlPath="assets/token_vocab.bin"`; `isAllowedName(storeName, variant)`; `activate`→`if(!f.isFile||…) return false`. | — |

---

## Punktgenaue Antworten

1. **Speicher:** `readBytes()` (648-MB-Haufen) + `allocateDirect` (648-MB-Direct) + Interpreter-native ⇒ **App-Heap-OOM** beim Encoder (L1-01); `raw_wav` (486 KB) ist unkritisch. `close()` wird bei Partialfehler (Exception-Zweig) und im Service-`shutdown` aufgerufen, aber der OOM-Fall (Error) fängt/löscht nicht.
2. **Metadaten-Flag:** risikoarm (litert überwiegend Java); erste Reibung bei inline/value-class-calls und `@JvmDefault`/SAM (Laufzeit `NoSuchMethodError`/`VerifyError`).
3. **`openFeaturizerOnly`/`attachFeaturizer`:** latent P1 — öffentlicher, nicht-manifest geprüfter Test-Hook; produktiv derzeit unerreichbar (L1-02).
4. **Fallback-Kontrakt:** `usedVariant` existiert, aber nichts zeigt ihn an → stille A-Aktivierung; Anzeige muss die *gestartete* Engine zeigen (L1-03).
5. **Regel 9:** Pfad/URL → Dateiname (korrekt), aber slash-lose Host-/Query-Tokens bleiben (L1-04).
6. **Qualitäts-Gate:** definiert, aber noch nicht messbar (B dekodiert nicht; kein A/B-Lauf) (L1-06).
7. **ABI:** Sideload arm64-only empfehlen (L1-07).

---

## Verdikt

- **V3 (Anbindung): nicht freigabefähig** — 1 P1 (L1-01 648-MB-`readBytes`-Heap-OOM im Encoder-Load) + 2 P2 (L1-02/L1-03).
- **V5 (Whitelist/Pfad): freigabefähig** — Stage-12-P1 korrekt behoben (L1-08).
- **V6a/V6b (Featurizer-Beweis + DEX): freigabefähig** — Beweis über `runForMultipleInputsOutputs`, `NEUTRAL`-Modus fachlich begründet (toter `log_mel`); nicht selbst gegen echte `.tflite` geprüft.
- **V8 (Featurizer/Stats): freigabefähig mit Note** (L1-04/Scrub-Rest).

**Gesamt: nicht freigabefähig** (L1-01 P1) — verbleibt bis der Encoder ohne Heap-Kopie (mmap/stream) geladen wird.

**Nicht geprüft (kein Gerät/Gradle):** echtes Laden des 648-MB-Encoders, `-Xskip-metadata`-Laufzeitwirkung, `scrubForUser` gegen reale LiteRT-Fehlermeldungen, A/B-Vergleichslauf.

## Top-3

1. **L1-01 (P1)** — Encoder über `Interpreter(File, options)` (mmap) oder `FileChannel`→Direct laden — nie `readBytes()` eines 648-MB-Haufens; `OutOfMemoryError` mit Teileröffnungs-Schließung behandeln.
2. **L1-02 (P2)** — `openFeaturizerOnly`/`attachFeaturizer` auf `@VisibleForTesting` stellen und im Produktionspfad Größe/SHA prüfen.
3. **L1-03 (P2)** — `usedVariant` in der Status-/Diagnosezeile anzeigen (Fallback auf A sichtbar machen).