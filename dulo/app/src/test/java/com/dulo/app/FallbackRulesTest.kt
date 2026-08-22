package com.dulo.app

import com.dulo.app.engine.StockfishBridge
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * Tests für den FIDE-konformen Fallback-Regelsimulator von Stockfish: Schachabwehr, absolute Fesselung (Pin), Matt/Patt und die reine Zip-Extraktion
 */
class FallbackRulesTest {

    @Test
    fun testInCheckDefenseOnlyBug7() {
        // Echtes Endspiel aus bug_7.jpg: die schwarze Dame auf e4 gibt dem weißen König (e1) direkt Schach
        // Weiß hat keine Dame und der Springer auf b1 erreicht e2 nicht, legal sind nur: Be2 als Block auf e2, Be3 als Block auf e3, Kd1/Kd2 als Ausweichzug
        // Ein belangloser Bauernzug wie f5f6, der den König im Schach lässt, ist strikt verboten
        val fenBug7 = "r1b1k1nr/pppp1ppp/2n5/5P2/3bq3/8/PPP3PP/RNB1KB1R w KQkq - 0 1"
        val eval = StockfishBridge.evaluateFallback(fenBug7)

        val legalDefenseMoves = setOf(
            "f1e2", // Be2 (Läufer blockt auf e2)
            "c1e3", // Be3 (Läufer blockt auf e3)
            "e1d1", // Kd1 (König weicht aus)
            "e1d2"  // Kd2 (König weicht aus)
        )

        println("testInCheckDefenseOnlyBug7 bestMove=${eval.bestMove}")
        assertTrue("Der erzeugte Zug ${eval.bestMove} muss zur Menge der legalen Abwehrzüge $legalDefenseMoves gehören", legalDefenseMoves.contains(eval.bestMove))
        assertNotEquals("f5f6", eval.bestMove)
        assertEquals(0, eval.depth)
    }

    @Test
    fun testPinnedPieceCannotLeaveKingLineBug6() {
        // Echtes Endspiel aus bug_6.jpg: der weiße Läufer auf e2 deckt den weißen König auf e1 gegen die schwarze Dame auf e4 ab (absolute Fesselung)
        // Der Läufer darf nicht e2f3 ziehen, sonst steht der weiße König unmittelbar auf der Linie der schwarzen Dame
        val fenBug6 = "r3k1nr/ppp2ppp/2np1p2/8/3bq1b1/8/PPP1B1PP/RNB1K2R w KQkq - 0 1"
        val eval = StockfishBridge.evaluateFallback(fenBug6)

        println("testPinnedPieceCannotLeaveKingLineBug6 bestMove=${eval.bestMove}")
        assertNotEquals("e2f3", eval.bestMove)
    }

    @Test
    fun testNonCheckLegalMovesNotOverFiltered() {
        // Grundstellung: Weiß hat mindestens 20 legale Züge (16 Bauern- und 4 Springerzüge)
        val initialFen = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"
        val eval = StockfishBridge.evaluateFallback(initialFen)

        println("testNonCheckLegalMovesNotOverFiltered bestMove=${eval.bestMove}")
        assertTrue(eval.bestMove.length in 4..5)
        assertFalse(eval.isMate)
    }

    @Test
    fun testCheckmateHandling() {
        // Klassisches Narrenmatt (Fool's Mate): der weiße König auf e1 ist durch die schwarze Dame auf h4 matt, es gibt keinen legalen Zug
        val foolsMateFen = "rnb1kbnr/pppp1ppp/8/4p3/6Pq/5P2/PPPPP2P/RNBQKBNR w KQkq - 0 1"
        val eval = StockfishBridge.evaluateFallback(foolsMateFen)

        println("testCheckmateHandling bestMove=${eval.bestMove}, isMate=${eval.isMate}, score=${eval.evalScore}")
        assertEquals("(checkmate)", eval.bestMove)
        assertTrue(eval.isMate)
        assertEquals(-100.0f, eval.evalScore, 0.001f)
    }

