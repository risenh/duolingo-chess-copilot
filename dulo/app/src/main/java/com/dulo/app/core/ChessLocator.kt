package com.dulo.app.core

import android.graphics.Bitmap
import android.graphics.Rect
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Eigenentwickelter, hochgenauer adaptiver 2D-Brettlokalisator (Fast Integral-SAT + GridLine Direct Calibration Locator)
 *
 * Aufbau (Umbau: direkte Vermessung der Gitterlinien):
 * 1. Grobsuche (Coarse Locator): obere Größengrenze bis sW (400px) gelockert, x wird frei durchsucht (ohne Zentrierbonus), liefert die besten N Näherungsrahmen
 * 2. Feinkalibrierung (Fine Grid Calibrate):
 *    Vereinbarung: die Grobsuche läuft im 400er-Raum, die Feinkalibrierung dagegen in voller Bildauflösung
 *    (im selben Raum wie der Python-Prototyp grid_calibrate.py), damit die Pixelschwellen RESIDUAL_GATE/SNAP_PX dieselbe Bedeutung behalten.
 *    - Waagerecht: Medianprofil über 6 Zeilenbänder + Gauß-Prior auf die Kammwellenlänge + zweifache arithmetische Ausgleichsrechnung (x_i = x0 + i*step) + Vollbreiten-Einrastung bis 2px
 *    - Senkrecht: starke Kanten im Zeilenmittelprofil von Ober- und Unterkante + doppeltes Gatter aus beidseitig niedriger Varianz (Quadratbedingung, Stichentscheid über die Nähe zum Grobrahmen, Kreuzprüfung mit den inneren Linien)
 *    - Senkrechter Rückfallpfad: arithmetische Ausgleichsrechnung über die 7 inneren Trennlinien + Peaksuche mit 3-Punkt-Glättung und 75-Perzentil + Phasensuche in Stufen + Konsistenzprüfung der Außenkanten samt Einrastung
 *    - Abwechselnd waagerecht/senkrecht: nach der senkrechten Konvergenz läuft die waagerechte Feinkalibrierung im neuen Fenster erneut, das entfernt Grafiken und Schaltflächen ausserhalb des Bretts
 * 3. Auswahl der Kandidaten und Confidence-Vereinbarung: RefineResult(rect, confidence, residual, isCropped), erlaubt getrennte Feinkalibrierung der besten N Kandidaten und die Rettung über den Zweitkandidaten
 */
object ChessLocator {

    private const val RELATIVE_RESIDUAL_GATE_RATIO = 0.05f // Gatter für das Residuum: 5 % der Feldbreite (skaliert mit der Auflösung)
    private const val RELATIVE_SQUARE_GATE_RATIO = 0.015f   // Gatter der Quadratbedingung: 1.5 % der Brettgröße (mindestens 4.0px)
    private const val RELATIVE_SNAP_RATIO = 0.005f          // Gatter der Vollbreiten-Einrastung: 0.5 % der Bildschirmbreite (mindestens 2.0px)
    private const val MIN_LINES = 5
    private const val OUTLIER_FRAC = 0.25f
    private const val WAVE_GATE = 0.015f
    private const val WAVE_GATE_V = 0.025f
    private const val BAR_STD_GATE = 16.0f
    private const val BAR_GRAD_MIN = 3.0f

    /**
     * Ergebnis der Lokalisierung mit Confidence-Stufe, Residuum und Erkennung zugeschnittener Frames:
     * @param rect Bereich des Bretts im Koordinatensystem des Originalbildes (kann negative Koordinaten enthalten)
     * @param score Antwortwert der Grobsuche (feinere Sortierung innerhalb derselben Confidence-Stufe)
     * @param confidence Stufe der Feinkalibrierung: "high" (beide Achsen durch das Gatter, kein Überlauf), "medium" (nur eine Achse, Rückfallpfad oder zugeschnittener Frame), "low" (Rückfall gescheitert, es bleibt beim Grobrahmen)
     * @param residual Residuum der Ausgleichsrechnung in Pixeln
     * @param isCropped Frame mit negativem Rand, das Rect liegt teilweise ausserhalb des Bildes
     */
    data class LocateResult(
        val rect: Rect,
        val score: Float,
        val confidence: String = "high",
        val residual: Float = 0f,
        val isCropped: Boolean = false
    )

    fun locateBoard(bitmap: Bitmap): LocateResult {
        return locateTopCandidates(bitmap, 1).first()
    }

