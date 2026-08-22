package com.dulo.app

import com.dulo.app.engine.StockfishBridge
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests für die UCI-Konfiguration der Engine: Threads, Hash, Optionsliste, Options-Erkennung
 * aus dem Handshake, hashfull-Nachregelung und Auswahl der passenden Binary-Variante.
 */
class EngineKonfigurationTest {

    @Test
    fun testThreadsLassenGenauEinenKernFrei() {
        // Auf höchste Spielstärke ausgelegt: nur ein Kern bleibt für Oberfläche und Aufnahme frei
        assertEquals(15, StockfishBridge.computeThreads(16))
        assertEquals(11, StockfishBridge.computeThreads(12))
        assertEquals(7, StockfishBridge.computeThreads(8))
        // Untergrenze: auf einem Ein- oder Zweikerner bleibt mindestens ein Suchthread übrig
        assertEquals(1, StockfishBridge.computeThreads(2))
        assertEquals(1, StockfishBridge.computeThreads(1))
    }

    @Test
    fun testBedenkzeitBetraegtZweiSekunden() {
        assertEquals(2000L, StockfishBridge.DEFAULT_MOVE_TIME_MS)
    }

    @Test
    fun testNeuePartieNurBeiVollemBrett() {
        // Ausgangsstellung: 32 Figuren
        assertTrue(StockfishBridge.isNewGamePosition("rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"))
        // Eröffnung nach ein paar Zügen: immer noch praktisch alles da
        assertTrue(StockfishBridge.isNewGamePosition("rnbqkbnr/pp1ppppp/8/2p5/4P3/5N2/PPPP1PPP/RNBQKB1R b KQkq - 1 2"))
        // Mittelspiel: die Tabelle bleibt stehen, ihre Bewertungen gelten weiter
        assertFalse(StockfishBridge.isNewGamePosition("r3k2r/pp3ppp/8/8/8/8/PP3PPP/R3K2R w KQkq - 0 20"))
        // Endspiel erst recht
        assertFalse(StockfishBridge.isNewGamePosition("8/8/4k3/8/8/4K3/4P3/8 w - - 0 40"))
    }

    @Test
    fun testHashGroesseFolgtDerVorgabetabelle() {
        // 256 MB bei 4-6 logischen Kernen
        assertEquals(256, StockfishBridge.computeHashMb(4))
        assertEquals(256, StockfishBridge.computeHashMb(6))
        // 512 MB bei 8-12 logischen Kernen
        assertEquals(512, StockfishBridge.computeHashMb(8))
        assertEquals(512, StockfishBridge.computeHashMb(12))
        // 1024 MB ab 16 logischen Kernen
        assertEquals(1024, StockfishBridge.computeHashMb(16))
        assertEquals(1024, StockfishBridge.computeHashMb(32))
        // Unterhalb der Tabelle bleibt es bei 128 MB
        assertEquals(128, StockfishBridge.computeHashMb(1))
    }

    @Test
    fun testHashWirdAufGeraetespeicherBegrenzt() {
        // 3 GB Telefon: ein Viertel davon deckelt die 1024 MB aus der Tabelle
        assertEquals(768, StockfishBridge.clampHashToDevice(1024, 3072))
        // 16 GB Gerät: die Tabellenvorgabe passt unverändert
        assertEquals(1024, StockfishBridge.clampHashToDevice(1024, 16384))
        // Unbekannter Speicher (0) lässt den Tabellenwert stehen
        assertEquals(1024, StockfishBridge.clampHashToDevice(1024, 0))
    }

    @Test
    fun testVollstaendigeOptionslisteOhneTablebases() {
        val config = StockfishBridge.buildEngineConfig(logicalCores = 16, deviceRamMb = 16384)
        assertEquals(15, config.threads)
        assertEquals(1024, config.hashMb)
        assertEquals(10, config.moveOverheadMs)
        assertNull(config.syzygyPath)

        val options = StockfishBridge.buildUciOptions(config).toMap()
        assertEquals("15", options["Threads"])
        assertEquals("1024", options["Hash"])
        assertEquals("1", options["MultiPV"])
        assertEquals("false", options["Ponder"])
        assertEquals("20", options["Skill Level"])
        assertEquals("false", options["UCI_LimitStrength"])
        assertEquals("10", options["Move Overhead"])
        assertEquals("0", options["nodestime"])
        assertEquals("true", options["UCI_ShowWDL"])
        assertEquals("auto", options["NumaPolicy"])
        // Ohne Tablebases wird SyzygyPath gar nicht erst gesetzt
        assertTrue(options.keys.none { it.startsWith("Syzygy") })
    }

    @Test
    fun testSyzygyOptionenNurMitTablebasePfad() {
        val config = StockfishBridge.buildEngineConfig(
            logicalCores = 8,
            deviceRamMb = 8192,
            syzygyPath = "/data/user/0/com.dulo.app/files/syzygy"
        )
        val options = StockfishBridge.buildUciOptions(config).toMap()
        assertEquals("/data/user/0/com.dulo.app/files/syzygy", options["SyzygyPath"])
        assertEquals("1", options["SyzygyProbeDepth"])
        assertEquals("5", options["SyzygyProbeLimit"])
        assertEquals("true", options["Syzygy50MoveRule"])
    }

