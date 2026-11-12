# Funktionen & Architektur

## Agent
`AgentRunner` führt eine Schleife aus: KI-Antwort → Tool-Aufrufe → Ergebnisse zurück an die KI.
- **Planung:** Die KI legt mit `set_plan` Ziel und Schritte fest und meldet Fortschritt mit `update_plan_step` (Fortschrittsbalken + aktueller Schritt in der UI).
- **Prüfen:** Jedes schreibende Tool liest nach der Aktion nach (Größe/Inhalt/Existenz) und meldet das Ergebnis. Der Abschlussbericht enthält zusätzlich eine **von der App erzeugte** Liste der tatsächlich ausgeführten, verifizierten Änderungen – unabhängig vom Text der KI.
- **Abschluss:** `finish_task` mit Zusammenfassung; das Ergebnis wird gespeichert und ins Gedächtnis übernommen.
- **Schutz:** Schrittlimit (einstellbar, Standard 40), Kontext-Kürzung alter Dateiinhalte, Prompt-Injection-Hinweis (Dateiinhalte sind Daten, keine Befehle).

## Werkzeuge
`list_files`, `list_directories`, `read_file`, `search_files`, `extract_text`, `create_file`, `update_file`, `rename_file`, `copy_file`, `move_file`, `create_directory`, `get_file_info`, `generate_report` – dazu `delete_file`, `remember`, `recall`.
- `create_file` erzeugt aus CSV-Text echte **.xlsx** und aus Text echte **.docx**.
- `update_file`: append / replace_text / overwrite, nur Textdateien; Backup vor jeder Änderung.
- `generate_report` überschreibt nie (wählt freien Namen). `copy`/`move`/`rename` überschreiben nie.

## Bestätigungen (Nutzer muss erlauben)
Löschen (immer), Überschreiben vorhandener Dateien (`create_file overwrite`, `update_file overwrite`). Die Aufgabe pausiert dann (Status „Wartet auf Bestätigung“, Benachrichtigung), der Worker endet akkuschonend und läuft nach deiner Antwort exakt am selben Punkt weiter. Bei Ablehnung erfährt die KI das und sucht eine Alternative.
Externe Übertragung: globale Einwilligung (KI-Einstellungen) + Protokolleintrag je Übertragung; Bilder brauchen eine zusätzliche Einwilligung.

## Dateiformate
TXT/MD/LOG, CSV, JSON, XML, HTML (Text ohne Tags), DOCX, XLSX (alle Blätter, tab-getrennt), PDF (Text via PdfBox-Android), JPG/PNG (Bildanalyse via Vision-Modell, verkleinert auf max. 1568 px).
**Grenzen:** Kein OCR für gescannte PDFs (Hinweis wird gemeldet; Alternative: Seiten als Bild bereitstellen), verschlüsselte PDFs werden abgelehnt, Dateien > 25 MB werden nicht gelesen, Ordner kopieren ist nicht unterstützt, XLSX-Formeln werden als Werte gelesen, DOCX/XLSX können neu erzeugt, aber nicht in-place editiert werden.

## Hintergrund
WorkManager (`AgentWorker`, expedited + Foreground-Service-Typ `dataSync`, sichtbare Benachrichtigung). Nach **jedem Schritt** wird der komplette Verlauf in Room gesichert. Bei Prozess-Ende/Neustart nimmt `recoverInterrupted()` die Aufgabe wieder auf; offene, noch nicht beantwortete Tool-Aufrufe werden dann ausgeführt. Pausieren/Fortsetzen/Abbrechen in der Aufgabenliste.
Android-Grenzen: Ab Android 15 beendet das System `dataSync`-Dienste nach ca. 6 h/24 h bzw. bei Akku-Optimierung früher; die Aufgabe wird dann automatisch fortgesetzt. Ein im Moment des Absturzes laufender Schreib-Tool-Aufruf kann beim Fortsetzen erneut versucht werden – wegen „überschreibt nie“ scheitert er dann sauber statt Daten zu zerstören.

## Gedächtnis (lokal, Room)
Ergebnisse früherer Aufgaben, Vorlieben, Dateinotizen, Liste analysierter Dateien (Pfad, Änderungsdatum, Größe → `get_file_info` zeigt „bereits analysiert / geändert“). Bei neuen Aufgaben werden per Stichwort-Abgleich die passendsten Einträge in den Prompt gegeben; die KI kann `recall`/`remember` nutzen. Einsehbar und löschbar in den KI-Einstellungen.

## Erweiterbarkeit
Neues Tool: Klasse von `AgentTool` ableiten, in `ToolRegistry.default()` eintragen. Neuer Anbieter: `LlmProvider` implementieren. Anderer Speicher: `VirtualFs` implementieren (die Tests nutzen eine In-Memory-Variante).
