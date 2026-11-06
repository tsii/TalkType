# REVIEW v0.1.46 (Qwen-Zweitmodell, T22-Paket) — cortecs/deepseek-v4-pro-0813
Persistiert vom Orchestrator 2026-11-05 00:05 UTC (Review-Schutzmodus erlaubte dem
Reviewer das Schreiben nicht; Inhalt wörtlich übernommen, Run sa-5d4a45d8).

## Ergebnis
**Verdict: P0 = 0, P1 = 2 — BLOCK** (Gate "P1=0" nicht erfüllt). P2 = 1.
Alle vier Schutz-Pflichten sind ERFÜLLT; die Blockade kommt aus dem Qwen-Download-Pfad.

## Die vier Schutz-Pflichten (Urteil)
1. **Laufendes Diktat wird NIE abgebrochen — ERFÜLLT.** ModelSwitchPolicy: dictationActive ->
   Deferred; onModelVariantPrefChanged (VoiceKeyboardService.kt ~1355-1370) setzt nur
   pendingSwitch; switchNow() (~1375) nur aus Apply(idle) oder onDictationStopped (~1329);
   dictationActive() deckt STARTING/RECORDING/FINALIZING ab; shutdown() idempotent
   (DictationCoordinator.kt:1117 closed.compareAndSet).
2. **Genau EINE Engine resident — ERFÜLLT.** switchNow(): shutdown() VOR coordinator=null;
   Preload (~699) und buildCoordinator (~1170) teilen EIN Resolve activeVariantReady();
   engineSetUsedId @1238.
3. **sha256-Pinning konsistent — ERFÜLLT** (Doku-Lücke VK-3). 6 Dateien (ModelManifest.kt:106)
   byte-identisch zu Artefakten auf Disk (mel_config 260B 1888b2defc…, encoder 187361021
   327974e1…, prefill 752810624 1a505e73…, step 752598179 9a7f8946…, tokenizer 11429653
   fe1fad59…, mel_filters 103040 8abf1244…). Summe 1704302777 B.
4. **UI-Texte ehrlich + Regel 9 — ERFÜLLT.** Opus-Texte exakt; Logs nur Codes/Zahlen.

## Findings
- **VK-1 · P1 — ETag-Seitenkanal nicht variant-fähig -> Qwen-Download scheitert immer.**
  ModelStore.kt:225 etagFor / :230 storeEtag / :374 safeName defaulten auf ONNX_CPU;
  ModelDownloader.kt:121/124/127/138/145/222 ruft ohne variant auf (nur partFile:90 ist
  variant-fähig). Qwen-Namen fallen durch die A-Whitelist -> IllegalArgumentException ->
  ModelError.Storage -> Download FAILED. DownloadPolicyPerSetTest umgeht mit Fake-Downloader
  den ETag-Pfad — echter ModelDownloader-Lauf fuer Qwen ungetestet. FIX: etagFor/storeEtag
  mit variant (analog partFile), alle 6 Aufrufe umstellen + Test.
- **VK-2 · P1 — BASE_URL_QWEN Platzhalter, nicht aufloesbar.** ModelManifest.kt:39
  'https://huggingface.co/TalkType/models/qwen3-asr-0.6b-int8/' — keine HF-Org, kein
  resolve/main-Muster, Kommentar ('pinned by T22a gate') unrichtig. Download -> 404, obwohl
  UI ihn anbietet. FIX: echten Host pinnen ODER Set bis Server-Entscheid via canOfferSet=false
  'nicht freigeschaltet' halten.
- **VK-3 · P2 — Contract §3 zaehlt 5 Dateien, Manifest pinnt 6** (mel_config.json fehlt in
  §3-Zeile). Reine Doku-Lücke. FIX: §3 ergänzen.

## Abgehakt (Belege im Review)
Decode-Schleife robust (Cap 128, EOS 151643/151645, Repeat-Guard 16/8, RMS 1e-4,
Whitespace-Post-Guard); KV alias-frei; keine ORT-Session-Leaks; deleteSet/Marker-Isolation
(B1-B4); Extra_Variant-Routing (1.7B-id faellt auf A zurueck); Setup Pref nur READY / Delete
nur nicht-aktiv / Invite manifestSetId(aktiv) stabil fuer A-Bestandsnutzer; MicCaptureTest-
Reparatur akzeptiert; Bluestein-Konstanten = Contract §4.0; Prompt-Tokens = §6; UI-Strings =
Opus-Texte.

## Naechster Schritt
VK-1 + VK-2 fixen (Download-Pfad), VK-3 Doku; danach Re-Review (Gate P0=0 & P1=0).

---
## Fix-Runde (Orchestrator, Datum folgt nach coder-Meldung + Re-Review)
**Fix-Runde Persistenz (Orchestrator, 2026-11-05 01:00 UTC):** VK-1/VK-2/VK-3 gefixt
(DownloadEtagVariantTest 2/2 neu; PerSet 8/8; Setup-Karte Qwen ohne Download-Angebot bei
leerer URL; Contract §3 auf 6 Dateien/1.704.302.777 B ergaenzt). Re-Review cortecs
(sa-5d4a45d8, wörtlich): **P0=0, P1=0 — Fixes akzeptiert, Gate wiederhergestellt**; A-Pfad
binary/default unveraendert (etag-Dateinamen identisch, Floor 2,5 GB bleibt). Einzelne
optionale Anmerkung (kein P-Status): Qwen-Karte zeigt bei Nichtverfuegbarkeit noch
'Not downloaded' statt 'noch nicht veruegbar' — Label-Nuance, P2-Bericht.
Packages NEU nach Fix: release sha d28c6669… (voll in dist-Phase), debug folgt im dist-Report.