    @Test
    fun testOptionsnamenAusDemHandshake() {
        assertEquals(
            "Move Overhead",
            StockfishBridge.parseOptionName("option name Move Overhead type spin default 10 min 0 max 5000")
        )
        assertEquals("UCI_ShowWDL", StockfishBridge.parseOptionName("option name UCI_ShowWDL type check default false"))
        assertEquals("Threads", StockfishBridge.parseOptionName("option name Threads type spin default 1 min 1 max 1024"))
        assertNull(StockfishBridge.parseOptionName("id name Stockfish 16"))
        assertNull(StockfishBridge.parseOptionName("uciok"))
    }

    @Test
    fun testHashfullAusInfoZeile() {
        val line = "info depth 22 seldepth 30 multipv 1 score cp 34 hashfull 412 nps 1200000 time 2000 pv e2e4"
        assertEquals(412, StockfishBridge.parseHashfull(line))
        assertNull(StockfishBridge.parseHashfull("info depth 3 score cp 12 pv d2d4"))
    }

    @Test
    fun testHashWirdErstUeberDreissigProzentAngehoben() {
        // 28 Prozent Füllung: die Vorgabe ist eingehalten, es bleibt beim aktuellen Hash
        assertEquals(
            512,
            StockfishBridge.adjustHashForHashfull(currentHashMb = 512, baseHashMb = 512, averageHashfull = 280, deviceRamMb = 16384)
        )
        // 41 Prozent Füllung: verdoppeln
        assertEquals(
            1024,
            StockfishBridge.adjustHashForHashfull(currentHashMb = 512, baseHashMb = 512, averageHashfull = 410, deviceRamMb = 16384)
        )
        // Obergrenze: höchstens das Vierfache des Ausgangswerts
        assertEquals(
            2048,
            StockfishBridge.adjustHashForHashfull(currentHashMb = 2048, baseHashMb = 512, averageHashfull = 900, deviceRamMb = 65536)
        )
        // Der Speicher des Geräts bleibt die harte Schranke (4 GB -> höchstens 1024 MB)
        assertEquals(
            1024,
            StockfishBridge.adjustHashForHashfull(currentHashMb = 1024, baseHashMb = 512, averageHashfull = 900, deviceRamMb = 4096)
        )
    }

    @Test
    fun testBinaryVarianteFolgtDenCpuMerkmalen() {
        val vorhanden = listOf(
            "libstockfish.so",
            "libstockfish-avx2.so",
            "libstockfish-bmi2.so",
            "libstockfish-vnni512.so",
            "libstockfish-armv8-dotprod.so"
        )
        // x86_64 mit AVX512-VNNI: die stärkste Variante gewinnt
        assertEquals(
            "libstockfish-vnni512.so",
            StockfishBridge.selectBinaryVariant(vorhanden, setOf("avx2", "bmi2", "avx512_vnni"))
        )
        // Ohne VNNI bleibt bmi2 vor avx2
        assertEquals("libstockfish-bmi2.so", StockfishBridge.selectBinaryVariant(vorhanden, setOf("avx2", "bmi2")))
        assertEquals("libstockfish-avx2.so", StockfishBridge.selectBinaryVariant(vorhanden, setOf("avx2")))
        // arm64 mit Dotprod
        assertEquals("libstockfish-armv8-dotprod.so", StockfishBridge.selectBinaryVariant(vorhanden, setOf("asimddp")))
        // Passt nichts, bleibt es bei der generischen Binary
        assertEquals("libstockfish.so", StockfishBridge.selectBinaryVariant(vorhanden, setOf("neon")))
        // Ist nur die generische Binary im APK, wird auch nur diese gewählt
        assertEquals(
            "libstockfish.so",
            StockfishBridge.selectBinaryVariant(listOf("libstockfish.so"), setOf("avx2", "bmi2", "avx512_vnni"))
        )
    }

    @Test
    fun testNeuePartieNurWennFigurenHinzukommen() {
        val grundstellung = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"
        val nachE4 = "rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq - 0 1"
        val mittelspiel = "r3k2r/pp3ppp/8/8/8/8/PP3PPP/R3K2R w KQkq - 0 20"

        // Erster Aufruf der Sitzung: es gibt keine Vorgeschichte
        assertTrue(StockfishBridge.isIndependentPosition(null, grundstellung))

        // Die Fortsetzung derselben Partie darf die Tabelle nicht leeren - genau daran krankte
        // die frühere Regel "28 Figuren oder mehr": sie traf in der ganzen Eröffnung zu und warf
        // die Tabelle bei jedem Zug weg.
        assertFalse(StockfishBridge.isIndependentPosition(grundstellung, nachE4))
        assertFalse(StockfishBridge.isIndependentPosition(nachE4, mittelspiel))

        // Zurück zur Grundstellung heißt: neue Partie
        assertTrue(StockfishBridge.isIndependentPosition(mittelspiel, grundstellung))

        // Und allgemein: kommen Figuren hinzu, kann es keine Fortsetzung sein
        assertTrue(StockfishBridge.isIndependentPosition(mittelspiel, nachE4))
    }

    @Test
    fun testGleicheStellungGiltNichtAlsNeuePartie() {
        val stellung = "r1bqkbnr/pppp1ppp/2n5/4p3/2B1P3/5N2/PPPP1PPP/RNBQK2R b KQkq - 0 1"
        assertFalse(StockfishBridge.isIndependentPosition(stellung, stellung))
    }
}
