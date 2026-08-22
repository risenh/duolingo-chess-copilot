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
    ("duolingo_1.jpeg", True),
    ("duolingo_2.jpg", True),
    ("duolingo_3.jpg", True),
    ("duolingo_test_1.jfif", True),
    ("duolingo_test_2.jfif", True),
    ("bug_3.jpg", True),
    ("bug_4.jpg", True),
    ("bug_1.jpg", False),
    ("bug_2.jpg", False),
]

print(f"{'Image':<22} | {'IsBoard':<7} | {'Occ':<3} | {'MeanSim':<7} | {'MedianSim':<9} | {'EmptyStdMean':<12} | {'2MeansDiff':<10}")
print("-" * 85)

for img_name, is_board in images:
    if not os.path.exists(img_name): continue
    img = cv2.imread(img_name)
    l, t, r, b = fast_sat_locate_board(img)
    step = (r - l) / 8.0
    
    occupied_sims = []
    empty_stds = []
    cell_means = []
    
    for row in range(8):
        for col in range(8):
            cx1 = int(round(l + col * step))
            cy1 = int(round(t + row * step))
            cx2 = int(round(l + (col + 1) * step))
            cy2 = int(round(t + (row + 1) * step))
            cell = img[cy1:cy2, cx1:cx2]
            f = extract_features_from_cell(cell)
            
            if f['center_std'] >= 6.0 and f['grad_mean'] >= 8.0:
                best_sim = -1.0
                for t_cls, t_body, t_head in templates:
                    b_cos = float(np.sum(f['f_body'] * t_body))
                    h_cos = float(np.sum(f['f_head'] * t_head))
                    score = 0.65 * b_cos + 0.35 * h_cos
                    if score > best_sim:
                        best_sim = score
                occupied_sims.append(best_sim)
                cell_means.append(f['center_mean'])
            else:
                empty_stds.append(f['center_std'])
                
    occ = len(occupied_sims)
    mean_sim = np.mean(occupied_sims) if occupied_sims else 0.0
    med_sim = np.median(occupied_sims) if occupied_sims else 0.0
    empty_std_mean = np.mean(empty_stds) if empty_stds else 0.0
    two_means_diff = (max(cell_means) - min(cell_means)) if len(cell_means) >= 2 else 0.0
    
    print(f"{img_name:<22} | {str(is_board):<7} | {occ:<3} | {mean_sim:<7.3f} | {med_sim:<9.3f} | {empty_std_mean:<12.2f} | {two_means_diff:<10.1f}")
