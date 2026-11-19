package com.fab.cyclope

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.location.Location
import android.text.SpannableString
import android.text.Spanned
import android.text.style.RelativeSizeSpan
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import org.osmdroid.views.MapView
import java.util.Locale
import kotlin.math.abs

class UiMapScaleLegend(
    private val context: Context,
    private val config: Config,
    private val mapViewProvider: () -> MapView?,
    private val latestLatitudeProvider: () -> Double?,
    private val currentMapStyleMaxZoomProvider: () -> Int,
    overlayBackgroundColor: Int,
    private val onTapped: () -> Unit
) {
    data class Config(
        val minLinePx: Double,
        val maxLinePx: Double,
        val targetLinePx: Double
    )

    private val distancesMeters = doubleArrayOf(10.0, 20.0, 50.0, 100.0, 200.0, 1000.0, 10_000.0, 100_000.0, 1000_000.0)
    private val clickDistancesMeters = doubleArrayOf(100.0, 10_000.0)
    private var nextClickDistanceIndex = 0
    private val minLinePx = config.minLinePx
    private val maxLinePx = config.maxLinePx
    private val targetLinePx = config.targetLinePx

    private val lineView = View(context).apply {
        setBackgroundColor(Color.WHITE)
    }

    private val textView = TextView(context).apply {
        setTextColor(Color.WHITE)
        textSize = 16f
        gravity = Gravity.CENTER_HORIZONTAL
    }

    val view: LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.END
        background = RippleDrawable(
            ColorStateList.valueOf(Color.argb(60, 255, 255, 255)), // white tap feedback
            GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dpToPx(6).toFloat()
                setColor(overlayBackgroundColor)
                setStroke(dpToPx(1), Color.argb(90, 255, 255, 255)) // thin outline ring for contrast on the map
            },
            null
        )
        setPadding(dpToPx(6), dpToPx(6), dpToPx(6), dpToPx(6))
        isClickable = true
        isFocusable = true
        setOnClickListener {
            onTapped()
            zoomMapToDistance(clickDistancesMeters[nextClickDistanceIndex])
            nextClickDistanceIndex = (nextClickDistanceIndex + 1) % clickDistancesMeters.size
        }

        addView(
            lineView,
            LinearLayout.LayoutParams(dpToPx(80), dpToPx(2)).apply {
                gravity = Gravity.END
            }
        )
        addView(
            textView,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.END
                topMargin = dpToPx(4)
            }
        )

        visibility = View.GONE
    }

    fun update() {
        val map = mapViewProvider() ?: return

        val metersPerPixel = metersPerPixelAt(map)
        if (!metersPerPixel.isFinite() || metersPerPixel <= 0.0) return

        var selectedDistance = Double.NaN
        var selectedLineWidth = Double.NaN
        var bestScore = Double.MAX_VALUE

        for (distanceMeters in distancesMeters) {
            val lineWidthPx = distanceMeters / metersPerPixel
            if (!lineWidthPx.isFinite() || lineWidthPx > maxLinePx) continue

            val score = if (lineWidthPx >= minLinePx) {
                abs(lineWidthPx - targetLinePx)
            } else {
                // If no value reaches minLinePx, keep the largest one below min.
                (minLinePx - lineWidthPx) + 1000.0
            }

            if (score < bestScore) {
                bestScore = score
                selectedDistance = distanceMeters
                selectedLineWidth = lineWidthPx
            }
        }

        if (!selectedDistance.isFinite() || !selectedLineWidth.isFinite()) {
            view.visibility = View.GONE
            return
        }

        val lineWidthInt = selectedLineWidth.toInt().coerceAtLeast(1)
        val lineParams = lineView.layoutParams as LinearLayout.LayoutParams
        if (lineParams.width != lineWidthInt) {
            lineParams.width = lineWidthInt
            lineView.layoutParams = lineParams
        }

        textView.text = formatDistance(selectedDistance)
        view.visibility = View.VISIBLE
    }

    private fun zoomMapToDistance(distanceMeters: Double) {
        val map = mapViewProvider() ?: return
        if (distanceMeters <= 0.0) return

        val currentMetersPerPixel = metersPerPixelAt(map)
        val desiredMetersPerPixel = distanceMeters / targetLinePx
        if (!currentMetersPerPixel.isFinite() || currentMetersPerPixel <= 0.0) return
        if (!desiredMetersPerPixel.isFinite() || desiredMetersPerPixel <= 0.0) return

        val zoomDelta = Math.log(currentMetersPerPixel / desiredMetersPerPixel) / Math.log(2.0)
        val targetZoom = (map.zoomLevelDouble + zoomDelta)
            .coerceIn(0.0, currentMapStyleMaxZoomProvider().toDouble())

        map.controller.setZoom(targetZoom)
        update()
    }

    private fun metersPerPixelAt(map: MapView): Double {
        val width = map.width
        val height = map.height
        if (width <= 1 || height <= 1) return Double.NaN

        val projection = map.projection
        val samplePx = width.coerceAtMost(300).coerceAtLeast(80)
        val centerX = width / 2
        val centerY = height / 2

        val p1 = projection.fromPixels(centerX, centerY)
        val p2 = projection.fromPixels(centerX + samplePx, centerY)
        if (p1 == null || p2 == null) return Double.NaN

        val result = FloatArray(1)
        Location.distanceBetween(
            p1.latitude,
            p1.longitude,
            p2.latitude,
            p2.longitude,
            result
        )

        val distanceMeters = result[0].toDouble()
        if (!distanceMeters.isFinite() || distanceMeters <= 0.0) return Double.NaN
        return distanceMeters / samplePx.toDouble()
    }

    private fun formatDistance(distanceMeters: Double): CharSequence {
        val (valueText, unitText) = if (distanceMeters >= 1000.0) {
            String.format(Locale.US, "%.0f", distanceMeters / 1000.0) to "km"
        } else {
            String.format(Locale.US, "%.0f", distanceMeters) to "m"
        }

        val fullText = "$valueText$unitText"
        return SpannableString(fullText).apply {
            setSpan(
                RelativeSizeSpan(0.8f),
                valueText.length,
                fullText.length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
    }

    private fun dpToPx(dp: Int): Int = (dp * context.resources.displayMetrics.density).toInt()
}
