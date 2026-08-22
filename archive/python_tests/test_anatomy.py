import cv2
import numpy as np

# Für jede Figurenart aus img1, img2 und img3 werden die Kennzahlen berechnet:
# 1. Antwort der Kreuzfaltung (cross_score)
# 2. Unsymmetrie zwischen links und rechts (asymmetry)
# 3. Zackigkeit bzw. Spreizung oben (top_crown)
# 4. Seitenverhältnis und Höhe (height_ratio)

def analyze_piece_anatomy(cell_img):
    resized = cv2.resize(cell_img, (60, 60))
    gray = cv2.cvtColor(resized, cv2.COLOR_BGR2GRAY)
    
    # Nur den Zentrumsbereich verwenden
    center = gray[12:48, 12:48]
    
    # Kanten
    gx = cv2.Sobel(gray, cv2.CV_32F, 1, 0, ksize=3)
    gy = cv2.Sobel(gray, cv2.CV_32F, 0, 1, ksize=3)
    mag = np.sqrt(gx**2 + gy**2)[10:50, 10:50]
    
    # Antwort der Kreuzfaltung (König)
    # Bereich 15:25 in der Mitte
    cross_kernel = np.array([[0, 1, 0], [1, 1, 1], [0, 1, 0]], dtype=np.float32)
    cross_resp = cv2.filter2D(mag, -1, cross_kernel)
    cross_max = np.max(cross_resp[10:30, 10:30])
    
    # Unsymmetrie zwischen links und rechts (Springer)
    left_side = mag[:, :20]
    right_side = cv2.flip(mag[:, 20:], 1)[:, :20]
    asym = np.mean(np.abs(left_side - right_side))
    
    # Breitenverteilung im oberen Drittel (Dame gegen Turm gegen Läufer)
    top_strip = mag[5:15, :]
    top_profile = np.sum(top_strip, axis=0)
    
    return {
        'cross': cross_max,
        'asym': asym,
        'mean_mag': np.mean(mag)
    }

for cls_name, p in [('King', 'scratch/all_cells/img1/r7_c4.png'),
                    ('Queen', 'scratch/all_cells/img1/r7_c3.png'),
                    ('Knight', 'scratch/all_cells/img1/r7_c1.png'),
                    ('Rook', 'scratch/all_cells/img1/r7_c0.png'),
                    ('Bishop', 'scratch/all_cells/img1/r7_c2.png'),
                    ('Pawn', 'scratch/all_cells/img1/r6_c0.png')]:
    img = cv2.imread(p)
    info = analyze_piece_anatomy(img)
    print(f"[{cls_name:6s}] cross: {info['cross']:.1f}, asym: {info['asym']:.1f}, mag: {info['mean_mag']:.1f}")
