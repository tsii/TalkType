#!/usr/bin/env bash
# Emulator-5560 zurueck in den Normalbetrieb (kein Audio-Einlass), damit alle
# anderen Tests genau so laufen wie vor dem Audio-Versuch.
#
# Pflicht laut Auftragsprotokoll: App wieder installieren und IME setzen
# (instrumentierte Laeufe deinstallieren die App am Ende) — und NIEMALS
# `pm clear` / clearPackageData, sonst ist der 671-MB-Modellbestand weg.
#
# EMULATOR 5558 GEHOERT EINEM ANDEREN PROJEKT — hier wird ausschliesslich 5560 angefasst.
set -u
export ANDROID_HOME=${ANDROID_HOME:-/opt/android-sdk}
export PATH="$PATH:$ANDROID_HOME/platform-tools"
export XDG_RUNTIME_DIR=/run/user/$(id -u)
PORT=5560
AVD=vk-api36
APK=${APK:-app/build/outputs/apk/debug/app-debug.apk}

echo "== 1/3 Emulator $PORT neu starten (Normalbetrieb, -no-audio) =="
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
echo "   Boot: $(adb -s emulator-$PORT shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')"

echo "== 2/3 PulseAudio-Module abbauen =="
for q in vkmic_src; do
  id=$(pactl list short sources 2>/dev/null | awk -v q=$q '$2==q {print $1}')
  [ -n "${id:-}" ] && pactl unload-module "$id" && echo "   Remap-Quelle $q entfernt"
done
for s in vkmic; do
  id=$(pactl list short sinks 2>/dev/null | awk -v s=$s '$2==s {print $1}')
  [ -n "${id:-}" ] && pactl unload-module "$id" && echo "   Null-Sink $s entfernt"
done

echo "== 3/3 App + IME wiederherstellen =="
if [ -f "$APK" ]; then
  adb -s emulator-$PORT install -r "$APK" 2>&1 | tail -1
else
  echo "   (kein $APK — Überspringen)"
fi
adb -s emulator-$PORT shell ime enable de.voicekeyboard/.ime.VoiceKeyboardService
adb -s emulator-$PORT shell ime set de.voicekeyboard/.ime.VoiceKeyboardService
echo "   IME: $(adb -s emulator-$PORT shell settings get secure default_input_method 2>/dev/null | tr -d '\r')"
