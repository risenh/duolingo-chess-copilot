import cv2
import numpy as np
import os
from test_locator_v3 import find_bottom_edge_and_board

os.makedirs("scratch/all_cells/img1", exist_ok=True)
os.makedirs("scratch/all_cells/img2", exist_ok=True)
os.makedirs("scratch/all_cells/img3", exist_ok=True)

configs = [
    ("duolingo_1.jpeg", "scratch/all_cells/img1"),
    ("duolingo_2.jpg", "scratch/all_cells/img2"),
    ("duolingo_3.jpg", "scratch/all_cells/img3"),
]

for img_name, out_dir in configs:
    img = cv2.imread(img_name)
    l, t, r, b = find_bottom_edge_and_board(img)
    step = (r - l) / 8.0
    for row in range(8):
        for col in range(8):
            x1 = int(round(l + col * step))
            y1 = int(round(t + row * step))
            x2 = int(round(l + (col + 1) * step))
            y2 = int(round(t + (row + 1) * step))
            cell = img[y1:y2, x1:x2]
            cv2.imwrite(f"{out_dir}/r{row}_c{col}.png", cell)

print("Alle Felder wurden nach scratch/all_cells/ exportiert")
