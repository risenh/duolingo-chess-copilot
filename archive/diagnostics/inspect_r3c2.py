import sys
import os
sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

import cv2
import numpy as np
import glob
from tools.test_full_pipeline_v2 import fast_sat_locate_board
from tools.extract_refined_templates import extract_features_from_cell

img = cv2.imread("duolingo_2.jpg")
l, t, r, b = fast_sat_locate_board(img)
step = (r - l) / 8.0

# Check row 3 col 2
cx1 = int(round(l + 2 * step))
cy1 = int(round(t + 3 * step))
cx2 = int(round(l + 3 * step))
cy2 = int(round(t + 4 * step))
cell = img[cy1:cy2, cx1:cx2]

f = extract_features_from_cell(cell)
print(f"Row 3 Col 2 (pawn): center_std={f['center_std']}, grad_mean={f['grad_mean']}")

template_dir = "dulo/app/src/main/assets/templates"
template_files = glob.glob(os.path.join(template_dir, "*.png"))
templates = []
for tf in template_files:
    cls_name = os.path.basename(tf).split("_")[0].upper()
    t_img = cv2.imread(tf)
    t_feat = extract_features_from_cell(t_img)
    templates.append((cls_name, t_feat['f_body'], t_feat['f_head'], os.path.basename(tf)))

scores = {}
for t_cls, t_body, t_head, t_name in templates:
    b_cos = float(np.sum(f['f_body'] * t_body))
    h_cos = float(np.sum(f['f_head'] * t_head))
    s = 0.65 * b_cos + 0.35 * h_cos
    if s > scores.get(t_cls, -1):
        scores[t_cls] = s

print("Scores for row 3 col 2:", sorted(scores.items(), key=lambda x: x[1], reverse=True))
