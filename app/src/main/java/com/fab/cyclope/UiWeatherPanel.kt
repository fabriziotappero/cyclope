package com.fab.cyclope

import android.content.Context
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ImageSpan
import android.text.style.RelativeSizeSpan
import android.util.TypedValue
import androidx.core.content.ContextCompat
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

data class WeatherSummary(
    val temperatureText: String,
    val rainText: String,
    val windText: String
)

class UiWeatherPanel(
    private val context: Context,
    val config: Config
) {
    data class Config(
        val weatherTextSizeSp: Float,
        val refreshIntervalSeconds: Long,
        val retryOnFailureIntervalSeconds: Long
    )

    companion object {
        private const val TAG = "Weather"
        private const val WEATHER_API_URL_TEMPLATE =
            "https://api.open-meteo.com/v1/forecast?latitude=%.5f&longitude=%.5f%s&current=temperature_2m,wind_speed_10m&hourly=precipitation_probability&wind_speed_unit=kmh&forecast_days=2"
    }

    fun placeholderSummary(): WeatherSummary = WeatherSummary(
        temperatureText = "--°C",
        rainText = "--%",
        windText = "--km/h"
    )

    fun refreshIntervalMs(lastRequestSucceeded: Boolean): Long {
        val seconds = if (lastRequestSucceeded) {
            config.refreshIntervalSeconds
        } else {
            config.retryOnFailureIntervalSeconds
        }
        return seconds * 1000L
    }

    fun fetchWeatherSummary(latitude: Double, longitude: Double, elevationMeters: Double?): WeatherSummary? {
        val elevationQuery = elevationMeters?.let {
            String.format(Locale.US, "&elevation=%.0f", it)
        } ?: ""

        val requestUrl = String.format(
            Locale.US,
            WEATHER_API_URL_TEMPLATE,
            latitude,
            longitude,
            elevationQuery
        )

        Log.log(context, TAG, "Request: $requestUrl")
        val connection = (URL(requestUrl).openConnection() as HttpURLConnection).apply {
            connectTimeout = 5000
            readTimeout = 5000
            requestMethod = "GET"
            doInput = true
        }

        return try {
            if (connection.responseCode !in 200..299) {
                Log.log(context, TAG, "HTTP ${connection.responseCode}")
                return null
            }

            val responseBody = connection.inputStream.bufferedReader().use { it.readText() }
            val root = JSONObject(responseBody)
            val current = root.optJSONObject("current") ?: return null

            val temperatureC = current.optDouble("temperature_2m", Double.NaN)
            val windSpeedKmh = current.optDouble("wind_speed_10m", Double.NaN)

            // Rain: highest precipitation probability in the next 5 hours.
            // Find the hourly entry for the current hour, then take the 5 entries after it.
            // Without a match, the window starts at the first hourly entry.
            val currentTime = current.optString("time", "")
            val currentHourKey = if (currentTime.length >= 13) currentTime.substring(0, 13) else currentTime
            val hourly = root.optJSONObject("hourly")
            val hourlyTimes = hourly?.optJSONArray("time")
            val hourlyRainProbabilities = hourly?.optJSONArray("precipitation_probability")

            var rainProbability = Double.NaN
            if (hourlyTimes != null && hourlyRainProbabilities != null) {
                val count = minOf(hourlyTimes.length(), hourlyRainProbabilities.length())
                val currentIndex = (0 until count).firstOrNull { i ->
                    val hourlyTime = hourlyTimes.optString(i)
                    (currentHourKey.isNotBlank() && hourlyTime.startsWith(currentHourKey)) ||
                            hourlyTime == currentTime
                } ?: -1
                Log.log(context, TAG, "Current time $currentTime matched hourly index $currentIndex")

                for (i in currentIndex + 1 until minOf(currentIndex + 6, count)) {
                    val probability = hourlyRainProbabilities.optDouble(i, Double.NaN)
                    Log.log(context, TAG, "Rain ${hourlyTimes.optString(i)}: $probability%")
                    if (!probability.isNaN() && (rainProbability.isNaN() || probability > rainProbability)) {
                        rainProbability = probability
                    }
                }
            }

            if (temperatureC.isNaN() && rainProbability.isNaN() && windSpeedKmh.isNaN()) return null

            val temperatureText = if (temperatureC.isNaN()) "--°C"
            else String.format(Locale.US, "%.0f°C", temperatureC)
            val rainText = if (rainProbability.isNaN()) "--%"
            else String.format(Locale.US, "%.0f%%", rainProbability)
            val windText = if (windSpeedKmh.isNaN()) "--km/h"
            else String.format(Locale.US, "%.0fkm/h", windSpeedKmh)

            Log.log(
                context,
                TAG,
                "Current time=$currentTime temperature=$temperatureC wind=$windSpeedKmh rain_max=$rainProbability"
            )
            Log.log(context, TAG, "Summary: $temperatureText, $rainText, $windText")
            WeatherSummary(
                temperatureText = temperatureText,
                rainText = rainText,
                windText = windText
            )
        } finally {
            connection.disconnect()
        }
    }

    fun formatWeatherSummaryWithIcons(weatherSummary: WeatherSummary, textColor: Int): CharSequence {
        val temperatureIconPlaceholder = "\uFFFC"
        val rainIconPlaceholder = "\uFFFD"
        val windIconPlaceholder = "\uE000"

        val plainText = "${weatherSummary.temperatureText}  ${weatherSummary.rainText}  ${weatherSummary.windText} "
        val textWithIcons =
            "$temperatureIconPlaceholder${weatherSummary.temperatureText}  $rainIconPlaceholder${weatherSummary.rainText}  $windIconPlaceholder${weatherSummary.windText} "
        val spannable = SpannableString(textWithIcons)

        val temperatureIconIndex = textWithIcons.indexOf(temperatureIconPlaceholder)
        val rainIconIndex = textWithIcons.indexOf(rainIconPlaceholder)
        val windIconIndex = textWithIcons.indexOf(windIconPlaceholder)
        if (temperatureIconIndex < 0 || rainIconIndex < 0 || windIconIndex < 0) return plainText

        val temperatureDrawable = ContextCompat.getDrawable(context, R.drawable.ic_temperature)
            ?: return plainText
        val rainyDrawable = ContextCompat.getDrawable(context, R.drawable.ic_rainy)
            ?: return plainText
        val windDrawable = ContextCompat.getDrawable(context, R.drawable.ic_wind)
            ?: return plainText

        val iconSizePx = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP,
            config.weatherTextSizeSp,
            context.resources.displayMetrics
        ).toInt().coerceAtLeast(1)

        temperatureDrawable.setTint(textColor)
        temperatureDrawable.setBounds(0, 0, iconSizePx, iconSizePx)
        spannable.setSpan(
            ImageSpan(temperatureDrawable, ImageSpan.ALIGN_CENTER),
            temperatureIconIndex,
            temperatureIconIndex + temperatureIconPlaceholder.length,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )

        rainyDrawable.setTint(textColor)
        rainyDrawable.setBounds(0, 0, iconSizePx, iconSizePx)
        spannable.setSpan(
            ImageSpan(rainyDrawable, ImageSpan.ALIGN_CENTER),
            rainIconIndex,
            rainIconIndex + rainIconPlaceholder.length,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )

        windDrawable.setTint(textColor)
        windDrawable.setBounds(0, 0, iconSizePx, iconSizePx)
        spannable.setSpan(
            ImageSpan(windDrawable, ImageSpan.ALIGN_CENTER),
            windIconIndex,
            windIconIndex + windIconPlaceholder.length,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )

        // make "km/h" a little smaller for aesthetic reasons
        val windUnit = "km/h"
        if (weatherSummary.windText.endsWith(windUnit)) {
            val windTextStart = windIconIndex + windIconPlaceholder.length
            val windUnitStart = windTextStart + weatherSummary.windText.length - windUnit.length
            val windUnitEnd = windTextStart + weatherSummary.windText.length
            spannable.setSpan(
                RelativeSizeSpan(0.9f),
                windUnitStart,
                windUnitEnd,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }

        return spannable
    }
}
