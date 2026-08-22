import cv2
import numpy as np
import os
from test_locator_v3 import find_bottom_edge_and_board
from robust_classifier import UltraRobustDuolingoClassifier
from engine_evaluator import evaluate_fen_cloud

os.makedirs("scratch/debug_test_set", exist_ok=True)
classifier = UltraRobustDuolingoClassifier()

for filename in ["duolingo_test_1.jfif", "duolingo_test_2.jfif", "duolingo_test_3.jfif"]:
    print(f"\n==================== Testing {filename} ====================")
    img = cv2.imread(filename)
    try:
        l, t, r, b = find_bottom_edge_and_board(img)
        print(f"Board rect: L={l}, T={t}, R={r}, B={b}, Size={r-l}x{b-t}")
        
        # Gitter zeichnen
        vis = img.copy()
        cv2.rectangle(vis, (l, t), (r, b), (0, 255, 0), 2)
        step = (r - l) / 8.0
        for i in range(1, 8):
            cv2.line(vis, (int(l + i * step), t), (int(l + i * step), b), (0, 255, 0), 1)
            cv2.line(vis, (l, int(t + i * step)), (r, int(t + i * step)), (0, 255, 0), 1)
        cv2.imwrite(f"scratch/debug_test_set/{filename}_board.png", vis)
        
        # Felder schneiden und erkennen
        cells_8x8 = []
        for row in range(8):
            row_cells = []
            for col in range(8):
                x1 = int(round(l + col * step))
                y1 = int(round(t + row * step))
                x2 = int(round(l + (col + 1) * step))
                y2 = int(round(t + (row + 1) * step))
                row_cells.append(img[y1:y2, x1:x2])
            cells_8x8.append(row_cells)
            
        raw_board = classifier.classify_board(cells_8x8)
        
        print("Detected Board:")
        for r in raw_board:
            print(" ".join(r))
            
    except Exception as e:
        print(f"Error on {filename}: {e}")
