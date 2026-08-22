import sys
import os

PROJECT_ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
if PROJECT_ROOT not in sys.path:
    sys.path.insert(0, PROJECT_ROOT)

import cv2
import numpy as np
import glob
from tests.python.test_full_pipeline_v2 import fast_sat_locate_board
from tools.extract_refined_templates import extract_features_from_cell
from tests.python.validate_all_fen import sanitize_board_py, build_fen_py
from tools.image_utils import resolve_image_path, load_image

# Von Hand feldweise geprüfte Sollwerte echter Aufnahmen vom Gerät
POSITIVE_GROUND_TRUTH_DB = {
    "duolingo_1.jpeg": {
        "perspective": True, # White
        "board_fen": "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR",
        "full_fen": "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"
    },
    "duolingo_2.jpg": {
        "perspective": False, # Black
        "board_fen": "rnbqkbnr/pppppppp/8/8/5P2/8/PPPPP1PP/RNBQKBNR",
        "full_fen": "rnbqkbnr/pppppppp/8/8/5P2/8/PPPPP1PP/RNBQKBNR b KQkq - 0 1"
    },
    "duolingo_3.jpg": {
        "perspective": True, # White
        "board_fen": "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR",
        "full_fen": "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"
    },
    "duolingo_test_1.jfif": {
        "perspective": True, # White
        "board_fen": "rnbqkb1r/pppppppp/5n2/8/3P4/8/PPP1PPPP/RNBQKBNR",
        "full_fen": "rnbqkb1r/pppppppp/5n2/8/3P4/8/PPP1PPPP/RNBQKBNR w KQkq - 0 1"
    },
    "bug_3.jpg": {
        "perspective": True, # White
        "board_fen": "rnbqk2r/1p2bppp/p1pp1n2/3Pp1B1/4P3/2N2P2/PPP3PP/R2QKBNR",
        "full_fen": "rnbqk2r/1p2bppp/p1pp1n2/3Pp1B1/4P3/2N2P2/PPP3PP/R2QKBNR w KQkq - 0 1"
    },
    "bug_4.jpg": {
        "perspective": True, # White
        "board_fen": "rnbqkb1r/1p3ppp/p1pp1n2/3Pp3/4P3/2N2P2/PPP3PP/R1BQKBNR",
        "full_fen": "rnbqkb1r/1p3ppp/p1pp1n2/3Pp3/4P3/2N2P2/PPP3PP/R1BQKBNR w KQkq - 0 1"
    },
    "bug_7.jpg": {
        "perspective": True, # White
        "board_fen": "r1b1k1nr/pppp1ppp/2n5/5P2/3bq3/8/PPP3PP/RNB1KB1R",
        "full_fen": "r1b1k1nr/pppp1ppp/2n5/5P2/3bq3/8/PPP3PP/RNB1KB1R w KQkq - 0 1"
    }
}

# Negativbeispiele ohne Brett, die das Gatter zu 100 % abweisen muss (gemessen MedianSim <= 0.456)
NEGATIVE_SAMPLES = [
    "bug_1.jpg", # Duolingo-Startseite mit dem Lernpfad (MedianSim=0.383)
    "bug_2.jpg", # Liste der Duolingo-Lobby (MedianSim=0.366)
    "bug_5.jpg", # Diagnoseansicht dieser App (MedianSim=0.456)
]

