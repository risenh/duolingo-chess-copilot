import cv2
import numpy as np
import os

class UltraRobustDuolingoClassifier:
    """
    Multimodaler Figurenklassifikator für Duolingo-Schach
    Rein rechnerische Kennzahlen:
    1. Strenge Kern-ROI von 18 % bis 82 %
    2. Multimodal normiertes Gradientenfeld mit normierter Kreuzkorrelation (NCC)
    3. Adaptives 2-Means-Clustering trennt weiße und schwarze Figuren
    """
    def __init__(self):
        # Echte Positivbeispiele aus drei verschiedenen Designs sammeln
        self.class_samples = {
            'P': [
                "scratch/all_cells/img1/r6_c0.png", "scratch/all_cells/img1/r6_c1.png", "scratch/all_cells/img1/r6_c4.png", "scratch/all_cells/img1/r6_c6.png",
                "scratch/all_cells/img1/r1_c0.png", "scratch/all_cells/img1/r1_c3.png", "scratch/all_cells/img1/r1_c6.png", "scratch/all_cells/img1/r1_c7.png",
                "scratch/all_cells/img2/r1_c0.png", "scratch/all_cells/img2/r1_c1.png", "scratch/all_cells/img2/r1_c5.png", "scratch/all_cells/img2/r1_c6.png", "scratch/all_cells/img2/r3_c2.png",
                "scratch/all_cells/img2/r6_c0.png", "scratch/all_cells/img2/r6_c4.png", "scratch/all_cells/img2/r6_c6.png", "scratch/all_cells/img2/r6_c7.png",
                "scratch/all_cells/img3/r6_c0.png", "scratch/all_cells/img3/r6_c3.png", "scratch/all_cells/img3/r6_c6.png", "scratch/all_cells/img3/r6_c7.png",
                "scratch/all_cells/img3/r1_c0.png", "scratch/all_cells/img3/r1_c4.png", "scratch/all_cells/img3/r1_c6.png", "scratch/all_cells/img3/r1_c7.png"
            ],
            'R': [
                "scratch/all_cells/img1/r7_c0.png", "scratch/all_cells/img1/r7_c7.png",
                "scratch/all_cells/img1/r0_c0.png", "scratch/all_cells/img1/r0_c7.png",
                "scratch/all_cells/img2/r0_c0.png", "scratch/all_cells/img2/r0_c7.png",
                "scratch/all_cells/img2/r7_c0.png", "scratch/all_cells/img2/r7_c7.png",
                "scratch/all_cells/img3/r7_c0.png", "scratch/all_cells/img3/r7_c7.png",
                "scratch/all_cells/img3/r0_c0.png", "scratch/all_cells/img3/r0_c7.png"
            ],
            'N': [
                "scratch/all_cells/img1/r7_c1.png", "scratch/all_cells/img1/r7_c6.png",
                "scratch/all_cells/img1/r0_c1.png", "scratch/all_cells/img1/r0_c6.png",
                "scratch/all_cells/img2/r0_c1.png", "scratch/all_cells/img2/r0_c6.png",
                "scratch/all_cells/img2/r7_c1.png", "scratch/all_cells/img2/r7_c6.png",
                "scratch/all_cells/img3/r7_c1.png", "scratch/all_cells/img3/r7_c6.png",
                "scratch/all_cells/img3/r0_c1.png", "scratch/all_cells/img3/r0_c6.png"
            ],
            'B': [
                "scratch/all_cells/img1/r7_c2.png", "scratch/all_cells/img1/r7_c5.png",
                "scratch/all_cells/img1/r0_c2.png", "scratch/all_cells/img1/r0_c5.png",
                "scratch/all_cells/img2/r0_c2.png", "scratch/all_cells/img2/r0_c5.png",
                "scratch/all_cells/img2/r7_c2.png", "scratch/all_cells/img2/r7_c5.png",
                "scratch/all_cells/img3/r7_c2.png", "scratch/all_cells/img3/r7_c5.png",
                "scratch/all_cells/img3/r0_c2.png", "scratch/all_cells/img3/r0_c5.png"
            ],
            'Q': [
                "scratch/all_cells/img1/r7_c3.png", "scratch/all_cells/img1/r0_c3.png",
                "scratch/all_cells/img2/r0_c4.png", "scratch/all_cells/img2/r7_c4.png",
                "scratch/all_cells/img3/r7_c3.png", "scratch/all_cells/img3/r0_c3.png"
            ],
            'K': [
                "scratch/all_cells/img1/r7_c4.png", "scratch/all_cells/img1/r0_c4.png",
                "scratch/all_cells/img2/r0_c3.png", "scratch/all_cells/img2/r7_c3.png",
                "scratch/all_cells/img3/r7_c4.png", "scratch/all_cells/img3/r0_c4.png"
            ]
        }
        
        self.templates = {}
        for cls_name, paths in self.class_samples.items():
            feats = []
            for p in paths:
                if os.path.exists(p):
                    img = cv2.imread(p)
                    feats.append(self._extract_feature(img))
            self.templates[cls_name] = feats

    def _extract_feature(self, cell_img):
        # Auf 48x48 normieren
        resized = cv2.resize(cell_img, (48, 48))
        gray = cv2.cvtColor(resized, cv2.COLOR_BGR2GRAY)
        
        # Streng den Kernbereich von 18 % bis 82 % schneiden (entfernt die Gitterlinien)
        core_gray = gray[9:39, 9:39]
        
        # Sobel-Gradient
        gx = cv2.Sobel(core_gray, cv2.CV_32F, 1, 0, ksize=3)
        gy = cv2.Sobel(core_gray, cv2.CV_32F, 0, 1, ksize=3)
        mag = np.sqrt(gx**2 + gy**2)
        
        norm = np.linalg.norm(mag)
        mag_norm = mag / (norm + 1e-5)
        
        return {
            'mag_norm': mag_norm,
            'core_gray': core_gray,
            'center_std': np.std(core_gray),
            'center_mean': np.mean(core_gray),
            'grad_mean': np.mean(mag)
        }

    def classify_board(self, cells_8x8):
        feats_8x8 = [[self._extract_feature(cells_8x8[r][c]) for c in range(8)] for r in range(8)]
        
        # 1. Leeres Feld von besetztem Feld unterscheiden
        occupied = []
        for r in range(8):
            for c in range(8):
                f = feats_8x8[r][c]
                # Ein leeres Feld hat weder Kanten noch Varianz im Inneren
                if f['center_std'] < 6.0 or f['grad_mean'] < 8.0:
                    continue
                    
                # Mit den 6 Figurenarten abgleichen und die ähnlichste Vorlage wählen
                best_cls = 'P'
                best_sim = -1e9
                for cls_name, t_list in self.templates.items():
                    for t in t_list:
                        # Reine Kosinus-Kreuzkorrelation (Cosine NCC)
                        cos_sim = float(np.sum(f['mag_norm'] * t['mag_norm']))
                        if cos_sim > best_sim:
                            best_sim = cos_sim
                            best_cls = cls_name
                            
                occupied.append((r, c, f, best_cls))
                
        if not occupied:
            return [['.' for _ in range(8)] for _ in range(8)]
            
        # 2. 2-Means-Clustering trennt Schwarz und Weiß ohne feste Schwellen
        means = [item[2]['center_mean'] for item in occupied]
        c1, c2 = min(means), max(means)
        for _ in range(10):
            g1 = [m for m in means if abs(m - c1) <= abs(m - c2)]
            g2 = [m for m in means if abs(m - c1) > abs(m - c2)]
            if g1: c1 = float(np.mean(g1))
            if g2: c2 = float(np.mean(g2))
            
        split_thresh = (c1 + c2) / 2.0
        
        # 3. Das 8x8-Brett aufbauen
        board_res = [['.' for _ in range(8)] for _ in range(8)]
        for r, c, f, cls_name in occupied:
            is_white = (f['center_mean'] >= split_thresh)
            symbol = cls_name if is_white else cls_name.lower()
            board_res[r][c] = symbol
            
        return board_res

print("UltraRobustDuolingoClassifier wurde geladen")
