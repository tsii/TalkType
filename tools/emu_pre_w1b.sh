#!/usr/bin/env bash
# PRE-FIX W1b-Repro auf vc32: echte Mikro-Kette, Erste-Session nach KALTSTART.
# Supersede-Präfix: Toggle-Session 1 startet, sofort Halte-Session 2 → vc32-Gate
# wird durch asynchronen Spät-Abort geöffnet → F43 stoppt bei erster Pause.
set -u
cd <workdir>
ADB="adb -s <device>"; LOG=/tmp/c2_pre_w1b_mic.log; : > "$LOG"; exec >>"$LOG" 2>&1
ts() { date "+%H:%M:%S.%3N"; }
export PATH=$PATH:/opt/android-sdk/platform-tools
export XDG_RUNTIME_DIR=/run/user/$(id -u)
echo "=== PRE-W1b MIC-Kette $(ts)"
# 1) Kaltstart mit Audio
timeout 400 bash tools/emulator_audio_up.sh | tail -1
$ADB install -r -g dist/voice-keyboard-0.1.31-diag-debug.apk | tail -1
$ADB shell ime set de.voicekeyboard/.ime.VoiceKeyboardService >/dev/null
$ADB shell am start -n de.voicekeyboard/.debug.DebugInputActivity >/dev/null 2>&1; sleep 3
$ADB shell input tap 540 300; sleep 2
# 2) Supersede-Präfix: Toggle-Session 1 (kurzes DOWN/UP), dann sofort HALTEN (Session 2)
$ADB shell input -d 0 motionevent DOWN 880 1635; sleep 0.15
$ADB shell input -d 0 motionevent UP 880 1635
echo "session1 toggle $(ts)"
sleep 0.5
$ADB shell input -d 0 motionevent DOWN 880 1635
echo "session2 HOLD DOWN $(ts)"
# 3) Korpus 120 s durchsprechen (paplay), HALTEN bleibt unten
paplay testdata/c2/k120.wav
echo "paplay fertig $(ts) — halte 5 s, dann UP"
sleep 5
$ADB shell input -d 0 motionevent UP 880 1635
echo "UP $(ts)"; sleep 40
# 4) Export (vc32-Felder; grab-Muster)
for v in 1 2; do
  $ADB shell input tap 1017 1635; sleep 3
  $ADB shell input keyevent 4; sleep 1
  $ADB shell input tap 540 300; sleep 1
  $ADB shell input keyevent 123
  $ADB shell "for i in \$(seq 1 240); do input keyevent 67; done"
  $ADB shell input keyevent 279; sleep 1
  $ADB shell uiautomator dump /sdcard/u.xml >/dev/null 2>&1
  $ADB shell cat /sdcard/u.xml | python3 -c "
import sys,html,re
x=sys.stdin.read()
for m in re.finditer(r'text=\"([^\"]*)\"', x):
    t=html.unescape(m.group(1))
    if 'audio_s' in t and 'cap_blocks' in t: print(html.unescape(t))
" > /tmp/c2_export_pre_w1b.txt
  [ -s /tmp/c2_export_pre_w1b.txt ] && { echo "export ok"; break; }
  echo "Versuch $v leer"; sleep 3
done
python3 tools/export_to_csv.py /tmp/c2_export_pre_w1b.txt --tag pre_w1b_mic_vc32 || true
echo "=== DONE $(ts)"; touch /tmp/c2_pre_done
