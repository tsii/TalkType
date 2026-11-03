#!/usr/bin/env bash
# Return <device> to normal operation (no audio inlet), so that all
# other tests run exactly as they did before the audio attempt.
#
# Mandatory per the task protocol: reinstall the app and set the IME
# (instrumented runs uninstall the app at the end) — and NEVER
# `pm clear` / clearPackageData, otherwise the 671-MB model inventory is gone.
#
# EMULATOR 5558 BELONGS TO ANOTHER PROJECT — only 5560 is touched here.
set -u
export ANDROID_HOME=${ANDROID_HOME:-/opt/android-sdk}
export PATH="$PATH:$ANDROID_HOME/platform-tools"
export XDG_RUNTIME_DIR=/run/user/$(id -u)
PORT=5560
AVD=vk-api36
APK=${APK:-app/build/outputs/apk/debug/app-debug.apk}

echo "== 1/3 restart emulator $PORT (normal operation, -no-audio) =="
adb -s emulator-$PORT emu kill >/dev/null 2>&1
sleep 5
nohup "$ANDROID_HOME/emulator/emulator" -avd "$AVD" -port $PORT -no-window -no-snapshot \
  -no-boot-anim -gpu swiftshader_indirect -no-audio > /tmp/emu$PORT\_normal.log 2>&1 &
disown
adb -s emulator-$PORT wait-for-device
for _ in $(seq 1 40); do
  [ "$(adb -s emulator-$PORT shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ] && break
  sleep 5
done
echo "   boot: $(adb -s emulator-$PORT shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')"

echo "== 2/3 tear down the PulseAudio modules =="
for q in vkmic_src; do
  id=$(pactl list short sources 2>/dev/null | awk -v q=$q '$2==q {print $1}')
  [ -n "${id:-}" ] && pactl unload-module "$id" && echo "   remap source $q removed"
done
for s in vkmic; do
  id=$(pactl list short sinks 2>/dev/null | awk -v s=$s '$2==s {print $1}')
  [ -n "${id:-}" ] && pactl unload-module "$id" && echo "   null sink $s removed"
done

echo "== 3/3 restore app + IME =="
if [ -f "$APK" ]; then
  adb -s emulator-$PORT install -r "$APK" 2>&1 | tail -1
else
  echo "   (no $APK — skipping)"
fi
adb -s emulator-$PORT shell ime enable de.voicekeyboard/.ime.VoiceKeyboardService
adb -s emulator-$PORT shell ime set de.voicekeyboard/.ime.VoiceKeyboardService
echo "   IME: $(adb -s emulator-$PORT shell settings get secure default_input_method 2>/dev/null | tr -d '\r')"
