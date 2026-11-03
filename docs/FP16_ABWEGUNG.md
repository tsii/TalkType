# Abwägung fp16 vs int8 (Parakeet TDT 0.6B v3, ONNX Runtime 1.31.1)

**Stand:** 2026-10-30 · **Rolle:** Messung/Entscheidung (keine Code-/Modell-Änderung)
**Auftrag:** prüfen, ob die bereits konvertierte fp16-Variante etwas bringt — **ohne Telefon**.
**Messort:** Container-CPU (x86_64), `onnxruntime 1.31.1` = **dieselbe** ORT-Version wie im
Android-AAR. Der Emulator fällt für native ORT-Läufe aus (SIGSEGV in `libonnxruntime.so`,
ARM-Bridge, vgl. `HANDOFF.md`), deshalb sind **alle** Zahlen hier Verhältnisse, **keine
Telefon-RTF**.
**Regel 9:** keine Audio-/Textinhalte in diesem Dokument — nur Zahlen und Sprachbeispiele
als Längenangabe.

---

## 1. Bestand & Struktur (Schritt 1)

`.models-fp16/` (gemessen, `sha256sum`):

| Datei | Bytes | SHA-256 |
|---|---|---|
| `encoder-model.float16.onnx` | 1 238 960 452 | `4fdbec7d6fc14ca97ed81f5f4eb61792a4eea752a292f81f63c5ee619b25795b` |
| `decoder_joint-model.float16.onnx` | 36 269 630 | `acc2eda03bf3a81ed53a2c529720c1ffa6fc16ca94c23953ca0d9b5b83f84d8e` |
| `nemo128.onnx` | 139 764 | `a9fde1486ebfcc08f328d75ad4610c67835fea58c73ba57e3209a6f6cf019e9f` (identisch zu int8) |
| `vocab.txt` | 93 939 | `d58544679ea4bc6ac563d1f545eb7d474bd6cfa467f0a6e2c1dc1c7d37e3c35d` (identisch zu int8) |
| `config.json` | 97 | `666903c76b9798caf2c210afd4f6cd60b08a8dbf9800ec8d7a3bc0d2148ac466` |

Summe der **vier** Modell-Dateien (das zählt `ModelStore`): **1 275 463 785 B** vs. int8
**670 619 706 B** → **1,90×**.

**Struktur-Vorgabe (nicht umgesetzt, nur beschrieben):** `ModelStore` erwartet je Variante
`active-<variante>.txt` → `versions/v-<id>/` mit den **storeNames** aus
`model/ModelManifest.kt` (`nemo128.onnx`, `encoder-model.int8.onnx`,
`decoder_joint-model.int8.onnx`, `vocab.txt`). Die fp16-Dateien heißen
`…float16.onnx` ⇒ eine Variante `ONNX_FP16` braucht **eigene storeName-Einträge** (oder
umbenannte Dateien) — ein reines Kopieren in `versions/v-*/` würde als `BROKEN` gelten.

## 2. Qualität (Schritt 2) — **identisch**

Referenzpfad `tools/reference_decode.py` (int8) bzw. eine Kopie unter `/tmp` mit
fp16-Dateinamen; Material `testdata/de_smoke.wav` (6,40 s) und `testdata/de_pause.wav` (9,90 s),
Zieltext aus `docs/ASR_CONTRACT.md` §4.1.

| Datei | int8 Tokens | fp16 Tokens | Token-IDs identisch | Text identisch | Zeichen |
|---|---|---|---|---|---|
| `de_smoke.wav` | 27 | 27 | **ja** | **ja** | 79 / 79 |
| `de_pause.wav` | 25 | 25 | **ja** | **ja** | 79 / 79 |

⇒ **Wort-Identität 100 %, CED 0 %** gegen die jeweilige int8-Referenz (und damit auch
gegen §4.1). **Kein** Qualitätsgewinn durch fp16 auf unserem Material — erwartbar, weil int8
dort bereits fehlerfrei ist.

### 2a. Befund: die ausgelieferten fp16-Dateien sind **nicht ladbar**

Ohne Eingriff scheitert der Session-Aufbau reproduzierbar:

```
onnxruntime.capi.onnxruntime_pybind11_state.Fail: [ONNXRuntimeError] : 1 : FAIL :
Load model from .models-fp16/encoder-model.float16.onnx failed: Type Error:
Type parameter (T) of Optype (Add) bound to different types
(tensor(float) and tensor(float16) in node (/pre_encode/Add).
```

**Ursache (graphgenau):** alle 612 Initializer sind fp16 (Typ 10), die Graph-IOs sind
bewusst fp32 (`keep_io_types`), **aber** drei `Cast`-Knoten im `pre_encode`-Block
(`/pre_encode/Cast`, `Cast_1`, `Cast_2`) casten nach **float32** (`to=1`) und speisen damit
fp16-`Add`-Knoten — ein Typkonflikt, den der Konverter nicht aufgelöst hat.

**Diagnose-Patch (nur zum Messen, `to=1` → `to=10`, Kopie unter `/tmp`, Repo unberührt):**
Damit lädt das Modell und liefert **exakt** die int8-Texte (§2).

**Wichtig — der Patch ist KEINE Produktionslösung:** `pre_encode` rechnet damit in fp16;
Ganzzahl-Framecounts sind in fp16 nur bis **2048** exakt (11-Bit-Mantisse). Bei 990 Mel-Frames
(9,9 s) unkritisch, bei langen Diktaten (≥ ~20,5 s ⇒ ≥ 2048 Frames) droht **Rundung der
Frame-Arithmetik**. Sauber wäre: `pre_encode` in **fp32** belassen und **nach** der
Framecount-Berechnung einen `Cast` nach fp16 einfügen (Konverter-Option
`keep_io_types` + korrektes Cast-Insertion, oder Neo-Modell mit expliziten Casts).

