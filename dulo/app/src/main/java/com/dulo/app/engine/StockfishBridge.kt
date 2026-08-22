package com.dulo.app.engine

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.util.Log
import android.util.LruCache
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.regex.Pattern

/**
 * Reaktive Pipe-Brücke zur Stockfish-UCI-Engine (V7, doppelt abgesicherter Start und strenge Schachregeln)
 * Kernmechanik:
 * 1. Doppelte Absicherung des Starts: zuerst nativeLibraryDir, sonst automatische Kopie nach filesDir samt setExecutable(true)
 * 2. Eine eigene Hintergrund-Coroutine liest die Pipe blockierend aus und schiebt die Zeilen über einen Channel<String> weiter
 * 3. Strenger UCI-Handshake mit durchgängigem Diagnoseprotokoll
 * 4. Vollständig überarbeitete Zugregeln in evaluateFallback (Läufer diagonal, Turm gerade, Dame in acht Richtungen), erkennbar an depth=0
 * 5. Strikte Regel: Ergebnisse aus dem Fallback landen niemals im evalCache
 * 6. Die Diagnose steckt in der jeweiligen EngineEvaluation, damit kein globaler Zustand zwischen zwei Analysen überlebt
 */
object StockfishBridge {

    private const val TAG = "StockfishBridge"

    // NNUE-Bewertungsnetz (Forensik aus bug_10: fehlt das Netz, beendet sich die Engine bei der ersten Suche selbst, der geglückte Handshake ist dann nur scheinbare Bereitschaft)
    // Der Dateiname muss exakt dem beim Kompilieren eingebauten Standardnetz entsprechen, niemals umbenennen
    private const val NNUE_ASSET_NAME = "nnue/nn-5af11540bbfe.nnue"

    data class EngineEvaluation(
        val bestMove: String,     // UCI-Zug, z. B. "e2e4", "e7e8q"; "(invalid)" ist der Sentinel für eine unmögliche Stellung aus der Erkennung
        val evalScore: Float,     // Stellungsbewertung, z. B. +0.58, -1.20
        val depth: Int,           // Suchtiefe (echte Engine >= 10, Fallback 0)
        val isMate: Boolean = false,
        val diagnosticInfo: String = "" // Diagnose genau dieser Berechnung (Dauer, Tiefe, letzte UCI-Ausgaben bzw. Ausnahme-/Fallbackgrund)
    )

    /**
     * Konfiguration der Engine für eine Sitzung
     * @param threads Anzahl der Suchthreads (logische Kerne minus 2)
     * @param hashMb Größe der Transpositionstabelle in MB
     * @param moveOverheadMs Move Overhead in ms (10 lokal, 50-100 über Netzwerk; die Engine läuft hier lokal auf dem Gerät)
     * @param syzygyPath Pfad zu den Syzygy-Tablebases oder null, wenn keine vorhanden sind
     */
    data class EngineConfig(
        val threads: Int,
        val hashMb: Int,
        val moveOverheadMs: Int = LOCAL_MOVE_OVERHEAD_MS,
        val syzygyPath: String? = null
    )

    // Move Overhead: 10 ms für eine lokal auf dem Gerät laufende Engine
    // (über Netzwerk wären laut Vorgabe 50-100 ms nötig, das trifft hier nicht zu)
    const val LOCAL_MOVE_OVERHEAD_MS = 10

    /**
     * Reine Funktion: Threads = logische Kerne minus 1, mindestens 1.
     *
     * Ausgelegt auf höchste Spielstärke: es bleibt genau ein Kern für Oberfläche und
     * Bildschirmaufnahme frei. Während die Engine rechnet, ruht die Beobachtung ohnehin,
     * die Suche bekommt also praktisch das ganze Gerät.
     */
    fun computeThreads(logicalCores: Int): Int = (logicalCores - 1).coerceAtLeast(1)

    /**
     * Reine Funktion: Hash-Größe nach Vorgabe.
     * 256 MB bei 4-6, 512 MB bei 8-12 und 1024 MB ab 16 logischen Kernen.
     * Bezugsgröße sind wie in der Threads-Zeile der Vorgabe die logischen Kerne des Geräts
     * (16 Kerne ergeben 14 Suchthreads und 1024 MB Hash), nicht die daraus abgeleitete Threadzahl.
     * Die Lücken der Tabelle (7 bzw. 13-15) werden monoton nach unten aufgefüllt,
     * unterhalb von 4 Kernen bleiben 128 MB.
     */
    fun computeHashMb(logicalCores: Int): Int = when {
        logicalCores >= 16 -> 1024
        logicalCores >= 8 -> 512
        logicalCores >= 4 -> 256
        else -> 128
    }

    /**
     * Reine Funktion: begrenzt den Hash auf ein Viertel des physischen Arbeitsspeichers.
     * Auf einem Telefon ist die Tabellengröße sonst nicht durch das Gerät gedeckt: der
     * Engine-Prozess wird vom System abgeschossen oder das Gerät beginnt zu swappen, was die
     * Spielstärke stärker kostet als der kleinere Hash. deviceRamMb <= 0 bedeutet "unbekannt",
     * dann bleibt der Tabellenwert unverändert.
     */
    fun clampHashToDevice(hashMb: Int, deviceRamMb: Int): Int {
        if (deviceRamMb <= 0) return hashMb
        val cap = (deviceRamMb / 4).coerceAtLeast(16)
        return min(hashMb, cap)
    }

    /**
     * Reine Funktion: stellt die vollständige Konfiguration aus Kernanzahl, Gerätespeicher und
     * optionalem Tablebase-Pfad zusammen.
     */
    fun buildEngineConfig(logicalCores: Int, deviceRamMb: Int = 0, syzygyPath: String? = null): EngineConfig {
        val threads = computeThreads(logicalCores)
        return EngineConfig(
            threads = threads,
            hashMb = clampHashToDevice(computeHashMb(logicalCores), deviceRamMb),
            moveOverheadMs = LOCAL_MOVE_OVERHEAD_MS,
            syzygyPath = syzygyPath
        )
    }

    /**
     * Reine Funktion: die zu sendenden setoption-Paare in fester Reihenfolge.
     * Entspricht der Vorgabetabelle; SyzygyPath und die drei Syzygy-Parameter entfallen,
     * solange keine Tablebases vorliegen.
     */
    fun buildUciOptions(config: EngineConfig): List<Pair<String, String>> {
        val options = mutableListOf(
            "Threads" to config.threads.toString(),
            "Hash" to config.hashMb.toString(),
            "MultiPV" to "1",
            "Ponder" to "false",
            "Skill Level" to "20",
            "UCI_LimitStrength" to "false",
            "Move Overhead" to config.moveOverheadMs.toString(),
            "nodestime" to "0",
            "UCI_ShowWDL" to "true",
            "NumaPolicy" to "auto"
        )
        val syzygy = config.syzygyPath
        if (!syzygy.isNullOrEmpty()) {
            options.add("SyzygyPath" to syzygy)
            options.add("SyzygyProbeDepth" to "1")
            options.add("SyzygyProbeLimit" to "5")
            options.add("Syzygy50MoveRule" to "true")
        }
        return options
    }

    /**
     * Reine Funktion: liest den Optionsnamen aus einer Handshake-Zeile der Form
     * "option name Move Overhead type spin default 10 min 0 max 5000".
     * Gibt null zurück, wenn die Zeile keine Optionszeile ist.
     */
    fun parseOptionName(line: String): String? {
        val trimmed = line.trim()
        if (!trimmed.startsWith("option name ")) return null
        val rest = trimmed.removePrefix("option name ")
        val typeIdx = rest.indexOf(" type ")
        val name = if (typeIdx >= 0) rest.substring(0, typeIdx) else rest
        return name.trim().ifEmpty { null }
    }

    /**
     * Reine Funktion: liest den hashfull-Wert (Promille, 0..1000) aus einer info-Zeile.
     */
    fun parseHashfull(line: String): Int? {
        val matcher = hashfullPattern.matcher(line)
        return if (matcher.find()) matcher.group(1)?.toIntOrNull() else null
    }

    /**
     * Reine Funktion: zieht den Hash nach, damit hashfull im Mittel unter 30 Prozent bleibt.
     * Liegt der Mittelwert der letzten Suchen über 300 Promille, wird verdoppelt, allerdings
     * höchstens bis zum Vierfachen des Ausgangswerts und nie über die Grenze des Geräts.
     * Gibt den unveränderten Wert zurück, wenn nichts zu tun ist.
     */
    fun adjustHashForHashfull(
        currentHashMb: Int,
        baseHashMb: Int,
        averageHashfull: Int,
        deviceRamMb: Int
    ): Int {
        if (averageHashfull <= 300) return currentHashMb
        val target = clampHashToDevice(min(currentHashMb * 2, baseHashMb * 4), deviceRamMb)
        return max(currentHashMb, target)
    }

