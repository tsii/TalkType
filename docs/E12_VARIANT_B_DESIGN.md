# E12 – Variante B: Latenz, Umschalter und Auslieferungsgrenze

## 1. Entscheidung und verbindliche Tatsachen

**Empfehlung: (a), ehrlicher später Beginn; B ausschließlich als ausdrücklich aktivierte Voraboption.**
A bleibt die bewährte Standardengine: ONNX Runtime, dynamisch int8, bestehender Golden-Test.
B ist `Arm/parakeet-tdt-0-6b-v3-int8-litert`, LiteRT, 683 298 069 Byte, cached streaming.
Die übergebenen Messungen gelten als Fakten; hier keine neue Laufzeitmessung/Implementierung.
Vier B-Modelle: featurizer, cached encoder, decoder_step (LSTM), joint_step.
Featurizer verlangt fest 121600 Samples bei 16 kHz = 7,6 s pro raw_wav-Eingabe.
Masken, Normierungswerte, pre_encode_feature_cache und previous_sample gehören zum Vertrag.
Encoder-/Decoderzustände sind sitzungsbezogen; allein cache_last_channel benötigt rund 18,6 MB.
Das ist kein Gesamt-RAM-Budget: Graphen, Tensoren, Kopien und Runtime kommen hinzu.

## 2. Latenzversprechen und kurze Aufnahmen

Für den ersten ungepolsterten Block wird rund 7,6 s Audio gesammelt; Inferenz kommt hinzu.
**Kein Versprechen „Text garantiert nach 8 s“ und kein unbelegtes „dafür stabiler“.**
Status beim Halten: **„B (LiteRT) sammelt Audio – erste Ausgabe frühestens nach etwa 8 s.“**
Nach Blockübergabe: **„B (LiteRT) verarbeitet lokal …“**
Die Zeit ist Aufnahmezeit, nicht zwingend reine Sprachzeit; Pausen zählen zu Samples.
**ANNAHME:** Der erste volle gültige Eingabeblock benötigt keine weitere ungemessene Wartezeit.

(b) wird für Live-Hypothesen verworfen: vorhandene Maskeneingänge belegen noch keine
korrekten Kurzfenster, Cachefortschritte oder eine erste Ausgabe nach einer Sekunde.
Polster-Rechenlast ist nicht mit echter niedriger Encoderlatenz gleichzusetzen.
(c) wird verworfen: keine automatische Lang-/Kurz-Erkennung und kein heimlicher A/B-Wechsel.
Der Nutzer wählt eine Engine für das ganze Diktat; eine Dauergrenze würde deren Identität verwischen.

Loslassen beendet das Mikrofon sofort, unabhängig vom Füllstand des B-Blocks.
Status dann: **„B (LiteRT) verarbeitet Ihre Aufnahme abschließend …“**
Kurze Aufnahmen und der letzte Teilblock dürfen nicht verloren gehen: Finalverarbeitung
mit Masken/Nullpolster ist dafür ein eigener zwingender Vertrags-/Qualitätstest.
Solange korrekte Masken, Padding und Cache-/Längenfortschreibung nicht belegt sind,
ist B **nicht aktivierbar**; keine Auslieferung, die kurze Aufnahmen still verwirft.
Kein Live-Kurzfutter als getarnter Fallback und keine lange stille Nachaufnahme nach UP.
Folgehypothesen hängen vom verifizierten gültigen Hop und der Geräteleistung ab;
die A-Kadenz von 0,3–1 s wird für B nicht ungeprüft übernommen.

## 3. UI-Umschalter und Modellzustände

Ort: vorhandene Einrichtung/Einstellungen, Abschnitt **„Spracherkennung“**, zwei Optionen:
- **„A – Bewährt (ONNX)“**; Hilfetext: „Verwenden Sie die bewährte lokale Erkennung.“
- **„B – Vorabversion (LiteRT)“**; Hilfetext: „Rechnen Sie mit einer ersten Ausgabe frühestens nach etwa 8 s.“
Keine CPU-/NPU-Werbung: LiteRT allein beweist keinen verwendeten Beschleuniger.

A bereit und IDLE: Wechsel sofort, ohne Bestätigung. B bereit: beim ersten Aktivieren
explizite Zustimmung **„B ist experimentell. Möchten Sie B für Ihre nächsten Diktate verwenden?“**
Aktionen **„B verwenden“** / **„Bei A bleiben“**. Späterer Wechsel bereit/IDLE sofort.
Während Aufnahme, Stop/Finalisierung oder laufendem Enginewechsel ist der Umschalter
gesperrt: **„Beenden Sie zuerst Ihr Diktat.“** Normale Tastatureingabe bleibt möglich.
Ein Wechsel betrifft erst das nächste Diktat, beendet keine aktive Aufnahme heimlich.

B fehlt: **„B benötigt einen zusätzlichen Download: etwa 683 MB. A bleibt verfügbar.“**
Explizite Aktion **„B herunterladen“**, mit Netzwerkfreigabe und Abbruch; kein Request
allein durch Antippen/Markieren der Option. Zustimmung nennt experimentellen Status,
Download und Speicherbedarf; freien Platz einschließlich Staging/Altbestand prüfen.
Fortschritt zeigt empfangene/Gesamtbytes; anschließend Hash-/Vollständigkeitsprüfung
aller vier gepinnten Modellteile. Kein Teilbestand gilt als ausführbare B-Installation.
Nach Download bleibt A aktiv, bis der Nutzer **„B verwenden“** wählt; kein automatischer Wechsel.

