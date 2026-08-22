import os
import cv2
import numpy as np
from typing import Optional

PROJECT_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
TEST_IMAGES_DIR = os.path.join(PROJECT_ROOT, "test_images")

SEARCH_DIRS = [
    os.path.join(TEST_IMAGES_DIR, "benchmarks"),
    os.path.join(TEST_IMAGES_DIR, "bugs"),
    os.path.join(TEST_IMAGES_DIR, "calibration"),
    TEST_IMAGES_DIR,
    PROJECT_ROOT
]

def resolve_image_path(image_name_or_path: str) -> str:
    """
    Bildpfad auflösen:
    1. Ist der Pfad bereits gültig (absolut oder relativ), wird er normalisiert zurückgegeben;
    2. bei einem reinen Dateinamen wird in test_images/benchmarks, test_images/bugs und test_images/calibration gesucht;
    3. wird nichts gefunden, kommt der ursprüngliche Wert zurück.
    """
    if os.path.isabs(image_name_or_path) and os.path.exists(image_name_or_path):
        return image_name_or_path
    
    if os.path.exists(image_name_or_path):
        return os.path.abspath(image_name_or_path)
    
    # Zum Suchen nur den Dateinamen verwenden
    base_name = os.path.basename(image_name_or_path)
    for search_dir in SEARCH_DIRS:
        candidate = os.path.join(search_dir, base_name)
        if os.path.exists(candidate):
            return candidate
            
    # Rekursiv unterhalb von test_images suchen
    if os.path.exists(TEST_IMAGES_DIR):
        for root, _, files in os.walk(TEST_IMAGES_DIR):
            if base_name in files:
                return os.path.join(root, base_name)
                
    return image_name_or_path

def load_image(image_name_or_path: str) -> Optional[np.ndarray]:
    """
    Bild sicher einlesen, auch bei Pfaden mit Sonderzeichen unter Windows.
    """
    resolved_path = resolve_image_path(image_name_or_path)
    if not os.path.exists(resolved_path):
        return None
    try:
        data = np.fromfile(resolved_path, dtype=np.uint8)
        img = cv2.imdecode(data, cv2.IMREAD_COLOR)
        return img
    except Exception:
        return cv2.imread(resolved_path)
