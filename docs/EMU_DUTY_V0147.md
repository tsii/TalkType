# EMU-Pflicht-Programm 0.1.47 (Plan: sipgate-oracle via sa-b716f)

Subagent sa-b716f (delegate · my/my-qwen38-flash-next:medium) status: done

# Emu-Pflicht-Programm 0.1.47 (emulator-5566) — read-only Plan, koordinaten-frei von Schätzung

## 0. Gelesene Konstanten (Belegpfad)

| Größe | Wert | Quelle |
|---|---|---|
| Zeilenhöhe nominal (4 Reihen) | 55 dp, rowGap 0, keyGap 0 | `KeyboardTheme.kt` `ROW_HEIGHT_4_ROWS_DP=55`, `ROW_GAP_DP=0`, `KEY_GAP_DP=0` |
| Long-press-Schwelle | **450 ms** | `KeyboardTheme.LONG_PRESS_MS=450L`; Globus-arming `KeyboardView.kt` onTouchEvent ACTION_DOWN (`langArm`) |
| Popup-Zelle/Höhe/Pad | 48/56/4 dp | `KeyboardTheme.POPUP_*` |
| Status-/Korrektur-Band | debug **48 dp** (APK ist debug → `debugStatusRow=true`), release 40 dp | `ImeRootView.kt` init: `rowHeightPx = ((if (debugStatusRow) 48 else 40) * density)`, `KeyboardTheme.STATUS_LINE_DP=48` |
| Headroom über Band | 64 dp | `KeyboardTheme.POPUP_HEADROOM_DP=64` |
| Fußreihe-Zusammensetzung | `symbols 1.5 · globe 1 · emoji 1 · space 6.5 · enter 1 · mic 3` (space 7.5−1, mic 2+1, Summe 14) | `KeyboardModel.kt` `D10_FOOTER.composeRow` + `de-de.json` base row 4 |
| Breitenformel | `unit = (1080 − keyGap·(n−1))/Σwidth`, `left_i = Σ breiter` (`keyGapPx=0`) | `KeyboardModel.kt` init-Loop |
| Debounce Korrektur | **300 ms**, Separatoren (inkl. **Space**) → **sofort** | `VoiceKeyboardService.kt` `CORR_DEBOUNCE_MS=300L`, `CORR_SEPARATORS`, `CorrectionWiring.scheduleCompute` |
| UNDO-Fenster | 1 Level, endet **nur durch Tasten-Feed** (`feedText`/`feedBackspace` → `replacer.invalidateUndo()`); Wand-Tap ist **kein** Feed | `CorrectionWiring.feedText/feedBackspace`, `RegionReplacer.undo` |
| Design-Deckel | 276 dp | `KeyboardView.MAX_HEIGHT_DP=276` |

**Dichte:** Referenz der bisherigen Belege = **440 dpi → d=2.75** (`EVIDENCE_T15_OVERLAY.md` R1.4, „menu height 154 px at density 2.75“). Step 0 verifiziert per `wm density`.

## A. Deterministische Koordinaten

**Zwei Kalibrierte K und R (Pflicht vor jedem Punkt; ersetzt alle Schätzungen — Lesson aus `EVIDENCE_T15_OVERLAY.md` R1: „row edges … never derived from assumed budgets“):**

```
adb -s emulator-5566 shell wm density                      # erwartet 440
adb -s emulator-5566 shell am start -n de.voicekeyboard/.debug.DebugInputActivity
adb -s emulator-5566 shell input tap 540 348 ; sleep 1.5
adb -s emulator-5566 shell uiautomator dump /sdcard/v47cal.xml
adb -s emulator-5566 pull /sdcard/v47cal.xml /tmp/v47cal.xml
grep -o '<node[^>]*content-desc="Keyboard [^>]*>' /tmp/v47cal.xml   # className=de.voicekeyboard.keyboard.KeyboardView (KeyboardView.onInitializeAccessibilityNodeInfo)
# → bounds="[0,K][1080,B]" ; R = (B-K)/4   (4 Reihen, rowGap=0)
```
Kreuzprüfung (Schleifen-Kalibrierung, meldet was wirklich getroffen wurde):
```
adb -s emulator-5566 shell input tap 521 $((K+3*R+R/2))
adb -s emulator-5566 logcat -d -s VK-HIT | tail -1   # MUSS "id=space ... view=1080xH" mit H==B-K
```
Weicht `id` ab → Kalibrierung verwerfen, Dump erneut lesen (Abbruchkriterium nach 2 Versuchen).

**x-Tabelle (dichteunabhängig, Breite=1080, keyGap=0; Zeilen-Σwidth: r0=10→u=108; r1/r2≈9.99996→u≈108.0004; r3=14→u=77.1429):**

