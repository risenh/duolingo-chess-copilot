import cv2
import numpy as np
import os

def find_board_rect(image):
    """
    Bestimmt das genaue Pixelrechteck des 2D-Bretts auf dem Bildschirm (left, top, right, bottom)
    Grundgedanke:
    1. Das Brett besteht aus 8x8 gleich großen Feldern und ist quadratisch (w ≈ h).
    2. Es ist waagerecht mittig und nimmt meist 85 % bis 98 % der Bildschirmbreite ein.
    3. Im Inneren wechselt alle 1/8 der Breite bzw. Höhe die Feldfarbe (regelmäßige hochfrequente Kanten).
    """
    img_h, img_w = image.shape[:2]
    gray = cv2.cvtColor(image, cv2.COLOR_BGR2GRAY)
    
    # Für die schnelle Grobsuche verkleinern
    scale = 400.0 / img_w
    small_w = 400
    small_h = int(img_h * scale)
    small_gray = cv2.resize(gray, (small_w, small_h))
    
    # Kantendetektion
    edges = cv2.Canny(small_gray, 40, 120)
    
    best_score = -1
    best_rect = None # (x, y, size) in small coords
    
    # Die Brettgröße liegt im verkleinerten Bild meist zwischen 300 und 390
    # Die y-Koordinate liegt meist zwischen small_h * 0.2 und small_h * 0.8
    for size in range(280, 395, 4):
        step = size / 8.0
        # Versatz der Feldgrenzen gegenüber der linken oberen Ecke (x, y)
        offsets = [int(i * step) for i in range(1, 8)]
        
        # x mittig durchsuchen (vor allem im mittleren Bereich)
        center_x = (small_w - size) // 2
        for x in range(max(0, center_x - 15), min(small_w - size + 1, center_x + 16), 2):
            for y in range(int(small_h * 0.2), int(small_h * 0.8) - size, 4):
                # Kantenantwort der Gitterlinien im angenommenen Brett berechnen
                score = 0
                for off in offsets:
                    # Antwort der waagerechten Gitterlinien
                    score += np.sum(edges[y + off, x:x + size] > 0)
                    # Antwort der senkrechten Gitterlinien
                    score += np.sum(edges[y:y + size, x + off] > 0)
                
                # Prüfen, ob die Farbvarianz in den Feldmitten zum Schachbrettmuster passt
                if score > best_score:
                    best_score = score
                    best_rect = (x, y, size)
                    
    if best_rect is None:
        raise ValueError("Das Brett konnte nicht lokalisiert werden")
        
    x_s, y_s, size_s = best_rect
    # Zurück in die Koordinaten des Originalbildes rechnen
    inv_scale = 1.0 / scale
    x = int(round(x_s * inv_scale))
    y = int(round(y_s * inv_scale))
    size = int(round(size_s * inv_scale))
    
    # Grenzen feinjustieren: die Spitzen der Gitterlinien suchen
    crop = gray[y:y+size, x:x+size]
    
    return (x, y, x + size, y + size)

# Test ausführen und Darstellung erzeugen
os.makedirs("scratch/debug_board", exist_ok=True)
for filename in ["duolingo_1.jpeg", "duolingo_2.jpg", "duolingo_3.jpg"]:
    img = cv2.imread(filename)
    l, t, r, b = find_board_rect(img)
    print(f"[{filename}] Board rect: L={l}, T={t}, R={r}, B={b}, Size={r-l}x{b-t}")
    
    # Rahmen und 8x8-Gitter zeichnen
    vis = img.copy()
    cv2.rectangle(vis, (l, t), (r, b), (0, 255, 0), 4)
    step = (r - l) / 8.0
    for i in range(1, 8):
        # Senkrechte Linie
        cv2.line(vis, (int(l + i * step), t), (int(l + i * step), b), (0, 255, 0), 2)
        # Waagerechte Linie
        cv2.line(vis, (l, int(t + i * step)), (r, int(t + i * step)), (0, 255, 0), 2)
        
    cv2.imwrite(f"scratch/debug_board/{filename}_board.png", vis)

print("Darstellung der Brettlokalisierung gespeichert unter scratch/debug_board/")
