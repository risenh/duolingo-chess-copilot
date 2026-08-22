import sys
import os
sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

import cv2
import numpy as np
import glob
from tools.test_full_pipeline_v2 import fast_sat_locate_board
from tools.extract_refined_templates import extract_features_from_cell
from tools.validate_all_fen import sanitize_board_py, build_fen_py

template_dir = "dulo/app/src/main/assets/templates"
template_files = glob.glob(os.path.join(template_dir, "*.png"))
templates = []
for tf in template_files:
    cls_name = os.path.basename(tf).split("_")[0].upper()
    img = cv2.imread(tf)
    feat = extract_features_from_cell(img)
    templates.append((cls_name, feat['f_body'], feat['f_head']))

for bug_img in ["bug_5.jpg", "bug_6.jpg", "bug_7.jpg"]:
    if not os.path.exists(bug_img):
        print(f"File not found: {bug_img}")
        continue
    img = cv2.imread(bug_img)
    print(f"\n==================== Image {bug_img} (Shape: {img.shape}) ====================")
    l, t, r, b = fast_sat_locate_board(img)
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
                
    sims = sorted([item[4] for item in occupied])
    med_sim = sims[len(sims)//2] if sims else 0.0
    print(f"Board Box: [{l}, {t}, {r}, {b}], size={r-l}x{b-t}, Occupied: {len(occupied)}, MedianSim: {med_sim:.3f}")
    
    raw_board = [['.' for _ in range(8)] for _ in range(8)]
    if occupied:
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
    
    print(f"Perspective: {'White' if is_white_persp else 'Black'}")
    print(f"Detected FEN: {full_fen}")
    print("Raw Board:")
    for row in raw_board:
        print(" ".join(row))
