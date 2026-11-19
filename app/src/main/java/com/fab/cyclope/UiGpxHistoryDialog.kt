package com.fab.cyclope

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.ImageSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject

private const val GPX_HISTORY_PREFERENCES = "cyclope"
private const val GPX_HISTORY_KEY = "gpx_history"

fun formatDurationText(minutes: Long): String =
    if (minutes >= 60) "${minutes / 60}h ${minutes % 60}min" else "${minutes}min"

class GpxHistoryStore(
    private val context: Context,
    val config: Config
) {
    data class Config(
        val maxEntries: Int,
        val dialogWidthFraction: Float,
        val dialogMaxHeightFraction: Float
    )

    data class Entry(
        val uri: Uri,
        val name: String,
        val lengthKm: Double,
        val ascentMeters: Double,
        val durationMinutes: Long?,
        val lastUsedMs: Long
    )

    private val preferences = context.getSharedPreferences(GPX_HISTORY_PREFERENCES, Context.MODE_PRIVATE)

    fun entries(): List<Entry> {
        val json = preferences.getString(GPX_HISTORY_KEY, null) ?: return emptyList()
        val array = JSONArray(json)
        return (0 until array.length()).map { index ->
            val item = array.getJSONObject(index)
            Entry(
                uri = Uri.parse(item.getString("uri")),
                name = item.getString("name"),
                lengthKm = item.getDouble("lengthKm"),
                ascentMeters = item.getDouble("ascentMeters"),
                durationMinutes = if (item.isNull("durationMinutes")) null else item.getLong("durationMinutes"),
                lastUsedMs = item.getLong("lastUsedMs")
            )
        }.sortedByDescending { it.lastUsedMs }
    }

    // Skipped when the provider does not allow persistent access to the URI.
    fun remember(uri: Uri, name: String, lengthKm: Double, ascentMeters: Double, durationMinutes: Long?) {
        try {
            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (e: SecurityException) {
            Log.log(context, "GpxHistory", "Cannot persist access to $uri: ${e.message}")
            return
        }

        val updated = listOf(Entry(uri, name, lengthKm, ascentMeters, durationMinutes, System.currentTimeMillis())) +
                entries().filter { it.uri != uri }
        val sorted = updated.sortedByDescending { it.lastUsedMs }
        sorted.drop(config.maxEntries).forEach { releaseUriPermission(it.uri) }
        save(sorted.take(config.maxEntries))
    }

    fun isAvailable(uri: Uri): Boolean {
        val hasPermission = context.contentResolver.persistedUriPermissions.any {
            it.uri == uri && it.isReadPermission
        }
        if (!hasPermission) return false

        return try {
            context.contentResolver.openInputStream(uri)?.use { true } ?: false
        } catch (_: Exception) {
            false
        }
    }

    fun clear() {
        entries().forEach { releaseUriPermission(it.uri) }
        preferences.edit().remove(GPX_HISTORY_KEY).apply()
    }

    private fun releaseUriPermission(uri: Uri) {
        try {
            context.contentResolver.releasePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: SecurityException) {
            // Permission was already released by the provider.
        }
    }

    private fun save(entries: List<Entry>) {
        val array = JSONArray()
        entries.forEach { entry ->
            array.put(
                JSONObject().apply {
                    put("uri", entry.uri.toString())
                    put("name", entry.name)
                    put("lengthKm", entry.lengthKm)
                    put("ascentMeters", entry.ascentMeters)
                    put("durationMinutes", entry.durationMinutes)
                    put("lastUsedMs", entry.lastUsedMs)
                }
            )
        }
        preferences.edit().putString(GPX_HISTORY_KEY, array.toString()).apply()
    }
}

class UiGpxHistoryDialog(
    private val activity: Activity,
    private val store: GpxHistoryStore,
    private val onOpenFileRequested: () -> Unit,
    private val onGpxSelected: (Uri) -> Unit
) {
    fun show() {
        val entries = store.entries()
        // Checking availability opens each file, so it runs off the UI thread.
        Thread {
            val availability = entries.map { it to store.isAvailable(it.uri) }
            activity.runOnUiThread { showEntries(availability) }
        }.start()
    }

    private fun showEntries(availability: List<Pair<GpxHistoryStore.Entry, Boolean>>) {
        val metrics = activity.resources.displayMetrics
        val dialogWidthPx = (metrics.widthPixels * store.config.dialogWidthFraction).toInt()
        val maxHeightPx = (metrics.heightPixels * store.config.dialogMaxHeightFraction).toInt()
        val list = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpToPx(16), 0, dpToPx(16), dpToPx(8))
        }

        if (availability.isEmpty()) {
            list.addView(TextView(activity).apply {
                text = "No GPX files opened yet"
                setTextColor(Color.LTGRAY)
            })
        }

        lateinit var dialog: AlertDialog
        availability.forEachIndexed { index, (entry, isAvailable) ->
            if (index > 0) {
                list.addView(
                    View(activity).apply { setBackgroundColor(Color.parseColor("#505050")) }, // medium gray separator
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dpToPx(1))
                )
            }
            // Files that are no longer accessible are dimmed as a whole row: name, details and all icons.
            val rowColor = if (isAvailable) Color.WHITE else Color.GRAY
            val detailColor = if (isAvailable) Color.LTGRAY else Color.GRAY
            val distancePlaceholder = "\uFFFC"
            val ascentPlaceholder = "\uFFFD"
            val nameText = entry.name.substringBeforeLast('.', entry.name)
            val timerPlaceholder = "\uFFFE"
            val durationPart = entry.durationMinutes?.let { "  $timerPlaceholder${formatDurationText(it)}" } ?: ""
            val detailText =
                "$distancePlaceholder%.0fkm  $ascentPlaceholder%.0fm$durationPart".format(entry.lengthKm, entry.ascentMeters)
            val spannable = SpannableString("$nameText\n$detailText")
            spannable.setSpan(
                ForegroundColorSpan(detailColor),
                nameText.length + 1,
                spannable.length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            val iconSizePx = dpToPx(16) // Matches the 16px row text.
            val pathIconSizePx = dpToPx(27)
            list.addView(LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL // path icon is centered on the name and details lines
                setPadding(0, dpToPx(10), 0, dpToPx(10))
                ContextCompat.getDrawable(activity, R.drawable.ic_path)?.let { pathIcon ->
                    pathIcon.setTint(rowColor)
                    addView(ImageView(activity).apply {
                        setImageDrawable(pathIcon)
                        layoutParams = LinearLayout.LayoutParams(pathIconSizePx, pathIconSizePx).apply {
                            marginEnd = dpToPx(5)
                        }
                    })
                }
                addView(TextView(activity).apply {
                    textSize = 16f
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    ContextCompat.getDrawable(activity, R.drawable.ic_distance)?.let { distanceIcon ->
                        distanceIcon.setTint(detailColor)
                        distanceIcon.setBounds(0, 0, iconSizePx, iconSizePx)
                        val start = spannable.indexOf(distancePlaceholder)
                        spannable.setSpan(
                            ImageSpan(distanceIcon, ImageSpan.ALIGN_CENTER),
                            start,
                            start + 1,
                            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                        )
                    }
                    ContextCompat.getDrawable(activity, R.drawable.ic_ascent)?.let { ascentIcon ->
                        ascentIcon.setTint(detailColor)
                        ascentIcon.setBounds(0, 0, iconSizePx, iconSizePx)
                        val start = spannable.indexOf(ascentPlaceholder)
                        spannable.setSpan(
                            ImageSpan(ascentIcon, ImageSpan.ALIGN_CENTER),
                            start,
                            start + 1,
                            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                        )
                    }
                    if (entry.durationMinutes != null) ContextCompat.getDrawable(activity, R.drawable.ic_timer)?.let { timerIcon ->
                        timerIcon.setTint(detailColor)
                        timerIcon.setBounds(0, 0, iconSizePx, iconSizePx)
                        val start = spannable.indexOf(timerPlaceholder)
                        spannable.setSpan(
                            ImageSpan(timerIcon, ImageSpan.ALIGN_CENTER),
                            start,
                            start + 1,
                            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                        )
                    }
                    text = spannable
                    setTextColor(rowColor)
                })
                if (isAvailable) {
                    val outValue = TypedValue()
                    context.theme.resolveAttribute(android.R.attr.selectableItemBackground, outValue, true)
                    setBackgroundResource(outValue.resourceId)
                    setOnClickListener {
                        dialog.dismiss()
                        onGpxSelected(entry.uri)
                    }
                }
            })
        }

        if (availability.isNotEmpty()) {
            val accentColor = TypedValue()
            activity.theme.resolveAttribute(
                androidx.appcompat.R.attr.colorAccent,
                accentColor,
                true
            ) // same color as the dialog buttons
            list.addView(TextView(activity).apply {
                text = "Clear history"
                setTextColor(accentColor.data)
                setPadding(0, dpToPx(12), 0, 0)
                setOnClickListener {
                    store.clear()
                    dialog.dismiss()
                }
            })
        }

        dialog = AlertDialog.Builder(activity)
            .setTitle("Recent GPX tracks")
            .setView(MaxHeightScrollView(activity, maxHeightPx).apply {
                setPadding(0, dpToPx(16), 0, 0) // fixed gap below the title, kept while scrolling
                addView(list)
            })

            .setNegativeButton("Cancel", null)
            .setPositiveButton("Open") { _, _ -> onOpenFileRequested() }
            .show()
        dialog.window?.setLayout(dialogWidthPx, ViewGroup.LayoutParams.WRAP_CONTENT)
        dialog.window?.setBackgroundDrawable(GradientDrawable().apply {
            setColor(Color.argb(187, 0, 0, 0)) // black and a bit transparent
            setStroke(dpToPx(1), Color.DKGRAY) // subtle gray border
            cornerRadius = dpToPx(12).toFloat()
        })
    }

    private fun dpToPx(dp: Int): Int = (dp * activity.resources.displayMetrics.density).toInt()
}

private class MaxHeightScrollView(context: Context, private val maxHeightPx: Int) : ScrollView(context) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, View.MeasureSpec.makeMeasureSpec(maxHeightPx, View.MeasureSpec.AT_MOST))
    }
}
