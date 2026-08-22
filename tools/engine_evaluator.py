import urllib.request
import urllib.parse
import json
import time

def evaluate_fen_cloud(fen):
    """
    Holt den besten Zug und die Bewertung über die offizielle Stockfish-Cloud-API
    """
    encoded_fen = urllib.parse.quote(fen)
    url = f"https://lichess.org/api/cloud-eval?fen={encoded_fen}"
    
    req = urllib.request.Request(
        url,
        headers={'User-Agent': 'DuLo/1.0'}
    )
    
    try:
        start_t = time.time()
        with urllib.request.urlopen(req, timeout=3.0) as response:
            data = json.loads(response.read().decode())
            elapsed = (time.time() - start_t) * 1000
            
            pvs = data.get('pvs', [])
            if pvs:
                best_move_uci = pvs[0].get('moves', '').split(' ')[0]
                cp = pvs[0].get('cp', 0)
                mate = pvs[0].get('mate', None)
                depth = data.get('depth', 0)
                return {
                    'best_move': best_move_uci,
                    'eval_cp': cp,
                    'mate': mate,
                    'depth': depth,
                    'latency_ms': elapsed,
                    'source': 'Stockfish 16+ Cloud'
                }
    except Exception as e:
        return {'error': str(e)}

if __name__ == '__main__':
    test_fen = "rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq - 0 1"
    print(f"Testing Engine on FEN: {test_fen}")
    res = evaluate_fen_cloud(test_fen)
    print("Engine Result:", res)
