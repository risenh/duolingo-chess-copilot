"""Feinkalibrierung über die Gitterlinien (Python-Prototyp der Stufe 1, Zwilling zu refineByGridLines in Kotlin).

Entwurfsgrundsätze (Umbau: direkte Vermessung der Gitterlinien):
- Der Grobrahmen (aus einem beliebigen vorhandenen Lokalisator) liefert nur eine Näherung, den Endwert bestimmt die Feinkalibrierung aus der gemessenen Geometrie;
- waagerecht: einheitliche arithmetische Ausgleichsrechnung x_i = x0 + i*step, ohne Fallunterscheidung zwischen bildbreit und mit Rand;
  die Kammwellenlänge (lambda ~ W/8) wirkt über zwei Durchgänge samt Entfernen einzelner Ausreißerpeaks und verhindert Scheinpeaks aus Figurenumrissen;
  bei höchstens 2px Abweichung von der vollen Breite rastet das Ergebnis auf 0 ein;
- senkrecht: Hauptanker sind die Innenkanten der beiden bildbreiten Rechtecke, abgesichert durch ein doppeltes Gatter (Höhe ~ Breite mit höchstens 4px Abweichung und deutlicher std-Einbruch auf beiden Seiten),
  sonst greift der Rückfallpfad über die Ausgleichsrechnung der inneren waagerechten Trennlinien;
- Ausgabe ist RefineResult(rect, confidence, residual); bei einem Residuum über 2.5px oder zu wenigen Linien bleibt der Grobrahmen mit herabgestufter Confidence stehen.
"""
import numpy as np
import cv2

RELATIVE_RESIDUAL_GATE_RATIO = 0.05  # Gatter für das Residuum: 5 % der Feldbreite (skaliert mit der Auflösung)
RELATIVE_SQUARE_GATE_RATIO = 0.015    # Gatter der Quadratbedingung: 1.5 % der Brettgröße (mindestens 4.0px)
RELATIVE_SNAP_RATIO = 0.005           # Gatter der Vollbreiten-Einrastung: 0.5 % der Bildschirmbreite (mindestens 2.0px)
MIN_LINES = 5                         # Mindestzahl innerer Trennlinien für die Ausgleichsrechnung (von insgesamt 7)
OUTLIER_FRAC = 0.25                   # Peaks, die mehr als 0.25*step vom Gitter abweichen, gelten als Figurenumriss
WAVE_GATE = 0.015                     # Waagerecht: maximale relative Abweichung zwischen ausgeglichener und Resonanzwellenlänge (verhindert das Einrasten auf einer falschen Wellenlänge)
WAVE_GATE_V = 0.025                   # Senkrechter Rückfallpfad: dasselbe Gatter, wegen der geringeren Linienzahl weiter gefasst
BAR_STD_GATE = 16.0                   # Kriterium für ein gleichmäßiges Band an der Rechteckkante: mittlere Zeilen-std der 4 Zeilen auf der Bandseite
BAR_GRAD_MIN = 3.0                    # Mindesthöhe des Gradienten im Zeilenmittelprofil (bildbreite starke Kante)


def load_image(path):
    """Bild sicher einlesen, auch bei Pfaden mit Sonderzeichen (cv2.imread liefert dort None)."""
    data = np.fromfile(path, dtype=np.uint8)
    img = cv2.imdecode(data, cv2.IMREAD_COLOR)
    if img is None:
        raise FileNotFoundError(f'Bild konnte nicht gelesen werden: {path}')
    return img