B teilweise: **„B unvollständig – setzen Sie den Download fort.“** B nicht aktivierbar.
B defekt: **„B-Modell beschädigt – laden Sie B erneut.“** B nicht aktivierbar.
B vollständig, aber inkompatibel: **„B kann nicht geladen werden. Verwenden Sie A.“**
Eine vormals gewählte, jetzt defekte B-Installation wird als **nicht bereit** angezeigt,
nicht als laufendes A ausgegeben. Rückkehr zu A erfordert eine bewusste Nutzeraktion.
Es gibt keinen automatischen Enginefallback und keinen automatischen Reparaturdownload.

Bei AsrNotReadyException: Capture beenden/freigeben, Ausgabe der betroffenen Session
invalidieren; Status **„B ist nicht bereit. Prüfen Sie das Modell oder wählen Sie A.“**
Meldung bleibt nach Loslassen bestehen; nicht durch „Bereit“ ersetzen. Entsprechender
A-Fehler nennt A. Bereits eingefügten Text erhalten, keine späten Ergebnisse/Geisterschriften,
kein stilles IDLE. Keine Exception-/Stacktexte für Nutzer und kein Audio/Text im Fehlerlog.
Wechsel-/Sessiongenerationen verwerfen alte Lade-/Inferenzergebnisse; pro Sitzung nur
eine Engine. B-Caches nicht zwischen Diktaten oder Engines weiterreichen; alte Ressourcen
geordnet schließen, keine unnötig parallel residenten A-/B-Gewichte.

## 4. Eine vertrauenswürdige Anzeige: tatsächliche Engine in der Diagnosezeile

Die vorhandene **Diagnosezeile** erhält als erstes Feld die tatsächlich laufende Engine:
**„Diagnose: rechnet=B/LiteRT | erst=…s RTF=… T=…“**
Für A entsprechend **„Diagnose: rechnet=A/ONNX | erst=…s RTF=… T=…“**.
Quelle ist der wirklich gestartete Backend-Aufruf samt Sessiongeneration, nicht die Auswahlpräferenz.
Vor erstem Backend-Aufruf **„rechnet=keine“**, bei fehlender Bereitschaft **„rechnet=keine (B nicht bereit)“**.
Nach Laufende **„zuletzt=B/LiteRT“** statt weiter „rechnet=B“ vorzutäuschen.
Messwerte stammen ausschließlich aus derselben Session/Engine; vorherige Werte zurücksetzen.
`erst` misst ab erstem aufgenommenem Sample bis erstem tatsächlich angewendetem Text,
also einschließlich B-Sammelzeit; fehlt Text, „–“, nicht 0. RTF und T behalten ihre
bestehende dokumentierte Bedeutung, keine neue erfundene Einheit/Temperaturinterpretation.

## 5. Qualitätsgrenze und Freigabe

A behält §4.1-Golden unverändert; B darf dessen Erfolg nicht erben.
Für B sind **Wort-Identität ≥95 % und Zeichen-Edit-Distanz ≤2 %** ein Mindestgate,
keine zugesicherte Gleichwertigkeit: Wort-Identität als 1−WER mit Levenshtein-Abgleich,
Zeichendistanz als CER; keine positionsweise Tokenzählung. Beide Grenzen pro Fixture,
nicht durch Mittelung schlechter Fälle mit guten verdecken; Referenztexte versionieren.
Großschreibung/Interpunktion nicht nachträglich entfernen, um die Grenze schönzurechnen.
„Kein fremdsprachiger Output“ heißt keine erfundene Übersetzung/fremde Sprachpassage;
erwartete Fachwörter und Code-Switching aus dem Referenztext bleiben zulässig.
Zusätzlich zwingend: keine verlorenen/duplizierten Wörter an Cache-/Blockgrenzen,
Kurzaufnahme, Teilblock-Finalisierung, Pause, lange Aufnahme und Stop ohne Nachaufnahme.
Fremdtextschutz, Aufnahmeguards und 15-Sprachen-Layoutfunktion bleiben unabhängig davon Pflicht.
Bei Nichterreichen: B als **„Vorabversion nicht freigegeben“** gesperrt, A weiter verfügbar;
keine heimliche Lockerung der Metriken und kein Umschalten auf A unter dem Namen B.

**Auslieferung: Vorabversion, standardmäßig AUS, nur mit expliziter Zustimmung.**
Das ist die Zielentscheidung **nach** bestandenem Vertrags-/Qualitäts-/Sicherheitsgate;
allein der beschriebene Modellbestand rechtfertigt heute noch keine aktivierbare B-Engine.
A bleibt Standard und Rückweg; ehrlicher später Beginn ist kein Funktionsfehler,
verlorene kurze Diktate oder nicht beweisbare Engineidentität wären Freigabeblocker.

**OFFEN:** B-Masken-/Hopvertrag, Tailtests, tatsächliche Geräte-Latenzen und B-Qualitätsnachweise.
**VERIFIKATION:** Reine Designentscheidung auf übergebenen Fakten; keine Builds/adb/Codearbeit.
