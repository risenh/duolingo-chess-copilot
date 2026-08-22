import cv2
import numpy as np
import os

def locate_duolingo_board(image):
    """
    Sehr robuster adaptiver Lokalisator für das 2D-Brett der Duolingo-Schachoberfläche
    """
    img_h, img_w = image.shape[:2]
    gray = cv2.cvtColor(image, cv2.COLOR_BGR2GRAY)
    
    # 1. Die Brettbreite liegt meist zwischen 90 % und 98 % der Bildschirmbreite
    # Das Brett ist waagerecht mittig, die Ränder links und rechts sind gleich
    # Gesucht wird die Brettbreite W in [0.88 * img_w, 0.98 * img_w]
    
    # Sobel-Gradient in waagerechter und senkrechter Richtung berechnen
    grad_x = cv2.Sobel(gray, cv2.CV_32F, 1, 0, ksize=3)
    grad_y = cv2.Sobel(gray, cv2.CV_32F, 0, 1, ksize=3)
    mag = np.abs(grad_x) + np.abs(grad_y)
    
    best_score = -1e9
    best_box = None
    
    # Für die schnelle Gittersuche verkleinern
    scale = 400.0 / img_w
    s_w = 400
    s_h = int(img_h * scale)
    s_gray = cv2.resize(gray, (s_w, s_h)).astype(np.float32)
    s_mag = cv2.resize(mag, (s_w, s_h))
    
    # Die Brettgröße liegt im verkleinerten Bild meist zwischen 350 und 396
    min_size = int(0.85 * s_w)
    max_size = min(s_w, int(0.98 * s_w))
    
    pattern = np.zeros((8, 8), dtype=np.float32)
    for r in range(8):
        for c in range(8):
            pattern[r, c] = 1.0 if (r + c) % 2 == 0 else -1.0
            
    for size in range(min_size, max_size + 1, 2):
        step = size / 8.0
        center_x = (s_w - size) // 2
        # x fein durchsuchen
        for x in range(max(0, center_x - 6), min(s_w - size + 1, center_x + 7), 2):
        # y: das Brett liegt in der unteren Hälfte (etwa 10 bis 15 % Abstand zum unteren Rand)
        # Suchbereich: von (s_h * 0.3) bis (s_h - size - 2)
            y_min = int(s_h * 0.25)
            y_max = s_h - size
            for y in range(y_min, y_max, 2):
                # Berechnung 1: Kantenenergie der 7 inneren Trennlinien
                edge_score = 0.0
                for i in range(1, 8):
                    line_y = int(y + i * step)
                    line_x = int(x + i * step)
                    # Gradient auf den waagerechten Trennlinien abtasten
                    edge_score += np.mean(s_mag[line_y-1:line_y+2, x:x+size])
                    # Gradient auf den senkrechten Trennlinien abtasten
                    edge_score += np.mean(s_mag[y:y+size, line_x-1:line_x+2])
                
                # Berechnung 2: Merkmale des abwechselnden Musters
                grid_means = np.zeros((8, 8), dtype=np.float32)
                for r in range(8):
                    cy1 = int(y + r * step)
                    cy2 = int(y + (r + 1) * step)
                    for c in range(8):
                        cx1 = int(x + c * step)
                        cx2 = int(x + (c + 1) * step)
                        cell = s_gray[cy1:cy2, cx1:cx2]
                        if cell.size == 0:
                            continue
                        ch, cw = cell.shape
                        # Die Ecken ringsum abtasten
                        c_vals = [
                            cell[0:max(1, int(ch*0.2)), 0:max(1, int(cw*0.2))],
                            cell[0:max(1, int(ch*0.2)), -max(1, int(cw*0.2)):],
                            cell[-max(1, int(ch*0.2)):, 0:max(1, int(cw*0.2))],
                            cell[-max(1, int(ch*0.2)):, -max(1, int(cw*0.2)):]
                        ]
                        grid_means[r, c] = np.mean([np.mean(cv) for cv in c_vals])
                
                grid_norm = grid_means - np.mean(grid_means)
                corr = abs(np.sum(grid_norm * pattern))
                
                # Gesamtwertung: Kantenenergie der Trennlinien, Korrelation mit dem Muster und die Annahme, dass das Brett unten liegt
                # Die Unterkante des Duolingo-Bretts liegt meist zwischen 80 % und 98 % der Bildhöhe
                bottom_ratio = (y + size) / s_h
                pos_prior = 1.0 if 0.75 <= bottom_ratio <= 0.98 else 0.4
                
                total_score = (corr * 2.0 + edge_score * 0.5) * pos_prior
                
                if total_score > best_score:
                    best_score = total_score
                    best_box = (x, y, size)
                    
    x_s, y_s, size_s = best_box
    inv_scale = 1.0 / scale
    x = int(round(x_s * inv_scale))
    y = int(round(y_s * inv_scale))
    size = int(round(size_s * inv_scale))
    
    return (x, y, x + size, y + size)

# Erneut ausführen und darstellen
os.makedirs("scratch/debug_board_perfect", exist_ok=True)
for filename in ["duolingo_1.jpeg", "duolingo_2.jpg", "duolingo_3.jpg"]:
    img = cv2.imread(filename)
    l, t, r, b = locate_duolingo_board(img)
    print(f"[{filename}] Perfect Board rect: L={l}, T={t}, R={r}, B={b}, Size={r-l}x{b-t}")
    
    vis = img.copy()
    cv2.rectangle(vis, (l, t), (r, b), (0, 255, 0), 4)
    step = (r - l) / 8.0
    for i in range(1, 8):
        cv2.line(vis, (int(l + i * step), t), (int(l + i * step), b), (0, 255, 0), 2)
        cv2.line(vis, (l, int(t + i * step)), (r, int(t + i * step)), (0, 255, 0), 2)
        
    cv2.imwrite(f"scratch/debug_board_perfect/{filename}_board.png", vis)
