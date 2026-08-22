import sys
import os
sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

import cv2
import numpy as np

img = cv2.imread("duolingo_2.jpg")
from tools.test_full_pipeline_v2 import fast_sat_locate_board
l, t, r, b = fast_sat_locate_board(img)
step = (r - l) / 8.0

print("Row 0 piece centers and heights vs Row 7 piece centers and heights:")
for r_idx in [0, 7]:
    for c_idx in range(4):
        cx1 = int(l + c_idx * step)
        cy1 = int(t + r_idx * step)
        cx2 = int(l + (c_idx + 1) * step)
        cy2 = int(t + (r_idx + 1) * step)
        crop = img[cy1:cy2, cx1:cx2]
        gray = cv2.cvtColor(crop, cv2.COLOR_BGR2GRAY)
        
        # Senkrechten Schwerpunkt und die Grenzen der Vordergrundpixel bestimmen
        bg_val = np.median(gray[:5, :5])
        diff = np.abs(gray.astype(float) - bg_val)
        mask = diff > 15
        if np.any(mask):
            y_indices, x_indices = np.where(mask)
            print(f"Row {r_idx} Col {c_idx}: Y range = [{y_indices.min()}, {y_indices.max()}], Y center = {y_indices.mean():.1f}, Cell H = {crop.shape[0]}")
