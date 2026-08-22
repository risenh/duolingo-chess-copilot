# Umbau der Lokalisierung: direkte Vermessung der Gitterlinien

## Vorgeschichte (Fehlerbild und Ursachenkette)

**Beobachtung auf dem Gerät**: Die farbigen Markierungen des Overlays wandern zur Brettmitte hin,
in der Mitte ist die Abweichung praktisch null, am Rand am größten (oben links etwa ein Sechstel
eines Feldes). Historisch kamen dazu gelegentliche Verwechslungen von Turm, Springer, Läufer und
Dame sowie ein MedianSim, der immer wieder an das Gatter bei 0.52 stieß.

**Befunde (in dieser Sitzung abgeschlossen, fünf voneinander unabhängige Belege)**:
1. Telemetrie vom Gerät: `BoardRect(19,1046-1251,2278)`, size=1232 ≈ 0.98×1260 – der Wert klebt an
   der oberen Schranke `ChessLocator.maxSize = min(sW, 0.98*sW)`;
2. Vermessung der Gitterlinien im Screenshot: das echte Brett ist mit size=1260 (x0=0) volle
   Bildschirmbreite – der wahre Wert liegt also außerhalb des Suchraums;
3. Die Position der Markierungen deckt sich pixelgenau mit dem "auf 440/450 skalierten Rechteck"
   (im 450er-Raum über 4 Frames höchstens 1px Abweichung). Das Overlay zeichnet also korrekt,
   falsch ist das Rechteck;
4. Dasselbe Rechteck schneidet auch die Felder für den Klassifikator zu: am Rand liegt das Fenster
   um bis zu 5.9px daneben (im 48er-Raum), das anatomische Kopfmerkmal trifft den "Hals", der
   Kopf-Kosinus kippt und Turm, Springer, Läufer und Dame werden verwechselt. Weil Vorlagen und
   Laufzeitzuschnitt aus verschiedenen Koordinatensystemen stammen, sinkt zusätzlich der MedianSim
   auf ganzer Breite;
5. Zweites Fehlerbild: ein Scheinpeak kann size auf 0.87W drücken (bug_12, size=1093), die Erkennung
   verliert das ganze Brett und wird vom Gatter abgewiesen. Dazu kommt ein Restversatz von 5px in der
   Mitte, verursacht von der erzwungenen Annahme, das Brett sei waagerecht zentriert;
6. Ankerpunkte an Ober- und Unterkante (vom Nutzer benannt und bestätigt): über und unter dem Brett
   liegt je ein bildbreites Rechteck (oben mit dem Band der geschlagenen Figuren, unten ein dunkler
   Balken), beide sind unterschiedlich hoch (der untere ist höher und sauberer). Das Zeilenprofil des
   Screenshots zeigt: Unterkante des oberen Rechtecks bei y=1028 (die Zeilen-std bricht auf 0.8 ein),
   Oberkante des unteren bei y=2289 (std 3.6), Abstand 1261 = Bildschirmbreite 1260, also **exakt
   stimmig**; die reine Extrapolation aus den inneren Gitterlinien liefert dagegen y0≈1032 und driftet
   um 4px. Für die senkrechte Begrenzung sind die beiden Rechtecke damit die besseren Hauptanker.

**Verworfene Alternativen**: Homographie, Umrechnung über die DPI, Hough-Transformation für die vier
Ecken. Screenshots haben keine perspektivische Verzerrung, Screenshot und Overlay teilen sich
nachweislich dasselbe Koordinatensystem, und ein bildbreites Brett hat keinen äußeren Rahmen, den man
detektieren könnte – all das wäre überdimensioniert. Eine manuelle Kalibrierung über vier Punkte
bleibt als Notausgang für später vorgemerkt, ist aber nicht Teil dieses Umbaus.

## Kern des Vorhabens

Der Lokalisator wird in zwei Stufen geteilt: **die Grobsuche liefert nur einen Näherungsrahmen**
(die Größenschranke wird gelockert), **die Feinkalibrierung bestimmt den Endwert aus der gemessenen
Geometrie**. Waagerecht und senkrecht werden getrennt behandelt und stützen sich auf verschiedene Anker:

- **Waagerecht (x/size) – einheitliche arithmetische Ausgleichsrechnung ohne Sonderfälle**: Es wird
  nicht vorab entschieden, ob das Brett bildbreit ist oder einen Rand hat. Über die inneren senkrechten
  Linien läuft immer der Ansatz `x_i = x0 + i·step`; bei einem bildbreiten Brett konvergiert das von
  selbst gegen x0≈0 und step≈W/8. Weicht das Ergebnis um höchstens 2px von der vollen Breite ab, rastet
  es auf 0 ein, sonst gilt der berechnete Wert (so bleiben Geräte mit schmalem Rand korrekt und es
  entsteht kein Streckfehler am Rand);
