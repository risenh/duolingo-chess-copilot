import cv2
import numpy as np

img8 = cv2.imread("bug_8.jpg")
h, w = img8.shape[:2]

# Let's save small crops of lines in bug_8.jpg
# Top card:
# Lines:
# 1. [Diagnose eines einzelnen Screenshots]
# 2. Brettkoordinaten: [L=16, T=1043, R=1248, B=2275]
# 3. Perspektive: Weiß (White)
# 4. Stellung (FEN): ...
# 5. Vollständiges FEN: ...
# 6. -----------------------------
# 7. Empfohlener Zug: ...
# 8. Bewertung: +0.00
# 9. Suchtiefe: 0 [Fallback-Generator]
# 10. -----------------------------
# 11. [Engine bereit (Pfad1 (nativeLibDir))]
# 12. Pfad1 [nativeLibDir]: exists=true, canExec=true
# 13. Prozessstart: erfolgreich (Pfad1 (nativeLibDir))
# 14. Handshake [uciok]: erfolgreich (14ms)
# 15. Handshake [readyok]: erfolgreich (8ms)
# 16. Gesamtdauer: 28ms | echtes Stockfish ist bereit

# Let's inspect the exact lines!
for i in range(10):
    y1 = int(h * (0.07 + i * 0.03))
    y2 = int(h * (0.07 + (i + 1) * 0.03))
    line_crop = img8[y1:y2, :]
    cv2.imwrite(f"scratch/line_{i}.png", line_crop)
print("Saved 10 line crops from bug_8.jpg")
