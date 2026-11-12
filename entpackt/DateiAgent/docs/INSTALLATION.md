# Installation

Voraussetzungen: Android 8.0+ (API 26) auf dem Telefon.

## Variante A – Android Studio (empfohlen)
1. Android Studio (aktuelle Stabil-Version) installieren; JDK 17 ist enthalten.
2. `File ▸ Open` → Ordner `DateiAgent` wählen. Android Studio lädt Gradle 8.10.2 und alle Abhängigkeiten selbst (Internet nötig).
3. Telefon: Entwickleroptionen + USB-Debugging aktivieren, per USB verbinden, oben das Gerät wählen, **Run ▶**.
4. APK-Datei erzeugen: `Build ▸ Build Bundle(s)/APK(s) ▸ Build APK(s)`.
   Ergebnis: `app/build/outputs/apk/release/app-release.apk` (mit Debug-Schlüssel signiert, direkt installierbar).
5. Datei aufs Telefon kopieren und öffnen; „Installation aus unbekannten Quellen“ für die Dateien-App erlauben.

Hinweis: Das Projekt enthält `gradle-wrapper.properties`, aber keine `gradlew`-Skripte. Android Studio braucht sie nicht. Für die Kommandozeile
einmal `gradle wrapper --gradle-version 8.10.2` ausführen (Gradle muss installiert sein), danach `./gradlew assembleRelease`.

## Variante B – GitHub Actions (ohne lokale Installation)
1. Projekt in ein GitHub-Repository hochladen (`.github/workflows/build.yml` liegt bei).
2. Tab **Actions** → „Build APK“ → Artefakt **DateiAgent-apk** herunterladen. Der Lauf führt auch die Unit-Tests aus.

## Eigene Signatur (für Weitergabe/Play Store)
Im `release`-Block von `app/build.gradle.kts` die `signingConfig` durch einen eigenen Keystore ersetzen. Den Keystore niemals ins Repository einchecken.

## Erster Start
1. Menü ☰ → **Freigegebener Ordner** → Ordner wählen.
2. **KI-Einstellungen** → API-Schlüssel eintragen, Einwilligung zur Übertragung aktivieren, „Speichern“, „Verbindung testen“.
3. **Berechtigungen** → Benachrichtigungen erlauben.
4. Im **KI-Chat** eine Aufgabe eingeben.
