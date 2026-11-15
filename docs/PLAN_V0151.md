# PLAN V0.1.51 — vier Nutzerbefunde (⌫-Breite · ⌫-Glyphe · Rechte-Button · Modelle ohne Host)

Ausgangspunkt V0.1.50 (versionCode 50). Zahlen aus dem Planer-Lauf `sa-92a6330a`
(read-only, deshalb hier abgelegt). Normalisierung: `KeyboardModel` rechnet **pro Zeile**
`unit = (usable − keyGap·(n−1)) / Σ`; `KEY_GAP_DP = 0`, Padding 0 ⇒ Test-px == Geräte-px @1080.

## A — Backspace auf eine ganze Einheit
| Zeile Z1 | Standard (13 Assets) | cs-cz | ru-ru |
|---|---|---|---|
| Einheiten | `m-left` 0.5 + 9 × 1.0 + **⌫ 1.0** (Σ 10.5) | 0.5 + 10 × 1.0 + 1.0 (Σ 11.5) | 0.5 + 11 × 1.0 + 1.0 (Σ 12.5) |
| px @1080 | Buchstaben **102.86**, ⌫ **102.86** | 93.91 | 86.40 |
| Symbol-Ebenen Z1 | 0.5 + 10 × 1.0 + **⌫ 1.0** (Σ 11.5) → Zeichen/⌫ **93.91** | dito | dito |
Unverändert: Z0 (10 × 1.0), Z2 (⇧1.5 + 7 × 1.0 + ⏎1.5, M-Mitte 864 px), Z3 (Footer).
Tests: `GeometryV0149Test` (`⌫ == Nachbarbuchstabe` statt `0.75 ×`; Σ-Werte 10.5/11.5/12.5;
px-Probe 102.86/93.91/86.40), `LayoutCompletenessTest` (⌫ == 1.0), `SymbolGridAcrossAssetsTest`,
`LetterRowAlignmentAcrossAssetsTest` nachziehen — Absichten erhalten, nichts aufweichen.

## B — Backspace-Glyphe ohne senkrechten Strich
`KeyboardView.buildActionGlyphPaths(...)`, Zweig `KeyActions.BACKSPACE`: der `detail`-Pfad
(`cx − 0.465s`, `cy ∓ 0.27s`) entfällt; `outline` (Schaft + Pfeilkopf) bleibt **zeichengleich**.
`⇧` und `⏎` unberührt. Mitziehen: Kommentare in `KeyboardView`/`KeyboardTheme`
(„⌫ = pure left arrow **with base**", `head↔base distance 0.465−0.285`, F36/V2 §8) und
`ImeRootViewLayoutTest`-Erwartungen (⌫-Glyphe hat **einen** Pfad, keine Basis-Linie; Höhe
0.60·s und Zentrierung unverändert). Sichtprüfung Emulator + Render-PNG.

## C — „Grant permissions" muss reagieren
Ist-Bug: `requestPermissionsNow()` kehrt still zurück, wenn nichts fehlt (`missing.isEmpty()`
→ `return`), und tut nichts, wenn alles gewährt ist — der Button wirkt tot.
Soll:
1. **Etwas fehlt** → System-Dialog (`RequestMultiplePermissions`) wie bisher.
2. **Nichts fehlt** → Toast „All permissions granted" + Hinweistext zeigt den Ist-Zustand.
3. **Dauerhaft verweigert** (Marker gesetzt) → `setup_open_settings` sichtbar und der Hinweis
   nennt den Weg über die System-Einstellungen.
`refreshUi()` setzt den Hinweistext zustandsabhängig (neu: `setup_permissions_all_granted`).
Tests (Robolectric): Zustandsmatrix fehlt/alles/verweigert → Button-Aktion + Sichtbarkeit +
Hinweistext.

## D — Modelle ohne Host nutzbar machen: **lokaler Import** (d3)
Kein HTTPS-Host verfügbar (kein DNS-Name, kein Zertifikat, kein Schreibzugriff auf
`82.116.120.4`); `BASE_URL_QWEN`/`BASE_URL_U2` bleiben leer. Deshalb:
- **P1** `ModelStore`-Import-API: `importSet(variant, source)` kopiert die im gepinnten Manifest
  erwarteten Dateien (rekursiv, Segment-Pfade aus der Multi-Segment-Store-Logik), **verifiziert
  jeden sha256** vor dem Aktivieren, räumt bei Fehler auf und lässt ein vorhandenes Set
  unangetastet.
- **P2** UI: Karte „Import folder" (`ACTION_OPEN_DOCUMENT_TREE`) je Set; Fehlerfälle mit
  klaren Meldungen (Datei fehlt / Hash falsch / Platz / Abbruch).
- **P3** Kein Auto-Download, keine Netzfreigabe ohne Nutzeraktion (Vertrag bleibt).
- **P4** Tests: Fake-`ContentResolver` mit (a) korrekten Dateien → READY, (b) falschem Hash →
  Fehler + altes Set unverändert, (c) fehlender Teil → Fehler, (d) Abbruch → kein Halbzustand.
- **P5** Doku: `docs/MODEL_IMPORT.md` (welche Dateien wohin, Größen, Hashes) + CHANGELOG.

## E — Reihenfolge & DoD
1. A (JSON + Testtabellen) → 2. B (Glyphe + Tests) → 3. C (Rechte-UX + Tests) →
4. D (Import, größter Brocken). Danach: Debug-Volltest `--rerun-tasks` (XML `/tmp/v151_debug/`),
Emulator-Sicht (⌫ in Tastenbreite, ⌫ ohne Strich, Rechte-Toast, Import mit Fake-Ordner),
`versionCode 51 / 0.1.51`, CHANGELOG, dist-Rotation, Commit+Push, APK per
`tools/send_apk_mail.py` an ts@ii.de.
