import cv2
import numpy as np

def locate_board_by_grid(img_path):
    img = cv2.imread(img_path)
    h, w = img.shape[:2]
    
    # Das Brett liegt meist mittig bis unten und nimmt 90 % bis 100 % der Bildbreite ein
    # In Graustufen umwandeln
    gray = cv2.cvtColor(img, cv2.COLOR_BGR2GRAY)
    
    # Kanten suchen
    edges = cv2.Canny(gray, 30, 100)
    
    # Kantenpunkte waagerecht projizieren
    row_proj = np.sum(edges, axis=1)
    
    # Kantenpunkte senkrecht projizieren
    col_proj = np.sum(edges, axis=0)
    
    # Bereiche mit hochfrequentem Feldwechsel suchen
    print(f"--- Analyzing {img_path} ({w}x{h}) ---")
    
    # Den ausgeschnittenen Brettbereich darstellen oder speichern
    # Das Duolingo-Brett ist quadratisch und hat je Seite 8 Felder.
    # Über 8 gleich breite und gleich hohe abwechselnde Felder lässt es sich genau lokalisieren.
    return img

for name in ["duolingo_1.jpeg", "duolingo_2.jpg", "duolingo_3.jpg"]:
    locate_board_by_grid(name)
