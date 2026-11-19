package com.fab.cyclope

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.LayerDrawable
import android.location.Location
import android.text.SpannableString
import android.text.Spanned
import android.text.style.RelativeSizeSpan
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import java.util.ArrayDeque
import java.util.Locale

class UiLargeNumbersPanel(
    private val context: Context,
    private val config: Config,
    private val distanceKmProvider: () -> Double?
) {
    data class Config(
        val controlSurfaceColor: Int,
        val bigNumbersTextSizeSp: Float,
        val unitSizeRatio: Float,
        val columnGapDp: Int,
        val smoothingWindowSize: Int
    )

    private val speedText = TextView(context).apply {
        setTextColor(Color.WHITE)
        textSize = config.bigNumbersTextSizeSp
        gravity = Gravity.CENTER_HORIZONTAL or Gravity.BOTTOM
        text = formatBigMetricText("--.-", "km/h")
    }

    private val distanceToEndText = TextView(context).apply {
        setTextColor(Color.WHITE)
        textSize = config.bigNumbersTextSizeSp
        gravity = Gravity.CENTER_HORIZONTAL or Gravity.BOTTOM
        text = formatBigMetricText("--.-", "km")
    }

    private val speedLabelText = TextView(context).apply {
        setTextColor(Color.LTGRAY)
        textSize = 30f
        gravity = Gravity.CENTER_HORIZONTAL
        typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
        text = "SPEED"
    }

    private val recentSpeedKmh = ArrayDeque<Double>()
    private val recentElevationMeters = ArrayDeque<Double>()
    private var isShowingElevation = false

    val view: LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL

        val distanceLabelText = TextView(context).apply {
            setTextColor(Color.LTGRAY)
            textSize = 30f
            gravity = Gravity.CENTER_HORIZONTAL
            typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
            text = "TO FINISH"
        }

        val speedColumn = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = LayerDrawable(arrayOf<Drawable>(
                ColorDrawable(config.controlSurfaceColor),
                context.getDrawable(R.drawable.ic_corner_triangle)!!.apply { setTint(Color.LTGRAY) }
            )).apply {
                setLayerInset(1, dpToPx(4), dpToPx(4), 0, 0) // small hint that the box is clickable
                setLayerGravity(1, Gravity.TOP or Gravity.START) // keep the triangle at its own size
            }
            setOnClickListener { toggleShownValue() }

            addView(
                speedLabelText,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            )
            addView(
                speedText,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    0,
                    1f
                )
            )
        }

        val distanceColumn = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(config.controlSurfaceColor)

            addView(
                distanceLabelText,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            )
            addView(
                distanceToEndText,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    0,
                    1f
                )
            )
        }

        addView(
            speedColumn,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f).apply {
                marginEnd = dpToPx(config.columnGapDp)
            }
        )
        addView(
            distanceColumn,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f).apply {
                marginStart = dpToPx(config.columnGapDp)
            }
        )
    }

    fun onLocation(location: Location) {
        if (location.hasSpeed()) {
            val speedKmh = (location.speed * 3.6f).coerceAtLeast(0f).toDouble()
            addSample(recentSpeedKmh, speedKmh)
        }
        if (location.hasAltitude()) {
            addSample(recentElevationMeters, location.altitude)
        }
    }

    fun refresh() {
        render(distanceKmProvider())
    }

    private fun toggleShownValue() {
        isShowingElevation = !isShowingElevation
        speedLabelText.text = if (isShowingElevation) "ELEVATION" else "SPEED"
        refresh()
    }

    private fun render(distanceKm: Double?) {
        speedText.text = if (isShowingElevation) {
            val elevationMeters = average(recentElevationMeters)
            if (elevationMeters == null) {
                formatBigMetricText("----", "m")
            } else {
                formatBigMetricText(String.format(Locale.US, "%.0f", elevationMeters.coerceIn(0.0, 9999.9)), "m")
            }
        } else {
            val speedKmh = average(recentSpeedKmh)
            if (speedKmh == null) {
                formatBigMetricText("--.-", "km/h")
            } else {
                formatBigMetricText(String.format(Locale.US, "%.1f", speedKmh.coerceIn(0.0, 999.9)), "km/h")
            }
        }

        distanceToEndText.text = if (distanceKm == null) {
            formatBigMetricText("--.-", "km")
        } else {
            formatBigMetricText(String.format(Locale.US, "%.1f", distanceKm), "km")
        }
    }

    private fun addSample(samples: ArrayDeque<Double>, value: Double) {
        samples.addLast(value)
        while (samples.size > config.smoothingWindowSize) {
            samples.removeFirst()
        }
    }

    private fun average(samples: ArrayDeque<Double>): Double? {
        if (samples.isEmpty()) return null
        return samples.sum() / samples.size
    }

    private fun dpToPx(dp: Int): Int = (dp * context.resources.displayMetrics.density).toInt()

    private fun formatBigMetricText(valueText: String, unitText: String): CharSequence {
        val fullText = "$valueText$unitText"
        val spannable = SpannableString(fullText)
        val unitStart = valueText.length
        spannable.setSpan(
            RelativeSizeSpan(config.unitSizeRatio),
            unitStart,
            fullText.length,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        return spannable
    }
}
