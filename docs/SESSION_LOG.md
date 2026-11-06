- 2026-10-30 16:49 UTC: SSH deploy key pair for GitHub generated:
  ~/.ssh/<deploy-key> (ed25519,
  SHA256:LMjwQnpHd+jkU6dLPKwC+J5MG7x80A1pdiFJZzGDFmo), host alias
  '<ssh-alias>' added to ~/.ssh/config. Public key handed to the
  user; the private key stays in the container. Finding: the existing entry
  'Host github.com' points to ~/.ssh/<deploy-key>, which does not exist
  (IdentitiesOnly yes) -> direct git@github.com operations fail; not
  changed.
- 2026-10-30 16:49 UTC: user decision: English dictation is clarified, the
  MULTILANG/D12 topic stays parked (no task, no build). The remaining open
  topics are thus only: dictation level (waiting for the 🐞 export from
  0.1.10 resp. an answer 'A/B') and the remaining screenshots
  (caps/pressed/emoji panel/2nd opening, light+dark).
- 2026-10-30 16:58 UTC: first publication on GitHub: remote origin =
  <ssh-alias>:tsii/Voice-Keyboard.git (deploy key ed25519, alias
  '<ssh-alias>'), main = 8b5644f, 217 files. Signing password
  redacted in HANDOFF.md/docs/*, state/ and keystore/ newly ignored, 11.8
  GiB of tmp_pack garbage removed from .git.

## 2026-11-02 (multimodal looker delegation)
- installed: tesseract-ocr + tesseract-ocr-deu (apt) — reading the IME status
  row from images (screenshot evidence for the C2 matrix).
- 2026-11-02 13:3x UTC: 0.1.32 FINAL ACCEPTANCE AFTER P0 FOLLOW-UP. The first
  acceptance was CLEAN (editor_rebaseline_lost at 8.5 s, restart:20) — the
  user chose (a) the P0 fix. Two death modes convicted (the exp/act diagnosis
  in VK-SESS made them immediately separable): A) restartInput WITHIN our
  commit — the tracker expectation was one cycle stale on the worker side,
  the suffix test falsely reports loss (fix AP7-E1c: sink.ownText prefix
  adoption, test (g)); B) getTextBeforeCursor=null in the re-bind window, a
  dying old IC (fix AP7-E1d: a 150-ms single retry + baseline reopen against
  the silent write stop, test (h)). E1 re-acceptance PASS: audio_s=122.0,
  editor_len=339, restart_inputs=6, rebases=7, the E1d retry proven live
  (kept=1 exp=213 act=213), no abort. Debug full suite exit=0;
  EditorRestartSurvival (a)/(e) = load flaky (A/B pre-existing, stable in
  isolation). F54 screenshots: note — adb-injected long-presses
  (345/480@2170, 0.45–0.8 s) opening the punctuation menu were not capturable
  in the screenshot (pixel diff against the keyboard base showed no change in
  the keyboard area); overall Tesseract OCR found '( )' in f54_period.png;
  the delegated image reading reported 8 cells ', ! ? : ; ( )', which does
  NOT count as evidence due to the pixel contradiction. Release/debug dist
  0.1.32 newly built (SHA256 d526…5628 / f7f4…cb0d). The Kimi documentation
  session sa-2f4fe32e no longer exists — documentation instead here + to the
  user.

## 2026-11-04 — swapfile-qwen (T22a gate, Qwen-1.7B export) — angelegt und entfernt
- Der 1.7B-Decoder-Export brauchte ~17-18 GB freien RAM in einem Prozess (9 GB
  Torch-Modell + 8.2 GB ONNX-Kopie); freier RAM lag bei ~6 GB (Parallel-Sessions).
- Temporare Massnahme im Container (sudo vorhanden, 37 TB frei): 16-GB-Datei
  /swapfile-qwen (dd + mkswap). Mehrfach an-/abgeschaltet; Rettungsversuche wurden
  vom Orchestrator gestoppt (Zeitbudget, Zahlenbasis 0.6B reichte fuer den Gate-Tisch).
- Aufraeumen (Orchestrator, 22:4x UTC): swapoff + rm -f /swapfile-qwen — Datei weg,
  swapon zeigt nur noch die Container-Base-Partition (976 MB). Kein permanenter
  Zustand hinterlassen. Zahlen: docs/QWEN_ASR_CONTRACT.md §7.

## 2026-11-05 — U2-GATE BART/seq2seq Rechtschreib-Korrektur export (Subagent)
- pip: sentencepiece 0.2.3 in .venv-asr installiert (T5-Tokenizer, noetig fuer
  oliverguhr/spelling-correction-german-base = FLAN-T5-Finetune, NICHT BART —
  Aufgaben-Titel-Abweichung dokumentiert).
- HF-Downloads gepinnt: raw/de @ b3916256, raw/en @ 0e395835 nach
  /home/dev/models/bart-export/raw/ (kein Gate, Lizenzen MIT/Apache-2.0, s.
  tools/bart_ref/LICENSES/NOTES.md).
- Export/Paritaet/RTF belegt: tools/bart_ref/export_bart_onnx.py +
  run_bart_ref.py + gen_bart_goldens.py, EXPORT_LOG.txt; Goldens
  testdata/bart_goldens/{de,en}.tsv (Seed 42); Berichte reports/bart_u2_{de,en}.json.
- GO/No-Go: GO mit Bedingungen — docs/BART_CORRECT_CONTRACT.md (Groesse int8
  345.7/421.7 MB > 200 MB Ziel → Report + Merge-Empfehlung; RTF 0.02–0.06 ok).
- Kein Gradle, kein adb/Emulator, kein commit, keine App-Aenderungen.

## 2026-11-05 — T24a U2-Graph-Merge: deduplizierte Merged-Artefakte (Subagent)
- Kein Netz, kein pip noetig (onnx 1.23.2 + onnxruntime 1.31.1 aus .venv-asr).
- Analyse tools/bart_ref/merge_analysis.py → reports/bart_merge_analysis_{de,en}.json:
  embed_tokens 3x + lm_head 2x + ALLE Decoder-Layer-Gewichte (init/step) byteweise
  identisch (sha256) → en 166.5 MB / de 173.2 MB redundante Kopien in den int8-Graphen.
- Fusion Weg (b) gewaehlt (ExternalData-Rewrite, kein Neu-Export, Gewichte
  byteweise identisch): tools/bart_ref/merge_shared_initializers.py →
  /home/dev/models/bart-export/{en,de}-merged/ (3 Graphen + shared/shared_weights.bin
  + tokenizer-Kopie). Groessen: en 345.7→179.3 MB (Ziel ≤220 ✅), de 421.7→248.5 MB
  (Ziel ≤270 ✅). EXPORT_LOG.txt stage=merge ergaenzt (sha256 + Tool-Versionen).
- Paritaets-GATE tools/bart_ref/verify_merge_gate.py → reports/bart_u2_merge_{de,en}.json:
  20 U2-Goldens je Sprache, fusioniert vs UNfusioniert int8: Wort-Identitaet 100 %
  (Token-Ids identisch), Logitpfad rel 0.0; RTF en 0.018 / de 0.038.
- Ungefusionerte Artefakte unangetastet (int8-sha256 gegen EXPORT_LOG verifiziert).
  Docs: tools/bart_ref/MERGE_ANALYSIS.md (Analyse+Begruendung), BART_CORRECT_CONTRACT.md §12.
- Kein Gradle, kein adb/Emulator, kein commit, keine App-Aenderungen.

## T25a (2026-11-05, sum_ref Export)
- Werkzeug installiert: `apt-get install time` (GNU /usr/bin/time für `-v` RSS-Messung der Export-Stufen).
- Keine weiteren neuen Werkzeuge; .venv-asr unverändert (transformers 5.22.1, ort 1.31.1, torch 2.15 cpu, onnx 1.23.2).