    /**
     * Liefert die besten N Rahmen, sortiert nach Confidence und Antwortwert (Grundlage der Kandidatenrettung)
     */
    fun locateTopCandidates(bitmap: Bitmap, maxCount: Int): List<LocateResult> {
        val width = bitmap.width
        val height = bitmap.height

        // 1. Auf eine einheitliche Breite von 400px verkleinern: geräteunabhängig und in Millisekunden zu rechnen
        val scale = 400.0f / width
        val sW = 400
        val sH = (height * scale).toInt()

        val scaledBmp = if (width == sW && height == sH) {
            bitmap
        } else {
            Bitmap.createScaledBitmap(bitmap, sW, sH, true)
        }

        val pixels = IntArray(sW * sH)
        scaledBmp.getPixels(pixels, 0, sW, 0, 0, sW, sH)
        if (scaledBmp !== bitmap) {
            scaledBmp.recycle()
        }

        // 2. Graustufen sowie waagerechten und senkrechten Sobel-Gradienten bestimmen (|gx| + |gy|)
        val gray = FloatArray(sW * sH)
        for (i in pixels.indices) {
            val p = pixels[i]
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            gray[i] = 0.299f * r + 0.587f * g + 0.114f * b
        }

        val mag = FloatArray(sW * sH)
        for (y in 1 until sH - 1) {
            val yOffset = y * sW
            val yPrev = (y - 1) * sW
            val yNext = (y + 1) * sW
            for (x in 1 until sW - 1) {
                val v00 = gray[yPrev + x - 1]
                val v02 = gray[yPrev + x + 1]
                val v10 = gray[yOffset + x - 1]
                val v12 = gray[yOffset + x + 1]
                val v20 = gray[yNext + x - 1]
                val v22 = gray[yNext + x + 1]
                val v01 = gray[yPrev + x]
                val v21 = gray[yNext + x]

                val gx = (v02 + 2f * v12 + v22) - (v00 + 2f * v10 + v20)
                val gy = (v20 + 2f * v21 + v22) - (v00 + 2f * v01 + v02)
                mag[yOffset + x] = abs(gx) + abs(gy)
            }
        }

        // 3. Integralbilder (SAT) für Graustufen und Gradient aufbauen
        val satGray = DoubleArray((sW + 1) * (sH + 1))
        val satMag = DoubleArray((sW + 1) * (sH + 1))
        val satStride = sW + 1

        for (y in 1..sH) {
            var rowSumG = 0.0
            var rowSumM = 0.0
            val srcOffset = (y - 1) * sW
            val satRowOffset = y * satStride
            val satPrevRowOffset = (y - 1) * satStride

            for (x in 1..sW) {
                rowSumG += gray[srcOffset + x - 1]
                rowSumM += mag[srcOffset + x - 1]
                satGray[satRowOffset + x] = satGray[satPrevRowOffset + x] + rowSumG
                satMag[satRowOffset + x] = satMag[satPrevRowOffset + x] + rowSumM
            }
        }

        fun rectSum(sat: DoubleArray, x1: Int, y1: Int, x2: Int, y2: Int): Double {
            val cX1 = max(0, min(sW, x1))
            val cY1 = max(0, min(sH, y1))
            val cX2 = max(0, min(sW, x2))
            val cY2 = max(0, min(sH, y2))
            if (cX2 <= cX1 || cY2 <= cY1) return 0.0
            return sat[cY2 * satStride + cX2] - sat[cY1 * satStride + cX2] -
                    sat[cY2 * satStride + cX1] + sat[cY1 * satStride + cX1]
        }

        fun rectMean(sat: DoubleArray, x1: Int, y1: Int, x2: Int, y2: Int): Float {
            val w = max(1, x2 - x1)
            val h = max(1, y2 - y1)
            return (rectSum(sat, x1, y1, x2, y2) / (w * h)).toFloat()
        }

        // 4. Abwechselndes 8x8-Schachbrettmuster
        val pattern = FloatArray(64) { idx ->
            val r = idx / 8
            val c = idx % 8
            if ((r + c) % 2 == 0) 1.0f else -1.0f
        }

        fun evaluateBox(x: Int, y: Int, size: Int): Float {
            val step = size / 8.0f

            // Kern des Umbaus: Prüfung der Energiebalance des Gitters, damit einseitige Streifenmuster (etwa die Figurenablage der Oberfläche) nicht mehr als Brett gelten
            var hEdgeScore = 0.0f
            var vEdgeScore = 0.0f
            for (i in 1..7) {
                val ly = (y + i * step).toInt()
                val lx = (x + i * step).toInt()
                hEdgeScore += rectMean(satMag, x, ly - 1, x + size, ly + 2)
                vEdgeScore += rectMean(satMag, lx - 1, y, lx + 2, y + size)
            }
            
            // Die Figurenablage hat starke waagerechte Kanten, aber keine gleichmäßig verteilten senkrechten Kanten
            val minEdge = min(hEdgeScore, vEdgeScore)
            val maxEdge = max(hEdgeScore, vEdgeScore)
            val edgeBalance = minEdge / max(1e-5f, maxEdge) // bei einem echten Brett liegt dieser Wert nahe 1.0
            
            // Die Energie wird mit der Balance multipliziert: der Wert eines einseitig starken Rahmens bricht auf ein Zehntel ein
            val balancedEdgeScore = (hEdgeScore + vEdgeScore) * edgeBalance

            // (2) Abtastung der 4 Ecken jedes der 8x8 Felder (18 % Randbereich), das spart die Figur in der Feldmitte aus
            val gridMeans = FloatArray(64)
            val cornerW = max(1, (step * 0.18f).toInt())
            var gridSum = 0.0f

            for (r in 0..7) {
                val cy1 = (y + r * step).toInt()
                val cy2 = (cy1 + step).toInt()
                for (c in 0..7) {
                    val cx1 = (x + c * step).toInt()
                    val cx2 = (cx1 + step).toInt()

                    val m1 = rectMean(satGray, cx1, cy1, cx1 + cornerW, cy1 + cornerW)
                    val m2 = rectMean(satGray, cx2 - cornerW, cy1, cx2, cy1 + cornerW)
                    val m3 = rectMean(satGray, cx1, cy2 - cornerW, cx1 + cornerW, cy2)
                    val m4 = rectMean(satGray, cx2 - cornerW, cy2 - cornerW, cx2, cy2)

                    val cellVal = (m1 + m2 + m3 + m4) * 0.25f
                    gridMeans[r * 8 + c] = cellVal
                    gridSum += cellVal
                }
            }

            val gridAvg = gridSum / 64.0f
            var corrSum = 0.0f
            for (i in 0 until 64) {
                corrSum += (gridMeans[i] - gridAvg) * pattern[i]
            }
            val corr = abs(corrSum)

            // Plausibilitätsannahme zur senkrechten Lage in Duolingo (Unterkante zwischen 60 % und 99 %)
            val bottomRatio = (y + size).toFloat() / sH.toFloat()
            // Die Annahme ist bewusst locker gehalten, die Entscheidung fällt im Gitteralgorithmus
            val posPrior = if (bottomRatio in 0.55f..0.99f) 1.0f else 0.35f

            // Das Gewicht des 8x8-Musters ist erhöht und mit der Kantenbalance verrechnet
            return (corr * 2.5f + balancedEdgeScore * 0.5f) * posPrior
        }

        // 5. Stufe 1: Grobsuche (step=4), Größe bis sW (400px), x frei über das gesamte Intervall [0, sW-size] ohne Zentrierannahme
        val minSize = (0.60f * sW).toInt()
        val maxSize = sW
        val topEntries = ArrayList<Pair<Float, IntArray>>() // (score, [x,y,size])

        fun recordCandidate(score: Float, x: Int, y: Int, size: Int) {
            val minSep = size * 0.12f // Mindestabstand der Peaks für die Entdopplung
            val idx = topEntries.indexOfFirst { (_, e) ->
                abs(e[0] - x) < minSep && abs(e[1] - y) < minSep && abs(e[2] - size) < minSep
            }
            if (idx >= 0) {
                if (score > topEntries[idx].first) topEntries[idx] = Pair(score, intArrayOf(x, y, size))
                return
            }
            topEntries.add(Pair(score, intArrayOf(x, y, size)))
            topEntries.sortByDescending { it.first }
            while (topEntries.size > maxCount + 4) topEntries.removeAt(topEntries.size - 1)
        }

        // Schrittweite 8 für size entspricht dem Python-Referenzlauf (die Lücken deckt die Feinsuche mit +-5 ab);
        // x/y bleiben bei step=4 im verkleinerten Bild, weil evaluateBox in Kotlin rahmenweise läuft
        // (die Python-Referenz zählt dank numpy vektorisiert alles auf), die Laufzeit auf dem Gerät klärt die Telemetrie
        for (size in minSize..maxSize step 8) {
            val minY = (sH * 0.15f).toInt()
            val maxY = sH - size

            // Der Suchbereich in x ist völlig offen, damit auch Layouts mit Rand oder Versatz getroffen werden
            for (x in 0..(sW - size) step 4) {
                // Die obere Grenze in y schließt sH-size aus (wie das halboffene Intervall n_y = s_h-size-y_min der Python-Referenz)
                for (y in minY until maxY step 4) {
                    val score = evaluateBox(x, y, size)
                    recordCandidate(score, x, y, size)
                }
            }
        }

        // 6. Feinsuche um die Grobkandidaten (+-5 Pixel mit step=1, Größenfenster +-5 wie in der Python-Referenz)
        val initialCandidates = ArrayList<Pair<Float, IntArray>>()
        for (cand in topEntries.take(maxCount + 2)) {
            val (cScore, box) = cand
            var bestScore = cScore
            var bestX = box[0]
            var bestY = box[1]
            var bestSize = box[2]
            for (size in max(minSize, box[2] - 5)..min(maxSize, box[2] + 5) step 1) {
                for (x in max(0, box[0] - 4)..min(sW - size, box[0] + 4) step 1) {
                    for (y in max(0, box[1] - 4)..min(sH - size, box[1] + 4) step 1) {
                        val sc = evaluateBox(x, y, size)
                        if (sc > bestScore) {
                            bestScore = sc
                            bestX = x
                            bestY = y
                            bestSize = size
                        }
                    }
                }
            }
            initialCandidates.add(Pair(bestScore, intArrayOf(bestX, bestY, bestSize)))
        }

        // 7. Feinkalibrierung: jeder der besten N Kandidaten wird einzeln über die Gitterlinienprofile arithmetisch nachgezogen.
        // Entscheidende Vereinbarung: refine muss in voller Bildauflösung laufen (der Python-Prototyp tut das ebenfalls),
        // die Grobsuche bleibt nur aus Geschwindigkeitsgründen im 400er-Raum. Lief refine dort, waren alle Pixelschwellen
        // (RESIDUAL_GATE/SNAP_PX/Ausreißertoleranz) gegenüber dem Originalbild um etwa das 3.15-fache gelockert, dazu kam eine Peak-Quantisierung von 3px:
        // auf einem von der Blase verdeckten Frame kam ein Phantomrahmen mit kleinem Residuum durch das Gatter und schlug den echten Rahmen
        // (Vorfall Screenshot_20260818_225702, falscher Rahmen L=-56,T=284,size=1266; im 400er-Raum ließ sich derselbe Phantomrahmen in Python nachstellen).
        val fullGray = FloatArray(width * height)
        val fullPixels = IntArray(width * height)
        bitmap.getPixels(fullPixels, 0, width, 0, 0, width, height)
        for (i in fullPixels.indices) {
            val p = fullPixels[i]
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            fullGray[i] = 0.299f * r + 0.587f * g + 0.114f * b
        }

        val invScale = 1.0f / scale
        val refinedResults = ArrayList<LocateResult>()

        for ((score, coarseBox) in initialCandidates) {
            // Der Grobkandidat wird in Originalkoordinaten umgerechnet und dort fein nachgezogen (das Ergebnis liegt bereits im Originalraum)
            val ox = (coarseBox[0] * invScale).roundToInt()
            val oy = (coarseBox[1] * invScale).roundToInt()
            val oSize = (coarseBox[2] * invScale).roundToInt()
            val calibrated = refineByGridLines(fullGray, width, height, ox, oy, oSize)

            // Vollbreiten-Einrastung: weicht der Rahmen um höchstens 0.5 % (mindestens 2px) von der Bildschirmbreite ab, wird er auf 0 und width gesetzt
            var origX = calibrated.x0.roundToInt()
            var origSize = calibrated.size.roundToInt()
            val origY = calibrated.y0.roundToInt()
            val snapPx = max(2.0f, width * RELATIVE_SNAP_RATIO)
            if (abs(calibrated.x0) <= snapPx && abs(calibrated.size - width) <= snapPx) {
                origX = 0
                origSize = width
            }

            // Umgang mit zugeschnittenen Frames: der Überlauf wird nicht per clamp verfälscht, sondern gekennzeichnet und die Confidence herabgestuft
            val isCropped = origX < 0 || origY < 0 || (origX + origSize) > width || (origY + origSize) > height
            val finalConf = if (isCropped && calibrated.confidence == "high") "medium" else calibrated.confidence

            refinedResults.add(
                LocateResult(
                    rect = Rect(origX, origY, origX + origSize, origY + origSize),
                    score = score,
                    confidence = finalConf,
                    residual = calibrated.residual,
                    isCropped = isCropped
                )
            )
        }

        // 8. Sortierung: zuerst die Confidence-Stufe, dann der Gesamtwert (Grobwertung + Bonus für durchgehende Abwechslung + Vorzug für volle Breite - Abzug für das Residuum)
        fun confRank(c: String): Int = when (c) {
            "high" -> 2
            "medium" -> 1
            else -> 0
        }

        fun consensusScore(res: LocateResult): Float {
            val altRes = computeRingAlternationDetailed(
                fullGray,
                width,
                height,
                res.rect.left.toFloat(),
                res.rect.top.toFloat(),
                res.rect.width().toFloat()
            )
            val fullSnapBonus = if (abs(res.rect.width() - width) <= 2) 50f else 0f
            val altBonus = if (altRes.allRowsPass) 300f else (altRes.totalScore * 100f)
            return res.score + altBonus + fullSnapBonus - (res.residual * 5f)
        }

        refinedResults.sortWith { a, b ->
            val rankDiff = confRank(b.confidence) - confRank(a.confidence)
            if (rankDiff != 0) return@sortWith rankDiff
            val scoreDiff = consensusScore(b).compareTo(consensusScore(a))
            if (scoreDiff != 0) return@sortWith scoreDiff
            val resDiff = (a.residual * 2f).roundToInt() - (b.residual * 2f).roundToInt()
            if (resDiff != 0) return@sortWith resDiff
            b.score.compareTo(a.score)
        }

        return refinedResults.take(maxCount)
    }

