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

img = cv2.imread("bug_3.jpg")
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
            occupied.append((row, col, f, best_cls))

raw_board = [['.' for _ in range(8)] for _ in range(8)]
means = [item[2]['center_mean'] for item in occupied]
thresh = (min(means) + max(means)) / 2.0
for r_idx, c_idx, f, cls_name in occupied:
    is_white = f['center_mean'] >= thresh
    raw_board[r_idx][c_idx] = cls_name.upper() if is_white else cls_name.lower()

sanitized = sanitize_board_py(raw_board)
board_fen, full_fen = build_fen_py(sanitized, True)
print("Detected FEN on bug_3.jpg with grad_mean >= 22.0:")
print(f"Board FEN: {board_fen}")
print(f"Full FEN:  {full_fen}")