## 3. Laufzeit (Schritt 3) — fp16 ist **langsamer**

Je 3 Läufe, Median, dieselbe CPU, `--threads 8`:

| Material | Variante | RTF (Compute) | Encoder | Decoder | Wanduhr je Prozess (inkl. Session-Load) |
|---|---|---|---|---|---|
| `de_smoke` (6,40 s) | int8 | **0,0162** | 97 ms | 4 ms | **1,34 s** |
| `de_smoke` | fp16 | 0,0216 | 122 ms | 8 ms | 3,09 s |
| `de_pause` (9,90 s) | int8 | **0,0136** | 122 ms | 4 ms | **1,59 s** |
| `de_pause` | fp16 | 0,0173 | 147 ms | 16 ms | 3,12 s |

**Verhältnisse fp16 / int8:**

| Kennzahl | `de_smoke` | `de_pause` | Mittel |
|---|---|---|---|
| **RTF (Compute)** | 1,333 | 1,272 | **≈ 1,30** ⇒ fp16 **30 % langsamer** |
| **Wanduhr (inkl. Laden)** | 2,30 | 1,97 | **≈ 2,13** ⇒ **gut doppelt so langsam** am Start |
| **maxRSS beim Laden** (Kind-Prozess) | — | 924 MB → **3 838 MB** | **≈ 4,2× Speicher** |

**Begründung:** ORT-CPU hat auf x86 keine durchgehenden fp16-Kernel; das Modell wird intern
auf fp32 hochgecastet (zusätzliche Kopien) **und** die 1,90× größeren Gewichte müssen durch
denselben Speicherbus. Beides zusammen erklärt die 30 % Mehrzeit bei der Berechnung und die
verdoppelte Startzeit (1,24 GB von Platte lesen + Initializer umwandeln).

## 4. Entscheidungsvorlage

| Kriterium | int8 (heute) | fp16 | Bewertung |
|---|---|---|---|
| Größe (4 Dateien) | **670 619 706 B** | 1 275 463 785 B (**1,90×**) | int8 |
| Download (10 / 25 / 50 Mbit/s) | 9,0 / 3,6 / 1,8 min | 17,0 / 6,8 / 3,4 min | int8 |
| Qualität auf unserem Material | 100 % (§4.1 exakt) | **100 %** (identische IDs) | **gleich** |
| Compute-RTF (Container-CPU) | 0,0136–0,0162 | 0,0173–0,0216 (**1,30×**) | int8 |
| Startzeit je Prozess | 1,34–1,59 s | 3,09–3,12 s (**2,13×**) | int8 |
| maxRSS beim Laden | 924 MB | **3 838 MB** (4,2×) | int8 (**auf 4-GB-Geräten kritisch**) |
| Auslieferbarkeit heute | ja, gerätebewährt | **nein** (3 Cast-Knoten, s. §2a) | int8 |

### Empfehlung: **fp16 nicht ausliefern.** int8 bleibt die Variante.

Begründung: fp16 bringt **null** Qualitätsgewinn (identische Token-IDs), kostet **1,90×
Download**, **1,30× Rechenzeit**, **2,13× Startzeit** und **4,2× Speicher beim Laden** — auf
einem Telefon mit 4–8 GB RAM ist der Speicherpunkt der harte Ausschluss (der 671-MB-Encoder
braucht heute schon ~0,9 GB RSS; fp16 landet bei ~3,8 GB). Die verbreitete Annahme „fp16 ist
auf ARM schneller" gilt nur mit **FP16-SIMD-Kerneln im Runtime-Backend** (XNNPACK/MLAS mit
`fp16arith`) — ORT-CPU nutzt sie für dieses Modell nicht, wie die Messung zeigt.

### Was das Ergebnis **nicht** sagt

- **Keine Telefon-RTF.** Weder ARM64 noch Tensor G5 wurden gemessen; das Verhältnis 1,30 ist
  ein x86-CPU-Wert. Auf ARM könnte fp16 *theoretisch* anders liegen (FP16-SIMD existiert auf
  ARMv8.2+), ist aber ohne Gerätemessung nicht behauptbar.
- **Keine Aussage über einen NPU-Pfad** (fp16 auf GPU/NPU ist ein anderes Thema, siehe
  `NPU_LITERT_FEASIBILITY.md`).

## 5. Wenn fp16 trotzdem weiterverfolgt werden soll (Reihenfolge)

1. **Konverter-Fix**: `pre_encode` in fp32 + Cast nach fp16 (kein `to=10`-Hack), Modell neu
   erzeugen, **Ladetest** in ORT 1.31.1 (Python) und im Android-AAR — ohne diesen Test ist
   jede weitere Messung wertlos.
2. **Frame-Arithmetik prüfen**: 300-s-Fixture ⇒ Mel-Frames ≫ 2048; Ergebnis gegen int8
   vergleichen (Token-IDs).
3. **Erst dann** Gerätemessung (RTF/Startzeit/RSS) — und nur, wenn 1./2. grün sind.

## ANNAHME-Zeilen
- `ANNAHME: Die 3 Cast-Knoten sind der einzige Ladefehler; nach dem Patchen liefen beide
  Referenzdateien ohne weitere Typfehler (beobachtet, nicht formal bewiesen).`
- `ANNAHME: Der x86-RTF-Faktor 1,30 ist auf ARM nicht direkt übertragbar (FP16-SIMD).`
