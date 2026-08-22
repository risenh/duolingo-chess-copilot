import cv2
import numpy as np
import os

def find_bottom_edge_and_board(image):
    """
    Sperrt das Brett von unten her ein: über die waagerechte Trennlinie am unteren Rand des Brettbereichs und das Gitter mit 8 Perioden
    """
    img_h, img_w = image.shape[:2]
    gray = cv2.cvtColor(image, cv2.COLOR_BGR2GRAY)
    
    # Die Brettbreite liegt zwischen 0.88 * img_w und 0.98 * img_w
    # Waagerechten Projektionsgradienten berechnen
    grad_y = np.abs(cv2.Sobel(gray, cv2.CV_32F, 0, 1, ksize=3))
    
    # Die Unterkante y_bottom liegt immer zwischen 0.70 * img_h und 0.98 * img_h
    # Das Brett besteht aus 8 Reihen der Höhe step, von y_bottom aus liegen darüber also 8 gleich weit entfernte Linien:
    # y_bottom, y_bottom - step, ..., y_bottom - 8*step
    
    best_score = -1e9
    best_params = None # (size, x, y_bottom)
    
    # Schnelle, genaue Suche in voller oder halber Auflösung
    # Größenbereich
    for size in range(int(0.85 * img_w), int(0.98 * img_w), 4):
        step = size / 8.0
        x = (img_w - size) // 2 # waagerecht mittig
        
        # Suchbereich für y_bottom: von 0.75 * img_h bis 0.98 * img_h
        y_bottom_min = int(0.65 * img_h + size)
        y_bottom_max = min(img_h - 10, int(0.99 * img_h))
        
        for y_bottom in range(int(size + img_h * 0.1), min(img_h - 5, int(size + img_h * 0.4)), 4):
            y_top = y_bottom - size
            if y_top < int(img_h * 0.15):
                continue
                
            # Kantenenergie der waagerechten Linien des 8x8-Gitters aufsummieren
            h_line_energy = 0.0
            for i in range(9): # 9 waagerechte Linien (0 bis 8, mit Ober- und Unterkante)
                ly = int(y_top + i * step)
                if 0 <= ly < img_h:
                    h_line_energy += np.mean(grad_y[max(0, ly-2):min(img_h, ly+3), x:x+size])
                    
            # Varianz des abwechselnden Musters an den Eckpunkten der 8x8 Felder
            # Die Ecken jedes Feldes abtasten
            pattern = np.zeros((8, 8), dtype=np.float32)
            grid_means = np.zeros((8, 8), dtype=np.float32)
            for r in range(8):
                for c in range(8):
                    pattern[r, c] = 1.0 if (r + c) % 2 == 0 else -1.0
                    cy1 = int(y_top + r * step)
                    cy2 = int(y_top + (r + 1) * step)
                    cx1 = int(x + c * step)
                    cx2 = int(x + (c + 1) * step)
                    cell = gray[cy1:cy2, cx1:cx2]
                    if cell.size == 0:
                        continue
                    ch, cw = cell.shape
                    # Die vier Ecken abtasten
                    c_vals = [
                        cell[0:max(1, int(ch*0.15)), 0:max(1, int(cw*0.15))],
                        cell[0:max(1, int(ch*0.15)), -max(1, int(cw*0.15)):],
                        cell[-max(1, int(ch*0.15)):, 0:max(1, int(cw*0.15))],
                        cell[-max(1, int(ch*0.15)):, -max(1, int(cw*0.15)):]
                    ]
                    grid_means[r, c] = np.mean([np.mean(cv) for cv in c_vals])
            
            grid_norm = grid_means - np.mean(grid_means)
            corr = abs(np.sum(grid_norm * pattern))
            
            score = corr * 1.5 + h_line_energy * 0.8
            if score > best_score:
                best_score = score
                best_params = (x, y_top, size)
                
    bx, by, bsize = best_params
    return (bx, by, bx + bsize, by + bsize)

os.makedirs("scratch/debug_board_v3", exist_ok=True)
for filename in ["duolingo_1.jpeg", "duolingo_2.jpg", "duolingo_3.jpg"]:
    img = cv2.imread(filename)
    l, t, r, b = find_bottom_edge_and_board(img)
    print(f"[{filename}] V3 Board rect: L={l}, T={t}, R={r}, B={b}, Size={r-l}x{b-t}")
    
    vis = img.copy()
    cv2.rectangle(vis, (l, t), (r, b), (0, 255, 0), 4)
    step = (r - l) / 8.0
    for i in range(1, 8):
        cv2.line(vis, (int(l + i * step), t), (int(l + i * step), b), (0, 255, 0), 2)
        cv2.line(vis, (l, int(t + i * step)), (r, int(t + i * step)), (0, 255, 0), 2)
        
    cv2.imwrite(f"scratch/debug_board_v3/{filename}_board.png", vis)