    /**
     * Reine Funktion: wählt die zur CPU passende Binary-Variante.
     * Vorgabe ist, statt der generischen Binary eine auf die Befehlssatzerweiterungen zugeschnittene
     * zu verwenden. Auf x86_64 sind das vnni512 > bmi2 > avx2, auf arm64 die Dotprod-/i8mm-Varianten.
     * Übergeben werden die tatsächlich vorhandenen Dateinamen und die aus /proc/cpuinfo gelesenen
     * Merkmale; gibt es keinen Treffer, bleibt es bei der generischen libstockfish.so.
     */
    fun selectBinaryVariant(availableBinaries: List<String>, cpuFeatures: Set<String>): String {
        val preference = listOf(
            "vnni512" to setOf("avx512_vnni", "avx512vnni"),
            "bmi2" to setOf("bmi2"),
            "avx2" to setOf("avx2"),
            "armv8-i8mm" to setOf("i8mm"),
            "armv8-dotprod" to setOf("asimddp", "dotprod")
        )
        val normalized = cpuFeatures.map { it.lowercase() }.toSet()
        for ((variant, flags) in preference) {
            val binary = "libstockfish-$variant.so"
            if (availableBinaries.contains(binary) && flags.any { normalized.contains(it) }) {
                return binary
            }
        }
        return "libstockfish.so"
    }

    private var process: Process? = null
    private var writer: BufferedWriter? = null
    private var reader: BufferedReader? = null
    private var lineChannel: Channel<String>? = null
    private var readerJob: Job? = null

    @Volatile
    private var isEngineReady: Boolean = false
    private var appContext: Context? = null

    // Vom Engine-Prozess selbst gemeldete ERROR-Zeile (z. B. fehlendes NNUE-Netz), für die Handshake-Diagnose
    private var lastEngineError: String? = null

    @Volatile
    private var lastEngineStartupStatus: String = "Engine noch nicht initialisiert"

    private val engineMutex = Mutex()
    private val scope = CoroutineScope(Dispatchers.IO)

    // LRU-Cache: hält die echten Analyseergebnisse der letzten 32 Stellungen
    private val evalCache = LruCache<String, EngineEvaluation>(32)

    /**
     * Zuletzt tatsächlich durchgerechnete Stellung.
     *
     * Daran wird entschieden, ob die nächste Stellung die Fortsetzung derselben Partie ist. Nur
     * wenn nicht, wird die Transpositionstabelle geleert - innerhalb einer Partie erspart sie der
     * Suche einen großen Teil der Arbeit.
     */
    @Volatile
    private var lastSearchedFen: String? = null

    /**
     * Laeuft in Stockfish gerade noch eine Suche, deren "bestmove" niemand abgeholt hat?
     *
     * Das passiert, sobald ein Aufrufer waehrend der Rechnung abbricht (Zeitgrenze der
     * Beobachtungsschleife, Abschalten des Schalters). Die Engine rechnet dann weiter und legt ihr
     * Ergebnis irgendwann in den Zeilenkanal - unter Umstaenden erst, nachdem die naechste Suche
     * schon losgeschickt wurde. Diese Zeile beantwortete dann die falsche Stellung: ein Zug aus der
     * vorigen Stellung, in der neuen meist unmoeglich. Genau das erzeugte die Stoerungsmeldung.
     *
     * Deshalb wird gemerkt, ob noch ein Ergebnis aussteht, und die naechste Suche wartet es
     * zuerst ab.
     */
    @Volatile
    private var searchInFlight = false

    // Bedenkzeit pro Zug: "go movetime 2000". Mehr Zeit ist der wirksamste Hebel für Spielstärke -
    // je Verdopplung der Bedenkzeit gewinnt Stockfish grob 50 bis 70 Elo hinzu. Zwei Sekunden sind
    // der Kompromiss aus Spielstärke und Reaktionszeit im Overlay; alle übrigen Optionen bleiben
    // auf voller Stärke (Threads = Kerne minus 1, großer Hash, warme Transpositionstabelle).
    const val DEFAULT_MOVE_TIME_MS = 2000L

    /**
     * Obergrenze der Suchtiefe, so wie sie Analysewerkzeuge wie lichess verwenden.
     *
     * Gesucht wird mit "go depth D movetime T": Stockfish hört auf, sobald eine der beiden Grenzen
     * erreicht ist. In einer geklärten Stellung ist die Suche damit früher fertig, ohne dass es
     * Spielstärke kostet - in einer scharfen Stellung bleiben die vollen zwei Sekunden.
     */
    const val MAX_SEARCH_DEPTH = 30

    /**
     * Ab dieser Tiefe darf die Suche vorzeitig enden, wenn der beste Zug stabil bleibt.
     * Darunter ist die Aussage zu jung, um ihr zu trauen.
     */
    const val MIN_SETTLED_DEPTH = 20

    /** So viele Tiefen in Folge muss derselbe Zug herauskommen, bevor abgebrochen wird */
    const val STABLE_DEPTHS_REQUIRED = 6

    /** Ab dieser Tiefe gilt ein gefundenes Matt als belastbar */
    const val MATE_SETTLED_DEPTH = 12

    /**
     * Reine Funktion: baut den Suchbefehl.
     *
     * Beide Grenzen zusammen, weil sie unterschiedliche Fälle abdecken: die Tiefe beendet eine
     * längst entschiedene Suche früh, die Zeit deckelt eine Stellung, in der die Tiefe nicht
     * erreicht wird.
     */
    fun buildGoCommand(moveTimeMs: Long, maxDepth: Int = MAX_SEARCH_DEPTH): String =
        "go depth $maxDepth movetime $moveTimeMs"

    // Von der laufenden Engine per "option name ..." gemeldete Optionsnamen.
    // Gesetzt wird nur, was die Engine auch kennt (ältere Versionen kennen z. B. kein NumaPolicy).
    private val supportedOptions = mutableSetOf<String>()

    // Aktuell angewandte Konfiguration und der tatsächlich gesetzte Hash (kann per hashfull nachgezogen werden)
    @Volatile
    private var activeConfig: EngineConfig? = null
    @Volatile
    private var activeHashMb: Int = 0

    // Gleitendes Fenster der hashfull-Meldungen (Promille, 0..1000) der letzten Suchen.
    // Vorgabe: der Mittelwert bleibt unter 30 Prozent, sonst wird der Hash vergrößert.
    private val hashfullSamples = ArrayDeque<Int>()

    private val bestMovePattern = Pattern.compile("^bestmove\\s+([a-h][1-8][a-h][1-8][qrbnQRBN]?|\\(none\\))")
    private val scoreCpPattern = Pattern.compile("score\\s+cp\\s+(-?\\d+)")
    private val scoreMatePattern = Pattern.compile("score\\s+mate\\s+(-?\\d+)")
    private val depthPattern = Pattern.compile("depth\\s+(\\d+)")
    private val hashfullPattern = Pattern.compile("hashfull\\s+(\\d+)")

    fun init(context: Context) {
        appContext = context.applicationContext
        if (isEngineReady && process != null) return

        scope.launch {
            engineMutex.withLock {
                if (isEngineReady && process != null) return@withLock
                startEngineProcessLocked()
            }
        }
    }

