package com.fab.cyclope

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import kotlin.math.roundToInt

// One circle per GPX track; tap inverts it, double tap disables it, horizontal drag reorders it.
class UiGpxTrackSelector(
    private val context: Context,
    private val onTrackTapped: (Int) -> Unit,
    private val onTrackDoubleTapped: (Int) -> Unit,
    private val onTrackMoved: (from: Int, to: Int) -> Unit,
    private val onInteracted: () -> Unit
) {
    private val sizePx = dp(36) // circle diameter
    private val gapPx = dp(2) // gap between circles
    private val maxCircles = 7 // tracks after the 7th get no circle

    // Circles shown above the tracks while dragging, one drop position per track.
    private val dropArea = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        visibility = View.GONE
        translationY = -sizePx.toFloat() // sit one row above the circles
    }

    private val circlesRow = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        clipChildren = false
        clipToPadding = false
    }

    val view = FrameLayout(context).apply {
        visibility = View.GONE
        clipChildren = false
        clipToPadding = false
        addView(dropArea, bottomStartParams())
        addView(circlesRow, bottomStartParams())
    }

    fun update(states: List<UiGpxTrackManager.TrackState>) {
        circlesRow.removeAllViews()
        dropArea.removeAllViews()
        dropArea.visibility = View.GONE
        view.visibility = View.VISIBLE
        val shownStates = states.take(maxCircles)
        shownStates.forEachIndexed { index, state ->
            dropArea.addView(View(context).apply { background = dropSlot() }, slotParams())
            circlesRow.addView(buildCircle(index, state, shownStates.lastIndex), slotParams())
        }
    }

    // Shown while the map is paused/parked; hidden while the map follows GPS during a ride.
    fun setVisible(visible: Boolean) {
        view.visibility = if (visible && circlesRow.childCount > 0) View.VISIBLE else View.GONE
    }

    private fun buildCircle(index: Int, state: UiGpxTrackManager.TrackState, lastIndex: Int): FrameLayout {
        val isDisabled = state == UiGpxTrackManager.TrackState.DISABLED
        val icon = ImageView(context).apply {
            setImageResource(if (isDisabled) R.drawable.ic_play_disable else R.drawable.ic_play)
            rotation = if (state == UiGpxTrackManager.TrackState.INVERTED) 180f else 0f
        }
        return FrameLayout(context).apply {
            background = circleFill()
            elevation = dp(2).toFloat() // small resting lift so circles read above the map
            addView(icon, FrameLayout.LayoutParams(dp(28), dp(28), Gravity.CENTER))
            var downX = 0f
            val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
                override fun onSingleTapConfirmed(e: MotionEvent): Boolean { onTrackTapped(index); return true }
                override fun onDoubleTap(e: MotionEvent): Boolean { onTrackDoubleTapped(index); return true }
            })
            setOnTouchListener { v, event ->
                // Require a short hold before a drag starts so taps are not read as drags. With a single circle there is
                // nothing to reorder, so dragging is disabled.
                val isDragging = lastIndex > 0 && event.eventTime - event.downTime >= 300L
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        downX = event.rawX
                        onInteracted() // keep the selector visible while it is being used
                    }
                    MotionEvent.ACTION_MOVE -> if (isDragging) {
                        v.translationX = event.rawX - downX
                        v.translationY = -sizePx.toFloat()
                        v.elevation = dp(8).toFloat()
                        dropArea.visibility = View.VISIBLE
                        val steps = ((event.rawX - downX) / (sizePx + gapPx)).roundToInt()
                        highlightDropHole((index + steps).coerceIn(0, lastIndex))
                    }
                    MotionEvent.ACTION_UP -> {
                        resetDrag(v)
                        onInteracted() // restart the timer from when the finger lifts
                        if (isDragging) {
                            val steps = ((event.rawX - downX) / (sizePx + gapPx)).roundToInt()
                            val target = (index + steps).coerceIn(0, lastIndex)
                            if (target != index) onTrackMoved(index, target)
                        }
                    }
                    MotionEvent.ACTION_CANCEL -> resetDrag(v)
                }
                gestures.onTouchEvent(event)
                true
            }
        }
    }

    private fun resetDrag(circle: View) {
        circle.translationX = 0f
        circle.translationY = 0f
        circle.elevation = dp(2).toFloat()
        dropArea.visibility = View.GONE
        for (i in 0 until dropArea.childCount) dropArea.getChildAt(i).apply {
            background = dropSlot(false)
            scaleX = 1f
            scaleY = 1f
        }
    }

    // Highlight the drop hole under the dragged circle to preview where it will land.
    private fun highlightDropHole(target: Int) {
        for (i in 0 until dropArea.childCount) dropArea.getChildAt(i).apply {
            background = dropSlot(i == target)
            // Slightly larger so the rim stays visible around the dragged circle that covers it.
            scaleX = if (i == target) 1.15f else 1f
            scaleY = scaleX
        }
    }

    private fun circleFill() = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(Color.argb(190, 0, 0, 0)) // dark background for the circle
        setStroke(dp(1), Color.argb(90, 255, 255, 255)) // thin outline ring for contrast on the map
    }

    // Looks like an empty hole in the map: a dark translucent disc with a thin rim. The slot the dragged circle would
    // land in is darker with a brighter rim, so it stands out as the active hole.
    private fun dropSlot(highlight: Boolean = false) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(Color.argb(if (highlight) 200 else 110, 0, 0, 0))
        setStroke(dp(2), Color.argb(if (highlight) 230 else 110, 255, 255, 255))
    }

    private fun slotParams() = LinearLayout.LayoutParams(sizePx, sizePx).apply { marginEnd = gapPx }

    private fun bottomStartParams() = FrameLayout.LayoutParams(
        FrameLayout.LayoutParams.WRAP_CONTENT,
        FrameLayout.LayoutParams.WRAP_CONTENT,
        Gravity.BOTTOM or Gravity.START
    )

    private fun dp(value: Int): Int = (value * context.resources.displayMetrics.density).toInt()
}
