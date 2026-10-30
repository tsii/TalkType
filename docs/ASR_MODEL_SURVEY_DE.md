# Modell-Survey Deutsch (offline, Android/GrapheneOS) — lohnt ein anderes Modell?

**Stand:** 2026-10-28 · **Rolle:** Vergleich/Urteil (keine Code-/Build-Änderung)
**Grundlage:** Orchestrator-Messungen (A = 670 619 706 B, 100 % Wort-Identität auf
`testdata/*.wav` gegen `ASR_CONTRACT.md` §4.1, Live-Diktat auf dem Pixel grün;
B = Arm/LiteRT schnell, aber 50,2 % / sprachinstabil → siehe `VARIANT_B_QUALITY.md`).
**Kein Netz in diesem Auftrag** — alle Fremdzahlen sind **[ANNAHME]** (Doku-Wissen aus
dem Gedächtnis, hier nicht nachprüfbar) und entsprechend markiert. Entscheidungen
hängen **nicht** an diesen Zahlen, sondern an Architektur und unseren Gates.

## 0. Der Maßstab, der alles filtert

1. **Unser Golden ist exakt** (§4.1, 4 WAVs) und auf Gerät + Desktop **100 %**.
   Jeder Wechsel muss das mindestens halten — sonst ist er eine Regression.
2. **Streaming alle 0,3–1 s** mit `commit/` + `DeltaTyper` (E8: 3 Hypothesen,
   2 Wörter ungeschützt). Ein Modell, das nur 30-s-Fenster kann, bricht die Architektur.
3. **Offline, eine signierte Sideload-APK**, keine Google-Dienste.

## 1. Kandidaten im Vergleich

| # | Kandidat | Größe (installiert) | Deutsch-Qualität | Streaming 0,3–1 s? | Android-Weg | Urteil |
|---|---|---|---|---|---|---|
| **A** | **`istupakov/parakeet-tdt-0.6b-v3-onnx` int8 (heute)** | **671 MB** | **100 % auf unserem Golden** | **ja** (TDT, frame-synchron) | ONNX Runtime AAR, läuft | **Referenz** |
| A2 | dasselbe als **fp32** (`encoder-model.onnx` + `.data`) | **2,44 GB** (+41,8 MB Encoder) | ≈ int8, `ANNAHME:` ΔWER < 0,5 %-Punkt | ja | ORT, unverändert | **verworfen**: 4× Download, kein belegbarer Qualitätsgewinn (unser Golden ist schon exakt) |
| A3 | dasselbe als **fp16** | ~1,2 GB | ≈ int8 | ja | ORT-CPU fp16 nur teilweise nativ (Casts) → `ANNAHME:` kein Speedup garantiert | **Messkandidat** (siehe §4b) |
| W1 | `openai/whisper-tiny` | 39 M ≈ 40 MB (int8 ~40 MB) | Common Voice de **~30–40 % WER** `ANNAHME:` | **nein** (30-s-Fenster) | whisper.cpp (NDK) | **unbrauchbar** (Qualität) |
| W2 | `whisper-base` | 74 M ≈ 80–140 MB | CV-de **~18–25 %** `ANNAHME:` | nein | whisper.cpp | unbrauchbar (Qualität + Architektur) |
| W3 | `whisper-small` (auch `hustxuejie/whisper-small-de`) | 244 M ≈ 250 MB (int8) | CV-de **~10–15 %** `ANNAHME:` | nein | whisper.cpp / CTranslate2 (Python) | **Architekturproblem** + unter unserem Gate |
| W4 | `whisper-large-v3` / `-turbo` | 1,55 B ≈ 1,6 GB int8 (turbo ~0,8 GB) | CV-de **~4–6 %** (turbo ~5–7 %) `ANNAHME:` | nein | whisper.cpp | beste deutsche Qualität, aber 30-s-Fenster + 1,6 GB |
| W5 | `mobiuslabsgmbh/faster-whisper-large-v3`, `Systran/faster-whisper-*`, `primeline/whisper-large-v3-german`, Distil-Whisper-de | 0,8–1,6 GB | wie W4 (±1 %-Punkt) `ANNAHME:` | nein | **CTranslate2 hat kein offizielles Android-AAR** → NDK-Eigenbau `ANNAHME:` | **verworfen** (Runtime-Risiko + Architektur) |

**Architektur-Kern (das wichtigste Ergebnis dieser Tabelle):** Whisper ist
**kein** Streaming-Modell. Es verarbeitet 30-s-Fenster und muss für „Hypothese alle
0,3–1 s" entweder ständig neu über 30 s laufen (Rechenlast ×10–30) oder mit
überlappenden Fenstern gepuffert werden (Latenz ≥ 2–5 s, Randduplikate). Genau daran
ist B gescheitert, und Whisper hat dasselbe Problem in stärkerer Form. Zusätzlich ist
Whisper für **Halluzinationen auf Stille** bekannt — das ist die Fehlerklasse, die wir
mit §4.3/E5 mühsam ausgeschlossen haben.

## 2. Deutsche WER-Zahlen: was ist mit unserer Situation vergleichbar?

| Quelle | Was sie misst | Vergleichbar mit uns? |
|---|---|---|
| Common Voice de (WER) | gelesene Sätze, viele Sprecher, **gutes Mikrofon** | nur grob: unsere 4 WAVs sind **synthetisch (espeak-ng)** und roboterhaft |
| VoxForge de | Freiwillige, stark variierende Qualität | nein (Domänen-Mismatch) |
| DWD/Radio-Nachrichten | Broadcast-Sprecher, Volltext | nein (keine Kurzdiktate) |
| Unsere §4.1-Goldens | **genau unser Material** (4 Sätze, 12–27 Tokens) | **ja — das ist der einzige belastbare Maßstab** |

