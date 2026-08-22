import cv2
import numpy as np
import os

def find_board_rect_checkerboard_corr(image):
    """
    Lokalisiert das Brett über die Korrelation mit dem abwechselnden 8x8-Muster (Checkerboard Alternating Correlation)
    """
    img_h, img_w = image.shape[:2]
    gray = cv2.cvtColor(image, cv2.COLOR_BGR2GRAY)
    
    # Das abwechselnde 8x8-Muster aufbauen (+1, -1, +1, -1 ...)
    pattern = np.zeros((8, 8), dtype=np.float32)
    for r in range(8):
        for c in range(8):
            pattern[r, c] = 1.0 if (r + c) % 2 == 0 else -1.0
            
    # Für die schnelle Suche verkleinern
    scale = 300.0 / img_w
    small_w = 300
    small_h = int(img_h * scale)
    small_gray = cv2.resize(gray, (small_w, small_h)).astype(np.float32)
    
    best_score = -1.0
    best_rect = None
    
    # Suchgrößen (im verkleinerten Bild liegt die Brettbreite meist zwischen 250 und 298)
    for size in range(240, min(small_w, 298), 2):
        # x mittig durchsuchen
        center_x = (small_w - size) // 2
        for x in range(max(0, center_x - 10), min(small_w - size + 1, center_x + 11), 2):
            # y liegt zwischen 25 % und 85 % der Bildhöhe
            for y in range(int(small_h * 0.2), int(small_h * 0.9) - size, 3):
                # Mittelwerte der 8x8 Felder bestimmen
                step = size / 8.0
                grid_means = np.zeros((8, 8), dtype=np.float32)
                
                # Damit die Figur in der Mitte nicht stört, werden die 4 Ecken bzw. Randbereiche jedes Feldes gemittelt
                for r in range(8):
                    y1 = int(y + r * step)
                    y2 = int(y + (r + 1) * step)
                    for c in range(8):
                        x1 = int(x + c * step)
                        x2 = int(x + (c + 1) * step)
                        
                        # Die Eckbereiche des Feldes abtasten (dort verdeckt die Figur nichts)
                        cell = small_gray[y1:y2, x1:x2]
                        if cell.size == 0:
                            continue
                        ch, cw = cell.shape
                        # Je Ecke 20 % abtasten
                        corner_vals = [
                            cell[0:max(1, int(ch*0.25)), 0:max(1, int(cw*0.25))],
                            cell[0:max(1, int(ch*0.25)), -max(1, int(cw*0.25)):],
                            cell[-max(1, int(ch*0.25)):, 0:max(1, int(cw*0.25))],
                            cell[-max(1, int(ch*0.25)):, -max(1, int(cw*0.25)):]
                        ]
                        grid_means[r, c] = np.mean([np.mean(cv) for cv in corner_vals])
                        
                # Korrelation mit dem Muster berechnen
                # Den Gesamtmittelwert abziehen
                grid_norm = grid_means - np.mean(grid_means)
                corr = abs(np.sum(grid_norm * pattern))
                
                if corr > best_score:
                    best_score = corr
                    best_rect = (x, y, size)
                    
    x_s, y_s, size_s = best_rect
    inv_scale = 1.0 / scale
    x = int(round(x_s * inv_scale))
    y = int(round(y_s * inv_scale))
    size = int(round(size_s * inv_scale))
    
    # Innerhalb des Bildes bleiben
    x = max(0, min(img_w - size, x))
    y = max(0, min(img_h - size, y))
    
    return (x, y, x + size, y + size)

# Erneut ausführen und darstellen
os.makedirs("scratch/debug_board_corr", exist_ok=True)
for filename in ["duolingo_1.jpeg", "duolingo_2.jpg", "duolingo_3.jpg"]:
    img = cv2.imread(filename)
    l, t, r, b = find_board_rect_checkerboard_corr(img)
    print(f"[{filename}] Corr Board rect: L={l}, T={t}, R={r}, B={b}, Size={r-l}x{b-t}")
    
    vis = img.copy()
    cv2.rectangle(vis, (l, t), (r, b), (0, 255, 0), 4)
    step = (r - l) / 8.0
    for i in range(1, 8):
        cv2.line(vis, (int(l + i * step), t), (int(l + i * step), b), (0, 255, 0), 2)
        cv2.line(vis, (l, int(t + i * step)), (r, int(t + i * step)), (0, 255, 0), 2)
        
    cv2.imwrite(f"scratch/debug_board_corr/{filename}_board.png", vis)
