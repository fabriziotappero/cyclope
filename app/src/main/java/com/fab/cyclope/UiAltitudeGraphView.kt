package com.fab.cyclope

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import java.util.Locale

class UiAltitudeGraphView(context: Context) : View(context) {
    // Invoked on a double tap; used to fit the map to the whole route.
    var onDoubleTap: (() -> Unit)? = null

    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDoubleTap(e: MotionEvent): Boolean {
            onDoubleTap?.invoke()
            return true
        }
    })

    override fun onTouchEvent(event: MotionEvent): Boolean {
        gestureDetector.onTouchEvent(event)
        return true
    }
    // borders all around plot
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.DKGRAY
        style = Paint.Style.STROKE
        strokeWidth = 5f
    }

    // current altitude horizontal and vertical lines
    // keep same visual level as grid lines
    private val markerGuideLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.LTGRAY
        style = Paint.Style.STROKE
        strokeWidth = 5f
    }

    // plot altitude line
    private val profilePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 5f
    }

    // plot profile fill per inclination section
    private val sectionFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    // legend text
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 41f
    }

    // current inclination text next to the marker
    private val inclinationLabelPaint = Paint(labelPaint).apply {
        textSize = 41f * 1.4f
    }
    private val inclinationBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.LTGRAY
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }

    // legend text background
    private val labelBackgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(180, 0, 0, 0) // black half transparent
        style = Paint.Style.FILL
    }


    // current altitude circular marker fill
    private val markerFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }

    // plot altitude circular marker border
    private val markerBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }

    // plot grid lines
    private val gridLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.DKGRAY
        style = Paint.Style.STROKE
        strokeWidth = 4f
    }

    // Adaptive grid steps:
    // - Y grid uses one of these altitude steps (meters): 50/100/200/400/800
    // - X grid uses one of these distance steps (km): 5/10/20/40/80/160
    // For each axis we pick the smallest step that keeps the number of grid lines
    // under the configured cap (maxHorizontalGridLines / maxVerticalGridLines).
    private val gridStepCandidatesMeters = intArrayOf(100, 200, 400, 800)
    private val xGridStepCandidatesKm = intArrayOf(10, 20, 40, 80, 160)
    private val maxHorizontalGridLines = 6
    private val maxVerticalGridLines = 13

    // These lists come from the loaded GPX track.
    // In this app flow they are effectively updated when:
    // - a new GPX is loaded, or
    // - GPX direction is reversed.
    // Any such change means the static plot geometry/text may change.
    private var altitudes: List<Double?> = emptyList()
    private var cumulativeDistancesKm: List<Double> = emptyList()
    private var inclinationSections: List<InclinationSection> = emptyList()

    // Index of the GPX sample currently closest to user position (null = do not draw marker).
    private var highlightedIndex: Int? = null

    // Static plot cache (grid/profile/labels).
    // Normal GPS updates do NOT rebuild this bitmap: they only move the white marker.
    private var staticPlotBitmap: Bitmap? = null
    private var staticPlotDirty = true
    private var markerCenters: List<PointF?> = emptyList()
    private val markerRadius = 18f
    // Space above the highest and below the lowest altitude, so the marker ball is not cut off at the plot edges.
    private val profileVerticalMargin = markerRadius + markerBorderPaint.strokeWidth
    private val plotVerticalPadding = 0f

    fun setAltitudes(values: List<Double?>) {
        altitudes = values
        highlightedIndex = highlightedIndex?.takeIf { it in altitudes.indices && altitudes[it] != null }
        // GPX profile content changed -> rebuild static layer once.
        markStaticPlotDirty()
        invalidate()
    }

    fun setHighlightedIndex(index: Int?) {
        val newIndex = index?.takeIf { it in altitudes.indices && altitudes[it] != null }
        if (newIndex == highlightedIndex) return

        highlightedIndex = newIndex
        // Marker, guide ticks and grade label are drawn on top of the cached static plot, so only redraw.
        invalidate()
    }

    fun setCumulativeDistancesKm(values: List<Double>) {
        cumulativeDistancesKm = values
        // GPX distance axis changed -> rebuild static layer once.
        markStaticPlotDirty()
        invalidate()
    }

    fun setInclinationSections(values: List<InclinationSection>) {
        inclinationSections = values
        markStaticPlotDirty()
        invalidate()
    }

    override fun onDetachedFromWindow() {
        staticPlotBitmap?.recycle()
        staticPlotBitmap = null
        super.onDetachedFromWindow()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w != oldw || h != oldh) {
            // Coordinates depend on view size; rebuild cache at new dimensions.
            markStaticPlotDirty()
        }
    }

    private fun markStaticPlotDirty() {
        // Drop cached static bitmap so next onDraw rebuilds it.
        // This is expected mostly on GPX load/reverse (not on each GPS fix).
        staticPlotDirty = true
        markerCenters = emptyList()
        staticPlotBitmap?.recycle()
        staticPlotBitmap = null
    }

    private fun countGridLines(rangeValue: Double, stepValue: Int): Int {
        if (rangeValue <= 0.0) return 0

        var count = 0
        var level = stepValue.toDouble()
        while (level < rangeValue) {
            count++
            level += stepValue
        }
        return count
    }

    private fun drawHorizontalGridLines(
        canvas: Canvas,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        altitudeGainMeters: Double,
        rangeMeters: Double
    ) {
        val selectedGridStepMeters = gridStepCandidatesMeters.firstOrNull {
            countGridLines(altitudeGainMeters, it) <= maxHorizontalGridLines
        } ?: gridStepCandidatesMeters.last()

        val gridLineCount = minOf(
            maxHorizontalGridLines,
            countGridLines(altitudeGainMeters, selectedGridStepMeters)
        )

        for (lineIndex in 1..gridLineCount) {
            val gainAtLine = selectedGridStepMeters * lineIndex.toDouble()
            if (gainAtLine >= altitudeGainMeters) break

            val ratio = (gainAtLine / rangeMeters).coerceIn(0.0, 1.0)
            val y = (bottom - ratio * (bottom - top)).toFloat()
            canvas.drawLine(left, y, right, y, gridLinePaint)
        }
    }

    private fun drawVerticalGridLines(
        canvas: Canvas,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        totalDistanceKm: Double
    ) {
        if (totalDistanceKm <= 0.0) return

        val selectedGridStepKm = xGridStepCandidatesKm.firstOrNull {
            countGridLines(totalDistanceKm, it) <= maxVerticalGridLines
        } ?: xGridStepCandidatesKm.last()

        val gridLineCount = minOf(
            maxVerticalGridLines,
            countGridLines(totalDistanceKm, selectedGridStepKm)
        )

        for (lineIndex in 1..gridLineCount) {
            val distanceAtLineKm = selectedGridStepKm * lineIndex.toDouble()
            if (distanceAtLineKm >= totalDistanceKm) break

            val ratio = (distanceAtLineKm / totalDistanceKm).coerceIn(0.0, 1.0)
            val x = (left + ratio * (right - left)).toFloat()
            canvas.drawLine(x, top, x, bottom, gridLinePaint)
        }
    }

    private fun ensureStaticPlotBitmap() {
        val w = width
        val h = height
        if (w <= 0 || h <= 0) return

        val bitmap = staticPlotBitmap
        if (!staticPlotDirty && bitmap != null && bitmap.width == w && bitmap.height == h) {
            return
        }

        bitmap?.recycle()
        val newBitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val staticCanvas = Canvas(newBitmap)
        drawStaticPlot(staticCanvas, w.toFloat(), h.toFloat())
        staticPlotBitmap = newBitmap
        staticPlotDirty = false
    }

    private fun drawLabelWithBackground(canvas: Canvas, text: String, left: Float, top: Float, textPaint: Paint = labelPaint, cornerRadius: Float = 0f, borderPaint: Paint? = null) {
        val horizontalPadding = 10f
        val verticalPadding = 6f
        val textWidth = textPaint.measureText(text)
        val textHeight = textPaint.descent() - textPaint.ascent()

        val rectLeft = left
        val rectTop = top
        val rectRight = left + textWidth + horizontalPadding * 2f
        val rectBottom = top + textHeight + verticalPadding * 2f
        canvas.drawRoundRect(rectLeft, rectTop, rectRight, rectBottom, cornerRadius, cornerRadius, labelBackgroundPaint)
        borderPaint?.let { canvas.drawRoundRect(rectLeft, rectTop, rectRight, rectBottom, cornerRadius, cornerRadius, it) }

        val textX = rectLeft + horizontalPadding
        val textY = rectTop + verticalPadding - textPaint.ascent()
        canvas.drawText(text, textX, textY, textPaint)
    }

    private fun drawStaticPlot(canvas: Canvas, w: Float, h: Float) {
        val left = 0f
        val top = plotVerticalPadding
        val right = w
        val bottom = h - plotVerticalPadding

        canvas.drawRect(left, top, right, bottom, borderPaint)

        val markerPoints = MutableList<PointF?>(altitudes.size) { null }
        val validAltitudes = altitudes.mapNotNull { it }
        if (validAltitudes.size < 2 || altitudes.size < 2) {
            markerCenters = markerPoints
            canvas.drawText("No altitude data", left + 10f, bottom - 10f, labelPaint)
            return
        }

        val minAlt = validAltitudes.minOrNull() ?: run {
            markerCenters = markerPoints
            return
        }
        val maxAlt = validAltitudes.maxOrNull() ?: run {
            markerCenters = markerPoints
            return
        }
        val altitudeGain = maxAlt - minAlt
        val range = altitudeGain.takeIf { it > 0.0 } ?: 1.0
        // Altitudes are drawn between these two lines; the frame and the section fills still use top/bottom.
        val profileTop = top + profileVerticalMargin
        val profileBottom = bottom - profileVerticalMargin

        val hasDistanceAxis = cumulativeDistancesKm.size == altitudes.size && cumulativeDistancesKm.isNotEmpty()
        val totalDistanceKm = if (hasDistanceAxis) cumulativeDistancesKm.last().coerceAtLeast(0.0) else 0.0
        val fallbackStepX = (right - left) / (altitudes.size - 1).toFloat()

        val xForIndex: (Int) -> Float = { index ->
            if (hasDistanceAxis && totalDistanceKm > 0.0) {
                val ratio = (cumulativeDistancesKm[index] / totalDistanceKm).coerceIn(0.0, 1.0)
                (left + ratio * (right - left)).toFloat()
            } else {
                left + index * fallbackStepX
            }
        }

        drawVerticalGridLines(
            canvas = canvas,
            left = left,
            top = top,
            right = right,
            bottom = bottom,
            totalDistanceKm = totalDistanceKm
        )

        drawHorizontalGridLines(
            canvas = canvas,
            left = left,
            top = profileTop,
            right = right,
            bottom = profileBottom,
            altitudeGainMeters = altitudeGain,
            rangeMeters = range
        )

        val path = Path()
        var started = false
        altitudes.forEachIndexed { index, altitude ->
            if (altitude == null) {
                started = false
                return@forEachIndexed
            }

            val x = xForIndex(index)
            val ratio = ((altitude - minAlt) / range).coerceIn(0.0, 1.0)
            val y = (profileBottom - ratio * (profileBottom - profileTop)).toFloat()
            markerPoints[index] = PointF(x, y)

            if (!started) {
                path.moveTo(x, y)
                started = true
            } else {
                path.lineTo(x, y)
            }
        }

        for (section in inclinationSections) {
            val sectionPoints = (section.startIndex..section.endIndex).mapNotNull { markerPoints[it] }
            if (sectionPoints.size < 2) continue
            val fillPath = Path().apply {
                moveTo(sectionPoints.first().x, bottom)
                sectionPoints.forEach { lineTo(it.x, it.y) }
                lineTo(sectionPoints.last().x, bottom)
                close()
            }
            sectionFillPaint.color = section.color
            canvas.drawPath(fillPath, sectionFillPaint)
        }

        canvas.drawPath(path, profilePaint)
        markerCenters = markerPoints

        // Show altitude gain relative to the lowest point on the loaded profile.
        val altitudeGainText = String.format(Locale.US, "+%.0fm", altitudeGain)
        drawLabelWithBackground(canvas, altitudeGainText, left + 28f, top + 2f)
    }

    private fun drawMarker(canvas: Canvas) {
        val index = highlightedIndex ?: return
        val point = markerCenters.getOrNull(index) ?: return

        // Guide ticks on the Y axis (left border) and X axis (bottom border) at the marker position.
        val yAxisX = borderPaint.strokeWidth / 2f
        val xAxisY = height - plotVerticalPadding
        val tickLength = 24f
        canvas.drawLine(yAxisX, point.y, yAxisX + tickLength, point.y, markerGuideLinePaint)
        canvas.drawLine(point.x, xAxisY - tickLength, point.x, xAxisY, markerGuideLinePaint)

        canvas.drawCircle(point.x, point.y, markerRadius, markerFillPaint)
        canvas.drawCircle(point.x, point.y, markerRadius, markerBorderPaint)
        drawInclinationLabel(canvas, index, point)
    }

    private fun drawInclinationLabel(canvas: Canvas, index: Int, point: PointF) {
        val section = inclinationSections.firstOrNull { index in it.startIndex..it.endIndex } ?: return
        val text = "${Math.round(section.percent)}%"
        val labelWidth = inclinationLabelPaint.measureText(text) + 20f
        val labelHeight = inclinationLabelPaint.descent() - inclinationLabelPaint.ascent() + 12f
        val gap = markerRadius + 8f
        val leftSide = point.x - gap - labelWidth
        val left = if (leftSide >= 0f) leftSide else point.x + gap
        val top = (point.y - labelHeight * 1.1f).coerceIn(0f, height - labelHeight)
        drawLabelWithBackground(canvas, text, left, top, inclinationLabelPaint, cornerRadius = 14f, borderPaint = inclinationBorderPaint)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width <= 0 || height <= 0) return

        ensureStaticPlotBitmap()
        staticPlotBitmap?.let { canvas.drawBitmap(it, 0f, 0f, null) }
            ?: drawStaticPlot(canvas, width.toFloat(), height.toFloat())

        drawMarker(canvas)
    }
}
