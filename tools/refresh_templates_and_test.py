import sys
import os
sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

import cv2
import numpy as np
from tools.test_full_pipeline_v2 import fast_sat_locate_board
from tools.extract_refined_templates import extract_features_from_cell

TEMPLATE_SOURCES = [
    # duolingo_1 (White perspective standard board)
    ("duolingo_1.jpeg", 7, 0, "R"),
    ("duolingo_1.jpeg", 7, 1, "N"),
    ("duolingo_1.jpeg", 7, 2, "B"),
    ("duolingo_1.jpeg", 7, 3, "Q"),
    ("duolingo_1.jpeg", 7, 4, "K"),
    ("duolingo_1.jpeg", 7, 5, "B"),
    ("duolingo_1.jpeg", 7, 6, "N"),
    ("duolingo_1.jpeg", 7, 7, "R"),
    ("duolingo_1.jpeg", 6, 0, "P"),
    ("duolingo_1.jpeg", 6, 1, "P"),
    ("duolingo_1.jpeg", 6, 2, "P"),
    ("duolingo_1.jpeg", 6, 3, "P"),
    ("duolingo_1.jpeg", 6, 4, "P"),
    ("duolingo_1.jpeg", 6, 5, "P"),
    ("duolingo_1.jpeg", 6, 6, "P"),
    ("duolingo_1.jpeg", 6, 7, "P"),
    
    # duolingo_2 (Row 0 opponent white pieces, Row 7 player black pieces, Row 1 & 6 pawns)
    ("duolingo_2.jpg", 0, 0, "R"),
    ("duolingo_2.jpg", 0, 1, "N"),
    ("duolingo_2.jpg", 0, 2, "B"),
    ("duolingo_2.jpg", 0, 3, "K"),
    ("duolingo_2.jpg", 0, 4, "Q"),
    ("duolingo_2.jpg", 0, 5, "B"),
    ("duolingo_2.jpg", 0, 6, "N"),
    ("duolingo_2.jpg", 0, 7, "R"),
    ("duolingo_2.jpg", 1, 0, "P"),
    ("duolingo_2.jpg", 1, 1, "P"),
    ("duolingo_2.jpg", 1, 3, "P"),
    ("duolingo_2.jpg", 1, 4, "P"),
    ("duolingo_2.jpg", 1, 5, "P"),
    ("duolingo_2.jpg", 6, 0, "P"),
    ("duolingo_2.jpg", 6, 1, "P"),
    ("duolingo_2.jpg", 6, 2, "P"),
    ("duolingo_2.jpg", 6, 3, "P"),
    ("duolingo_2.jpg", 6, 4, "P"),
    ("duolingo_2.jpg", 6, 5, "P"),
    ("duolingo_2.jpg", 7, 0, "R"),
    ("duolingo_2.jpg", 7, 1, "N"),
    ("duolingo_2.jpg", 7, 2, "B"),
    ("duolingo_2.jpg", 7, 3, "K"),
    ("duolingo_2.jpg", 7, 4, "Q"),
    ("duolingo_2.jpg", 7, 5, "B"),
    ("duolingo_2.jpg", 7, 6, "N"),
    ("duolingo_2.jpg", 7, 7, "R"),
    
    # duolingo_3 (Light clean theme)
    ("duolingo_3.jpg", 7, 0, "R"),
    ("duolingo_3.jpg", 7, 1, "N"),
    ("duolingo_3.jpg", 7, 2, "B"),
    ("duolingo_3.jpg", 7, 3, "Q"),
    ("duolingo_3.jpg", 7, 4, "K"),
    ("duolingo_3.jpg", 7, 5, "B"),
    ("duolingo_3.jpg", 7, 6, "N"),
    ("duolingo_3.jpg", 7, 7, "R"),
    ("duolingo_3.jpg", 6, 0, "P"),
    ("duolingo_3.jpg", 6, 1, "P"),
    ("duolingo_3.jpg", 6, 2, "P"),
    ("duolingo_3.jpg", 6, 3, "P"),
    ("duolingo_3.jpg", 6, 4, "P"),
    ("duolingo_3.jpg", 6, 5, "P"),
    ("duolingo_3.jpg", 6, 6, "P"),
    ("duolingo_3.jpg", 6, 7, "P"),
    ("duolingo_3.jpg", 0, 0, "R"),
    ("duolingo_3.jpg", 0, 1, "N"),
    ("duolingo_3.jpg", 0, 2, "B"),
    ("duolingo_3.jpg", 0, 3, "Q"),
    ("duolingo_3.jpg", 0, 4, "K"),
    ("duolingo_3.jpg", 0, 5, "B"),
    ("duolingo_3.jpg", 0, 6, "N"),
    # in-game tactical / moved piece samples
    ("duolingo_2.jpg", 3, 2, "P"),
    ("duolingo_test_1.jfif", 4, 3, "P"),
    ("duolingo_test_1.jfif", 2, 5, "N"),
    ("bug_3.jpg", 5, 2, "N"),
    ("bug_3.jpg", 3, 6, "B"),
    ("bug_3.jpg", 7, 0, "R"),
    ("bug_3.jpg", 7, 3, "Q"),
    ("bug_3.jpg", 7, 4, "K"),
    ("bug_3.jpg", 7, 5, "B"),
    ("bug_3.jpg", 7, 6, "N"),
    ("bug_3.jpg", 7, 7, "R"),
    ("bug_4.jpg", 5, 2, "N"),
    ("bug_4.jpg", 7, 7, "R"),
]

def build_template_bank():
    templates = {}
    assets_dir = "dulo/app/src/main/assets/templates"
    os.makedirs(assets_dir, exist_ok=True)
    
    # Alte Vorlagen entfernen
    for f in os.listdir(assets_dir):
        if f.endswith(".png"):
            os.remove(os.path.join(assets_dir, f))
            
    # Bereits geladene Bilder und Lokalisierungsergebnisse zwischenspeichern
    img_cache = {}
    
    counter = {}
    for img_path, r, c, cls_name in TEMPLATE_SOURCES:
        if not os.path.exists(img_path): continue
        if img_path not in img_cache:
            img = cv2.imread(img_path)
            l, t, right, b = fast_sat_locate_board(img)
            step = (right - l) / 8.0
            img_cache[img_path] = (img, l, t, right, b, step)
            
        img, l, t, right, b, step = img_cache[img_path]
        
        cx1 = int(l + c * step)
        cy1 = int(t + r * step)
        cx2 = int(l + (c + 1) * step)
        cy2 = int(t + (r + 1) * step)
        crop = img[cy1:cy2, cx1:cx2]
        
        # 48x48-Vorlage nach assets/templates speichern
        idx = counter.get(cls_name, 0)
        counter[cls_name] = idx + 1
        
        tpl_name = f"{cls_name}_{idx}.png"
        tpl_path = os.path.join(assets_dir, tpl_name)
        resized_48 = cv2.resize(crop, (48, 48), interpolation=cv2.INTER_LINEAR)
        cv2.imwrite(tpl_path, resized_48)
        
        feat = extract_features_from_cell(crop)
        if cls_name not in templates:
            templates[cls_name] = []
        templates[cls_name].append(feat)
        
    print(f"Generated {sum(counter.values())} templates in {assets_dir}: {counter}")
    return templates

if __name__ == '__main__':
    build_template_bank()
