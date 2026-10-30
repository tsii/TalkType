# NPU via LiteRT-AOT auf Tensor G5 — Machbarkeit für unser Diktat

**Stand:** 2026-10-28 · **Rolle:** Feasibility/Plan (keine Code-/Build-Änderung)
**Baut auf:** `docs/GPU_ACCELERATION_ANALYSIS.md` (G1), `docs/RUNTIME_SWITCH_NPU_ASSESSMENT.md` (G2)
**Legende:** **[BELEGT]** offizielle Doku · **[THESE]** Nutzer-Recherche, hier **nicht** verifizierbar ·
**[ANNAHME]** begründete Schätzung · **[NICHT PRÜFBAR]** im Container/Emulator unmöglich

> **Wichtig zur Quellenlage:** Die Nutzer-Recherche (LiteRT ≥ 2.1.6, `SocModel.TENSOR_G5`,
> `litert-npu-runtime-google-tensor`, AI-Pack-Auslieferung, `useLegacyPackaging`-Blocker,
> fail-closed-Gate) ist **plausibel und detailliert**, aber ich kann sie hier **nicht
> gegenprüfen** (kein Netz, kein Gerät, kein Gradle). Ich behandle sie als **[THESE]** und
> baue den Plan so, dass jede ihrer Annahmen eine **eigene, billige Prüfstufe** hat.
> G2 bleibt in einem Punkt bestehen: **ohne** diese Toolchain gibt es keinen NPU-Weg.

---

## 1. Verdikt (3 Zeilen)

**Gangbar — aber nur mit Neuquantisierung und nur als Parallelpfad**, nicht als Ersatz:
der Encoder ist ein Einzelgraph und damit AOT-fähig, der Decoder (stateful LSTM + Joint)
bleibt **zwingend** auf der CPU.
**Die eine Bedingung, die zuerst bewiesen werden muss:** ein **fail-closed** Nachweis, dass
der Encoder-Lauf auf dem Gerät **wirklich** auf dem NPU läuft (nicht XNNPACK) **und** der
Golden-Text aus `ASR_CONTRACT.md` §4.1 dabei **exakt** erhalten bleibt.
Fällt einer der beiden Nachweise, ist der Weg tot — unabhängig von der erreichten RTF.

---

## 2. Risiko-Tabelle

