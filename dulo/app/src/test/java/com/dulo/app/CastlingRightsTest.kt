package com.dulo.app

import com.dulo.app.core.UltraRobustClassifier
import com.dulo.app.engine.StockfishBridge
import org.junit.Assert.assertEquals
import org.junit.Test

class CastlingRightsTest {

    @Test
    fun testInitialPosition_hasFullCastlingRights() {
        val board = Array(8) { CharArray(8) { '.' } }
        // Rank 8 (row 0)
        board[0][0] = 'r'; board[0][4] = 'k'; board[0][7] = 'r'
        // Rank 1 (row 7)
        board[7][0] = 'R'; board[7][4] = 'K'; board[7][7] = 'R'

        val castling = UltraRobustClassifier.computeCastlingRights(board)
        assertEquals("KQkq", castling)
    }

    @Test
    fun testUserScreenshotCase_kingsMoved_hasNoCastlingRights() {
        // Screenshot_20260819_162907.jpg: White King at f1 (7, 5), Black King at h7 (1, 7)
        val board = Array(8) { CharArray(8) { '.' } }
        board[1][7] = 'k' // h7
        board[7][4] = 'R' // e1 has Rook, not King!
        board[7][5] = 'K' // f1 has King

        val castling = UltraRobustClassifier.computeCastlingRights(board)
        assertEquals("-", castling)

        // Test buildFenFromBoard produces valid fullFen
        val res = UltraRobustClassifier.buildFenFromBoard(board, isWhitePerspective = true)
        assertEquals("8/7k/8/8/8/8/8/4RK2 w - - 0 1", res.fullFen)
    }

    @Test
    fun testPartialCastling_onlyWhiteKingside() {
        val board = Array(8) { CharArray(8) { '.' } }
        board[7][4] = 'K' // e1
        board[7][7] = 'R' // h1
        // Black king has moved
        board[0][6] = 'k' // g8

        val castling = UltraRobustClassifier.computeCastlingRights(board)
        assertEquals("K", castling)
    }

    @Test
    fun testPartialCastling_blackBoth_whiteQueensideOnly() {
        val board = Array(8) { CharArray(8) { '.' } }
        board[7][4] = 'K'; board[7][0] = 'R' // e1, a1
        board[0][4] = 'k'; board[0][0] = 'r'; board[0][7] = 'r' // e8, a8, h8

        val castling = UltraRobustClassifier.computeCastlingRights(board)
        assertEquals("Qkq", castling)
    }

    @Test
    fun testStockfishBridge_sanitizeCastlingRights_cleansInvalidKQkq() {
        // Dirty FEN with invalid KQkq when Kings have moved
        val dirtyFen = "1Q6/p5pk/3p3p/8/p1p1P3/P1q3P1/2P2P1P/4RK2 w KQkq - 0 1"
        val cleaned = StockfishBridge.sanitizeCastlingRights(dirtyFen)
        assertEquals("1Q6/p5pk/3p3p/8/p1p1P3/P1q3P1/2P2P1P/4RK2 w - - 0 1", cleaned)
    }

    @Test
    fun testStockfishBridge_sanitizeCastlingRights_preservesValidRights() {
        val validFen = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"
        val cleaned = StockfishBridge.sanitizeCastlingRights(validFen)
        assertEquals("rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1", cleaned)
    }
}
