# Cloud-KI einrichten

## OpenAI (Standard)
1. Auf platform.openai.com ein Konto anlegen, Guthaben hinterlegen, unter *API keys* einen Schlüssel erzeugen (`sk-…`).
2. In der App: **KI-Einstellungen** → Anbieter „OpenAI“, Modell eintragen (Standard `gpt-4o`; jedes Chat-Modell mit Tool-Calling und – für Bilder – Vision funktioniert, z. B. `gpt-4.1`). Das Modell ist frei änderbar.
3. Schlüssel einfügen → **Speichern** → **Verbindung testen**.

## Wie der Schlüssel geschützt ist
- Er ist **nicht in der APK** und nicht im Quellcode. Du gibst ihn zur Laufzeit ein.
- Gespeichert wird er mit `EncryptedSharedPreferences` (AES-256-GCM, Master-Key im Android Keystore). Backups sind deaktiviert (`allowBackup=false`).
- Grenze: Wer dein entsperrtes, gerootetes Gerät kontrolliert, kann ihn auslesen. Setze in OpenAI ein **Ausgabenlimit** und nutze ein eigenes Projekt für diese App.

## Sicherste Variante: eigener Proxy
Ein kleiner Server (z. B. Cloud-Function) hält den echten Schlüssel und leitet `POST /v1/chat/completions` weiter; die App bekommt nur ein widerrufbares Token.
In der App: Anbieter „OpenAI-kompatibel“, Basis-URL = deine Proxy-URL (`https://…/v1`), im Schlüsselfeld das Token. Auf dem Proxy: Rate-Limit, Modell-Whitelist, Logging.

## Weitere Anbieter
Alles mit OpenAI-kompatibler Chat-Completions-Schnittstelle und Tool-Calling geht ohne Codeänderung über „OpenAI-kompatibel“ (Basis-URL + Modell).
Für Anbieter mit anderem Protokoll (z. B. Anthropic-Messages-API): Klasse `LlmProvider` implementieren (`ai/Llm.kt`), in `AppContainer.provider()` und `Providers.presets` eintragen. Agent, Tools und UI bleiben unverändert.

## Kosten & Datenschutz
Jede gelesene Dateipassage geht an den Anbieter und kostet Tokens. Große Dateien werden in Abschnitte (12.000 Zeichen) geteilt; alte Tool-Ergebnisse werden im Verlauf gekürzt.
Ohne Einwilligung in den KI-Einstellungen startet **keine** Aufgabe. Das Aktivitätsprotokoll zeigt jede Übertragung (Datei, Zeichenzahl, Ziel-Host).
