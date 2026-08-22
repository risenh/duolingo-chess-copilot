import cv2
import numpy as np
import os
from test_locator_v3 import find_bottom_edge_and_board
from robust_classifier import UltraRobustDuolingoClassifier
from engine_evaluator import evaluate_fen_cloud

def uci_to_coords(uci_move, is_white_perspective, board_rect):
    """
    Bildet einen UCI-Zug (z. B. 'e7e5', 'g1f3') auf Bildschirmkoordinaten ab: (x1, y1) -> (x2, y2)
    """
    l, t, r, b = board_rect
    step = (r - l) / 8.0
    
    from_sq = uci_move[:2]
    to_sq = uci_move[2:4]
    
    # Algebraische Notation zerlegen
    # 'a'~'h' -> col 0~7
    # '1'~'8' -> rank 1~8
    col_map = {c: i for i, c in enumerate('abcdefgh')}
    
    from_file = col_map[from_sq[0]]
    from_rank = int(from_sq[1])
    
    to_file = col_map[to_sq[0]]
    to_rank = int(to_sq[1])
    
    if is_white_perspective:
    # Weiß unten: Reihe 8 ist Bildschirmzeile 0, Reihe 1 ist Bildschirmzeile 7
    # Linie 'a' ist Bildschirmspalte 0, Linie 'h' ist Bildschirmspalte 7
        r1 = 8 - from_rank
        c1 = from_file
        r2 = 8 - to_rank
        c2 = to_file
    else:
    # Schwarz unten: Reihe 1 ist Bildschirmzeile 0, Reihe 8 ist Bildschirmzeile 7
    # Linie 'h' ist Bildschirmspalte 0, Linie 'a' ist Bildschirmspalte 7
        r1 = from_rank - 1
        c1 = 7 - from_file
        r2 = to_rank - 1
        c2 = 7 - to_file
        
    x1 = int(round(l + (c1 + 0.5) * step))
    y1 = int(round(t + (r1 + 0.5) * step))
    x2 = int(round(l + (c2 + 0.5) * step))
    y2 = int(round(t + (r2 + 0.5) * step))
    
    sq1_rect = (int(l + c1 * step), int(t + r1 * step), int(l + (c1+1) * step), int(t + (r1+1) * step))
    sq2_rect = (int(l + c2 * step), int(t + r2 * step), int(l + (c2+1) * step), int(t + (r2+1) * step))
    
    return (x1, y1), (x2, y2), sq1_rect, sq2_rect

def draw_overlay_suggestion(image, move_info, board_rect, is_white_persp):
    """
    Bildet die leuchtende halbtransparente Darstellung des Android-Overlays nach
    """
    vis = image.copy()
    overlay = image.copy()
    
    p1, p2, sq1, sq2 = uci_to_coords(move_info['best_move'], is_white_persp, board_rect)
    
    # 1. Startfeld hervorheben (halbtransparentes Blaugrün)
    cv2.rectangle(overlay, (sq1[0], sq1[1]), (sq1[2], sq1[3]), (0, 230, 115), -1)
    
    # 2. Zielfeld hervorheben (halbtransparentes Goldgelb)
    cv2.rectangle(overlay, (sq2[0], sq2[1]), (sq2[2], sq2[3]), (0, 215, 255), -1)
    
    # Halbtransparent überblenden
    cv2.addWeighted(overlay, 0.45, vis, 0.55, 0, vis)
    
    # 3. Weich leuchtenden Pfeil zeichnen
    # Äußerer Schein
    cv2.arrowedLine(vis, p1, p2, (0, 100, 0), 12, tipLength=0.25, line_type=cv2.LINE_AA)
    # Heller grüner Kern des Pfeils
    cv2.arrowedLine(vis, p1, p2, (0, 255, 128), 6, tipLength=0.25, line_type=cv2.LINE_AA)
    
    # 4. Infofeld über dem Brett zeichnen (Floating Pill)
    eval_text = f"Best: {move_info['best_move']} | Eval: {move_info['eval_cp']/100:+.2f}"
    l, t, r, b = board_rect
    pill_w = 340
    pill_h = 44
    pill_x = l + (r - l - pill_w) // 2
    pill_y = max(10, t - pill_h - 12)
    
    cv2.rectangle(vis, (pill_x, pill_y), (pill_x + pill_w, pill_y + pill_h), (20, 20, 20), -1)
    cv2.rectangle(vis, (pill_x, pill_y), (pill_x + pill_w, pill_y + pill_h), (0, 255, 128), 2)
    cv2.putText(vis, eval_text, (pill_x + 16, pill_y + 30), cv2.FONT_HERSHEY_SIMPLEX, 0.75, (255, 255, 255), 2, cv2.LINE_AA)
    
    return vis

def run_pipeline(img_path, out_path):
    print(f"\n==================== Running Pipeline on {img_path} ====================")
    img = cv2.imread(img_path)
    board_rect = find_bottom_edge_and_board(img)
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
    print(f"FEN: {full_fen}")
    
    # Berechnung durch die Engine
    move_info = evaluate_fen_cloud(full_fen)
    print(f"Stockfish Result: {move_info}")
    
    if 'best_move' in move_info:
        vis = draw_overlay_suggestion(img, move_info, board_rect, is_white_persp)
        cv2.imwrite(out_path, vis)
        print(f"Bild mit dem empfohlenen Zug erzeugt: {out_path}")

if __name__ == '__main__':
    os.makedirs("scratch/demo_results", exist_ok=True)
    run_pipeline("duolingo_1.jpeg", "scratch/demo_results/demo_duolingo_1.png")
    run_pipeline("duolingo_2.jpg", "scratch/demo_results/demo_duolingo_2.png")
    run_pipeline("duolingo_3.jpg", "scratch/demo_results/demo_duolingo_3.png")
