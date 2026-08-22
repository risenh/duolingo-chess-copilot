import cv2
import numpy as np
import os
import glob

class MultiModalPieceClassifier:
    """
    Multimodaler Figurenklassifikator für Duolingo-Schach
    Enthält die Referenzmerkmale aller 6 Figurenarten für helles und dunkles Design sowie für Schwarz und Weiß
    """
    def __init__(self):
        # Pfade der bekannten Beispielbilder je Klasse
        # img1: r0: r,n,b,q,k,b,n,r; r1: p; r6: P; r7: R,N,B,Q,K,B,N,R
        # img2: r0: R,N,B,K,Q,B,N,R; r1: P,P,.,P,P,P,P,P; r3: .,.,P; r6: p; r7: r,n,b,k,q,b,n,r
        # img3: r0: r,n,b,q,k,b,n,r; r1: p; r6: P; r7: R,N,B,Q,K,B,N,R
        
        self.sample_map = {
            'R': [
                "scratch/all_cells/img1/r7_c0.png", "scratch/all_cells/img1/r7_c7.png",
                "scratch/all_cells/img2/r0_c0.png", "scratch/all_cells/img2/r0_c7.png",
                "scratch/all_cells/img3/r7_c0.png", "scratch/all_cells/img3/r7_c7.png"
            ],
            'N': [
                "scratch/all_cells/img1/r7_c1.png", "scratch/all_cells/img1/r7_c6.png",
                "scratch/all_cells/img2/r0_c1.png", "scratch/all_cells/img2/r0_c6.png",
                "scratch/all_cells/img3/r7_c1.png", "scratch/all_cells/img3/r7_c6.png"
            ],
            'B': [
                "scratch/all_cells/img1/r7_c2.png", "scratch/all_cells/img1/r7_c5.png",
                "scratch/all_cells/img2/r0_c2.png", "scratch/all_cells/img2/r0_c5.png",
                "scratch/all_cells/img3/r7_c2.png", "scratch/all_cells/img3/r7_c5.png"
            ],
            'Q': [
                "scratch/all_cells/img1/r7_c3.png",
                "scratch/all_cells/img2/r0_c4.png", # Achtung: in img2 steht der König auf c3 und die Dame auf c4
                "scratch/all_cells/img3/r7_c3.png"
            ],
            'K': [
                "scratch/all_cells/img1/r7_c4.png",
                "scratch/all_cells/img2/r0_c3.png",
                "scratch/all_cells/img3/r7_c4.png"
            ],
            'P': [
                "scratch/all_cells/img1/r6_c0.png", "scratch/all_cells/img1/r6_c3.png",
                "scratch/all_cells/img2/r1_c0.png", "scratch/all_cells/img2/r3_c2.png",
                "scratch/all_cells/img3/r6_c0.png", "scratch/all_cells/img3/r6_c4.png"
            ],
            # Beispiele für schwarze Figuren
            'r': [
                "scratch/all_cells/img1/r0_c0.png", "scratch/all_cells/img1/r0_c7.png",
                "scratch/all_cells/img2/r7_c0.png", "scratch/all_cells/img2/r7_c7.png",
                "scratch/all_cells/img3/r0_c0.png", "scratch/all_cells/img3/r0_c7.png"
            ],
            'n': [
                "scratch/all_cells/img1/r0_c1.png", "scratch/all_cells/img1/r0_c6.png",
                "scratch/all_cells/img2/r7_c1.png", "scratch/all_cells/img2/r7_c6.png",
                "scratch/all_cells/img3/r0_c1.png", "scratch/all_cells/img3/r0_c6.png"
            ],
            'b': [
                "scratch/all_cells/img1/r0_c2.png", "scratch/all_cells/img1/r0_c5.png",
                "scratch/all_cells/img2/r7_c2.png", "scratch/all_cells/img2/r7_c5.png",
                "scratch/all_cells/img3/r0_c2.png", "scratch/all_cells/img3/r0_c5.png"
            ],
            'q': [
                "scratch/all_cells/img1/r0_c3.png",
                "scratch/all_cells/img2/r7_c4.png",
                "scratch/all_cells/img3/r0_c3.png"
            ],
            'k': [
                "scratch/all_cells/img1/r0_c4.png",
                "scratch/all_cells/img2/r7_c3.png",
                "scratch/all_cells/img3/r0_c4.png"
            ],
            'p': [
                "scratch/all_cells/img1/r1_c0.png", "scratch/all_cells/img1/r1_c4.png",
                "scratch/all_cells/img2/r6_c0.png", "scratch/all_cells/img2/r6_c4.png",
                "scratch/all_cells/img3/r1_c0.png", "scratch/all_cells/img3/r1_c3.png"
            ]
        }
        
        self.templates = {}
        for piece_type, paths in self.sample_map.items():
            feats = []
            for p in paths:
                if os.path.exists(p):
                    img = cv2.imread(p)
                    feats.append(self._get_feature(img))
            self.templates[piece_type] = feats

    def _get_feature(self, cell_img):
        # Auf 48x48 normieren
        resized = cv2.resize(cell_img, (48, 48))
        gray = cv2.cvtColor(resized, cv2.COLOR_BGR2GRAY)
        
        # Nur den Bereich 10 % bis 90 % verwenden, das entfernt die Gitterlinien am Rand
        gx = cv2.Sobel(gray, cv2.CV_32F, 1, 0, ksize=3)
        gy = cv2.Sobel(gray, cv2.CV_32F, 0, 1, ksize=3)
        mag = np.sqrt(gx**2 + gy**2)
        
        mask = np.zeros_like(mag)
        mask[6:42, 6:42] = 1.0
        mag = mag * mask
        
        # Gradientenenergie normieren
        norm = np.linalg.norm(mag)
        mag_norm = mag / (norm + 1e-6)
        
        # Helligkeitskontrast in der Feldmitte
        center_gray = gray[10:38, 10:38]
        
        return {
            'mag_norm': mag_norm,
            'gray': gray,
            'center_std': np.std(center_gray),
            'center_mean': np.mean(center_gray),
            'energy': np.sum(mag)
        }

    def classify(self, cell_img):
        feat = self._get_feature(cell_img)
        
        # 1. Leeres Feld erkennen: im Inneren ändert sich fast nichts
        if feat['energy'] < 100 or feat['center_std'] < 6.5:
            return '.'
            
        # 2. Kosinus-Ähnlichkeit mit den Vorlagen jeder Klasse
        best_type = '.'
        best_sim = -1.0
        
        for p_type, feat_list in self.templates.items():
            for t_feat in feat_list:
                # Kosinus-Ähnlichkeit der Gradienten
                cos_sim = np.sum(feat['mag_norm'] * t_feat['mag_norm'])
                
                # Abzug bei unpassender Helligkeit (trennt weiße von schwarzen Figuren)
                # Ist die eine Seite weiß (Großbuchstabe) und die andere schwarz (Kleinbuchstabe), gibt eine große Differenz der Mittelwerte Abzug
                is_t_white = p_type.isupper()
                # Grobe Einschätzung, ob das Feld eher hell ist
                is_f_white = feat['center_mean'] > 120
                
                penalty = 0.0
                if is_t_white != is_f_white:
                # Treffer mit der falschen Farbe abwerten
                    penalty = 0.15
                    
                final_sim = cos_sim - penalty
                
                if final_sim > best_sim:
                    best_sim = final_sim
                    best_type = p_type
                    
        return best_type

print("Das Modul MultiModalPieceClassifier wurde geladen")
