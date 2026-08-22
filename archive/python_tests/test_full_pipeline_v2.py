import cv2
import numpy as np
import os
import sys

PROJECT_ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
if PROJECT_ROOT not in sys.path:
    sys.path.insert(0, PROJECT_ROOT)

from tools.grid_calibrate import locate_board
from tools.image_utils import resolve_image_path, load_image

def fast_sat_locate_board(image, return_score=False, top_n=3):
    """
    Lokalisator mit Gitterlinien-Feinkalibrierung, deckungsgleich zum ChessLocator in Kotlin
    """
    res = locate_board(image, top_n=top_n)
    if res is None:
        return (0, 0, 0, 0) if not return_score else (0, 0, 0, 0, 0.0)
    x0, y0, size = res['rect']
    score = float(res['candidates'][0][0][1]) if 'candidates' in res and res['candidates'] else 0.0
    if return_score:
        return x0, y0, x0 + size, y0 + size, score
    return x0, y0, x0 + size, y0 + size

if __name__ == '__main__':
    images = [
        "duolingo_1.jpeg", "duolingo_2.jpg", "duolingo_3.jpg",
        "duolingo_test_1.jfif", "duolingo_test_2.jfif", "Screenshot_20260817_162715.jpg"
    ]
    for img_name in images:
        resolved = resolve_image_path(img_name)
        if not os.path.exists(resolved):
            continue
        img = load_image(resolved)
        res = locate_board(img, top_n=3)
        if res is not None:
            x0, y0, size = res['rect']
            print(f"[{img_name}] Rect=({x0}, {y0}, {x0+size}, {y0+size}), Size={size}x{size}, Conf={res['confidence']}, Resid={res['residual']:.2f}px")

