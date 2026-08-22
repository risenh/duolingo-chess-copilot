package com.dulo.app

import com.dulo.app.core.UltraRobustClassifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Prüft die beiden Sonderzüge, die im Auto-Betrieb sonst durchfallen.
 *
 * Beide fielen bisher in die vollständige Erkennung - und genau dort entstehen die
 * Fehleinordnungen, die die fortgeschriebene Stellung verderben. Die Rochade kommt in den
 * meisten Partien vor, en passant seltener, aber ein stehengebliebener Geisterbauer macht
 * jede weitere Berechnung wertlos.
 */
class AutoZugTest {

    private fun board(vararg rows: String): Array<CharArray> {
        require(rows.size == 8)
        return Array(8) { r -> rows[r].toCharArray() }
    }

    /** Streuungen bauen: besetzte Felder bekommen einen deutlichen Wert, leere fast null */
    private fun stds(besetzt: Set<Int>): FloatArray =
        FloatArray(64) { if (it in besetzt) 45f else 0.5f }

    private val weissRochadeBereit = board(
        "....k...",
        "........",
        "........",
        "........",
        "........",
        "........",
        "PPPPPPPP",
        "R...K..R"
    )

    @Test
    fun testKurzeRochadeWeissWirdAbgelesen() {
        // e1 = 60, h1 = 63, g1 = 62, f1 = 61
        val vorher = stds(setOf(60, 63, 4))
        val nachher = stds(setOf(62, 61, 4))
        assertEquals(
            "e1g1",
            UltraRobustClassifier.detectCastling(vorher, nachher, weissRochadeBereit, isWhitePerspective = true)
        )
    }

    @Test
    fun testLangeRochadeWeissWirdAbgelesen() {
        // e1 = 60, a1 = 56, c1 = 58, d1 = 59
        val vorher = stds(setOf(60, 56, 4))
        val nachher = stds(setOf(58, 59, 4))
        assertEquals(
            "e1c1",
            UltraRobustClassifier.detectCastling(vorher, nachher, weissRochadeBereit, isWhitePerspective = true)
        )
    }

    @Test
    fun testRochadeAusSchwarzerSicht() {
        val brett = board(
            "r...k..r",
            "pppppppp",
            "........",
            "........",
            "........",
            "........",
            "........",
            "....K..."
        )
        // Aus schwarzer Sicht kehren sich die Feldnummern um: e8 = 63 - 4 = 59, h8 = 63 - 7 = 56,
        // g8 = 63 - 6 = 57, f8 = 63 - 5 = 58
        val vorher = stds(setOf(59, 56, 3))
        val nachher = stds(setOf(57, 58, 3))
        assertEquals(
            "e8g8",
            UltraRobustClassifier.detectCastling(vorher, nachher, brett, isWhitePerspective = false)
        )
    }

    @Test
    fun testOhneTurmInDerEckeKeineRochade() {
        val brett = board(
            "....k...",
            "........",
            "........",
            "........",
            "........",
            "........",
            "PPPPPPPP",
            "....K..R"
        )
        val vorher = stds(setOf(60, 56, 4))
        val nachher = stds(setOf(58, 59, 4))
        assertNull(UltraRobustClassifier.detectCastling(vorher, nachher, brett, isWhitePerspective = true))
    }

    @Test
    fun testGewoehnlicherZugIstKeineRochade() {
        val vorher = stds(setOf(60, 63, 4))
        val nachher = stds(setOf(60, 63, 12))
        assertNull(
            UltraRobustClassifier.detectCastling(vorher, nachher, weissRochadeBereit, isWhitePerspective = true)
        )
    }

    @Test
    fun testEnPassantNimmtDenGeschlagenenBauernMit() {
        // Schwarz hat gerade d7-d5 gezogen, Weiß schlägt mit e5 im Vorbeigehen auf d6
        val vorher = board(
            "....k...",
            "........",
            "........",
            "...pP...",
            "........",
            "........",
            "........",
            "....K..."
        )
        val nachher = UltraRobustClassifier.applyUciMove(vorher, "e5d6")
        requireNotNull(nachher)
        // Der eigene Bauer steht auf d6
        assertEquals('P', nachher[2][3])
        // Das Startfeld ist leer
        assertEquals('.', nachher[3][4])
        // Und der geschlagene Bauer neben dem Startfeld ist weg - ohne das bliebe ein Geisterbauer
        assertEquals('.', nachher[3][3])
    }

    @Test
    fun testGewoehnlicherSchlagzugLaesstDieNachbarnStehen() {
        val vorher = board(
            "....k...",
            "........",
            "...p....",
            "....P...",
            "........",
            "........",
            "........",
            "....K..."
        )
        val nachher = UltraRobustClassifier.applyUciMove(vorher, "e5d6")
        requireNotNull(nachher)
        assertEquals('P', nachher[2][3])
        assertEquals('.', nachher[3][4])
    }

    @Test
    fun testRochadeWirdNachgespieltUndNimmtDieRechte() {
        val nachher = UltraRobustClassifier.applyUciMove(weissRochadeBereit, "e1g1")
        requireNotNull(nachher)
        assertEquals('K', nachher[7][6])
        assertEquals('R', nachher[7][5])
        assertEquals("kq", UltraRobustClassifier.updateCastlingRights("KQkq", "e1g1", 'K'))
    }

    @Test
    fun testGewoehnlicherZugBrauchtZweiBeruehrungen() {
        // e2 = Feld 52, e4 = Feld 36 aus weißer Sicht
        assertEquals(
            listOf(52, 36),
            UltraRobustClassifier.tapCellsForMove("e2e4", isWhitePerspective = true)
        )
    }

    @Test
    fun testUmwandlungTipptNurStartUndZiel() {
        // Welche Figur es wird, entscheidet danach eine Berührung auf der eingeblendeten Tafel -
        // und die wird auf dem Bildschirm gesucht, nicht aus dem Zielfeld abgeleitet.
        // e7 = Feld 12, e8 = Feld 4
        assertEquals(
            listOf(12, 4),
            UltraRobustClassifier.tapCellsForMove("e7e8q", isWhitePerspective = true)
        )
    }

    @Test
    fun testUmwandlungAusSchwarzerSicht() {
        // Aus schwarzer Sicht kehren sich die Feldnummern um: e2 = 63 - 52 = 11, e1 = 63 - 60 = 3
        assertEquals(
            listOf(11, 3),
            UltraRobustClassifier.tapCellsForMove("e2e1q", isWhitePerspective = false)
        )
    }

    @Test
    fun testUnbrauchbarerZugLiefertKeineBeruehrungen() {
        assertNull(UltraRobustClassifier.tapCellsForMove("(none)", isWhitePerspective = true))
        assertNull(UltraRobustClassifier.tapCellsForMove("e2", isWhitePerspective = true))
        assertNull(UltraRobustClassifier.tapCellsForMove("", isWhitePerspective = true))
    }

    @Test
    fun testUmgewandelteFigurLandetAufDemBrett() {
        val vorher = board(
            "........",
            "....P...",
            "........",
            "........",
            "........",
            "........",
            "........",
            "....K..k"
        )
        val nachher = UltraRobustClassifier.applyUciMove(vorher, "e7e8q")
        requireNotNull(nachher)
        assertEquals('Q', nachher[0][4])
        assertEquals('.', nachher[1][4])
    }
}
