import cv2
import numpy as np
import os
from test_locator_v3 import find_bottom_edge_and_board
from piece_classifier import DuolingoPieceClassifier

class DuolingoChessDetector:
    def __init__(self):
        self.classifier = DuolingoPieceClassifier()
        
    def detect_image(self, image_path):
        img = cv2.imread(image_path)
        if img is None:
            raise FileNotFoundError(f"Bild nicht gefunden: {image_path}")
            
        l, t, r, b = find_bottom_edge_and_board(img)
        step = (r - l) / 8.0
        
        raw_board = [] # 8x8 von der Bildschirmoberkante zur Unterkante
        for row in range(8):
            row_symbols = []
            for col in range(8):
                x1 = int(round(l + col * step))
                y1 = int(round(t + row * step))
                x2 = int(round(l + (col + 1) * step))
                y2 = int(round(t + (row + 1) * step))
                
                cell = img[y1:y2, x1:x2]
                is_white_square = ((row + col) % 2 == 0)
                symbol, is_white_piece = self.classifier.classify_cell(cell, is_white_square)
                row_symbols.append(symbol)
            raw_board.append(row_symbols)
            
        # Perspektive bestimmen (Orientation):
        # Farbzugehörigkeit der Figuren in den obersten und untersten 2 Reihen zählen
        top_white_count = sum(1 for r in range(2) for c in range(8) if raw_board[r][c].isupper())
        top_black_count = sum(1 for r in range(2) for c in range(8) if raw_board[r][c].islower())
        
        bottom_white_count = sum(1 for r in range(6, 8) for c in range(8) if raw_board[r][c].isupper())
        bottom_black_count = sum(1 for r in range(6, 8) for c in range(8) if raw_board[r][c].islower())
        
        # Stehen unten überwiegend weiße Figuren, ist es die Sicht von Weiß (normale Lage: Reihe 8 bis Reihe 1)
        # Stehen unten überwiegend schwarze Figuren, ist es die Sicht von Schwarz (gedreht: oben Reihe 1 von Weiß, unten Reihe 8 von Schwarz)
        is_white_perspective = (bottom_white_count >= bottom_black_count)
        
        if is_white_perspective:
            standard_board = raw_board # Bildschirmzeile 0 entspricht Reihe 8
            active_color = 'w'
        else:
            # Um 180 Grad drehen, damit die Matrix wieder von Reihe 8 bis Reihe 1 läuft
            standard_board = [row[::-1] for row in raw_board[::-1]]
            active_color = 'b'
            
        # FEN zusammensetzen
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
            'perspective': 'white' if is_white_perspective else 'black',
            'raw_board': raw_board,
            'standard_board': standard_board,
            'fen': board_fen,
            'full_fen': full_fen
        }

if __name__ == '__main__':
    detector = DuolingoChessDetector()
    for name in ["duolingo_1.jpeg", "duolingo_2.jpg", "duolingo_3.jpg"]:
        print(f"\n==================== Analyzing {name} ====================")
        res = detector.detect_image(name)
        print(f"Perspective: {res['perspective']}")
        print("Raw Screen Board:")
        for r in res['raw_board']:
            print(" ".join(r))
        print(f"Generated FEN: {res['fen']}")
        print(f"Full FEN: {res['full_fen']}")
