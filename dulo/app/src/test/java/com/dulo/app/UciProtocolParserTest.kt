package com.dulo.app

import com.dulo.app.engine.StockfishBridge
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit-Tests für die regulären Ausdrücke und den Zustandsautomaten zum Parsen der UCI-Ausgabezeilen
 */
class UciProtocolParserTest {

    @Test
    fun testParseBestMoveStandard() {
        val line1 = "bestmove e2e4 ponder e7e5"
        val move1 = StockfishBridge.parseBestMoveLine(line1)
        assertEquals("e2e4", move1)

        val line2 = "bestmove g8f6"
        val move2 = StockfishBridge.parseBestMoveLine(line2)
        assertEquals("g8f6", move2)

        val line3 = "bestmove (none)"
        val move3 = StockfishBridge.parseBestMoveLine(line3)
        assertEquals("(none)", move3)
    }

    @Test
    fun testParseBestMovePromotion() {
        // Schlüsselfall: Bauernumwandlung (Promotion)
        val lineQueen = "bestmove e7e8q ponder d8d7"
        assertEquals("e7e8q", StockfishBridge.parseBestMoveLine(lineQueen))

        val lineKnight = "bestmove a2a1n"
        assertEquals("a2a1n", StockfishBridge.parseBestMoveLine(lineKnight))

        val lineRook = "bestmove b7b8r"
        assertEquals("b7b8r", StockfishBridge.parseBestMoveLine(lineRook))

        val lineBishop = "bestmove h2h1b"
        assertEquals("h2h1b", StockfishBridge.parseBestMoveLine(lineBishop))
    }

    @Test
    fun testParseInfoScoreCp() {
        val infoLine = "info depth 14 seldepth 20 multipv 1 score cp 58 nodes 12345 nps 456789 time 27 pv e2e4 e7e5 g1f3"
        val eval = StockfishBridge.parseInfoLine(infoLine)

        assertEquals(14, eval?.depth)
        assertEquals(0.58f, eval?.evalScore ?: 0f, 0.001f)
        assertFalse(eval?.isMate ?: true)
    }

    @Test
    fun testParseInfoScoreNegativeCp() {
        val infoLine = "info depth 12 score cp -120 pv d7d5"
        val eval = StockfishBridge.parseInfoLine(infoLine)

        assertEquals(12, eval?.depth)
        assertEquals(-1.20f, eval?.evalScore ?: 0f, 0.001f)
    }

    @Test
    fun testParseInfoScoreMate() {
        // Weiß setzt in 2 Zügen matt
        val mateLinePositive = "info depth 16 score mate 2 pv f7f8q"
        val evalPos = StockfishBridge.parseInfoLine(mateLinePositive)

        assertTrue(evalPos?.isMate ?: false)
        assertEquals(100.0f, evalPos?.evalScore ?: 0f, 0.001f)

        // Schwarz wird mattgesetzt
        val mateLineNegative = "info depth 16 score mate -3 pv g8h8"
        val evalNeg = StockfishBridge.parseInfoLine(mateLineNegative)

        assertTrue(evalNeg?.isMate ?: false)
        assertEquals(-100.0f, evalNeg?.evalScore ?: 0f, 0.001f)
    }

    @Test
    fun testFallbackEvaluatorFindsExistingPieceMove() {
        // Der Fallback-Bewerter darf auf einem echten Brett niemals auf ein leeres Feld zeigen
        val fen = "r1bqkbnr/pppp1ppp/2n5/4p3/4P3/5N2/PPPP1PPP/RNBQKB1R w KQkq - 2 3"
        val eval = StockfishBridge.evaluateFallback(fen)
        assertNotNull(eval.bestMove)
        assertTrue(eval.bestMove.length in 4..5)
        
        val fromCol = eval.bestMove[0] - 'a'
        val fromRank = eval.bestMove[1] - '1'
        // Das Startfeld des Zuges muss eine eigene Figur tragen
        val rows = fen.split(" ")[0].split("/")
        val board = Array(8) { r ->
            val rowStr = rows[r]
            val expanded = StringBuilder()
            for (ch in rowStr) {
                if (ch.isDigit()) repeat(ch - '0') { expanded.append('.') }
                else expanded.append(ch)
            }
            expanded.toString().toCharArray()
        }
        val pieceAtFrom = board[7 - fromRank][fromCol]
        assertTrue("Piece at from square should be white", pieceAtFrom.isUpperCase())
    }

