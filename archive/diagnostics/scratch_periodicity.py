import cv2
import numpy as np

# Untersucht, wie sich echtes Brett und der Bereich mit Figuren und Text in der waagerechten und senkrechten Projektion unterscheiden
for name in ["duolingo_test_1.jfif", "duolingo_test_2.jfif", "duolingo_test_3.jfif", "duolingo_1.jpeg"]:
    img = cv2.imread(name)
    h, w = img.shape[:2]
    gray = cv2.cvtColor(img, cv2.COLOR_BGR2GRAY)
    
    # Senkrechten Sobel (waagerechte Linien) und waagerechten Sobel (senkrechte Linien) berechnen
    gx = np.abs(cv2.Sobel(gray, cv2.CV_32F, 1, 0, ksize=3))
    gy = np.abs(cv2.Sobel(gray, cv2.CV_32F, 0, 1, ksize=3))
    
    # Das echte Brett nimmt in x-Richtung meist den mittleren Hauptbereich des Bildes ein
    print(f"File {name}: {w}x{h}")