| Risiko | Eintritt | Wirkung | Nachweis |
|---|---|---|---|
| **Multi-Subgraph** („ODC only supports Single subgraph models") | **hoch** | AOT scheitert **vor** dem Vendor-Compiler; keine sinnvolle Fehlermeldung | Wir haben **3** Modelle: `nemo128.onnx` (Mel), `encoder` (652 MB), `decoder_joint` (18 MB). Nur der Encoder ist ein Kandidat; **jede** Datei muss einzeln geprüft werden, ob sie nach Konvertierung genau **einen** Subgraph hat (`[THESE]`: ODC-Check greift vor Vendor-Compiler) |
| **Statische int8-Quantisierung** (EdgeTPU will `wNa8o8`) | **sicher** | Unser Modell ist **dynamisch** quantisiert (ORT `MatMulInteger`/`DynamicQuantizeLinear`, per-Tensor-Aktivierungen zur Laufzeit). Neuquantisierung mit Kalibrierdaten nötig ⇒ **Genauigkeitsrisiko**, Golden-Text bricht wahrscheinlich | WER/Golden-Vergleich **vor** jeder NPU-Arbeit (Stufe S2) |
| **Stilles CPU-Fallback** | **hoch** | `CompiledModel.create()` meldet Fehler nicht; Modell läuft auf XNNPACK, wir messen „NPU" und messen nichts; zusätzlich Compile-/Copy-Overhead ⇒ **langsamer als heute** | Fail-closed-Gate über `LiteRtCompiledModelIsNonCpuFullyAccelerated` **[THESE]**; ohne Gate: **kein Go** |
| **Version-Pairing Toolchain/Runtime/Dispatcher** | mittel | `ai-edge-litert` (Compile-Zeit) ↔ `litert`-Runtime ↔ `libLiteRtDispatch_GoogleTensor.so` müssen zusammenpassen; Mismatch = SIGABRT/„No dispatch library found" **[THESE]** | Versions-Pinning im Build + `nm`/`readelf`-Check im CI (JVM/Emulator-Stufe S3) |
| **`useLegacyPackaging=true`** | **sicher** (nötig) | Libs werden **unkomprimiert** im APK gespeichert und bei Installation nach `/data/app/.../lib/arm64/` entpackt ⇒ **APK wächst** um die Dispatch-Lib (Größenordnung 5–20 MB **[ANNAHME]**), Installationszeit/-platz steigt; ohne das Flag liegt die Lib komprimiert im Split-APK und der Dispatcher findet sie nicht (SIGABRT) **[THESE]** | APK-Größen-Diff + `adb shell ls /data/app/*/lib/arm64/` (Gerät, Stufe S3) |
| **AI-Pack/Asset-Pack per Sideload** | **K.-o.-Risiko** | Play Asset Delivery (fast-follow/on-demand) wird **von Play ausgeliefert**; bei Sideload (`adb install`, F-Droid, direkter Download) gibt es **keinen** Delivery-Dienst. „Install-time"-Packs liegen im APK/Split, „fast-follow"/„on-demand" **nicht** [BELEGT] developer.android.com/guide/playcore/asset-delivery | **Prüfung:** entweder (a) AOT-Modell **in unser eigenes `ModelStore`-Download** legen (wir haben Resume/Hash/SHA bereits) oder (b) Install-Time-Pack ⇒ APK + Modellgröße (untragbar bei 671 MB). Empfehlung: **(a)** |
| **GrapheneOS-Berechtigungen** | niedrig | Kein GMS nötig für LiteRT selbst; NPU-Zugriff über Vendor-HAL/Dispatcher-Lib braucht **keine** Sonderrechte **[ANNAHME]**; Risiko bleibt, dass der Dispatcher ohne Play-Dienste nicht geladen wird | Gerätetest S3 (Logcat: Dispatcher-Load, `IsFullyAccelerated`) |
| **Gerätebindung des AOT-Modells** | **hoch** | AOT ist **SoC-spezifisch** (`TENSOR_G5`); für alle anderen Geräte (minSdk 29!) brauchen wir weiterhin den CPU-Pfad ⇒ **zwei Modellartefakte**, zwei Downloads, zwei Golden-Sätze | Stufe S1 (Manifest/Feature-Flag), S5 (Gerät) |
| **Frontend/Decoder auf CPU** | sicher | Mel (`nemo128.onnx`) und Decoder bleiben CPU; pro Hypothese ~512 KB Encoder-Ausgabe NPU→CPU, Decoder-Schritte 25–40 × 4 KB ⇒ **klein**, aber Latenz-Additiv pro Schritt | Messung S5 (Latenz je Hypothese) |

**Fazit der Tabelle:** Zwei Punkte sind K.-o.: **Sideload-Auslieferung** (lösbar über
unseren eigenen Downloader) und **fehlendes fail-closed-Gate** (nur auf dem Gerät prüfbar).

---

## 3. Stufenplan (TDD-Ketten, jede Stufe mit ausführbarem Nachweis)

Regel: **Test zuerst**, Abbruch = kein Weiterbauen. Stufen S0–S2 laufen **hier**
(JVM/x86_64-Emulator), S3–S5 nur auf dem Nutzergerät.

| Stufe | Ziel | Test ZUERST | Dateien | max. Dauer | Abbruchkriterium |
|---|---|---|---|---|---|
| **S0** | Parallelpfad-Gerüst ohne Verhaltensänderung | JVM: `AsrEngineFactory` liefert bei `feature=npu` **denselben** CPU-Engine-Typ wie heute; alle 232 Tests + Golden bleiben grün | `asr/EngineFactory.kt`, `model/ModelStore.kt` (nur Lesen) | 2 h | irgendein bestehender Test rot |
| **S1** | Fail-closed-Gate (Kern!) | JVM-Fake: `NpuGate.isFullyAccelerated()` liefert `false` ⇒ Engine wählt **CPU**, Logzeile „NPU nicht verfügbar", **kein** Download des NPU-Modells; `true` ⇒ NPU-Pfad | `asr/NpuGate.kt` (neu), `asr/EngineFactory.kt` | 3 h | Gate lässt ohne Nachweis NPU zu |
| **S2** | Quantisierungs-Metadaten + Manifest | JVM: `ModelManifest` kennt `quant=dynamic-int8|static-int8`; Test: NPU-Pfad verlangt **static-int8**, CPU-Pfad akzeptiert beides; Hash-/Größenprüfung | `model/ModelManifest.kt`, `model/ModelStore.kt` | 2 h | Manifest ohne `quant`-Feld akzeptiert |
| **S3** | AOT-Artefakt + Dispatch-Lib vorhanden | Gerätetest: nach Installation liegt `libLiteRtDispatch_GoogleTensor.so` **in `/data/app/.../lib/arm64/`** (nicht im APK), `IsFullyAccelerated==true`; sonst **Abbruch** | `app/build.gradle.kts` (`useLegacyPackaging`, `abiFilters`), `model/` | 4 h | Lib fehlt **oder** Gate `false` |
| **S4** | Golden-Äquivalenz | Gerätetest `s1_realEngine_realWav` mit NPU-Pfad: Text **exakt** §4.1; zusätzlich WER über 20 Sätze (neu aufzunehmen) | `androidTest/`, `testdata/` | 3 h | Text ≠ §4.1 **und** WER-Differenz > 1 %-Punkt |
| **S5** | Messung | RTF/Latenz/RAM/Thermal (§4) auf dem Gerät | `docs/PERF_NPU.md` (neu) | 2 h | RTF ≥ CPU-RTF |

**Summe:** ~16 h Bauzeit + Gerätezugang des Nutzers. Stufen S0–S2 sind ohne Gerät
abschließbar; **ab S3 ist der Emulator wertlos** (x86_64 ohne Tensor-NPU).

---

## 4. Messprotokoll für das Nutzergerät

| Zahl | Wie | Schwelle für „Umbau lohnt" |
|---|---|---|
| **RTF** pro 10 s Sprache (`de_pause.wav`) | 3 Läufe, Median, NPU vs. CPU | **NPU-RTF ≤ 0,6 × CPU-RTF** (also ≥ 1,7×) |
| **Erstwort-Latenz** | Start → erstes angezeigtes Wort | **nicht schlechter** als CPU (+0 s) |
| **RAM-Peak** | maxRSS | **≤ CPU + 10 %** |
| **Thermal 3 min** | RTF-Drift + Akku-Temperatur | **Drift < 15 %**, keine Drosselung |
| **Fail-closed-Frage** | `IsFullyAccelerated` **und** Logcat ohne XNNPACK-Kernel für den Encoder | **muss `true` sein** — sonst ist alles andere wertlos |

**Warum die 1,7×-Schwelle:** der Encoder ist ~85–90 % der Rechenzeit, aber die
Last ist **speicherbandbreitenbegrenzt** (600 MB int8-Gewichte pro Pass, geteiltes
LPDDR5X). Ein NPU kann nur gewinnen, wenn er die Gewichte **nicht** pro Pass aus dem
DRAM streamt — bei 600 MB > On-Chip-SRAM ist das unwahrscheinlich
**[ANNAHME]**. Erwartung daher **1,0–1,6×**, nicht 5×.

---

## 5a. Was dabei verloren gehen kann (und wie wir es absichern)

| Verlust | Absicherung |
|---|---|
| **Erkennungsqualität** (statische int8-Aktivierungen) | Golden-Text §4.1 **exakt** als Gate; zusätzlich WER über 20 Sätze; der Nutzer entscheidet bei WER-Differenz > 1 %-Punkt |
| **Goldene Tests** (neue Modellartefakte) | CPU-Pfad **bleibt** unverändert und behält die heutigen Goldens; NPU-Pfad bekommt **eigene** Goldens (`§4.1-npu`) |
| **APK-Größe / Installation** | `useLegacyPackaging` + Dispatch-Lib + evtl. NPU-Modell ⇒ APK-Delta messen; NPU-Modell **nicht** ins APK, sondern in den bestehenden 671-MB-Download (nur bei Tensor-G5-Geräten) |
| **Wartbarkeit** | Feature-Schalter `feature=npu` (Default **aus**), ein Codepfad, zwei Artefakte; Toolchain-Versionen gepinnt |
| **Gerätebindung** | AOT-Artefakt nur für Tensor G5; alle anderen Geräte bleiben CPU (minSdk 29 unverändert) |

---

## 6. Offene Punkte, die **zuerst** zu klären sind (Reihenfolge)

1. **AI-Pack ohne Play-Store?** → **umgehbar**, indem wir das AOT-Artefakt in unseren
   eigenen `ModelStore`-Download legen (Resume/Hash vorhanden). Kein Play nötig.
2. **Fail-closed-Gate öffentlich?** → **[THESE]** nein; wir bauen es selbst (S1) und
   behandeln es als **Pflicht-Gate**.
3. **Statische int8 für den Encoder** → Kalibrierdaten fehlen (nur 4 espeak-WAVs) ⇒
   **20–50 Sätze deutsches Material** aufnehmen, Quantizer laufen lassen, WER messen.
4. **Ein-Subgraph-Check** → pro Datei prüfen (S2), bevor Compile-Zeit investiert wird.

## ANNAHME-Zeilen

- `ANNAHME: Die Nutzer-Recherche zu LiteRT ≥ 2.1.6 / TENSOR_G5 / AI-Pack ist korrekt,`
  `aber hier nicht verifizierbar (kein Netz, kein Gerät, kein Gradle).`
- `ANNAHME: Dispatch-Lib-Größe 5–20 MB; APK-Delta muss gemessen werden.`
- `ANNAHME: Encoder-Anteil an der Gesamtzeit 85–90 %; Decoder bleibt CPU.`
