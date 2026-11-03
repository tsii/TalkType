# ADB über Netzwerk zum Nutzer-Telefon (GrapheneOS) — Schritt-für-Schritt

**Zweck:** Drahtlose Diagnose (Logcat/🐞-Exporte) ohne USB-Kabel, damit wir die Abbrüche
(„Audio-Start fehlgeschlagen" nach IME-Start, Toggle-Abbrüche „Kein Text erkannt") live sehen.
**Benötigt:** Telefon und Server (<internal-host>) im **selben WLAN** (kein Gastnetz). USB-Debugging
muss **nicht** an sein; **root wird nicht benötigt**; kein Kabel nötig.

## 1. Vorbereitung am Telefon

1. **Entwickleroptionen entsperren:** Einstellungen → Über das Telefon → **Build-Nummer** 7× hintereinander tippen (ggf. PIN eingeben). *Du siehst jetzt:* „Du bist jetzt Entwickler" (Toast).
2. **Drahtloses Debugging aktivieren:** Einstellungen → System → Entwickleroptionen → **Drahtloses Debugging** einschalten. *Du siehst jetzt:* der Schalter ist an, darunter erscheint eine IP mit Port.
3. **Koppeln öffnen:** Tippe auf „Drahtloses Debugging" → **„Gerät koppeln"** (Pairing). *Du siehst jetzt:* ein Fenster mit einer **6-stelligen Zahl** (Pairing-Code) und einer **IP:PORT**-Angabe. **Bildschirm muss an bleiben** — bei Displaysperung verschwindet der Pairing-Dialog oft.

## 2. Diese Werte notieren und uns mitteilen (getrennt beschriften!)

| Wert | Wo sichtbar | Beispiel |
|---|---|---|
| **A) Pairing-Code** (6-stellig) | Fenster „Gerät koppeln" | `482 913` |
| **B) Pairing-IP:Port** | Fenster „Gerät koppeln" (unter dem Code) | `192.168.1.42:37125` |
| **C) Connect-IP:Port** | Hauptbildschirm „Drahtloses Debugging" (unter dem Schalter) | `192.168.1.42:40133` |

**Wichtig:** B und C haben **unterschiedliche Ports** — beide notieren, nicht vertauschen.

## 3. Kommandos auf dem Server (führen WIR aus — nicht eintippen, nur zur Info)

```
adb pair <B) Pairing-IP:Port> <A) Pairing-Code>     # einmalig, Code gilt nur kurz
adb connect <C) Connect-IP:Port>                    # danach für jede Sitzung
adb devices                                         # Kontrolle: Gerät muss als "device" erscheinen
```

## 4. Schnell-Test (führen WIR aus)

```
adb -s <C) Connect-IP:Port> shell getprop ro.product.model
```
Erwartet: Modellname (z. B. `Pixel 8`). Dann läuft Logcat und wir ziehen die 🐞-Exporte live.

## 5. Fehlerbilder

| Du siehst / es passiert | Ursache | Abhilfe |
|---|---|---|
| Server: `failed to authenticate` / Code abgelehnt | Code falsch abgeschrieben oder **abgelaufen** (gilt nur Sekunden bis Minuten) | „Gerät koppeln" **schließen und neu öffnen** → frischen Code mitteilen; wiederholen |
| `adb connect` bleibt nach Pairing hängen / Timeout | **Client-Isolation** im Router (Geräte im WLAN dürfen einander nicht erreichen) | Router: „AP/AP-Isolation" bzw. „Client-Isolation" deaktivieren; sonst 2.4/5-GHz-Netz des **selben Routers** testen |
| Pairing-IP beginnt mit `192.168.x.x`, Server ist `<internal-host>` | Telefon in **anderem Netz/Gastnetz** | Telefon und Server müssen am **selben Router/Netz** hängen (kein Gast-WLAN, kein „ Gäste"-SSID) |
| Nach Fernstart/Neustart des Telefons kein Connect mehr | GrapheneOS schaltet **Drahtloses Debugging nach Neustart und nach WLAN-Trennung ab** | Schritte 2–3 wiederholen (Entwickleroptionen bleiben entschlert) |
| Dialog verschwindet beim Koppeln | **Displaysperre/Bildschirm aus** | Bildschirm **an** lassen; Display-Timeout temporär hoch |
| Unbekannte Telefon-IP | steht in Schritt 2 B/C auf dem Telefon selbst | einfach die angezeigten Werte übermitteln |

## 6. Sicherheit — nach der Diagnose

1. Einstellungen → System → Entwickleroptionen → **Drahtloses Debugging → gekoppelte Geräte → Server-Eintrag entfernen** (Pairing verwerfen).
2. Schalter **Drahtloses Debugging aus**.
3. Entwickleroptionen dürfen an bleiben (unproblematisch); bei Bedarf: „Entwickleroptionen" ganz aus.
4. Wir protokollieren nur **Zahlen und Codes** (keine personenbezogenen Inhalte) — dieselbe Regel-9-Disziplin wie in den 🐞-Exporten.

## 7. Kurz-Checkliste

- [ ] Build-Nummer 7× → Entwickleroptionen da
- [ ] Drahtloses Debugging **an**
- [ ] „Gerät koppeln" offen, Bildschirm an
- [ ] **A** Pairing-Code, **B** Pairing-IP:Port, **C** Connect-IP:Port notiert und übermittelt
- [ ] Telefon am **selben Netz** wie Server (kein Gastnetz, keine Client-Isolation)
- [ ] Nach Diagnose: Pairing entfernt + Drahtloses Debugging **aus**

*Stand: v0.1.29/v0.1.30-Diagnose. Bei Abbruch der Diagnose einfach Debugging ausschalten — nichts am Telefon zurückgeblieben.*
