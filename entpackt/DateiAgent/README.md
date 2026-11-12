# DateiAgent – autonomer KI-Dateiassistent für Android

Du gibst der App **einen Ordner** frei (Storage Access Framework) und erteilst Aufgaben in normaler deutscher Sprache.
Eine Cloud-KI (OpenAI-API, austauschbar) plant die Schritte und führt sie über **echte Werkzeuge** im Ordner aus.
Jede Aktion wird von der App ausgeführt, geprüft und protokolliert – die KI kann Ergebnisse nicht nur „behaupten“.

> **Ehrlicher Stand:** Der Quellcode ist vollständig, wurde aber in der Entwicklungsumgebung **nicht kompiliert und nicht auf einem Gerät getestet**
> (dort gab es weder Android SDK noch Zugriff auf Maven/Google-Repositories). Die APK entsteht beim ersten Build bei dir
> (Android Studio oder GitHub Actions, siehe `docs/INSTALLATION.md`). Kleinere Compiler-Korrekturen beim ersten Build sind möglich.
> Die Unit-Tests (`app/src/test`) prüfen die Kernlogik; das Geräte-Testprotokoll steht in `docs/TESTPLAN.md`.

## Dokumentation
- `docs/INSTALLATION.md` – Bauen & Installieren der APK
- `docs/KI_EINRICHTUNG.md` – OpenAI-Schlüssel, Modell, Proxy, weitere Anbieter
- `docs/ORDNERFREIGABE.md` – Ordner freigeben, widerrufen, Fehlerfälle
- `docs/FUNKTIONEN.md` – Funktionen, Werkzeuge, Architektur, Sicherheit, Grenzen
- `docs/TESTPLAN.md` – Automatisierte Tests + manuelle Geräte-Tests

## Aufbau (`app/src/main/java/de/dateiagent`)
| Paket | Inhalt |
|---|---|
| `fs` | `VirtualFs`, `PathValidator` (Sandbox), `SafFileSystem` (SAF), `FolderAccess` (persistente Berechtigung) |
| `extract` | Textextraktion TXT/PDF/DOCX/XLSX/CSV/JSON/XML/HTML, Office-Schreiber, Bild-Kodierung |
| `tools` | Die 13 geforderten Tools + `delete_file`, `remember`, `recall` |
| `ai` | `LlmProvider`-Schnittstelle, OpenAI-kompatibler Client (Function Calling) |
| `agent` | `AgentRunner`: Plan → Ausführen → Prüfen → Abschluss, Freigaben, Checkpoints |
| `work` | WorkManager-Worker (Foreground), Aufgabenverwaltung, Wiederherstellung |
| `data` | Room (Aufgaben, Protokoll, Gedächtnis), verschlüsselte Einstellungen |
| `ui` | Jetpack Compose: Chat, Dateimanager, Ordner, Aufgaben, Protokoll, KI-Einstellungen, Berechtigungen |
