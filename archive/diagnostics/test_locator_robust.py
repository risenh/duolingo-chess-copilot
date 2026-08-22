import cv2
import numpy as np
import os
import glob

def locate_duolingo_board_robust(image):
    img_h, img_w = image.shape[:2]
    gray = cv2.cvtColor(image, cv2.COLOR_BGR2GRAY)
    
    # 1. Sobel-Gradient berechnen
    grad_x = cv2.Sobel(gray, cv2.CV_32F, 1, 0, ksize=3)
    grad_y = cv2.Sobel(gray, cv2.CV_32F, 0, 1, ksize=3)
    mag = np.abs(grad_x) + np.abs(grad_y)
    
    scale = 400.0 / img_w
    s_w = 400
    s_h = int(img_h * scale)
    s_gray = cv2.resize(gray, (s_w, s_h)).astype(np.float32)
    s_mag = cv2.resize(mag, (s_w, s_h))
    
    min_size = int(0.85 * s_w)
    max_size = min(s_w, int(0.98 * s_w))
    
    pattern = np.zeros((8, 8), dtype=np.float32)
    for r in range(8):
        for c in range(8):
            pattern[r, c] = 1.0 if (r + c) % 2 == 0 else -1.0
            
    best_score = -1e9
    best_box = (0, 0, min_size)
    
    for size in range(min_size, max_size + 1, 4):
        step = size / 8.0
        center_x = (s_w - size) // 2
        for x in range(max(0, center_x - 8), min(s_w - size + 1, center_x + 9), 2):
            y_min = int(s_h * 0.20)
            y_max = s_h - size
            for y in range(y_min, y_max, 4):
                # 1. Kantenenergie der Trennlinien
                edge_score = 0.0
                for i in range(1, 8):
                    ly = int(y + i * step)
                    lx = int(x + i * step)
                    edge_score += np.mean(s_mag[max(0, ly-1):min(s_h, ly+2), x:x+size])
                    edge_score += np.mean(s_mag[y:y+size, max(0, lx-1):min(s_w, lx+2)])
                    
                # 2. Varianz des abwechselnden Musters an den 4 Ecken (spart die Figur in der Mitte aus)
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
                        c_vals = [
                            cell[0:max(1, int(ch*0.18)), 0:max(1, int(cw*0.18))],
                            cell[0:max(1, int(ch*0.18)), -max(1, int(cw*0.18)):],
                            cell[-max(1, int(ch*0.18)):, 0:max(1, int(cw*0.18))],
                            cell[-max(1, int(ch*0.18)):, -max(1, int(cw*0.18)):]
                        ]
                        grid_means[r, c] = np.mean([np.mean(cv) for cv in c_vals])
                        
                g_norm = grid_means - np.mean(grid_means)
                corr = abs(np.sum(g_norm * pattern))
                
                # 3. Plausibilitätsannahme zur senkrechten Lage
                bottom_ratio = (y + size) / s_h
                pos_prior = 1.0 if 0.70 <= bottom_ratio <= 0.98 else 0.35
                
                total_score = (corr * 2.0 + edge_score * 0.4) * pos_prior
                if total_score > best_score:
                    best_score = total_score
                    best_box = (x, y, size)
                    
    x_s, y_s, size_s = best_box
    inv_scale = 1.0 / scale
    x = int(round(x_s * inv_scale))
    y = int(round(y_s * inv_scale))
    size = int(round(size_s * inv_scale))
    
    x = max(0, min(img_w - size, x))
    y = max(0, min(img_h - size, y))
    
    return x, y, x + size, y + size

if __name__ == '__main__':
    images = ["duolingo_1.jpeg", "duolingo_2.jpg", "duolingo_3.jpg", "duolingo_test_1.jfif", "duolingo_test_2.jfif", "duolingo_test_3.jfif"]
    for img_name in images:
        if not os.path.exists(img_name): continue
        img = cv2.imread(img_name)
        l, t, r, b = locate_duolingo_board_robust(img)
        print(f"[{img_name}] Found Board: L={l}, T={t}, R={r}, B={b}, Size={r-l}x{b-t}, Screen={img.shape[1]}x{img.shape[0]}")
