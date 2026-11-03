#!/usr/bin/env bash
# v0.1.30 Emulator-Smoke: Halte-Diktat mit Satzpausen + UP-im-STARTING-Hinweis.
# Evidenz: 🐞-Export via Zwischenablage -> KEYCODE_PASTE ins Debug-Feld -> uiautomator-Text.
set -u
cd <workdir>
ADB="adb -s <device>"
export PATH=$PATH:/opt/android-sdk/platform-tools
export XDG_RUNTIME_DIR=/run/user/$(id -u)
LOG=/tmp/sm30_smoke.log; : > "$LOG"; exec >>"$LOG" 2>&1
ts() { date "+%H:%M:%S.%3N"; }
grab_export() { # $1 = Zieldatei
  for versuch in 1 2; do
    $ADB shell input tap 1017 1635; sleep 2          # 🐞: kopieren + Share-Dialog
    $ADB shell input keyevent 4; sleep 1             # Share-Dialog schließen
    $ADB shell input tap 540 300; sleep 1            # Debug-Feld fokussieren
    # Feld leeren: ans Ende, dann 220× Backspace (Export ≈ 1100 Zeichen)
    $ADB shell input keyevent 123
    $ADB shell "for i in \$(seq 1 220); do input keyevent 67; done"
    $ADB shell input keyevent 279; sleep 1           # PASTE (KEYCODE_PASTE)
    $ADB shell uiautomator dump /sdcard/u.xml >/dev/null 2>&1
    $ADB shell cat /sdcard/u.xml | python3 -c "
import sys,html,re
x=sys.stdin.read()
for m in re.finditer(r'text=\"([^\"]*)\"', x):
    t=html.unescape(m.group(1))
    t=t.replace('&#10;','\n')
    if 'audio_s' in t and 'status_res' in t:
        print(t)
" > "$1"
    if [ -s "$1" ]; then echo "--- export -> $1 ($(wc -l < "$1") Zeilen, Versuch $versuch)"; return; fi
    echo "--- Versuch $versuch leer, wiederhole"; sleep 2
  done
  echo "--- EXPORT FEHLT: $1"
}
echo "=== SMOKE v0.1.30 $(ts) ==="
$ADB shell am force-stop de.voicekeyboard; sleep 2
$ADB shell am start -n de.voicekeyboard/.debug.DebugInputActivity >/dev/null 2>&1; sleep 3
$ADB shell input tap 540 300; sleep 2                       # IME zeigen

echo "--- TEST 1: HOLD 182s mit 3s-Pausen (Kern) $(ts)"
echo "DOWN $(ts)"
$ADB shell input -d 0 motionevent DOWN 880 1635
sleep 3
paplay testdata/hold/hold_korpus_180.wav                    # 182,5 s (4s Start-Stille)
sleep 1
echo "UP $(ts)"
$ADB shell input -d 0 motionevent UP 880 1635
sleep 30                                                     # Final-Decode auf 182s-Puffer
grab_export /tmp/sm30_export_hold.txt

echo "--- TEST 2: UP im STARTING-Fenster $(ts)"
$ADB shell input tap 540 300; sleep 1
$ADB shell input -d 0 motionevent DOWN 880 1635; sleep 1
echo "UP $(ts)"
$ADB shell input -d 0 motionevent UP 880 1635
sleep 14
grab_export /tmp/sm30_export_upstart.txt
echo "=== DONE $(ts) ==="
