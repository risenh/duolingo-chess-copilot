import cv2
import numpy as np
import os
from comb_locator import comb_filter_locate_board
from robust_classifier import UltraRobustDuolingoClassifier
from engine_evaluator import evaluate_fen_cloud
from run_demo import draw_overlay_suggestion

def evaluate_test_image(img_path, out_demo_path):
    print(f"\n==================== Pipeline Evaluation: {img_path} ====================")
    img = cv2.imread(img_path)
    if img is None:
        raise FileNotFoundError(img_path)
        
    board_rect = comb_filter_locate_board(img)
    l, t, r, b = board_rect
    step = (r - l) / 8.0
    
    classifier = UltraRobustDuolingoClassifier()
    cells_8x8 = []
    for row in range(8):
        row_cells = []
        for col in range(8):
            x1 = int(round(l + col * step))
            y1 = int(round(t + row * step))
            x2 = int(round(l + (col + 1) * step))
            y2 = int(round(t + (row + 1) * step))
            row_cells.append(img[y1:y2, x1:x2])
        cells_8x8.append(row_cells)
        
    raw_board = classifier.classify_board(cells_8x8)
    
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
    
    print(f"Perspective: {'White (Bottom)' if is_white_persp else 'Black (Bottom)'}")
    print("Detected Board Matrix:")
    for r in raw_board:
        print(" ".join(r))
    print(f"FEN: {full_fen}")
    
    # Besten Zug von der Engine holen
    move_info = evaluate_fen_cloud(full_fen)
    print(f"Stockfish Result: {move_info}")
    
    if 'best_move' in move_info:
        vis = draw_overlay_suggestion(img, move_info, board_rect, is_white_persp)
        cv2.imwrite(out_demo_path, vis)
        print(f"Darstellung gespeichert: {out_demo_path}")
        
    return {
        'fen': full_fen,
        'move_info': move_info,
        'raw_board': raw_board
    }

if __name__ == '__main__':
    os.makedirs("scratch/new_test_results", exist_ok=True)
    for name in ["duolingo_test_1.jfif", "duolingo_test_2.jfif", "duolingo_test_3.jfif"]:
        out_p = f"scratch/new_test_results/{name}_demo.png"
        evaluate_test_image(name, out_p)
