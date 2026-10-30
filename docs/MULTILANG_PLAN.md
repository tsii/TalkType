# Plan: Mehrsprachiges Tippen über die Sprachgrenze (Autokorrektur zurückgestellt)

**Stand:** 2026-10-28 · **Rolle:** Plan (keine Umsetzung, kein Build, kein Code-Umbau)
**Nutzerentscheidung desselben Tages:** Autokorrektur/Wortfindung wird **vorerst nicht**
umgesetzt; dieser Plan liegt vollständig vor und wird erst auf Zuruf abgearbeitet.

**Wunsch, wörtlich:** deutsch tippen **mit englischen Wörtern dazwischen** (und
umgekehrt) — **ohne** Sprachumschaltung; bei mehreren ausgewählten Sprachen sollen
Tippen *und* Korrektur **alle gewählten Sprachen gemeinsam** abdecken (alle 15).

---

## 1. Zerlegung in unabhängige Fähigkeiten (der Kern)

Der Wunsch besteht aus **drei** Fähigkeiten, die technisch **nichts** miteinander zu tun
haben. F1 ist ohne Wörterbuch möglich, F2 nicht, F3 ist eine andere Baustelle.

### F1 — Tastenangebot für mehrere Sprachen gleichzeitig (**ohne Wörterbuch, heute machbar**)

Ziel: Die Tastatur bietet **dauerhaft** alle Sonderzeichen aller gewählten Sprachen an —
`ä ö ü ß` (de) **und** `é è ê à ç ù` (fr) **und** `ñ á é í ó ú` (es) **und** `å æ ø`
(sv/da), `ı ş ğ` (tr), `ё ы э` (ru), `ě š č ř` (cs), `α β ς` (el) …

**Mechanik:** Ein **Laufzeit-Merge** der gewählten Assets zu einem *virtuellen Layout*:

- Buchstaben-Reihenfolge/Geometrie kommen vom **Primär-Layout** (`current_language`).
- `alternates` jeder Taste = **Vereinigung** der `alternates` dieser Taste über **alle
  aktiven Sprachen** (gleiche Taste, gleiche Position, z. B. `n` → `ñ ń`, `s` → `ß ś š`).
- Zeichen, die in einer aktiven Sprache eine **eigene** Taste sind, werden im virtuellen
  Layout zum **Alternate** derselben Taste der Primärsprache (z. B. `ü` aus de wird auf
  `u` gelegt, wenn Primär = en).
- `symbols`/`symbolsShift` bleiben unverändert (Sprache ist dort bereits neutral).
- Das **Primär-Layout** bestimmt weiterhin Groß-/Kleinschreibung (`CaseMapper` je
  `caseLocale`) — ein gemergtes Layout hat **eine** `caseLocale` (die der Primärsprache).

**Zwei harte Regelkonflikte, die der Plan lösen muss (nicht verstecken):**

| Regel | Konflikt | Lösung im Plan |
|---|---|---|
| `LAYOUT_REQUIREMENTS` §1 Regel 3: „max. **8** alternates pro Taste" | Union über 15 Sprachen kann 8 überschreiten (z. B. `a`: `á à â ã å ä æ` …) | Regel gilt für **Assets** (bleiben unverändert ≤ 8). Das **virtuelle** Layout bekommt ein eigenes, dokumentiertes Limit **12** + Test „≤ 12, keine Duplikate, kein Zeichen der Taste selbst" |
| §1 Regel 3: alternates nur **ein** Codepoint, NFC | `ı`/`i` (tr), `ß` (de), `ς` (el) sind je 1 Codepoint → ok; `ẞ` nur in `shift` | bleibt erfüllt; Test prüft NFC + `codePointCount == 1` |

**Globus-Semantik ändert sich:** `🌐` wechselt künftig **nur** die Primärsprache
(= Buchstabenanordnung), **nicht** das Zeichenangebot und **nicht** die Diktatsprache
(ASR bleibt `auto`, DESIGN §3). Bei nur **einer** aktiven Sprache ist das Verhalten
exakt wie heute.

### F2 — Wortfindung/Vervollständigung über die Sprachgrenze (**braucht Wörterbuch — zurückgestellt**)

Das ist die eigentliche „Autokorrektur über Sprachen": Vervollständigung, Groß/Klein-
Heuristik, „häufige Wörter", Tippfehler-Korrektur über einen **gemeinsamen Wortschatz**
aller aktiven Sprachen. Ohne Lexikon ist das **nicht** möglich (keine Statistik, keine
Kandidaten). Es braucht: ein offline mitgeliefertes oder heruntergeladenes Wörterbuch je
Sprache, eine gemeinsame Kandidatenliste, Ranking und eine UI, die Korrekturen sichtbar
macht (Vorschlagsleiste) — plus eine Konfliktregel mit dem ASR-Diktat (DESIGN §7.1
warnt genau davor: zwei Korrekturinstanzen auf demselben Text).

