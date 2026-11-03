#!/usr/bin/env bash
# Compact hold proof: 62 s hold (2x 3-s pauses), 75 s finalize time, then export.
set -u
cd <workdir>
ADB="adb -s <device>"
export PATH=$PATH:/opt/android-sdk/platform-tools
export XDG_RUNTIME_DIR=/run/user/$(id -u)
LOG=/tmp/sm30_k2.log; : > "$LOG"; exec >>"$LOG" 2>&1
ts() { date "+%H:%M:%S.%3N"; }
echo "=== HOLD62 $(ts)"
$ADB shell am force-stop de.voicekeyboard; sleep 2
$ADB shell am start -n de.voicekeyboard/.debug.DebugInputActivity >/dev/null 2>&1; sleep 3
FOC=$($ADB shell dumpsys window | grep -m1 mCurrentFocus); echo "focus: $FOC"
$ADB shell input tap 540 300; sleep 2
echo "DOWN $(ts)"
$ADB shell input -d 0 motionevent DOWN 880 1635
paplay testdata/hold/hold_korpus_62.wav
sleep 1
echo "UP $(ts)"
$ADB shell input -d 0 motionevent UP 880 1635
echo "finalize window 75s $(ts)"
sleep 75
for v in 1 2 3; do
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
    if 'audio_s' in t and 'mic_rel' in t: print(html.unescape(t))
" > /tmp/sm30_export_hold62.txt
  if [ -s /tmp/sm30_export_hold62.txt ]; then echo "--- export attempt $v ok"; break; fi
  echo "--- attempt $v empty"; sleep 3
done
echo "=== DONE $(ts)"
