package com.dulo.app.core

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Rect
import java.io.InputStream
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Rein in Kotlin geschriebener multimodaler 2D-Figurenerkenner für Duolingo-Schach (V7, semantisches Qualitätsgatter)
 * Kernmechanik:
 * 1. Absolutes Qualitätsgatter für die Figurensemantik (MedianSim >= 0.52f && belegt >= 4) blockt Nicht-Brett-Bilder (Startseite, Lobby) zu 100 %
 * 2. Nach dem Belegungsgatter folgen adaptive vertikale Vordergrund-Schwerpunktsuche und Schiebefenster-Klemmung (x0=clamp, y0=clamp) gegen abgeschnittene Figurenköpfe in Reihe 0 und Dimensionskollaps
 * 3. Score-Fusion zweier Regionen: 0.65 * cos(f_body, t_body) + 0.35 * cos(f_head, t_head) trennt Turm/Dame/Läufer/Bauer/Springer/König präzise
 * 4. Adaptives 2-Means-Clustering der Farbzugehörigkeit inklusive Einfarbigkeitsschutz (Spannweite < 35)
 * 5. Strikte Einhaltung der Lichess-Regelkonventionen und der Obergrenzen für die Figurenanzahl
 */
class UltraRobustClassifier(context: Context? = null) {

    data class TemplateFeature(
        val className: String,
        val bodyNorm: FloatArray,
        val headNorm: FloatArray
    )

    data class CellFeature(
        val bodyNorm: FloatArray,
        val headNorm: FloatArray,
        val centerStd: Float,
        val centerMean: Float,
        val gradMean: Float
    )

    sealed class ClassificationResponse {
        data class Success(
            val result: DetectionResult,
            val medianSim: Float,
            val occupiedCount: Int,
            val detectedPerspective: Boolean,
            // Confidence der Perspektiverkennung in [0..1] und das dabei ausschlaggebende Signal:
            // Der Aufrufer sperrt die Perspektive nur bei ausreichender Confidence und kann sie sonst anzeigen
            val perspectiveConfidence: Float = 0.0f,
            val perspectiveReason: String = "",
            // Zellenweise Telemetrie (für die Fälle bug_11~14): unsichere belegte Felder und vom Gatter verworfene Kandidaten, Grundlage für die Kalibrierung der Einzelfeld-Schwellen
            val lowConfidenceCells: List<String> = emptyList(),
            val gateRejectedCells: List<String> = emptyList()
        ) : ClassificationResponse()

        data class Rejected(
            val reason: String,
            val medianSim: Float,
            val occupiedCount: Int,
            // Zellenweise Telemetrie beim Abweisen (für bug_18 und fälschlich geblockte Bretter mit niedrigem Sim): Bildschirmkoordinaten r{r}c{c}=Klasse(sim)
            val lowConfidenceCells: List<String> = emptyList(),
            val gateRejectedCells: List<String> = emptyList()
        ) : ClassificationResponse()
    }

    /**
     * Ergebnis der Zugerkennung über den Feldvergleich.
     * @param fromCell Feldindex (Zeile * 8 + Spalte) im Bildschirmraster, das leer geworden ist
     * @param toCell Feldindex, auf dem die Figur jetzt steht
     */
    data class DetectedMove(val fromCell: Int, val toCell: Int)

    /**
     * Ergebnis der Perspektiverkennung
     * @param isWhitePerspective true = Weiß sitzt unten (eigene Farbe Weiß)
     * @param confidence Betrag der gewichteten Signalsumme in [0..1]; 0 = die Signale heben sich auf
     * @param reason das Signal mit dem größten Beitrag, für Anzeige und Forensik
     */
    data class PerspectiveVerdict(
        val isWhitePerspective: Boolean,
        val confidence: Float,
        val reason: String
    )

    data class DetectionResult(
        val boardFen: String,
        val fullFen: String,
        val activeColor: String,
        val isWhitePerspective: Boolean,
        val rawBoard: Array<CharArray>,
        val standardBoard: Array<CharArray>,
        val boardRect: Rect,
        val medianSim: Float = 1.0f,
        val occupiedCount: Int = 0
    )

    private val templates = mutableListOf<TemplateFeature>()

    init {
        if (context != null) {
            loadTemplatesFromAssets(context)
        }
    }