### F3 — ASR über Sprachgrenzen (**nicht** Teil dieses Plans)

Diktat bleibt `language=auto` (25 Sprachen im Modell, KONZEPT §2.5); ein deutscher Satz
mit englischen Wörtern ist heute schon möglich — das ist **keine** Tipp-Fähigkeit.
Abgrenzung nur zur Klarheit: F1/F2 betreffen ausschließlich das **Tippen/Korrigieren**.

---

## 2. Konzept-Treue

| Punkt | Was der Nutzer will | Was `docs/KONZEPT.md` / Regeln sagen | Entscheidung/Wortlaut für die Konzept-Treue-Tabelle |
|---|---|---|---|
| **Wörterbücher/Autokorrektur** | Korrektur über mehrere Sprachen gemeinsam | `KONZEPT.md` §7.1: „**Keine Autokorrektur und keine lernenden Wörterbücher in Version 1.**" · `reminders/ALWAYS.md` Regel 9: „keine Autokorrektur/Wörterbücher in v1" | **Bewusste, dokumentierte Abweichung (Phase 2), Datum 2026-10-28, durch den Nutzer selbst entschieden.** Eintrag: „*Mehrsprachige Wortfindung/Korrektur (F2) erweitert die v1-Aussage „keine Wörterbücher": Der Nutzer wünscht Korrektur über alle aktiven Sprachen. Umgesetzt wird sie nur **offline** (mitgeliefertes/herabladbares Lexikon, kein Cloud-Dienst), **abschaltbar**, **ohne Lernverhalten** (keine Nutzerhistorie) und erst nach F1. Bis dahin gilt KONZEPT §7.1 unverändert.*" |
| **Ressourcen** | 15 Sprachen gemeinsam | KONZEPT: offline, eine signierte Sideload-APK | Lexika nur **offline**: entweder im APK (Größe!) oder über den bestehenden `ModelStore`-Download (Resume/Hash). **Keine** Cloud, kein Google-Dienst, kein Telemetrie-Lernen |
| **F1 (Zeichenangebot)** | sofort gewünscht | KONZEPT/DESIGN schließen nur *Autokorrektur/Wörterbücher* aus, **nicht** das Zeichenangebot | **Keine Abweichung** — F1 ist reine Layout-Logik und darf sofort geplant/gebaut werden |

---

## 3. Stufenplan (test-first, ≤ 4 Dateien, ≤ 2 Builds je Auftrag)

### Phase 1 — F1: Zeichenangebot über die Sprachmenge (freigegeben, Autokorrektur-frei)

| M | Ziel | Test ZUERST | Dateien | ETA |
|---|---|---|---|---|
| **M1.1** | Merge-Kern als reine Funktion | `LayoutMergerTest`: (a) Primär de + aktiv en → `n`-Alternates enthalten `ñ`; (b) Primär en + aktiv de → Taste `u` trägt `ü`; (c) ≤ **12** Alternates, keine Duplikate, kein eigenes Zeichen; (d) NFC + 1 Codepoint; (e) **ein** Asset allein ⇒ Ergebnis **identisch** zum Asset (Regression) | `layouts/LayoutMerger.kt` (neu, ~120 Z.), `app/src/test/.../layouts/LayoutMergerTest.kt` | 3 h |
| **M1.2** | Merge im Ladevorgang | `LayoutRepositoryTest`: `loadMerged(activeIds, primary)` liefert virtuelles Asset; bei 1 Sprache identisch zu `load()`; unbekannte IDs werden ignoriert | `layouts/LayoutRepository.kt`, `layouts/LayoutMerger.kt` | 1,5 h |
| **M1.3** | Verdrahtung + Globus-Semantik | `MultilangWiringTest` (instrumented): Wechsel der Primärsprache ändert Buchstabenanordnung, **behält** die Sonderzeichen der anderen aktiven Sprachen; `active_languages` unverändert | `ime/VoiceKeyboardService.kt`, `keyboard/KeyboardView.kt` | 2 h |
| **M1.4** | Shift-Ebene des Merges | `LayoutMergerShiftTest`: Großformen der gemergten Alternates (nur Zeichen **mit** Großform, D3-Regel aus `LAYOUT_REQUIREMENTS`), `ß→ẞ`, `σ/ς→Σ`-Ausnahme | `layouts/LayoutMerger.kt` | 2 h |
| **M1.5** | Sichtprüfung + Doku | `LAYOUT_REQUIREMENTS` §1 Regel 3 um „gilt für Assets; virtuelles Layout ≤ 12" ergänzen; Emulator: 2× öffnen, Feldwechsel | `docs/LAYOUT_REQUIREMENTS.md` | 1 h |

