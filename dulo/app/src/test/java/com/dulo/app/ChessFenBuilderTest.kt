package com.dulo.app

import com.dulo.app.core.UltraRobustClassifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.Ignore

/**
 * Unit-Tests für das Verdichten der 8x8-Brettmatrix zum FEN, das Spiegeln der Perspektive, die Eindeutigkeit beider Könige, das Bauernverbot auf Reihe 1/8 und die Anzahlgrenzen
 */
class ChessFenBuilderTest {

    @Test
    fun testInitialPositionWhitePerspective() {
        val board = arrayOf(
            charArrayOf('r', 'n', 'b', 'q', 'k', 'b', 'n', 'r'),
            charArrayOf('p', 'p', 'p', 'p', 'p', 'p', 'p', 'p'),
            charArrayOf('.', '.', '.', '.', '.', '.', '.', '.'),
            charArrayOf('.', '.', '.', '.', '.', '.', '.', '.'),
            charArrayOf('.', '.', '.', '.', '.', '.', '.', '.'),
            charArrayOf('.', '.', '.', '.', '.', '.', '.', '.'),
            charArrayOf('P', 'P', 'P', 'P', 'P', 'P', 'P', 'P'),
            charArrayOf('R', 'N', 'B', 'Q', 'K', 'B', 'N', 'R')
        )

        val result = UltraRobustClassifier.buildFenFromBoard(board, isWhitePerspective = true)
        assertEquals("rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR", result.boardFen)
        assertEquals("rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1", result.fullFen)
        assertTrue(result.isWhitePerspective)
    }

    @Test
    fun testBlackPerspectiveFlipping() {
        val rawScreenBoard = arrayOf(
            charArrayOf('R', 'N', 'B', 'K', 'Q', 'B', 'N', 'R'),
            charArrayOf('P', 'P', 'P', '.', 'P', 'P', 'P', 'P'),
            charArrayOf('.', '.', '.', '.', '.', '.', '.', '.'),
            charArrayOf('.', '.', '.', 'P', '.', '.', '.', '.'),
            charArrayOf('.', '.', '.', '.', '.', '.', '.', '.'),
            charArrayOf('.', '.', '.', '.', '.', '.', '.', '.'),
            charArrayOf('p', 'p', 'p', 'p', 'p', 'p', 'p', 'p'),
            charArrayOf('r', 'n', 'b', 'k', 'q', 'b', 'n', 'r')
        )

        val result = UltraRobustClassifier.buildFenFromBoard(rawScreenBoard, isWhitePerspective = false)
        assertFalse(result.isWhitePerspective)
        assertEquals("b", result.activeColor)
        assertEquals("rnbqkbnr", result.boardFen.split('/')[0])
        assertEquals("RNBQKBNR", result.boardFen.split('/')[7])
    }

    @Test
    fun testEmptySquareRowCompression() {
        val row = charArrayOf('.', '.', '.', 'p', '.', '.', '.', '.')
        val compressed = UltraRobustClassifier.compressRow(row)
        assertEquals("3p4", compressed)

        val fullEmptyRow = charArrayOf('.', '.', '.', '.', '.', '.', '.', '.')
        assertEquals("8", UltraRobustClassifier.compressRow(fullEmptyRow))
    }

    @Ignore("Das Nachfüllen wurde abgeschafft, dieser Test ist hinfällig")
    @Test
    fun testZeroKingRecovery() {
        // Schlüsselfall: erkennt die Klassifikation keinen weißen König, füllte der Algorithmus ihn früher auf der weißen Grundreihe nach
        val boardWithNoWhiteKing = arrayOf(
            charArrayOf('r', 'n', 'b', 'q', 'k', 'b', 'n', 'r'),
            charArrayOf('p', 'p', 'p', 'p', 'p', 'p', 'p', 'p'),
            charArrayOf('.', '.', '.', '.', '.', '.', '.', '.'),
            charArrayOf('.', '.', '.', '.', '.', '.', '.', '.'),
            charArrayOf('.', '.', '.', '.', '.', '.', '.', '.'),
            charArrayOf('.', '.', '.', '.', '.', '.', '.', '.'),
            charArrayOf('P', 'P', 'P', 'P', 'P', 'P', 'P', 'P'),
            charArrayOf('R', 'N', 'B', 'Q', '.', 'B', 'N', 'R') // Feld des weißen Königs ist leer
        )

        val sanitized = UltraRobustClassifier.sanitizeBoard(boardWithNoWhiteKing)
        var whiteKingCount = 0
        var blackKingCount = 0
        for (r in 0..7) {
            for (c in 0..7) {
                if (sanitized[r][c] == 'K') whiteKingCount++
                if (sanitized[r][c] == 'k') blackKingCount++
            }
        }
        assertEquals(1, whiteKingCount)
        assertEquals(1, blackKingCount)
    }

