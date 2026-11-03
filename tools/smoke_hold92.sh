#!/usr/bin/env bash
# Kernbeweis v0.1.31: 92-s-Halte-Diktat ALS ERSTE App-Session nach Kaltstart.
set -u
cd <workdir>
ADB="adb -s <device>"
export PATH=$PATH:/opt/android-sdk/platform-tools
export XDG_RUNTIME_DIR=/run/user/$(id -u)
LOG=/tmp/sm31_hold.log; : > "$LOG"; exec >>"$LOG" 2>&1
ts() { date "+%H:%M:%S.%3N"; }
echo "=== HOLD92 als Erste-Session $(ts)"
$ADB shell am start -n de.voicekeyboard/.debug.DebugInputActivity >/dev/null 2>&1; sleep 3
$ADB shell input tap 540 300; sleep 2
echo "DOWN $(ts)"
$ADB shell input -d 0 motionevent DOWN 880 1635
sleep 3
paplay testdata/hold/hold_korpus_92.wav
sleep 1
echo "UP $(ts)"
$ADB shell input -d 0 motionevent UP 880 1635
sleep 45
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
" > /tmp/sm31_export_hold.txt
  if [ -s /tmp/sm31_export_hold.txt ]; then echo "--- export Versuch $v ok"; break; fi
  echo "--- Versuch $v leer"; sleep 3
done
$ADB exec-out screencap -p > /tmp/sm31_kb.png 2>/dev/null
echo "=== DONE $(ts)"