def coarse_candidates(image, top_n=3):
    """Gelockerter Grob-Lokalisator, Zwilling zum umgebauten ChessLocator aus Stufe 2.

    Aufgebaut wie fast_sat_locate_board, jedoch ohne die zwei Einschränkungen, die den wahren Wert aus dem Suchraum drängten:
    1. max_size steigt von 0.98*s_w auf s_w (ein bildbreites Brett ist nicht mehr ausgeschlossen);
    2. die erzwungene waagerechte Zentrierung entfällt, x wird im gesamten zulässigen Bereich durchsucht (schmaler Rand und volle Breite gleichberechtigt).
    Rückgabe sind die besten n Kandidaten, nach Punktzahl absteigend und ausreichend entdoppelt: [((x0, y0, size), score), ...]
    (in Pixelkoordinaten des Originalbildes).
    """
    img_h, img_w = image.shape[:2]
    gray = cv2.cvtColor(image, cv2.COLOR_BGR2GRAY)
    scale = 400.0 / img_w
    s_w = 400
    s_h = int(img_h * scale)
    s_gray = cv2.resize(gray, (s_w, s_h)).astype(np.float32)
    mag = np.abs(cv2.Sobel(s_gray, cv2.CV_32F, 1, 0, ksize=3)) + \
          np.abs(cv2.Sobel(s_gray, cv2.CV_32F, 0, 1, ksize=3))
    sat_gray = cv2.integral(s_gray)
    sat_mag = cv2.integral(mag)
    rr, cc = np.indices((8, 8))
    pattern = np.where((rr + cc) % 2 == 0, 1.0, -1.0).astype(np.float32)

    def rect_sum(sat, x1, y1, x2, y2):
        x1, y1 = max(0, int(x1)), max(0, int(y1))
        x2, y2 = min(s_w, int(x2)), min(s_h, int(y2))
        if x2 <= x1 or y2 <= y1:
            return 0.0
        return sat[y2, x2] - sat[y1, x2] - sat[y2, x1] + sat[y1, x1]

    def rect_mean(sat, x1, y1, x2, y2):
        w = max(1, int(x2) - int(x1))
        h = max(1, int(y2) - int(y1))
        return rect_sum(sat, x1, y1, x2, y2) / (w * h)

    def score_box(x, y, size):
        step = size / 8.0
        edge = 0.0
        for i in range(1, 8):
            ly = int(y + i * step)
            lx = int(x + i * step)
            edge += rect_mean(sat_mag, x, ly - 1, x + size, ly + 2)
            edge += rect_mean(sat_mag, lx - 1, y, lx + 2, y + size)
        gm = np.zeros((8, 8), dtype=np.float32)
        cw = max(1, int(step * 0.18))
        for r in range(8):
            cy1 = y + r * step
            cy2 = cy1 + step
            for c in range(8):
                cx1 = x + c * step
                cx2 = cx1 + step
                gm[r, c] = 0.25 * (rect_mean(sat_gray, cx1, cy1, cx1 + cw, cy1 + cw) +
                                   rect_mean(sat_gray, cx2 - cw, cy1, cx2, cy1 + cw) +
                                   rect_mean(sat_gray, cx1, cy2 - cw, cx1 + cw, cy2) +
                                   rect_mean(sat_gray, cx2 - cw, cy2 - cw, cx2, cy2))
        corr = abs(np.sum((gm - gm.mean()) * pattern))
        bottom_ratio = (y + size) / s_h
        prior = 1.0 if 0.60 <= bottom_ratio <= 0.99 else 0.35
        return (corr * 2.0 + edge * 0.4) * prior

    min_size = int(0.60 * s_w)  # deckt Querformat und zugeschnittene Bilder ab (das Brett kann nur 60 % der Breite einnehmen)
    max_size = s_w
    # Vektorisierte Grobsuche: die frühere Fassung rief score_box in drei verschachtelten Schleifen je Rahmen auf
    # (rund 70.000 reine Python-Aufrufe je Frame, 99 % der Laufzeit). Die Integralbilder liegen bereits vor,
    # damit lassen sich Kantenenergie und die Mittelwerte der 8x8-Eckpunkte je Größe für alle (x, y) auf einmal berechnen,
    # inhaltlich gleichbedeutend mit der Fassung je Rahmen:
    y_min = max(0, int(s_h * 0.15))
    found = []  # [(score, x, y, size)]

    def push(score, x, y, size):
        for i, (s0, x0, y0, sz0) in enumerate(found):
            if abs(x - x0) < 0.1 * s_w and abs(y - y0) < 0.1 * s_w and abs(size - sz0) < 0.08 * s_w:
                if score > s0:
                    found[i] = (score, x, y, size)
                return
        found.append((score, x, y, size))
        if len(found) > 30:
            found.sort(reverse=True, key=lambda t: t[0])
            del found[20:]

    def coarse_scan_size(size: int) -> None:
        # Streng wie score_box je Rahmen: die Linienposition ist int(y + i*step) (mit dem Fenster y abgeschnitten,
        # der Versatz unterscheidet sich je Zeile, es darf nicht vorab gerundet werden), die obere Grenze schließt s_h-size aus
        step = size / 8.0
        n_y = max(0, s_h - size - y_min)  # y liegt in [y_min, s_h-size)
        n_x = s_w - size + 1
        if n_y <= 0:
            return
        yidx = np.arange(n_y) + y_min
        xidx = np.arange(n_x)
        # Zweidimensionale Indizierung mit reinen Integer-Arrays sat[row_idx[:, None], col_idx] ergibt stabil (n_y, n_x)
        # und vermeidet die Falle, dass sich bei gemischten Indizes (Array und Slice) die Achsenreihenfolge verschiebt
        col_x = xidx[None, :]           # (1, n_x): Spaltenkoordinate x im Integralbild
        col_xw = col_x + size           # x + size
        row_y = yidx[:, None]           # (n_y, 1): Zeilenkoordinate y im Integralbild
        row_yw = row_y + size           # y + size
        edge = np.zeros((n_y, n_x), dtype=np.float64)
        for i in range(1, 8):
            # ly = int(y + i*step) je y abgeschnitten: das Band ist 3 hoch und size breit, der Mittelwert hängt nur von (x,y) ab
            ly = (yidx + i * step).astype(int)[:, None]
            edge += ((sat_mag[ly + 2, col_xw] - sat_mag[ly - 1, col_xw]
                      - sat_mag[ly + 2, col_x] + sat_mag[ly - 1, col_x])
                     / (3.0 * size))
            # lx = int(x + i*step) je x abgeschnitten: das Band ist 3 breit und size hoch;
            # rechts kann x+lx+2 über den Rand laufen, deshalb wird die Breite je x wie in der Vorlage über min(s_w, x2) beschnitten
            lx = (xidx + i * step).astype(int)[None, :]
            lx2 = np.minimum(s_w, lx + 2)  # Spaltenindex im Integralbild begrenzen
            cw_band = lx2 - (lx - 1)
            edge += ((sat_mag[row_yw, lx2] - sat_mag[row_y, lx2]
                      - sat_mag[row_yw, lx - 1] + sat_mag[row_y, lx - 1])
                     / (cw_band * size))
        cw = max(1, int(step * 0.18))
        inv_cw2 = 1.0 / (cw * cw)
        gm = np.zeros((n_y, n_x, 64), dtype=np.float64)
        # Vier-Ecken-Formel des Integralbildes: Mittelwert = (S[y+cw,x+cw]-S[y,x+cw]-S[y+cw,x]+S[y,x])/cw^2;
        # die Eckkoordinate cy1 = int(y + r*step) wird je y abgeschnitten, indiziert wird über das Array der Zeilenversätze
        for r in range(8):
            cy1 = (yidx + r * step).astype(int)[:, None]
            cy2b = (yidx + (r + 1) * step).astype(int)[:, None] - cw
            for c in range(8):
                cx1 = int(c * step)   # x ist ganzzahlig, daher gilt int(x + c*step) = x + int(c*step)
                cx2r = int((c + 1) * step) - cw
                # Die vier Eckbereiche: (cy1,cx1) (cy1,cx2r) (cy2b,cx1) (cy2b,cx2r);
                # ro hat die Form (n_y,1), col_x+Skalar die Form (1,n_x), das Broadcasting ergibt (n_y,n_x)
                for ro, coloff in ((cy1, cx1), (cy1, cx2r), (cy2b, cx1),
                                   (cy2b, cx2r)):
                    gm[:, :, r * 8 + c] += (
                        sat_gray[ro + cw, col_x + coloff + cw]
                        - sat_gray[ro, col_x + coloff + cw]
                        - sat_gray[ro + cw, col_x + coloff]
                        + sat_gray[ro, col_x + coloff]) * inv_cw2
        gm *= 0.25
        corr = np.abs(np.einsum('xyk,k->xy', gm - gm.mean(axis=2, keepdims=True),
                                pattern.ravel()))
        bottom = (yidx + size) / s_h
        prior = np.where((bottom >= 0.60) & (bottom <= 0.99), 1.0, 0.35)[:, None]
        sc = (corr * 2.0 + edge * 0.4) * prior
        for row in range(n_y):
            x = int(np.argmax(sc[row]))
            push(float(sc[row, x]), x, y_min + row, size)
    
    # Stufe 1: Grobsuche mit Schrittweite 8 für size (x und y zählt die Vektorisierung vollständig auf, step=4 ist nicht mehr nötig)
    for size in range(min_size, max_size + 1, 8):
        coarse_scan_size(size)
    found.sort(reverse=True, key=lambda t: t[0])
    found = found[:6]

    # Stufe 2: Feinsuche je Kandidat mit +-5 Pixeln und step=1 (deckt die Lücken der Schrittweite 8 ab)
    refined = []
    for _, bx, by, bsz in found:
        best = (score_box(bx, by, bsz), bx, by, bsz)
        for size in range(max(min_size, bsz - 5), min(max_size, bsz + 5) + 1):
            for x in range(max(0, bx - 4), min(s_w - size, bx + 4) + 1):
                for y in range(max(0, by - 4), min(s_h - size, by + 4) + 1):
                    sc = score_box(x, y, size)
                    if sc > best[0]:
                        best = (sc, x, y, size)
        refined.append(best)

    inv = 1.0 / scale
    out = []
    for sc, x, y, size in sorted(refined, reverse=True, key=lambda t: t[0]):
        rx = max(0, min(img_w - int(round(size * inv)), int(round(x * inv))))
        ry = max(0, min(img_h - int(round(size * inv)), int(round(y * inv))))
        rs = int(round(size * inv))
        out.append(((rx, ry, rs), float(sc)))
        if len(out) >= top_n:
            break
    return out


