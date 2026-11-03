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