    // =========================================================================
    // Stufe 2: Kern der direkten Gitterlinienvermessung (Kotlin-Umsetzung des Python-Prototyps)
    // =========================================================================

    internal data class CalibratedBox(
        val x0: Float,
        val y0: Float,
        val size: Float,
        val confidence: String,
        val residual: Float
    )

    internal data class FitResult(
        val p0: Float,
        val step: Float,
        val residual: Float,
        val lineCount: Int,
        val isOk: Boolean
    )

    /**
     * Mehrfache arithmetische Ausgleichsrechnung: Indizes zuordnen -> Ausgleich nach kleinsten Quadraten -> Ausreißer entfernen -> erneut ausgleichen
     */
    private fun fitArithmetic(points: List<Pair<Int, Float>>): Triple<Float, Float, Float> {
        var sumX = 0.0
        var sumY = 0.0
        var sumXX = 0.0
        var sumXY = 0.0
        val n = points.size.toDouble()

        for ((idx, pos) in points) {
            val x = idx.toDouble()
            val y = pos.toDouble()
            sumX += x
            sumY += y
            sumXX += x * x
            sumXY += x * y
        }

        val denom = n * sumXX - sumX * sumX
        val step = if (abs(denom) > 1e-6) ((n * sumXY - sumX * sumY) / denom).toFloat() else 0f
        val p0 = ((sumY - step.toDouble() * sumX) / n).toFloat()

        var totalResid = 0.0
        for ((idx, pos) in points) {
            totalResid += abs(pos - (p0 + idx * step))
        }
        val avgResid = (totalResid / n).toFloat()

        return Triple(p0, step, avgResid)
    }

