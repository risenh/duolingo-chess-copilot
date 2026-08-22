# 🤝 Leitfaden für Beiträge (Contributing Guide)

Vielen Dank für dein Interesse an **DuLo**! Beiträge aus der Community sind
ausdrücklich willkommen: Code, Fehlerbehebungen, Verbesserungen an den Algorithmen und Vorschläge
für neue Funktionen.

---

## 🛠️ Entwicklung und lokale Tests

### Voraussetzungen
1. **JDK 17** (empfohlen: Temurin JDK 17)
2. **Android Studio** (empfohlen: Hedgehog / Iguana / Ladybug oder neuer)
3. **Android NDK** (nur nötig, wenn die Stockfish-Quellen selbst übersetzt werden sollen)
4. **Git LFS** (nach dem Klonen `git lfs pull` ausführen, sonst fehlen die echten NNUE-Gewichte)

### Selbsttest vor dem Einreichen
Vor einem Pull Request müssen alle Kotlin-Unit-Tests durchlaufen:

```bash
cd dulo
./gradlew testDebugUnitTest
```

---

## 📝 Konventionen für Commits und Pull Requests

1. **Branches**:
   - Neue Zweige für Funktionen oder Fehlerbehebungen von `master` abzweigen:
     `git checkout -b feature/dein-feature-name` bzw. `fix/deine-fehlerbehebung`.
2. **Commit-Format**:
   - Empfohlen sind Conventional Commits, zum Beispiel:
     - `feat: neues Verfahren zur Erkennung der Brettkanten`
     - `fix: falsche Größenberechnung des Overlays bei bestimmten Auflösungen`
     - `docs: englische README ergänzt`
     - `test: Unit-Test für die Königsstellung im Endspiel`
3. **Codestil**:
   - Dem offiziellen Kotlin-Styleguide folgen, keine impliziten ungeprüften Umwandlungen;
   - Neue Algorithmen oder mathematische Berechnungen brauchen passende Unit-Tests.

---

## 🐛 Fehler melden (Bug Reports)

Wenn die Lokalisierung oder die Erkennung in einem bestimmten Duolingo-Level oder auf einem
bestimmten Gerät danebenliegt, freuen wir uns über ein Issue. Bitte lege bei:
- Gerätemodell und Android-Version;
- einen Screenshot der fehlerhaften Situation (für die Reproduktion bitte unter `test_images/bugs/` ablegen);
- die Schritte zur Reproduktion und das erwartete Ergebnis.
