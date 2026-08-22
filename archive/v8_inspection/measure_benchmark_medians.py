import sys
import os
sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

import cv2
import numpy as np
import glob
from tools.test_full_pipeline_v2 import fast_sat_locate_board
from tools.extract_refined_templates import extract_features_from_cell

template_dir = "dulo/app/src/main/assets/templates"
template_files = glob.glob(os.path.join(template_dir, "*.png"))
templates = []
for tf in template_files:
    cls_name = os.path.basename(tf).split("_")[0].upper()
    img = cv2.imread(tf)
    feat = extract_features_from_cell(img)
    templates.append((cls_name, feat['f_body'], feat['f_head']))

images = [
    ("duolingo_1.jpeg", "Positive (Standard Board)"),
    ("duolingo_2.jpg",  "Positive (In-game Board)"),
    ("duolingo_3.jpg",  "Positive (Light Theme Board)"),
    ("duolingo_test_1.jfif", "Positive (Puzzle Card)"),
    ("bug_3.jpg",       "Positive (In-game Board)"),
    ("bug_4.jpg",       "Positive (In-game Board)"),
    ("bug_7.jpg",       "Positive (In-game Check)"),
    ("bug_1.jpg",       "Negative (Map UI)"),
    ("bug_2.jpg",       "Negative (Lobby UI)"),
    ("bug_5.jpg",       "Negative (Offline Diagnostic UI)")
]

print(f"{'Image':<22} | {'Category':<32} | {'Occ':<3} | {'MedianSim':<9} | {'MeanSim':<8} | {'Gate (>=0.52)'}")
print("=" * 95)

for img_name, desc in images:
    if not os.path.exists(img_name):
        print(f"Missing {img_name}")
        continue
    img = cv2.imread(img_name)
    l, t, r, b = fast_sat_locate_board(img)
    step = (r - l) / 8.0
    
    occupied_sims = []
    for row in range(8):
        for col in range(8):
            cx1 = int(round(l + col * step))
            cy1 = int(round(t + row * step))
            cx2 = int(round(l + (col + 1) * step))
            cy2 = int(round(t + (row + 1) * step))
            cell = img[cy1:cy2, cx1:cx2]
            f = extract_features_from_cell(cell)
            if f['center_std'] >= 6.0 and f['grad_mean'] >= 22.0:
                best_sim = -1.0
                for t_cls, t_body, t_head in templates:
                    b_cos = float(np.sum(f['f_body'] * t_body))
                    h_cos = float(np.sum(f['f_head'] * t_head))
                    score = 0.65 * b_cos + 0.35 * h_cos
                    if score > best_sim:
                        best_sim = score
                occupied_sims.append(best_sim)
                
    sims = sorted(occupied_sims)
    n = len(sims)
    med_sim = sims[n // 2] if n % 2 == 1 else (sims[n // 2 - 1] + sims[n // 2]) / 2.0 if n > 0 else 0.0
    mean_sim = np.mean(sims) if n > 0 else 0.0
    gate_status = "PASS (Board)" if med_sim >= 0.52 and n >= 4 else "BLOCKED (Non-board)"
    
    print(f"{img_name:<22} | {desc:<32} | {n:<3} | {med_sim:<9.3f} | {mean_sim:<8.3f} | {gate_status}")
