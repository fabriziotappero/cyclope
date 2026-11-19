package com.fab.cyclope

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.text.SpannableString
import android.text.Spanned
import android.text.style.RelativeSizeSpan
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import java.util.Locale

class UiGpxProgressBar(
    private val context: Context
) {
    private val barHeightPx = dpToPx(26)

    private val fillView = FrameLayout(context).apply {
        setBackgroundColor(Color.rgb(180, 220, 180)) // pale green
    }

    private val doneText = TextView(context).apply {
        setTextColor(Color.BLACK)
        textSize = 19f
        gravity = Gravity.CENTER
        typeface = Typeface.DEFAULT_BOLD
        isSingleLine = true
        maxLines = 1
    }

    private val toaText = TextView(context).apply {
        setTextColor(Color.WHITE)
        textSize = 19f
        typeface = Typeface.DEFAULT_BOLD
        isSingleLine = true
        visibility = View.GONE
    }

    private val barBackground = FrameLayout(context).apply {
        setBackgroundColor(Color.DKGRAY)
        addView(
            fillView,
            FrameLayout.LayoutParams(0, FrameLayout.LayoutParams.MATCH_PARENT, Gravity.START)
        )
        addView(
            toaText,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER_VERTICAL or Gravity.START)
        )
    }

    val view: FrameLayout = FrameLayout(context).apply {
        setPadding(0, 0, 0, 0)
        addView(
            barBackground,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                barHeightPx
            )
        )
    }

    private var lastDoneKm: Double? = null
    private var lastTotalKm: Double? = null
    private var lastTimeToDestinationMinutes: Long? = null

    init {
        fillView.addView(
            doneText,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
            )
        )
        doneText.visibility = View.GONE
    }

    fun setProgress(doneKm: Double?, totalKm: Double?, timeToDestinationMinutes: Long?) {
        lastDoneKm = doneKm
        lastTotalKm = totalKm
        lastTimeToDestinationMinutes = timeToDestinationMinutes
        render()
    }

    private fun render() {
        val doneKm = lastDoneKm
        val totalKm = lastTotalKm

        val barWidth = barBackground.width
        if (barWidth <= 0) {
            view.post { render() }
            return
        }

        val progress = if (doneKm != null && totalKm != null && totalKm > 0.0) {
            (doneKm / totalKm).coerceIn(0.0, 1.0)
        } else {
            0.0
        }

        val fillWidthPx = (barWidth * progress).toInt().coerceAtLeast(0)
        val fillLayoutParams = fillView.layoutParams as FrameLayout.LayoutParams
        if (fillLayoutParams.width != fillWidthPx) {
            fillLayoutParams.width = fillWidthPx
            fillView.layoutParams = fillLayoutParams
        }

        renderToa(fillWidthPx, barWidth)

        if (doneKm == null) {
            doneText.visibility = View.GONE
            return
        }

        val doneValueText = String.format(Locale.US, "%.1f", doneKm.coerceAtLeast(0.0))
        val doneFullText = "${doneValueText}km"
        val doneSpannable = SpannableString(doneFullText).apply {
            setSpan(
                RelativeSizeSpan(0.8f),
                doneValueText.length,
                doneFullText.length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
        doneText.text = doneSpannable

        val requiredTextWidthPx = doneText.paint.measureText(doneFullText).toInt() + dpToPx(0)
        doneText.visibility = if (fillWidthPx >= requiredTextWidthPx) View.VISIBLE else View.GONE
    }

    private fun renderToa(fillWidthPx: Int, barWidth: Int) {
        val minutes = lastTimeToDestinationMinutes
        if (minutes == null) {
            toaText.visibility = View.GONE
            return
        }
        toaText.text = shrinkUnitLetters(formatDurationText(minutes))
        toaText.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
        val grayWidthPx = barWidth - fillWidthPx
        if (toaText.measuredWidth > grayWidthPx) {
            toaText.visibility = View.GONE
            return
        }
        val grayCenterPx = fillWidthPx + grayWidthPx / 2
        val layoutParams = toaText.layoutParams as FrameLayout.LayoutParams
        layoutParams.leftMargin = grayCenterPx - toaText.measuredWidth / 2
        toaText.layoutParams = layoutParams
        toaText.visibility = View.VISIBLE
    }

    private fun shrinkUnitLetters(text: String): SpannableString {
        return SpannableString(text).apply {
            Regex("[a-z]+").findAll(text).forEach { match ->
                setSpan(
                    RelativeSizeSpan(0.8f),
                    match.range.first,
                    match.range.last + 1,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
        }
    }

    private fun dpToPx(dp: Int): Int = (dp * context.resources.displayMetrics.density).toInt()
}
