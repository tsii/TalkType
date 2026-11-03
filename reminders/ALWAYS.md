# Immer gültige Regeln (Voice-Keyboard)

1. **Rollen sind fix.** **Design/UI = `my/work-sipgate-coding-ultra`**; **Plan = `my/work-sipgate-coding-ultra`** (vom Nutzer am 2026-11-03 ausdrücklich bekräftigt); Analysen/Doku = `moonshot/kimi-k3`; **Code = `my/work-zai-org-glm-5.3-verda`**; **Review jeder Code-Arbeit = `cortecs/deepseek-v4-pro-0813`**. (Rollenwechsel 2026-11-01: Design von Sol → sipgate-coding-ultra, da Sol dauerhaft ausgefallen (Codex-Limit); Plan von Kimi → sipgate-coding-ultra; Code von cortecs/glm-5.3 → glm-5.3-verda. Design, Plan und Code via Provider `my`.) Der Orchestrator delegiert und verifiziert mechanisch — keine eigene Facharbeit, keine eigenen Reviews.
2. **`docs/KONZEPT.md` ist verbindlich** (erprobte Erfahrung des Nutzers). Abweichungen nur mit Begründung in der „Konzept-Treue"-Tabelle bzw. als dokumentierte Präzisierung.
3. **Kein `sleep` im Turn.** Warten = `schedule_task` (≥ 60 s) + Statuszeile an den Nutzer. Max. 2× `wait_all(60)` bei kurzen Jobs.
4. **Auftragsgrenzen:** ≤ 4 Dateien, ≤ ~150 Zeilen, ≤ 2 Build-Läufe, Ziel ≤ 10 min. Kanarienvögel → sofort `stop` (Details `docs/CONTINGENCY_PLAN.md` §4).
5. **Gradle = globales Mutex**, Emulator/adb exklusiv, ASR-Modelllauf nie parallel zum Emulator. `claim_files` vor jedem Multi-Datei-Auftrag.
6. **Kein Write-only ohne Verifikation.** Bei Gradle-Blockade: Verifikation per `schedule_task` in ≤ 5 min terminieren und Auftrag als `UNVERIFIZIERT` markieren.
7. **Nach „force-terminated / synthetic result":** nichts neu delegieren — erst `get_result`, dann Soll/Ist am Dateisystem, dann selbst verifizieren, dann übernehmen/`steer`/`stop`. `stop`/`steer` einzeln pro Tool-Block.
8. **Slot-Budget ≤ 6 lebende Subagenten**; Recycling über `steer` auf `done`-Sessions; fertige Sessions per Dismiss freigeben.
9. **Privacy:** keine Audio- oder Textinhalte in Logs; kein Netzwerkzugriff außer Modell-Download; keine Autokorrektur/Wörterbücher in v1.
10. **Kein `git commit`, kein Push auf main, kein Deploy** ohne ausdrückliche Freigabe des Nutzers.
11. **ALLE Subagenten IMMER im Hintergrund** (Nutzer-Befehl 2026-11-01): jede Delegation/Session mit `persist=true` als Hintergrund-Konversation starten, nie im eigenen Turn blockierend warten; Ergebnisse am Puls abholen (`get_result`/Datei), Wecker-Stand aktualisieren.
