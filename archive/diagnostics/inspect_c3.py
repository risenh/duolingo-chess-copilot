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
    templates.append((cls_name, os.path.basename(tf), feat['f_body'], feat['f_head']))

img = cv2.imread("bug_4.jpg")
l, t, r, b = fast_sat_locate_board(img)
step = (r - l) / 8.0

# Row 5 Col 2 (c3 Knight)
c_c3 = img[int(t + 5*step):int(t + 6*step), int(l + 2*step):int(l + 3*step)]
fc3 = extract_features_from_cell(c_c3)

scores_c3 = []
for cls_name, fname, t_body, t_head in templates:
    score = 0.65 * np.sum(fc3['f_body'] * t_body) + 0.35 * np.sum(fc3['f_head'] * t_head)
    scores_c3.append((score, cls_name, fname))
scores_c3.sort(key=lambda x: x[0], reverse=True)

print("Top 5 matches for c3 (Row 5 Col 2):")
for s, c, fn in scores_c3[:5]:
    print(f"  {c:<2} ({fn:<30}): {s:.4f}")
