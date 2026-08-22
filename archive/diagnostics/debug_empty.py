import cv2
import numpy as np

for r, c in [(2, 2), (3, 3), (4, 4), (6, 0), (6, 4), (7, 4)]:
    p = f"scratch/all_cells/img3/r{r}_c{c}.png"
    img = cv2.imread(p)
    gray = cv2.cvtColor(img, cv2.COLOR_BGR2GRAY)
    
    # Den Bereich 20 % bis 80 % schneiden (weit weg von den Gitterlinien)
    ch, cw = gray.shape
    center_roi = gray[int(ch*0.25):int(ch*0.75), int(cw*0.25):int(cw*0.75)]
    
    # Standardabweichung im Inneren und Kantenenergie berechnen
    gx = cv2.Sobel(center_roi, cv2.CV_32F, 1, 0, ksize=3)
    gy = cv2.Sobel(center_roi, cv2.CV_32F, 0, 1, ksize=3)
    grad_mag = np.mean(np.sqrt(gx**2 + gy**2))
    std_val = np.std(center_roi)
    
    print(f"img3 (r{r}, c{c}): center std = {std_val:.2f}, grad_mag = {grad_mag:.2f}")
