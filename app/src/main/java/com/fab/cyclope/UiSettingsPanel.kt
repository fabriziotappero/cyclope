package com.fab.cyclope

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.BatteryManager
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import androidx.core.content.ContextCompat
import java.util.ArrayDeque
import java.util.Locale
import kotlin.math.abs

class UiSettingsPanel(
    private val context: Context,
    private val config: Config,
    isReverseGpxDirection: Boolean,
    isPimpMyGpxTracks: Boolean,
    mapStyleLabels: List<String>,
    appVersionBuildText: String,
    private val onMapStyleSelected: (Int) -> Unit,
    private val onReverseGpxDirectionChanged: (Boolean) -> Unit,
    private val onPimpMyGpxTracksChanged: (Boolean) -> Unit
) {
    data class Config(
        val animationDurationMs: Long,
        val minVisibleHandleHeightDp: Int,
        val backgroundColor: Int,
        val controlSurfaceColor: Int,
        val showDebugStatusSection: Boolean,
        val settingsPanelVerticalGapDp: Int,
        val settingsPanelHandleVerticalPaddingDp: Int,
        val batteryCurrentSmoothingWindowSamples: Int,
        val batteryCurrentSampleIntervalMs: Long
    )

    private val recentBatteryCurrentUa = ArrayDeque<Double>()
    private var lastBatteryCurrentSampleTimeMs = 0L
    private var lastBatteryCurrentSampleUa: Double? = null

    private val statusBatteryRemaining: TextView = TextView(context).apply {
        setTextColor(Color.LTGRAY)
        textSize = 18f
        gravity = Gravity.START
        val battery = ContextCompat.getDrawable(context, R.drawable.ic_battery)?.apply { setTint(Color.LTGRAY) }
        setCompoundDrawablesRelativeWithIntrinsicBounds(battery, null, null, null)
        compoundDrawablePadding = dpToPx(2)
    }

    private val statusRequiredPower: TextView = TextView(context).apply {
        setTextColor(Color.LTGRAY)
        textSize = 18f
        gravity = Gravity.END
        val bolt = ContextCompat.getDrawable(context, R.drawable.ic_bolt)?.apply { setTint(Color.LTGRAY) }
        setCompoundDrawablesRelativeWithIntrinsicBounds(bolt, null, null, null)
        compoundDrawablePadding = dpToPx(2)
    }

    val view: LinearLayout

    private var panel: View? = null
    private var isExpanded = false
    private var collapsedTranslationY = 0f

    init {
        setStatus()

        val mapStyleAdapter = object : ArrayAdapter<String>(
            context,
            android.R.layout.simple_spinner_item,
            mapStyleLabels
        ) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val view = super.getView(position, convertView, parent)
                (view as? TextView)?.apply {
                    setTextColor(Color.WHITE)
                    gravity = Gravity.START or Gravity.CENTER_VERTICAL
                }
                return view
            }

            override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View {
                val view = super.getDropDownView(position, convertView, parent)
                (view as? TextView)?.apply {
                    setTextColor(Color.WHITE)
                    setBackgroundColor(config.backgroundColor)
                }
                return view
            }
        }
        mapStyleAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)

        // Map style dropdown selector (Android View class name is Spinner).
        val styleSpinner = Spinner(context).apply {
            adapter = mapStyleAdapter
            minimumHeight = dpToPx(48)
            // Medium gray border
            background = roundedControlBackground(config.controlSurfaceColor, Color.parseColor("#505050"))
            setPopupBackgroundDrawable(ColorDrawable(config.backgroundColor))

            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    onMapStyleSelected(position)
                    setExpanded(expanded = false, animate = true)
                }

                override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            }
        }

        val statusRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(
                statusBatteryRemaining,
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            )
            addView(
                statusRequiredPower,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            )
        }

        val debugStatusSection = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            visibility = if (config.showDebugStatusSection) View.VISIBLE else View.GONE
            addView(
                statusRow,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            )
        }

        val reverseGpxDirectionCheckBox = CheckBox(context).apply {
            text = "Reverse GPX tracks"
            setTextColor(Color.WHITE)
            isChecked = isReverseGpxDirection
            setOnCheckedChangeListener { _, checked ->
                onReverseGpxDirectionChanged(checked)
                setExpanded(expanded = false, animate = true)
            }
        }

        val pimpMyGpxTracksCheckBox = CheckBox(context).apply {
            text = "Show points of interest along the track"
            setTextColor(Color.WHITE)
            isChecked = isPimpMyGpxTracks
            setOnCheckedChangeListener { _, checked ->
                onPimpMyGpxTracksChanged(checked)
                setExpanded(expanded = false, animate = true)
            }
        }

        val appVersionBuildInfoText = TextView(context).apply {
            setTextColor(Color.GRAY)
            textSize = 12f
            gravity = Gravity.CENTER_HORIZONTAL
            text = appVersionBuildText
        }

        val mapTypeLabel = TextView(context).apply {
            text = "Map Type:"
            setTextColor(Color.LTGRAY)
            textSize = 14f
            gravity = Gravity.START
        }

        val mapTypeRow = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, 0)

            addView(
                mapTypeLabel,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            )

            addView(
                styleSpinner,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = dpToPx(5)
                    marginStart = dpToPx(15)
                }
            )
        }

        val panelHandle = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(
                0,
                dpToPx(config.settingsPanelHandleVerticalPaddingDp),
                0,
                dpToPx(config.settingsPanelHandleVerticalPaddingDp)
            )

            val handleLine = View(context).apply {
                setBackgroundColor(Color.LTGRAY)
            }
            addView(
                handleLine,
                LinearLayout.LayoutParams(
                    dpToPx(62),
                    dpToPx(4)
                )
            )
        }

        val settingsContent = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpToPx(8), dpToPx(config.settingsPanelVerticalGapDp), dpToPx(8), dpToPx(config.settingsPanelVerticalGapDp))

            if (config.showDebugStatusSection) {
                addView(
                    debugStatusSection,
                    LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                )
            }

            addView(
                mapTypeRow,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = dpToPx(config.settingsPanelVerticalGapDp)
                }
            )

            addView(
                pimpMyGpxTracksCheckBox,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = dpToPx(config.settingsPanelVerticalGapDp)
                }
            )

            addView(
                reverseGpxDirectionCheckBox,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = dpToPx(config.settingsPanelVerticalGapDp)
                }
            )

            addView(
                appVersionBuildInfoText,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = dpToPx(config.settingsPanelVerticalGapDp)
                }
            )
        }

        view = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(config.backgroundColor)
            addView(
                panelHandle,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            )
            addView(
                settingsContent,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            )
        }

        bind(panel = view, dragHandle = panelHandle)
    }

    fun setExpanded(expanded: Boolean, animate: Boolean) {
        val panel = panel ?: return
        isExpanded = expanded
        val targetTranslation = if (expanded) 0f else collapsedTranslationY

        if (animate) {
            panel.animate()
                .translationY(targetTranslation)
                .setDuration(config.animationDurationMs)
                .start()
        } else {
            panel.translationY = targetTranslation
        }
    }

    fun setStatus() {
        statusBatteryRemaining.text = estimateBatteryRemaining()
        statusRequiredPower.text = batteryPowerText()
    }

    private fun bind(panel: View, dragHandle: View) {
        this.panel = panel

        panel.addOnLayoutChangeListener { _, _, top, _, bottom, _, oldTop, _, oldBottom ->
            val newHeight = bottom - top
            val oldHeight = oldBottom - oldTop
            if (newHeight <= 0 || newHeight == oldHeight) return@addOnLayoutChangeListener

            val visibleHandleHeightPx = dragHandle.height.coerceAtLeast(dpToPx(config.minVisibleHandleHeightDp))
            collapsedTranslationY = (newHeight - visibleHandleHeightPx).coerceAtLeast(0).toFloat()
            panel.translationY = if (isExpanded) 0f else collapsedTranslationY
        }

        panel.post {
            val visibleHandleHeightPx = dragHandle.height.coerceAtLeast(dpToPx(config.minVisibleHandleHeightDp))
            collapsedTranslationY = (panel.height - visibleHandleHeightPx).coerceAtLeast(0).toFloat()
            panel.translationY = collapsedTranslationY
            isExpanded = false
        }

        val touchSlop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
        var downRawY = 0f
        var downTranslationY = 0f
        var dragged = false

        dragHandle.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawY = event.rawY
                    downTranslationY = panel.translationY
                    dragged = false
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val dy = event.rawY - downRawY
                    if (abs(dy) > touchSlop) {
                        dragged = true
                    }

                    val newTranslation = (downTranslationY + dy)
                        .coerceIn(0f, collapsedTranslationY)
                    panel.translationY = newTranslation
                    true
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (!dragged) {
                        setExpanded(!isExpanded, animate = true)
                    } else {
                        val midpoint = collapsedTranslationY * 0.5f
                        val shouldExpand = panel.translationY < midpoint
                        setExpanded(shouldExpand, animate = true)
                    }
                    true
                }

                else -> false
            }
        }
    }

    private fun currentBatteryStatusIntent() =
        context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))

    private fun estimateBatteryRemaining(): String {
        val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val batteryStatus = currentBatteryStatusIntent() ?: return "Unknown"

        val status = batteryStatus.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL

        if (isCharging) {
            clearBatteryCurrentSmoothing()
            return ""
        }

        val chargeCounter = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)
        val currentUa = smoothedBatteryCurrentMicroAmps(batteryManager)

        if (chargeCounter <= 0 || currentUa == null || currentUa <= 0.0) {
            return ""
        }

        val hours = chargeCounter.toDouble() / currentUa
        val totalMinutes = (hours * 60).toInt().coerceAtLeast(0)
        val h = totalMinutes / 60
        val m = totalMinutes % 60

        return String.format(Locale.US, "%dh %02dmin", h, m)
    }

    // Current power draw, e.g. "850mW"; empty when unknown.
    private fun batteryPowerText(): String {
        val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val batteryStatus = currentBatteryStatusIntent() ?: return ""

        // Use smoothed current so the value is less noisy and consistent with remaining-time estimate.
        val currentUa = smoothedBatteryCurrentMicroAmps(batteryManager)
        val voltageMv = batteryStatus.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1)

        if (currentUa == null || voltageMv <= 0) return ""

        val powerMw = (currentUa * voltageMv.toDouble()) / 1_000_000.0
        return String.format(Locale.US, "%.0fmW", powerMw)
    }

    private fun smoothedBatteryCurrentMicroAmps(batteryManager: BatteryManager): Double? {
        val nowMs = System.currentTimeMillis()

        // Sample at most once per interval so repeated UI refresh calls don't over-weight one reading.
        if (nowMs - lastBatteryCurrentSampleTimeMs >= config.batteryCurrentSampleIntervalMs) {
            val sampleUa = readCurrentMicroAmps(batteryManager)
            if (sampleUa != null && sampleUa > 0.0) {
                recentBatteryCurrentUa.addLast(sampleUa)
                while (recentBatteryCurrentUa.size > config.batteryCurrentSmoothingWindowSamples) {
                    recentBatteryCurrentUa.removeFirst()
                }
                lastBatteryCurrentSampleUa = sampleUa
            }
            lastBatteryCurrentSampleTimeMs = nowMs
        }

        if (recentBatteryCurrentUa.isEmpty()) {
            return lastBatteryCurrentSampleUa
        }

        return recentBatteryCurrentUa.sum() / recentBatteryCurrentUa.size
    }

    private fun clearBatteryCurrentSmoothing() {
        recentBatteryCurrentUa.clear()
        lastBatteryCurrentSampleTimeMs = 0L
        lastBatteryCurrentSampleUa = null
    }

    private fun readCurrentMicroAmps(batteryManager: BatteryManager): Double? {
        val currentAverage = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_AVERAGE)
        val currentNow = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)

        val rawCurrent = when {
            currentAverage != Int.MIN_VALUE && currentAverage != 0 -> currentAverage
            currentNow != Int.MIN_VALUE && currentNow != 0 -> currentNow
            else -> Int.MIN_VALUE
        }

        if (rawCurrent == Int.MIN_VALUE || rawCurrent == 0) {
            return null
        }

        // Some devices report in mA instead of µA; normalize to µA.
        val absoluteRaw = abs(rawCurrent.toDouble())
        return if (absoluteRaw in 1.0..9_999.0) absoluteRaw * 1000.0 else absoluteRaw
    }

    private fun roundedControlBackground(fillColor: Int, strokeColor: Int): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dpToPx(8).toFloat()
            setColor(fillColor)
            setStroke(dpToPx(1), strokeColor)
        }
    }

    private fun dpToPx(dp: Int): Int = (dp * context.resources.displayMetrics.density).toInt()
}
