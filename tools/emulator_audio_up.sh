#!/usr/bin/env bash
# Emulator-5560 mit echtem Mikrofon-Einlass starten (Nachweis des Audio-Pfads).
#
# GEFUNDEN DURCH MESSUNG (2026-10-30), drei Dinge sind noetig:
#   1) -allow-host-audio  — ohne diesen Schalter NULLT der Emulator das Mikrofon
#      (Hilfetext: "Allows sending of audio from audio input devices.
#       Otherwise, zeroes out audio.")
#   2) KEIN -no-window    — der Headless-Zweig (qemu-system-*-headless) hat keinen
#      Audio-Treiber ("Could not init `pa' audio driver"). Stattdessen
#      QT_QPA_PLATFORM=offscreen + -qt-hide-window: unsichtbar, aber mit Audio.
#   3) PulseAudio am Standardpfad $XDG_RUNTIME_DIR/pulse/native + Null-Sink als
#      Standard-Senke/-Quelle, und der -audio-Zweig des Emulators kennt nur `pa`.
#
# Betriebssensorenlage: 65 % => Peak ~19800, RMS ~1900 (kein Clipping, nah am
# Originalpegel der Test-WAV). Bei 100 % clipping (Peak 32768), bei 30 % zu leise.
#
# EMULATOR 5558 GEHOERT EINEM ANDEREN PROJEKT — hier wird ausschliesslich 5560 angefasst.
set -u
export ANDROID_HOME=${ANDROID_HOME:-/opt/android-sdk}
export PATH="$PATH:$ANDROID_HOME/platform-tools"
export XDG_RUNTIME_DIR=/run/user/$(id -u)
PORT=5560
AVD=vk-api36
SENKENLAGE=${SENKENLAGE:-65%}

echo "== 1/4 PulseAudio am Standardpfad =="
mkdir -p "$XDG_RUNTIME_DIR"; chmod 700 "$XDG_RUNTIME_DIR"
pgrep -x pulseaudio >/dev/null || pulseaudio --start --exit-idle-time=-1 --log-target=file:/tmp/pa-vk.log
sleep 2
pactl info | sed -n '1,2p'
pactl load-module module-null-sink sink_name=vkmic sink_properties=device.description=VKMic 2>/dev/null || echo "   Null-Sink bereits geladen"
pactl load-module module-remap-source master=vkmic.monitor source_name=vkmic_src 2>/dev/null || echo "   Remap-Quelle bereits geladen"
pactl set-default-sink vkmic
pactl set-default-source vkmic_src
pactl set-sink-volume vkmic "$SENKENLAGE"
echo "   Senke=$(pactl get-default-sink) Quelle=$(pactl get-default-source) Lage=$(pactl get-sink-volume vkmic | head -1 | tr -s ' ')"

echo "== 2/4 Emulator $PORT neu starten (unsichtbar, MIT Audio) =="
adb -s emulator-$PORT emu kill >/dev/null 2>&1
sleep 5
nohup env QT_QPA_PLATFORM=offscreen PULSE_SERVER=unix:$XDG_RUNTIME_DIR/pulse/native \
  "$ANDROID_HOME/emulator/emulator" -avd "$AVD" -port $PORT -no-snapshot -no-boot-anim \
  -gpu swiftshader_indirect -qt-hide-window -audio pa -allow-host-audio \
  > /tmp/emu$PORT\_audio.log 2>&1 &
disown
adb -s emulator-$PORT wait-for-device
for _ in $(seq 1 40); do
  [ "$(adb -s emulator-$PORT shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ] && break
  sleep 5
done
echo "   Boot: $(adb -s emulator-$PORT shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')"

echo "== 3/4 Treiber-Fehlerfreiheit prüfen =="
fehler=$(grep -icE "could not init" /tmp/emu$PORT\_audio.log)
echo "   'Could not init' im Log: $fehler  (muss 0 sein)"
[ "$fehler" = "0" ] || { echo "   ABBRUCH: Emulator hat keinen Audio-Treiber."; exit 1; }

echo "== 4/4 End-to-End-Beweis: WAV -> virtuelles Mikrofon -> Geraeteaufnahme =="
adb -s emulator-$PORT root >/dev/null 2>&1; sleep 2
( for i in 1 2; do paplay "$PWD/testdata/de_smoke.wav"; done ) &
SPEILER=$!
sleep 1
adb -s emulator-$PORT shell 'timeout 8 tinycap /data/local/tmp/mikro.wav -D 0 -d 0 -c 1 -r 16000 >/dev/null 2>&1'
wait $SPEILER 2>/dev/null
adb -s emulator-$PORT pull /data/local/tmp/mikro.wav /tmp/mikro.wav >/dev/null 2>&1
.venv-asr/bin/python tools/check_mic_capture.py /tmp/mikro.wav 6