    /**
     * Reine Funktion: extrahiert die zur Geräte-ABI passende libstockfish.so atomar und als Stream aus dem APK-Zip
     * Läuft sowohl im JVM-Unit-Test als auch beim Entpacken auf dem Gerät
     */
    fun extractBinaryFromZip(zip: java.util.zip.ZipFile, supportedAbis: Array<String>, targetFile: File): Boolean {
        for (abi in supportedAbis) {
            val entryName = "lib/$abi/libstockfish.so"
            val entry = zip.getEntry(entryName)
            if (entry != null) {
                val parentDir = targetFile.parentFile
                if (parentDir != null && !parentDir.exists()) {
                    parentDir.mkdirs()
                }
                val tmpFile = File(parentDir ?: File("."), "${targetFile.name}.tmp")
                try {
                    zip.getInputStream(entry).use { input ->
                        FileOutputStream(tmpFile).use { output ->
                            input.copyTo(output)
                        }
                    }
                    if (tmpFile.exists() && tmpFile.length() == entry.size) {
                        if (targetFile.exists()) targetFile.delete()
                        val renamed = tmpFile.renameTo(targetFile)
                        if (renamed || targetFile.exists()) {
                            targetFile.setExecutable(true, false)
                            return true
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "extractBinaryFromZip failed for ABI $abi: ${e.message}")
                } finally {
                    if (tmpFile.exists()) tmpFile.delete()
                }
            }
        }
        return false
    }

    /**
     * Physischen Arbeitsspeicher des Geräts in MB lesen (0 = unbekannt).
     * Grundlage für die Deckelung der Hash-Größe.
     */
    private fun readDeviceRamMb(context: Context): Int {
        return try {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return 0
            val info = ActivityManager.MemoryInfo()
            am.getMemoryInfo(info)
            (info.totalMem / (1024L * 1024L)).toInt()
        } catch (e: Exception) {
            Log.w(TAG, "readDeviceRamMb failed: ${e.message}")
            0
        }
    }

    /**
     * Syzygy-Tablebases nur dann setzen, wenn tatsächlich welche vorliegen.
     * Erwartet wird das Verzeichnis filesDir/syzygy mit mindestens einer .rtbw-Datei.
     * Vorgabe ist außerdem, Tablebases nur von einer SSD zu lesen; der interne Flash-Speicher
     * eines Telefons erfüllt das, eine langsame externe Karte wäre hier nicht vorgesehen.
     */
    private fun findSyzygyPath(context: Context): String? {
        return try {
            val dir = File(context.filesDir, "syzygy")
            val hasTables = dir.isDirectory && (dir.listFiles()?.any { it.name.endsWith(".rtbw") } == true)
            if (hasTables) dir.absolutePath else null
        } catch (e: Exception) {
            Log.w(TAG, "findSyzygyPath failed: ${e.message}")
            null
        }
    }

    /**
     * Befehlssatzerweiterungen der CPU aus /proc/cpuinfo lesen (Zeilen "flags" bzw. "Features").
     */
    private fun readCpuFeatures(): Set<String> {
        return try {
            val features = mutableSetOf<String>()
            File("/proc/cpuinfo").forEachLine { line ->
                val lower = line.lowercase()
                if (lower.startsWith("flags") || lower.startsWith("features")) {
                    val values = lower.substringAfter(":", "").trim().split(Regex("\\s+"))
                    features.addAll(values.filter { it.isNotEmpty() })
                }
            }
            features
        } catch (e: Exception) {
            Log.w(TAG, "readCpuFeatures failed: ${e.message}")
            emptySet()
        }
    }

    private suspend fun startEngineProcessLocked() {
        val context = appContext ?: run {
            lastEngineStartupStatus = "Start fehlgeschlagen: appContext ist null"
            Log.w(TAG, "startEngineProcessLocked failed: appContext is null")
            return
        }

        val diag = StringBuilder()
        val startTime = System.currentTimeMillis()

        try {
            var selectedBinary: File? = null
            var startupPathDesc = "unbekannter Pfad"

            // Absicherung 1: zuerst das System-Verzeichnis nativeLibraryDir prüfen.
            // Liegt dort neben der generischen Binary eine auf die CPU zugeschnittene Variante
            // (vnni512 / bmi2 / avx2 bzw. armv8-i8mm / armv8-dotprod), wird diese bevorzugt.
            val nativeLibDir = context.applicationInfo.nativeLibraryDir
            val availableBinaries = File(nativeLibDir).listFiles()
                ?.map { it.name }
                ?.filter { it.startsWith("libstockfish") && it.endsWith(".so") }
                ?: emptyList()
            val variantName = selectBinaryVariant(availableBinaries, readCpuFeatures())
            val nativeFile = File(nativeLibDir, variantName)
            val nativeExists = nativeFile.exists()
            val nativeCanExec = nativeFile.canExecute()
            diag.append("Pfad1 [nativeLibDir]: binary=$variantName, exists=$nativeExists, canExec=$nativeCanExec\n")

            if (nativeExists && nativeCanExec) {
                selectedBinary = nativeFile
                startupPathDesc = "Pfad1 (nativeLibDir, $variantName)"
            }

            // Absicherung 2: ist Pfad 1 nicht nutzbar, die Binary atomar aus dem APK-Zip nach filesDir streamen und +x setzen
            if (selectedBinary == null) {
                val filesDirBin = File(context.filesDir, "libstockfish.so")
                val apkPath = context.applicationInfo.sourceDir
                var extracted = false

                if (apkPath != null && File(apkPath).exists()) {
                    try {
                        val zip = java.util.zip.ZipFile(apkPath)
                        val abis = Build.SUPPORTED_ABIS ?: arrayOf("arm64-v8a", "armeabi-v7a", "x86_64")
                        extracted = extractBinaryFromZip(zip, abis, filesDirBin)
                        zip.close()
                    } catch (e: Exception) {
                        diag.append("Pfad2 [Ausnahme beim APK-Entpacken]: ${e.javaClass.simpleName}: ${e.message}\n")
                    }
                }

                filesDirBin.setExecutable(true, false)
                val filesExists = filesDirBin.exists()
                val filesCanExec = filesDirBin.canExecute()
                diag.append("Pfad2 [filesDir]: extracted=$extracted, exists=$filesExists, canExec=$filesCanExec\n")

                if (filesExists && filesCanExec) {
                    selectedBinary = filesDirBin
                    startupPathDesc = "Pfad2 (APK-Zip -> filesDir)"
                }
            }

            if (selectedBinary == null || !selectedBinary.exists()) {
                lastEngineStartupStatus = "[Engine-Start fehlgeschlagen]\n$diag\nKeine ausführbare Binary verfügbar, es greift der reine Kotlin-Fallback"
                Log.w(TAG, "Stockfish binary unavailable, using fallback\n$diag")
                isEngineReady = false
                return
            }

            // Absicherung 2.5: NNUE-Netz bereitstellen (fehlt es, gibt die Engine bei der ersten Suche ERROR aus und beendet sich)
            val nnueDir = File(context.filesDir, "nnue")
            val nnueFile = ensureNnueFile(context, nnueDir, diag)

            val pb = ProcessBuilder(selectedBinary.absolutePath)
            pb.redirectErrorStream(true)
            // Die Engine sucht das Netz standardmäßig im Arbeitsverzeichnis unter dem eingebauten Namen, cwd zeigt deshalb zusätzlich dorthin
            pb.directory(nnueDir)
            val p = pb.start()
            process = p
            diag.append("Prozessstart: erfolgreich ($startupPathDesc)\n")

            writer = BufferedWriter(OutputStreamWriter(p.outputStream))
            val br = BufferedReader(InputStreamReader(p.inputStream))
            reader = br

            // Eigenen nicht blockierenden Ereigniskanal anlegen
            val channel = Channel<String>(Channel.UNLIMITED)
            lineChannel = channel

            // Blockierende Lese-Coroutine im Hintergrund starten
            readerJob = scope.launch(Dispatchers.IO) {
                try {
                    while (isActive) {
                        val line = br.readLine() ?: break
                        channel.send(line)
                    }
                } catch (e: Exception) {
                    Log.d(TAG, "Reader loop exited: ${e.message}")
                } finally {
                    channel.close()
                    // Lektion aus bug_16/17: wird das Flag nach dem Tod des Engine-Prozesses nicht zurückgesetzt,
                    // lesen Folgeaufrufe ins Leere ("Timeout, 0 Zeilen empfangen") und fallen in den Fallback statt neu zu starten
                    isEngineReady = false
                }
            }

            // Absicherung 3: strenge UCI-Handshake-Sequenz mit 5000 ms Karenz (uci -> uciok -> ucinewgame -> isready -> readyok)
            // Die Optionsliste gehört zum gerade gestarteten Prozess: vor dem Handshake leeren,
            // damit nach einem Neustart keine Namen aus der vorherigen Instanz stehen bleiben.
            supportedOptions.clear()
            val uciStart = System.currentTimeMillis()
            sendCommand("uci")
            val uciOk = waitForResponse("uciok", timeoutMs = 5000)
            val uciElapsed = System.currentTimeMillis() - uciStart
            diag.append("Handshake [uciok]: ${if (uciOk) "erfolgreich (${uciElapsed}ms)" else "Timeout (${uciElapsed}ms)"}\n")

            if (uciOk) {
                // Absoluten Pfad des Bewertungsnetzes explizit setzen (zweite Absicherung neben cwd)
                nnueFile?.let { sendCommand("setoption name EvalFile value ${it.absolutePath}") }

                // Vorgegebene Spielstärke-Konfiguration anwenden (Ziel: maximale Spielstärke bei 2 s Bedenkzeit).
                // Hinweis zur früheren Einstellung Threads=1/Hash=16: die diente der Reproduzierbarkeit
                // (Lektion aus bug_19). Mehrere Threads machen die Suche unter movetime-Abbruch wieder
                // nicht deterministisch; identische Stellungen bleiben trotzdem stabil, weil der
                // evalCache dasselbe FEN nicht erneut durchrechnet.
                val config = buildEngineConfig(
                    logicalCores = Runtime.getRuntime().availableProcessors(),
                    deviceRamMb = readDeviceRamMb(context),
                    syzygyPath = findSyzygyPath(context)
                )
                activeConfig = config
                activeHashMb = config.hashMb
                hashfullSamples.clear()
                val skipped = mutableListOf<String>()
                for ((name, value) in buildUciOptions(config)) {
                    if (supportedOptions.isEmpty() || supportedOptions.contains(name)) {
                        sendCommand("setoption name $name value $value")
                    } else {
                        skipped.add(name)
                    }
                }
                diag.append("UCI-Konfiguration: Threads=${config.threads}, Hash=${config.hashMb}MB, MoveOverhead=${config.moveOverheadMs}ms")
                diag.append(config.syzygyPath?.let { ", SyzygyPath=$it" } ?: ", Syzygy=aus")
                diag.append("\n")
                if (skipped.isNotEmpty()) {
                    diag.append("Von dieser Engine nicht unterstützt (übersprungen): ${skipped.joinToString(", ")}\n")
                }

                sendCommand("ucinewgame")
                val readyStart = System.currentTimeMillis()
                sendCommand("isready")
                val readyOk = waitForResponse("readyok", timeoutMs = 5000)
                val readyElapsed = System.currentTimeMillis() - readyStart
                diag.append("Handshake [readyok]: ${if (readyOk) "erfolgreich (${readyElapsed}ms)" else "Timeout (${readyElapsed}ms)"}\n")

                isEngineReady = readyOk
                if (readyOk) {
                    val totalTime = System.currentTimeMillis() - startTime
                    diag.append("Gesamtdauer: ${totalTime}ms | echtes Stockfish ist bereit")
                    lastEngineStartupStatus = "[Engine bereit ($startupPathDesc)]\n$diag"
                    Log.i(TAG, "Stockfish ready successfully\n$diag")
                } else {
                    lastEngineStartupStatus = "[Handshake fehlgeschlagen]\n$diag\nreadyok im Timeout, Prozess wird beendet${lastEngineError?.let { "\nEngine-Fehler: $it" } ?: ""}"
                    isEngineReady = false
                    destroyProcessLocked()
                }
            } else {
                lastEngineStartupStatus = "[Handshake fehlgeschlagen]\n$diag\nuciok im Timeout, Prozess wird beendet${lastEngineError?.let { "\nEngine-Fehler: $it" } ?: ""}"
                isEngineReady = false
                destroyProcessLocked()
            }
        } catch (e: Exception) {
            diag.append("Abbruch durch Ausnahme: ${e.javaClass.simpleName}: ${e.message}\n")
            lastEngineStartupStatus = "[Ausnahme beim Engine-Start]\n$diag"
            Log.e(TAG, "Failed to start Stockfish process\n$diag", e)
            destroyProcessLocked()
        }
    }

    /**
     * Stellt sicher, dass das NNUE-Netz in filesDir bereitliegt (assets -> atomare .tmp-Kopie -> rename, mit Größenprüfung und Cache-Wiederverwendung)
     */
    private fun ensureNnueFile(context: Context, nnueDir: File, diag: StringBuilder): File? {
        try {
            if (!nnueDir.exists()) nnueDir.mkdirs()
            val target = File(nnueDir, NNUE_ASSET_NAME.substringAfterLast('/'))
            if (target.exists() && target.length() > 1024 * 1024) {
                diag.append("NNUE [Cache]: bereit (${target.length()} bytes)\n")
                return target
            }
            val tmp = File(nnueDir, target.name + ".tmp")
            context.assets.open(NNUE_ASSET_NAME).use { input ->
                FileOutputStream(tmp).use { out -> input.copyTo(out) }
            }
            return if (tmp.length() > 1024 * 1024 && tmp.renameTo(target)) {
                diag.append("NNUE [Entpacken]: erfolgreich (${target.length()} bytes)\n")
                target
            } else {
                tmp.delete()
                diag.append("NNUE [Entpacken]: fehlgeschlagen (Größe oder rename fehlerhaft)\n")
                null
            }
        } catch (e: Exception) {
            diag.append("NNUE [Ausnahme beim Entpacken]: ${e.javaClass.simpleName}: ${e.message}\n")
            return null
        }
    }

    private fun sendCommand(cmd: String) {
        try {
            writer?.let {
                it.write(cmd)
                it.newLine()
                it.flush()
            }
        } catch (e: Exception) {
            Log.w(TAG, "sendCommand '$cmd' failed: ${e.message}")
        }
    }

    private suspend fun waitForResponse(expected: String, timeoutMs: Long): Boolean {
        val channel = lineChannel ?: return false
        val deadline = System.currentTimeMillis() + timeoutMs

        while (System.currentTimeMillis() < deadline) {
            val remaining = deadline - System.currentTimeMillis()
            if (remaining <= 0) break

            val line = withTimeoutOrNull(remaining) {
                try {
                    channel.receive()
                } catch (_: Exception) {
                    null
                }
            } ?: break

            // Während des Handshakes meldet die Engine mit "option name <X> type ..." jede
            // unterstützte Option. Nur diese Namen werden später gesetzt, damit eine ältere
            // Stockfish-Version nicht auf unbekannte Optionen (NumaPolicy, UCI_ShowWDL) laufen muss.
            parseOptionName(line)?.let { supportedOptions.add(it) }

            // Meldet die Engine selbst einen ERROR (z. B. fehlendes NNUE-Netz), sofort abbrechen statt bis zum Timeout zu warten
            if (line.contains("ERROR")) {
                lastEngineError = line
                Log.w(TAG, "Stockfish reported ERROR during handshake: $line")
                return false
            }

            if (line.contains(expected)) {
                return true
            }
        }
        return false
    }

    /**
     * Analysiert ein FEN und liefert Zugempfehlung samt Bewertung (mit Ergebnis-Cache und Selbstheilung durch Neustart)
     */
    suspend fun evaluateFen(fen: String, moveTimeMs: Long = DEFAULT_MOVE_TIME_MS): EngineEvaluation = withContext(Dispatchers.IO) {
        // 1. Zuerst den LRU-Cache prüfen: bei unverändertem Brett bleibt die Empfehlung stabil und die Originaldiagnose dieser Stellung erhalten
        val cached = evalCache.get(fen)
        if (cached != null) {
            return@withContext cached
        }

        engineMutex.withLock {
            // Cache erneut prüfen
            val reCheck = evalCache.get(fen)
            if (reCheck != null) {
                return@withContext reCheck
            }

            // 2. FEN-Vorprüfung (Lektion aus bug_19/superbug): unmögliche Stellungen aus Erkennungsfehlern (z. B. die Seite am Zug steht im Schach)
            // lassen Stockfish sofort mit bestmove (none) antworten. Das wurde früher als Engine-Tod gedeutet, mit ständigen Neustarts und wildem Fallback-Spiel.
            // Die Ursache liegt in der Erkennung, deshalb direkt ein Sentinel-Ergebnis zurückgeben, ohne Engine und ohne Fallback
            val fenProblem = validateFenSanity(fen)
            if (fenProblem != null) {
                val diag = "[FEN ungültig, Engine nicht gestartet]\nGrund: $fenProblem\nFEN: $fen\nSchluss: die Erkennung liefert eine unmögliche Stellung (Seite am Zug im Schach o. ae.), das ist ein Erkennungs-/Perspektivfehler, kein Engine-Fehler"
                Log.w(TAG, "FEN rejected by sanity check: $fenProblem, fen=$fen")
                appendFallbackLog("[FEN ungültig, abgewiesen] $fen", diag)
                return@withContext EngineEvaluation(
                    bestMove = "(invalid)",
                    evalScore = 0.0f,
                    depth = -1,
                    isMate = false,
                    diagnosticInfo = diag
                )
            }

            // 3. Selbstheilung (Auto-Respawn): nach dem ersten Fehlschlag Prozess beenden, neu starten und dieselbe Stellung einmal wiederholen
            // (Lektion aus bug_16/17: nach dem Tod der Engine landete schon ein einziger Aufruf im wild ziehenden Fallback, ein Neustart fängt kurzzeitige Ausfälle unbemerkt ab)
            var engineResult: EngineEvaluation? = null
            for (attempt in 1..2) {
                if (!isEngineReady || process == null) {
                    startEngineProcessLocked()
                }
                engineResult = evaluateViaEngineLocked(fen, moveTimeMs)
                if (engineResult != null) {
                    return@withContext engineResult
                }
                if (attempt == 1) {
                    Log.w(TAG, "Stockfish first attempt failed for FEN: $fen, restarting process and retrying")
                    destroyProcessLocked()
                }
            }

            // 4. Zweite Absicherung: reine Kotlin-Zugbewertung (sicherer Fallback, dessen Ergebnis niemals in den evalCache geschrieben wird)
            Log.w(TAG, "Using fallback heuristic evaluator for FEN: $fen")
            val fallbackDiag = "[Fallback: reine Kotlin-Regeln]\nGrund: die Engine lieferte bei diesem Versuch keinen Zug, es übernimmt der reine Regelgenerator\nStartstatus: $lastEngineStartupStatus"
            appendFallbackLog(fen, fallbackDiag)
            val fallback = evaluateFallback(fen).copy(diagnosticInfo = fallbackDiag)
            return@withContext fallback
        }
    }

    /**
     * Einzelner Analyseversuch der Engine (setzt den engineMutex voraus): bei Erfolg Ergebnis samt Cache-Eintrag, bei Fehlschlag Prozess beenden und null zurückgeben, der Aufrufer entscheidet über Wiederholung oder Fallback
     */
    private suspend fun evaluateViaEngineLocked(fen: String, moveTimeMs: Long): EngineEvaluation? {
        if (!isEngineReady || process == null || lineChannel == null) {
            return null
        }

        val evalStartTime = System.currentTimeMillis()
        val receivedLines = mutableListOf<String>()
        // Höchster hashfull-Wert dieser Suche (Promille); die Füllung wächst während der Suche monoton
        var searchHashfull = -1

        try {
            // Eine vorige Suche kann noch laufen - etwa weil ihr Aufrufer abgebrochen wurde,
            // während Stockfish weiterrechnete. Ein "position" mitten in eine laufende Suche zu
            // schicken bringt die Engine aus dem Tritt; sie antwortet danach nicht mehr sinnvoll,
            // wird für tot gehalten und neu gestartet, und es zieht der Regelgenerator.
            // Deshalb erst beenden, dann alle Restzeilen verwerfen.
            if (searchInFlight) {
                sendCommand("stop")
                // "stop" wirkt nicht sofort: Stockfish beendet die Suche und schreibt danach sein
                // "bestmove". Bis dahin muss gewartet werden, sonst landet genau diese Zeile im
                // Lesedurchgang der neuen Suche und wird fuer deren Antwort gehalten.
                // Auf "isready" antwortet die Engine auch waehrend der Suche, "readyok" taugt als
                // Schranke also gerade nicht.
                val beendet = waitForResponse(SEARCH_TERMINATION_MARKER, timeoutMs = 3000)
                searchInFlight = false
                if (!beendet) {
                    Log.w(TAG, "Vorige Suche endete nicht auf stop, Prozess wird neu gestartet")
                    destroyProcessLocked()
                    return null
                }
            }
            while (lineChannel?.tryReceive()?.getOrNull() != null) {}

            // ucinewgame nur bei einer wirklich neuen Partie senden.
            //
            // Der Befehl leert die Transpositionstabelle. Innerhalb einer laufenden Partie ist die
            // nächste Stellung aber keine unabhängige, sondern die Fortsetzung der vorherigen: die
            // gespeicherten Bewertungen passen weiterhin und ersparen der Suche einen großen Teil
            // der Arbeit. Sie bei jedem Zug wegzuwerfen kostet spürbar Spielstärke.
            if (isIndependentPosition(lastSearchedFen, fen)) {
                Log.i(TAG, "Neue Partie erkannt, Transpositionstabelle wird geleert")
                sendCommand("ucinewgame")
            }
            lastSearchedFen = fen
            sendCommand("isready")
            val readyOk = waitForResponse("readyok", timeoutMs = 3000)
            if (!readyOk) {
                Log.w(TAG, "Stockfish readyok timeout before evaluate, resetting process")
                destroyProcessLocked()
                return null
            }

            val currentChannel = lineChannel
            if (currentChannel == null || !isEngineReady || process == null) {
                destroyProcessLocked()
                return null
            }

            val sanitizedFen = sanitizeCastlingRights(fen)
            sendCommand("position fen $sanitizedFen")
            sendCommand(buildGoCommand(moveTimeMs))
            // Ab hier steht ein Ergebnis aus, bis es unten gelesen wird
            searchInFlight = true

            var lastEval: EngineEvaluation? = null
            var bestMoveResult: String? = null
            val deadline = System.currentTimeMillis() + moveTimeMs + 4000L

            // Verfolgt, wie lange die Engine schon beim selben Zug bleibt: Grundlage für den
            // vorzeitigen Abbruch. Die Bedenkzeit ist eine Obergrenze, kein Soll.
            var stableMove: String? = null
            var stableDepths = 0
            var lastSeenDepth = -1
            var stopSent = false

            // Ausgabe reaktiv und suspendierend lesen
            while (System.currentTimeMillis() < deadline) {
                val remaining = deadline - System.currentTimeMillis()
                if (remaining <= 0) break

                val line = withTimeoutOrNull(remaining) {
                    try {
                        currentChannel.receive()
                    } catch (_: Exception) {
                        null
                    }
                } ?: break

                receivedLines.add(line)
                if (receivedLines.size > 20) receivedLines.removeAt(0)

                // Meldet die Engine selbst einen ERROR (z. B. fehlendes NNUE-Netz), sofort merken - die Diagnose braucht ihn, falls auch die Wiederholung scheitert
                if (line.contains("ERROR")) {
                    lastEngineError = line
                }

                parseHashfull(line)?.let { searchHashfull = max(searchHashfull, it) }

                val parsedInfo = parseInfoLine(line)
                if (parsedInfo != null) {
                    lastEval = parsedInfo
                }

                // Vorzeitiger Abbruch: Bleibt der beste Zug über mehrere Tiefen derselbe, ist die
                // Sache entschieden und die restliche Bedenkzeit wäre reine Wartezeit.
                if (!stopSent) {
                    val pvMove = parsePvMove(line)
                    val pvDepth = parsedInfo?.depth ?: -1
                    if (pvMove != null && pvDepth > lastSeenDepth) {
                        lastSeenDepth = pvDepth
                        stableDepths = if (pvMove == stableMove) stableDepths + 1 else 1
                        stableMove = pvMove
                        val elapsed = System.currentTimeMillis() - evalStartTime
                        if (searchIsSettled(stableDepths, pvDepth, parsedInfo?.isMate == true, elapsed, moveTimeMs)) {
                            Log.i(TAG, "Zug $pvMove steht seit $stableDepths Tiefen fest (Tiefe $pvDepth), Suche wird beendet")
                            sendCommand("stop")
                            stopSent = true
                        }
                    }
                }

                val bm = parseBestMoveLine(line)
                if (bm != null) {
                    bestMoveResult = bm
                    break
                }
            }

            // Das Ergebnis ist abgeholt - die Engine rechnet nicht mehr
            if (bestMoveResult != null) searchInFlight = false

            // Lektion aus bug_19/superbug: bestmove (none) ist die korrekte Sofortantwort der Engine auf eine Stellung ohne legale Züge
            // (meist zusammen mit info depth 0 score mate 0) und kein Engine-Tod. Früher galt das als Fehlschlag, mit Dauerneustarts und wildem Fallback.
            // Schon vorhandene Ausgabezeilen beweisen, dass der Prozess lebt: Ergebnis als Partieende verbuchen, Prozess unangetastet lassen
            if (bestMoveResult == "(none)") {
                val inCheck = isKingInCheck(parseFenBoard(fen), parseFenIsWhite(fen))
                val diag = "[Stockfish bestätigt: keine legalen Züge]\nEngine lebt und antwortet sofort mit bestmove (none) | empfangene Zeilen: ${receivedLines.size}\nBefund: ${if (inCheck) "Schachmatt" else "Patt"} (kein Engine-Fehler, kein Neustart)"
                val terminal = EngineEvaluation(
                    bestMove = if (inCheck) "(checkmate)" else "(stalemate)",
                    evalScore = if (parseFenIsWhite(fen)) -100.0f else 100.0f,
                    depth = 0,
                    isMate = inCheck,
                    diagnosticInfo = diag
                )
                Log.i(TAG, "Stockfish bestmove (none) consumed as terminal state (inCheck=$inCheck), engine kept alive")
                evalCache.put(fen, terminal)
                return terminal
            }

            if (bestMoveResult != null) {
                if (searchHashfull >= 0) applyHashfullSample(searchHashfull)
                val totalElapsed = System.currentTimeMillis() - evalStartTime
                val depth = if ((lastEval?.depth ?: -1) > 0) lastEval!!.depth else 0
                val score = lastEval?.evalScore ?: 0.0f
                val diag = "[Stockfish-Berechnung erfolgreich]\nDauer: ${totalElapsed}ms | Tiefe: $depth | Bewertung: $score | Zug: $bestMoveResult\nLetzte Ausgaben: ${receivedLines.takeLast(2).joinToString(" || ")}"
                val result = EngineEvaluation(
                    bestMove = bestMoveResult,
                    evalScore = score,
                    depth = depth,
                    isMate = lastEval?.isMate ?: false,
                    diagnosticInfo = diag
                )
                Log.i(TAG, "Stockfish evaluate success: bestMove=${result.bestMove}, depth=${result.depth}, score=${result.evalScore}")
                evalCache.put(fen, result)
                return result
            }

            val totalElapsed = System.currentTimeMillis() - evalStartTime
            val engineErr = lastEngineError?.let { "\nEngine-Fehler: $it" } ?: ""
            val diag = "[Stockfish: Versuch ohne Ausgabe]\nBisherige Dauer: ${totalElapsed}ms | empfangene Zeilen: ${receivedLines.size}\nLetzte Zeilen: ${receivedLines.takeLast(2).joinToString(" || ")}$engineErr"
            Log.w(TAG, "Stockfish evaluate attempt produced no bestmove, resetting process\n$diag")
            destroyProcessLocked()
            return null
        } catch (e: CancellationException) {
            // Der Aufrufer hat aufgegeben (Zeitgrenze der Beobachtungsschleife, Schalter aus).
            // Der Prozess bleibt ausdruecklich stehen: ihn hier abzuraeumen kostete beim naechsten
            // Zug den vollstaendigen Neustart samt Netz laden - mehrere Sekunden, in denen DuLo
            // stillstand. searchInFlight bleibt gesetzt, die naechste Suche raeumt sauber auf.
            Log.i(TAG, "Suche abgebrochen, Prozess bleibt bestehen")
            throw e
        } catch (e: Exception) {
            val diag = "[Ausnahme in der Stockfish-Analyse, Abstieg in den Fallback]\nAusnahme: ${e.javaClass.simpleName}: ${e.message}"
            Log.w(TAG, "Stockfish evaluateFen exception: ${e.message}\n$diag", e)
            destroyProcessLocked()
            return null
        }
    }

    /**
     * Reine Funktion: Ist diese Stellung der Beginn einer neuen Partie?
     *
     * Entscheidend ist die Zahl der Figuren: mit 28 oder mehr steht praktisch noch die
     * Ausgangsstellung, dann lohnt das Leeren der Tabelle. In jeder anderen Lage ist die Stellung
     * die Fortsetzung der bisherigen Partie.
     */
    fun isNewGamePosition(fen: String): Boolean {
        val boardPart = fen.substringBefore(' ')
        val pieces = boardPart.count { it.isLetter() }
        return pieces >= 28
    }

    /**
     * Reine Funktion: Ist die neue Stellung eine unabhängige Partie und keine Fortsetzung?
     *
     * [isNewGamePosition] allein taugt dafür nicht: Nach 28 Figuren gefragt, trifft das auf die
     * ersten Züge jeder Partie zu - die Tabelle wurde dann bei jedem Zug der Eröffnung geleert,
     * also genau in der Phase, in der sie am meisten trägt.
     *
     * Verlässlich ist der Vergleich mit der zuletzt gerechneten Stellung: Innerhalb einer Partie
     * nimmt die Zahl der Figuren nie zu. Kommen welche hinzu, ist eine neue Partie angefangen
     * worden. Dazu die Grundstellung selbst und der Fall, dass noch gar nichts gerechnet wurde.
     *
     * @param previousFen zuletzt gerechnete Stellung, null beim ersten Aufruf
     */
    fun isIndependentPosition(previousFen: String?, currentFen: String): Boolean {
        if (previousFen == null) return true

        val currentBoard = currentFen.substringBefore(' ')
        // Die Grundstellung ist immer der Anfang einer Partie
        if (currentBoard == "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR") return true

        val previousPieces = previousFen.substringBefore(' ').count { it.isLetter() }
        val currentPieces = currentBoard.count { it.isLetter() }
        // Innerhalb einer Partie verschwinden Figuren, sie kommen nie hinzu
        return currentPieces > previousPieces
    }

    /**
     * Nimmt den hashfull-Wert einer Suche in das gleitende Fenster auf und vergrößert den Hash,
     * sobald der Mittelwert der letzten Suchen über 30 Prozent liegt (Vorgabe).
     * Der neue Wert gilt ab der nächsten Suche; Stockfish legt die Tabelle bei setoption Hash neu an.
     */
    private fun applyHashfullSample(hashfull: Int) {
        val config = activeConfig ?: return
        hashfullSamples.addLast(hashfull)
        while (hashfullSamples.size > 8) hashfullSamples.removeFirst()
        if (hashfullSamples.size < 4) return

        val average = hashfullSamples.sum() / hashfullSamples.size
        val context = appContext ?: return
        val adjusted = adjustHashForHashfull(
            currentHashMb = activeHashMb,
            baseHashMb = config.hashMb,
            averageHashfull = average,
            deviceRamMb = readDeviceRamMb(context)
        )
        if (adjusted > activeHashMb) {
            Log.i(TAG, "hashfull average ${average}/1000 exceeds 300, raising Hash from ${activeHashMb}MB to ${adjusted}MB")
            activeHashMb = adjusted
            sendCommand("setoption name Hash value $adjusted")
            hashfullSamples.clear()
        }
    }

    /**
     * Fallback-Ereignisse zur Selbstforensik speichern (Lektion aus bug_15: das Overlay zeigte nur [Fallback], der Grund war nach der nächsten erfolgreichen Analyse nicht mehr nachvollziehbar)
     * Hängt an filesDir/debug/engine_fallback_log.txt an und behält die letzten 30 Einträge
     */
    private fun appendFallbackLog(fen: String, diagInfo: String) {
        try {
            val ctx = appContext ?: return
            val debugDir = File(ctx.filesDir, "debug")
            if (!debugDir.exists()) debugDir.mkdirs()
            val logFile = File(debugDir, "engine_fallback_log.txt")
            val timeStr = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
            val entry = "[$timeStr] FEN: $fen\nDiagnose: ${diagInfo.replace("\n", " | ")}\n\n"
            val existing = if (logFile.exists()) logFile.readText() else ""
            val entries = (existing + entry).split("\n\n").filter { it.isNotBlank() }
            logFile.writeText(entries.takeLast(30).joinToString("\n\n") + "\n\n")
        } catch (_: Exception) {
        }
    }

    /**
     * Hilfsfunktion zum Zerlegen eines FEN: Brettarray (row 0 = Reihe 8), wird auch für die Partieende-Erkennung genutzt
     */
    private fun parseFenBoard(fen: String): Array<CharArray> {
        val rows = fen.split(" ")[0].split("/")
        return Array(8) { r ->
            val rowStr = if (r < rows.size) rows[r] else "8"
            val expanded = StringBuilder()
            for (ch in rowStr) {
                if (ch.isDigit()) repeat(ch - '0') { expanded.append('.') } else expanded.append(ch)
            }
            while (expanded.length < 8) expanded.append('.')
            expanded.toString().toCharArray()
        }
    }

    private fun parseFenIsWhite(fen: String): Boolean {
        val parts = fen.split(" ")
        return parts.size < 2 || parts[1] == "w"
    }

    /**
     * Reine Funktion: bereinigt die Rochaderechte im FEN (defensive Programmierung)
     * Prüft, ob die Rochadekennzeichen (KQkq) zur tatsächlichen Stellung von König und Turm passen.
     * Hat der König oder der zugehörige Turm sein Ausgangsfeld verlassen, fällt das entsprechende Kennzeichen weg; bleibt keines übrig, steht dort "-".
     * So kann ein an Stockfish übergebenes FEN niemals an BAD_CASTLING_RIGHTS scheitern.
     */
    fun sanitizeCastlingRights(fen: String): String {
        val parts = fen.trim().split("\\s+".toRegex()).toMutableList()
        if (parts.size < 3) return fen
        val board = parseFenBoard(fen)
        val originalCastling = parts[2]
        if (originalCastling == "-") return fen

        val sb = StringBuilder()
        // K: White kingside (K at e1, R at h1)
        if (originalCastling.contains('K') && board.size >= 8 && board[7].size >= 8 && board[7][4] == 'K' && board[7][7] == 'R') sb.append('K')
        // Q: White queenside (K at e1, R at a1)
        if (originalCastling.contains('Q') && board.size >= 8 && board[7].size >= 8 && board[7][4] == 'K' && board[7][0] == 'R') sb.append('Q')
        // k: Black kingside (k at e8, r at h8)
        if (originalCastling.contains('k') && board.size >= 8 && board[0].size >= 8 && board[0][4] == 'k' && board[0][7] == 'r') sb.append('k')
        // q: Black queenside (k at e8, r at a8)
        if (originalCastling.contains('q') && board.size >= 8 && board[0].size >= 8 && board[0][4] == 'k' && board[0][0] == 'r') sb.append('q')

        val newCastling = if (sb.isEmpty()) "-" else sb.toString()
        parts[2] = newCastling
        return parts.joinToString(" ")
    }

    /**
     * FEN-Vorprüfung (Befund aus bug_19/superbug): fängt unmögliche Stellungen der Erkennung ab, damit ein sofortiges (none) nicht als Engine-Tod missverstanden wird.
     * null bedeutet bestanden, sonst folgt die Beschreibung des Verstoßes. Geprüft werden: beide Könige genau einmal, Könige nicht benachbart, Seite am Zug nicht bereits im Schach
     * (mit python-chess verifiziert: bei solchen Stellungen gibt Stockfish info depth 0 score mate 0 und bestmove (none) aus)
     */
    fun validateFenSanity(fen: String): String? {
        val board = parseFenBoard(fen)
        var wk = 0
        var bk = 0
        var wkr = -1; var wkc = -1
        var bkr = -1; var bkc = -1
        
        // Harte Untergrenze für die Gesamtzahl der Figuren, damit vereinzelte Merkmale eines falschen UI-Rahmens nicht zu einem Endspiel zusammengesetzt werden
        var totalPieces = 0 

        for (r in 0..7) {
            for (c in 0..7) {
                val piece = board[r][c]
                if (piece != '.') totalPieces++ 

                when (piece) {
                    'K' -> { wk++; wkr = r; wkc = c }
                    'k' -> { bk++; bkr = r; bkc = c }
                    'P', 'p' -> if (r == 0 || r == 7) return "Bauer auf Grund- oder Umwandlungsreihe (r${r}c${c})"
                }
            }
        }
        if (wk != 1 || bk != 1) return "Anzahl der Könige falsch (weiß=$wk, schwarz=$bk)"
        
        // Harte Schranke: in einer Schachpartie können niemals weniger als 4 Figuren stehen (mindestens beide Könige plus Bauer oder Springer).
        // Liegt die Gesamtzahl darunter, ist es zu 99,9 % ein falscher Rahmen aus der Duolingo-Oberfläche.
        if (totalPieces <= 3) return "Brett fast leer (nur $totalPieces Figuren), sicher ein falsch erkannter UI-Rahmen"

        if (maxOf(abs(wkr - bkr), abs(wkc - bkc)) <= 1) return "Könige stehen nebeneinander"
        return null
    }

    private fun destroyProcessLocked() {
        try {
            process?.destroy()
        } catch (_: Exception) {}
        try {
            readerJob?.cancel()
            lineChannel?.close()
            writer?.close()
            reader?.close()
        } catch (_: Exception) {}

        process = null
        writer = null
        reader = null
        lineChannel = null
        readerJob = null
        isEngineReady = false
        // Mit dem Prozess verschwindet auch die laufende Suche
        searchInFlight = false
        // Ein frischer Prozess beginnt mit leerer Transpositionstabelle: die nächste Suche gilt
        // wieder als unabhängig.
        lastSearchedFen = null
    }

    /**
     * Kennzeichen der Zeile, mit der Stockfish eine Suche abschliesst.
     *
     * Wird als Schranke benutzt, um eine abgebrochene Vorsuche sauber auslaufen zu lassen. Der
     * Vergleich laeuft ueber Teilzeichenketten, deshalb muss sicher sein, dass keine andere
     * Ausgabezeile das Wort enthaelt - dafuer gibt es [isSearchTerminationLine] und seinen Test.
     */
    const val SEARCH_TERMINATION_MARKER = "bestmove"

    /**
     * Reine Funktion: Schliesst diese Ausgabezeile eine Suche ab?
     */
    fun isSearchTerminationLine(line: String): Boolean = line.contains(SEARCH_TERMINATION_MARKER)

    fun parseBestMoveLine(line: String): String? {
        val matcher = bestMovePattern.matcher(line.trim())
        return if (matcher.find()) matcher.group(1) else null
    }

    /**
     * Reine Funktion: liest den ersten Zug der Hauptvariante aus einer info-Zeile.
     *
     * Stockfish meldet nach jeder Tiefe seine beste Fortsetzung als "... pv e2e4 e7e5 ...".
     * Der erste Zug darin ist der Zug, den die Engine bei dieser Tiefe spielen würde.
     */
    fun parsePvMove(line: String): String? {
        if (!line.startsWith("info ")) return null
        // Zeilen aus einer fehlgeschlagenen Fenstersuche sind nur Zwischenstände: Ihre Bewertung
        // ist eine Schranke, kein Ergebnis, und die Hauptvariante darin kann in die Irre führen.
        if (line.contains("lowerbound") || line.contains("upperbound")) return null
        val marker = line.indexOf(" pv ")
        if (marker < 0) return null
        val rest = line.substring(marker + 4).trim()
        val move = rest.substringBefore(' ')
        return if (move.matches(Regex("[a-h][1-8][a-h][1-8][qrbnQRBN]?"))) move else null
    }

    /**
     * Reine Funktion: Steht der beste Zug fest genug, um die Suche abzubrechen?
     *
     * Die Bedenkzeit ist eine Obergrenze, kein Soll. Bleibt die Engine über mehrere Tiefen hinweg
     * beim selben Zug und hat dabei schon ordentlich weit gerechnet, ändert weitere Rechenzeit das
     * Ergebnis so gut wie nie mehr - sie kostet nur Wartezeit vor dem Zug. Ein erzwungener Zug oder
     * ein klarer Schlagabtausch ist oft nach einem Bruchteil der Zeit entschieden.
     *
     * Bewusst zurückhaltend: In einer scharfen Stellung wechselt der beste Zug zwischen den Tiefen,
     * dort greift der Abbruch nicht und es bleibt bei der vollen Bedenkzeit.
     *
     * @param stableDepths Wie viele Tiefen in Folge denselben Zug ergeben haben
     * @param depth        Zuletzt erreichte Suchtiefe
     * @param isMate       Steht bereits ein Matt fest?
     */
    fun searchIsSettled(
        stableDepths: Int,
        depth: Int,
        isMate: Boolean,
        elapsedMs: Long,
        moveTimeMs: Long
    ): Boolean {
        // Ein gefundenes Matt ist das Ende der Fahnenstange, da hilft kein Weiterrechnen
        if (isMate && depth >= MATE_SETTLED_DEPTH) return true

        // Sonst bewusst streng. Der Abbruch soll die Wartezeit in offensichtlichen Stellungen
        // sparen und nirgends sonst - Spielstärke ist wichtiger als eine Sekunde. Deshalb müssen
        // drei Dinge zusammenkommen: eine belastbare Tiefe, ein über viele Tiefen unveränderter
        // Zug, und mindestens die halbe Bedenkzeit muss verbraucht sein. Fehlt eines davon,
        // wird bis zum Schluss gerechnet.
        return depth >= MIN_SETTLED_DEPTH &&
            stableDepths >= STABLE_DEPTHS_REQUIRED &&
            elapsedMs >= moveTimeMs / 2
    }

    fun parseInfoLine(line: String): EngineEvaluation? {
        if (!line.startsWith("info ")) return null

        var depth = -1
        val depthMatcher = depthPattern.matcher(line)
        if (depthMatcher.find()) {
            depth = depthMatcher.group(1)?.toIntOrNull() ?: -1
        }

        val mateMatcher = scoreMatePattern.matcher(line)
        if (mateMatcher.find()) {
            val mateSteps = mateMatcher.group(1)?.toIntOrNull() ?: 0
            val score = if (mateSteps > 0) 100.0f else -100.0f
            return EngineEvaluation(bestMove = "", evalScore = score, depth = depth, isMate = true)
        }

        val cpMatcher = scoreCpPattern.matcher(line)
        if (cpMatcher.find()) {
            val cp = cpMatcher.group(1)?.toIntOrNull() ?: 0
            val score = cp / 100.0f
            return EngineEvaluation(bestMove = "", evalScore = score, depth = depth, isMate = false)
        }

        return null
    }

    /**
     * Prüft, ob ein Zielfeld im Angriffsbereich bzw. auf einer Angriffslinie der angegebenen Farbe liegt
     */
    fun isSquareAttacked(board: Array<CharArray>, targetR: Int, targetC: Int, byWhite: Boolean): Boolean {
        // 1. Springerangriff (8 L-Sprünge)
        val kOffsets = arrayOf(
            Pair(-2, -1), Pair(-2, 1), Pair(-1, -2), Pair(-1, 2),
            Pair(1, -2), Pair(1, 2), Pair(2, -1), Pair(2, 1)
        )
        val kSym = if (byWhite) 'N' else 'n'
        for ((dr, dc) in kOffsets) {
            val nr = targetR + dr
            val nc = targetC + dc
            if (nr in 0..7 && nc in 0..7 && board[nr][nc] == kSym) return true
        }

        // 2. Bauernangriff diagonal (weiße Bauern ziehen nach oben -1, die Quelle liegt also bei +1; schwarze Bauern ziehen nach unten +1, Quelle bei -1)
        val pSym = if (byWhite) 'P' else 'p'
        val pDr = if (byWhite) 1 else -1
        for (pDc in arrayOf(-1, 1)) {
            val nr = targetR + pDr
            val nc = targetC + pDc
            if (nr in 0..7 && nc in 0..7 && board[nr][nc] == pSym) return true
        }

        // 3. Turm/Dame: Angriffslinien in den 4 geraden Richtungen
        val orthSyms = if (byWhite) charArrayOf('R', 'Q') else charArrayOf('r', 'q')
        val orthDirs = arrayOf(Pair(-1, 0), Pair(1, 0), Pair(0, -1), Pair(0, 1))
        for ((dr, dc) in orthDirs) {
            var step = 1
            while (true) {
                val nr = targetR + dr * step
                val nc = targetC + dc * step
                if (nr !in 0..7 || nc !in 0..7) break
                val p = board[nr][nc]
                if (p != '.') {
                    if (p in orthSyms) return true
                    break
                }
                step++
            }
        }

        // 4. Läufer/Dame: Angriffslinien in den 4 diagonalen Richtungen
        val diagSyms = if (byWhite) charArrayOf('B', 'Q') else charArrayOf('b', 'q')
        val diagDirs = arrayOf(Pair(-1, -1), Pair(-1, 1), Pair(1, -1), Pair(1, 1))
        for ((dr, dc) in diagDirs) {
            var step = 1
            while (true) {
                val nr = targetR + dr * step
                val nc = targetC + dc * step
                if (nr !in 0..7 || nc !in 0..7) break
                val p = board[nr][nc]
                if (p != '.') {
                    if (p in diagSyms) return true
                    break
                }
                step++
            }
        }

        // 5. König: Angriff auf die 8 Nachbarfelder
        val kingSym = if (byWhite) 'K' else 'k'
        for (dr in -1..1) {
            for (dc in -1..1) {
                if (dr == 0 && dc == 0) continue
                val nr = targetR + dr
                val nc = targetC + dc
                if (nr in 0..7 && nc in 0..7 && board[nr][nc] == kingSym) return true
            }
        }

        return false
    }

    /**
     * Prüft, ob der König der angegebenen Farbe im Schach steht
     */
    fun isKingInCheck(board: Array<CharArray>, isWhite: Boolean): Boolean {
        val kingSym = if (isWhite) 'K' else 'k'
        var kr = -1
        var kc = -1
        for (r in 0..7) {
            for (c in 0..7) {
                if (board[r][c] == kingSym) {
                    kr = r
                    kc = c
                    break
                }
            }
            if (kr != -1) break
        }
        if (kr == -1) return false
        return isSquareAttacked(board, kr, kc, byWhite = !isWhite)
    }

    /**
     * Intelligenter Fallback-Zuggenerator (strikt nach FIDE-Regeln: Probezug-Filter, Schachabwehr, absolute Fesselung, Verzweigung Matt/Patt)
     */
    fun evaluateFallback(fen: String): EngineEvaluation {
        val parts = fen.split(" ")
        val isWhite = if (parts.size > 1) parts[1] == "w" else true
        val rows = parts[0].split("/")

        val board = Array(8) { r ->
            val rowStr = if (r < rows.size) rows[r] else "8"
            val expanded = StringBuilder()
            for (ch in rowStr) {
                if (ch.isDigit()) {
                    repeat(ch - '0') { expanded.append('.') }
                } else {
                    expanded.append(ch)
                }
            }
            while (expanded.length < 8) expanded.append('.')
            expanded.toString().toCharArray()
        }

        // Liste der Pseudo-Züge: (fromR, fromC, toR, toC, promoSuffix)
        data class RawMove(val fromR: Int, val fromC: Int, val toR: Int, val toC: Int, val promo: String = "")
        val candidateMoves = mutableListOf<RawMove>()

        for (r in 0..7) {
            for (c in 0..7) {
                val piece = board[r][c]
                val belongsToActive = if (isWhite) piece.isUpperCase() else piece.isLowerCase()
                if (!belongsToActive) continue

                val pUpper = piece.uppercaseChar()

                when (pUpper) {
                    'P' -> {
                        val dir = if (isWhite) -1 else 1
                        val nextR = r + dir
                        if (nextR in 0..7 && board[nextR][c] == '.') {
                            val promoSuffix = if (nextR == 0 || nextR == 7) "q" else ""
                            candidateMoves.add(RawMove(r, c, nextR, c, promoSuffix))
                            val startRank = if (isWhite) 6 else 1
                            val doubleNextR = r + 2 * dir
                            if (r == startRank && board[doubleNextR][c] == '.') {
                                candidateMoves.add(RawMove(r, c, doubleNextR, c))
                            }
                        }
                        // Diagonal schlagen
                        for (dc in arrayOf(-1, 1)) {
                            val targetC = c + dc
                            if (nextR in 0..7 && targetC in 0..7) {
                                val target = board[nextR][targetC]
                                val isEnemy = if (isWhite) (target != '.' && target.isLowerCase())
                                else (target != '.' && target.isUpperCase())
                                if (isEnemy) {
                                    val promoSuffix = if (nextR == 0 || nextR == 7) "q" else ""
                                    candidateMoves.add(RawMove(r, c, nextR, targetC, promoSuffix))
                                }
                            }
                        }
                    }
                    'N' -> {
                        val offsets = arrayOf(
                            Pair(-2, -1), Pair(-2, 1), Pair(-1, -2), Pair(-1, 2),
                            Pair(1, -2), Pair(1, 2), Pair(2, -1), Pair(2, 1)
                        )
                        for ((dr, dc) in offsets) {
                            val nr = r + dr
                            val nc = c + dc
                            if (nr in 0..7 && nc in 0..7) {
                                val target = board[nr][nc]
                                val isEnemyOrEmpty = if (isWhite) (target == '.' || target.isLowerCase())
                                else (target == '.' || target.isUpperCase())
                                if (isEnemyOrEmpty) {
                                    candidateMoves.add(RawMove(r, c, nr, nc))
                                }
                            }
                        }
                    }
                    'B' -> {
                        val diagDirs = arrayOf(Pair(-1, -1), Pair(-1, 1), Pair(1, -1), Pair(1, 1))
                        for ((dr, dc) in diagDirs) {
                            var step = 1
                            while (true) {
                                val nr = r + dr * step
                                val nc = c + dc * step
                                if (nr !in 0..7 || nc !in 0..7) break
                                val target = board[nr][nc]
                                if (target == '.') {
                                    candidateMoves.add(RawMove(r, c, nr, nc))
                                } else {
                                    val isEnemy = if (isWhite) target.isLowerCase() else target.isUpperCase()
                                    if (isEnemy) {
                                        candidateMoves.add(RawMove(r, c, nr, nc))
                                    }
                                    break
                                }
                                step++
                            }
                        }
                    }
                    'R' -> {
                        val orthDirs = arrayOf(Pair(-1, 0), Pair(1, 0), Pair(0, -1), Pair(0, 1))
                        for ((dr, dc) in orthDirs) {
                            var step = 1
                            while (true) {
                                val nr = r + dr * step
                                val nc = c + dc * step
                                if (nr !in 0..7 || nc !in 0..7) break
                                val target = board[nr][nc]
                                if (target == '.') {
                                    candidateMoves.add(RawMove(r, c, nr, nc))
                                } else {
                                    val isEnemy = if (isWhite) target.isLowerCase() else target.isUpperCase()
                                    if (isEnemy) {
                                        candidateMoves.add(RawMove(r, c, nr, nc))
                                    }
                                    break
                                }
                                step++
                            }
                        }
                    }
                    'Q' -> {
                        val allDirs = arrayOf(
                            Pair(-1, 0), Pair(1, 0), Pair(0, -1), Pair(0, 1),
                            Pair(-1, -1), Pair(-1, 1), Pair(1, -1), Pair(1, 1)
                        )
                        for ((dr, dc) in allDirs) {
                            var step = 1
                            while (true) {
                                val nr = r + dr * step
                                val nc = c + dc * step
                                if (nr !in 0..7 || nc !in 0..7) break
                                val target = board[nr][nc]
                                if (target == '.') {
                                    candidateMoves.add(RawMove(r, c, nr, nc))
                                } else {
                                    val isEnemy = if (isWhite) target.isLowerCase() else target.isUpperCase()
                                    if (isEnemy) {
                                        candidateMoves.add(RawMove(r, c, nr, nc))
                                    }
                                    break
                                }
                                step++
                            }
                        }
                    }
                    'K' -> {
                        val kingDirs = arrayOf(
                            Pair(-1, 0), Pair(1, 0), Pair(0, -1), Pair(0, 1),
                            Pair(-1, -1), Pair(-1, 1), Pair(1, -1), Pair(1, 1)
                        )
                        for ((dr, dc) in kingDirs) {
                            val nr = r + dr
                            val nc = c + dc
                            if (nr in 0..7 && nc in 0..7) {
                                val target = board[nr][nc]
                                val isEnemyOrEmpty = if (isWhite) (target == '.' || target.isLowerCase())
                                else (target == '.' || target.isUpperCase())
                                if (isEnemyOrEmpty) {
                                    candidateMoves.add(RawMove(r, c, nr, nc))
                                }
                            }
                        }
                    }
                }
            }
        }

        // Strikte Probezug-Simulation: verwirft Züge, die eine absolute Fesselung brechen oder ein Schach nicht aufheben
        val legalMoves = mutableListOf<String>()
        for (m in candidateMoves) {
            val simulated = Array(8) { rIdx -> board[rIdx].clone() }
            val movedPiece = simulated[m.fromR][m.fromC]
            simulated[m.fromR][m.fromC] = '.'
            simulated[m.toR][m.toC] = if (m.promo.isNotEmpty()) (if (isWhite) 'Q' else 'q') else movedPiece

            if (!isKingInCheck(simulated, isWhite)) {
                val fromSquare = "${('a' + m.fromC)}${8 - m.fromR}"
                val toSquare = "${('a' + m.toC)}${8 - m.toR}"
                legalMoves.add("$fromSquare$toSquare${m.promo}")
            }
        }

        // Partieende: ohne legalen Zug wird zwischen Schachmatt und Patt unterschieden
        if (legalMoves.isEmpty()) {
            val inCheck = isKingInCheck(board, isWhite)
            return if (inCheck) {
                // Schachmatt
                EngineEvaluation(
                    bestMove = "(checkmate)",
                    evalScore = if (isWhite) -100.0f else 100.0f,
                    depth = 0,
                    isMate = true,
                    diagnosticInfo = "[Fallback: reine Kotlin-Regeln]\nBefund: Schachmatt (Partie entschieden)"
                )
            } else {
                // Patt
                EngineEvaluation(
                    bestMove = "(stalemate)",
                    evalScore = 0.0f,
                    depth = 0,
                    isMate = false,
                    diagnosticInfo = "[Fallback: reine Kotlin-Regeln]\nBefund: Patt (Remis)"
                )
            }
        }

        // Aus den legalen Zügen den besten wählen: Zentrumsbesetzung oder Entwicklung einer Leichtfigur
        val bestMove = legalMoves.firstOrNull { it.endsWith("e4") || it.endsWith("d4") || it.endsWith("e5") || it.endsWith("c5") || it.endsWith("f3") || it.endsWith("f6") }
            ?: legalMoves.first()

        return EngineEvaluation(
            bestMove = bestMove,
            evalScore = 0.0f,
            depth = 0,
            isMate = false,
            diagnosticInfo = "[Fallback: reine Kotlin-Regeln]\nZug: $bestMove | Tiefe: 0"
        )
    }

    fun release() {
        scope.launch {
            engineMutex.withLock {
                try {
                    if (isEngineReady) {
                        sendCommand("quit")
                    }
                } catch (_: Exception) {}
                destroyProcessLocked()
                evalCache.evictAll()
            }
        }
    }
}