def _detect_peaks(prof, min_sep, thr_floor=1.5, pct=75.0):
    """Peaksuche auf einem 1D-Profil: lokales Maximum über der Schwelle, bei Abständen unter min_sep bleibt der stärkste Peak."""
    g = np.abs(np.diff(prof.astype(np.float64)))
    g = np.convolve(g, np.ones(3) / 3.0, mode='same')
    thr = max(thr_floor, np.percentile(g, pct))
    cand = []
    for i in range(1, len(g) - 1):
        if g[i] >= thr and g[i] >= g[i - 1] and g[i] > g[i + 1]:
            cand.append((g[i], i))
    cand.sort(reverse=True)
    keep = []
    for amp, i in cand:
        if all(abs(i - k) >= min_sep for k in keep):
            keep.append(i)
    return sorted(keep)


def _cluster_lines(positions, tol):
    """Peaks über Zeilen- und Spaltenbänder clustern: Peaks mit Abstand unter tol werden zum Median zusammengefasst, Rückgabe (Position, Stimmen)."""
    if not positions:
        return []
    ps = sorted(positions)
    groups = [[ps[0]]]
    for p in ps[1:]:
        if p - groups[-1][-1] < tol:
            groups[-1].append(p)
        else:
            groups.append([p])
    return [(float(np.median(g)), len(g)) for g in groups]


