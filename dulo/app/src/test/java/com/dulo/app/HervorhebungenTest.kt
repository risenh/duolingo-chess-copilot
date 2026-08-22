package com.dulo.app

import com.dulo.app.core.UltraRobustClassifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Prüft das Ablesen eines Zuges auf einem Brett, das die Oberfläche einfärbt.
 *
 * Duolingo hebt Start- und Zielfeld des letzten Zuges farbig hervor und nimmt die vorherige
 * Hervorhebung wieder weg. Für die Feldabtastung heißt das: Bei jedem Zug ändern vier Felder
 * ihr Aussehen, obwohl nur zwei davon mit dem Zug zu tun haben. Genau daran fiel das Ablesen
 * zuvor durch - und ohne Ablesen lief alles über die vollständige Erkennung, die sich mit
 * jedem Zug weiter verrechnete.
 */
class HervorhebungenTest {

    private val leer = 200f
    private val leerHervorgehoben = 235f

    private fun board(vararg rows: String): Array<CharArray> {
        require(rows.size == 8)
        return Array(8) { r -> rows[r].toCharArray() }
    }

    /** Feldabtastung bauen: figuren bildet Feldnummer auf Helligkeit ab */
    private fun felder(
        figuren: Map<Int, Float>,
        hervorgehobenLeer: Set<Int> = emptySet()
    ): Pair<FloatArray, FloatArray> {
        val means = FloatArray(64) { if (it in hervorgehobenLeer) leerHervorgehoben else leer }
        val stds = FloatArray(64) { 0.5f }
        for ((cell, helligkeit) in figuren) {
            means[cell] = helligkeit
            stds[cell] = 45f
        }
        return means to stds
    }

    @Test
    fun testZugWirdTrotzWegfallenderHervorhebungAbgelesen() {
        // Vorher: eigener Zug lag auf e2-e4, beide Felder sind noch eingefärbt.
        // e2 (Feld 52) ist leer und hervorgehoben, e4 (Feld 36) trägt die Figur.
        val (vorherM, vorherS) = felder(
            figuren = mapOf(36 to 90f, 12 to 40f),
            hervorgehobenLeer = setOf(52)
        )
        // Jetzt zieht der Gegner e7-e5: Feld 12 wird leer, Feld 28 besetzt.
        // Gleichzeitig verschwindet die alte Hervorhebung auf Feld 52.
        val (nachherM, nachherS) = felder(
            figuren = mapOf(36 to 90f, 28 to 40f),
            hervorgehobenLeer = setOf(12)
        )

        assertEquals(
            UltraRobustClassifier.DetectedMove(12, 28),
            UltraRobustClassifier.detectMove(vorherM, vorherS, nachherM, nachherS)
        )
    }

    @Test
    fun testMehrdeutigesZielWirdUeberDieGangartAufgeloest() {
        // Zwei Felder haben sich verändert, die noch besetzt sind: das echte Schlagfeld und ein
        // Feld, dessen Hervorhebung nur weggenommen wurde. Ohne bekannte Stellung bleibt das
        // mehrdeutig; mit ihr entscheidet die Gangart der Figur.
        val brett = board(
            "....k...",
            "........",
            "........",
            "....p...",
            "........",
            "..B.....",
            "........",
            "....K..."
        )
        // Läufer auf c3 = Feld 42, schwarzer Bauer auf e5 = Feld 28
        val (vorherM, vorherS) = felder(mapOf(42 to 90f, 28 to 40f, 4 to 40f))
        // Läufer schlägt auf e5; zusätzlich verliert Feld 4 (schwarzer König) seine Hervorhebung
        val nachherM = vorherM.copyOf()
        val nachherS = vorherS.copyOf()
        nachherM[42] = leer
        nachherS[42] = 0.5f
        nachherM[28] = 90f
        nachherM[4] = 40f - 30f

        // Ohne Stellung: zwei Kandidaten, also kein Ergebnis
        assertNull(UltraRobustClassifier.detectMove(vorherM, vorherS, nachherM, nachherS))

        // Mit Stellung: nur e5 ist für den Läufer auf c3 erreichbar
        assertEquals(
            UltraRobustClassifier.DetectedMove(42, 28),
            UltraRobustClassifier.detectMove(
                vorherM, vorherS, nachherM, nachherS,
                standardBoard = brett, isWhitePerspective = true
            )
        )
    }

    @Test
    fun testGangartLaeufer() {
        val brett = board(
            "....k...",
            "........",
            "........",
            "....p...",
            "........",
            "..B.....",
            "........",
            "....K..."
        )
        // c3 = Feld 42, e5 (diagonal, schlagbar) = Feld 28, d5 (nicht diagonal) = Feld 27
        assertTrue(UltraRobustClassifier.canPieceReach(brett, 42, 28, isWhitePerspective = true))
        assertFalse(UltraRobustClassifier.canPieceReach(brett, 42, 27, isWhitePerspective = true))
    }

    @Test
    fun testGangartSpringerUndBlockierterTurm() {
        val brett = board(
            "....k...",
            "........",
            "........",
            "........",
            "........",
            "........",
            "PPPPPPPP",
            "R...K..N"
        )
        // a1 = Feld 56: der Turm steht hinter der eigenen Bauernreihe
        assertFalse(UltraRobustClassifier.canPieceReach(brett, 56, 40, isWhitePerspective = true))
        // h1 = Feld 63, Springer nach g3 = Feld 46
        assertTrue(UltraRobustClassifier.canPieceReach(brett, 63, 46, isWhitePerspective = true))
    }

    @Test
    fun testGangartBauerZiehtNichtAufBesetztesFeld() {
        val brett = board(
            "....k...",
            "........",
            "........",
            "........",
            "....p...",
            "....P...",
            "........",
            "....K..."
        )
        // e3 = Feld 44, e4 = Feld 36 (durch den schwarzen Bauern besetzt)
        assertFalse(UltraRobustClassifier.canPieceReach(brett, 44, 36, isWhitePerspective = true))
    }

    @Test
    fun testGangartAusSchwarzerSicht() {
        // Dieselbe Stellung, aber das Brett steht gedreht: die Feldnummern kehren sich um
        val brett = board(
            "....k...",
            "........",
            "........",
            "....p...",
            "........",
            "..B.....",
            "........",
            "....K..."
        )
        // Aus schwarzer Sicht liegt c3 auf Feld 63 - 42 = 21, e5 auf 63 - 28 = 35
        assertTrue(UltraRobustClassifier.canPieceReach(brett, 21, 35, isWhitePerspective = false))
    }
}
