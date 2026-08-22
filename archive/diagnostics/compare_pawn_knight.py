import cv2
import numpy as np

# Vergleicht die Ausschnitte von Bauer (Pawn) und Springer (Knight)
for col in [0, 1, 5, 6, 7]:
    p_img = cv2.imread(f"scratch/all_cells/img3/r6_c{col}.png")
    n_img = cv2.imread(f"scratch/all_cells/img3/r7_c1.png")
    
    # Höhe und Umrissfläche bestimmen
    def get_contour_metrics(img):
        resized = cv2.resize(img, (48, 48))
        gray = cv2.cvtColor(resized, cv2.COLOR_BGR2GRAY)
        gx = cv2.Sobel(gray, cv2.CV_32F, 1, 0, ksize=3)
        gy = cv2.Sobel(gray, cv2.CV_32F, 0, 1, ksize=3)
        mag = np.sqrt(gx**2 + gy**2)[8:40, 8:40]
        
        # Den höchsten Punkt suchen (die oberste Zeile mit Gradient)
        row_energy = np.sum(mag, axis=1)
        active_rows = np.where(row_energy > 0.15 * np.max(row_energy))[0]
        height = len(active_rows) if len(active_rows) > 0 else 0
        return height, np.sum(mag)
        
    ph, pe = get_contour_metrics(p_img)
    nh, ne = get_contour_metrics(n_img)
    print(f"Col {col}: Pawn height={ph}, energy={pe:.1f} | Knight height={nh}, energy={ne:.1f}")
