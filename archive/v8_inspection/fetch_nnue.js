// NNUE-Netz beschaffen: zuerst aus dem DroidFish-APK, bei nicht erreichbarem F-Droid über den offiziellen Link
// Aufruf: node fetch_nnue.js
'use strict';
const https = require('https');
const crypto = require('crypto');
const fs = require('fs');
const path = require('path');
const zlib = require('zlib');

const APK_URL = 'https://f-droid.org/repo/org.petero.droidfish_99.apk';
const APK_LOCAL = path.join(__dirname, 'droidfish.apk');
const NNUE_OUT_DIR = path.join(__dirname, '..', 'dulo', 'app', 'src', 'main', 'assets', 'nnue');
// Offizieller Link (den Dateinamen nennt die Fehlermeldung der Binary, er entspricht exakt dem eingebauten Standardnetz)
const NNUE_DIRECT = [
    { name: 'nn-5af11540bbfe.nnue', url: 'https://tests.stockfishchess.org/api/nn/nn-5af11540bbfe.nnue' }
];

function download(url, dest, redirects = 0) {
    return new Promise((resolve, reject) => {
        if (redirects > 5) return reject(new Error('too many redirects'));
        const req = https.get(url, { headers: { 'User-Agent': 'Mozilla/5.0' } }, (res) => {
            if ([301, 302, 303, 307, 308].includes(res.statusCode)) {
                res.resume();
                return resolve(download(new URL(res.headers.location, url).href, dest, redirects + 1));
            }
            if (res.statusCode !== 200) {
                res.resume();
                return reject(new Error('HTTP ' + res.statusCode + ' for ' + url));
            }
            const total = parseInt(res.headers['content-length'] || '0', 10);
            let got = 0;
            const ws = fs.createWriteStream(dest);
            res.on('data', (chunk) => {
                got += chunk.length;
                if (total > 0 && Math.floor(got / total * 20) !== Math.floor((got - chunk.length) / total * 20)) {
                    process.stdout.write(`\r  Fortschritt: ${(got / 1048576).toFixed(1)}/${(total / 1048576).toFixed(1)} MB`);
                }
            });
            res.pipe(ws);
            ws.on('finish', () => { ws.close(); console.log('\n  Download abgeschlossen: ' + dest); resolve(); });
            ws.on('error', reject);
        });
        req.on('error', reject);
    });
}

// Minimaler Zip-Parser: EOCD -> zentrales Verzeichnis -> lokaler Dateikopf
function readZipEntries(buf) {
    // Die EOCD-Signatur 0x06054b50 vom Dateiende her rückwärts suchen
    let eocd = -1;
    for (let i = buf.length - 22; i >= Math.max(0, buf.length - 22 - 65536); i--) {
        if (buf.readUInt32LE(i) === 0x06054b50) { eocd = i; break; }
    }
    if (eocd === -1) throw new Error('EOCD not found');
    const count = buf.readUInt16LE(eocd + 10);
    const cdOffset = buf.readUInt32LE(eocd + 16);
    const entries = [];
    let p = cdOffset;
    for (let i = 0; i < count; i++) {
        if (buf.readUInt32LE(p) !== 0x02014b50) throw new Error('bad central dir entry at ' + p);
        const method = buf.readUInt16LE(p + 10);
        const compSize = buf.readUInt32LE(p + 20);
        const uncompSize = buf.readUInt32LE(p + 24);
        const nameLen = buf.readUInt16LE(p + 28);
        const extraLen = buf.readUInt16LE(p + 30);
        const commentLen = buf.readUInt16LE(p + 32);
        const localOff = buf.readUInt32LE(p + 42);
        const name = buf.toString('utf8', p + 46, p + 46 + nameLen);
        entries.push({ name, method, compSize, uncompSize, localOff });
        p += 46 + nameLen + extraLen + commentLen;
    }
    return entries;
}

