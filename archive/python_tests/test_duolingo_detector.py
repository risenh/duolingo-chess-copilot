import cv2
import numpy as np
import os
import unittest
from test_locator_v3 import find_bottom_edge_and_board
from robust_classifier import UltraRobustDuolingoClassifier

class DuolingoPipelineV3:
    def __init__(self):
        self.classifier = UltraRobustDuolingoClassifier()
        
    def process_image(self, img_path):
        img = cv2.imread(img_path)
        if img is None:
            raise FileNotFoundError(f"Datei nicht gefunden: {img_path}")
            
        l, t, r, b = find_bottom_edge_and_board(img)
        step = (r - l) / 8.0
        
        cells_8x8 = []
        for row in range(8):
            row_cells = []
            for col in range(8):
                x1 = int(round(l + col * step))
                y1 = int(round(t + row * step))
                x2 = int(round(l + (col + 1) * step))
                y2 = int(round(t + (row + 1) * step))
                cell = img[y1:y2, x1:x2]
                row_cells.append(cell)
            cells_8x8.append(row_cells)
            
        raw_board = self.classifier.classify_board(cells_8x8)
        
        # Weiße und schwarze Figuren oben und unten zählen, um die Perspektive zu bestimmen
        top_white = sum(1 for r in range(2) for c in range(8) if raw_board[r][c].isupper())
        top_black = sum(1 for r in range(2) for c in range(8) if raw_board[r][c].islower())
        bot_white = sum(1 for r in range(6, 8) for c in range(8) if raw_board[r][c].isupper())
        bot_black = sum(1 for r in range(6, 8) for c in range(8) if raw_board[r][c].islower())
        
        is_white_persp = (bot_white >= bot_black)
        
        if is_white_persp:
            standard_board = raw_board
            active_color = 'w'
        else:
            # Bei Schwarz unten liegt unten Reihe 8 von Schwarz und oben Reihe 1 von Weiß
            # Um 180 Grad drehen, damit die FEN-Matrix wieder von Reihe 8 (Schwarz) bis Reihe 1 (Weiß) läuft
            standard_board = [row[::-1] for row in raw_board[::-1]]
            active_color = 'b'
            
        fen_rows = []
        for row in standard_board:
            fen_row = ''
            empty_count = 0
            for sym in row:
                if sym == '.':
                    empty_count += 1
                else:
                    if empty_count > 0:
                        fen_row += str(empty_count)
                        empty_count = 0
                    fen_row += sym
            if empty_count > 0:
                fen_row += str(empty_count)
            fen_rows.append(fen_row)
            
        board_fen = '/'.join(fen_rows)
        full_fen = f"{board_fen} {active_color} KQkq - 0 1"
        
        return {
            'rect': (l, t, r, b),
            'perspective': 'white' if is_white_persp else 'black',
            'raw_board': raw_board,
            'standard_board': standard_board,
            'board_fen': board_fen,
            'full_fen': full_fen
        }

class TestDuolingoChessDetector(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.pipeline = DuolingoPipelineV3()
        
    def test_duolingo_1_dark_initial(self):
        """Testfall 1: dunkles Design, Grundstellung (duolingo_1.jpeg)"""
        res = self.pipeline.process_image("duolingo_1.jpeg")
        print("\n--- Test 1: duolingo_1.jpeg ---")
        for r in res['raw_board']:
            print(" ".join(r))
        print("FEN:", res['board_fen'])
        self.assertEqual(res['perspective'], 'white')
        self.assertEqual(res['board_fen'], "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR")

    def test_duolingo_2_dark_e4_black_perspective(self):
        """Testfall 2: dunkles Design, e4 gespielt, Sicht von Schwarz (duolingo_2.jpg)"""
        res = self.pipeline.process_image("duolingo_2.jpg")
        print("\n--- Test 2: duolingo_2.jpg ---")
        for r in res['raw_board']:
            print(" ".join(r))
        print("Standard FEN:", res['board_fen'])
        self.assertEqual(res['perspective'], 'black')
        # Grundreihe von Schwarz
        self.assertEqual(res['raw_board'][7], ['r', 'n', 'b', 'k', 'q', 'b', 'n', 'r'])
        # Grundreihe von Weiß
        self.assertEqual(res['raw_board'][0], ['R', 'N', 'B', 'K', 'Q', 'B', 'N', 'R'])
        # Vorgerückter Bauer von Weiß
        self.assertEqual(res['raw_board'][3][2], 'P')

    def test_duolingo_3_light_initial(self):
        """Testfall 3: helles Design, Grundstellung (duolingo_3.jpg)"""
        res = self.pipeline.process_image("duolingo_3.jpg")
        print("\n--- Test 3: duolingo_3.jpg ---")
        for r in res['raw_board']:
            print(" ".join(r))
        print("FEN:", res['board_fen'])
        self.assertEqual(res['perspective'], 'white')
        self.assertEqual(res['board_fen'], "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR")

if __name__ == '__main__':
    unittest.main()
