import cv2
import numpy as np
import os

class RobustSilhouetteClassifier:
    """
    Mehrskaliger Figurenklassifikator über die Vordergrundmaske (Silhouette) und eine Kantenpyramide
    Bleibt sowohl bei 36px als auch bei 155px Kantenlänge zuverlässig
    """
    def __init__(self):
        # Referenzbeispiele laden und daraus die reinen Umrisse gewinnen
        self.classes = ['P', 'R', 'N', 'B', 'Q', 'K']
        self.templates = {c: [] for c in self.classes}
        
        # Umrisse aus allen Beispielen sammeln
        sample_paths = {
            'P': ["scratch/all_cells/img1/r6_c0.png", "scratch/all_cells/img1/r6_c2.png", "scratch/all_cells/img3/r6_c0.png"],
            'R': ["scratch/all_cells/img1/r7_c0.png", "scratch/all_cells/img1/r7_c7.png", "scratch/all_cells/img3/r7_c0.png"],
            'N': ["scratch/all_cells/img1/r7_c1.png", "scratch/all_cells/img1/r7_c6.png", "scratch/all_cells/img3/r7_c1.png"],
            'B': ["scratch/all_cells/img1/r7_c2.png", "scratch/all_cells/img1/r7_c5.png", "scratch/all_cells/img3/r7_c2.png"],
            'Q': ["scratch/all_cells/img1/r7_c3.png", "scratch/all_cells/img3/r7_c3.png"],
            'K': ["scratch/all_cells/img1/r7_c4.png", "scratch/all_cells/img3/r7_c4.png"]
        }
        
        for cls_name, paths in sample_paths.items():
            for p in paths:
                if os.path.exists(p):
                    img = cv2.imread(p)
                    self.templates[cls_name].append(self._extract_silhouette(img))

    def _extract_silhouette(self, cell_img):
        # Einheitlich auf 36x36 skalieren (entspricht der niedrigsten Auflösung)
        resized = cv2.resize(cell_img, (36, 36))
        gray = cv2.cvtColor(resized, cv2.COLOR_BGR2GRAY)
        
        # Kernbereich von 20 % bis 80 % schneiden (etwa 7:29)
        core = gray[7:29, 7:29]
        
        # Mittleren Hintergrund aus den vier Ecken bestimmen
        ch, cw = core.shape
        bg_val = (np.mean(core[:4, :4]) + np.mean(core[:4, -4:]) + 
                  np.mean(core[-4:, :4]) + np.mean(core[-4:, -4:])) / 4.0
                  
        # Differenz zum Vordergrund
        diff = np.abs(core.astype(np.float32) - bg_val)
        
        # Umriss normieren
        norm = np.linalg.norm(diff)
        diff_norm = diff / (norm + 1e-5)
        
        # Waagerechtes Breitenprofil des Umrisses (22)
        h_profile = np.sum(diff_norm, axis=1)
        h_profile /= (np.linalg.norm(h_profile) + 1e-5)
        
        # Senkrechtes Profil (22)
        v_profile = np.sum(diff_norm, axis=0)
        v_profile /= (np.linalg.norm(v_profile) + 1e-5)
        
        return {
            'diff_norm': diff_norm,
            'h_prof': h_profile,
            'v_prof': v_profile,
            'std': np.std(core),
            'mean': np.mean(core),
            'energy': np.sum(diff)
        }

    def classify_board(self, cells_8x8):
        feats = [[self._extract_silhouette(cells_8x8[r][c]) for c in range(8)] for r in range(8)]
        
        # 1. Besetzte Felder bestimmen
        occupied = []
        for r in range(8):
            for c in range(8):
                f = feats[r][c]
        # Bei einem leeren Feld ist die Differenzenergie im Inneren sehr klein
                if f['std'] < 5.0 or f['energy'] < 1200:
                    continue
                    
                best_cls = 'P'
                best_sim = -1e9
                for cls_name, t_list in self.templates.items():
                    for t in t_list:
                        # Korrelation des 2D-Umrisses und der Profile
                        sim_2d = np.sum(f['diff_norm'] * t['diff_norm'])
                        sim_h = np.dot(f['h_prof'], t['h_prof'])
                        sim_v = np.dot(f['v_prof'], t['v_prof'])
                        
                        total_sim = sim_2d * 0.6 + sim_h * 0.2 + sim_v * 0.2
                        if total_sim > best_sim:
                            best_sim = total_sim
                            best_cls = cls_name
                            
                occupied.append((r, c, f, best_cls))
                
        if not occupied:
            return [['.' for _ in range(8)] for _ in range(8)]
            
        # 2. 2-Means-Clustering trennt Schwarz und Weiß
        means = [item[2]['mean'] for item in occupied]
        c1, c2 = min(means), max(means)
        for _ in range(10):
            g1 = [m for m in means if abs(m - c1) <= abs(m - c2)]
            g2 = [m for m in means if abs(m - c1) > abs(m - c2)]
            if g1: c1 = float(np.mean(g1))
            if g2: c2 = float(np.mean(g2))
            
        split_thresh = (c1 + c2) / 2.0
        
        # 3. Das 8x8-Ergebnis füllen
        board_res = [['.' for _ in range(8)] for _ in range(8)]
        for r, c, f, cls_name in occupied:
            is_white = (f['mean'] >= split_thresh)
            symbol = cls_name if is_white else cls_name.lower()
            board_res[r][c] = symbol
            
        return board_res

print("RobustSilhouetteClassifier wurde geladen")