function extractEntry(buf, entry) {
    const p = entry.localOff;
    if (buf.readUInt32LE(p) !== 0x04034b50) throw new Error('bad local header for ' + entry.name);
    const nameLen = buf.readUInt16LE(p + 26);
    const extraLen = buf.readUInt16LE(p + 28);
    const dataStart = p + 30 + nameLen + extraLen;
    const raw = buf.subarray(dataStart, dataStart + entry.compSize);
    if (entry.method === 0) return Buffer.from(raw);
    if (entry.method === 8) return zlib.inflateRawSync(raw);
    throw new Error('unsupported compression method ' + entry.method + ' for ' + entry.name);
}

async function tryFromApk() {
    if (!fs.existsSync(APK_LOCAL)) {
        console.log('DroidFish-APK von ' + APK_URL + ' herunterladen ...');
        await download(APK_URL, APK_LOCAL);
    } else {
        console.log('Bereits geladenes APK wird verwendet: ' + APK_LOCAL);
    }
    const buf = fs.readFileSync(APK_LOCAL);
    const entries = readZipEntries(buf);
    console.log('Anzahl der Einträge im APK: ' + entries.length);
    const nnueEntries = entries.filter(e => e.name.toLowerCase().endsWith('.nnue'));
    if (nnueEntries.length === 0) {
        console.log('!! Keine .nnue-Datei im APK gefunden, Inhalt von assets:');
        entries.filter(e => e.name.startsWith('assets/')).forEach(e => console.log('  ' + e.name));
        return false;
    }
    fs.mkdirSync(NNUE_OUT_DIR, { recursive: true });
    for (const e of nnueEntries) {
        const outName = path.basename(e.name);
        const outPath = path.join(NNUE_OUT_DIR, outName);
        console.log('Entpacke ' + e.name + ' (' + (e.uncompSize / 1048576).toFixed(1) + ' MB) -> ' + outPath);
        const data = extractEntry(buf, e);
        if (data.length !== e.uncompSize) throw new Error('size mismatch for ' + e.name);
        fs.writeFileSync(outPath, data);
    }
    return true;
}

async function fromDirect() {
    fs.mkdirSync(NNUE_OUT_DIR, { recursive: true });
    for (const item of NNUE_DIRECT) {
        const outPath = path.join(NNUE_OUT_DIR, item.name);
        console.log('Lade vom offiziellen Link ' + item.url + ' ...');
        await download(item.url, outPath);
        const data = fs.readFileSync(outPath);
        const sha = crypto.createHash('sha256').update(data).digest('hex');
        console.log('  Größe: ' + (data.length / 1048576).toFixed(1) + ' MB | SHA-256: ' + sha);
        // Schutz vor heruntergeladenen HTML- oder JSON-Fehlerseiten: eine Binärdatei beginnt nicht mit Text, die ersten 4 Bytes eines NNUE-Netzes sind die Version 0x7AF32F84
        const head4 = data.readUInt32LE(0);
        const looksLikeText = ['<!DO', '<htm', '{', '<'].some(s => data.subarray(0, s.length).toString('latin1').startsWith(s));
        if (looksLikeText || data.length < 10 * 1024 * 1024) {
            throw new Error('Der Inhalt sieht nach einer Fehlerseite aus oder ist zu klein (head=0x' + head4.toString(16) + '), er wird nicht geschrieben');
        }
        console.log('  Kopfkennung: 0x' + head4.toString(16).toUpperCase() + (head4 === 0x7AF32F84 ? ' (gültige NNUE-Version)' : ' (keine übliche NNUE-Version, bitte prüfen)'));
    }
}

(async () => {
    let ok = false;
    try {
        ok = await tryFromApk();
    } catch (e) {
        console.log('APK-Quelle fehlgeschlagen: ' + e.message + ', Rückfall auf den offiziellen Link ...');
    }
    if (!ok) await fromDirect();
    console.log('NNUE liegt bereit unter: ' + NNUE_OUT_DIR);
})().catch((err) => { console.error('\nFehlgeschlagen: ' + err.message); process.exit(1); });
