# Local model import (V0.1.51, plan `PLAN_V0151.md` §D)

The Qwen3-ASR and U2 (text correction) model sets have **no download host**:
`BASE_URL_QWEN` and `BASE_URL_U2` in `ModelManifest.kt` are empty, so the
Setup cards offer no download. This is intentional (no HTTPS host was
available: no DNS name, no certificate, no write access). Instead, the sets
can be **imported from a folder on the phone** — copied there via USB cable,
a cloud drive, or any other file transfer.

**No network is used at any point.** The import reads exactly the folder the
user picked in the system folder picker; nothing is downloaded, nothing is
sent anywhere.

## How to import

1. Copy the set folder onto the phone (any location — Downloads, a cloud
   folder on the device, an OTG stick; the picker can reach it).
2. Open the app → Setup (TalkType icon).
3. On the card of the desired set ("Qwen3-ASR 0.6B" or "Text correction
   (U2)") tap **Import folder…** and pick the folder of that set.
4. The app copies and verifies every file in the background (the card shows
   "Importing and checking…"). On success the card shows **Ready** exactly
   like after a download.

The U2 card imports the set of the **current typing language** (English
keyboard → U2-en, German keyboard → U2-de). To import the other language
set, switch the typing language first (Setup → language, or the 🌐 key).

## What the folder must contain

The picked folder is the **root of one set**. Every file must exist with its
exact relative path (or the unambiguous flat fallback described below), exact
byte size and exact SHA-256 checksum (pinned
manifest in `ModelManifest.kt`; identical to the download contract — the
same hashes the downloader would verify). Extra files are ignored.

### Qwen3-ASR (QWEN3_ASR) — flat, 6 files, ~1.65 GB

| relative path | bytes | sha256 |
|---|---|---|
| `encoder.int8.onnx` | 187,361,021 | `327974e11e2914037272224fc439af093bb251d0d232c0513bc9054d1ae6adba` |
| `qwen_prefill.int8.onnx` | 752,810,624 | `1a505e73e42074a2fdded9cdef4d9484586d52dbd654760982df738e1c03e48d` |
| `qwen_step.int8.onnx` | 752,598,179 | `9a7f8946996be4f36c0627265cc18d0d929c459001d15d1dfc5b6f783cb198d5` |
| `tokenizer.json` | 11,429,653 | `fe1fad59be22a41ee293363fcf95fdedbc7c93f3b49270b1d2e18bd1399a7a05` |
| `mel_filters.npy` | 103,040 | `8abf12444e0d9c0fd6c5e49f8b977d41e47b51a5eb48d399c232578d3d5e914f` |
| `mel_config.json` | 260 | `1888b2defc1b7db6ca081bd706f31e8fc1107cd44f1eba644834251f6dccd24c` |

### U2 English (BART_SPELLING_EN) — 7 files incl. subfolders, ~179 MB

| relative path | bytes | sha256 |
|---|---|---|
| `encoder.int8.onnx` | 43,601,202 | `bba119c4dc16830e6a0a0d670167134862ce124e1ac6b39c5abc936e2436cf54` |
| `decoder_init.int8.onnx` | 7,466,649 | `d986026349e944bbf1f2da44cf5a7e7da4b4bf211b1e3a6c2b86fd7704f96629` |
| `decoder_step.int8.onnx` | 324,984 | `2d1a70e4e4217f06fe42517003ec099035447b4a83cf1e508f2cb455901dc8b2` |
| `shared/shared_weights.bin` | 127,859,712 | `0a8a2920ae3412512ee60bfc48f31984a092c77cb3efd83a7fa80d7a39c3479c` |
| `tokenizer/tokenizer.json` | 3,558,746 | `dcf1e4d967564d18f9b7721e268b8a517027319a5cac544b9c1d9a0ce0163f44` |
| `tokenizer/tokenizer_config.json` | 497 | `123732aac64b1214daae8ad115a9efcd59d935f09db70872c01efb713781ae51` |
| `tokenizer/vocab_meta.json` | 225 | `5787c24699747b0db708eccc72475af62d38a1d6661b406a562986d41257f662` |

### U2 German (BART_SPELLING_DE) — 7 files incl. subfolders, ~249 MB

| relative path | bytes | sha256 |
|---|---|---|
| `encoder.int8.onnx` | 85,228,809 | `05507b81ecdad185131bdfdaccf942cf066584eb11ea20d85e5343eb25dbb89e` |
| `decoder_init.int8.onnx` | 14,453,095 | `97872f564c62c091db9b8f47b8b743ebcafa685d1bd2b01f37a088778d6ccda2` |
| `decoder_step.int8.onnx` | 333,394 | `275a785385b759351f84a74fc2ba7a18cd64b8823838c9e73f716291fd1a2b63` |
| `shared/shared_weights.bin` | 148,509,696 | `68296cd919fa2691744c80d62c981b55047b8f1ab3ed3e6a2f31b1111da430cd` |
| `tokenizer/tokenizer.json` | 2,422,267 | `483ff18e85be28f7c6a27b6a4ed17583abf139bb016bd4bd91277e35924e536d` |
| `tokenizer/tokenizer_config.json` | 2,488 | `682dc5904ee05fef4acddad8478ccf35aea659fecb927686bf77247d978648a1` |
| `tokenizer/vocab_meta.json` | 230 | `12d90200acae09451c327cb4402af03ba310f5002507a706dd4c9f351cca2da4` |

Example folder layout for U2-en (the picked folder = the set root):

```
u2-en/
├── encoder.int8.onnx
├── decoder_init.int8.onnx
├── decoder_step.int8.onnx
├── shared/
│   └── shared_weights.bin
└── tokenizer/
    ├── tokenizer.json
    ├── tokenizer_config.json
    └── vocab_meta.json
```

The folder NAME itself does not matter ("qwen-set", "model", "Download" —
irrelevant). The exact nested path is always preferred. If that path is
absent, a file directly in the picked root with the same basename is accepted
only when that basename belongs to exactly one path in the set manifest.
For example, `shared_weights.bin` in the root can supply
`shared/shared_weights.bin` after a transfer flattened the subfolders.

This fallback never searches arbitrary subfolders and never chooses between
duplicate display names. Ambiguous names, a file used as a directory, a
directory used as a file, and unreadable provider listings are import errors.
An unreadable or corrupt exact-path file is **not** replaced by a flat copy.
All accepted files still undergo the same pinned size and checksum checks.
SAF provider document ids may be opaque; lookup uses exact, case-sensitive
display names and filters directory listings locally (providers need not
honor SQL selection arguments).

## What happens on errors

The set is activated only after EVERY file passed the size and checksum
check. On any failure the import aborts, all temporary data is removed and
an **already installed set stays completely untouched** — there is never an
intermediate state in which the set is missing or half-installed.

Visible message per failure (file verification errors name the manifest-relative
path; store-level I/O failures have no file name):

| message | cause |
|---|---|
| `File missing in the folder: <file>` | the file (or a subfolder) does not exist in the picked folder |
| `File corrupt (checksum mismatch): <file>` | wrong content — transfer error, wrong version of the file |
| `File has the wrong size: <file>` | truncated or oversized file |
| `Import failed – check the folder and try again` | unreadable source/listing, ambiguous names, storage or staging/activation I/O failure; file-specific I/O errors carry the manifest path for the UI, never technical provider details |

Closing the folder picker without a choice cancels silently — no import
started, nothing was changed.

## Real downloads

Nothing changed in the download policy: as long as `BASE_URL_QWEN` /
`BASE_URL_U2` are empty, no download is offered anywhere, and a
hand-triggered download fails fast **without any network attempt**. Real
downloads become possible the moment a host is pinned in `ModelManifest.kt`
(`BASE_URL_*`), without any further code change — the import path is an
addition, not a replacement.

## Automated regression coverage

`ModelStoreImportTest` uses small pinned manifests for success, replacement,
missing/short/oversized/corrupt files, interrupted reads, named open failures,
staging creation failure and activation write failure. Failed imports preserve
the installed set and remove their temporary data where the filesystem allows.
`SafTreeImportSourceTest` runs against a fake framework `DocumentsProvider`
with opaque ids and unfiltered listings, covering exact nested precedence,
unique flat fallback, ambiguity, wrong document types and unreadable sources.
These are local unit tests; no device, network, or full-size model is needed.

## Where to verify hashes yourself

`sha256sum <file>` on the copied files must match the tables above; the
same values are pinned in `app/src/main/java/de/voicekeyboard/model/ModelManifest.kt`
(`QWEN`, `U2_EN`, `U2_DE`). The import does this check itself — a manual
check is only needed to diagnose a failing import.