def _fit_arithmetic(indexed):
    """Kleinste Quadrate für (i, p_i) mit p = p0 + i*step, Rückgabe (p0, step, mittleres Residuum)."""
    idx = np.array([t[0] for t in indexed], dtype=np.float64)
    pos = np.array([t[1] for t in indexed], dtype=np.float64)
    step, p0 = np.polyfit(idx, pos, 1)
    resid = float(np.mean(np.abs(pos - (p0 + idx * step))))
    return float(p0), float(step), resid


def _two_pass_fit(lines, x0_est, step_est):
    """Mehrfache arithmetische Ausgleichsrechnung: Indizes grob zuordnen -> ausgleichen -> neu zuordnen und Ausreißer entfernen -> erneut ausgleichen -> größtes Residuum entfernen.

    lines: [(position, votes)]. Rückgabe: dict(p0, step, residual, n_lines, ok, ok_soft).
    Der erste Durchgang ordnet die Indizes anhand der Kammwellenlänge des Grobrahmens zu; der zweite entfernt gemessen am Ergebnis
    alle Peaks mit einer Abweichung über OUTLIER_FRAC*step (Scheinpeaks aus Figurenumrissen); liegt das Residuum danach immer noch über dem Gatter,
    entfernt der dritte Durchgang den Punkt mit dem größten Residuum (es müssen mindestens MIN_LINES Linien bleiben). ok_soft steht für ein Residuum in (GATE, 4.0]
    bei höchstens 1 % Abweichung der Periode und dient dem senkrechten Rückfallpfad als abgeschwächtes Ergebnis.
    """
    def assign(p0, step):
        out = []
        for p, _v in lines:
            i = int(round((p - p0) / step))
            if 1 <= i <= 7 and abs(p - (p0 + i * step)) <= OUTLIER_FRAC * step + 2.0:
                out.append((i, p))
        return out

    cand = assign(x0_est, step_est)
    if len(cand) < MIN_LINES:
        return {'p0': None, 'step': None, 'residual': None, 'n_lines': len(cand),
                'ok': False, 'ok_soft': False}
    p0, step, _ = _fit_arithmetic(cand)
    cand2 = assign(p0, step)
    if len(cand2) < MIN_LINES:
        return {'p0': None, 'step': None, 'residual': None, 'n_lines': len(cand2),
                'ok': False, 'ok_soft': False}
    p0, step, resid = _fit_arithmetic(cand2)
    # Dritter Durchgang: liegt das Residuum über dem Gatter, wird der Punkt mit dem größten Residuum entfernt (eine einzelne Störlinie), sofern mindestens MIN_LINES übrig bleiben
    gate = step_est * RELATIVE_RESIDUAL_GATE_RATIO
    if resid > gate and len(cand2) > MIN_LINES:
        worst = max(cand2, key=lambda t: abs(t[1] - (p0 + t[0] * step)))
        cand3 = [t for t in cand2 if t != worst]
        p0b, stepb, residb = _fit_arithmetic(cand3)
        if residb < resid:
            p0, step, resid, cand2 = p0b, stepb, residb, cand3
    ok = resid <= gate
    ok_soft = (not ok) and resid <= step_est * 0.08 and abs(step - step_est) <= 0.01 * step_est
    return {'p0': p0, 'step': step, 'residual': resid, 'n_lines': len(cand2),
            'ok': ok, 'ok_soft': ok_soft}


