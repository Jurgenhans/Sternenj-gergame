# Ordnerfreigabe

1. Menü ☰ → **Freigegebener Ordner** → **Ordner auswählen**. Es öffnet sich die Android-Ordnerauswahl; „Diesen Ordner verwenden“ und „Zulassen“ bestätigen.
2. Die App speichert die Berechtigung dauerhaft (`takePersistableUriPermission`). Sie übersteht App- und Telefon-Neustarts.
3. Es ist immer nur **ein** Ordner aktiv; ein neuer ersetzt den alten.

**Empfehlung:** Eigenen Ordner anlegen (z. B. `KI-Ordner`), Dateien hineinkopieren. Android verbietet auf neueren Versionen die Auswahl des Speicher-Wurzelordners sowie von `Download` und `Android/data`.

## Was die App darf
Nur innerhalb des Ordners: auflisten, lesen, erstellen, bearbeiten, umbenennen, kopieren, verschieben, Unterordner anlegen, löschen (nur nach Bestätigung).
Alle Pfade werden relativ zum Ordner aufgelöst; `..`, `\`, `content://` und Steuerzeichen werden abgelehnt. Die App hat **keine** allgemeine Speicher-Berechtigung.

## Fehlerfälle (mit verständlicher Meldung)
- Berechtigung widerrufen (in den Android-Einstellungen oder per „Freigabe widerrufen“) → Hinweis, Ordner neu wählen.
- Ordner gelöscht/umbenannt oder SD-Karte entfernt → Hinweis, neu freigeben.
- Fehlende Schreibrechte → Hinweis, neu freigeben.
Laufende Aufgaben brechen in diesen Fällen mit der Meldung ab und können nach neuer Freigabe fortgesetzt werden.

## Änderungen nachvollziehen
Vor jeder Bearbeitung/Überschreibung legt die App eine Kopie in `<Ordner>/.ki-backup/` an. Alles andere steht im Aktivitätsprotokoll.
