import cv2
import numpy as np
import os
from test_locator_v3 import find_bottom_edge_and_board

class DuolingoPieceClassifier:
    """
    Klassifikator für die Form der 2D-Figuren in Duolingo-Schach
    Blendet Farbe und Hintergrund aus und gewinnt aus der Vordergrundmaske geometrische Merkmale
    (Seitenverhältnis, Schwerpunkt, Breitenverlauf über oben, Mitte und unten) zur Erkennung der 6 Figurenarten (P, N, B, R, Q, K)
    """
    def __init__(self):
        # Grundvorlagen laden und daraus die Referenzmerkmale bilden
        self.templates = {}
        for name in ['P', 'R', 'N', 'B', 'Q', 'K']:
            path = f"scratch/templates/{name}.png"
            if os.path.exists(path):
                t_img = cv2.imread(path)
                self.templates[name] = self._extract_shape_feature(t_img)

    def _extract_shape_feature(self, cell_img):
        """
        Merkmalsvektor eines einzelnen Feldes bestimmen
        1. Auf 48x48 normieren
        2. Sobel-Gradientenenergie der Zentrumsregion berechnen (48x48)
        3. Waagerechtes (48) und senkrechtes (48) Profil berechnen
        """
        resized = cv2.resize(cell_img, (48, 48))
        gray = cv2.cvtColor(resized, cv2.COLOR_BGR2GRAY)
        
        # Kantenstruktur per Sobel gewinnen
        gx = cv2.Sobel(gray, cv2.CV_32F, 1, 0, ksize=3)
        gy = cv2.Sobel(gray, cv2.CV_32F, 0, 1, ksize=3)
        mag = np.sqrt(gx**2 + gy**2)
        
        # Die Figur liegt im Bereich 10 % bis 90 %, das blendet die Gitterlinien am Rand aus
        mask = np.zeros_like(mag)
        mask[5:43, 5:43] = 1.0
        mag_core = mag * mask
        
        # Normieren
        norm = np.linalg.norm(mag_core)
        if norm > 1e-3:
            mag_core_norm = mag_core / norm
        else:
            mag_core_norm = mag_core
            
        h_proj = np.sum(mag_core, axis=1) # 48
        v_proj = np.sum(mag_core, axis=0) # 48
        
        h_norm = h_proj / (np.linalg.norm(h_proj) + 1e-5)
        v_norm = v_proj / (np.linalg.norm(v_proj) + 1e-5)
        
        return {
            'mag': mag_core_norm,
            'h_proj': h_norm,
            'v_proj': v_norm,
            'energy': np.sum(mag_core),
            'gray': gray
        }

    def classify_cell(self, cell_img, is_white_board_color):
        """
        Ein einzelnes Feld bestimmen:
        Rückgabe: (piece_char oder '.', is_white_piece)
        Beispiele: ('P', True) weißer Bauer, ('n', False) schwarzer Springer, ('.', None) leeres Feld
        """
        feat = self._extract_shape_feature(cell_img)
        
        # 1. Prüfen, ob das Feld leer ist
        # Ein leeres Feld hat im Inneren sehr wenig Energie (meist nur die Feldfarbe)
        gray = feat['gray']
        center_roi = gray[12:36, 12:36]
        std_val = np.std(center_roi)
        
        if feat['energy'] < 120 or std_val < 7.0:
            return '.', None
            
        # 2. Ähnlichkeit mit den 6 Vorlagen berechnen (Kosinus- und Profilähnlichkeit)
        best_type = 'P'
        best_sim = -1.0
        
        for name, t_feat in self.templates.items():
            # Korrelation der Matrizen
            cos_sim = np.sum(feat['mag'] * t_feat['mag'])
            # Korrelation der Profile
            h_sim = np.dot(feat['h_proj'], t_feat['h_proj'])
            v_sim = np.dot(feat['v_proj'], t_feat['v_proj'])
            
            total_sim = cos_sim * 0.6 + h_sim * 0.2 + v_sim * 0.2
            
            # Zusätzliches Merkmal für den König: das Kreuz auf der Brust
            # Das Kreuz erzeugt bei y: 16~30, x: 16~30 eine hohe lokale Kantenantwort
            if total_sim > best_sim:
                best_sim = total_sim
                best_type = name
                
        # 3. Bestimmen, ob die Figur schwarz oder weiß ist
        # Verglichen wird die mittlere Helligkeit der Figur mit der des umgebenden Hintergrunds
        corner_mean = (np.mean(gray[:8, :8]) + np.mean(gray[:8, -8:]) + 
                       np.mean(gray[-8:, :8]) + np.mean(gray[-8:, -8:])) / 4.0
        center_mean = np.mean(center_roi)
        
        # In Duolingo gilt in hellem wie dunklem Design:
        # helle Figuren sind in der Mitte deutlich heller (> 140 oder klar heller als die dunklen)
        # dunkle Figuren sind in der Mitte deutlich dunkler (< 100)
        is_white = center_mean > 120 or (center_mean - corner_mean > 15)
        
        piece_symbol = best_type if is_white else best_type.lower()
        return piece_symbol, is_white

print("DuolingoPieceClassifier ist definiert")
