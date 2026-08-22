import cv2
import numpy as np

# Die tatsächlichen Pixelgrenzen des Bretts werden auf 3 Bildern direkt gemessen:
# 1. duolingo_1.jpeg: 750 x 1334
# 2. duolingo_2.jpg: 1179 x 2004
# 3. duolingo_3.jpg: 1260 x 2800

for filename in ["duolingo_1.jpeg", "duolingo_2.jpg", "duolingo_3.jpg"]:
    img = cv2.imread(filename)
    h, w = img.shape[:2]
    
    # Direkt von unten nach oben nach der Unterkante des Bretts suchen (untere Kante des abgerundeten Rahmens):
    # Das Brett ist eine eigene Karte und hat unten eine deutliche waagerechte Trennlinie
    gray = cv2.cvtColor(img, cv2.COLOR_BGR2GRAY)
    
    # Den Bereich von 60 % bis 98 % der Höhe betrachten
    bottom_strip = gray[int(h*0.7):int(h*0.99), :]
    
    print(f"File {filename}: W={w}, H={h}")