**Phase-1-Summe: ca. 9,5 h** (3 Aufträge à ≤ 4 Dateien; Builds: 2× `test`, 1× instrumented).

### Phase 2 — F2: Korrektur über die Sprachmenge (**bewusst zurückgestellt**)

| M | Ziel | Test ZUERST | Dateien | ETA |
|---|---|---|---|---|
| **M2.0** | Entscheidungsvorlage (Lexikon-Quelle, Größe, Lizenz, Abschaltbarkeit) | Doku + `LexiconSourceTest` (nur Format) | `docs/LEXICON_DECISION.md` (neu) | 2 h |
| **M2.1** | Lexikon-Loader + Index (offline, gepackt) | `LexiconTest`: Laden, Lookup, Speicher < X MB | `lexicon/Lexicon.kt`, `lexicon/LexiconFormat.kt` | 4 h |
| **M2.2** | Kandidaten über **alle** aktiven Sprachen (Union, kein Ranking) | `CandidateUnionTest`: de+en liefert beide Wortlisten, dedupliziert, sortiert nach Sprache | `lexicon/CandidateProvider.kt` | 3 h |
| **M2.3** | Vorschlagsleiste (nur Anzeige, **keine** Auto-Ersetzung) | `SuggestionBarTest`: erscheint, Auswahl fügt ein, **kein** automatischer Eingriff | `keyboard/SuggestionBar.kt`, `keyboard/KeyboardView.kt` | 4 h |
| **M2.4** | Optional: Ranking/Lernen — **nur** mit expliziter Nutzerfreigabe | `NoLearningTest`: ohne Freigabe **keine** Persistenz von Nutzerwörtern | `lexicon/Ranking.kt` | 3 h |

**Phase-2-Summe: ca. 16 h**, davon allein 2 h Entscheidung + 4 h Lexikon. **Nicht** vor
F1, nicht ohne die Konzept-Treue-Zeile aus §2.

---

## 4. Datenschutz-Abwägung (F2)

- **Keine Wörterbuch-Downloads aus dem Netz** in der Grundvariante: Lexika kommen **im
  APK** (kleine Sprachen) oder über den **bestehenden Modell-Downloader** (`ModelStore`
  mit Resume/SHA-256, ein Host, eine Zustimmung). Kein Zwischenruf bei Drittanbietern.
- **Kein Lernverhalten:** keine Nutzerhistorie, keine Frequenzzählung, kein „zuletzt
  benutzt" — damit bleibt die Privacy-Zusage (Regel 9) intakt und die Abweichung
  betrifft nur „Wörterbuch vorhanden", nicht „Nutzerdaten gesammelt".
- **Abschaltbar:** ein Schalter in der Einrichtung („Wortvorschläge") — Default **aus**,
  bis der Nutzer ihn aktiviert. Damit ist der heutige Zustand (KONZEPT §7.1) der Default.
- **Größenordnung `ANNAHME:`** Lexikon je Sprache 0,3–2 MB komprimiert (Häufigkeitsliste
  Top-20k–50k); 15 Sprachen ≈ 5–20 MB — vertretbar gegenüber 671 MB Modell, aber es ist
  **APK-Wachstum** oder ein zweiter Download.

---

## 5. Zeitplan-Übersicht

| Phase | Inhalt | ETA | Abhängigkeit |
|---|---|---|---|
| **1 (F1)** | Zeichenangebot über alle aktiven Sprachen | **9,5 h** (3–4 Aufträge) | keine — **kann sofort starten** |
| **2a (F2-Doku)** | Lexikon-Entscheidung + Konzept-Treue-Eintrag | 2 h | Nutzerfreigabe für Wörterbücher |
| **2b (F2-Code)** | Lexikon + Vorschlagsleiste, **keine** Auto-Ersetzung | ~14 h | 2a |
| **2c (optional)** | Ranking/Lernen | 3 h | ausdrückliche Freigabe; sonst **nie** |

## ANNAHME-Zeilen

- `ANNAHME: Die Vereinigung der Alternates bleibt bei ≤ 12 pro Taste, wenn höchstens 4`
  `Sprachen gleichzeitig aktiv sind; bei mehr greift der Kappungs-Test (M1.1c) und`
  `dokumentiert die Grenze in der UI.`
- `ANNAHME: Lexikon-Größen 0,3–2 MB je Sprache (Top-20k–50k) — nicht gemessen.`
- `ANNAHME: F1 braucht keine Schema-Änderung (keine 5. Ebene); der Merge erzeugt ein`
  `virtuelles Asset im Speicher.`