def refine_horizontal(gray, box):
    """Waagerechte Kalibrierung über die Kammresonanz: Größensuche zur Bestimmung der Feldbreite -> Phasenfeinsuche -> Ausgleichsrechnung -> Vollbreiten-Einrastung.

    Die erste Ebene schneidet das Brett je Kandidat (size, phase) in 8 Zeilenbänder, summiert je Band die senkrechte Kantenenergie der 7 inneren Linien
    und nimmt den Median über die Bänder: echte Linien laufen durch alle 8 Bänder und sind überall stark, Scheinpeaks von Figuren treten nur in einzelnen Bändern auf
    und werden vom Median unterdrückt. Die zweite Ebene arbeitet nahe der besten Resonanz mit dem Medianprofil, Peaksuche und zwei Durchgängen der Ausgleichsrechnung
    und erreicht so Genauigkeit unterhalb eines Pixels.
    """
    x0c, y0c, sizec = [int(v) for v in box]
    H, W = gray.shape[:2]
    gx = np.abs(cv2.Sobel(gray, cv2.CV_32F, 1, 0, ksize=3))
    # Das Fenster der Zeilenbänder bleibt eng (+-0.05*size): bei kleinen Bildern ist die Feldbreite gering, ein weites Fenster zöge
    # Oberflächenelemente (Profilbild, Schaltflächen) in die mittleren 6 Bänder und verfälschte den Median;
    # den senkrechten Versatz des Grobrahmens fängt die gestufte Phasensuche ab
    y2 = int(max(y1 + 8, min(H, y0c + sizec + 0.05 * sizec)))
    # Von den 8 Zeilenbändern werden die mittleren 6 verwendet (das erste und letzte Band liegen meist auf der Bauernreihe und enthalten viele Scheinpeaks):
    # echte Linien laufen durch alle 6 Bänder, Scheinpeaks verfälschen höchstens 1 bis 2 Bänder, und der Median über eine gerade Anzahl mittelt die beiden mittleren Werte
    bands = np.array_split(np.arange(y1, y2), 8)[1:7]
    band_prof = np.stack([gx[ys, :].astype(np.float64).mean(axis=0) for ys in bands])

    def line_energy(xi):
        """Median der Kantenenergie über die Bänder an der vorhergesagten Spalte xi (innerhalb von +-1 das Maximum, dann der Median über die Bänder)."""
        if xi < 1 or xi >= W - 1:
            return 0.0
        win = band_prof[:, xi - 1:xi + 2].max(axis=1)
        return float(np.median(win))

    def comb_score(size, x0):
        sc = sum(line_energy(int(round(x0 + i * size / 8.0))) for i in range(1, 8))
        # Vorwissen zur Wellenlänge: die Resonanzgröße folgt einem Gauß-Prior um die Größe des Grobrahmens (sigma=6 %),
        # damit dicht stehende Scheinpeaks weit entfernt keine stärkere Scheinresonanz bilden (gemessen lag eine Scheinresonanz
        # 12 % über der echten, wurde aber 7.8 sigma außerhalb des Priors unterdrückt); der Faktor ist stetig, ohne Fallunterscheidung
        sc *= np.exp(-0.5 * ((size - sizec) / (0.06 * sizec)) ** 2)
        return sc

    # Erste Ebene: Größensuche (Kammwellenlänge lambda ~ W/8), die Phase wird pixelweise durchlaufen
    # Phasenfenster +-0.25*size: fängt den x-Versatz der Groblokalisierung ab (bei zugeschnittenen Querformatbildern über ein halbes Feld),
    # das enge Fenster der Zeilenbänder verhindert dabei Störungen von außerhalb
    s_lo = max(8.0, sizec * 0.88)
    s_hi = min(float(W), sizec * 1.14)
    best = (-1.0, None, None)
    for size in np.arange(s_lo, s_hi + 0.25, 0.25):
        xc_lo = max(-size * 0.05, x0c - 0.25 * sizec)
        xc_hi = min(W - size + size * 0.05, x0c + 0.25 * sizec)
        for x0 in np.arange(xc_lo, xc_hi + 0.9, 1.0):
            sc = comb_score(size, x0)
            if sc > best[0]:
                best = (sc, float(size), float(x0))
    sc_best, size_r, x0_r = best
    if size_r is None:
        return {'ok': False, 'x0': x0c, 'size': sizec, 'size_comb': float(sizec),
                'detail': {'p0': None, 'step': None, 'residual': None, 'n_lines': 0, 'ok': False}}

    # Zweite Ebene: Peaksuche nahe der besten Resonanzphase (+-2 Spalten, Medianprofil), zwei Durchgänge Ausgleichsrechnung für Subpixelgenauigkeit
    med_prof = np.median(band_prof, axis=0)
    raw = []
    for i in range(1, 8):
        xc = int(round(x0_r + i * size_r / 8.0))
        lo, hi = max(1, xc - 2), min(W - 2, xc + 2)
        seg = med_prof[lo:hi + 1]
        raw.append(lo + int(np.argmax(seg)))
    lines = [(float(p), 1) for p in raw]
    fit = _two_pass_fit(lines, x0_r, size_r / 8.0)
    # Gatter auf die Wellenlänge: das ausgeglichene step muss nahe an der Resonanzwellenlänge liegen; dicht stehende Scheinpeaks liefern zwar
    # ein kleines Residuum, aber eine abweichende Wellenlänge (gemessen: normale Frames höchstens 0.95 %, eingerastete Scheinpeaks 1.96 %) und gelten als gescheitert
    if fit['ok'] and abs(fit['step'] - size_r / 8.0) > WAVE_GATE * size_r / 8.0:
        fit = {'p0': x0_r, 'step': size_r / 8.0, 'residual': None,
               'n_lines': fit['n_lines'], 'ok': False}
    if fit['ok']:
        x0, size = fit['p0'], 8.0 * fit['step']
    else:
        x0, size = x0_r, size_r
        if fit.get('residual') is None:
            fit = {'p0': x0_r, 'step': size_r / 8.0, 'residual': None,
                   'n_lines': fit['n_lines'], 'ok': False}
    # Vollbreiten-Einrastung: weicht das Ergebnis um höchstens snap_px von der vollen Breite ab, wird auf 0 gesetzt (volle Breite ist der natürliche Sonderfall der Ausgleichsrechnung)
    snap_px = max(2.0, W * RELATIVE_SNAP_RATIO)
    if abs(x0) <= snap_px and abs(size - W) <= snap_px:
        x0, size = 0.0, float(W)
    # size_comb: die beste Resonanzgröße (Ersatzbeleg, falls die Ausgleichsrechnung scheitert), wird vom senkrechten Rückfallpfad genutzt
    return {'ok': True, 'x0': x0, 'size': size, 'size_comb': float(size_r),
            'detail': fit, 'comb_score': sc_best}