def run_detection_pipeline(img, templates):
    l, t, r, b = fast_sat_locate_board(img)
    if r <= l or b <= t:
        return None
    step = (r - l) / 8.0
    
    occupied = []
    for row in range(8):
        for col in range(8):
            cx1 = max(0, min(img.shape[1], int(round(l + col * step))))
            cy1 = max(0, min(img.shape[0], int(round(t + row * step))))
            cx2 = max(0, min(img.shape[1], int(round(l + (col + 1) * step))))
            cy2 = max(0, min(img.shape[0], int(round(t + (row + 1) * step))))
            if cx2 <= cx1 or cy2 <= cy1:
                continue
            cell = img[cy1:cy2, cx1:cx2]
            if cell.size == 0 or cell.shape[0] < 4 or cell.shape[1] < 4:
                continue
            f = extract_features_from_cell(cell)
            
            if f['center_std'] >= 6.0 and f['grad_mean'] >= 22.0:
                best_cls = 'P'
                best_sim = -1e9
                for t_cls, t_body, t_head in templates:
                    body_cos = float(np.sum(f['f_body'] * t_body))
                    head_cos = float(np.sum(f['f_head'] * t_head))
                    score = 0.65 * body_cos + 0.35 * head_cos
                    if score > best_sim:
                        best_sim = score
                        best_cls = t_cls
                occupied.append((row, col, f, best_cls, best_sim))
                
    # Semantisches Qualitätsgatter (deckungsgleich mit Kotlin)
    if len(occupied) < 4:
        return None
        
    sims = sorted([item[4] for item in occupied])
    n = len(sims)
    median_sim = sims[n // 2] if n % 2 == 1 else (sims[n // 2 - 1] + sims[n // 2]) / 2.0
    
    if median_sim < 0.52:
        return None
        
    # 2-Means
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
    
    for r_idx, c_idx, f, cls_name, _ in occupied:
        is_white = f['center_mean'] >= thresh
        raw_board[r_idx][c_idx] = cls_name.upper() if is_white else cls_name.lower()
        
    sanitized = sanitize_board_py(raw_board)
    top_w = sum(1 for row_p in sanitized[:2] for p in row_p if p.isupper())
    top_b = sum(1 for row_p in sanitized[:2] for p in row_p if p.islower())
    bot_w = sum(1 for row_p in sanitized[6:] for p in row_p if p.isupper())
    bot_b = sum(1 for row_p in sanitized[6:] for p in row_p if p.islower())
    is_white_persp = bot_w >= bot_b
    
    board_fen, full_fen = build_fen_py(sanitized, is_white_persp)
    return board_fen, full_fen, is_white_persp

def run_ground_truth_diff_verification():
    template_dir = os.path.join(PROJECT_ROOT, "dulo/app/src/main/assets/templates")
    template_files = glob.glob(os.path.join(template_dir, "*.png"))
    templates = []
    for tf in template_files:
        cls_name = os.path.basename(tf).split("_")[0].upper()
        img = load_image(tf)
        feat = extract_features_from_cell(img)
        templates.append((cls_name, feat['f_body'], feat['f_head']))
        
    print(f"Loaded {len(templates)} templates from {template_dir}")
    if len(templates) == 0:
        print("[FATAL ERROR] Template bank is EMPTY! Failing gating.")
        sys.exit(1)
        
    all_matched = True
    
    # 1. Negativbeispiele prüfen (müssen zu 100 % abgewiesen werden, Rückgabe None)
    print("\n=================== Abweisung der Negativbeispiele (Negative Gating) ===================")
    for neg_img in NEGATIVE_SAMPLES:
        neg_path = resolve_image_path(neg_img)
        if not os.path.exists(neg_path):
            print(f"[FATAL ERROR] Negative test sample '{neg_img}' missing!")
            all_matched = False
            continue
        img = load_image(neg_path)
        res = run_detection_pipeline(img, templates)
        if res is None:
            print(f"  --> [PASS] {neg_img} (kein Brett) wurde vom Qualitätsgatter abgewiesen (Rückgabe None)")
        else:
            print(f"  --> [FAIL] {neg_img} kam durch. Erkanntes Ergebnis: {res[0]}")
            all_matched = False
            
    # 2. Positivbeispiele prüfen (müssen das Gatter passieren und in allen 64 Feldern zum Sollwert passen)
    print("\n=================== Feldweiser Abgleich der Positivbeispiele ===================")
    verified_count = 0
    for img_name, gt in POSITIVE_GROUND_TRUTH_DB.items():
        pos_path = resolve_image_path(img_name)
        if not os.path.exists(pos_path):
            print(f"[FATAL ERROR] Required benchmark image '{img_name}' is MISSING from disk!")
            all_matched = False
            continue
            
        img = load_image(pos_path)
        res = run_detection_pipeline(img, templates)
        if res is None:
            print(f"[FAIL] Das Positivbeispiel {img_name} wurde vom Qualitätsgatter abgewiesen")
            all_matched = False
            continue
            
        board_fen, full_fen, is_white_persp = res
        expected_fen = gt["board_fen"]
        
        print(f"\n[{img_name}]")
        print(f"  Detected Board FEN: {board_fen}")
        print(f"  Expected Board FEN: {expected_fen}")
        
        if board_fen == expected_fen:
            print("  --> [MATCH] Alle 64 Felder stimmen überein, 0 Abweichungen")
            verified_count += 1
        else:
            print(f"  --> [DIFF MISMATCH] Abweichungen gefunden")
            all_matched = False

    if not all_matched or verified_count != len(POSITIVE_GROUND_TRUTH_DB):
        print(f"\n[FAIL] Gatter und Sollwerte stimmen nicht überein. Bestandene Positivbeispiele: {verified_count}/{len(POSITIVE_GROUND_TRUTH_DB)}")
        sys.exit(1)
    else:
        print(f"\n[SUCCESS] Alle Negativbeispiele wurden abgewiesen und alle {verified_count} Positivbeispiele stimmen in allen 64 Feldern mit den Sollwerten überein")

if __name__ == '__main__':
    run_ground_truth_diff_verification()
