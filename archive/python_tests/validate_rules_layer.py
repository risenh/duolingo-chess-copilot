# -*- coding: utf-8 -*-
"""
Grundlegende Prüfebene (Vorgabe des Nutzers: keine oberflächlichen Korrekturen mehr, sondern Prüfung von Brett-, Figuren- und Regelebene her)
Prüft die bisherigen Fehlerfälle gegen die maßgebliche Regelbibliothek python-chess:
  A. Entspricht das erkannte FEN den Schachregeln (je genau ein König, Könige nicht benachbart, kein Bauer auf Reihe 1/8, Seite am Zug nicht im Schach)
  B. Ist der vom Overlay empfohlene Zug in dieser Stellung legal
Nur zur Diagnose, kein CI-Gatter (ohne Referenzbild zu einem historischen Fall wird nichts blockiert).
"""
import chess

CASES = [
    # (bug_id, Quelle, FEN, empfohlener Zug in UCI)
    ("bug_11", "Gerät (verunreinigter Frame)", "r3k2r/pp3ppp/5n2/4q3/1n6/2PP4/P1PBNPbP/R3K1R1 b KQkq - 0 1", "b4c2"),
    ("bug_16", "Offline (wie in Python, sauberer Frame)", "7r/1p5P/pr3p2/P7/4k3/7P/2P4P/b6K b KQkq - 0 1", "e4e5"),
    ("bug_17", "Offline (wie in Python, sauberer Frame)", "r1r3k1/pp3pp1/7p/4Rb2/3n1P2/P7/2P3PP/7K b KQkq - 0 1", "c8c5"),
    ("bug_18", "Gerätetelemetrie (Frame mit Overlay)", "r3k2r/pp1q2pp/2n2p2/3Qp3/1b2P3/2N1B2P/PPPNRKP1/R6B w KQkq - 0 1", "f2c2"),
    # Die FEN vom Gerät zu bug_13/14 wurden nicht gespeichert, die Werte aus dem verunreinigten Offline-Frame dienen nur der Strukturprüfung
    ("bug_13 (verunreinigter Offline-Frame)", "Offline", "2n5/3qp3/3P4/5N2/PPP2PPP/N1BQR1K1/2brrpk1/4R1bB b KQkq - 0 1", "e8c8"),
    ("bug_14 (verunreinigter Offline-Frame)", "Offline", "2RBQ3/1krrbb2/1KR4B/PPP1N2P/5P2/7q/3n3P/8 w KQkq - 0 1", "g8c8"),
]


def fen_sanity(fen: str):
    """Liefert die Liste der Probleme; eine leere Liste bedeutet strukturell gültig"""
    problems = []
    board = fen.split(" ")[0]
    rows = board.split("/")
    wk = sum(row.count("K") for row in rows)
    bk = sum(row.count("k") for row in rows)
    if wk != 1:
        problems.append(f"Anzahl weißer Könige={wk}")
    if bk != 1:
        problems.append(f"Anzahl schwarzer Könige={bk}")
    if rows[0].find("P") != -1 or rows[7].find("P") != -1 or rows[0].find("p") != -1 or rows[7].find("p") != -1:
        problems.append("Bauer auf Reihe 1 oder 8")
    try:
        b = chess.Board(board + " w - - 0 1")
        kw, kb = b.king(chess.WHITE), b.king(chess.BLACK)
        if kw is not None and kb is not None and chess.square_distance(kw, kb) < 2:
            problems.append("Könige benachbart (unmögliche Stellung)")
    except Exception:
        pass
    # Der König der Seite am Zug darf nicht im Schach stehen (zweites Feld des FEN)
    try:
        b = chess.Board(fen)
        side = chess.WHITE if b.turn == chess.WHITE else chess.BLACK
        if b.is_attacked_by(not side, b.king(side)):
            problems.append("Der König der Seite am Zug steht im Schach (der letzte Zug war illegal oder die Farbe wurde vertauscht)")
    except ValueError as e:
        problems.append(f"FEN konnte nicht gelesen werden: {e}")
    return problems


def move_check(fen: str, uci: str):
    """Prüft die Legalität des Zuges, Rückgabe (Ergebnis, Erläuterung)"""
    try:
        b = chess.Board(fen)
        mv = chess.Move.from_uci(uci)
        if mv in b.legal_moves:
            return "legal", ""
        if mv in b.pseudo_legal_moves:
            return "scheinbar legal", "nach dem Zug steht der eigene König im Schach"
        piece = b.piece_at(mv.from_square)
        if piece is None:
            return "illegal", f"auf dem Startfeld {chess.square_name(mv.from_square)} steht keine Figur"
        return "illegal", f"{piece.symbol()} von {chess.square_name(mv.from_square)} nach {chess.square_name(mv.to_square)} passt nicht zur Gangart"
    except Exception as e:
        return "Fehler", str(e)


def main():
    print("=" * 78)
    print("Grundlegende Prüfebene: Regelkonformität des erkannten FEN und Legalität des empfohlenen Zuges (Regelbibliothek python-chess)")
    print("=" * 78)
    for bug, src, fen, move in CASES:
        print(f"\n[{bug}] Quelle: {src}")
        print(f"  FEN: {fen}")
        problems = fen_sanity(fen)
        if problems:
            print(f"  Regelverstöße im FEN: {'; '.join(problems)}")
        else:
            print("  FEN-Regeln: bestanden (je ein König, nicht benachbart, Bauern korrekt, Seite am Zug nicht im Schach)")
        if move:
            verdict, note = move_check(fen, move)
            print(f"  Empfohlener Zug {move}: {verdict}{(' | ' + note) if note else ''}")
    print("\n[INFO] Prüfung abgeschlossen (nur Diagnose, kein CI-Gatter)")


if __name__ == "__main__":
    main()