    @Test
    fun testRank1AndRank8IllegalPawnCleaning() {
        // Schlüsselfall: auf Reihe 1 und Reihe 8 darf im Schach niemals ein Bauer stehen (Lichess weist das hart ab), hier wird die Bereinigung geprüft
        val boardWithIllegalPawns = arrayOf(
            charArrayOf('r', 'n', 'b', 'P', 'k', 'b', 'n', 'r'), // weißer Bauer P auf Reihe 8
            charArrayOf('p', 'p', 'p', 'p', 'p', 'p', 'p', 'p'),
            charArrayOf('.', '.', '.', '.', '.', '.', '.', '.'),
            charArrayOf('.', '.', '.', '.', '.', '.', '.', '.'),
            charArrayOf('.', '.', '.', '.', '.', '.', '.', '.'),
            charArrayOf('.', '.', '.', '.', '.', '.', '.', '.'),
            charArrayOf('P', 'P', 'P', 'P', 'P', 'P', 'P', 'P'),
            charArrayOf('R', 'N', 'B', 'Q', 'K', 'B', 'p', 'R')  // schwarzer Bauer p auf Reihe 1
        )

        val sanitized = UltraRobustClassifier.sanitizeBoard(boardWithIllegalPawns)
        // Prüfen, dass auf Reihe 8 (row 0) und Reihe 1 (row 7) kein Bauer mehr steht
        for (c in 0..7) {
            assertFalse("Rank 8 cannot have white pawn", sanitized[0][c] == 'P')
            assertFalse("Rank 8 cannot have black pawn", sanitized[0][c] == 'p')
            assertFalse("Rank 1 cannot have white pawn", sanitized[7][c] == 'P')
            assertFalse("Rank 1 cannot have black pawn", sanitized[7][c] == 'p')
        }
    }

    @Test
    fun testPieceCountMaxLimits() {
        // Schlüsselfall: strikte Abwertung, wenn eine Figurenart die Obergrenze überschreitet (z. B. mehr als eine weiße Dame oder mehr als acht weiße Bauern)
        val boardWithThreeQueens = arrayOf(
            charArrayOf('r', 'n', 'b', 'q', 'k', 'b', 'n', 'r'),
            charArrayOf('p', 'p', 'p', 'p', 'p', 'p', 'p', 'p'),
            charArrayOf('.', '.', 'Q', '.', 'Q', '.', '.', '.'), // überzählige Dame
            charArrayOf('.', '.', '.', '.', '.', '.', '.', '.'),
            charArrayOf('.', '.', '.', '.', '.', '.', '.', '.'),
            charArrayOf('.', '.', '.', '.', '.', '.', '.', '.'),
            charArrayOf('P', 'P', 'P', 'P', 'P', 'P', 'P', 'P'),
            charArrayOf('R', 'N', 'B', 'Q', 'K', 'B', 'N', 'R')
        )

        val sanitized = UltraRobustClassifier.sanitizeBoard(boardWithThreeQueens)
        var whiteQueenCount = 0
        for (r in 0..7) {
            for (c in 0..7) {
                if (sanitized[r][c] == 'Q') whiteQueenCount++
            }
        }
        assertEquals("White queen count should strictly be 1", 1, whiteQueenCount)
    }

