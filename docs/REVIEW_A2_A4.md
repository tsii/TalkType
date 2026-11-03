# REVIEW — v0.1.33 Schnitt A2 + A4 (DeepSeek V4 Pro 0813)

**Datum:** 2026-11-02 · **Reviewer:** cortecs/deepseek-v4-pro-0813 (sa-d487be58, read-only) · **Verdikt: P0/P1 = 0 · Push-Empfehlung JA**
(Übernahme wörtlich vom Reviewer-Report; Datei per Orchestrator geschrieben, da Review-Slot ohne Schreibrecht.)

## Geprüfte Evidenz
- **A2a DeltaTyper**: `ReviseKind`/`ReviseOutcome` geschlossen, kein Null (`DeltaTyper.kt:38-54`); W3d-Latch einmalig, Reset nur nach erfolgreichem apply (`:96,126-131,149`); Gesamtrevision `replaceLength == eigene Länge`, Fremdpräfix byteweise unangetastet (`DeltaTyperTest.kt:337-360` ForeignPrefixSink); `reviseFinal` Besitz-zuerst (`:180-184`); SAME ohne Sink (`:272-277`).
- **A2b Controller/Tracker**: W1-Rebaseline exakt E1c (`DictationController.kt:614-615`); `lost=false` nur im `suffixProven`-Zweig (`OwnershipTracker.kt:236-242`), ownEditFresh (`:246-255`)/stillOurs (`:256-260`) löschen nicht, actual==null ablehnend (`SchreibstoppTest.kt:150-172`); Finale nie W3d-blockierbar (`SchreibstoppTest.kt:130-165`).
- **migrierter Bestandstest** `foreignSelectionBlocksFurtherWrites` (`DictationControllerTest.kt:224-257`): Cursor-Griff ⇒ Re-Baseline+Schreiben; Fremdinhalt ⇒ dauerhaft W1 ohne Sink-Zugriff, `lost` bleibt — Vertrag korrekt getragen.
- **A4a/A4b**: 53 Alt-KEYS Reihenfolge unverändert +10 neue, 64 Zeilen (`DebugInfoFormatter.kt:341` check); `event=window` 17 Metriken, Regel-9-Regex, geschlossene Codes; Release-Wirksamkeit `VoiceKeyboardService.kt:1048-1050` außerhalb `BuildConfig.DEBUG`.
- **Nebenläufigkeit/A1**: Counter @Volatile Einfach-Writer, Finale nach join, Reset in `start()` (`:432-449`); A1-Baselines NACH `src.start()`/VOR Thread-Start (`DictationCoordinator.kt:424-431`); Schwellwerte 3000/3000/2500 unverändert.
- Test-XMLs aller betroffenen Klassen: failures=0 errors=0.

## Findings
### P2 — `ownershipDeniedAtS` vor Re-Baseline-Versuch gesetzt
- Ort: `DictationController.kt:606-616` — `ownershipDeniedCount += 1`/`ownershipDeniedAtS` (`:606-609`) laufen VOR `tracker.rebaseline(...)` (`:614-615`).
- Wirkung: gelungener Suffixbeweis + Schreiben hinterlässt trotzdem Besitzverlust-Zeitstempel ⇒ falsch-positiver `owner_lost_at_s>=0` (Spannungsfeld PLAN §3.3).
- Minimalvorschlag: Zähler/Stempel erst im `if (!rebased || !isOwned)`-Zweig. Reiner Diagnose-Fix.

**Kein P0/P1.** Empfehlung: P2 im B-Schnitt als Mikrobeitrag mitnehmen, danach Endbau.
