# Testplan

## Automatisiert (`gradle testDebugUnitTest`, läuft auch in GitHub Actions)
| Datei | Prüft |
|---|---|
| `FsAndExtractTest` | Pfad-Sandbox (`..`, `\`, URIs), Namensprüfung, DOCX/XLSX/CSV/HTML/Latin-1, Chunking |
| `ToolsTest` | alle Tools gegen In-Memory-Dateisystem: Erstellen+Verifikation, Backup, Überschreibschutz, Freigabe-Pflicht, Move/Copy/Rename, Suche, Berichte, XLSX-Erzeugung |
| `AgentRunnerTest` | Mehrstufige Aufgabe mit echten Dateiänderungen, Freigabe→Ausführung, Ablehnung, Sandbox-Verstoß, Fehlerfälle, Wiederaufnahme aus Checkpoint, Pausieren, fehlende Einwilligung, widerrufener Ordner, Schrittlimit, Kontext-Kürzung |
| `OpenAiProviderTest` | Request-Format (Tools, Bilder), Antwort-Parsing, HTTP-Fehlerabbildung |

## Manuell auf dem Gerät (nicht automatisierbar ohne Gerät – bitte abhaken)
1. **Ordnerfreigabe:** Ordner wählen → Dateimanager zeigt Inhalt.
2. **Dauerhafter Zugriff:** App aus Recents wischen / Telefon neu starten → Dateimanager funktioniert ohne neue Auswahl.
3. **Widerruf:** Freigabe in der App widerrufen (oder Ordner umbenennen) → verständliche Meldung, Chat sperrt Aufgaben.
4. **Dateioperationen (manuell):** Ordner anlegen, umbenennen, löschen (mit Dialog) im Dateimanager.
5. **KI-Verbindung:** „Verbindung testen“ → Erfolg; falscher Schlüssel → Meldung „ungültig (401)“.
6. **Tool-Aufrufe:** Aufgabe „Liste alle Dateien auf und lege eine Datei uebersicht.txt mit den Namen an“ → Datei existiert wirklich; Protokoll zeigt Tool-Einträge.
7. **Mehrstufig:** Beispielaufgaben 1–6 aus dem Projektauftrag (Rechnungen → Excel öffnen und Zahlen gegen Originale prüfen).
8. **Bestätigung:** „Lösche die Datei test.txt“ → Dialog/Benachrichtigung; Ablehnen → Datei bleibt; Erlauben → weg.
9. **Fehlerbehandlung:** Flugmodus während Aufgabe → Wiederholung/Fehlermeldung; danach „Fortsetzen“.
10. **Hintergrund:** Aufgabe starten, App verlassen → Benachrichtigung „arbeitet…“, Ergebnis-Benachrichtigung.
11. **Neustart-Wiederherstellung:** Aufgabe starten, App im Task-Manager beenden → nach Neustart läuft sie weiter (Protokoll: „Unterbrochen … wiederhergestellt“).
12. **Formate:** je eine TXT/PDF/DOCX/XLSX/CSV/JSON/XML/HTML/JPG/PNG lesen lassen (Bild: Bildanalyse in Einstellungen erlauben).
