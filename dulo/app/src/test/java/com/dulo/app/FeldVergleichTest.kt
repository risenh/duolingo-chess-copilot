package com.dulo.app

import com.dulo.app.core.UltraRobustClassifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests für den Feldvergleich der Dauerbeobachtung: Er entscheidet, ob sich auf dem Brett etwas
 * bewegt hat, ohne dafür die volle Erkennung zu starten.
 */
class FeldVergleichTest {

    private fun leeresBrett() = FloatArray(64) { 120f }

    @Test
    fun testUnveraendertesBrettMeldetKeineAenderung() {
        val means = leeresBrett()
        val stds = FloatArray(64) { 4f }
        assertFalse(UltraRobustClassifier.boardCellsChanged(means, stds, means.copyOf(), stds.copyOf()))
    }

    @Test
    fun testLeichtesRauschenMeldetKeineAenderung() {
        val means = leeresBrett()
        val stds = FloatArray(64) { 4f }
        // Kompressionsrauschen bewegt Helligkeit und Streuung nur minimal
        val nachher = FloatArray(64) { means[it] + if (it % 2 == 0) 3f else -2f }
        val nachherStds = FloatArray(64) { stds[it] + 1.5f }
        assertFalse(UltraRobustClassifier.boardCellsChanged(means, stds, nachher, nachherStds))
    }

    @Test
    fun testFigurVerlaesstEinFeld() {
        val means = leeresBrett()
        val stds = FloatArray(64) { 4f }
        // Auf Feld 20 stand eine Figur (hohe Streuung), jetzt ist es leer
        stds[20] = 45f
        val nachherStds = stds.copyOf()
        nachherStds[20] = 4f
        assertTrue(UltraRobustClassifier.boardCellsChanged(means, stds, means.copyOf(), nachherStds))
    }

    @Test
    fun testFigurBetrittEinFeld() {
        val means = leeresBrett()
        val stds = FloatArray(64) { 4f }
        val nachherStds = stds.copyOf()
        nachherStds[42] = 48f
        assertTrue(UltraRobustClassifier.boardCellsChanged(means, stds, means.copyOf(), nachherStds))
    }

    @Test
    fun testFelderUnterDemPfeilWerdenUebersprungen() {
        val means = leeresBrett()
        val stds = FloatArray(64) { 4f }
        // Genau auf Feld 30 liegt der Pfeil und verfälscht die Helligkeit deutlich
        val nachher = means.copyOf()
        nachher[30] = 220f
        assertTrue(UltraRobustClassifier.boardCellsChanged(means, stds, nachher, stds.copyOf()))
        assertFalse(
            UltraRobustClassifier.boardCellsChanged(
                means, stds, nachher, stds.copyOf(), ignoredCells = setOf(30)
            )
        )
    }

    @Test
    fun testUnterschiedlicheGroessenGeltenAlsVeraendert() {
        assertTrue(
            UltraRobustClassifier.boardCellsChanged(
                FloatArray(64), FloatArray(64), FloatArray(16), FloatArray(16)
            )
        )
    }

}