def _outer_edge_score(gray, x0, size, y_fit):
    """Phasenentscheidung im Rückfallpfad: waagerechte Kantenenergie an der oberen und unteren Außenkante (bei der richtigen Lösung sind beide stark).

    Bei zweideutigen Lösungen (zwei um ein Feld versetzte Phasen mit gleich kleinem Residuum) ist das das einzige verlässliche Kriterium: bei der richtigen Lösung liegt
    sowohl bei y0 als auch bei y0+size eine durchgehende starke waagerechte Kante, bei der falschen fällt mindestens eine Seite ins Brettinnere oder in eine Lücke der Oberfläche.
    Rückgabe: (top_edge_energy, bottom_edge_energy, y_top_edge, y_bottom_edge).
    """
    H, W = gray.shape[:2]
    gy = np.abs(cv2.Sobel(gray, cv2.CV_32F, 0, 1, ksize=3)).astype(np.float64)
    xa = int(max(0, min(W, x0)))
    xb = int(max(xa + 4, min(W, x0 + size)))
    rowg = gy[:, xa:xb].mean(axis=1)

    def local(y):
        yc = int(round(y))
        lo, hi = max(0, yc - 5), min(H, yc + 6)
        if lo >= hi:
            return 0.0, yc
        seg = rowg[lo:hi]
        k = int(np.argmax(seg))
        return float(seg[k]), lo + k

    te, yt = local(y_fit)
    be, yb = local(y_fit + size)
    return te, be, yt, yb


def _vertical_bar_anchors(gray, box, expected_size, x_extent=None):
    """Hauptanker der beiden Rechtecke: starke Kante im Zeilenmittelprofil, niedrige std auf der Bandseite, Auswahl über die Quadratbedingung.

    Rückgabe (top_edge, bottom_edge, dev) oder None. Die Unterkante des oberen Rechtecks ist die Oberkante des Bretts,
    die Oberkante des unteren dessen Unterkante; beide werden getrennt gesucht (sie sind unterschiedlich hoch und unterschiedlich sauber).
    x_extent=(x0, size): Profil und Gradient bleiben im gemessenen waagerechten Bereich (mit 2 % Zugabe),
    damit in Querformat- oder Ausschnittbildern keine bildbreite Kante der Oberfläche den Anker vortäuscht.
    """
    x0c, y0c, sizec = box
    H, W = gray.shape[:2]
    if x_extent is not None:
        xa = int(max(0, min(W, x_extent[0] - 0.02 * W)))
        xb = int(max(xa + 4, min(W, x_extent[0] + x_extent[1] + 0.02 * W)))
        sub = gray[:, xa:xb]
    else:
        sub = gray
    span = int(0.18 * W)
    t_lo, t_hi = max(0, y0c - span), min(H - 1, y0c + int(0.05 * W))
    b_lo, b_hi = max(0, y0c + sizec - int(0.05 * W)), min(H - 2, y0c + sizec + span)
    prof = sub.astype(np.float64).mean(axis=1)
    g = np.abs(np.diff(prof))
    row_std = sub.astype(np.float64).std(axis=1)

    def cands(lo, hi, side):
        out = []
        for y in range(lo, hi):
            if g[y] < BAR_GRAD_MIN:
                continue
            if not (g[y] >= g[y - 1] and g[y] >= g[min(len(g) - 1, y + 1)]):
                continue
            # Das Kriterium des gleichmäßigen Bandes gilt der Außenseite: über der Oberkante liegt ein helles Band bzw. der untere Rand der Leiste geschlagener Figuren,
            # unter der Unterkante der dunkle Balken; da innen ebenfalls ein schmaler Rahmen des Bretts liegen kann, werden beide Seiten geprüft
            if side == 'top':
                bands = [row_std[max(0, y - 4):y], row_std[y + 1:y + 5]]
            else:
                bands = [row_std[max(0, y - 3):y + 1], row_std[y + 1:y + 5]]
            if any(b.size >= 3 and b.mean() < BAR_STD_GATE for b in bands):
                out.append(y)
        return out

    tops = cands(t_lo, t_hi, 'top')
    bots = cands(b_lo, b_hi, 'bottom')
    best = None
    square_gate = max(4.0, expected_size * RELATIVE_SQUARE_GATE_RATIO)
    for t in tops:
        for b in bots:
            dev = abs((b - t) - expected_size)
            if dev <= square_gate and (best is None or dev < best[2]):
                best = (t, b, dev)
    # Bei mehreren Kandidaten gewinnt das Paar, das der Kante des Grobrahmens am nächsten liegt (der Grobrahmen ist bereits eine Näherung, das verhindert weit entfernte Kanten der Oberfläche als Anker)
    ties = [(t, b, abs((b - t) - expected_size)) for t in tops for b in bots
            if abs((b - t) - expected_size) <= square_gate]
    if ties:
        best = min(ties, key=lambda e: (e[2], abs(e[0] - y0c) + abs(e[1] - (y0c + sizec))))
    return best