- **Senkrecht (y) – Hauptanker über die beiden Rechtecke plus doppeltes Gatter**: Beide Rechtecke
  werden als bildbreite Bänder erkannt (Zeilenmittelprofil und niedrige Varianz innerhalb der Zeile),
  **die Unterkante des oberen Rechtecks ist die Oberkante des Bretts, die Oberkante des unteren
  Rechtecks dessen Unterkante**. Beide werden getrennt gesucht, weil sie unterschiedlich aussehen.
  **Doppeltes Gatter**: Der Anker gilt nur, wenn ① `Oberkante unten − Unterkante oben` ≈ size ist
  (höchstens 4px Abweichung) und ② an beiden Stellen die std deutlich einbricht. Fehlt eines von
  beidem (Endspiel ohne geschlagene Figuren, von der Blase verdeckte Kanten, dunkles Design mit
  anderem std-Verlauf), greift der Rückfallpfad über die inneren waagerechten Linien, die von
  Änderungen der Oberfläche unberührt bleiben;
- **Unterdrückung von Scheinpeaks über die Kammwellenlänge**: Vor der Peaksuche legt ein Kammfilter
  die Feldbreite λ≈W/8 fest und siebt periodisch vor; bei der Ausgleichsrechnung fallen einzelne
  Peaks heraus, die zu weit vom arithmetischen Gitter abweichen. So werden dicht stehende Figuren
  (Damenkrone, Bauernkopf) nicht mehr als Trennlinien gezählt und die Linienindizes verrutschen nicht;
- **Confidence-Vereinbarung**: Die Feinkalibrierung liefert einheitlich `RefineResult(rect, confidence,
  residual)`. Bei einem Residuum über 2.5px oder zu wenigen Linien bleibt der Grobrahmen stehen und die
  Confidence wird herabgestuft – als Telemetrie und als Grundlage, die Gatter später zu kalibrieren;
- Die inneren Gitterlinien bleiben als zweiter Beleg und Rückfallpfad erhalten und tragen später den
  Ausbau auf "feldweise ungleichmäßige Grenzen". Der Fehler wird innerhalb eines Feldes aufgefangen,
  die Vorannahme "bildbreit oder mit Rand" entfällt vollständig, beide Layouts funktionieren.

## Stufe 1: Prototyp in Python (ohne Änderungen an der App)

- Neu: `tools/grid_calibrate.py`
  - Eingabe ist der Grobrahmen (weiterhin aus fast_sat/comb, die Größenschranke vorübergehend auf
    1.0W), Ausgabe sind das feinkalibrierte Rect und die Diagnose (Anzahl gefundener Linien, Residuum);
  - Senkrechter Hauptanker: In einem Band über dem Grobrahmen (Oberkante −0.2W bis Oberkante) und
    einem darunter (Unterkante bis Unterkante +0.2W) entsteht ein bildbreites Zeilenmittelprofil;
    Peaks des Betrags der Ableitung zusammen mit dem Kriterium **niedrige std innerhalb der Zeile**
    (gleichmäßiges Band) liefern die Kanten. Oben gilt die innerste starke Kante mit Hell-Dunkel- oder
    Dunkel-Hell-Wechsel und einbrechender std als Unterkante des oberen Rechtecks, unten spiegelbildlich
    die Oberkante des unteren. Besteht das doppelte Gatter (Höhe ≈ Breite mit höchstens 4px Abweichung
    und an beiden Stellen deutlicher std-Einbruch), gilt der Anker, sonst greift die Ausgleichsrechnung
    über die inneren Linien;
  - Waagerechter Ablauf: einheitliche Ausgleichsrechnung – Spaltenmittelprofil aus 8 Zeilenbändern →
    Vorsieben über die Kammwellenlänge λ≈W/8 → Peaksuche auf dem Gradienten → Clustern über die
    Zeilenbänder hinweg (mindestens 6 Linien) → einzelne Peaks abseits des arithmetischen Gitters
    entfernen → `x_i = x0 + i·step` ausgleichen; bei höchstens 2px Abweichung von der vollen Breite
    einrasten, bei einem Residuum über 2px zurück zum Grobrahmen;
  - Die Ausgleichsrechnung über die inneren waagerechten Linien dient senkrecht als zweiter Beleg und
    als Rückfallpfad und wird gegen den Ankerwert geprüft (gemessene Abweichung in der Größenordnung
    von 4px; besteht das Gatter, hat der Anker Vorrang);
  - Die Profile werden über Zeilen- und Spaltenbänder gemittelt und sind damit von Natur aus robust
    gegen verdeckte Linien und JPEG-Rauschen; zusätzlich erzwingen künstlich verfälschte Frames
    (überschriebene Bereiche der beiden Rechtecke) den Test des Rückfallpfades.
- Neu: das Regressionsskript `tools/validate_grid_calibration.py`
  - Referenzmenge: `pianyi_1~4` (Sollwert 450, bildbreit), `Screenshot_20260817_162715` (Sollwert 1260,
    bildbreit), `duolingo_1~3`, `duolingo_test_1~3`, `bug_11~20` (mit Rand und schwierigen Stellungen,
    als Regressionsschutz). Bekannte Lücke: Frames aus Lernlektionen, ohne Leiste für geschlagene
    Figuren und im dunklen Design müssen noch auf dem Gerät aufgenommen werden, bis dahin vertreten
    sie die künstlich verfälschten Frames;
  - Je Bild werden ausgegeben: Grobrahmen gegen feinkalibriertes Rect, der Fehler in size/x0/y0
    gegenüber dem aus dem Profil gemessenen Sollwert, mit der Zusicherung |Fehler in size| ≤ 4px;
  - Besonders zu prüfen: bei älteren Aufnahmen mit Rand entstehen keine Scheinpeaks, und Frames vom Typ
    bug_12 werden von der Feinkalibrierung wieder eingefangen.
