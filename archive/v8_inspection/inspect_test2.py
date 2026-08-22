import sys
import os
sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

import cv2
import numpy as np
from tools.test_full_pipeline_v2 import fast_sat_locate_board
from tools.extract_refined_templates import extract_features_from_cell
import glob

# Inspect duolingo_test_2.jfif with current templates
template_dir = "dulo/app/src/main/assets/templates"
template_files = glob.glob(os.path.join(template_dir, "*.png"))
templates = []
for tf in template_files:
    cls_name = os.path.basename(tf).split("_")[0].upper()
    img = cv2.imread(tf)
    feat = extract_features_from_cell(img)
    templates.append((cls_name, feat['f_body'], feat['f_head']))

img = cv2.imread("duolingo_test_2.jfif")
l, t, r, b = fast_sat_locate_board(img)
step = (r - l) / 8.0

print(f"duolingo_test_2.jfif located: [{l}, {t}, {r}, {b}]")
for row in range(8):
    row_str = []
    for col in range(8):
        cx1 = int(round(l + col * step))
        cy1 = int(round(t + row * step))
        cx2 = int(round(l + (col + 1) * step))
        cy2 = int(round(t + (row + 1) * step))
        cell = img[cy1:cy2, cx1:cx2]
        f = extract_features_from_cell(cell)
        if f['center_std'] >= 6.0 and f['grad_mean'] >= 8.0:
            best_cls = 'P'
            best_sim = -1e9
            for t_cls, t_body, t_head in templates:
                body_cos = float(np.sum(f['f_body'] * t_body))
                head_cos = float(np.sum(f['f_head'] * t_head))
                score = 0.65 * body_cos + 0.35 * head_cos
                if score > best_sim:
                    best_sim = score
                    best_cls = t_cls
            row_str.append(best_cls)
        else:
            row_str.append('.')
    print(f"Row {row}: {' '.join(row_str)}")