    internal fun twoPassFit(lines: List<Float>, x0Est: Float, stepEst: Float): FitResult {
        fun assign(p0: Float, step: Float): List<Pair<Int, Float>> {
            val res = ArrayList<Pair<Int, Float>>()
            for (p in lines) {
                val idx = ((p - p0) / step).roundToInt()
                if (idx in 1..7 && abs(p - (p0 + idx * step)) <= OUTLIER_FRAC * step + 2.0f) {
                    res.add(Pair(idx, p))
                }
            }
            return res
        }

        val maxAllowedResid = stepEst * RELATIVE_RESIDUAL_GATE_RATIO

        val cand1 = assign(x0Est, stepEst)
        if (cand1.size < MIN_LINES) {
            return FitResult(x0Est, stepEst, 99f, cand1.size, false)
        }
        val (p0_1, step_1, _) = fitArithmetic(cand1)

        val cand2 = assign(p0_1, step_1)
        if (cand2.size < MIN_LINES) {
            return FitResult(x0Est, stepEst, 99f, cand2.size, false)
        }
        var (p0_2, step_2, resid_2) = fitArithmetic(cand2)
        var finalCand = cand2

        // Dritter Durchgang: liegt das Residuum über dem Gatter, wird der Punkt mit dem größten Residuum entfernt (eine einzelne Störlinie)
        if (resid_2 > maxAllowedResid && cand2.size > MIN_LINES) {
            var worstIdx = 0
            var worstResid = -1f
            for (i in cand2.indices) {
                val r = abs(cand2[i].second - (p0_2 + cand2[i].first * step_2))
                if (r > worstResid) {
                    worstResid = r
                    worstIdx = i
                }
            }
            val cand3 = cand2.filterIndexed { index, _ -> index != worstIdx }
            val (p0_3, step_3, resid_3) = fitArithmetic(cand3)
            if (resid_3 < resid_2) {
                p0_2 = p0_3
                step_2 = step_3
                resid_2 = resid_3
                finalCand = cand3
            }
        }

        val isOk = resid_2 <= maxAllowedResid
        return FitResult(p0_2, step_2, resid_2, finalCand.size, isOk)
    }