    @Test
    fun testRowConservation() {
        val board = arrayOf(
            charArrayOf('r', 'n', 'b', 'q', 'k', 'b', 'n', 'r'),
            charArrayOf('.', 'p', '.', 'p', '.', 'p', '.', 'p'),
            charArrayOf('.', '.', '.', '.', '.', '.', '.', '.'),
            charArrayOf('.', '.', '.', '.', 'P', '.', '.', '.'),
            charArrayOf('.', '.', '.', 'p', '.', '.', '.', '.'),
            charArrayOf('.', '.', '.', '.', '.', '.', '.', '.'),
            charArrayOf('P', '.', 'P', '.', 'P', '.', 'P', '.'),
            charArrayOf('R', 'N', 'B', 'Q', 'K', 'B', 'N', 'R')
        )

        val result = UltraRobustClassifier.buildFenFromBoard(board, isWhitePerspective = true)
        val ranks = result.boardFen.split('/')
        assertEquals(8, ranks.size)
        for (rk in ranks) {
            var sum = 0
            for (ch in rk) {
                if (ch.isDigit()) sum += ch - '0'
                else sum += 1
            }
            assertEquals(8, sum)
        }
    }

    @Test
    fun testBuildFenWithTelemetryAndPerspectiveLock() {
        // Endspielstellung: die weißen Bauern sind im Zentrum weit vorgerückt, auf der Grundreihe steht nur noch der weiße König, während schwarze Dame und Turm dort eingedrungen sind (unten mehr schwarze als weiße Figuren)
        val endgameBoard = arrayOf(
            charArrayOf('r', '.', '.', '.', 'k', '.', '.', '.'),
            charArrayOf('.', '.', '.', '.', '.', '.', '.', '.'),
            charArrayOf('.', '.', '.', '.', '.', '.', '.', '.'),
            charArrayOf('.', '.', 'P', '.', '.', 'P', '.', '.'),
            charArrayOf('.', '.', '.', '.', '.', '.', '.', '.'),
            charArrayOf('.', '.', '.', '.', '.', '.', '.', '.'),
            charArrayOf('.', '.', '.', '.', '.', '.', '.', '.'),
            charArrayOf('q', '.', '.', '.', 'K', '.', '.', 'r')
        )

        // Prüfung 1: buildFenFromBoard mit den Telemetrieparametern
        val result = UltraRobustClassifier.buildFenFromBoard(
            rawBoard = endgameBoard,
            isWhitePerspective = true, // Perspektive der Sitzung ist auf Weiß gesperrt
            medianSim = 0.985f,
            occupiedCount = 6
        )

        assertEquals("w", result.activeColor)
        assertTrue(result.isWhitePerspective)
        assertEquals(0.985f, result.medianSim, 0.001f)
        assertEquals(6, result.occupiedCount)
        assertEquals("r3k3/8/8/2P2P2/8/8/8/q3K2r", result.boardFen)
    }

    @Test
    fun testResolvePerspectiveLockStateMachine() {
        // 1. Automatische Sperre in der Eröffnung: 32 belegte Felder, hohe Confidence -> gesperrt auf Weiß (true)
        val lock1 = UltraRobustClassifier.resolvePerspectiveLock(
            currentLock = null,
            detectedPerspective = true,
            occupiedCount = 32,
            medianSim = 0.95f
        )
        assertEquals(true, lock1)

        // 2. Schutz gegen Kippen im Endspiel: bereits auf Weiß (true) gesperrt, nur noch 6 Figuren, der Angriff auf der Grundreihe lässt die Erkennung fälschlich Schwarz (false) melden -> Sperre bleibt Weiß (true)
        val lock2 = UltraRobustClassifier.resolvePerspectiveLock(
            currentLock = lock1,
            detectedPerspective = false,
            occupiedCount = 6,
            medianSim = 0.88f
        )
        assertEquals(true, lock2)

        // 3. Neue Partie, Neukalibrierung: wieder 30 belegte Felder (>=26), Seitenwechsel auf Schwarz (false) -> Sperre wird zwangsweise auf Schwarz (false) gesetzt
        val lock3 = UltraRobustClassifier.resolvePerspectiveLock(
            currentLock = lock2,
            detectedPerspective = false,
            occupiedCount = 30,
            medianSim = 0.96f
        )
        assertEquals(false, lock3)

        // 4. Hürde bei niedriger Confidence: ohne Sperre, nur 8 Figuren und niedriger Sim (0.58f) -> es wird noch nicht gesperrt (null)
        val lock4 = UltraRobustClassifier.resolvePerspectiveLock(
            currentLock = null,
            detectedPerspective = true,
            occupiedCount = 8,
            medianSim = 0.58f
        )
        assertEquals(null, lock4)
    }
}