| Taste | r | Σwidth-Formel | **x** | **y** |
|---|---|---|---|---|
| e | 0 | 2·108+54 | **270** | K+R/2 |
| r | 0 | 3·108+54 | **378** | K+R/2 |
| o | 0 | 8·108+54 | **918** | K+R/2 |
| g | 1 | 54+4·102.0003+51 | **513** | K+1.5R |
| Backspace | 1 | Ende 1080−54 | **1026** | K+1.5R |
| m | 2 | 132.0005+6·102.0003+51 | **795** *(trifft bestehenden Erfolgsbeweis)* | K+2.5R |
| Enter (Buchstabenr.) | 2 | 948+66 | **1014** | K+2.5R |
| Globe 🌐 | 3 | (1.5+0.5)·77.14 | **154** | K+3.5R |
| Space | 3 | 270…771.4, Mitte 520.7 | **521** (Toleranz 280–760; erklärt früheres (540,2270)) | K+3.5R |
| Enter (Fuß) | 3 | 771.4+53.6 | **810** | K+3.5R |
| Stab-Zentrum (Korrektur-Zeile) | — | 1080−(48·d)/2 | **1014** | **K−66** (debug-Band 48dp=132px, Wand MATCH_PARENT; `ImeRootView` wandButton 48dp breit, rechts) |

Das früher unklare Reihen-Rätsel ((873,1860)→„l“) ist damit gelöst: x=873 liegt in r1 „l“ (870–972) **und** r0 „o“ (864–972) — entscheidend war allein das Zeilen-y; K/R kommen jetzt ausschließlich aus Dump+VK-HIT, nie aus Annahme.

## B. Sieben Beweispunkte (adb-Folgen)

Feld-Lesehilfe ( Hauptfeld = erster EditText im Dump, `DebugInputActivity.kt` root vor bandProbe):
```
rd(){ adb -s emulator-5566 shell uiautomator dump /sdcard/v47f.xml>/dev/null; \
      adb -s emulator-5566 pull /sdcard/v47f.xml /tmp/v47f.xml>/dev/null; \
      grep -oP 'text="[^"]*"(?=[^>]*class="android.widget.EditText")' /tmp/v47f.xml | head -1; }
```

**(1) Vorschlag nach „morgem “**: Tippfolge `m,o,r,g,e,m` (je Tap + `sleep 0.5`), dann **Space** (Separator → sofort-Compute, `CORR_SEPARATORS`), `sleep 1`; Dump + `screencap > /tmp/v47_p1_row.png`. **Soll:** Dump enthält `text="morgen…`; `rd()` immer `"morgem "`. **Ast:** kein Vorschlag nach 2 Versuchen → Punkte 1–3 abbrechen, 4–7 weiter (Kern-Nichtnachweis dokumentieren).

**(2) Stab wendet an**: `grep -o '<node[^>]*content-desc="Apply correction"[^>]*>' /tmp/v47f.xml` → bounds-Mitte tap (Soll ≈ (1014, K−66)). `sleep 1`; `rd()` == **`morgen `**; Screenshot `p2`. **Ast:** Feld unverändert → selection/dictation-IDLE prüfen (Dump `selected=`/logcat `VK-CORR compute_abort`), 1 Retry, dann 2–3 abbrechen.

**(3) UNDO direkt danach**: ohne **jede** Keyboard-Berührung: Dump → `content-desc="Undo correction"` (`setWandUndoMode`, VK-2: Wand bleibt clickable), gleiche Bounds tap, `sleep 1`; `rd()` == **`morgem `**. Screenshot `p3`. **Analyse: per adb machbar** — der 300ms-Debounce ist irrelevant (nach APPLY ist der Tracker geleert, kein Compute eingeplant; `invalidateUndo()` läuft nur in `feedText/feedBackspace`, ein Wand-Tap ist kein Feed). **Entschärfung nur bei 2 Fehlversuchen:** `RegionReplacerTest.kt` + `CorrectionWiringTest.kt` (JVM) als Beweis akzeptieren, Emu = „Zeile+Anwendung“, Punkt als *teilweise* werten.

**(4) Setup-Schalter**: `am start -n de.voicekeyboard/.ime.SetupActivity`; scrollen bis Dump `Show suggestions while typing` (`buildTypingSection`, Pref `corr_row_enabled`, Default ON): `input swipe 540 1700 540 400 300` (max 6×). Switch-Knoten-Mitte tap → OFF. Zurück: `am start …/.debug.DebugInputActivity`; `input tap 540 348`; falls nötig `keyevent 4` + Feld erneut tap (erzwingt `onStartInputView→setFieldInputType→refreshCorrectionRowVisibility`). **Soll OFF:** Dump **ohne** `content-desc="Apply correction"` (debug: Band bleibt, Wand+Text GONE — `refreshCorrectionRowVisibility`); „morgem “+Space getippt → kein `morgen`. Screenshots `p4_off_norow`, (nach ON-Rücksetzung) `p4_on_row`. **Ast:** Wand bleibt OFF sichtbar → 1× IME-Reload wie oben; danach = echter Produktfehler → Abbruchkriterium (4).