    /**
     * Waagerechte Kalibrierung über die Kammresonanz: Medianprofil über 6 Zeilenbänder + zweifache arithmetische Ausgleichsrechnung + Vollbreiten-Einrastung
     */
    private fun refineHorizontal(
        gray: FloatArray, sW: Int, sH: Int,
        x0c: Int, y0c: Int, sizec: Int
    ): Pair<Boolean, FloatArray> { // Pair(ok, [x0, size, residual])
        val y1 = max(0, (y0c - 0.05f * sizec).toInt())
        val y2 = min(sH, (y0c + sizec + 0.05f * sizec).toInt())
        val bandH = max(1, (y2 - y1) / 8)

        // Mittelwertprofil des senkrechten Sobel-Gradienten gx aus 6 mittleren Zeilenbändern
        val bandProfiles = Array(6) { FloatArray(sW) }
        for (b in 0 until 6) {
            val byStart = y1 + (b + 1) * bandH
            val byEnd = min(y2, byStart + bandH)
            val count = max(1, byEnd - byStart)
            for (y in byStart until byEnd) {
                val rowOff = y * sW
                for (x in 1 until sW - 1) {
                    val gx = abs(gray[rowOff + x + 1] - gray[rowOff + x - 1])
                    bandProfiles[b][x] += gx
                }
            }
            for (x in 0 until sW) {
                bandProfiles[b][x] /= count.toFloat()
            }
        }

        fun lineEnergy(xi: Int): Float {
            if (xi < 1 || xi >= sW - 1) return 0f
            val vals = FloatArray(6) { b ->
                max(bandProfiles[b][xi - 1], max(bandProfiles[b][xi], bandProfiles[b][xi + 1]))
            }
            vals.sort()
            return (vals[2] + vals[3]) * 0.5f // Median der 6 Bänder
        }

        fun combScore(size: Float, x0: Float): Float {
            var sc = 0f
            val step = size / 8.0f
            for (i in 1..7) {
                sc += lineEnergy((x0 + i * step).roundToInt())
            }
            val dev = (size - sizec) / (0.06f * sizec)
            sc *= exp(-0.5f * dev * dev)
            return sc
        }

        // Suche über Größe und Phase (Schrittweite 0.25px wie np.arange(s_lo, s_hi+0.25, 0.25) der Python-Referenz;
        // über einen Zähler gesteuert, damit sich keine Gleitkommafehler aufaddieren)
        val sLo = max(8f, sizec * 0.88f)
        val sHi = min(sW.toFloat(), sizec * 1.14f)
        var bestSc = -1f
        var bestSize = sizec.toFloat()
        var bestX0 = x0c.toFloat()

        var kS = 0
        while (true) {
            val s = sLo + kS * 0.25f
            if (s >= sHi + 0.25f) break
            val xcLo = max(-s * 0.05f, x0c - 0.25f * sizec)
            val xcHi = min(sW - s + s * 0.05f, x0c + 0.25f * sizec)
            var x = xcLo
            while (x <= xcHi + 0.9f) {
                val sc = combScore(s, x)
                if (sc > bestSc) {
                    bestSc = sc
                    bestSize = s
                    bestX0 = x
                }
                x += 1.0f
            }
            kS++
        }

        // Peaksuche nahe der besten Resonanzphase und zweifache arithmetische Ausgleichsrechnung
        val medProf = FloatArray(sW)
        val tmpVals = FloatArray(6)
        for (x in 0 until sW) {
            for (b in 0 until 6) tmpVals[b] = bandProfiles[b][x]
            tmpVals.sort()
            medProf[x] = (tmpVals[2] + tmpVals[3]) * 0.5f
        }

        val rawPeaks = ArrayList<Float>()
        val stepR = bestSize / 8.0f
        for (i in 1..7) {
            val xc = (bestX0 + i * stepR).roundToInt()
            val lo = max(1, xc - 2)
            val hi = min(sW - 2, xc + 2)
            var maxV = -1f
            var maxP = xc
            for (p in lo..hi) {
                if (medProf[p] > maxV) {
                    maxV = medProf[p]
                    maxP = p
                }
            }
            rawPeaks.add(maxP.toFloat())
        }

        var fit = twoPassFit(rawPeaks, bestX0, stepR)
        if (fit.isOk && abs(fit.step - stepR) > WAVE_GATE * stepR) {
            fit = FitResult(bestX0, stepR, 99f, fit.lineCount, false)
        }

        var outX0 = if (fit.isOk) fit.p0 else bestX0
        var outSize = if (fit.isOk) fit.step * 8.0f else bestSize
        val residual = if (fit.isOk) fit.residual else 99f

        // Vollbreiten-Einrastung: Abweichung höchstens 0.5 % (mindestens 2px)
        val snapPx = max(2.0f, sW * RELATIVE_SNAP_RATIO)
        if (abs(outX0) <= snapPx && abs(outSize - sW) <= snapPx) {
            outX0 = 0f
            outSize = sW.toFloat()
        }

        return Pair(fit.isOk, floatArrayOf(outX0, outSize, residual, bestSize))
    }

    /**
     * Hauptanker für Ober- und Unterkante: starke Kanten im Zeilenmittelprofil + doppeltes Gatter aus beidseitig niedriger Varianz + Stichentscheid über die Nähe zum Grobrahmen
     */
    private fun findVerticalBarAnchors(
        gray: FloatArray, sW: Int, sH: Int,
        x0c: Int, y0c: Int, sizec: Int,
        expectedSize: Float, x0: Float, size: Float
    ): Triple<Int, Int, Float>? {
        val xa = max(0, (x0 - 0.02f * sW).toInt())
        val xb = min(sW, (x0 + size + 0.02f * sW).toInt())
        val spanX = max(1, xb - xa)

        val prof = FloatArray(sH)
        val rowStd = FloatArray(sH)

        for (y in 0 until sH) {
            var sum = 0.0
            var sumSq = 0.0
            val rowOff = y * sW
            for (x in xa until xb) {
                val v = gray[rowOff + x].toDouble()
                sum += v
                sumSq += v * v
            }
            val mean = sum / spanX
            prof[y] = mean.toFloat()
            val variance = max(0.0, (sumSq / spanX) - mean * mean)
            rowStd[y] = kotlin.math.sqrt(variance).toFloat()
        }

        val g = FloatArray(sH)
        for (y in 1 until sH) {
            g[y] = abs(prof[y] - prof[y - 1])
        }

        val spanY = (0.18f * sW).toInt()
        val tLo = max(0, y0c - spanY)
        val tHi = min(sH - 1, y0c + (0.05f * sW).toInt())
        val bLo = max(0, y0c + sizec - (0.05f * sW).toInt())
        val bHi = min(sH - 2, y0c + sizec + spanY)

        fun findCandidates(lo: Int, hi: Int, isTop: Boolean): List<Int> {
            val cands = ArrayList<Int>()
            for (y in lo until hi) {
                if (g[y] < BAR_GRAD_MIN) continue
                if (!(g[y] >= g[y - 1] && g[y] >= g[min(sH - 1, y + 1)])) continue

                // Beide Seiten der Grenze werden geprüft (wie in der Python-Referenz): auf einer Seite genügen 3 Zeilen mit mittlerer std < 16.0
                val bandA = if (isTop) max(0, y - 4) until y else max(0, y - 3) until min(sH, y + 1)
                val bandB = (y + 1) until min(sH, y + 5)

                fun bandStdOk(range: IntRange): Boolean {
                    if (range.last - range.first + 1 < 3) return false
                    var stdSum = 0f
                    var count = 0
                    for (sy in range) {
                        stdSum += rowStd[sy]
                        count++
                    }
                    return count >= 3 && (stdSum / count) < BAR_STD_GATE
                }

                if (bandStdOk(bandA) || bandStdOk(bandB)) {
                    cands.add(y)
                }
            }
            return cands
        }

        val tops = findCandidates(tLo, tHi, true)
        val bots = findCandidates(bLo, bHi, false)

        // Alle Paare sammeln, die die Quadratbedingung erfüllen, und nach (Abweichung, Nähe zum Grobrahmen) auswählen (Stichentscheid wie in Python)
        val squareGate = max(4.0f, expectedSize * RELATIVE_SQUARE_GATE_RATIO)
        val ties = ArrayList<Triple<Int, Int, Float>>()
        for (t in tops) {
            for (b in bots) {
                val dev = abs((b - t).toFloat() - expectedSize)
                if (dev <= squareGate) {
                    ties.add(Triple(t, b, dev))
                }
            }
        }

        if (ties.isEmpty()) return null
        return ties.minWithOrNull { a, b ->
            val devDiff = a.third.compareTo(b.third)
            if (abs(a.third - b.third) > 1e-4) return@minWithOrNull devDiff
            val distA = abs(a.first - y0c) + abs(a.second - (y0c + sizec))
            val distB = abs(b.first - y0c) + abs(b.second - (y0c + sizec))
            distA.compareTo(distB)
        }
    }

