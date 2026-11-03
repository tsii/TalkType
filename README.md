# Voice Keyboard

Android-Tastatur (IME) für GrapheneOS: **15 Sprachen tippen** und **offline diktieren** —
ohne Google-Dienste, ohne Netzwerkzugriff zur Laufzeit, ohne Wörterbücher oder Autokorrektur.

<p align="center">
  <img src="docs/img/tastatur-0.1.38.png" alt="Voice Keyboard — QWERTZ-Tastatur (15 Sprachen, Y-Reihe zentriert)" width="720">
</p>

## Was es kann

- Tastatur mit 15 Layouts (Schichtsystem: Grund-/Shift-/Symbol-/Emoji-Ebene), Optik nah am Systemstandard, heller und dunkler Modus.
- Diktat über ein lokales Spracherkennungsmodell (Parakeet, int8, ~670 MB). Audio verlässt das Gerät nie.
- Modell-Download einmalig per Nutzerentscheid; Netz danach automatisch wieder aus.
- Mikro per Haltegeste: halten = Aufnahme, loslassen = Abschluss; Status und Fehler in der Statuszeile.

## Datenschutz

Kein Telemetry-Provider, keine Absturzmeldungen, keine Wortlisten. Die einzige
Netzberechtigung besteht für den einmaligen Modell-Download. Diagnosefelder (🐞) zeigen
nur Messwerte wie Pegel, Zeit und Pfadtyp — niemals Audio- oder Textinhalte.

## Bauen

```
export ANDROID_HOME=/opt/android-sdk
./gradlew --offline assembleRelease      # APK
./gradlew --offline testDebugUnitTest    # Unit-Tests
```
Erfordert Android-SDK 35 und arm64-Zielgeräte. Release-Signaturschlüssel wird nicht
mitgeliefert.

## Status

Frühes Stadium (0.1.x), Entwicklung auf einem echten Gerät geprüft. Diktat funktioniert
auf Deutsch; weitere Sprachen brauchen eigene Erkennungsmodelle. Siehe `docs/` für
Konzept, Layout-Anforderungen und ASR-Vertrag.