    @Test
    fun testFallbackBishopMovesDiagonally() {
        // Weiß hat nur einen Läufer auf g5: der Zug muss diagonal sein (ein gerader Zug wie g5g6 ist ausgeschlossen)
        val fen = "8/8/8/6B1/8/8/8/4K2k w - - 0 1"
        val eval = StockfishBridge.evaluateFallback(fen)
        assertEquals(0, eval.depth)
        assertEquals(0.0f, eval.evalScore, 0.001f)
        
        val fromSquare = eval.bestMove.substring(0, 2)
        val toSquare = eval.bestMove.substring(2, 4)
        assertEquals("g5", fromSquare)
        
        val dc = Math.abs(toSquare[0] - fromSquare[0])
        val dr = Math.abs(toSquare[1] - fromSquare[1])
        assertTrue("Bishop move must be diagonal (dc == dr)", dc == dr && dc > 0)
    }

    @Test
    fun testInvalidOrIrrelevantLines() {
        val invalidLine = "info currmove e2e4 currmovenumber 1"
        assertEquals(null, StockfishBridge.parseBestMoveLine(invalidLine))
    }

    @Test
    fun testEngineEvaluationDiagnosticInfoPreservation() {
        val diag = "[Stockfish-Berechnung erfolgreich]\nDauer: 120ms | Tiefe: 12 | Bewertung: +0.45 | Zug: e2e4"
        val eval = StockfishBridge.EngineEvaluation(
            bestMove = "e2e4",
            evalScore = 0.45f,
            depth = 12,
            isMate = false,
            diagnosticInfo = diag
        )
        assertEquals("e2e4", eval.bestMove)
        assertEquals(diag, eval.diagnosticInfo)
    }

    @Test
    fun testFallbackReturnsDiagnosticInfo() {
        val fen = "8/8/8/6B1/8/8/8/4K2k w - - 0 1"
        val eval = StockfishBridge.evaluateFallback(fen)
        assertTrue("Fallback result must contain diagnosticInfo", eval.diagnosticInfo.contains("[Fallback: reine Kotlin-Regeln]"))
    }

    @Test
    fun testSanityCheckRejectsInvalidFen() {
        // Eine Stellung ohne beide Könige muss abgewiesen werden
        val invalidFen = "8/8/8/8/8/8/8/8 w - - 0 1"
        val problem = StockfishBridge.validateFenSanity(invalidFen)
        assertNotNull("validateFenSanity should reject kingless board", problem)
    }

    @Test
    fun testSanityCheckAllowsActiveKingInCheck() {
        // Weißer König auf e1, schwarzer König auf e7, schwarzer Turm auf e8 gibt Schach, weißer Bauer auf a2: eine völlig normale legale Partie, die niemals als unmögliche Stellung gelten darf
        val inCheckFen = "4r3/4k3/8/8/8/8/P7/4K3 w - - 0 1"
        val problem = StockfishBridge.validateFenSanity(inCheckFen)
        assertNull("validateFenSanity must allow active king in check position", problem)
    }

    /**
     * Eine abgebrochene Suche muss abgewartet werden, bevor die naechste losgeschickt wird -
     * sonst beantwortet ihr "bestmove" die falsche Stellung. Als Schranke dient das Wort
     * "bestmove". Dieser Test haelt fest, dass keine andere Ausgabezeile darauf anschlaegt:
     * eine info-Zeile duerfte die Schranke nicht vorzeitig oeffnen.
     */
    @Test
    fun testNurDieAbschlusszeileBeendetDieSuche() {
        assertTrue(StockfishBridge.isSearchTerminationLine("bestmove e2e4 ponder e7e5"))
        assertTrue(StockfishBridge.isSearchTerminationLine("bestmove (none)"))
        assertTrue(StockfishBridge.isSearchTerminationLine("bestmove e7e8q"))

        assertFalse(
            StockfishBridge.isSearchTerminationLine(
                "info depth 22 seldepth 30 multipv 1 score cp 34 nodes 1234567 nps 900000 " +
                    "hashfull 210 tbhits 0 time 1400 pv e2e4 e7e5 g1f3 b8c6"
            )
        )
        assertFalse(StockfishBridge.isSearchTerminationLine("info depth 1 currmove e2e4 currmovenumber 1"))
        assertFalse(StockfishBridge.isSearchTerminationLine("readyok"))
        assertFalse(StockfishBridge.isSearchTerminationLine("info string NNUE evaluation using nn-xxxx.nnue"))
    }
}