    /**
     * Peaksuche in einem 1D-Profil (3-Punkt-Glättung + adaptive Schwelle am 75-Perzentil + Abstandsunterdrückung, deckungsgleich mit Python _detect_peaks)
     */
    private fun detectPeaks1D(prof: FloatArray, minSep: Float, thrFloor: Float = 1.5f, pct: Float = 0.75f): List<Float> {
        if (prof.size < 3) return emptyList()
        val n = prof.size - 1
        val rawDiff = FloatArray(n) { i -> abs(prof[i + 1] - prof[i]) }

        // Glättung über 3 Punkte, entspricht np.convolve(g, [1,1,1]/3, mode='same')
        val gSmooth = FloatArray(n)
        for (i in 0 until n) {
            val vPrev = if (i > 0) rawDiff[i - 1] else 0f
            val vCurr = rawDiff[i]
            val vNext = if (i < n - 1) rawDiff[i + 1] else 0f
            val count = (if (i > 0) 1 else 0) + 1 + (if (i < n - 1) 1 else 0)
            gSmooth[i] = (vPrev + vCurr + vNext) / count.toFloat()
        }

        // Schwelle am 75-Perzentil
        val sortedG = gSmooth.clone().apply { sort() }
        val pIdx = (sortedG.size * pct).toInt().coerceIn(0, sortedG.size - 1)
        val thr = max(thrFloor, sortedG[pIdx])

        // Lokale Maxima auswählen
        val cand = ArrayList<Pair<Float, Int>>() // (amp, idx)
        for (i in 1 until n - 1) {
            if (gSmooth[i] >= thr && gSmooth[i] >= gSmooth[i - 1] && gSmooth[i] > gSmooth[i + 1]) {
                cand.add(Pair(gSmooth[i], i))
            }
        }
        cand.sortByDescending { it.first }

        // Nichtmaximum-Unterdrückung über den Mindestabstand
        val keep = ArrayList<Int>()
        for ((_, idx) in cand) {
            if (keep.none { abs(it - idx) < minSep }) {
                keep.add(idx)
            }
        }
        keep.sort()
        return keep.map { it.toFloat() }
    }

    /**
     * Ausgleichsrechnung über die inneren waagerechten Trennlinien (entspricht Python _fit_horizontal_lines)
     */
    private fun fitHorizontalLines(
        gray: FloatArray, sW: Int, sH: Int,
        x0c: Int, y0c: Int, sizec: Int
    ): FitResult {
        val sEst = sizec / 8.0f
        val rawPeaks = ArrayList<Float>()

        for (c in 0 until 8) {
            val x1 = max(0, (x0c + (c + 0.22f) * sEst).toInt())
            val x2 = min(sW, (x0c + (c + 0.78f) * sEst).toInt())
            val width = max(1, x2 - x1)
            if (width < 4) continue

            val yLo = max(0, (y0c - 0.5f * sizec).toInt())
            val yHi = min(sH, (y0c + sizec + 0.5f * sizec).toInt())

            val prof = FloatArray(yHi - yLo)
            for (y in yLo until yHi) {
                var sum = 0f
                val rowOff = y * sW
                for (x in x1 until x2) sum += gray[rowOff + x]
                prof[y - yLo] = sum / width
            }

            // Mit 3-Punkt-Glättung und adaptiver Schwelle am 75-Perzentil
            val peaks = detectPeaks1D(prof, minSep = 0.5f * sEst, thrFloor = 1.5f, pct = 0.75f)
            for (p in peaks) {
                rawPeaks.add(p + yLo)
            }
        }

        // Clustern über die Spaltenbänder hinweg (entspricht Python _cluster_lines)
        rawPeaks.sort()
        val clustered = ArrayList<Float>()
        if (rawPeaks.isNotEmpty()) {
            var curGroup = ArrayList<Float>()
            curGroup.add(rawPeaks[0])
            val tol = 0.25f * sEst
            for (i in 1 until rawPeaks.size) {
                val p = rawPeaks[i]
                if (p - curGroup.last() < tol) {
                    curGroup.add(p)
                } else {
                    curGroup.sort()
                    clustered.add(curGroup[curGroup.size / 2])
                    curGroup = ArrayList()
                    curGroup.add(p)
                }
            }
            curGroup.sort()
            clustered.add(curGroup[curGroup.size / 2])
        }

        return twoPassFit(clustered, y0c.toFloat(), sEst)
    }

