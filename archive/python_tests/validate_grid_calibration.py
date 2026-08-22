# -*- coding: utf-8 -*-
"""Regressionsprüfung der Feinkalibrierung über die Gitterlinien (Gatterskript der Stufe 1).

Die Referenzmenge besteht aus 7 vom Nutzer benannten Duolingo-Screenshots und dem Unfall-Frame aus Stufe 4 (zusammen 8 Frames). Die Sollwerte wurden unabhängig gemessen
und von Hand geprüft (2026-08-17/18): abgedeckt sind Layouts mit Rand (duolingo_1, Screenshot_20260816_131145),
bildbreite Layouts (duolingo_2, bug_16, bug_17, Screenshot_20260817_121754)
und ein Ausschnitt mit negativem Rand (bug_20_lowsim) sowie beide y-Lagen aus Partien gegen den Computer und gegen Menschen.
Screenshot_20260818_225702 ist der Unfall-Frame vom Gerät, auf dem Sprechblase und Figurengrafik den oberen Teil des Bretts verdecken (Kotlin ließ sich dort
beim refine im 400er-Raum von einem Phantomrahmen überbieten) und dient als Regressionsschutz.

Zusicherungen (Testfälle):
1. Keine Lokalisierung führt sofort zum Fehlschlag (None darf nicht stillschweigend durchgehen);
2. |Fehler in x0|, |Fehler in y0| und |Fehler in size| liegen jeweils unter TOL_PX (4px);
3. Ein Frame mit negativem Rand (Rect außerhalb des Bildes) muss erkannt werden: die Confidence darf nicht high sein
   (high bleibt Brettern vorbehalten, die vollständig sichtbar sind und beide Belege bestehen; ein Ausschnitt lässt sich nicht vollständig prüfen
   und kann nur gemeldet werden). Für Frames ohne Überlauf gilt diese Einschränkung nicht.

Vereinbarungen:
- Fehlt ein Referenzbild, scheitert das Skript deutlich (FileNotFoundError), damit die CI es direkt einbinden kann und nichts still übersprungen wird;
- die Sollwerte stehen als ganzzahlige Tripel (x0, y0, size) fest; bei einem anderen Gerät oder einer neuen Oberfläche
  kommen nach demselben Verfahren (messen und prüfen) neue Frames hinzu, statt die Parameter des Verfahrens zu ändern.
"""
import os
import sys
import time
from typing import Dict, Optional, Tuple

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from grid_calibrate import load_image, locate_board  # noqa: E402

BASE = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
TOL_PX = 4.0

Rect = Tuple[int, int, int]

# Verzeichnis der Sollwerte: Datei -> (x0, y0, size, Beschreibung des Layouts).
# Bei bug_20_lowsim ist size=1264 größer als die Bildschirmbreite 1260, das ist die typische Form eines Ausschnitts mit negativem Rand.
# Screenshot_20260816_131145 stammt vom Gerät und hat einen Rand (je etwa 61px), damit ist auch dieser Fall abgedeckt.
GROUND_TRUTH: Dict[str, Tuple[Rect, str]] = {
    'duolingo_1.jpeg':       ((36, 459, 677),  'mit Rand'),
    'duolingo_2.jpg':        ((0, 719, 1179),  'bildbreit'),
    'bug_16.jpg':            ((0, 1029, 1260), 'bildbreit'),
    'bug_17.jpg':            ((0, 1029, 1260), 'bildbreit'),
    'bug_20_lowsim.jpg':     ((-4, 989, 1264), 'Ausschnitt mit negativem Rand'),
    'Screenshot_20260816_131145.jpg': ((61, 1153, 1137), 'Gerät, mit Rand'),
    'Screenshot_20260817_121754.jpg': ((0, 936, 1260),   'Gerät, bildbreit'),
    'Screenshot_20260818_225702.jpg': ((0, 1030, 1260),  'Gerät, bildbreit, von Sprechblase und Grafik verdeckt'),
}


def is_cropped(rect: Rect, img_w: int, img_h: int) -> bool:
    """Ragt der Brettrahmen über das Bild hinaus, gilt der Frame als Ausschnitt (negativer Rand)."""
    x0, y0, size = rect
    return x0 < 0 or y0 < 0 or x0 + size > img_w or y0 + size > img_h


def check_frame(name: str) -> Optional[str]:
    """Prüft einen Frame; None bedeutet bestanden, sonst folgt der Grund als Text."""
    path = os.path.join(BASE, name)
    if not os.path.exists(path):
        # Deutlicher Fehlschlag: ein fehlendes Referenzbild muss in der CI auffallen und darf nicht übersprungen werden
        raise FileNotFoundError(f'Referenzbild fehlt: {path}')
    truth, note = GROUND_TRUTH[name]
    img = load_image(path)
    img_h, img_w = img.shape[:2]

    t0 = time.time()
    res = locate_board(img, top_n=3)
    cost = time.time() - t0
    if res is None:
        return f'{name}: Lokalisierung fehlgeschlagen (kein Kandidat) [{note}]'

    rect = res['rect']
    errs = [f'{axis}: ist {got}, soll {tru}, Differenz {got - tru:+d}'
            for axis, got, tru in zip(('x0', 'y0', 'size'), rect, truth)
            if abs(got - tru) > TOL_PX]

    cropped = is_cropped(rect, img_w, img_h)
    # Ein Ausschnitt kann nur erkannt und gemeldet werden: ragt der Rahmen über das Bild hinaus, sind die Gitterlinien unvollständig,
    # gibt das Verfahren trotzdem high aus, erzwingt es eine Übereinstimmung und verletzt die Vereinbarung für negative Ränder
    if cropped and res['confidence'] == 'high':
        errs.append(f'Ausschnitt mit high bewertet (er darf nur gemeldet, nicht erzwungen werden)')

    status = ('PASS' if not errs else 'FAIL')
    crop_tag = ' [Ausschnitt erkannt]' if cropped else ''
    print(f'{status}  {name:24s} rect={rect} conf={res["confidence"]}'
          f'{crop_tag}  resid={res["residual"]:.2f}  {cost:.1f}s  [{note}]')
    for e in errs:
        print(f'      -> {e}')
    return f'{name}: ' + '; '.join(errs) if errs else None


def main() -> int:
    print(f'Regressionsprüfung der Gitterlinien-Feinkalibrierung (Toleranz {TOL_PX:.0f}px, {len(GROUND_TRUTH)} Frames)')
    failures = []
    for name in GROUND_TRUTH:
        try:
            err = check_frame(name)
        except FileNotFoundError as exc:
            print(f'FAIL  {exc}')
            return 2
        if err:
            failures.append(err)
    print()
    if failures:
        print(f'Ergebnis: {len(failures)}/{len(GROUND_TRUTH)} Frames nicht bestanden')
        for f in failures:
            print(f'  - {f}')
        return 1
    print(f'Ergebnis: alle {len(GROUND_TRUTH)}/{len(GROUND_TRUTH)} Frames bestanden')
    return 0


if __name__ == '__main__':
    sys.exit(main())