    @Test
    fun testStalemateHandling() {
        // Klassische Pattstellung: schwarzer König auf a8, weißer König auf c7, weiße Dame auf b6. Schwarz ist am Zug, steht nicht im Schach und hat keinen legalen Zug
        val stalemateFen = "k7/2K5/1Q6/8/8/8/8/8 b - - 0 1"
        val eval = StockfishBridge.evaluateFallback(stalemateFen)

        println("testStalemateHandling bestMove=${eval.bestMove}, isMate=${eval.isMate}, score=${eval.evalScore}")
        assertEquals("(stalemate)", eval.bestMove)
        assertFalse(eval.isMate)
        assertEquals(0.0f, eval.evalScore, 0.001f)
    }

    @Test
    fun testBug8Bug10RealGameNoFalseCheckFiltering() {
        // Echte Gerätestellung aus bug_8.jpg / bug_10.jpg (Erkennungsperspektive: Schwarz): der schwarze König auf e8 steht nicht im Schach
        // (die g-Linie der Dame auf g4 blockt der eigene Bauer auf g7, die Diagonale der Bauer auf d7), der Fallback lieferte auf dem Gerät deterministisch b8a8 (rxa8)
        // Regressionsziel: der Probezug-Filter darf die Stellung nicht fälschlich als Schach werten und legale Züge verwerfen, illegale Königszüge (in die Linie von Bd6) müssen wegfallen
        val fenBug8 = "Nrb1k2r/pp1p2pp/3B1p2/3Bp3/4P1Q1/8/PPP2P1P/RN1R2K1 b KQkq - 0 1"
        val eval = StockfishBridge.evaluateFallback(fenBug8)

        val legalMoves = setOf(
            "b8a8", "e8d8", "h8g8", "h8f8",
            "a7a6", "a7a5", "b7b6", "b7b5",
            "g7g6", "g7g5", "h7h6", "h7h5", "f6f5"
        )
        val illegalKingMoves = setOf("e8e7", "e8f8", "e8f7")

        println("testBug8Bug10RealGameNoFalseCheckFiltering bestMove=${eval.bestMove}")
        assertTrue("Der erzeugte Zug ${eval.bestMove} muss zur Menge der legalen Züge $legalMoves gehören", legalMoves.contains(eval.bestMove))
        assertFalse("Ein illegaler Königszug in die Linie von Bd6 darf nicht ausgegeben werden", illegalKingMoves.contains(eval.bestMove))
        assertEquals("Die deterministische Ausgabe für bug_8/bug_10 auf dem Gerät ist b8a8", "b8a8", eval.bestMove)
        assertEquals(0, eval.depth)
        assertFalse(eval.isMate)
    }

    @Test
    fun testExtractBinaryFromZipSynthetic() {
        // Rein im Speicher erzeugtes Test-Zip mit dem Eintrag lib/arm64-v8a/libstockfish.so
        val tempZipFile = File.createTempFile("test_apk", ".zip")
        val targetBinFile = File.createTempFile("test_libstockfish", ".so")
        tempZipFile.deleteOnExit()
        targetBinFile.deleteOnExit()

        val dummyContent = "Stockfish-Synthetic-Binary-Data-For-JVM-Test".toByteArray()

        ZipOutputStream(tempZipFile.outputStream()).use { zos ->
            val entry = ZipEntry("lib/arm64-v8a/libstockfish.so")
            zos.putNextEntry(entry)
            zos.write(dummyContent)
            zos.closeEntry()
        }

        val zip = ZipFile(tempZipFile)
        val extracted = StockfishBridge.extractBinaryFromZip(
            zip = zip,
            supportedAbis = arrayOf("arm64-v8a", "armeabi-v7a"),
            targetFile = targetBinFile
        )
        zip.close()

        assertTrue("Synthetic zip extraction must succeed", extracted)
        assertEquals(dummyContent.size.toLong(), targetBinFile.length())
        assertEquals(String(dummyContent), targetBinFile.readText())
    }
}