`ANNAHME:` Ein öffentlicher WER-Vergleich sagt bei 4 Sätzen nichts über den
Live-Diktat-Alltag; bei n=4 ist ein Unterschied von 2 %-Punkten Rauschen. Deshalb gilt
unten: **jeder Wechsel wird an §4.1 + 20 neuen deutschen Sätzen gemessen**, nicht an
Fremd-WER.

## 3. Der eigentliche Befund: unser Problem ist **Latenz/Größe**, nicht Genauigkeit

- A ist auf unserem Material **fehlerfrei (100 %)** und auf dem Gerät erprobt.
- „Größer" (fp32/Whisper-large) kann per Definition **nicht** besser als 100 % werden —
  es kann nur gleich gut sein und 2–4× mehr kosten.
- „Kleiner" (Whisper tiny/base/small) senkt die Genauigkeit **unter** unser Gate und
  bringt zusätzlich das 30-s-Architekturproblem. Kein Kandidat dieser Klasse kann
  unser §4.1-Golden halten (`ANNAHME:` belegt durch die öffentlichen WER-Bereiche).
- Was fehlt, sind **Zahlen vom Pixel**: RTF, Erstwort-Latenz, RAM-Peak, Thermik
  (G1 §6). Solange die fehlen, ist jeder Modellwechsel Spekulation.

## 4. Kosten

| Variante | Download | bei 10 Mbit/s | 25 Mbit/s | 50 Mbit/s | Gerätespeicher (zusätzlich zu heute 671 MB) |
|---|---|---|---|---|---|
| **A (heute)** | 671 MB | 9,0 min | 3,6 min | 1,8 min | 0 (Basis) |
| **A3 fp16** | ~1,2 GB | 16,0 min | 6,4 min | 3,2 min | +0,5 GB (Ersatz, wenn A entfällt) |
| **A2 fp32** | 2,48 GB | 33,1 min | 13,2 min | 6,6 min | +1,8 GB |
| **W3 whisper-small int8** | ~250 MB | 3,3 min | 1,3 min | 0,7 min | −0,4 GB (kleiner!) |
| **W4 whisper-large-v3-turbo int8** | ~0,8 GB | 10,7 min | 4,3 min | 2,1 min | +0,13 GB |
| **W4 whisper-large-v3 int8** | ~1,6 GB | 21,3 min | 8,5 min | 4,3 min | +0,9 GB |

**Auf x86_64-Emulator beweisbar** (vor dem Pixel): ONNX-Modelle (A, A2, A3) laden und
Goldens reproduzieren — ja; **Whisper-Wege** nur als whisper.cpp-CLI, nicht als
App-Pfad (kein AAR), also **nicht** app-nah beweisbar.

## 5. Empfehlung

**(a) Nichts ändern** — mit einer einzigen, billigen Mess-Ausnahme:

- **v0.1.x:** A bleibt. Kein Modellwechsel. Stattdessen die **vier Pixel-Zahlen** aus
  G1 §6 erheben (RTF, Erstwort-Latenz, RAM-Peak, 3-min-Thermik). Erst wenn RTF > 0,6
  ist, lohnt überhaupt ein Blick auf Alternativen.
- **Wenn die Zahl es rechtfertigt:** **(b) fp16-Parakeet als dritte Variante**
  (~1,2 GB) — gleiche Architektur, gleiche Runtime, halbe Gewichtsbytes; erwarteter
  Gewinn 1,2–1,6× **nur wenn** die Last bandbreitenbegrenzt ist (`ANNAHME:`), Risiko:
  ORT-CPU-fp16 teilweise über Casts → kann neutral sein. **Kostet nur eine Messung,
  keine Architekturänderung.**
- **(c) Wechsel auf Whisper/Distil/CTranslate2: nein.** 30-s-Architektur,
  Halluzinationsneigung, kein Android-AAR für CTranslate2, und die Qualität kann unser
  bereits fehlerfreies Golden nicht übertreffen.
- **(d) Doppelmodell (klein live / groß beim Loslassen):** reizvoll, aber es verdoppelt
  Download **und** Latenz-Komplexität und löst kein Problem, das wir derzeit messen
  können. **Zurückgestellt**, nicht verworfen.

## 6. Welches Gate zuerst zu messen ist

1. **RTF auf dem Pixel** (10 s Sprache, Median aus 3 Läufen) — entscheidet, ob
   überhaupt Handlungsbedarf besteht (Schwelle: > 0,6 = Handlungsbedarf).
2. Erst danach: fp16-A/B (§4b) mit denselben vier Zahlen.
3. Qualitäts-Gate bleibt **immer** §4.1 (exakt für A; für jede neue Variante:
   Wort-Identität ≥ 95 %, CED ≤ 2 %, kein fremdsprachiger Output — R3/R4-Maßstab).

## ANNAHME-Zeilen

- `ANNAHME: Alle WER-Zahlen (Whisper-Familie, CV-de) sind Gedächtniswerte ohne Netz-`
  `Prüfung; sie dienen nur der Größenordnung. Entscheidungen hängen nicht daran.`
- `ANNAHME: ORT-CPU-fp16 nutzt auf arm64 keine durchgehenden FP16-Kernel (Casts) →`
  `Netto-Gewinn unklar; muss gemessen werden.`
- `ANNAHME: CTranslate2/faster-whisper hat kein offizielles Android-AAR; ein NDK-`
  `Eigenbau ist möglich, aber nicht abgeschätzt.`