**(5) Globus-Langdruck ≥450 ms**: 
```
adb -s emulator-5566 logcat -c
( adb -s emulator-5566 shell input swipe 154 $((K+3*R+R/2)) 154 $((K+3*R+R/2)) 1600 >/dev/null 2>&1 & )
sleep 0.9 ; adb -s emulator-5566 exec-out screencap -p > /tmp/v47_p5_globe_popup.png
sleep 1  ; adb -s emulator-5566 logcat -d -s VK-HIT | grep popup-open
```
( swipe im Hold = Popup offen; losgelassenes UP würde es sonst sofort schließen — `onTouchEvent` UP setzt `popup=null`.) **Soll:** `VK-HIT popup-open cells=2 …` (`openPopupIfArmed` langMode: [Deutsch][All-languages-Label]); Screenshot zeigt 2 Zellen über dem Globus (Zellhöhe 132px, Menühöhe 154px, ~22px über Tastenzeile). **Ast:** kein `popup-open` → Swipe-Dauer auf 2000; danach ausbleibendes cells=2 = Abbruchkriterium (5).

**(6) 13/15 grau + Toast**: Setup, Sprachsektion (oben, index 2; ggf. `swipe 540 600 540 1600 300`). **Soll:** `grep -o 'text="[^"]*· coming soon"' … | wc -l` == **13** (15 CANONICAL − de-de/en-us, `LanguageAvailability`); Screenshot `p6_langs_grey`. Tap auf Zeilen-Bounds-Mitte von `Français · fr-FR · coming soon`; `sleep 0.5`; Dump: **exakter** Knoten `text="coming soon"` (Toast — die Zeilen selbst heißen „… · coming soon“, keine Verwechslung); Screenshot `p6_toast`.Français-Checkbox unverändert checked. **Ast:** Toast nicht im Dump (platformabhängig) → Screenshot mit sichtbarem Toast genügt als Beweis; sonst `SetupLanguageAvailabilityTest.kt` als *teilweise*.

**(7) Band-Regression (Metron aus `EVIDENCE_T15_OVERLAY.md` R1: VK-HIT-Rects, keine Annahmen)**:
```
adb -s emulator-5566 logcat -c
( adb -s emulator-5566 shell input swipe 270 $((K+R/2)) 270 $((K+R/2)) 1600 >/dev/null 2>&1 & )
sleep 0.9 ; screencap -p > /tmp/v47_p7_r0band.png ; sleep 1
adb -s emulator-5566 logcat -d -s VK-HIT | grep -E 'popup-open'
```
**Soll:** `popup-open cells=6 left=… top=T` mit **T<0 und T+154 ≤ 0** (view-relativ; 154px=56dp@2.75) ⇒ absolutes Popup-Intervall `[K+T, K+T+154]` endet oberhalb von r0 (K) — Overlap mit keiner Key-Row; Screenshot zeigt gedrücktes „e“ frei. **Ast:** `T+154>0` (Overlay in Key-Row) = dokumentierte Regression → Abbruchkriterium (7), **kein** Produkt-Eingriff während der Pflicht.

## C. Beweisliste
`/tmp/v47_p1_row.png, p2_applied.png, p3_undo.png, p4_off_norow.png, p4_on_row.png, p5_globe_popup.png, p6_langs_grey.png, p6_toast.png, p7_r0band.png` + `/tmp/v47cal.xml`, `/tmp/v47f.xml`, VK-HIT-Logauszüge je Punkt.

## D. UNDO-Entschlärfung
Nur als Rückfallebene (siehe Punkt 3): JVM-Tests belegen Undo-Semantik; Emu dann „Zeile+Anwendung“, Punkt (3)=*teilweise* mit Begründung „adb-tap-Pfad zweimal ohne Wiederherstellung, Timing-Analyse schließt Debounce als Ursache aus“.

## Nächste Schritte (Hauptagent)
1. Step-0-Kalibrierung fahren, K/R notieren, VK-HIT-Kreuzprüfung `id=space`.
2. Punkte 1–7 exakt in gezeigter Reihenfolge (3 unmittelbar nach 2, ohne Tasten-Tap dazwischen).
3. Bei Abweichungen nur die Ast-Regeln anwenden; Koordinaten niemals „nach Augenmaß“ nachjustieren — bei jeder Fehlanzeige erst `logcat -d -s VK-HIT | tail -1` (meldet getroffene Taste + Rect).
