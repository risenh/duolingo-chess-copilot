import cv2
import numpy as np
import os

# Die bewährte Funktion zur Brettlokalisierung einbinden
from test_locator_v3 import find_bottom_edge_and_board

def extract_cells(image_path):
    img = cv2.imread(image_path)
    l, t, r, b = find_bottom_edge_and_board(img)
    step = (r - l) / 8.0
    
    cells = []
    for row in range(8):
        row_cells = []
        for col in range(8):
            x1 = int(round(l + col * step))
            y1 = int(round(t + row * step))
            x2 = int(round(l + (col + 1) * step))
            y2 = int(round(t + (row + 1) * step))
            cell = img[y1:y2, x1:x2]
            row_cells.append(cell)
        cells.append(row_cells)
    return cells

# Vorlagen der Standardfiguren aus der Grundstellung schneiden und speichern
os.makedirs("scratch/templates", exist_ok=True)
img1 = cv2.imread("duolingo_1.jpeg")
cells1 = extract_cells("duolingo_1.jpeg")

# duolingo_1 zeigt die Grundstellung mit Weiß unten (row 6: Bauern, row 7: R N B Q K B N R)
# und Schwarz oben (row 0: r n b q k b n r, row 1: p)
pieces_map = {
    'R': cells1[7][0],
    'N': cells1[7][1],
    'B': cells1[7][2],
    'Q': cells1[7][3],
    'K': cells1[7][4],
    'P': cells1[6][0],
}

for name, cell in pieces_map.items():
    # Auf 64x64 normieren
    resized = cv2.resize(cell, (64, 64))
    cv2.imwrite(f"scratch/templates/{name}.png", resized)
    
print("Die Vorlagen der 6 Figurenarten wurden aus dem Duolingo-Brett extrahiert")
