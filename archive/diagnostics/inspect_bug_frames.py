import sys
import os
sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

import cv2
import numpy as np
import glob
from tools.test_full_pipeline_v2 import fast_sat_locate_board
from tools.extract_refined_templates import extract_features_from_cell
from tools.validate_all_fen import sanitize_board_py, build_fen_py

# Zellenweise Forensik der fehlerhaften Frames bug_11~15 vom Gerät (nur Diagnose, kein Qualitätsgatter, blockiert keinen Build)
# Zweck: Belege je Feld über die zwei besten Treffer für Fehlklassifikationen (Läufer->Springer, Turm->Läufer) und übersehene Figuren
BUG_FRAMES = ["bug_11.jpg", "bug_12.jpg", "bug_13.jpg", "bug_14.jpg", "bug_15.jpg"]

FILES = "abcdefgh"

def square_name(row, col, is_white_persp):
    """Rechnet ein Bildschirmfeld (row, col) je nach Perspektive in Brettkoordinaten um"""
    if is_white_persp:
        return FILES[col] + str(8 - row)
    else:
        return FILES[7 - col] + str(row + 1)

def inspect_frame(path, templates):
    img = cv2.imread(path)
    if img is None:
        print(f"[ERROR] Bild konnte nicht gelesen werden: {path}")
        return

    print(f"\n=================== {path} ===================")
    try:
        l, t, r, b = fast_sat_locate_board(img)
    except Exception as e:
        print(f"[ERROR] Brettlokalisierung fehlgeschlagen: {e}")
        return
    print(f"Brett lokalisiert: L={l}, T={t}, R={r}, B={b}")
    step = (r - l) / 8.0

    occupied = []
    for row in range(8):
        for col in range(8):
            cx1 = int(round(l + col * step))
            cy1 = int(round(t + row * step))
            cx2 = int(round(l + (col + 1) * step))
            cy2 = int(round(t + (row + 1) * step))
            cell = img[cy1:cy2, cx1:cx2]
            f = extract_features_from_cell(cell)

            if f['center_std'] >= 6.0 and f['grad_mean'] >= 22.0:
                best_cls, best_sim = 'P', -1e9
                second_cls, second_sim = 'P', -1e9
                for t_cls, t_body, t_head in templates:
                    score = 0.65 * float(np.sum(f['f_body'] * t_body)) + 0.35 * float(np.sum(f['f_head'] * t_head))
                    if score > best_sim:
                        if t_cls != best_cls and best_sim > second_sim:
                            second_sim, second_cls = best_sim, best_cls
                        best_sim, best_cls = score, t_cls
                    elif score > second_sim and t_cls != best_cls:
                        second_sim, second_cls = score, t_cls
                occupied.append((row, col, f, best_cls, best_sim, second_cls, second_sim))

    if len(occupied) < 4:
        print(f"[INFO] Weniger als 4 belegte Felder ({len(occupied)}), das Gatter weist ab")
        return

    sims = sorted([item[4] for item in occupied])
    n = len(sims)
    median_sim = sims[n // 2] if n % 2 == 1 else (sims[n // 2 - 1] + sims[n // 2]) / 2.0
    print(f"Belegt: {len(occupied)} | MedianSim: {median_sim:.3f} (Gatter 0.520)")
    if median_sim < 0.52:
        print("[INFO] MedianSim unter dem Gatter, auf dem Gerät würde abgewiesen")

    # 2-Means-Clustering für Schwarz und Weiß (deckungsgleich mit dem Gatterskript und mit Kotlin)
    raw_board = [['.' for _ in range(8)] for _ in range(8)]
    means = [item[2]['center_mean'] for item in occupied]
    min_val, max_val = min(means), max(means)
    if max_val - min_val < 35.0:
        avg = float(np.mean(means))
        thresh = min_val - 1.0 if avg >= 120.0 else max_val + 1.0
    else:
        c1, c2 = min_val, max_val
        for _ in range(10):
            g1 = [m for m in means if abs(m - c1) <= abs(m - c2)]
            g2 = [m for m in means if abs(m - c1) > abs(m - c2)]
            if g1: c1 = float(np.mean(g1))
            if g2: c2 = float(np.mean(g2))
        thresh = (c1 + c2) / 2.0

    for r_idx, c_idx, f, cls_name, _, _, _ in occupied:
        is_white = f['center_mean'] >= thresh
        raw_board[r_idx][c_idx] = cls_name.upper() if is_white else cls_name.lower()

    sanitized = sanitize_board_py(raw_board)
    top_w = sum(1 for row_p in sanitized[:2] for p in row_p if p.isupper())
    top_b = sum(1 for row_p in sanitized[:2] for p in row_p if p.islower())
    bot_w = sum(1 for row_p in sanitized[6:] for p in row_p if p.isupper())
    bot_b = sum(1 for row_p in sanitized[6:] for p in row_p if p.islower())
    is_white_persp = bot_w >= bot_b

    board_fen, full_fen = build_fen_py(sanitized, is_white_persp)
    print(f"Perspektive: {'Weiß' if is_white_persp else 'Schwarz'}")
    print(f"Erkanntes Board-FEN: {board_fen}")
    print(f"Erkanntes volles FEN: {full_fen}")

    # Tabelle der zwei besten Treffer je Feld (geringer Abstand deutet auf Fehlklassifikation)
    print("\nZwei beste Treffer je Feld (Abstand = Top1 - Top2, ein kleiner Abstand ist verdächtig):")
    print(f"  {'Feld':<6}{'Top1':<5}{'Sim1':<9}{'Top2':<5}{'Sim2':<9}{'Abstand':<8}{'Helligkeit'}")
    for r_idx, c_idx, f, best_cls, best_sim, second_cls, second_sim in sorted(occupied):
        color_mark = raw_board[r_idx][c_idx]
        margin = best_sim - second_sim
        flag = " <-- geringer Abstand" if margin < 0.05 else ""
        print(f"  {square_name(r_idx, c_idx, is_white_persp):<6}{best_cls}({color_mark})"
              f"  {best_sim:<9.4f}{second_cls:<5}{second_sim:<9.4f}{margin:<8.4f}{f['center_mean']:.1f}{flag}")

    # Liste der leeren Felder (Beleg für übersehene Figuren: Abgleich mit dem echten Brett)
    empties = []
    occ_set = {(item[0], item[1]) for item in occupied}
    for row in range(8):
        for col in range(8):
            if (row, col) not in occ_set:
                empties.append(square_name(row, col, is_white_persp))
    print(f"\nAls leer erkannte Felder ({len(empties)}): {' '.join(empties)}")

def main():
    template_dir = "dulo/app/src/main/assets/templates"
    template_files = glob.glob(os.path.join(template_dir, "*.png"))
    templates = []
    for tf in template_files:
        cls_name = os.path.basename(tf).split("_")[0].upper()
        img = cv2.imread(tf)
        feat = extract_features_from_cell(img)
        templates.append((cls_name, feat['f_body'], feat['f_head']))
    print(f"Loaded {len(templates)} templates from {template_dir}")
    if len(templates) == 0:
        print("[FATAL ERROR] Template bank is EMPTY!")
        sys.exit(1)

    frames = sys.argv[1:] if len(sys.argv) > 1 else BUG_FRAMES
    for bf in frames:
        if not os.path.exists(bf):
            print(f"[ERROR] Frame fehlt: {bf} (gehört in die Versionsverwaltung)")
            continue
        try:
            inspect_frame(bf, templates)
        except Exception as e:
            print(f"[ERROR] Fehler bei {bf}: {e}")

    print("\n[INFO] Forensik abgeschlossen (nur Diagnose, kein Qualitätsgatter)")

if __name__ == '__main__':
    main()
