#!/usr/bin/env bash
# AP5 — C2-Matrix-Runner (PLAN_C2_FABLE_R2 §1.3). Emulator-5560, FakeWav-Spur (kein Mikro).
# Nutzung: tools/emu_c2_run.sh <mode> <engine> <korpus-wav> <tag>
#   mode   : plain | restart:20 | cursor:25 | spans | slowmain:300 | hideime:30 | webview
#   engine : real | stub:38.5:200
set -u
cd <workdir>
MODE="${1:?mode}"; ENG="${2:?engine}"; WAV="${3:?korpus}"; TAG="${4:-run}"
export PATH=$PATH:/opt/android-sdk/platform-tools XDG_RUNTIME_DIR=${XDG_RUNTIME_DIR:-/run/user/$(id -u)}
ADB="adb -s <device>"; PKG=de.voicekeyboard; SIDECAR="${WAV%.wav}.json"
CSV=reports/c2_runs.csv; LOG=/tmp/c2_${TAG}.log; : > "$LOG"; exec >>"$LOG" 2>&1
ts() { date "+%H:%M:%S"; }
echo "=== C2-Lauf tag=$TAG mode=$MODE engine=$ENG wav=$WAV $(ts)"

# 0) Boot-Check
$ADB shell getprop sys.boot_completed 2>/dev/null | grep -q 1 || { echo "E: Emulator nicht boot"; exit 2; }

# 1) Korpus pushen + Hooks setzen
$ADB push "$WAV" /data/local/tmp/k.wav >/dev/null
$ADB shell setprop debug.vk.fakewav /data/local/tmp/k.wav
$ADB shell setprop debug.vk.engine "$ENG"
$ADB shell setprop debug.vk.export_file 1
$ADB shell am force-stop $PKG                    # Hooks wirken je Prozess
DUR=$(ffprobe -v error -show_entries format=duration -of csv=p=0 "$WAV" | cut -d. -f1)

# 2) Hostile-Editor starten
$ADB shell am start -n $PKG/.debug.HostileEditorActivity --es mode "$MODE" >/dev/null 2>&1
sleep 3
$ADB shell input tap 540 300 2>/dev/null || true  # Feld fokussieren (Layout-Muster DebugInput)
$ADB shell ime set $PKG/.ime.VoiceKeyboardService >/dev/null
FOCUS=$(adb -s <device> shell dumpsys window | grep -m1 mCurrentFocus)
echo "focus: $FOCUS"
echo "$FOCUS" | grep -q HostileEditor || { echo "E: Fokus nicht auf HostileEditor (Popup? Onboarding deaktivieren)"; exit 3; }
# Tastatur-Sichtbar-Assert (Sonst trifft DOWN ins Leere):
for k in 1 2 3; do
  SHOWN=$(adb -s <device> shell "dumpsys input_method 2>/dev/null | grep -m1 mInputShown")
  echo "$SHOWN" | grep -q true && break
  $ADB shell input tap 540 300; sleep 0.6; $ADB shell input tap 540 300; sleep 1.2
  $ADB shell input text "x" 2>/dev/null  # Fokussiertes Feld + Tipp => IME zeigt sich
  sleep 1.2
done
adb -s <device> shell "dumpsys input_method 2>/dev/null | grep -m1 mInputShown" | grep -q true || { echo "E: Tastatur nicht sichtbar"; exit 4; }

# 3) HOLD starten (Mikro-Taste 880,1635) + Sampler
$ADB logcat -c
# W1b-Präfix: Vorläufer-Toggle-Session + sofortige Halte-Geste => Supersede mit asynchronem Spät-Abort
if [ "${PREFIX:-none}" = "supersede" ]; then
  $ADB shell input -d 0 motionevent DOWN 880 1635; sleep 0.08
  $ADB shell input -d 0 motionevent UP 880 1635   # Toggle-Session 1 startet
  echo "PRE-FIX toggle-session1 $(ts)"; sleep 0.3
fi
$ADB shell input -d 0 motionevent DOWN 880 1635
echo "DOWN $(ts)"
# Session-Assert: mic_down muss binnen 20 s in VK-SESS erscheinen
for k in $(seq 1 10); do sleep 2; adb -s <device> logcat -d -s VK-SESS 2>/dev/null | grep -q mic_down && break; done
adb -s <device> logcat -d -s VK-SESS 2>/dev/null | grep -q mic_down || { echo "E: keine Session gestartet (mic_down fehlt)"; exit 5; }
( for i in $(seq 1 $(( (DUR+90)/15 ))); do sleep 15;
    { echo "--- sample $(ts)"; $ADB shell dumpsys meminfo $PKG 2>/dev/null | grep -E "TOTAL PSS";
      adb -s <device> logcat -d -s VK-SESS 2>/dev/null | tail -2; } >> /tmp/c2_${TAG}_sampler.log;
  done ) & SAMPLER=$!

# 4) Warten bis IDLE oder Korpus-Ende + 60 s
DEADLINE=$(( $(date +%s) + DUR + 60 )); STOPPED=0
while [ "$(date +%s)" -lt "$DEADLINE" ]; do
  if adb -s <device> logcat -d -s VK-SESS 2>/dev/null | grep -qE "state=IDLE"; then STOPPED=1; break; fi
  sleep 5
done
echo "stopped=$STOPPED vor Korpus-Ende? $(ts)"
[ "$STOPPED" = 0 ] && { $ADB shell input -d 0 motionevent UP 880 1635; echo "UP (regulaeres Finale) $(ts)"; sleep 20; }
kill $SAMPLER 2>/dev/null

# 5) Editor-Textlaenge (uiautomator, nur Laenge!)
$ADB shell uiautomator dump /sdcard/e.xml >/dev/null 2>&1
EDLEN=$(adb -s <device> shell cat /sdcard/e.xml | python3 -c "
import sys,re,html
m=[html.unescape(x) for x in re.findall(r'text=\"([^\"]*)\"',sys.stdin.read())]
print(max((len(x) for x in m), default=0))")
echo "editor_len=$EDLEN golden_chars=$(python3 -c "import json;print(json.load(open('$SIDECAR'))['golden_chars'])" 2>/dev/null)"

# 6) VK-SESS-Ende + Export ziehen
adb -s <device> logcat -d -s VK-SESS | tail -25
$ADB shell input tap 1017 1635; sleep 2          # 🐞
$ADB shell input keyevent 4; sleep 1
$ADB shell input tap 540 300; $ADB shell input keyevent 123
$ADB shell "for i in \$(seq 1 240); do input keyevent 67; done"
$ADB shell input keyevent 279; sleep 1
adb -s <device> shell uiautomator dump /sdcard/u.xml >/dev/null 2>&1
adb -s <device> shell cat /sdcard/u.xml | python3 -c "
import sys,html,re
x=sys.stdin.read()
for m in re.finditer(r'text=\"([^\"]*)\"', x):
    t=html.unescape(m.group(1))
    if 'audio_s' in t and 'cap_blocks' in t: print(html.unescape(t))
" > /tmp/c2_export_${TAG}.txt
if [ ! -s /tmp/c2_export_${TAG}.txt ]; then
  $ADB exec-out run-as $PKG cat files/debug_export.txt > /tmp/c2_export_${TAG}.txt 2>/dev/null
fi
python3 tools/export_to_csv.py /tmp/c2_export_${TAG}.txt --tag "$TAG" --csv "$CSV" || echo "WARN: Export leer"
echo "=== DONE $(ts) (Log: $LOG)"
