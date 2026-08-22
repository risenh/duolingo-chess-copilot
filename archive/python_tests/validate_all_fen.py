import sys
import os
sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

import cv2
import numpy as np
from tools.test_full_pipeline_v2 import fast_sat_locate_board
from tools.robust_classifier import UltraRobustDuolingoClassifier

def sanitize_board_py(board):
    res = [list(row) for row in board]
    # 1. Kein Bauer auf Reihe 1 oder 8
    for c in range(8):
        if res[0][c] in ('P', 'p'): res[0][c] = '.'
        if res[7][c] in ('P', 'p'): res[7][c] = '.'
        
    # 2. Obergrenzen der Figurenanzahl
    piece_counts = {}
    max_limits = {'K': 1, 'k': 1, 'Q': 1, 'q': 1, 'R': 2, 'r': 2, 'B': 2, 'b': 2, 'N': 2, 'n': 2, 'P': 8, 'p': 8}
    for r in range(8):
        for c in range(8):
            p = res[r][c]
            if p == '.': continue
            cnt = piece_counts.get(p, 0) + 1
            piece_counts[p] = cnt
            if cnt > max_limits.get(p, 8):
                is_white = p.isupper()
                cands = ['R', 'B', 'N'] if is_white else ['r', 'b', 'n']
                fallback = '.'
                for cand in cands:
                    if piece_counts.get(cand, 0) < max_limits[cand]:
                        fallback = cand
                        piece_counts[cand] = piece_counts.get(cand, 0) + 1
                        break
                res[r][c] = fallback
                
    # 3. Eindeutigkeit beider Könige
    white_kings = sum(row.count('K') for row in res)
    black_kings = sum(row.count('k') for row in res)
    
    if white_kings == 0:
        placed = False
        for r in range(7, 5, -1):
            for c in (3, 4):
                if res[r][c] == '.' or res[r][c].isupper():
                    res[r][c] = 'K'
                    placed = True
                    break
            if placed: break
        if not placed:
            res[7][4] = 'K'
            
    if black_kings == 0:
        placed = False
        for r in range(0, 2):
            for c in (3, 4):
                if res[r][c] == '.' or res[r][c].islower():
                    res[r][c] = 'k'
                    placed = True
                    break
            if placed: break
        if not placed:
            res[0][4] = 'k'
            
    # 4. Zweitprüfung
    final_counts = {}
    for r in range(8):
        for c in range(8):
            p = res[r][c]
            if p == '.': continue
            cnt = final_counts.get(p, 0) + 1
            final_counts[p] = cnt
            if cnt > max_limits.get(p, 8):
                res[r][c] = '.'
                
    return res

def compress_row_py(row):
    sb = []
    empty = 0
    for p in row:
        if p == '.':
            empty += 1
        else:
            if empty > 0:
                sb.append(str(empty))
                empty = 0
            sb.append(p)
    if empty > 0:
        sb.append(str(empty))
    return "".join(sb)

def build_fen_py(board, is_white_perspective):
    std_board = board if is_white_perspective else [[board[7 - r][7 - c] for c in range(8)] for r in range(8)]
    board_fen = "/".join(compress_row_py(row) for row in std_board)
    active = "w" if is_white_perspective else "b"
    full_fen = f"{board_fen} {active} KQkq - 0 1"
    return board_fen, full_fen

def validate_all():
    classifier = UltraRobustDuolingoClassifier()
    images = ["duolingo_1.jpeg", "duolingo_2.jpg", "duolingo_3.jpg", "duolingo_test_1.jfif", "duolingo_test_2.jfif"]
    all_passed = True
    
    for img_name in images:
        if not os.path.exists(img_name):
            continue
        img = cv2.imread(img_name)
        l, t, r, b = fast_sat_locate_board(img)
        step = (r - l) / 8.0
        
        cells = []
        for row in range(8):
            row_cells = []
            for col in range(8):
                cx1 = int(l + col * step)
                cy1 = int(t + row * step)
                cx2 = int(l + (col + 1) * step)
                cy2 = int(t + (row + 1) * step)
                cell_crop = img[cy1:cy2, cx1:cx2]
                row_cells.append(cell_crop)
            cells.append(row_cells)
            
        raw_board = classifier.classify_board(cells)
        sanitized = sanitize_board_py(raw_board)
        
        # Perspektive bestimmen
        top_white = sum(1 for row in sanitized[:2] for p in row if p.isupper())
        top_black = sum(1 for row in sanitized[:2] for p in row if p.islower())
        bot_white = sum(1 for row in sanitized[6:] for p in row if p.isupper())
        bot_black = sum(1 for row in sanitized[6:] for p in row if p.islower())
        is_white = bot_white >= bot_black if (bot_white + bot_black) > 0 else True
        
        board_fen, full_fen = build_fen_py(sanitized, is_white)
        
        print(f"\n=================== [{img_name}] ===================")
        print(f"Board Rect: L={l}, T={t}, R={r}, B={b}, Size={r-l}x{b-t}")
        print(f"Perspective: {'White' if is_white else 'Black'}")
        print(f"Board FEN: {board_fen}")
        print(f"Full FEN:  {full_fen}")
        
        # Prüfung nach den Lichess-Regeln
        ranks = board_fen.split('/')
        if len(ranks) != 8:
            print(f"  [ERROR] Rank count != 8: {len(ranks)}")
            all_passed = False
            
        white_kings = board_fen.count('K')
        black_kings = board_fen.count('k')
        if white_kings != 1 or black_kings != 1:
            print(f"  [ERROR] Invalid King counts on board: White={white_kings}, Black={black_kings}")
            all_passed = False
            
        if 'P' in ranks[0] or 'p' in ranks[0] or 'P' in ranks[7] or 'p' in ranks[7]:
            print(f"  [ERROR] Pawns on rank 1 or 8: Top={ranks[0]}, Bottom={ranks[7]}")
            all_passed = False
            
        for idx, rk in enumerate(ranks):
            cnt = sum(int(ch) if ch.isdigit() else 1 for ch in rk)
            if cnt != 8:
                print(f"  [ERROR] Rank {8 - idx} total squares != 8: {rk}")
                all_passed = False

    if not all_passed:
        print("\n[FAIL] FEN validation failed!")
        sys.exit(1)
    else:
        print("\n[SUCCESS] All benchmark images passed 100% Lichess standard FEN validation!")

if __name__ == '__main__':
    validate_all()
