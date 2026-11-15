# PLAN_HOSTING — Ein-Tipp-Downloadquelle für QWEN / U2-en / U2-de

Stand 2026-11-15. Kein Produktcode geändert. Downloader-Vertrag (gelesen, nicht geändert):
`ModelDownloader.kt:104` → `URL(baseUrl + file.urlPath)`, `urlPath` ist standardmäßig `storeName`;
sendet `Range` + `Accept-Encoding: identity`, merkt sich `ETag`, prüft danach sha256.
`HttpURLConnection` folgt Redirects nur ohne Protokollwechsel (HTTPS→HTTPS ist ok).

## 0. Harter Befund: Dieser Container hat KEINEN HTTP(S)-Egress → der echte Mini-Test war hier unmöglich
| Test | Ergebnis |
|---|---|
| `curl -4 -v https://github.com` | TCP 443 „Connected“, TLS ClientHello **ohne Antwort**, exit 28 nach 10 s |
| `curl http://github.com`, `http://deb.debian.org`, `https://pypi.org` | `000`, Timeout |
| `bash -c '</dev/tcp/203.0.113.7/443'` (TEST-NET, gibt's nicht) | „tcp-ok“ → **transparentes Sinkhole**, TCP-ok sagt nichts aus |
| DNS `bashupload.com`, `cdn-lfs.huggingface.co` | löst nicht auf |
| `ssh -T github-voicekeyboard` | `Hi tsii/TalkType! You've successfully authenticated` ✅ |
| `git ls-remote origin` | `4437b165… refs/heads/main` ✅ |
| `gh auth status` | „You are not logged into any GitHub hosts“ |
| `~/.cache/huggingface/token` | nicht vorhanden |

Dummy für den Test (inzwischen gelöscht): `/tmp/vk_dummy.bin`, 2 097 152 B,
sha256 `a8406375b924642548780c955c0581b72c488cd2b77f0c384ddf515e22f72f5b`.
Upload-Versuche (alle gescheitert, wegen Egress — nicht wegen des Dienstes):
```
curl -F file=@vk_dummy.bin https://0x0.st                                      → 000, exit 28
curl -F reqtype=fileupload -F fileToUpload=@vk_dummy.bin https://catbox.moe/user/api.php → 000, exit 28
curl -T vk_dummy.bin https://bashupload.com/vk_dummy.bin                       → 000, exit 6 (DNS)
curl -X POST https://huggingface.co/api/repos/create                           → 000, exit 28
```
**Einzig nutzbarer Ausgang = `git push` über SSH auf `tsii/TalkType`** (siehe §2, Option C, und ihre Grenzen).
Der echte Rundlauftest muss auf dem Rechner des Nutzers laufen, siehe §5 (fertiges Skript).

## 1. Dienste (Konnektivität gemessen; Grenzen laut Dienst-Doku, Stand Wissen ≤2025 — **nicht live geprüft**)
| Dienst | hier gemessen | max. Datei | Haltbarkeit | Direktlink / Redirect | `<base><name>` 1:1? | Range/ETag | anonym? | Urteil |
|---|---|---|---|---|---|---|---|---|
| gofile.io | TCP ok, HTTPS 000 | unbegrenzt (Gast) | Gast-Uploads verfallen nach Inaktivität (~10 Tage) | **kein** stabiler Direktlink (Webseite/Cookie-Token; Direktlinks nur Premium) | nein | – | ja | ❌ |
| bashupload.com | DNS-Fehler | 50 GB | **3 Tage** | direkt | ja (Name im Pfad) | ? | ja | ❌ zu kurz |
| file.io | TCP ok, HTTPS 000 | 2 GB (free) | **nach 1. Download gelöscht** / 14 Tage | direkt | nein (Zufalls-ID) | – | ja | ❌ |
| 0x0.st | TCP ok, HTTPS 000 | **512 MiB** (qwen_prefill 752 MB passt nicht) | 30 Tage–1 Jahr, je größer desto kürzer | direkt | nein (Zufalls-ID) | ja | ja | ❌ |
| catbox.moe | TCP ok, HTTPS 000 | **200 MB** (Qwen passt nicht; U2 passt, größte 148,5 MB) | „permanent“, Betreiber-Privatprojekt; anonym **nicht löschbar** | `files.catbox.moe/<id>.<ext>` direkt | nein (Zufalls-ID) | ja | ja | ⚠ nur U2-Notnagel |
| litterbox (catbox) | TCP ok, HTTPS 000 | 1 GB | max. **72 h** | direkt | nein | ja | ja | ❌ |
| pixeldrain | TCP ok, HTTPS 000 | 10 GB (free) | Löschung nach ~60–120 Tagen ohne Abruf; Tageslimit/Drosselung free | `/api/file/<id>` | nein (Zufalls-ID) | ja | anonymer Upload seit 2024 **API-Key nötig** | ❌ |
| **Hugging Face Hub** | TCP ok, HTTPS 000 | 50 GB/Datei (Xet/LFS) | unbefristet; öffentliche Repos kostenlos | `huggingface.co/<repo>/resolve/<rev>/<pfad>` → 302 auf CDN (HTTPS→HTTPS) | **ja, inkl. Unterordner** (`shared/…`, `tokenizer/…`) | ja/ja (in App bereits erprobt: Parakeet A/B laufen genau so) | Download anonym ✅, **Upload nur mit Konto + Write-Token** | ✅ **primär** |
| **GitHub Releases** | TCP ok, HTTPS 000 | **2 GiB/Asset** (Qwen-Max 752,8 MB ok), ≤1000 Assets/Release, kein Gesamt- oder Bandbreitenlimit dokumentiert | so lange Repo+Release existieren | `github.com/<o>/<r>/releases/download/<tag>/<name>` → 302 auf `release-assets/objects.githubusercontent.com` (HTTPS→HTTPS) | Name ja, aber **kein `/` im Assetnamen** → U2 braucht Mapping | ja/ja | Download anonym **nur bei öffentlichem Repo**; Upload braucht **Token** (REST `uploads.github.com`), Deploy-Key reicht **nicht** | ✅ **Fallback** |
| Git-Repo (Deploy-Key) + raw/LFS | **SSH geht** ✅ | Git: **100 MB hart** (Push wird abgelehnt) → qwen_*, shared_weights scheitern; LFS: 2 GB/Datei | wie Repo | `media.githubusercontent.com/media/<o>/<r>/<ref>/<pfad>` | ja | ja | via Deploy-Key pushbar, Download nur öffentliches Repo | ⚠ LFS-Free-Kontingent **10 GiB Bandbreite/Monat** → ~5 Qwen-Downloads/Monat; `git-lfs` hier nicht installiert |

## 2. Empfehlung

### Primär: Hugging Face Hub, ein öffentliches Modell-Repo (z. B. `tsii/talktype-models`)
Struktur (Dateinamen 1:1 aus den lokalen Exporten, Unterordner bleiben erhalten):
```
qwen3-asr-0.6b/{encoder.int8.onnx, qwen_prefill.int8.onnx, qwen_step.int8.onnx, tokenizer.json, mel_filters.npy, mel_config.json}
u2-en/{encoder.int8.onnx, decoder_init.int8.onnx, decoder_step.int8.onnx, shared/shared_weights.bin, tokenizer/tokenizer.json, tokenizer/tokenizer_config.json, tokenizer/vocab_meta.json}
u2-de/{… gleiche 7 Namen …}
```
Werte (`<SHA>` = Commit-Hash nach dem Upload; **Commit-SHA statt `main` pinnen**, dann ist der Inhalt unveränderlich und passt dauerhaft zu den sha256-Pins):
```kotlin
const val BASE_URL_QWEN  = "https://huggingface.co/tsii/talktype-models/resolve/<SHA>/qwen3-asr-0.6b/"
const val BASE_URL_U2_EN = "https://huggingface.co/tsii/talktype-models/resolve/<SHA>/u2-en/"
const val BASE_URL_U2_DE = "https://huggingface.co/tsii/talktype-models/resolve/<SHA>/u2-de/"
```
**Nötige Downloader-/Manifest-Anpassung (klein):** Heute liefert `baseUrlFor()` für **EN und DE dieselbe**
`BASE_URL_U2`, beide Sets haben aber **identische Dateinamen** (`encoder.int8.onnx` …) mit verschiedenen Hashes.
Ein gemeinsamer Ordner ist unmöglich. Zwei gleichwertige Wege:
- (a) `BASE_URL_U2` in `BASE_URL_U2_EN` / `BASE_URL_U2_DE` aufteilen (2 Zeilen in `baseUrlFor`, Tests
  `ModelU2VariantTest:140`, `ModelStoreMultiSegmentTest:147` anpassen) — **empfohlen**, oder
- (b) `BASE_URL_U2 = ".../resolve/<SHA>/"` lassen und in `U2_EN`/`U2_DE` `urlPath = "u2-en/" + storeName` bzw. `"u2-de/"…` setzen
  (das Feld `urlPath` existiert schon genau dafür, siehe Parakeet `assets/token_vocab.bin`).
Sonst nichts: Unterordner-Pfade, 302→CDN, Range, ETag sind mit dem bestehenden Code für HF schon im Einsatz.
Offen (bekannt aus `ModelManifest.kt:59`): Die Store-Whitelist muss Namen mit mehreren Segmenten (`shared/…`) akzeptieren — das ist unabhängig vom Hoster.

### Fallback: GitHub Release auf `tsii/TalkType` (nur wenn das Repo **öffentlich** ist)
Tags `models-qwen-v1`, `models-u2-v1`; Assetnamen flach, da `/` nicht erlaubt ist:
```kotlin
const val BASE_URL_QWEN = "https://github.com/tsii/TalkType/releases/download/models-qwen-v1/"   // 6 Namen 1:1
const val BASE_URL_U2   = "https://github.com/tsii/TalkType/releases/download/models-u2-v1/"
// U2: Mapping über vorhandenes urlPath, storeName bleibt unverändert:
ModelFile("shared/shared_weights.bin", …, urlPath = "u2-en__shared__shared_weights.bin")
ModelFile("encoder.int8.onnx",        …, urlPath = "u2-en__encoder.int8.onnx")   // usw., DE mit "u2-de__"
```
Bei Qwen also keine Anpassung; bei U2 nur Manifest-`urlPath` (kein Code im Downloader).
Nachteil: Assets sind nicht inhaltlich gepinnt (sie lassen sich ersetzen) — der sha256-Check der App fängt das ab.

## 3. Einmalig beim Nutzer vs. bei uns anonym möglich
| Schritt | Wer |
|---|---|
| HF-Konto anlegen, Org/Repo `talktype-models` (public, Lizenz: Qwen3-ASR Apache-2.0, U2 MIT/Apache-2.0 laut `BART_CORRECT_CONTRACT.md` §Lizenz) | **Nutzer** |
| HF-Token mit **fine-grained write nur für dieses Repo** erzeugen, in Container als `HF_TOKEN` hinterlegen **oder** Upload selbst ausführen (§5) | **Nutzer** |
| Container braucht dafür **HTTPS-Egress** zu `huggingface.co` + CDN (heute gesperrt) — sonst lädt der Nutzer von seinem Rechner hoch | **Nutzer/Infra** |
| Fallback GitHub: Repo `tsii/TalkType` muss **öffentlich** sein; ein PAT (`contents:write`) für das Release; Deploy-Key kann **keine** Releases | **Nutzer** |
| Anonym und **ohne** Konto erreichbar: nur Wegwerf-Hoster (catbox/0x0/…) — alle untauglich (Größe, Ablauf, Zufalls-IDs) und hier ohnehin gesperrt | – |
| Danach bei uns: `<SHA>` eintragen, BASE_URL_U2 aufteilen, Tests anpassen | **wir** |

## 4. Haltbarkeit / Risiko / Löschweg
- **HF (primär):** unbefristet, CDN mit Range; Risiko: Policy-Änderung zu Speicher für kostenlose Konten (öffentliche Repos sind heute „best effort“, 1,7 GB+0,4 GB unkritisch), Repo-Sperre bei Lizenzverstoß (Lizenzen erlauben Weitergabe; Model Cards + Lizenztexte aus `tools/bart_ref/LICENSES/` mit hochladen). Commit-SHA-Pin = keine stille Inhaltsänderung. **Löschen:** Website → Settings → Delete repository, oder `hf repo delete tsii/talktype-models`; einzelne Dateien per Commit `hf upload --delete`. Achtung: alte App-Versionen mit SHA-Pin brechen dann.
- **GitHub Releases (Fallback):** haltbar, solange Repo öffentlich bleibt; Risiko: Repo-Umstellung auf privat bricht alle Downloads sofort (401/404). **Löschen:** `gh release delete models-qwen-v1 --cleanup-tag` bzw. Web-UI.
- **Wegwerf-Hoster:** wegen Ablauf (3 Tage bis 1 Jahr), Einmal-Downloads oder fehlender Löschmöglichkeit (catbox anonym) für eine ausgelieferte App **nicht vertretbar**.
- Allgemein: Integrität sichert der sha256-Pin der App ab, egal wo gehostet wird; Verfügbarkeit nicht → zwei Quellen (HF + GH Release) sind dauerhaft die robusteste Lösung (Fallback-Liste wäre spätere Downloader-Änderung, jetzt nicht nötig).

## 5. Echter Rundlauftest + Upload — fertig zum Ausführen (Rechner mit Netz)
```bash
# Mini-Test 2 MB (HF), erwartetes Ergebnis: beide sha256 gleich, Range-Antwort 206
pip install -U huggingface_hub && hf auth login            # Write-Token
head -c 2097152 /dev/urandom > vk_dummy.bin && sha256sum vk_dummy.bin
hf repo create tsii/talktype-models --repo-type model     # public
hf upload tsii/talktype-models vk_dummy.bin test/vk_dummy.bin
curl -sSL https://huggingface.co/tsii/talktype-models/resolve/main/test/vk_dummy.bin | sha256sum
curl -sSIL -H 'Range: bytes=0-99' https://huggingface.co/tsii/talktype-models/resolve/main/test/vk_dummy.bin | grep -iE '^(HTTP|location|etag|content-range)'
hf upload tsii/talktype-models --delete 'test/*' . .      # Dummy wieder weg (oder Web-UI)
# Echter Upload
hf upload tsii/talktype-models /home/dev/models/qwen-export/out-0.6b-flat qwen3-asr-0.6b   # 6 Dateien flach zusammenstellen
hf upload tsii/talktype-models /home/dev/models/bart-export/en-merged u2-en
hf upload tsii/talktype-models /home/dev/models/bart-export/de-merged u2-de
git ls-remote https://huggingface.co/tsii/talktype-models main   # → <SHA> für die BASE_URLs
```
Hinweis Quellpfade: `/home/dev/models/qwen-asr-export/` **existiert nicht** in diesem Container. Die 6 Qwen-Dateien liegen verteilt
(Größen stimmen byte-genau mit `ModelManifest.QWEN`): `qwen-export/out-0.6b/encoder/encoder.int8.onnx` (187 361 021),
`…/prefill/qwen_prefill.int8.onnx` (752 810 624), `…/step/qwen_step.int8.onnx` (752 598 179), `…/mel/mel_filters.npy` (103 040),
`…/mel/mel_config.json` (260), `qwen-export/Qwen3-ASR-0.6B-hf/tokenizer.json` (11 429 653) → vor dem Upload in einen flachen Ordner kopieren
und gegen die Manifest-sha256 prüfen. Prüfen, dass `qwen_*.int8.onnx` **keine** externen `.onnx.data` referenzieren (Größen sprechen für eingebettete Gewichte).
U2: `en-merged` 7 Dateien / 182 812 015 B, `de-merged` 7 Dateien / 250 949 979 B — Größen = Manifest.
