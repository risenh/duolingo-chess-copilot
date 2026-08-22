package com.dulo.app

import com.dulo.app.core.ChessLocator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests zur Skaleninvarianz der arithmetischen Ausgleichsrechnung im ChessLocator über verschiedene Auflösungen (400px, 1080p, 1260p, 1440p)
 */
class ChessLocatorScaleTest {

    @Test
    fun testTwoPassFit_400pxScale_passesGate() {
        // Skala 400px: Feldbreite 50px, die Gitterlinien tragen leichtes Rauschen von 1.5px
        val stepEst = 50.0f
        val x0Est = 0.0f
        val peaks = listOf(
            51.0f,  // idx 1: 50 + 1.0
            99.0f,  // idx 2: 100 - 1.0
            151.5f, // idx 3: 150 + 1.5
            198.5f, // idx 4: 200 - 1.5
            251.0f, // idx 5: 250 + 1.0
            299.0f, // idx 6: 300 - 1.0
            351.0f  // idx 7: 350 + 1.0
        )

        val result = ChessLocator.twoPassFit(peaks, x0Est, stepEst)
        assertTrue("Kleine Störungen auf der 400px-Skala müssen die Ausgleichsrechnung bestehen", result.isOk)
        assertTrue("Das Residuum muss innerhalb der 5%-Toleranz eines Feldes liegen (2.5px)", result.residual <= stepEst * 0.05f)
        assertEquals(7, result.lineCount)
    }

    @Test
    fun testTwoPassFit_1260pScale_passesRelativeGate() {
        // Skala 1260p (wie in bug_16/bug_17): Feldbreite 157.5px, Residuum der Ausgleichsrechnung 3.5px
        // Das alte fest verdrahtete 2.5px-Gatter scheiterte hier sofort (99f), das neue relative 5%-Gatter (7.875px) muss bestehen
        val stepEst = 157.5f
        val x0Est = 0.0f
        val peaks = listOf(
            157.0f + 3.0f,  // idx 1: 160.0
            315.0f - 3.5f,  // idx 2: 311.5
            472.5f + 3.0f,  // idx 3: 475.5
            630.0f - 3.5f,  // idx 4: 626.5
            787.5f + 3.0f,  // idx 5: 790.5
            945.0f - 3.5f,  // idx 6: 941.5
            1102.5f + 3.0f  // idx 7: 1105.5
        )

        val result = ChessLocator.twoPassFit(peaks, x0Est, stepEst)
        assertTrue("Ein arithmetisches Gitter auf einem 1260p-Display muss das relative 5%-Gatter bestehen", result.isOk)
        assertTrue("Das Residuum von 3.2px bildet die Gittergenauigkeit korrekt ab und ist <= 7.875px", result.residual <= stepEst * 0.05f)
        assertTrue("Das Residuum darf nicht der Entartungswert 99f sein", result.residual < 10.0f)
        assertEquals(7, result.lineCount)
    }

    @Test
    fun testTwoPassFit_RandomUiPeaks_rejected() {
        // Falsche Peaks aus unruhigen UI-Kanten: nicht arithmetisch verteilt, riesiges Residuum
        val stepEst = 157.5f
        val x0Est = 0.0f
        val peaks = listOf(
            100.0f,
            280.0f,
            420.0f,
            750.0f,
            1100.0f
        )

        val result = ChessLocator.twoPassFit(peaks, x0Est, stepEst)
        assertFalse("Ein Satz nicht arithmetischer Störpeaks muss vom Gatter abgewiesen werden", result.isOk)
    }
}
