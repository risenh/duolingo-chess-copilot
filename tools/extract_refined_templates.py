import sys
import os
sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

import cv2
import numpy as np

def extract_features_from_cell(cell_img):
    """
    Schwerpunktausrichtung, Schiebefenster-Klemmung und Merkmalsextraktion zweier Regionen (36x36 ROI)
    """
    resized = cv2.resize(cell_img, (48, 48), interpolation=cv2.INTER_LINEAR)
    gray = cv2.cvtColor(resized, cv2.COLOR_BGR2GRAY) if len(resized.shape) == 3 else resized.copy()
    
    # 1. Statistik für das Belegungsgatter (auf Basis der 30x30-Zentrumsregion)
    center_roi = gray[9:39, 9:39].astype(np.float32)
    center_std = float(np.std(center_roi))
    center_mean = float(np.mean(center_roi))
    
    # Sobel-Gradient des gesamten Feldes
    sobelx = cv2.Sobel(gray, cv2.CV_32F, 1, 0, ksize=3)
    sobely = cv2.Sobel(gray, cv2.CV_32F, 0, 1, ksize=3)
    mag_full = np.sqrt(sobelx**2 + sobely**2)
    grad_mean = float(np.mean(mag_full[9:39, 9:39]))
    
    # 2. Vordergrundschwerpunkt per Hintergrunddifferenz aus dem Median der 4 Ecken (3x3 je Ecke, 36 Punkte, gemeinsamer Median)
    # Die Suche bleibt im Innenbereich [2:46, 2:46], damit die 2px-Randlinien und die Kanten der Nachbarfelder den Schwerpunkt nicht verziehen
    corner_pixels = np.concatenate([
        gray[0:3, 0:3].flatten(), gray[0:3, 45:48].flatten(),
        gray[45:48, 0:3].flatten(), gray[45:48, 45:48].flatten()
    ])
    bg_val = float(np.median(corner_pixels))
    diff = np.abs(gray.astype(np.float32) - bg_val)
    mask = np.zeros_like(diff, dtype=bool)
    mask[2:46, 2:46] = diff[2:46, 2:46] > 15.0
    
    if np.any(mask):
        y_idxs, x_idxs = np.where(mask)
        cy = float(np.mean(y_idxs))
        cx = float(np.mean(x_idxs))
    else:
        cy = 24.0
        cx = 24.0
        
    # 3. Schiebefenster-Klemmung des Ursprungs (die ROI bleibt exakt 36x36)
    x0 = int(np.clip(round(cx - 18), 0, 12))
    y0 = int(np.clip(round(cy - 18), 0, 12))
    
    # 4. Körpermerkmal: 30x30 (zentriert aus dem 36x36-Fenster)
    body_mag = mag_full[y0+3:y0+33, x0+3:x0+33].flatten()
    body_norm = np.linalg.norm(body_mag) + 1e-5
    f_body = (body_mag / body_norm).astype(np.float32)
    
    # 5. Kopfmerkmal: 10x30 (die obersten 10 Zeilen des 30x30-Körpers)
    head_mag = mag_full[y0+3:y0+13, x0+3:x0+33].flatten()
    head_norm = np.linalg.norm(head_mag) + 1e-5
    f_head = (head_mag / head_norm).astype(np.float32)
    
    return {
        'f_body': f_body,
        'f_head': f_head,
        'center_std': center_std,
        'center_mean': center_mean,
        'grad_mean': grad_mean,
        'cy': cy,
        'cx': cx,
        'x0': x0,
        'y0': y0
    }

print("extract_features_from_cell pipeline defined successfully!")
