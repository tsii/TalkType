# T25a-GATE Lizenz-/Größen-Nachweis — Summarizer-Kandidaten A/B/C

Alle Quellen am 2026-11-05 geprüft (HF-Hub-API + resolve-Downloads auf Revisions-Shas
gepinnt). Lizenztexte/Model-Cards lokal gesichert in diesem Ordner.
Regel: **Apache-2.0 oder besser = BESTANDEN**; jede CC/BY-/NC-/Clause-Komponente =
NOTBESTANDEN (Plan §2/§9 "Licence unverified" — kein Kandidat shippt auf "~").

---

## Kandidat A — Qwen/Qwen3-1.7B (Plan: "Qwen3-1.7B-Instruct", primär)

| Feld | Wert |
|---|---|
| HF-ID / Revision | `Qwen/Qwen3-1.7B` @ `70d244cc86ccca08cf5af4e1e306ecf908b1ad5e` |
| Exakte Lizenz | **Apache-2.0** (`LICENSE`, 11 343 B, sha256 unten) |
| Apache-2.0 oder besser? | **JA** |
| Zusätzliche Clauses | **NEIN** — LICENSE ist der kanonische Apache-2.0-Volltext mit Copyright-Zeile "Copyright 2024 Alibaba Cloud" (identische Datei zu B, byte-gleich); cardData `license: apache-2.0`, keine `license_link`-Abweichung, kein CC/NC/Tradement-Zusatztext in der Model-Card |
| weights bf16 (Hub) | 4 063 515 592 B (2 Shards: 3 441 185 608 + 622 329 984) ≈ 4,06 GB |
| int8-Zielgröße (Plan §2) | ~1,6–1,9 GB nach §12-Fusion (ZIEL, T25b-Messung) |
| Lokal gesichert | `LICENSE_Qwen3-1.7B.txt`, `README_Qwen3-1.7B.md` |

Hinweis: Qwen3-0.6B/1.7B sind die post-trained (instruct-fähigen) Modelle der Serie
("Training Stage: Pretraining & Post-training", Card); die Plan-Bezeichnung
"Qwen3-1.7B-Instruct" meint diese HF-IDs.

## Kandidat B — Qwen/Qwen3-0.6B (Plan: "Qwen3-0.6B-Instruct", Fallback)

| Feld | Wert |
|---|---|
| HF-ID / Revision | `Qwen/Qwen3-0.6B` @ `c1899de289a04d12100db370d81485cdf75e47ca` |
| Exakte Lizenz | **Apache-2.0** (`LICENSE`, 11 343 B, byte-identisch zu A) |
| Apache-2.0 oder besser? | **JA** |
| Zusätzliche Clauses | **NEIN** (wie A) |
| weights bf16 (Hub) | 1 503 300 328 B ≈ 1,50 GB (Single-File) |
| int8-Zielgröße (Plan §2) | ~0,65–0,75 GB (ZIEL) |
| Lokal gesichert | `LICENSE_Qwen3-0.6B.txt`, `README_Qwen3-0.6B.md` |

## Kandidat C — HuggingFaceTB/SmolLM2-1.7B-Instruct (Nur-Prüfung, kein Export)

| Feld | Wert |
|---|---|
| HF-ID / Revision | `HuggingFaceTB/SmolLM2-1.7B-Instruct` @ `31b70e2e869a7173562077fd711b654946d38674` |
| Exakte Lizenz | **Apache-2.0** — aber: **kein LICENSE-File im Repo!** Nachweis über cardData `license: apache-2.0` + Model-Card-Abschnitt "## License → [Apache 2.0](https://www.apache.org/licenses/LICENSE-2.0)" (README Zeile 321 ff, lokal: `README_SmolLM2-1.7B-Instruct.md`) |
| Apache-2.0 oder besser? | **JA** |
| Zusätzliche Clauses | **NEIN** |
| weights bf16 (Hub) | 3 422 777 952 B ≈ 3,42 GB |
| int8-Zielgröße (Plan §2) | ~1,7–1,9 GB (ZIEL) |
| Lokal gesichert | `README_SmolLM2-1.7B-Instruct.md`, `apache-2.0_canonical.txt` (Kanon-Text aus `tools/bart_ref/LICENSES/`;_da kein Repo-LICENSE existiert, ist der Kanon-Text als Referenz hinterlegt — Bei Schiffs-Entscheidung für C wäre das License-File-Fehlen NOTICE-Pflichtig zu dokumentieren)

## Prüfweg (reproduzierbar)

```
curl https://huggingface.co/api/models/<id>            # cardData.license + .sha
curl https://huggingface.co/api/models/<id>/tree/main  # Dateiliste + Größen
curl -L https://huggingface.co/<id>/resolve/main/<pfad>?revision=<sha> -o <lokal>
```

## sha256 der gesicherten Lizenztexte

```
832dd9e00a68dd83b3c3fb9f5588dad7dcf337a0db50f7d9483f310cd292e92e  LICENSE_Qwen3-0.6B.txt
832dd9e00a68dd83b3c3fb9f5588dad7dcf337a0db50f7d9483f310cd292e92e  LICENSE_Qwen3-1.7B.txt
1ab64a26fcb3b461423b89a433a8c858f1bf8d4086f979cbb3ff878d47cf20e9  README_Qwen3-0.6B.md
257e52c419dac2258852643f18af6c974f21f8c6c1b6f371b6cca6201cf29091  README_Qwen3-1.7B.md
b00c0570dbc43674a0028d088fc72f40d83eea90f47cd987706ca2f95ed114c0  README_SmolLM2-1.7B-Instruct.md
cfc7749b96f63bd31c3c42b5c471bf756814053e847c10f3eb003417bc523d30  apache-2.0_canonical.txt
```

## GATE-Ergebnis T25a (HARD GATE, Plan §2/§8)

- **A: BESTANDEN** (Apache-2.0, lokale Datei, keine Clauses)
- **B: BESTANDEN** (Apache-2.0, lokale Datei, keine Clauses)
- **C: BESTANDEN mit Fußnote** (Apache-2.0 per Card/Metadata; kein LICENSE-File im Repo)
- damit ist die Lizenz-Hürde für die Export-Pipeline (T25a fp32, T25b int8) geräumt;
  ein NOTICE im APK ist bei Apache-2.0-Trails üblich (T25j, nicht Teil von T25a).
