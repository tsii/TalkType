#!/usr/bin/env bash
# Start <device> with a real microphone inlet (proof of the audio path).
#
# FOUND BY MEASUREMENT (2026-10-30), three things are needed:
#   1) -allow-host-audio  — without this switch the emulator ZEROES the microphone
#      (help text: "Allows sending of audio from audio input devices.
#       Otherwise, zeroes out audio.")
#   2) NO -no-window      — the headless branch (qemu-system-*-headless) has no
#      audio driver ("Could not init `pa' audio driver"). Instead
#      QT_QPA_PLATFORM=offscreen + -qt-hide-window: invisible, but with audio.
#   3) PulseAudio on the standard path $XDG_RUNTIME_DIR/pulse/native + null sink as
#      the default sink/source, and the emulator's -audio branch knows only `pa`.
#
# Operating sink level: 65 % => peak ~19800, RMS ~1900 (no clipping, close to
# the original level of the test WAV). At 100 % clipping (peak 32768), at 30 % too quiet.
#
# EMULATOR 5558 BELONGS TO ANOTHER PROJECT — only 5560 is touched here.
set -u
export ANDROID_HOME=${ANDROID_HOME:-/opt/android-sdk}
export PATH="$PATH:$ANDROID_HOME/platform-tools"
export XDG_RUNTIME_DIR=/run/user/$(id -u)
PORT=5560
AVD=vk-api36
SENKENLAGE=${SENKENLAGE:-65%}

echo "== 1/4 PulseAudio on the standard path =="
mkdir -p "$XDG_RUNTIME_DIR"; chmod 700 "$XDG_RUNTIME_DIR"
pgrep -x pulseaudio >/dev/null || pulseaudio --start --exit-idle-time=-1 --log-target=file:/tmp/pa-vk.log
sleep 2
pactl info | sed -n '1,2p'
pactl load-module module-null-sink sink_name=vkmic sink_properties=device.description=VKMic 2>/dev/null || echo "   null sink already loaded"
pactl load-module module-remap-source master=vkmic.monitor source_name=vkmic_src 2>/dev/null || echo "   remap source already loaded"
pactl set-default-sink vkmic
pactl set-default-source vkmic_src
pactl set-sink-volume vkmic "$SENKENLAGE"
echo "   sink=$(pactl get-default-sink) source=$(pactl get-default-source) level=$(pactl get-sink-volume vkmic | head -1 | tr -s ' ')"

echo "== 2/4 restart emulator $PORT (invisible, WITH audio) =="
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
echo "   boot: $(adb -s emulator-$PORT shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')"

echo "== 3/4 check the driver for errors =="
fehler=$(grep -icE "could not init" /tmp/emu$PORT\_audio.log)
echo "   'Could not init' in the log: $fehler  (must be 0)"
[ "$fehler" = "0" ] || { echo "   ABORT: emulator has no audio driver."; exit 1; }

echo "== 4/4 end-to-end proof: WAV -> virtual microphone -> device recording =="
adb -s emulator-$PORT root >/dev/null 2>&1; sleep 2
( for i in 1 2; do paplay "$PWD/testdata/de_smoke.wav"; done ) &
SPEILER=$!
sleep 1
adb -s emulator-$PORT shell 'timeout 8 tinycap /data/local/tmp/mikro.wav -D 0 -d 0 -c 1 -r 16000 >/dev/null 2>&1'
wait $SPEILER 2>/dev/null
adb -s emulator-$PORT pull /data/local/tmp/mikro.wav /tmp/mikro.wav >/dev/null 2>&1
.venv-asr/bin/python tools/check_mic_capture.py /tmp/mikro.wav 6