    /**
     * Energie der starken Außenkanten bestimmen (liefert (topEdge, bottomEdge, yTopEdge, yBottomEdge))
     */
    private fun outerEdgeScore(
        gray: FloatArray, sW: Int, sH: Int,
        x0: Float, size: Float, yFit: Float
    ): FloatArray { // [te, be, yt, yb]
        val xa = max(0, x0.toInt())
        val xb = min(sW, (x0 + size).toInt())
        val spanX = max(1, xb - xa)

        fun localMax(yTarget: Float): Pair<Float, Int> {
            val yc = yTarget.roundToInt()
            // Sobel gy (ksize=3) ist nur auf den inneren Zeilen [1, sH-2] gültig, das Suchfenster bleibt darin
            val lo = max(1, yc - 5)
            val hi = min(sH - 1, yc + 6)
            var maxG = 0f
            var bestY = yc
            for (y in lo until hi) {
                // Sobel gy 3x3: |Faltung [1,2,1] der Zeile y+1 minus Faltung [1,2,1] der Zeile y-1|,
                // dimensionsgleich mit cv2.Sobel(gray, 0, 1) in Python _outer_edge_score
                // (die Randspalten werden gespiegelt fortgesetzt, entspricht BORDER_DEFAULT)
                var sum = 0f
                val prevOff = (y - 1) * sW
                val nextOff = (y + 1) * sW
                for (x in xa until xb) {
                    val xl = if (x > 0) x - 1 else 1
                    val xr = if (x < sW - 1) x + 1 else sW - 2
                    val up = gray[prevOff + xl] + 2f * gray[prevOff + x] + gray[prevOff + xr]
                    val dn = gray[nextOff + xl] + 2f * gray[nextOff + x] + gray[nextOff + xr]
                    sum += abs(dn - up)
                }
                val avg = sum / spanX
                if (avg > maxG) {
                    maxG = avg
                    bestY = y
                }
            }
            return Pair(maxG, bestY)
        }

        val (te, yt) = localMax(yFit)
        val (be, yb) = localMax(yFit + size)
        return floatArrayOf(te, be, yt.toFloat(), yb.toFloat())
    }

    /**
     * Haupteinstieg der Gitter-Feinkalibrierung (im 400px breiten Raum)
     */
    internal fun refineByGridLines(
        gray: FloatArray, sW: Int, sH: Int,
        x0c: Int, y0c: Int, sizec: Int
    ): CalibratedBox {
        // 1. Waagerechte Feinkalibrierung
        val (hOk, hParams) = refineHorizontal(gray, sW, sH, x0c, y0c, sizec)
        var x0 = hParams[0]
        var size = hParams[1]
        val hResid = hParams[2]
        val sizeV = if (hOk) size else hParams[3]

        // 2. Senkrechte Feinkalibrierung: Hauptanker der Außenkanten + Prüfung der Abwechslung per Ringabtastung (Algorithmus aus duolingo-pgn-export)
        var y0 = y0c.toFloat()
        var vPath = "coarse"
        var vResid = 99f

        val bars = findVerticalBarAnchors(gray, sW, sH, x0c, y0c, sizec, size, x0, size)
        if (bars != null) {
            val barsY0 = bars.first.toFloat()
            val altRes = computeRingAlternationDetailed(gray, sW, sH, x0, barsY0, size)
            val devOk = bars.third <= max(4.0f, size * RELATIVE_SQUARE_GATE_RATIO)
            
            // Hauptpfad mit mehreren Belegen: geometrisch geschlossener Außenrahmen (devOk) und in allen Reihen bestandene Ringabtastung der 64 Felder (Merkmal eines echten Bretts)
            if (devOk && altRes.allRowsPass) {
                y0 = barsY0
                vPath = "bars"
                vResid = bars.third
            } else {
                val cross = fitHorizontalLines(gray, sW, sH, x0c, bars.first, size.roundToInt())
                val sEst = size / 8.0f
                if (cross.isOk || (devOk && cross.residual <= sEst * 0.10f && altRes.totalScore >= 0.75f)) {
                    y0 = barsY0
                    vPath = "bars"
                    vResid = min(bars.third, cross.residual)
                }
            }
        }

        // 3. Senkrechter Rückfallpfad: streng begrenzte Phasensuche in der Nachbarschaft (höchstens ein halbes Feld, damit kein Feld übersprungen wird) samt Gatter auf durchgehende Abwechslung
        if (vPath == "coarse") {
            val sEst = sizeV / 8.0f
            var bestResid = 99f
            var bestY0 = y0c.toFloat()

            for (k in getDegeneratePhaseSteps()) {
                val yTry = (y0c + k * sEst).roundToInt()
                val hfit = fitHorizontalLines(gray, sW, sH, x0c, yTry, sizeV.roundToInt())
                if (hfit.isOk && abs(hfit.step - sEst) <= WAVE_GATE_V * sEst) {
                    val edges = outerEdgeScore(gray, sW, sH, x0, sizeV, hfit.p0)
                    val te = edges[0]
                    val be = edges[1]
                    val yt = edges[2]
                    if (te >= BAR_GRAD_MIN && be >= BAR_GRAD_MIN) {
                        val candAlt = computeRingAlternationDetailed(gray, sW, sH, x0, yt, sizeV)
                        // Alle 8 Reihen des Schachbrettmusters müssen bestehen (allRowsPass), damit kein einfarbiger Bereich der Oberfläche getroffen wird
                        if (candAlt.allRowsPass || (candAlt.totalScore >= 0.90f && candAlt.minRowScore >= 0.75f)) {
                            // Abstandsstrafe: bevorzugt wird die Feinjustierung nahe der Mitte des Grobrahmens, das verhindert entfernte Scheinresonanzen
                            val effectiveResid = hfit.residual + abs(k) * (sEst * 0.02f)
                            if (effectiveResid < bestResid) {
                                bestResid = effectiveResid
                                bestY0 = yt
                            }
                        }
                    }
                }
            }

            val maxAllowedResid = sEst * RELATIVE_RESIDUAL_GATE_RATIO
            if (bestResid <= maxAllowedResid) {
                y0 = bestY0
                vPath = "gridlines"
                vResid = bestResid
            }
        }

        // 4. Zweiter waagerechter Durchgang nach der senkrechten Konvergenz
        if (vPath != "coarse") {
            val (hOk2, hParams2) = refineHorizontal(gray, sW, sH, x0.roundToInt(), y0.roundToInt(), size.roundToInt())
            if (hOk2) {
                x0 = hParams2[0]
                size = hParams2[1]
            }
        }

        // 5. Gesamtbewertung der Confidence
        val confidence = when {
            hOk && vPath == "bars" -> "high"
            hOk && vPath == "gridlines" -> "medium"
            hOk || vPath != "coarse" -> "medium"
            else -> "low"
        }
        val residual = max(if (hOk) hResid else 0f, if (vPath != "coarse") vResid else 99f)

        return CalibratedBox(x0, y0, size, confidence, residual)
    }

