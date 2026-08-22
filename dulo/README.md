# 🦉 DuLo (Overlay-Schachassistent für Duolingo)

Eigenständige Android-App, die im Schachmodus von Duolingo den besten Zug direkt auf dem Bildschirm anzeigt.

---

## 🌟 Kernfunktionen

1. **Unempfindlich gegenüber der Duolingo-Oberfläche**:
   - Ein Kammfilter über 8 Perioden blendet Rückmeldefenster, Figurengrafiken und schwarze Ränder zuverlässig aus;
   - die Auswertung läuft vollständig auf dem Gerät und liefert das FEN in Millisekunden.
2. **Vollständig transparentes Overlay (`FLAG_NOT_TOUCHABLE`)**:
   - Der Hinweis erscheint als leuchtender grüner Pfeil samt hervorgehobenen Feldern über dem Brett;
   - Berührungen gehen durch das Overlay hindurch, das Spiel lässt sich also normal bedienen.
3. **Stockfish vollständig offline auf dem Gerät**:
   - Der beste Zug und die Bewertung entstehen ohne jede Netzwerkverbindung auf dem Chip des Telefons.
4. **Eingebaute Diagnose einzelner Screenshots**:
   - In der App lässt sich ein Bild aus der Galerie prüfen, um Erkennung und Engine-Ausgabe nachzuvollziehen.

---

## 🚀 Bauen ohne lokale Einrichtung

### Weg A: GitHub Actions (empfohlen, erzeugt das APK vollautomatisch)
1. Das Projekt in das eigene GitHub-Repository pushen;
2. GitHub Actions startet die Pipeline [`.github/workflows/build-apk.yml`](../.github/workflows/build-apk.yml) von selbst;
3. Nach ein bis zwei Minuten unter **Actions** den letzten Lauf öffnen und unten unter **Artifacts**
   das Archiv `DuLo-APK` herunterladen, entpacken und installieren.

### Weg B: GitHub Codespaces (im Browser bearbeiten und bauen)
1. Im Repository auf **Code** klicken, **Codespaces** wählen und **Create codespace on master** starten;
2. Der Browser öffnet eine vollständige VS-Code-Umgebung in der Cloud;
3. Im Terminal ausführen:
   ```bash
   cd dulo
   ./gradlew assembleDebug
   ```
4. Nach dem Bauen liegt die Datei unter `app/build/outputs/apk/debug/app-debug.apk` und lässt sich
   im Dateibaum per **Rechtsklick → Download** speichern.

---

## 🧪 Tests

```bash
cd dulo
./gradlew testDebugUnitTest
```

Die Unit-Tests decken die Gittervermessung, den FEN-Aufbau, die Perspektiverkennung, den
Fallback-Zuggenerator, das Parsen der UCI-Ausgaben und die Engine-Konfiguration ab.
