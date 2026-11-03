"""Checks a recording taken from the emulator device for a real tone.

Purpose: proof that the host sound (paplay -> null sink -> virtual microphone)
really arrives in the Android recording. Shows peak/RMS overall and per second.
No audio content is printed, only numbers (rule 9).

Usage: .venv-asr/bin/python tools/check_mic_capture.py <wav> [seconds]
"""
import sys
import wave

pfad = sys.argv[1] if len(sys.argv) > 1 else "/tmp/mic_test.wav"
grenze = int(sys.argv[2]) if len(sys.argv) > 2 else 12

with wave.open(pfad) as w:
    rate, kan, weite = w.getframerate(), w.getnchannels(), w.getsampwidth()
    n = w.getnframes()
    daten = w.readframes(n)

if weite != 2:
    print(f"ERROR: expected 16 bit, found {weite * 8} bit")
    sys.exit(2)

samples = memoryview(daten).cast("h")
dauer = n / rate


def peak_rms(auschnitt):
    if not len(auschnitt):
        return 0, 0
    quadrate = sum((s * s for s in auschnitt), 0)
    return max(max(auschnitt), -min(auschnitt)), int((quadrate / len(auschnitt)) ** 0.5)


p_g, r_g = peak_rms(samples[:: max(1, len(samples) // 200000)])
print(f"device recording: {rate} Hz, {kan} channel, {weite * 8} bit, {dauer:.1f} s, {n} samples")
print(f"overall (sampled): peak={p_g}  RMS={r_g}")

schritte = rate * kan
print("profile of the first seconds (second: peak / RMS):")
for i in range(min(grenze, int(dauer))):
    abschnitt = samples[i * schritte : (i + 1) * schritte]
    p, r = peak_rms(abschnitt[:: max(1, len(abschnitt) // 8000)])
    print(f"  {i + 1:>3}: {p:>6} / {r:>6}")

print("RESULT:", "TONE REACHED THE MICROPHONE" if r_g > 20 else "SILENCE - the tone did not arrive")
sys.exit(0 if r_g > 20 else 1)
