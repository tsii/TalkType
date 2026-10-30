"""Pruft eine vom Emulator-Geraet stammende Aufnahme auf echten Ton.

Zweer: Beweis, dass der Host-Ton (paplay -> Null-Sink -> virtuelles Mikrofon)
wirklich in der Android-Aufnahme ankommt. Zeigt Peak/RMS gesamt und pro Sekunde.
Kein Audio-Inhalt wird ausgegeben, nur Zahlen (Regel 9).

Aufruf: .venv-asr/bin/python tools/check_mic_capture.py <wav> [sekunden]
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
    print(f"FEHLER: erwartet 16 Bit, gefunden {weite * 8} Bit")
    sys.exit(2)

samples = memoryview(daten).cast("h")
dauer = n / rate


def peak_rms(auschnitt):
    if not len(auschnitt):
        return 0, 0
    quadrate = sum((s * s for s in auschnitt), 0)
    return max(max(auschnitt), -min(auschnitt)), int((quadrate / len(auschnitt)) ** 0.5)


p_g, r_g = peak_rms(samples[:: max(1, len(samples) // 200000)])
print(f"Ger\u00e4teaufnahme: {rate} Hz, {kan} Kanal, {weite * 8} Bit, {dauer:.1f} s, {n} Samples")
print(f"gesamt (Stichprobe): Peak={p_g}  RMS={r_g}")

schritte = rate * kan
print("Profil der ersten Sekunden (Sekunde: Peak / RMS):")
for i in range(min(grenze, int(dauer))):
    abschnitt = samples[i * schritte : (i + 1) * schritte]
    p, r = peak_rms(abschnitt[:: max(1, len(abschnitt) // 8000)])
    print(f"  {i + 1:>3}: {p:>6} / {r:>6}")

print("ERGNIS:", "TON KAM AM MIKROFON AN" if r_g > 20 else "STILLE - Ton kam nicht an")
sys.exit(0 if r_g > 20 else 1)
