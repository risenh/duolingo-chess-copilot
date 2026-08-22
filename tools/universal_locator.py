import cv2
import numpy as np
import os

def universal_locate_board(image):
    """
    Allgemeiner adaptiver Lokalisator für 2D-Schachbretter (Universal Multi-Scale Checkerboard Locator)
    Ohne Annahmen über Gerät, Bildschirmausrichtung oder Einblendungen am unteren Rand, rein über die Bildmerkmale
    """
    img_h, img_w = image.shape[:2]
    gray = cv2.cvtColor(image, cv2.COLOR_BGR2GRAY)
    
    # Abwechselndes 8x8-Muster aufbauen
    pattern = np.zeros((8, 8), dtype=np.float32)
    for r in range(8):
        for c in range(8):
            pattern[r, c] = 1.0 if (r + c) % 2 == 0 else -1.0
            
    # Auf eine einheitliche Größe verkleinern (längste Kante 480), damit die Suche Millisekunden dauert
    max_dim = 480.0
    scale = max_dim / max(img_w, img_h)
    s_w = int(round(img_w * scale))
    s_h = int(round(img_h * scale))
    s_gray = cv2.resize(gray, (s_w, s_h)).astype(np.float32)
    
    # Kantengradient
    gx = cv2.Sobel(s_gray, cv2.CV_32F, 1, 0, ksize=3)
    gy = cv2.Sobel(s_gray, cv2.CV_32F, 0, 1, ksize=3)
    s_mag = np.sqrt(gx**2 + gy**2)
    
    best_score = -1e9
    best_rect = None # (x, y, size) in scaled coords
    
    # Bereich der Brettkante im verkleinerten Bild: min_dim * 0.5 bis min_dim * 0.98
    min_dim = min(s_w, s_h)
    min_s = max(80, int(min_dim * 0.55))
    max_s = int(min_dim * 0.98)
    
    for size in range(min_s, max_s + 1, 4):
        step = size / 8.0
        # x-Koordinaten durchlaufen
        for x in range(0, s_w - size + 1, 4):
            # y-Koordinaten durchlaufen
            for y in range(0, s_h - size + 1, 4):
                # Mittelwert der Eckpunkte der 8x8 Felder abtasten
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
                        # Je Ecke 15 % abtasten, damit die Figur in der Mitte nicht stört
                        c1 = cell[0:max(1, int(ch*0.18)), 0:max(1, int(cw*0.18))]
                        c2 = cell[0:max(1, int(ch*0.18)), -max(1, int(cw*0.18)):]
                        c3 = cell[-max(1, int(ch*0.18)):, 0:max(1, int(cw*0.18))]
                        c4 = cell[-max(1, int(ch*0.18)):, -max(1, int(cw*0.18)):]
                        grid_means[r, c] = (np.mean(c1) + np.mean(c2) + np.mean(c3) + np.mean(c4)) / 4.0
                        
                # Übereinstimmung mit dem abwechselnden 8x8-Muster berechnen
                g_norm = grid_means - np.mean(grid_means)
                corr = abs(np.sum(g_norm * pattern))
                
                # Kantenenergie der 7+7 Gitterlinien berechnen
                edge_sum = 0.0
                for i in range(1, 8):
                    ly = int(y + i * step)
                    lx = int(x + i * step)
                    edge_sum += np.mean(s_mag[ly-1:ly+2, x:x+size])
                    edge_sum += np.mean(s_mag[y:y+size, lx-1:lx+2])
                    
                total_score = corr * 2.0 + edge_sum * 0.4
                if total_score > best_score:
                    best_score = total_score
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

    # Test über alle 6 Bilder
os.makedirs("scratch/debug_universal", exist_ok=True)
all_images = [
    "duolingo_1.jpeg", "duolingo_2.jpg", "duolingo_3.jpg",
    "duolingo_test_1.jfif", "duolingo_test_2.jfif", "duolingo_test_3.jfif"
]

for filename in all_images:
    img = cv2.imread(filename)
    if img is None:
        continue
    l, t, r, b = universal_locate_board(img)
    print(f"[{filename}] Universal Board rect: L={l}, T={t}, R={r}, B={b}, Size={r-l}x{b-t}")
    
    vis = img.copy()
    cv2.rectangle(vis, (l, t), (r, b), (0, 255, 0), 2)
    step = (r - l) / 8.0
    for i in range(1, 8):
        cv2.line(vis, (int(l + i * step), t), (int(l + i * step), b), (0, 255, 0), 1)
        cv2.line(vis, (l, int(t + i * step)), (r, int(t + i * step)), (0, 255, 0), 1)
    cv2.imwrite(f"scratch/debug_universal/{filename}_board.png", vis)

print("Allgemeine Lokalisierung für alle 6 Bilder abgeschlossen, gespeichert unter scratch/debug_universal/")