def _fit_horizontal_lines(gray, box):
    """Rückfallpfad: arithmetische Ausgleichsrechnung über das Zeilenprofil der inneren Trennlinien (zwei Durchgänge, wie waagerecht)."""
    x0c, y0c, sizec = box
    H, W = gray.shape[:2]
    s_est = sizec / 8.0
    raw = []
    for c in range(8):
        x1 = int(max(0, x0c + (c + 0.22) * s_est))
        x2 = int(min(W, x0c + (c + 0.78) * s_est))
        if x2 - x1 < 4:
            continue
        # Die Spaltenbänder sind schmal (0.56*step), deshalb bleibt selbst ein Fenster von +-0.5*size frei von Figuren- und Oberflächenstörungen
        # und deckt einen y-Versatz des Grobrahmens von über einem halben Feld ab
        y_lo = int(max(0, y0c - 0.5 * sizec))
        y_hi = int(min(H, y0c + sizec + 0.5 * sizec))
        prof = gray[y_lo:y_hi, x1:x2].astype(np.float64).mean(axis=1)
        for p in _detect_peaks(prof, min_sep=0.5 * s_est):
            raw.append(p + y_lo)
    lines = _cluster_lines(raw, tol=0.25 * s_est)
    return _two_pass_fit(lines, y0c, s_est)