- Die Zahlen dieser Stufe sind das Kriterium für die Freigabe: erst wenn alle Referenzfälle bestehen,
  folgt Stufe 2.

## Stufe 2: Portierung nach Kotlin (nur die Lokalisierung)

Geändert wird `dulo/app/src/main/java/com/dulo/app/core/ChessLocator.kt`:
- Die Schranke `maxSize` steigt von `0.98*sW` auf `sW` (damit liegt ein bildbreites Brett im Suchraum;
  bei size=sW bleibt für x nur noch 0 übrig, das hat keine Nebenwirkungen);
- Neu ist `refineByGridLines(gray, box): RefineResult(rect, confidence, residual)`, die Kotlin-Umsetzung
  des Verfahrens aus Stufe 1 (die Zeilenprofile nutzen das bereits vorhandene Graustufenarray, das
  kostet Millisekunden). Vor der Rückgabe aus `locateTopCandidates` wird jeder der besten N Kandidaten
  **einzeln** nachgezogen (keine gemeinsamen Anker, sonst rastet ein Scheinkandidat im Bereich der Blase
  auf einer falschen Kante ein). Bei einem Residuum über 2.5px oder fehlendem Anker mit weniger als 6
  Linien bleibt der Grobrahmen des Kandidaten stehen und die Confidence sinkt (mit Telemetrie, wie schon
  bei bug_18, als Grundlage für spätere harte Schwellen). Die bestehende Kandidatenrettung bleibt unberührt;
- Klassifikator, Overlay, Rettungslogik und Gatterschwellen bleiben unverändert.

## Stufe 3: Abgleich mit dem Python-Zwilling und Gatter in der CI

- `tools/test_full_pipeline_v2.py` und `tools/comb_locator.py` bekommen dasselbe refine (die Umsetzungen
  in Kotlin und Python bleiben deckungsgleich, Lektion aus bug_19);
- `validate_grid_calibration.py` wird in das bestehende Python-Gatter der CI eingehängt
  (`.github/workflows/build-apk.yml`), die Referenzbilder liegen bereits in der Versionsverwaltung.
  Fehlende Eingaben führen zum Abbruch mit deutlicher Meldung, nicht zum stillen Überspringen.

## Stufe 4: Regression auf dem Gerät (mit Unterstützung des Nutzers)

- Nach der Installation eine Partie spielen und aus `last_diagnostic.txt` das BoardRect prüfen
  (erwartet: x0≈0, y0≈1028, size≈1260);
- Per Screenshot prüfen, ob die Markierungen auf den Feldern sitzen (erwartet: der Versatz am Rand
  sinkt von etwa 24px auf höchstens 4px);
- Bei stehendem Brett zweimal tippen und prüfen, dass das FEN stabil bleibt, der MedianSim steigt und
  weniger Felder knapp am Gatter liegen.

## Erwarteter Nutzen und Risiken

| Punkt | Heute | Erwartet |
|---|---|---|
| Versatz des Overlays am Rand | ~24px (etwa ein Sechstel Feld) | ≤4px (etwa ein Vierzigstel Feld) |
| Versatz des Zuschnitts (48er-Raum) | bis zu 5.9px | ≤1.5px |
| Akute Verwechslungen wie bug_12 | Scheinpeak bei 0.87W, Brett komplett falsch | von der Feinkalibrierung korrigiert oder vom Residuum-Gatter gemeldet |
| Restversatz von 5px in der Mitte | vorhanden | durch die gemessene Phase beseitigt |

Risiken: Bei stark verdeckten Frames werden zu wenige Linien gefunden – dafür bleibt der Grobrahmen als
Rückfall. Fällt die Leiste der geschlagenen Figuren weg (Lernlektion, kein Schlagen, anderes Design),
fangen das doppelte Gatter und die inneren Gitterlinien den Fall ab, geprüft an künstlich verfälschten
Frames. Falsche Kanten eines Scheinkandidaten werden durch das kandidatenweise refine und das
Residuum-Gatter abgefangen. Für dunkle Frames und Lernlektionen fehlen noch echte Aufnahmen. Rücknahme:
alles steckt in einer Datei, ein revert genügt.

## Ausdrücklich nicht Teil des Vorhabens

- Keine Homographie, keine Umrechnung über die DPI, keine Hough-Transformation für die Ecken
  (im vorliegenden Szenario nicht nötig, oben begründet);
- Keine Änderung an den Merkmalen des Klassifikators oder an den Gatterschwellen (zuerst die Ursache
  beheben, danach über den Nutzen neu entscheiden);
- Keine manuelle Kalibrierung über vier Punkte (bleibt eine Option für später).
