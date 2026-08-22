import urllib.request
import zipfile
import io
import os

# V9 (Forensik aus bug_10): Fehlt der Engine das NNUE-Bewertungsnetz, beendet sie sich bei der ersten Suche selbst.
# Den Standarddateinamen nennt die Fehlermeldung der Binary; er muss exakt dem beim Kompilieren eingebauten Namen entsprechen.
NNUE_FILES = {
    'nn-5af11540bbfe.nnue': 'dulo/app/src/main/assets/nnue/nn-5af11540bbfe.nnue',
}
NNUE_DIRECT_URL_TMPL = 'https://tests.stockfishchess.org/api/nn/{name}'

def setup_stockfish():
    url = "https://f-droid.org/repo/org.petero.droidfish_99.apk"
    print(f"Downloading DroidFish APK from {url}...")
    
    req = urllib.request.Request(url, headers={'User-Agent': 'Mozilla/5.0'})
    with urllib.request.urlopen(req, timeout=30) as res:
        apk_data = res.read()
        
    zf = zipfile.ZipFile(io.BytesIO(apk_data))
    
    mapping = {
        'assets/arm64-v8a/stockfish': 'dulo/app/src/main/assets/bin/arm64-v8a/stockfish',
        'assets/armeabi-v7a/stockfish': 'dulo/app/src/main/assets/bin/armeabi-v7a/stockfish',
        'assets/x86_64/stockfish': 'dulo/app/src/main/assets/bin/x86_64/stockfish',
    }
    
    for src_entry, dest_path in mapping.items():
        os.makedirs(os.path.dirname(dest_path), exist_ok=True)
        data = zf.read(src_entry)
        with open(dest_path, 'wb') as f:
            f.write(data)
        print(f"Extracted {src_entry} -> {dest_path} (size: {len(data):,} bytes)")

    print("All Stockfish binaries extracted successfully!")
    return zf

def setup_nnue(zf=None):
    """NNUE-Netz beschaffen: zuerst aus dem DroidFish-APK, ersatzweise über den offiziellen Link"""
    apk_entries = set(zf.namelist()) if zf is not None else set()
    for name, dest_path in NNUE_FILES.items():
        os.makedirs(os.path.dirname(dest_path), exist_ok=True)
        data = None
        # Quelle 1: ein gleichnamiger .nnue-Eintrag im APK
        for entry in apk_entries:
            if os.path.basename(entry) == name:
                data = zf.read(entry)
                print(f"Extracted NNUE from APK entry {entry}")
                break
        # Quelle 2: offizieller Link
        if data is None:
            direct_url = NNUE_DIRECT_URL_TMPL.format(name=name)
            print(f"Downloading NNUE from {direct_url} ...")
            req = urllib.request.Request(direct_url, headers={'User-Agent': 'Mozilla/5.0'})
            with urllib.request.urlopen(req, timeout=300) as res:
                data = res.read()
        if len(data) < 10 * 1024 * 1024:
            raise RuntimeError(f"NNUE {name} ist zu klein ({len(data)} Bytes), der Download ist vermutlich fehlgeschlagen")
        with open(dest_path, 'wb') as f:
            f.write(data)
        print(f"NNUE ready: {dest_path} (size: {len(data):,} bytes)")

if __name__ == '__main__':
    zf = None
    try:
        zf = setup_stockfish()
    except Exception as e:
        print(f"APK-Quelle nicht verfügbar ({e}), das NNUE-Netz kommt über den offiziellen Link")
    setup_nnue(zf)