    private fun loadTemplatesFromAssets(context: Context) {
        try {
            val templateFiles = context.assets.list("templates") ?: emptyArray()
            for (filename in templateFiles) {
                if (!filename.endsWith(".png")) continue
                val clsName = filename.split("_")[0].uppercase()
                val inputStream: InputStream = context.assets.open("templates/$filename")
                val bmp = BitmapFactory.decodeStream(inputStream)
                inputStream.close()

                if (bmp != null) {
                    val feat = extractFeatureFromBitmap(bmp)
                    templates.add(TemplateFeature(clsName, feat.bodyNorm, feat.headNorm))
                    bmp.recycle()
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * Ausführliche Klassifikationspipeline: liefert die Confidence-Kennzahlen (medianSim, occupiedCount) und den Abweisungsgrund mit zurück
     * Ein per Sitzung gesperrter overridePerspective kann übergeben werden, um Perspektivfehler im Endspiel zu verhindern
     */
    fun classifyBoardDetailed(
        bitmap: Bitmap,
        boardRect: Rect,
        overridePerspective: Boolean? = null
    ): ClassificationResponse {
        // Defensive Klemmung (zweite Absicherung neben dem isCropped-Hinweis des Locators): Das Rect zugeschnittener Frames bzw. Rettungskandidaten kann über den Bildrand hinausragen,
        // ein direktes createBitmap würde eine IllegalArgumentException werfen; schrumpft das Rect beim Schnitt mit dem Vollbild, gilt der Frame als unvollständig und wird abgewiesen (nur Hinweis, kein hartes Matching)
        val safeRect = Rect(boardRect)
        val fullyInside = safeRect.intersect(0, 0, bitmap.width, bitmap.height) &&
            safeRect.width() == boardRect.width() && safeRect.height() == boardRect.height()
        if (!fullyInside || safeRect.width() < 8) {
            return ClassificationResponse.Rejected(reason = "CROPPED_RECT", medianSim = 0f, occupiedCount = 0)
        }
        val step = (safeRect.right - safeRect.left) / 8.0f
        val cellsFeats = Array(8) { r ->
            Array(8) { c ->
                val x1 = (safeRect.left + c * step).toInt()
                val y1 = (safeRect.top + r * step).toInt()
                val x2 = (safeRect.left + (c + 1) * step).toInt()
                val y2 = (safeRect.top + (r + 1) * step).toInt()

                val cellW = max(1, x2 - x1)
                val cellH = max(1, y2 - y1)
                val cellBmp = Bitmap.createBitmap(bitmap, x1, y1, cellW, cellH)
                val feat = extractFeatureFromBitmap(cellBmp)
                if (cellBmp !== bitmap) cellBmp.recycle()
                feat
            }
        }

        // 1. Belegungsgatter und Kosinus-Abgleich mit den Templates
        data class OccupiedCell(
            val r: Int,
            val c: Int,
            val feat: CellFeature,
            val primaryClass: String,
            val bestSimilarity: Float,
            val secondaryClass: String,
            val kingSimilarity: Float
        )
        val occupiedList = mutableListOf<OccupiedCell>()
        // Vom Gatter verworfene Kandidaten: Felder mit zu geringer Zentrumsvarianz, deren Kantengradient aber bereits über der Schwelle liegt - Hauptverdächtige für "echte Figur fälschlich als leer erkannt" (verdeckte Figuren, bug_13/14)
        val gateRejected = mutableListOf<String>()

        for (r in 0..7) {
            for (c in 0..7) {
                val f = cellsFeats[r][c]
                // Belegungsgatter: leere Felder haben extrem niedrige Zentrumsvarianz und Kantengradienten (gradMean >= 22.0 filtert 2.5D-Perspektivschatten und leichte Überlappungen am oberen Rand zuverlässig heraus)
                if (f.centerStd < 6.0f || f.gradMean < 22.0f) {
                    if (f.gradMean >= 22.0f) {
                        gateRejected.add("r${r}c${c}|std=${String.format("%.1f", f.centerStd)}|grad=${String.format("%.1f", f.gradMean)}")
                    }
                    continue
                }

                var bestCls = "P"
                var bestSim = -1e9f
                var secondCls = "P"
                var secondSim = -1e9f
                var kingSim = -1e9f

                for (t in templates) {
                    val bodyCos = computeCosineSimilarity(f.bodyNorm, t.bodyNorm)
                    val headCos = computeCosineSimilarity(f.headNorm, t.headNorm)
                    val score = 0.65f * bodyCos + 0.35f * headCos

                    if (t.className == "K" && score > kingSim) {
                        kingSim = score
                    }
                    if (score > bestSim) {
                        secondSim = bestSim
                        secondCls = bestCls
                        bestSim = score
                        bestCls = t.className
                    } else if (score > secondSim && t.className != bestCls) {
                        secondSim = score
                        secondCls = t.className
                    }
                }
                occupiedList.add(OccupiedCell(r, c, f, bestCls, bestSim, secondCls, kingSim))
            }
        }

        // 2. Semantisches Qualitätsgatter des Bretts (Semantic Quality Gating)
        // Telemetrie beim Abweisen: unsichere belegte Felder aufsteigend nach sim, um bei "echtes Brett fälschlich geblockt" die Einzelfeldverteilung zu prüfen (die Perspektive steht hier noch nicht fest, daher Bildschirmkoordinaten)
        val rejectedLowConf = occupiedList
            .filter { it.bestSimilarity < 0.60f }
            .sortedBy { it.bestSimilarity }
            .map { "r${it.r}c${it.c}=${it.primaryClass}(${String.format("%.2f", it.bestSimilarity)})" }

        if (occupiedList.size < 4) {
            return ClassificationResponse.Rejected(
                reason = "Zu wenige belegte Felder (${occupiedList.size} < 4)",
                medianSim = 0.0f,
                occupiedCount = occupiedList.size,
                lowConfidenceCells = rejectedLowConf,
                gateRejectedCells = gateRejected
            )
        }

        val sortedSims = occupiedList.map { it.bestSimilarity }.sorted()
        val medianSim = if (sortedSims.size % 2 == 1) {
            sortedSims[sortedSims.size / 2]
        } else {
            (sortedSims[sortedSims.size / 2 - 1] + sortedSims[sortedSims.size / 2]) / 2.0f
        }

        // Nicht-Brett-Bilder (z. B. Lernpfad, Lobby) haben eine extrem niedrige Median-Ähnlichkeit (gemessen <= 0.378), echte Bretter >= 0.673
        if (medianSim < 0.52f) {
            return ClassificationResponse.Rejected(
                reason = "Ähnlichkeit zu niedrig (MedianSim=${String.format("%.3f", medianSim)} < 0.520)",
                medianSim = medianSim,
                occupiedCount = occupiedList.size,
                lowConfidenceCells = rejectedLowConf,
                gateRejectedCells = gateRejected
            )
        }

        // 3. Adaptives 2-Means-Clustering trennt die Farben Schwarz und Weiß
        val rawBoard = Array(8) { CharArray(8) { '.' } }
        val means = FloatArray(occupiedList.size) { occupiedList[it].feat.centerMean }
        val splitThreshold = calculateTwoMeansThreshold(means)

        for (cell in occupiedList) {
            val isWhite = cell.feat.centerMean >= splitThreshold
            val sym = if (isWhite) cell.primaryClass[0].uppercaseChar() else cell.primaryClass[0].lowercaseChar()
            rawBoard[cell.r][cell.c] = sym
        }

        // 4. Regelprüfung (kein Bauer auf Reihe 1/8, kapazitätsbewusste Abwertung bei Überschreitung der Figurenobergrenzen)
        val sanitizedBoard = sanitizeBoard(rawBoard)

        // 5. Perspektive bestimmen: welche Farbe sitzt unten am eigenen Brettrand
        // Früher entschied allein die Figurenmehrheit der beiden untersten Reihen darüber.
        // Genau diese Regel kippte im Mittel- und Endspiel (eingedrungene gegnerische Figuren,
        // geräumte eigene Grundreihe, Bauernumwandlung) und ließ die App die Figuren des
        // Gegners statt der eigenen analysieren. detectPerspective gewichtet stattdessen
        // mehrere unabhängige Signale gegeneinander und meldet eine Confidence mit zurück.
        val perspectiveVerdict = detectPerspective(sanitizedBoard)
        val detectedPerspective = perspectiveVerdict.isWhitePerspective
        val effectivePerspective = overridePerspective ?: detectedPerspective

        // 5.5 Zellenweise Confidence-Telemetrie (Lektion aus bug_11: Feld b4 hatte nur Sim 0.50 bei 0.06 Abstand und passierte trotzdem das globale Median-Gatter und verunreinigte das FEN)
        val fileChars = "abcdefgh"
        val lowConfidenceCells = occupiedList
            .filter { it.bestSimilarity < 0.60f }
            .sortedBy { it.bestSimilarity }
            .map { cell ->
                val name = if (effectivePerspective) {
                    "${fileChars[cell.c]}${8 - cell.r}"
                } else {
                    "${fileChars[7 - cell.c]}${cell.r + 1}"
                }
                "$name=${cell.primaryClass}(${String.format("%.2f", cell.bestSimilarity)})"
            }

        val result = buildFenFromBoard(
            rawBoard = sanitizedBoard,
            isWhitePerspective = effectivePerspective,
            boardRect = safeRect,
            medianSim = medianSim,
            occupiedCount = occupiedList.size
        )

        return ClassificationResponse.Success(
            result = result,
            medianSim = medianSim,
            occupiedCount = occupiedList.size,
            detectedPerspective = detectedPerspective,
            perspectiveConfidence = perspectiveVerdict.confidence,
            perspectiveReason = perspectiveVerdict.reason,
            lowConfidenceCells = lowConfidenceCells,
            gateRejectedCells = gateRejected
        )
    }

    fun classifyBoard(bitmap: Bitmap, boardRect: Rect): DetectionResult? {
        return when (val resp = classifyBoardDetailed(bitmap, boardRect)) {
            is ClassificationResponse.Success -> resp.result
            is ClassificationResponse.Rejected -> null
        }
    }

    /**
     * Extrahiert die anatomischen Merkmale zweier Regionen eines 48x48-Feldes (30x30 Körper + 10x30 Kopf)
     */
    fun extractFeatureFromBitmap(cellBmp: Bitmap): CellFeature {
        val resized = if (cellBmp.width == 48 && cellBmp.height == 48) {
            cellBmp
        } else {
            Bitmap.createScaledBitmap(cellBmp, 48, 48, true)
        }
        val pixels = IntArray(48 * 48)
        resized.getPixels(pixels, 0, 48, 0, 0, 48, 48)
        if (resized !== cellBmp) resized.recycle()

        val gray = FloatArray(48 * 48)
        for (i in pixels.indices) {
            val p = pixels[i]
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            gray[i] = 0.299f * r + 0.587f * g + 0.114f * b
        }

        // 1. Statistik für das Belegungsgatter (auf Basis der 30x30-Zentrumsregion: Zeilen 9..38, Spalten 9..38)
        var sumGray = 0.0f
        var sumSqGray = 0.0f
        for (r in 9..38) {
            for (c in 9..38) {
                val v = gray[r * 48 + c]
                sumGray += v
                sumSqGray += v * v
            }
        }
        val centerMean = sumGray / 900.0f
        val centerVariance = max(0f, (sumSqGray / 900.0f) - (centerMean * centerMean))
        val centerStd = sqrt(centerVariance)

        // 2. Sobel-Gradient des gesamten Feldes
        val mag = FloatArray(48 * 48)
        var sumCenterMag = 0.0f
        for (gy in 1..46) {
            val yOffset = gy * 48
            val yPrev = (gy - 1) * 48
            val yNext = (gy + 1) * 48
            for (gx in 1..46) {
                val v00 = gray[yPrev + gx - 1]
                val v02 = gray[yPrev + gx + 1]
                val v10 = gray[yOffset + gx - 1]
                val v12 = gray[yOffset + gx + 1]
                val v20 = gray[yNext + gx - 1]
                val v22 = gray[yNext + gx + 1]
                val v01 = gray[yPrev + gx]
                val v21 = gray[yNext + gx]

                val sobelX = (v02 + 2f * v12 + v22) - (v00 + 2f * v10 + v20)
                val sobelY = (v20 + 2f * v21 + v22) - (v00 + 2f * v01 + v02)
                val m = sqrt(sobelX * sobelX + sobelY * sobelY)
                mag[yOffset + gx] = m
                if (gy in 9..38 && gx in 9..38) {
                    sumCenterMag += m
                }
            }
        }
        val gradMean = sumCenterMag / 900.0f

        // 3. Vordergrundschwerpunkt per Hintergrunddifferenz aus dem Median der 4 Ecken (3x3 je Ecke, 36 Punkte, gemeinsamer Median)
        val cornerVals = FloatArray(36)
        var cIdx = 0
        for (r in 0..2) {
            for (c in 0..2) {
                cornerVals[cIdx++] = gray[r * 48 + c]
                cornerVals[cIdx++] = gray[r * 48 + (45 + c)]
                cornerVals[cIdx++] = gray[(45 + r) * 48 + c]
                cornerVals[cIdx++] = gray[(45 + r) * 48 + (45 + c)]
            }
        }
        cornerVals.sort()
        val bgVal = cornerVals[18]

        // Suche auf den Innenbereich [2..45] begrenzen, damit die 2px-Randlinien bzw. Kanten der Nachbarfelder den Schwerpunkt nicht verziehen
        var sumFgY = 0.0
        var sumFgX = 0.0
        var fgCount = 0
        for (y in 2..45) {
            val yOff = y * 48
            for (x in 2..45) {
                if (abs(gray[yOff + x] - bgVal) > 15.0f) {
                    sumFgY += y
                    sumFgX += x
                    fgCount++
                }
            }
        }

        val cy = if (fgCount > 0) (sumFgY / fgCount).toFloat() else 24.0f
        val cx = if (fgCount > 0) (sumFgX / fgCount).toFloat() else 24.0f

        // 4. Schiebefenster-Klemmung des Ursprungs (garantiert eine ROI von exakt 36x36, kein Dimensionskollaps)
        val x0 = max(0, min(12, (cx - 18f).roundToInt()))
        val y0 = max(0, min(12, (cy - 18f).roundToInt()))

        // 5. Körpermerkmal: 30x30 (zentriert aus dem 36x36-Fenster geschnitten, 900 Dimensionen)
        val bodyMag = FloatArray(900)
        var bodySumSq = 0.0f
        for (r in 0..29) {
            val srcY = y0 + 3 + r
            for (c in 0..29) {
                val srcX = x0 + 3 + c
                val v = mag[srcY * 48 + srcX]
                bodyMag[r * 30 + c] = v
                bodySumSq += v * v
            }
        }
        val bodyNormVal = sqrt(bodySumSq) + 1e-5f
        val bodyNorm = FloatArray(900) { bodyMag[it] / bodyNormVal }

        // 6. Anatomisches Kopfmerkmal: 10x30 (die obersten 10 Zeilen des 30x30-Körpers, 300 Dimensionen)
        val headMag = FloatArray(300)
        var headSumSq = 0.0f
        for (r in 0..9) {
            for (c in 0..29) {
                val v = bodyMag[r * 30 + c]
                headMag[r * 30 + c] = v
                headSumSq += v * v
            }
        }
        val headNormVal = sqrt(headSumSq) + 1e-5f
        val headNorm = FloatArray(300) { headMag[it] / headNormVal }

        return CellFeature(bodyNorm, headNorm, centerStd, centerMean, gradMean)
    }

    private fun computeCosineSimilarity(a: FloatArray, b: FloatArray): Float {
        var dot = 0.0f
        val len = min(a.size, b.size)
        for (i in 0 until len) {
            dot += a[i] * b[i]
        }
        return dot
    }

    companion object {
        fun calculateTwoMeansThreshold(means: FloatArray): Float {
            if (means.isEmpty()) return 128.0f
            if (means.size == 1) return if (means[0] >= 120f) means[0] - 1f else means[0] + 1f

            val minVal = means.minOrNull() ?: 0.0f
            val maxVal = means.maxOrNull() ?: 255.0f

            if (maxVal - minVal < 35.0f) {
                val avg = means.average().toFloat()
                return if (avg >= 120.0f) minVal - 1.0f else maxVal + 1.0f
            }

            var c1 = minVal
            var c2 = maxVal

            for (iter in 0 until 10) {
                var sum1 = 0.0f
                var cnt1 = 0
                var sum2 = 0.0f
                var cnt2 = 0

                for (m in means) {
                    if (abs(m - c1) <= abs(m - c2)) {
                        sum1 += m
                        cnt1++
                    } else {
                        sum2 += m
                        cnt2++
                    }
                }
                if (cnt1 > 0) c1 = sum1 / cnt1
                if (cnt2 > 0) c2 = sum2 / cnt2
            }
            return (c1 + c2) / 2.0f
        }

        fun sanitizeBoard(board: Array<CharArray>): Array<CharArray> {
            val result = Array(8) { r -> CharArray(8) { c -> board[r][c] } }

            // 1. Auf Reihe 1 (row 7) und Reihe 8 (row 0) darf niemals ein Bauer stehen
            for (c in 0..7) {
                if (result[0][c] == 'P' || result[0][c] == 'p') {
                    result[0][c] = '.'
                }
                if (result[7][c] == 'P' || result[7][c] == 'p') {
                    result[7][c] = '.'
                }
            }

            // 2. Figurenanzahl zählen und Obergrenzen durchsetzen (kapazitätsbewusste Abwertung, damit die Ersatzfigur nicht selbst überläuft)
            val pieceCounts = mutableMapOf<Char, Int>()
            val maxLimits = mapOf(
                'K' to 1, 'k' to 1,
                'Q' to 1, 'q' to 1,
                'R' to 2, 'r' to 2,
                'B' to 2, 'b' to 2,
                'N' to 2, 'n' to 2,
                'P' to 8, 'p' to 8
            )

            for (r in 0..7) {
                for (c in 0..7) {
                    val p = result[r][c]
                    if (p == '.') continue
                    val count = pieceCounts.getOrDefault(p, 0) + 1
                    pieceCounts[p] = count
                    val maxAllowed = maxLimits[p] ?: 8
                    if (count > maxAllowed) {
                        val isWhite = p.isUpperCase()
                        val candidates = if (isWhite) charArrayOf('R', 'B', 'N') else charArrayOf('r', 'b', 'n')
                        var fallbackPiece = '.'
                        for (cand in candidates) {
                            val candCount = pieceCounts.getOrDefault(cand, 0)
                            if (candCount < (maxLimits[cand] ?: 2)) {
                                fallbackPiece = cand
                                pieceCounts[cand] = candCount + 1
                                break
                            }
                        }
                        result[r][c] = fallbackPiece
                    }
                }
            }

            // 3. Eindeutigkeit beider Könige (das alte Nachfüllen wurde abgeschafft, die strenge Prüfung erfolgt stromabwärts)
            var whiteKingCount = 0
            var blackKingCount = 0
            for (r in 0..7) {
                for (c in 0..7) {
                    if (result[r][c] == 'K') whiteKingCount++
                    if (result[r][c] == 'k') blackKingCount++
                }
            }

            // 4. Abschließende Zweitprüfung
            val finalCounts = mutableMapOf<Char, Int>()
            for (r in 0..7) {
                for (c in 0..7) {
                    val p = result[r][c]
                    if (p == '.') continue
                    val cnt = finalCounts.getOrDefault(p, 0) + 1
                    finalCounts[p] = cnt
                    val limit = maxLimits[p] ?: 8
                    if (cnt > limit) {
                        result[r][c] = '.'
                    }
                }
            }

            return result
        }

        fun compressRow(row: CharArray): String {
            val sb = StringBuilder()
            var empty = 0
            for (sym in row) {
                if (sym == '.') {
                    empty++
                } else {
                    if (empty > 0) {
                        sb.append(empty)
                        empty = 0
                    }
                    sb.append(sym)
                }
            }
            if (empty > 0) sb.append(empty)
            return sb.toString()
        }

        /**
         * Reine Funktion: berechnet aus dem Standardbrett (row 0 = Reihe 8, row 7 = Reihe 1) die gültigen Rochaderechte
         * Regeln:
         * - Weiße kurze Rochade (K): weißer König auf e1 (r=7, c=4) und weißer Turm auf h1 (r=7, c=7)
         * - Weiße lange Rochade (Q): weißer König auf e1 (r=7, c=4) und weißer Turm auf a1 (r=7, c=0)
         * - Schwarze kurze Rochade (k): schwarzer König auf e8 (r=0, c=4) und schwarzer Turm auf h8 (r=0, c=7)
         * - Schwarze lange Rochade (q): schwarzer König auf e8 (r=0, c=4) und schwarzer Turm auf a8 (r=0, c=0)
         * - Nicht erfüllte Bedingungen streichen den jeweiligen Buchstaben, fällt alles weg, wird "-" ausgegeben (sonst lehnt Stockfish das FEN mit BAD_CASTLING_RIGHTS ab)
         */
        fun computeCastlingRights(board: Array<CharArray>): String {
            val sb = StringBuilder()
            if (board.size >= 8 && board[7].size >= 8 && board[7][4] == 'K') {
                if (board[7][7] == 'R') sb.append('K')
                if (board[7][0] == 'R') sb.append('Q')
            }
            if (board.size >= 8 && board[0].size >= 8 && board[0][4] == 'k') {
                if (board[0][7] == 'r') sb.append('k')
                if (board[0][0] == 'r') sb.append('q')
            }
            return if (sb.isEmpty()) "-" else sb.toString()
        }

        fun buildFenFromBoard(
            rawBoard: Array<CharArray>,
            isWhitePerspective: Boolean,
            boardRect: Rect = Rect(),
            medianSim: Float = 1.0f,
            occupiedCount: Int = 0
        ): DetectionResult {
            val standardBoard = if (isWhitePerspective) {
                rawBoard
            } else {
                Array(8) { r -> CharArray(8) { c -> rawBoard[7 - r][7 - c] } }
            }

            val activeColor = if (isWhitePerspective) "w" else "b"
            val fenRows = standardBoard.map { compressRow(it) }
            val boardFen = fenRows.joinToString("/")
            val castling = computeCastlingRights(standardBoard)
            val fullFen = "$boardFen $activeColor $castling - 0 1"

            return DetectionResult(
                boardFen = boardFen,
                fullFen = fullFen,
                activeColor = activeColor,
                isWhitePerspective = isWhitePerspective,
                rawBoard = rawBoard,
                standardBoard = standardBoard,
                boardRect = boardRect,
                medianSim = medianSim,
                occupiedCount = occupiedCount
            )
        }

        /**
         * Reine Funktion: bestimmt aus dem Bildschirmbrett (row 0 = oben, row 7 = unten),
         * welche Farbe unten sitzt, also welche Figuren die eigenen sind.
         *
         * Fehlerbild vor dieser Funktion: gezählt wurde nur die Farbmehrheit der beiden
         * untersten Reihen. Sobald der Gegner dort eindringt oder die eigene Grundreihe leer
         * wird, kippte das Ergebnis, das FEN wurde gespiegelt und die App schlug Züge für
         * die Figuren des Gegners vor.
         *
         * Stattdessen werden vier unabhängige Signale gewichtet addiert (positiv = Weiß unten):
         * 1. Bauernrichtung (Gewicht bis 4.0): Bauern können nicht zurück, ihre mittlere
         *    Bildschirmzeile ist deshalb über die ganze Partie hinweg das stabilste Signal.
         *    Das Gewicht sinkt mit der Anzahl der noch vorhandenen Bauern.
         * 2. Königsstand (Gewicht 2.0): der eigene König steht in aller Regel unterhalb des gegnerischen.
         * 3. Materialschwerpunkt (Gewicht bis 2.0): alle Figuren nach Abstand zur Brettmitte gewichtet.
         * 4. Grundreihen (Gewicht 1.0): die alte Heuristik, jetzt nur noch eine Stimme von vieren.
         *
         * @return Perspektive, Confidence in [0..1] und das ausschlaggebende Signal
         */
        fun detectPerspective(board: Array<CharArray>): PerspectiveVerdict {
            var whitePawnRowSum = 0.0f
            var whitePawns = 0
            var blackPawnRowSum = 0.0f
            var blackPawns = 0
            var whiteKingRow = -1
            var blackKingRow = -1
            var massSum = 0.0f
            var pieceCount = 0
            var topWhite = 0
            var topBlack = 0
            var botWhite = 0
            var botBlack = 0

            for (r in 0..7) {
                for (c in 0..7) {
                    val sym = board[r][c]
                    if (sym == '.') continue
                    val isWhite = sym.isUpperCase()
                    pieceCount++
                    // Abstand zur Brettmitte, normiert auf [-1..1]: unten positiv, oben negativ
                    massSum += (if (isWhite) 1.0f else -1.0f) * ((r - 3.5f) / 3.5f)
                    when (sym) {
                        'P' -> { whitePawnRowSum += r; whitePawns++ }
                        'p' -> { blackPawnRowSum += r; blackPawns++ }
                        'K' -> whiteKingRow = r
                        'k' -> blackKingRow = r
                    }
                    if (r <= 1) { if (isWhite) topWhite++ else topBlack++ }
                    if (r >= 6) { if (isWhite) botWhite++ else botBlack++ }
                }
            }

            var weightedSum = 0.0f
            var weightTotal = 0.0f
            var strongestSignal = ""
            var strongestContribution = 0.0f

            fun addSignal(name: String, rawValue: Float, weight: Float) {
                if (weight <= 0.0f) return
                val contribution = rawValue.coerceIn(-1.0f, 1.0f) * weight
                weightedSum += contribution
                weightTotal += weight
                if (abs(contribution) > abs(strongestContribution)) {
                    strongestContribution = contribution
                    strongestSignal = name
                }
            }

            // 1. Bauernrichtung: Differenz der mittleren Bildschirmzeile beider Bauernketten
            // (Grundstellung: Weiß 6, Schwarz 1 -> Differenz 5 -> voll ausgeschlagenes Signal)
            if (whitePawns > 0 && blackPawns > 0) {
                val pawnRowDiff = (whitePawnRowSum / whitePawns) - (blackPawnRowSum / blackPawns)
                val pawnWeight = 4.0f * min(1.0f, (whitePawns + blackPawns) / 8.0f)
                addSignal("Bauernrichtung", pawnRowDiff / 5.0f, pawnWeight)
            }

            // 2. Königsstand: nur verwertbar, wenn beide Könige erkannt wurden
            if (whiteKingRow >= 0 && blackKingRow >= 0) {
                addSignal("Königsstand", (whiteKingRow - blackKingRow) / 5.0f, 2.0f)
            }

            // 3. Materialschwerpunkt über alle Figuren, Gewicht sinkt mit abnehmendem Material
            if (pieceCount > 0) {
                addSignal("Materialschwerpunkt", massSum / pieceCount, 2.0f * min(1.0f, pieceCount / 16.0f))
            }

            // 4. Grundreihen: die alte Heuristik als schwächste Stimme
            addSignal("Grundreihen", ((botWhite - botBlack) + (topBlack - topWhite)) / 16.0f, 1.0f)

            val isWhite = when {
                weightedSum > 0.0f -> true
                weightedSum < 0.0f -> false
                // Patt aller Signale (z. B. völlig symmetrisches Brett): alte Heuristik entscheidet
                else -> botWhite >= botBlack
            }
            val confidence = if (weightTotal > 0.0f) {
                (abs(weightedSum) / weightTotal).coerceIn(0.0f, 1.0f)
            } else {
                0.0f
            }
            val reason = if (strongestSignal.isEmpty()) "Grundreihen (Rückfall)" else strongestSignal
            return PerspectiveVerdict(isWhite, confidence, reason)
        }

        /**
         * Reine Funktion: alle Felder, auf denen Figuren einer Farbe stehen (z. B. "e7").
         *
         * @param standardBoard Brett in Standardausrichtung (row 0 = Reihe 8, row 7 = Reihe 1)
         * @param whitePieces true = die weißen Figuren (Großbuchstaben), false = die schwarzen
         */
        fun sideSquares(standardBoard: Array<CharArray>, whitePieces: Boolean): Set<String> {
            val fileChars = "abcdefgh"
            val squares = mutableSetOf<String>()
            for (r in standardBoard.indices) {
                val row = standardBoard[r]
                for (c in row.indices) {
                    val sym = row[c]
                    if (sym == '.') continue
                    if (sym.isUpperCase() != whitePieces) continue
                    val file = if (c in fileChars.indices) fileChars[c] else '?'
                    squares.add("$file${8 - r}")
                }
            }
            return squares
        }

        /** Reine Funktion: die Felder der gegnerischen Figuren */
        fun opponentSquares(standardBoard: Array<CharArray>, isWhitePerspective: Boolean): Set<String> =
            sideSquares(standardBoard, whitePieces = !isWhitePerspective)

        /**
         * Reine Funktion: eigene Farbe aus der Ausgangsstellung bestimmen.
         *
         * Regel: Was zu Beginn auf den beiden untersten Bildschirmreihen steht, sind die eigenen
         * Figuren; was oben steht, gehört dem Gegner. Ob die eigenen Figuren hell oder dunkel sind,
         * hat die Helligkeitsclusterung bereits entschieden - Großbuchstaben stehen für die hellen
         * (weißen), Kleinbuchstaben für die dunklen (schwarzen) Figuren.
         *
         * @param screenBoard Brett so, wie es auf dem Bildschirm steht (row 0 = oben, row 7 = unten)
         * @return true = eigene Farbe ist Weiß, false = Schwarz, null = die Reihen sind nicht eindeutig
         */
        fun sideFromStartingRows(screenBoard: Array<CharArray>): Boolean? {
            var bottomWhite = 0
            var bottomBlack = 0
            var topWhite = 0
            var topBlack = 0
            for (r in screenBoard.indices) {
                val row = screenBoard[r]
                for (c in row.indices) {
                    val sym = row[c]
                    if (sym == '.') continue
                    val isWhitePiece = sym.isUpperCase()
                    if (r >= 6) {
                        if (isWhitePiece) bottomWhite++ else bottomBlack++
                    } else if (r <= 1) {
                        if (isWhitePiece) topWhite++ else topBlack++
                    }
                }
            }

            // Unten muss eine Farbe klar überwiegen, sonst steht die Partie nicht am Anfang
            if (bottomWhite == bottomBlack) return null
            val mineAreWhite = bottomWhite > bottomBlack

            // Gegenprobe: oben sollte die andere Farbe stehen. Fällt sie aus, gilt trotzdem die Mehrheit unten.
            if (topWhite > 0 || topBlack > 0) {
                val opponentIsWhite = topWhite > topBlack
                if (opponentIsWhite == mineAreWhite) return null
            }
            return mineAreWhite
        }

        /**
         * Reine Funktion: Bildschirmfeld (0..63, oben links = 0) zu einem Feldnamen wie "e2".
         *
         * Der Pfeil wird in Brettkoordinaten berechnet, abgetastet wird aber in Bildschirmfeldern.
         * Spielt man Schwarz, steht das Brett gedreht - dann kehren sich Reihe und Linie um.
         *
         * @param square           Feldname aus dem UCI-Zug, etwa "e2"
         * @param isWhitePerspective true, wenn die eigenen (weißen) Figuren unten stehen
         * @return Feldnummer 0..63 oder null, wenn der Feldname unbrauchbar ist
         */
        fun screenCellForSquare(square: String, isWhitePerspective: Boolean): Int? {
            if (square.length < 2) return null
            val file = square[0] - 'a'
            val rank = square[1] - '0'
            if (file !in 0..7 || rank !in 1..8) return null
            val row = if (isWhitePerspective) 8 - rank else rank - 1
            val col = if (isWhitePerspective) file else 7 - file
            return row * 8 + col
        }

        /**
         * Reine Funktion: Umkehrung von [screenCellForSquare] - Bildschirmfeld zu Feldname.
         *
         * @param cell 0..63, oben links = 0
         */
        fun squareForScreenCell(cell: Int, isWhitePerspective: Boolean): String? {
            if (cell !in 0..63) return null
            val row = cell / 8
            val col = cell % 8
            val rank = if (isWhitePerspective) 8 - row else row + 1
            val file = if (isWhitePerspective) col else 7 - col
            return "${'a' + file}${rank}"
        }

        /**
         * Reine Funktion: macht aus zwei Bildschirmfeldern einen UCI-Zug.
         *
         * Erreicht ein Bauer die letzte Reihe, wird die Umwandlung in eine Dame angehängt - das
         * ist praktisch immer die Wahl, und die Oberfläche wandelt ohnehin selbst um.
         *
         * @param standardBoard Brett in Standardausrichtung, um die ziehende Figur nachzusehen
         */
        fun uciFromScreenCells(
            fromCell: Int,
            toCell: Int,
            isWhitePerspective: Boolean,
            standardBoard: Array<CharArray>
        ): String? {
            val fromSquare = squareForScreenCell(fromCell, isWhitePerspective) ?: return null
            val toSquare = squareForScreenCell(toCell, isWhitePerspective) ?: return null
            val fromRow = 8 - (fromSquare[1] - '0')
            val fromCol = fromSquare[0] - 'a'
            val piece = standardBoard.getOrNull(fromRow)?.getOrNull(fromCol) ?: return null
            if (piece == '.') return null

            val toRank = toSquare[1] - '0'
            val promotes = (piece == 'P' && toRank == 8) || (piece == 'p' && toRank == 1)
            return fromSquare + toSquare + if (promotes) "q" else ""
        }

        /**
         * Reine Funktion: schreibt die Rochaderechte um einen gespielten Zug fort.
         *
         * Aus der reinen Figurenstellung lassen sich die Rechte nicht ablesen: Ein König, der nach
         * f1 und wieder zurück nach e1 gegangen ist, steht wieder zu Hause, darf aber nie wieder
         * rochieren. [computeCastlingRights] muss dort raten und gäbe das Recht fälschlich zurück -
         * die Engine schlüge dann eine Rochade vor, die das Spiel ablehnt.
         *
         * Sobald die Stellung fortgeschrieben wird, ist die Zugfolge bekannt und die Rechte lassen
         * sich mitführen: Ein Königszug nimmt beide Rechte der Seite, ein Turmzug das seiner Ecke,
         * und ein Schlag auf einer Turmecke nimmt das Recht der geschlagenen Seite.
         *
         * @param current Bisherige Rechte in FEN-Schreibweise, etwa "KQkq" oder "-"
         * @param uci     Gespielter Zug
         * @param movingPiece Figur, die gezogen hat
         */
        fun updateCastlingRights(current: String, uci: String, movingPiece: Char): String {
            if (uci.length < 4) return current
            val rights = current.filter { it != '-' }.toSet().toMutableSet()
            if (rights.isEmpty()) return "-"

            val from = uci.substring(0, 2)
            val to = uci.substring(2, 4)

            when (movingPiece) {
                'K' -> { rights.remove('K'); rights.remove('Q') }
                'k' -> { rights.remove('k'); rights.remove('q') }
                'R' -> when (from) {
                    "h1" -> rights.remove('K')
                    "a1" -> rights.remove('Q')
                }
                'r' -> when (from) {
                    "h8" -> rights.remove('k')
                    "a8" -> rights.remove('q')
                }
            }

            // Ein Schlag auf einer Turmecke nimmt der anderen Seite ihr Recht - dort steht danach
            // kein Turm mehr, gleich ob er geschlagen wurde oder schon weg war.
            when (to) {
                "h1" -> rights.remove('K')
                "a1" -> rights.remove('Q')
                "h8" -> rights.remove('k')
                "a8" -> rights.remove('q')
            }

            if (rights.isEmpty()) return "-"
            // FEN schreibt die Rechte in fester Reihenfolge
            return "KQkq".filter { it in rights }
        }

        /**
         * Reine Funktion: baut das FEN unmittelbar aus einem Brett in Standardausrichtung.
         *
         * Gebraucht für die fortgeschriebene Stellung: dort steht das Brett bereits richtig herum,
         * und wer am Zug ist, ergibt sich aus dem Spielverlauf statt aus der Blickrichtung.
         */
        fun buildFenFromStandardBoard(
            standardBoard: Array<CharArray>,
            activeIsWhite: Boolean,
            boardRect: Rect = Rect(),
            // Mitgeführte Rechte; ohne Angabe werden sie aus der Stellung geraten
            castlingRights: String? = null
        ): DetectionResult {
            val activeColor = if (activeIsWhite) "w" else "b"
            val boardFen = standardBoard.joinToString("/") { compressRow(it) }
            // Geratene Rechte nie großzügiger als die mitgeführten: was einmal verspielt ist,
            // bleibt verspielt.
            val castling = castlingRights?.let { tracked ->
                val fromBoard = computeCastlingRights(standardBoard)
                val allowed = tracked.filter { it != '-' }.toSet()
                val combined = fromBoard.filter { it in allowed }
                if (combined.isEmpty()) "-" else combined
            } ?: computeCastlingRights(standardBoard)
            val occupied = standardBoard.sumOf { row -> row.count { it != '.' } }

            // rawBoard ist die Bildschirmansicht: bei schwarzer Sicht steht das Brett gedreht
            val rawBoard = if (activeIsWhite) {
                standardBoard
            } else {
                Array(8) { r -> CharArray(8) { c -> standardBoard[7 - r][7 - c] } }
            }

            return DetectionResult(
                boardFen = boardFen,
                fullFen = "$boardFen $activeColor $castling - 0 1",
                activeColor = activeColor,
                isWhitePerspective = activeIsWhite,
                rawBoard = rawBoard,
                standardBoard = standardBoard,
                boardRect = boardRect,
                medianSim = 1.0f,
                occupiedCount = occupied
            )
        }

        /**
         * Reine Funktion: findet die Auswahl der Umwandlungsfigur auf dem Bildschirm.
         *
         * Erreicht ein Bauer die letzte Reihe, blendet Duolingo eine Tafel mit vier Figuren ein -
         * Dame, Turm, Läufer, Springer, in dieser Reihenfolge nebeneinander. Ohne eine Berührung
         * darauf bleibt der Zug unvollendet und die Partie steht.
         *
         * Wo die Tafel erscheint, lässt sich nicht zuverlässig ausrechnen: Sie hängt am
         * Umwandlungsfeld, wird aber an den Bildschirmrand gerückt, wenn sie sonst hinausragen
         * würde. Deshalb wird sie gesucht statt geraten - an ihrem unverwechselbaren Muster:
         * vier helle Symbole nebeneinander, jedes rund ein halbes Feld breit, im Abstand von je
         * einem Feld. Nichts auf einem Schachbrett sieht sonst so aus.
         *
         * Wird nichts gefunden, kommt bewusst null zurück und es wird nicht getippt. Blind auf eine
         * vermutete Stelle zu tippen wäre schlimmer als ein unvollendeter Zug: Es könnte einen
         * ganz anderen Zug auslösen.
         *
         * Bewusst mit blanken Zahlen statt einem Rect: In den Unit-Tests von Android sind die
         * Methoden der Android-Klassen nicht belegt und liefern schlicht 0. Eine reine Funktion,
         * die davon abhängt, ist auf dem Rechner nicht prüfbar - und genau das ist hier einmal
         * durchgerutscht.
         *
         * @param luminance liefert die Helligkeit (0..255) eines Bildpunkts
         * @param boardTop  obere Kante des Bretts in Bildpunkten
         * @param squareSize Kantenlänge eines Feldes in Bildpunkten
         * @param promoCell Bildschirmfeld, auf dem der Bauer angekommen ist
         * @return Bildpunkt der Dame, oder null
         */
        fun findPromotionChoice(
            luminance: (Int, Int) -> Float,
            screenWidth: Int,
            screenHeight: Int,
            boardTop: Int,
            squareSize: Float,
            promoCell: Int,
            brightThreshold: Float = 150f
        ): Pair<Int, Int>? {
            if (promoCell !in 0..63) return null
            val square = squareSize
            if (square < 16f) return null

            val row = promoCell / 8
            val centreY = boardTop + (row + 0.5f) * square
            // Die Tafel liegt zum Brett hin, also nach unten, wenn oben umgewandelt wird
            val direction = if (row <= 3) 1 else -1

            val half = (0.25f * square).toInt().coerceAtLeast(4)
            val start = (centreY + direction * 1.2f * square).toInt()
            val end = (centreY + direction * 3.5f * square).toInt()
            val step = 6 * direction

            val treffer = mutableListOf<Pair<Int, Int>>()
            var probe = start
            while (if (direction > 0) probe < end else probe > end) {
                val queenX = scanChoiceRow(
                    luminance, screenWidth, screenHeight, probe, half, square, brightThreshold
                )
                if (queenX != null) treffer.add(queenX to probe)
                probe += step
            }
            if (treffer.isEmpty()) return null

            // Die mittlere der passenden Höhen trifft das Symbol am sichersten - die erste läge
            // an seinem oberen Rand.
            return treffer[treffer.size / 2]
        }

        /**
         * Sucht in einem waagerechten Streifen vier helle Symbole im Abstand von je einem Feld.
         * @return x-Mitte des linken Symbols (die Dame), oder null
         */
        private fun scanChoiceRow(
            luminance: (Int, Int) -> Float,
            screenWidth: Int,
            screenHeight: Int,
            probeY: Int,
            half: Int,
            square: Float,
            brightThreshold: Float
        ): Int? {
            val counts = IntArray(screenWidth)
            var y = (probeY - half).coerceAtLeast(0)
            val yEnd = (probeY + half).coerceAtMost(screenHeight)
            while (y < yEnd) {
                for (x in 0 until screenWidth) {
                    if (luminance(x, y) > brightThreshold) counts[x]++
                }
                y += 3
            }

            // Spalten mit mehreren hellen Zeilen zu Blöcken zusammenfassen. Die Mindestzahl hält
            // einzelne helle Punkte heraus, die sonst zwei Symbole zu einem verbinden.
            val blocks = mutableListOf<IntRange>()
            var blockStart = -1
            var lastOn = -1
            for (x in 0 until screenWidth) {
                if (counts[x] >= 3) {
                    if (blockStart < 0) blockStart = x
                    lastOn = x
                } else if (blockStart >= 0 && x - lastOn > 8) {
                    blocks.add(blockStart..lastOn)
                    blockStart = -1
                }
            }
            if (blockStart >= 0) blocks.add(blockStart..lastOn)

            // Nur Blöcke in der Größe eines Figurensymbols
            val symbols = blocks.filter {
                val width = it.last - it.first
                width >= 0.3f * square && width <= 0.95f * square
            }
            if (symbols.size < 4) return null

            val centres = symbols.map { (it.first + it.last) / 2 }
            // Vier Symbole im Abstand von je rund einem Feld
            for (i in 0..centres.size - 4) {
                val vier = centres.subList(i, i + 4)
                val abstaende = (0..2).map { vier[it + 1] - vier[it] }
                if (abstaende.all { it >= 0.7f * square && it <= 1.3f * square }) {
                    return vier[0]
                }
            }
            return null
        }

        /**
         * Reine Funktion: welche Bildschirmfelder muss der Auto-Zug antippen?
         *
         * Ein gewöhnlicher Zug braucht zwei Berührungen: Figur wählen, Ziel wählen. Eine
         * Umwandlung braucht eine dritte, denn die Oberfläche fragt danach, welche Figur es werden
         * soll. Diese Auswahl erscheint in aller Regel auf dem Umwandlungsfeld selbst, mit der
         * Dame obenauf - so machen es die verbreiteten Schachoberflächen. Der dritte Tipp geht
         * deshalb noch einmal auf dasselbe Feld.
         *
         * @param uci Zug in UCI-Schreibweise, bei einer Umwandlung mit fünftem Zeichen
         * @return Feldnummern in der Reihenfolge, in der getippt wird, oder null bei unbrauchbarem Zug
         */
        fun tapCellsForMove(uci: String, isWhitePerspective: Boolean): List<Int>? {
            if (uci.length < 4) return null
            val from = screenCellForSquare(uci.substring(0, 2), isWhitePerspective) ?: return null
            val to = screenCellForSquare(uci.substring(2, 4), isWhitePerspective) ?: return null
            // Auch bei einer Umwandlung nur Start und Ziel: Welche Figur es wird, entscheidet
            // danach eine Berührung auf der eingeblendeten Tafel, und die wird gesucht statt
            // geraten (siehe findPromotionChoice).
            return listOf(from, to)
            return if (uci.length >= 5) listOf(from, to, to) else listOf(from, to)
        }

        /**
         * Reine Funktion: wurde der empfohlene Zug auf dem Brett ausgeführt?
         *
         * Das ist die Abbruchbedingung für den Pfeil und arbeitet bewusst nur auf den beiden
         * betroffenen Feldern - so wie es ein Mensch prüfen würde: steht die Figur jetzt dort,
         * wo der Pfeil hinzeigt, und ist ihr Startfeld leer?
         *
         * Der Umweg über eine vollständige Neuerkennung entfällt damit; die war zu träge und
         * zu fehleranfällig, um den Pfeil im richtigen Moment wegzunehmen.
         *
         * @param fromCell Bildschirmfeld, von dem gezogen werden soll
         * @param toCell   Bildschirmfeld, auf das gezogen werden soll
         * @param occupiedLimit Ab dieser Streuung gilt ein Feld als besetzt
         */
        fun moveWasPlayed(
            referenceMeans: FloatArray,
            referenceStds: FloatArray,
            currentMeans: FloatArray,
            currentStds: FloatArray,
            fromCell: Int,
            toCell: Int,
            occupiedLimit: Float = 12f,
            meanTolerance: Float = 14f,
            stdTolerance: Float = 10f
        ): Boolean {
            if (fromCell !in 0..63 || toCell !in 0..63) return false
            if (referenceMeans.size < 64 || currentMeans.size < 64) return false
            if (referenceStds.size < 64 || currentStds.size < 64) return false

            // Startfeld: war besetzt, ist jetzt leer
            val fromWasOccupied = referenceStds[fromCell] >= occupiedLimit
            val fromIsEmpty = currentStds[fromCell] < occupiedLimit
            if (!fromWasOccupied || !fromIsEmpty) return false

            // Zielfeld: dort steht jetzt eine Figur, und sein Aussehen hat sich geändert.
            // Beides zusammen schließt aus, dass eine reine Hervorhebung des Bretts genügt.
            val toIsOccupied = currentStds[toCell] >= occupiedLimit
            val toChanged = abs(currentMeans[toCell] - referenceMeans[toCell]) > meanTolerance ||
                abs(currentStds[toCell] - referenceStds[toCell]) > stdTolerance
            return toIsOccupied && toChanged
        }

        /**
         * Reine Funktion: spielt einen UCI-Zug auf einem Brett in Standardausrichtung nach.
         *
         * Gebraucht, wenn der eigene Zug über die beiden Pfeilfelder erkannt wurde: Dann ist zwar
         * klar, dass gezogen wurde, aber das gemerkte Brett stünde noch auf der Stellung davor.
         * Der nächste Vergleich würde dann den eigenen und den gegnerischen Zug zusammen sehen und
         * niemanden mehr eindeutig benennen können.
         *
         * Rochade und Umwandlung werden mitgeführt; en passant nicht, das fällt beim nächsten
         * Vergleich als gewöhnliche Veränderung auf.
         *
         * @return neues Brett oder null, wenn der Zug unbrauchbar ist
         */
        fun applyUciMove(board: Array<CharArray>, uci: String): Array<CharArray>? {
            if (uci.length < 4) return null
            val fromFile = uci[0] - 'a'
            val fromRank = uci[1] - '0'
            val toFile = uci[2] - 'a'
            val toRank = uci[3] - '0'
            if (fromFile !in 0..7 || toFile !in 0..7 || fromRank !in 1..8 || toRank !in 1..8) return null
            if (board.size < 8) return null

            val fromRow = 8 - fromRank
            val toRow = 8 - toRank
            val next = Array(board.size) { r -> board[r].copyOf() }
            val piece = next[fromRow][fromFile]
            if (piece == '.') return null

            next[fromRow][fromFile] = '.'
            // Umwandlung: das fünfte Zeichen nennt die neue Figur, die Farbe bleibt
            val promoted = if (uci.length >= 5) {
                if (piece.isUpperCase()) uci[4].uppercaseChar() else uci[4].lowercaseChar()
            } else {
                piece
            }
            next[toRow][toFile] = promoted

            // En passant: ein Bauer zieht schräg auf ein leeres Feld. Geschlagen wird dann der
            // Bauer neben dem Startfeld, nicht der auf dem Zielfeld. Ohne diesen Zweig bliebe ein
            // Geisterbauer stehen und die fortgeschriebene Stellung wäre unbrauchbar.
            if ((piece == 'P' || piece == 'p') && fromFile != toFile && board[toRow][toFile] == '.') {
                next[fromRow][toFile] = '.'
            }

            // Rochade: der König geht zwei Linien weit, der Turm springt mit
            if ((piece == 'K' || piece == 'k') && fromRow == toRow && kotlin.math.abs(toFile - fromFile) == 2) {
                if (toFile > fromFile) {
                    // kurze Rochade: Turm von h auf f
                    next[toRow][5] = next[toRow][7]
                    next[toRow][7] = '.'
                } else {
                    // lange Rochade: Turm von a auf d
                    next[toRow][3] = next[toRow][0]
                    next[toRow][0] = '.'
                }
            }
            return next
        }

        /**
         * Ergebnis des Brettvergleichs zweier aufeinanderfolgender Erkennungen.
         *
         * @param changedSquares Anzahl der Felder, deren Inhalt sich geändert hat
         * @param moverIsWhite   true = Weiß hat gezogen, false = Schwarz, null = nicht eindeutig
         */
        data class BoardDiff(val changedSquares: Int, val moverIsWhite: Boolean?)

        /**
         * Reine Funktion: vergleicht zwei erkannte Bretter und sagt, wer gezogen hat.
         *
         * Das ist der Kern der Zugerkennung und ersetzt die frühere Zählweise über Feldmengen.
         * Der Unterschied ist entscheidend: gezählt wird nicht mehr, wie viele Felder einer Farbe
         * neu belegt sind, sondern es wird die Figur benannt, die auf einem Feld neu aufgetaucht
         * ist. Deren Farbe ist die Farbe des Ziehenden - das gilt für einen stillen Zug ebenso wie
         * für einen Schlagzug, bei dem eine Figur der Gegenfarbe verschwindet.
         *
         * Bewusst zurückhaltend: Alles, was nicht zu einem einzelnen Zug passt (kein oder mehr als
         * vier veränderte Felder, widersprüchliche Farben unter den neuen Figuren), liefert
         * `moverIsWhite = null`. Der Dienst wartet dann auf eine saubere Aufnahme, statt auf
         * gut Glück zu rechnen. Vier Felder deckt die Rochade ab.
         *
         * @param previous Brett der letzten angenommenen Erkennung (Standardausrichtung)
         * @param current  Brett der aktuellen Erkennung (Standardausrichtung)
         */
        fun diffBoards(previous: Array<CharArray>, current: Array<CharArray>): BoardDiff {
            var changed = 0
            var appearedWhite = 0
            var appearedBlack = 0
            var vacatedWhite = 0
            var vacatedBlack = 0

            for (r in 0 until 8) {
                for (c in 0 until 8) {
                    val before = colourClass(previous.getOrNull(r)?.getOrNull(c) ?: '.')
                    val after = colourClass(current.getOrNull(r)?.getOrNull(c) ?: '.')
                    if (before == after) continue
                    changed++

                    // Neu belegt oder von der Gegenfarbe übernommen: das ist ein Zielfeld.
                    // Die Farbe der dort stehenden Figur ist die Farbe des Ziehenden.
                    when (after) {
                        'W' -> appearedWhite++
                        'B' -> appearedBlack++
                    }
                    // Geräumt: Startfeld eines Zuges oder geschlagene Figur
                    when (before) {
                        'W' -> vacatedWhite++
                        'B' -> vacatedBlack++
                    }
                }
            }

            if (changed == 0) return BoardDiff(0, null)
            // Mehr als eine Rochade an Veränderung kann kein einzelner Zug sein: dann hat die
            // Erkennung gepatzt oder es lief eine Animation über das Brett.
            if (changed > 4) return BoardDiff(changed, null)

            // Eindeutig ist der Zug, wenn alle neu aufgetauchten Figuren dieselbe Farbe haben.
            val mover = when {
                appearedWhite > 0 && appearedBlack == 0 -> true
                appearedBlack > 0 && appearedWhite == 0 -> false
                // Nichts neu belegt (nur geräumt, etwa bei einer verdeckten Figur):
                // dann entscheidet die geräumte Farbe, sofern auch die eindeutig ist.
                appearedWhite == 0 && appearedBlack == 0 && vacatedWhite > 0 && vacatedBlack == 0 -> true
                appearedWhite == 0 && appearedBlack == 0 && vacatedBlack > 0 && vacatedWhite == 0 -> false
                else -> null
            }
            return BoardDiff(changed, mover)
        }

        /**
         * Reine Funktion: reduziert ein Feld auf leer, hell oder dunkel.
         *
         * Der Brettvergleich läuft bewusst auf dieser groben Stufe und nicht auf der Figurenart.
         * Grund: Welche Figur auf einem Feld steht, verwechselt der Musterabgleich gelegentlich
         * (Läufer und Springer sehen klein sehr ähnlich aus) - ob dort überhaupt etwas steht und
         * ob es hell oder dunkel ist, kommt dagegen aus der Helligkeitsclusterung und ist deutlich
         * verlässlicher.
         *
         * Genau daran scheiterte die Erkennung nach einigen Zügen: Eine einzelne verwechselte
         * Figur auf einem unberührten Feld zählte als Veränderung. Mit jedem Zug kamen weitere
         * dazu, bis der Vergleich dauerhaft "unklar" meldete und nichts mehr angezeigt wurde.
         */
        fun colourClass(symbol: Char): Char = when {
            symbol == '.' -> '.'
            symbol.isUpperCase() -> 'W'
            else -> 'B'
        }

        /**
         * Reine Funktion: steht hier die Grundstellung einer neuen Partie?
         *
         * Wird gebraucht, um die eigene Farbe zwischen zwei Partien neu zu bestimmen - der Nutzer
         * spielt mal Weiß, mal Schwarz, und eine einmal gesetzte Sperre wäre in der nächsten Partie
         * womöglich falsch. Geprüft wird die Bildschirmansicht: die beiden äußeren Reihen sind voll
         * besetzt, die vier mittleren leer.
         *
         * @param screenBoard Brett so, wie es auf dem Bildschirm steht (row 0 = oben, row 7 = unten)
         */
        fun isFreshStartPosition(screenBoard: Array<CharArray>): Boolean {
            if (screenBoard.size < 8) return false
            var outerOccupied = 0
            var middleOccupied = 0
            for (r in 0 until 8) {
                val row = screenBoard.getOrNull(r) ?: return false
                if (row.size < 8) return false
                for (c in 0 until 8) {
                    if (row[c] == '.') continue
                    if (r <= 1 || r >= 6) outerOccupied++ else middleOccupied++
                }
            }
            // Ein bis zwei Fehlerkennungen in den vollen Reihen sind hinnehmbar, das Mittelfeld
            // muss aber leer sein - sonst ist die Partie längst im Gange.
            return outerOccupied >= 30 && middleOccupied == 0
        }

        /**
         * Reine Funktion: liest aus zwei Aufnahmen den gespielten Zug ab.
         *
         * Ein gewöhnlicher Zug verändert genau zwei Felder: das Startfeld wird leer, das Zielfeld
         * wird besetzt (oder wechselt bei einem Schlagfall seinen Inhalt). Genau dieses Muster wird
         * hier gesucht - damit steht fest, welche Figur gezogen ist, ohne das ganze Brett neu zu
         * erkennen.
         *
         * Alles andere (Rochade mit vier veränderten Feldern, en passant, Umwandlung, mehrere
         * gleichzeitige Änderungen durch Animationen) liefert bewusst null: dann muss die vollständige
         * Erkennung ran, statt auf gut Glück zu raten.
         *
         * Ob ein Feld besetzt ist, verrät die Streuung: ein leeres Feld ist eine gleichmäßige Fläche,
         * eine Figur bringt Kanten und damit Streuung mit sich.
         *
         * @param occupiedLimit Streuung, ab der ein Feld als besetzt gilt
         */
        fun detectMove(
            previousMeans: FloatArray,
            previousStds: FloatArray,
            currentMeans: FloatArray,
            currentStds: FloatArray,
            ignoredCells: Set<Int> = emptySet(),
            occupiedLimit: Float = 12.0f,
            meanTolerance: Float = 14.0f,
            stdTolerance: Float = 10.0f,
            // Bekannte Stellung zur Auflösung mehrdeutiger Zielfelder; ohne sie bleibt die
            // Erkennung streng und liefert bei mehreren Kandidaten lieber null
            standardBoard: Array<CharArray>? = null,
            isWhitePerspective: Boolean = true
        ): DetectedMove? {
            if (previousMeans.size != currentMeans.size || previousStds.size != currentStds.size) return null

            val emptied = mutableListOf<Int>()
            // Klare Zielfelder: vorher leer, jetzt besetzt
            val strong = mutableListOf<Int>()
            // Mögliche Zielfelder eines Schlagzugs: vorher und jetzt besetzt, aber anders
            val weak = mutableListOf<Int>()

            for (i in currentMeans.indices) {
                if (i in ignoredCells) continue
                val wasOccupied = previousStds[i] >= occupiedLimit
                val isOccupied = currentStds[i] >= occupiedLimit
                val contentChanged = abs(previousMeans[i] - currentMeans[i]) > meanTolerance ||
                    abs(previousStds[i] - currentStds[i]) > stdTolerance

                when {
                    wasOccupied && !isOccupied -> emptied.add(i)
                    !wasOccupied && isOccupied -> strong.add(i)
                    wasOccupied && isOccupied && contentChanged -> weak.add(i)
                    // Ein leeres Feld, dessen Helligkeit sich ändert, ist die Zugmarkierung der
                    // Oberfläche: Duolingo hebt Start- und Zielfeld des letzten Zuges farbig
                    // hervor und nimmt die vorherige Hervorhebung wieder weg. Das früher hier
                    // stehende Abbrechen ließ deshalb praktisch jeden Zug durchfallen.
                    else -> Unit
                }
            }

            // Ein Zug räumt genau ein Feld. Zwei geräumte Felder sind eine Rochade oder ein
            // en passant - dafür ist die vollständige Erkennung zuständig.
            if (emptied.size != 1) return null
            val fromCell = emptied[0]

            // Ein klares Zielfeld schlägt jeden Schlagkandidaten: ein stiller Zug ist der Regelfall
            val candidates = if (strong.size == 1) listOf(strong[0]) else strong + weak
            if (candidates.isEmpty()) return null
            if (candidates.size == 1) return DetectedMove(fromCell, candidates[0])

            // Mehrere Kandidaten: das kann die weggenommene Hervorhebung des vorigen Zuges sein.
            // Aufgelöst wird das über die Gangart der ziehenden Figur - erreichbar ist immer nur
            // eines der Felder.
            val board = standardBoard ?: return null
            val reachable = candidates.filter { candidate ->
                canPieceReach(board, fromCell, candidate, isWhitePerspective)
            }
            return if (reachable.size == 1) DetectedMove(fromCell, reachable[0]) else null
        }

        /**
         * Reine Funktion: liest eine Rochade aus zwei Aufnahmen ab.
         *
         * Eine Rochade räumt zwei Felder (König und Turm) und belegt zwei neue. [detectMove]
         * verlangt bewusst genau ein geräumtes Feld und liefert deshalb null - ohne diesen Zweig
         * fiele jede Rochade in die vollständige Erkennung, und genau dort entstehen die Fehler,
         * die die Buchführung verderben.
         *
         * Erkannt wird sie an ihrem festen Muster: König und Turm stehen auf ihren Ausgangsfeldern,
         * und die vier veränderten Felder passen zu einer der vier möglichen Rochaden. Zurück kommt
         * der Königszug in UCI-Schreibweise - den Turm zieht [applyUciMove] von selbst mit.
         *
         * @return Königszug wie "e1g1" oder null
         */
        fun detectCastling(
            previousStds: FloatArray,
            currentStds: FloatArray,
            standardBoard: Array<CharArray>,
            isWhitePerspective: Boolean,
            occupiedLimit: Float = 12.0f
        ): String? {
            if (previousStds.size < 64 || currentStds.size < 64) return null

            // Die vier Rochaden als Feldnamen: König von, König nach, Turm von, Turm nach
            val patterns = listOf(
                listOf("e1", "g1", "h1", "f1"),
                listOf("e1", "c1", "a1", "d1"),
                listOf("e8", "g8", "h8", "f8"),
                listOf("e8", "c8", "a8", "d8")
            )

            for (pattern in patterns) {
                val (kingFrom, kingTo, rookFrom, rookTo) = pattern
                val kingRow = 8 - (kingFrom[1] - '0')
                val king = standardBoard.getOrNull(kingRow)?.getOrNull(kingFrom[0] - 'a') ?: continue
                val rookRow = 8 - (rookFrom[1] - '0')
                val rook = standardBoard.getOrNull(rookRow)?.getOrNull(rookFrom[0] - 'a') ?: continue

                // Stehen König und Turm überhaupt noch zu Hause?
                val expectKing = if (kingFrom[1] == '1') 'K' else 'k'
                val expectRook = if (rookFrom[1] == '1') 'R' else 'r'
                if (king != expectKing || rook != expectRook) continue

                val cells = pattern.map { screenCellForSquare(it, isWhitePerspective) ?: return null }
                val (kingFromCell, kingToCell, rookFromCell, rookToCell) = cells

                val geraeumt = previousStds[kingFromCell] >= occupiedLimit &&
                    currentStds[kingFromCell] < occupiedLimit &&
                    previousStds[rookFromCell] >= occupiedLimit &&
                    currentStds[rookFromCell] < occupiedLimit
                val belegt = currentStds[kingToCell] >= occupiedLimit &&
                    currentStds[rookToCell] >= occupiedLimit
                if (geraeumt && belegt) return kingFrom + kingTo
            }
            return null
        }

        /**
         * Reine Funktion: kann die Figur auf dem Startfeld das Zielfeld überhaupt erreichen?
         *
         * Geprüft wird allein die Gangart samt freier Bahn - Fesselungen und Schach bleiben außen
         * vor. Für den Zweck genügt das: Es geht nur darum, unter mehreren veränderten Feldern das
         * echte Zielfeld von einer weggenommenen Hervorhebung zu unterscheiden, und die liegt so
         * gut wie nie auf einem erreichbaren Feld.
         *
         * @param fromCell Bildschirmfeld der ziehenden Figur
         * @param toCell   Bildschirmfeld des möglichen Ziels
         */
        fun canPieceReach(
            standardBoard: Array<CharArray>,
            fromCell: Int,
            toCell: Int,
            isWhitePerspective: Boolean
        ): Boolean {
            val fromSquare = squareForScreenCell(fromCell, isWhitePerspective) ?: return false
            val toSquare = squareForScreenCell(toCell, isWhitePerspective) ?: return false
            val fromRow = 8 - (fromSquare[1] - '0')
            val fromCol = fromSquare[0] - 'a'
            val toRow = 8 - (toSquare[1] - '0')
            val toCol = toSquare[0] - 'a'
            if (fromRow == toRow && fromCol == toCol) return false

            val piece = standardBoard.getOrNull(fromRow)?.getOrNull(fromCol) ?: return false
            if (piece == '.') return false
            val target = standardBoard.getOrNull(toRow)?.getOrNull(toCol) ?: return false
            // Auf eine eigene Figur zieht niemand
            if (target != '.' && target.isUpperCase() == piece.isUpperCase()) return false

            val dRow = toRow - fromRow
            val dCol = toCol - fromCol
            val absRow = abs(dRow)
            val absCol = abs(dCol)

            fun pathIsClear(): Boolean {
                val stepRow = dRow.compareTo(0)
                val stepCol = dCol.compareTo(0)
                var r = fromRow + stepRow
                var c = fromCol + stepCol
                while (r != toRow || c != toCol) {
                    if (standardBoard.getOrNull(r)?.getOrNull(c) != '.') return false
                    r += stepRow
                    c += stepCol
                }
                return true
            }

            return when (piece.lowercaseChar()) {
                'n' -> (absRow == 1 && absCol == 2) || (absRow == 2 && absCol == 1)
                'k' -> absRow <= 1 && absCol <= 1
                'r' -> (dRow == 0 || dCol == 0) && pathIsClear()
                'b' -> absRow == absCol && pathIsClear()
                'q' -> (dRow == 0 || dCol == 0 || absRow == absCol) && pathIsClear()
                'p' -> {
                    // Weiße Bauern laufen zu kleineren Reihenindizes (Richtung Reihe 8)
                    val forward = if (piece.isUpperCase()) -1 else 1
                    val startRow = if (piece.isUpperCase()) 6 else 1
                    when {
                        // Schlagen: ein Feld schräg, dort muss etwas stehen
                        absCol == 1 && dRow == forward -> target != '.'
                        // Ziehen: gerade aus, das Zielfeld muss leer sein
                        dCol == 0 && dRow == forward -> target == '.'
                        dCol == 0 && dRow == 2 * forward && fromRow == startRow ->
                            target == '.' && standardBoard[fromRow + forward][fromCol] == '.'
                        else -> false
                    }
                }
                else -> false
            }
        }

        /**
         * Reine Funktion: wendet einen erkannten Zug auf das Bildschirmbrett an.
         * Die Figur vom Startfeld steht danach auf dem Zielfeld, das Startfeld ist leer.
         */
        fun applyMoveToScreenBoard(screenBoard: Array<CharArray>, move: DetectedMove): Array<CharArray> {
            val result = Array(8) { r -> CharArray(8) { c -> screenBoard[r][c] } }
            val fromRow = move.fromCell / 8
            val fromCol = move.fromCell % 8
            val toRow = move.toCell / 8
            val toCol = move.toCell % 8
            if (fromRow !in 0..7 || fromCol !in 0..7 || toRow !in 0..7 || toCol !in 0..7) return result
            result[toRow][toCol] = result[fromRow][fromCol]
            result[fromRow][fromCol] = '.'
            return result
        }

        /**
         * Reine Funktion: Hat sich die Belegung des Bretts verändert?
         *
         * Verglichen wird Feld für Feld, was tatsächlich dort steht: die Streuung im Feld zeigt an,
         * ob eine Figur darauf steht, die mittlere Helligkeit unterscheidet helle von dunklen Figuren.
         * Damit hängt die Erkennung an den Figuren selbst und nicht an einem Gesamteindruck des Bildes.
         *
         * @param ignoredCells Felder, die übersprungen werden - dort liegt der eingezeichnete Pfeil
         *        und verfälscht Helligkeit und Streuung
         * @param meanTolerance zulässige Abweichung der mittleren Helligkeit eines Feldes
         * @param stdTolerance zulässige Abweichung der Streuung eines Feldes
         */
        fun boardCellsChanged(
            previousMeans: FloatArray,
            previousStds: FloatArray,
            currentMeans: FloatArray,
            currentStds: FloatArray,
            ignoredCells: Set<Int> = emptySet(),
            meanTolerance: Float = 14.0f,
            stdTolerance: Float = 10.0f
        ): Boolean {
            if (previousMeans.size != currentMeans.size || previousStds.size != currentStds.size) return true
            for (i in currentMeans.indices) {
                if (i in ignoredCells) continue
                if (abs(previousMeans[i] - currentMeans[i]) > meanTolerance) return true
                if (abs(previousStds[i] - currentStds[i]) > stdTolerance) return true
            }
            return false
        }

        /**
         * Reine Funktion: mittlerer Helligkeitsabstand zweier eingedampfter Brettausschnitte.
         *
         * Die Dauerbeobachtung vergleicht damit aufeinanderfolgende Frames, ohne jedes Mal die
         * vollständige Erkennung zu starten. Unterschiedlich lange Raster gelten als völlig
         * verschieden (Float.MAX_VALUE), damit ein Wechsel der Brettgröße sicher auslöst.
         */
        fun fingerprintDistance(a: FloatArray, b: FloatArray): Float {
            if (a.isEmpty() || b.isEmpty() || a.size != b.size) return Float.MAX_VALUE
            var sum = 0.0f
            for (i in a.indices) {
                sum += abs(a[i] - b[i])
            }
            return sum / a.size
        }

        /**
         * Reine Funktion: Zustandsautomat für die Perspektivsperre der Sitzung
         * @param currentLock aktuell gesperrte Perspektive der Sitzung (null = noch nicht gesperrt)
         * @param detectedPerspective die im aktuellen Frame erkannte Perspektive
         * @param occupiedCount Anzahl der belegten Felder im aktuellen Frame
         * @param medianSim Median der Template-Ähnlichkeit im aktuellen Frame
         * @param perspectiveConfidence Confidence der Perspektiverkennung aus detectPerspective in [0..1]
         * @return die aktualisierte Sperre (null = Confidence reicht noch nicht zum Sperren)
         */
        fun resolvePerspectiveLock(
            currentLock: Boolean?,
            detectedPerspective: Boolean,
            occupiedCount: Int,
            medianSim: Float,
            perspectiveConfidence: Float = 1.0f
        ): Boolean? {
            // 1. Neue Partie: ab 26 belegten Feldern und hoher Confidence wird die Perspektive zwangsweise neu kalibriert und gesperrt.
            // Die Perspektiv-Confidence muss dabei ebenfalls stimmen, sonst übernimmt eine Fehlerkennung
            // die Sperre für die gesamte Partie und die App analysiert die Figuren des Gegners.
            if (occupiedCount >= 26 && medianSim >= 0.60f && perspectiveConfidence >= 0.30f) {
                return detectedPerspective
            }

            // 2. Erstsperre: ohne bestehende Sperre muss die Confidence-Hürde (occupied >= 16 oder medianSim >= 0.70f) genommen werden
            if (currentLock == null) {
                return if ((occupiedCount >= 16 || medianSim >= 0.70f) && perspectiveConfidence >= 0.20f) {
                    detectedPerspective
                } else {
                    null // Unterhalb der Hürde wird noch nicht gesperrt, dieser Frame nutzt einmalig detectedPerspective
                }
            }

            // 3. Mittel-/Endspiel: bestehende Sperre beibehalten, damit Figurenentwicklung oder Angriff auf der Grundreihe die Perspektive nicht kippen lässt
            return currentLock
        }
    }
}