    data class AlternationResult(
        val totalScore: Float,
        val minRowScore: Float,
        val allRowsPass: Boolean
    )

    // 8 sample directions at 0, 45, 90, 135, 180, 225, 270, 315 degrees
    private val RING_ANGLES = floatArrayOf(
        0f,
        (Math.PI / 4.0).toFloat(),
        (Math.PI / 2.0).toFloat(),
        (3.0 * Math.PI / 4.0).toFloat(),
        Math.PI.toFloat(),
        (5.0 * Math.PI / 4.0).toFloat(),
        (3.0 * Math.PI / 2.0).toFloat(),
        (7.0 * Math.PI / 4.0).toFloat()
    )

    /**
     * Reine Funktion: berechnet über eine Ringabtastung die Abwechslung heller und dunkler Felder eines 8x8-Bretts sowie die Vollständigkeit jeder Reihe (Schwelle aus dem 2-Means-Mittelpunkt)
     * Je Feld werden 8 Punkte auf einem Ring mit dem Radius 0.42 * cell_size um die Feldmitte abgetastet (die Figur in der Mitte bleibt ausgespart),
     * die Schwelle ergibt sich als Mittelpunkt zweier Cluster (funktioniert in hellem wie dunklem Modus), und es wird geprüft, ob alle 8 Reihen abwechselnde Felder zeigen.
     * - Echtes Brett (hell oder dunkel): totalScore >= 0.98, minRowScore >= 0.875, allRowsPass = true
     * - Um 1 bis 2 Felder verschobener Scheinrahmen: minRowScore <= 0.625 (Reihen ausserhalb des Bretts fallen sofort durch), allRowsPass = false
     */
    fun computeRingAlternationDetailed(
        gray: FloatArray,
        width: Int,
        height: Int,
        x0: Float,
        y0: Float,
        size: Float
    ): AlternationResult {
        val cs = size / 8.0f
        if (cs < 2f || width <= 0 || height <= 0) {
            return AlternationResult(0f, 0f, false)
        }
        val rad = cs * 0.42f
        val cellColors = FloatArray(64)
        val ringSamples = FloatArray(8)

        for (r in 0 until 8) {
            val cy = y0 + (r + 0.5f) * cs
            for (c in 0 until 8) {
                val cx = x0 + (c + 0.5f) * cs
                for (a in 0 until 8) {
                    val theta = RING_ANGLES[a]
                    val px = (cx + rad * kotlin.math.cos(theta)).roundToInt().coerceIn(0, width - 1)
                    val py = (cy + rad * kotlin.math.sin(theta)).roundToInt().coerceIn(0, height - 1)
                    ringSamples[a] = gray[py * width + px]
                }
                ringSamples.sort()
                // Median der 8 Punkte (Mittel aus drittem und viertem Wert)
                val med = (ringSamples[3] + ringSamples[4]) * 0.5f
                cellColors[r * 8 + c] = med
            }
        }

        // Schwelle als Mittelpunkt zweier Cluster (Mitte der 32 dunklen und der 32 hellen Felder), unabhängig von heller oder dunkler Darstellung
        val sortedCols = cellColors.clone().apply { sort() }
        var sumDark = 0f
        var sumLight = 0f
        for (i in 0 until 32) sumDark += sortedCols[i]
        for (i in 32 until 64) sumLight += sortedCols[i]
        val cDark = sumDark / 32f
        val cLight = sumLight / 32f
        val thr = (cDark + cLight) * 0.5f

        // Übereinstimmung mit dem Schachbrettmuster berechnen
        var p0 = 0
        var p1 = 0
        val isLight = BooleanArray(64)
        for (i in 0 until 64) {
            val r = i / 8
            val c = i % 8
            val light = cellColors[i] > thr
            isLight[i] = light
            val even = (r + c) % 2 == 0
            if (light == even) p0++ else p1++
        }
        val targetEven = p0 >= p1

        var minRowScore = 1.0f
        for (r in 0 until 8) {
            var rowMatches = 0
            for (c in 0 until 8) {
                val light = isLight[r * 8 + c]
                val expected = if (targetEven) (r + c) % 2 == 0 else (r + c) % 2 != 0
                if (light == expected) rowMatches++
            }
            val rowScore = rowMatches / 8.0f
            if (rowScore < minRowScore) minRowScore = rowScore
        }

        val totalScore = max(p0, p1) / 64.0f
        val allRowsPass = minRowScore >= 0.75f && totalScore >= 0.85f
        return AlternationResult(totalScore, minRowScore, allRowsPass)
    }

    fun computeRingAlternationScore(
        gray: FloatArray,
        width: Int,
        height: Int,
        x0: Float,
        y0: Float,
        size: Float
    ): Float {
        return computeRingAlternationDetailed(gray, width, height, x0, y0, size).totalScore
    }

    /**
     * Liste der Phasenschrittfaktoren des Rückfallpfades (streng auf [-0.5, 0.5] begrenzt)
     */
    fun getDegeneratePhaseSteps(): FloatArray {
        return floatArrayOf(0f, -0.15f, 0.15f, -0.30f, 0.30f, -0.45f, 0.45f)
    }
}