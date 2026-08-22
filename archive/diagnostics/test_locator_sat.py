import cv2
import numpy as np
import os
import time

def fast_locate_duolingo_board(image):
    img_h, img_w = image.shape[:2]
    gray = cv2.cvtColor(image, cv2.COLOR_BGR2GRAY)
    
    # 1. Auf eine einheitliche Größe verkleinern (400px Breite)
    scale = 400.0 / img_w
    s_w = 400
    s_h = int(img_h * scale)
    s_gray = cv2.resize(gray, (s_w, s_h)).astype(np.float32)
    
    # 2. Integralbild des Kantengradienten
    gx = cv2.Sobel(s_gray, cv2.CV_32F, 1, 0, ksize=3)
    gy = cv2.Sobel(s_gray, cv2.CV_32F, 0, 1, ksize=3)
    mag = np.abs(gx) + np.abs(gy)
    
    sat_gray = cv2.integral(s_gray)
    sat_mag = cv2.integral(mag)
    
    def rect_sum(sat, x1, y1, x2, y2):
        x1, y1 = max(0, int(x1)), max(0, int(y1))
        x2, y2 = min(s_w, int(x2)), min(s_h, int(y2))
        if x2 <= x1 or y2 <= y1: return 0.0
        return sat[y2, x2] - sat[y1, x2] - sat[y2, x1] + sat[y1, x1]

    def rect_mean(sat, x1, y1, x2, y2):
        w = max(1, int(x2) - int(x1))
        h = max(1, int(y2) - int(y1))
        return rect_sum(sat, x1, y1, x2, y2) / (w * h)

    pattern = np.zeros((8, 8), dtype=np.float32)
    for r in range(8):
        for c in range(8):
            pattern[r, c] = 1.0 if (r + c) % 2 == 0 else -1.0
            
    best_score = -1e9
    best_box = (0, 0, int(0.9 * s_w))
    
    min_size = int(0.85 * s_w)
    max_size = min(s_w, int(0.98 * s_w))
    
    for size in range(min_size, max_size + 1, 2):
        step = size / 8.0
        center_x = (s_w - size) // 2
        for x in range(max(0, center_x - 6), min(s_w - size + 1, center_x + 7), 2):
            y_min = int(s_h * 0.20)
            y_max = s_h - size
            for y in range(y_min, y_max, 2):
                # 1. Kantenenergie der 7 waagerechten und senkrechten Trennlinien
                edge_score = 0.0
                for i in range(1, 8):
                    ly = int(y + i * step)
                    lx = int(x + i * step)
                    edge_score += rect_mean(sat_mag, x, ly - 1, x + size, ly + 2)
                    edge_score += rect_mean(sat_mag, lx - 1, y, lx + 2, y + size)
                    
                # 2. Die 4 Ecken der 8x8 Felder abtasten
                grid_means = np.zeros((8, 8), dtype=np.float32)
                corner_w = max(1, int(step * 0.2))
                for r in range(8):
                    cy1 = y + r * step
                    cy2 = cy1 + step
                    for c in range(8):
                        cx1 = x + c * step
                        cx2 = cx1 + step
                        m1 = rect_mean(sat_gray, cx1, cy1, cx1 + corner_w, cy1 + corner_w)
                        m2 = rect_mean(sat_gray, cx2 - corner_w, cy1, cx2, cy1 + corner_w)
                        m3 = rect_mean(sat_gray, cx1, cy2 - corner_w, cx1 + corner_w, cy2)
                        m4 = rect_mean(sat_gray, cx2 - corner_w, cy2 - corner_w, cx2, cy2)
                        grid_means[r, c] = (m1 + m2 + m3 + m4) * 0.25
                        
                g_norm = grid_means - np.mean(grid_means)
                corr = abs(np.sum(g_norm * pattern))
                
                # 3. Annahme zur Lage unten (die Unterkante des Duolingo-Bretts liegt meist zwischen 75 % und 98 %)
                bottom_ratio = (y + size) / s_h
                pos_prior = 1.0 if 0.72 <= bottom_ratio <= 0.98 else 0.35
                
                score = (corr * 2.0 + edge_score * 0.4) * pos_prior
                if score > best_score:
                    best_score = score
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
        t0 = time.time()
        l, t, r, b = fast_locate_duolingo_board(img)
        dt = (time.time() - t0) * 1000
        print(f"[{img_name}] Fast SAT Locator: L={l}, T={t}, R={r}, B={b}, Size={r-l}x{b-t}, Time={dt:.1f}ms")
