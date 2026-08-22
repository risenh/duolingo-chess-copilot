package com.dulo.app

import com.dulo.app.core.UltraRobustClassifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Tests für die Bestimmung der eigenen Farbe aus der Ausgangsstellung.
 *
 * Regel: Was zu Beginn unten auf den beiden Reihen steht, gehört mir; oben steht der Gegner.
 * Hell oder dunkel entscheidet dann, ob ich Weiß oder Schwarz führe.
 */
class EigeneFarbeTest {

    /** Grundstellung so, wie sie auf dem Bildschirm steht (row 0 = oben) */
    private fun bildschirmGrundstellung(meineFigurenSindWeiss: Boolean): Array<CharArray> {
        val untenReihe = if (meineFigurenSindWeiss) "RNBQKBNR" else "rnbqkbnr"
        val untenBauern = if (meineFigurenSindWeiss) "PPPPPPPP" else "pppppppp"
        val obenReihe = if (meineFigurenSindWeiss) "rnbqkbnr" else "RNBQKBNR"
        val obenBauern = if (meineFigurenSindWeiss) "pppppppp" else "PPPPPPPP"
        return arrayOf(
            obenReihe.toCharArray(),
            obenBauern.toCharArray(),
            CharArray(8) { '.' },
            CharArray(8) { '.' },
            CharArray(8) { '.' },
            CharArray(8) { '.' },
            untenBauern.toCharArray(),
            untenReihe.toCharArray()
        )
    }

    @Test
    fun testHelleFigurenUntenBedeutetIchSpieleWeiss() {
        assertEquals(true, UltraRobustClassifier.sideFromStartingRows(bildschirmGrundstellung(true)))
    }

    @Test
    fun testDunkleFigurenUntenBedeutetIchSpieleSchwarz() {
        assertEquals(false, UltraRobustClassifier.sideFromStartingRows(bildschirmGrundstellung(false)))
    }

    @Test
    fun testLeeresBrettGibtKeineAuskunft() {
        assertNull(UltraRobustClassifier.sideFromStartingRows(Array(8) { CharArray(8) { '.' } }))
    }

    @Test
    fun testWidersprechendeReihenGebenKeineAuskunft() {
        // Unten und oben stehen jeweils helle Figuren: daraus lässt sich nichts ableiten
        val brett = Array(8) { CharArray(8) { '.' } }
        brett[7] = "RNBQKBNR".toCharArray()
        brett[0] = "RNBQKBNR".toCharArray()
        assertNull(UltraRobustClassifier.sideFromStartingRows(brett))
    }

    @Test
    fun testMittelspielMitLeererGegnerreiheZaehltDieMehrheitUnten() {
        // Der Gegner hat seine Grundreihen geräumt; unten stehen weiterhin die eigenen hellen Figuren
        val brett = Array(8) { CharArray(8) { '.' } }
        brett[7] = "R...K..R".toCharArray()
        brett[6] = "PPP..PPP".toCharArray()
        assertEquals(true, UltraRobustClassifier.sideFromStartingRows(brett))
    }

    @Test
    fun testFelderEinerFarbe() {
        val standard = arrayOf(
            "rnbqkbnr".toCharArray(),
            "pppppppp".toCharArray(),
            CharArray(8) { '.' },
            CharArray(8) { '.' },
            CharArray(8) { '.' },
            CharArray(8) { '.' },
            "PPPPPPPP".toCharArray(),
            "RNBQKBNR".toCharArray()
        )
        val weiss = UltraRobustClassifier.sideSquares(standard, whitePieces = true)
        val schwarz = UltraRobustClassifier.sideSquares(standard, whitePieces = false)
        assertEquals(16, weiss.size)
        assertEquals(16, schwarz.size)
        assertEquals(true, weiss.contains("e1"))
        assertEquals(true, schwarz.contains("e8"))
        assertEquals(emptySet<String>(), weiss.intersect(schwarz))
    }
}