def refine_grid(img_bgr, coarse_box):
    """Haupteinstieg der Feinkalibrierung: Grobrahmen -> waagerechte Ausgleichsrechnung und senkrechter Anker (ersatzweise innere Linien) -> RefineResult.

    coarse_box: (x0, y0, size). Rückgabe: dict(rect=(x0,y0,size), confidence, residual, detail).
    confidence: high = beide Achsen durch das Gatter; medium = nur eine Achse feinkalibriert; low = beide Achsen im Rückfall, es bleibt beim Grobrahmen.
    """
    x0c, y0c, sizec = [int(v) for v in coarse_box]
    gray = cv2.cvtColor(img_bgr, cv2.COLOR_BGR2GRAY)

    horiz = refine_horizontal(gray, (x0c, y0c, sizec))
    size = horiz['size'] if horiz['ok'] else sizec
    x0 = horiz['x0'] if horiz['ok'] else x0c
    # Bezugsgröße des senkrechten Rückfallpfades: bei bestandener Ausgleichsrechnung deren Wert, sonst die beste Resonanzgröße
    # (die grobe sizec kann von Scheinpeaks verzogen sein, dann rastet die gestufte Suche auf einer senkrechten Kante der Oberfläche ein)
    size_v = horiz['size'] if horiz['ok'] else horiz['size_comb']

    bars = _vertical_bar_anchors(gray, (x0c, y0c, sizec), expected_size=size,
                                 x_extent=(x0, size))
    if bars is not None:
        # Drittes Gatter: Kreuzprüfung über die inneren waagerechten Linien - mit dem Anker y0 als Phasenbezug werden die 7 inneren Trennlinien ausgeglichen,
        # ein zu großes Residuum entlarvt einen falschen Anker (eine falsche Kante, die zufällig die Quadratbedingung erfüllt) und führt in den Rückfallpfad
        cross = _fit_horizontal_lines(gray, (x0c, bars[0], int(round(size))))
        if cross['ok']:
            y0 = float(bars[0])
            v_path, v_resid = 'bars', bars[2]
        else:
            bars = None
    if bars is None:
        # Gestufte Phasensuche: die y-Koordinate des Grobrahmens kann um mehr als ein Feld danebenliegen (bei zugeschnittenen Querformatbildern gemessen etwa 1.2 Felder),
        # die Indizes beziehen sich auf die jeweils geprüfte y-Lage; probiert wird von nah nach fern mit 0, +-0.5, +-1, +-1.5, +-2 Feldern.
        # Drei Gatter: Residuum, Übereinstimmung der Wellenlänge (verhindert, dass dicht stehende Scheinpeaks mit kleinem Residuum auf einer falschen Wellenlänge einrasten;
        # gemessen liegt die richtige Phase nahe am Vorwissen, falsche Phasen rasten oft auf dem 0.9-fachen ein) und Konsistenz der Außenkanten;
        # es werden alle Stufen durchlaufen und die mit dem kleinsten Residuum genommen (nicht die erste bestandene: eine nahe falsche Lösung kann zuerst bestehen)
        s_est = size_v / 8.0
        chosen = None
        for k in (0, -0.5, 0.5, -1.0, 1.0, -1.5, 1.5, -2.0, 2.0):
            y_try = y0c + k * s_est
            hfit = _fit_horizontal_lines(gray, (x0c, int(round(y_try)), int(round(size_v))))
            if not hfit['ok']:
                continue
            if abs(hfit['step'] - s_est) > WAVE_GATE_V * s_est:
                continue
            y0_fit = hfit['p0']
            te, be, yt, yb = _outer_edge_score(gray, x0, size_v, y0_fit)
            if te >= BAR_GRAD_MIN and be >= BAR_GRAD_MIN:
                if chosen is None or hfit['residual'] < chosen[1]:
                    # Auf die gemessene Kantenposition einrasten (innerhalb von 3px), das gleicht den Phasenfehler des Profils aus
                    chosen = (float(yt), hfit['residual'])
        if chosen is not None:
            y0, v_path, v_resid = chosen[0], 'gridlines', chosen[1]
        else:
            y0, v_path, v_resid = float(y0c), 'coarse', None

    # Zweiter waagerechter Durchgang: im ersten Durchgang richtete sich das Fenster der Zeilenbänder nach dem groben y (möglicherweise mit Oberfläche oder Untertiteln verunreinigt),
    # nach der senkrechten Konvergenz läuft die waagerechte Kalibrierung mit dem feinkalibrierten Rahmen erneut, die Bänder liegen dann genau im Brett
    # und Scheinpeaks von außerhalb fallen weg; übernommen wird das Ergebnis nur, wenn die Ausgleichsrechnung besteht (sonst bleibt der erste Durchgang stehen)
    if v_path != 'coarse':
        horiz2 = refine_horizontal(gray, (int(round(x0)), int(round(y0)), int(round(size))))
        if horiz2['ok']:
            x0, size = horiz2['x0'], horiz2['size']
            horiz = horiz2

    if horiz['ok'] and v_path != 'coarse':
        confidence = 'high' if v_path == 'bars' else 'medium'
    elif horiz['ok'] or v_path != 'coarse':
        confidence = 'medium'
    else:
        confidence = 'low'

    residual = max(horiz['detail']['residual'] or 0.0,
                   v_resid if v_resid is not None else 99.0)
    return {
        'rect': (int(round(x0)), int(round(y0)), int(round(size))),
        'confidence': confidence,
        'residual': residual,
        'detail': {
            'horizontal': horiz,
            'vertical_path': v_path,
            'bars': bars,
            'coarse': (x0c, y0c, sizec),
        },
    }


_CONF_RANK = {'high': 2, 'medium': 1, 'low': 0}


def locate_board(image, top_n=3):
    """Haupteinstieg der gesamten Lokalisierung: gelockerte Grobsuche mit den besten N -> refine je Kandidat -> Auswahl über die Confidence.

    Jeder Kandidat wird einzeln feinkalibriert (ohne gemeinsame Anker), damit im Bereich eines Scheinkandidaten nichts erzwungen einrastet;
    Reihenfolge der Auswahl: Confidence-Stufe, dann kleineres Residuum, dann eine size nahe der Bildschirmbreite.
    """
    H, W = image.shape[:2]
    scored = coarse_candidates(image, top_n=top_n)
    if not scored:
        return None
    cands = [c for c, _s in scored]
    scores = {c: s for c, s in scored}
    results = [refine_grid(image, c) for c in cands]

    snap_px = max(2.0, W * RELATIVE_SNAP_RATIO)
    def _key(r):
        full = 1 if abs(r['rect'][2] - W) <= snap_px else 0
        # Bei fast gleichem Residuum (Zweideutigkeit um ein Feld) entscheidet der Wert der Grobsuche: die enthält die Übereinstimmung mit dem 8x8-Muster,
        # reagiert also auf die absolute Phase und trennt die beiden Lösungen, die die Feinkalibrierung nicht unterscheiden kann
        coarse = r['detail']['coarse']
        return (-_CONF_RANK[r['confidence']], round(r['residual'] * 2) / 2,
                -full, -scores.get(coarse, 0.0))

    results.sort(key=_key)
    best = results[0]
    best['candidates'] = list(zip(cands, results))
    return best
