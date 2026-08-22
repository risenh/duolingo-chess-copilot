package com.dulo.app

import com.dulo.app.core.ChessLocator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

class ChessLocatorAlternationTest {

    /**
     * Erzeugt ein synthetisches 8x8-Standardbrett als Graustufenbild (64 abwechselnd helle und dunkle Felder)
     * In jedem Feld liegt mittig ein Störobjekt (simulierte Figur), um zu prüfen, ob die Ringabtastung die Feldmitte zuverlässig ausspart
     */
    private fun generateSyntheticBoardWithPieces(
        boardSize: Int = 800,
        lightVal: Float = 240f,
        darkVal: Float = 180f,
        pieceVal: Float = 50f // dunkle Figur in der Feldmitte
    ): Pair<FloatArray, Int> {
        val w = boardSize
        val h = boardSize
        val gray = FloatArray(w * h)
        val cs = boardSize / 8.0f

        for (r in 0 until 8) {
            for (c in 0 until 8) {
                val isLight = (r + c) % 2 == 0
                val bg = if (isLight) lightVal else darkVal
                val cx = (c + 0.5f) * cs
                val cy = (r + 0.5f) * cs
                val pieceRadius = cs * 0.25f // die Figur belegt 25 % des Feldradius

                val x1 = (c * cs).toInt()
                val x2 = ((c + 1) * cs).toInt()
                val y1 = (r * cs).toInt()
                val y2 = ((r + 1) * cs).toInt()

                for (y in y1 until y2) {
                    for (x in x1 until x2) {
                        val dx = x - cx
                        val dy = y - cy
                        val inPiece = (dx * dx + dy * dy) <= (pieceRadius * pieceRadius)
                        gray[y * w + x] = if (inPiece) pieceVal else bg
                    }
                }
            }
        }
        return Pair(gray, w)
    }

    @Test
    fun testRingAlternation_syntheticBoardWithPieces_scoresOneHundredPercent() {
        val (gray, size) = generateSyntheticBoardWithPieces(boardSize = 800)
        val score = ChessLocator.computeRingAlternationScore(gray, size, size, 0f, 0f, size.toFloat())
        // Da die Ringabtastung die Mitte ausspart, muss der Score 1.0 betragen (64 von 64 Treffern)
        assertEquals(1.0f, score, 0.001f)
    }

    @Test
    fun testRingAlternation_shiftedBox_scoreCollapses() {
        // Großes Bild mit weißem Hintergrund oben und unten (Höhe 1200, Brett bei y=200..1000)
        val w = 800
        val h = 1200
        val fullGray = FloatArray(w * h) { 255f } // komplett weißer Hintergrund
        val (boardGray, boardSize) = generateSyntheticBoardWithPieces(boardSize = 800)

        // Brett bei y=200 einbetten
        val boardY = 200
        for (y in 0 until boardSize) {
            System.arraycopy(boardGray, y * w, fullGray, (y + boardY) * w, w)
        }

        // 1. An der echten Brettposition (y=200) muss der Score nahe 1.0 liegen
        val trueScore = ChessLocator.computeRingAlternationScore(fullGray, w, h, 0f, boardY.toFloat(), boardSize.toFloat())
        assertEquals(1.0f, trueScore, 0.001f)

        // 2. Um 2 Felder verschoben (y=200 + 200 = 400) liegen die unteren 2 Reihen auf dem weißen Hintergrund: allRowsPass muss false und minRowScore <= 0.65 sein
        val shiftedRes = ChessLocator.computeRingAlternationDetailed(fullGray, w, h, 0f, (boardY + 200).toFloat(), boardSize.toFloat())
        assertFalse("Shifted box must fail allRowsPass", shiftedRes.allRowsPass)
        assertTrue("Shifted box minRowScore must be <= 0.65, but was ${shiftedRes.minRowScore}", shiftedRes.minRowScore <= 0.65f)
    }

    @Test
    fun testPhaseSearchStep_isStrictlyBounded() {
        // Die Schrittweite des Rückfallpfades muss strikt auf [-0.5, 0.5] begrenzt bleiben
        val steps = ChessLocator.getDegeneratePhaseSteps()
        assertTrue("Max step must be <= 0.6", steps.maxOrNull()!! <= 0.6f)
        assertTrue("Min step must be >= -0.6", steps.minOrNull()!! >= -0.6f)
    }
}
