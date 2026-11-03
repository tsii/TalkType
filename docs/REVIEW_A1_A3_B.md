# REVIEW — v0.1.33 Schnitte A1, A3, P2-Mikrofix, B1, B2 (DeepSeek V4 Pro 0813)

**Datum:** 2026-11-02 · **Reviewer:** cortecs/deepseek-v4-pro-0813 (sa-f6a102b2, read-only) · **Verdikt: OK with notes — P0/P1 = 0 · Push JA**
(Übernahme wörtlich; Slot ohne Schreibrecht, Datei per Orchestrator gesichert.)

## Correct (Kernbelege)
- **A1**: `DictationCoordinator.kt:333–336` gemeinsame Baselines NACH `src.start()` (Erfolgs-Check Z.322), VOR `captureThread.start()` (344–347); Startdauer-Diagnose `lastStartMs` unverändert (Z.290/488ff); Schwellwerte 3000/3000/2500 unverändert; Verträge durch A1-Tests (DictationCoordinatorTest 562–586, 594–617) + F28b1 (420–449, exakt ein stall).
- **A3**: `SegmentSessionLog` rein (Service 1836–1853: baseline −1, Log nur `lastSeg>=0 && current>lastSeg`); Verdrahtung dünn (Z.601, 1416–1418); Segmenter-Zähler unverändert.
- **P2-Mikrofix**: `DictationController.kt:613–620` — `ownershipDeniedCount/AtS` jetzt INNERHALB `if (!rebased || !tracker.isOwned(...))`; gelungene Re-Baseline zählt nicht. AP3-P2-Test (422–451) belegt Zähler 0→1 + Sekundenwert.
- **B1**: `row0MenuBelowKey` (LongPressMenu 111–117) = `keyTop − gap − menuH < 0`, pad/cell/spaceAbove ohne Entscheidungsmacht; menuTopPx-Zweig 90–95, mittlere/Footer bit-identisch; Tests 682/265/115/232 + reale Geometrie 171 statt −168.
- **B2**: `BackspaceRepeat.kt` pure Logik (450/70, down() eine Aktion, tick holt nicht auf, cancel idempotent); KeyboardView DOWN 844–851, UP/CANCEL 995–999 (removeCallbacks, kein Doppeldelete, Tap-Ausschluss Z.1030–1032); Repeat → `onAction(BACKSPACE)` (Z.822) = Servicezweig `backspaceDeleteOnce` (Service 1752–1765: Selektion ZUERST ⇒ `commitText("",1)`, sonst Graphem/DEL-Fallback); `onFinishInputView` Z.695 + `onDetachedFromWindow` 688–691 canceln.
- **Release-Gate**: `build.gradle.kts:14–15` versionCode 34 / 0.1.33 ✓.

## Findings
**P0: keine. P1: keine.**
**P2 (Bericht, nicht blockierend):**
1. Stale Kommentar `LongPressMenu.kt:94` („darf negativ sein") — seit B1 dort nie negativ; Kommentar korrigieren.
2. Theoretischer Arming-Sonderfall `KeyboardView.kt:941` (`k.top <= 0f`-Guard): Reihe 0 mit keyTop∈1..167 px — in realer Geometrie unerreichbar (keyTop=0); optional Kommentar.
3. Redundante frühe Baseline-Setzung `DictationCoordinator.kt:326–340` vor `engine.load()` — im Erfolgspfad überschrieben, Fehlpfad ohne Watchdog; tot-redundant, harmlos.

## Acceptance PLAN vs. Code
| Schnitt | Acceptance | Beleg | Status |
|---|---|---|---|
| A1 | Baselines nach start()/vor Thread | DC 333–336 | ✓ |
| A1 | Startdiagnose + Schwellwerte unverändert | Z.290/488ff; 3000/3000/2500 | ✓ |
| A1 | 3-s-Totheit genau einmal stall | F28b1 + A1-Test 2 | ✓ |
| A3 | baseline −1 / nur echte Erhöhung | Service 1836–1853, 601, 1416 | ✓ |
| A3 | kein Segmenter-Eingriff | segmentFinalizations coordinator-intern | ✓ |
| B1 | above>=0-Regel, pad/cell/spaceAbove ohne Macht | LongPressMenu 117 | ✓ |
| B1 | Geometrie+Arming eine Quelle; 682/265/115/232 | 90–97, Arming 941, Tests | ✓ (P2-#2) |
| B2 | 450/70, DOWN eine Aktion, kein Doppeldelete | BackspaceRepeat; KV 844–851, 995–999 | ✓ |
| B2 | Repeat=Servicezweig; Selektion zuerst, 1× commitText | KV 822; Service 1752–1765 | ✓ |
| B2 | kein Timer nach Editorwechsel | 695, 688–691 | ✓ |
| P2 | Zähler nur bei echter Verweigerung | DC 613–620 | ✓ |

## Migrations-Audit (3 Bestandstest-Änderungen) — alle korrekt
1. `LongPressMenuTest` F54 a) entfernt → ersetzt durch B1-Test 1 (`assertNotEquals(-168)` + `top+147<=318`) + Grenzfälle 168/167; F54 b)/F38 e) konsistent.
2. `foreignSelectionBlocksFurtherWrites` (DCT 224–249): Cursor-Griff ⇒ Re-Baseline+Schreiben; echter Fremdinhalt ⇒ W1 ohne Sink, `lost` bleibt — A2b-Vertrag korrekt.
3. `AP3-P2` (DCT 422–451): auf echten Fremdinhalt umgeschrieben (Suffix weg ⇒ Re-Baseline scheitert ⇒ 1 Treffer + 4.0 s) — konsistent mit P2-Mikrofix.

## Nebenläufigkeit/Timer
`removeCallbacks` mit identischem Runnable-Objekt (835/998); Runnable re-schedulet über `scheduleBackspaceRepeat`, bei `armed=false` sofortige Rückkehr. Kein Leak-Pfad.

**Verdikt: P0/P1 = 0, Push JA.** (Reviewer konnte Testzahlen nicht gegenrechnen — Gradle-Mutex; Orchestrator hat 573/0 XML-verifiziert.)
