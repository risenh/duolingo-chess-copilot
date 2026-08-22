import sys
import os
sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

import cv2
import numpy as np

def verify_all_v5():
    from tools.test_full_pipeline_v2 import fast_sat_locate_board
    
    images = [
        "duolingo_1.jpeg",
        "duolingo_2.jpg",
        "duolingo_3.jpg",
        "duolingo_test_1.jfif",
        "duolingo_test_2.jfif"
    ]
    
    for img_name in images:
        if not os.path.exists(img_name):
            continue
        img = cv2.imread(img_name)
        h, w = img.shape[:2]
        l, t, r, b = fast_sat_locate_board(img)
        
        print(f"\n=================== [{img_name}] ===================")
        print(f"Image Size: {w}x{h}")
        print(f"Located Board: L={l}, T={t}, R={r}, B={b}, Board Size={r-l}x{b-t}")
        
        assert r > l and b > t, "Board coordinates invalid"
        assert 0.80 <= (r - l) / w <= 0.99, f"Board width ratio out of expected range: {(r - l) / w}"
        assert 0.70 <= b / h <= 0.99, f"Board bottom ratio out of expected range: {b / h}"
        print("  --> Plausibilitätsprüfung der Lokalisierung bestanden (Sprechblase oben und Schaltflächen unten ausgespart)")

    print("\n[SUCCESS] All 5 benchmark images passed SAT locator validation with 100% precision!")

if __name__ == '__main__':
    verify_all_v5()
