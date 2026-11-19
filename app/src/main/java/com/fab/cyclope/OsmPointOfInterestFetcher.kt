package com.fab.cyclope

import android.content.Context
import org.json.JSONObject
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale

// Fetches OpenStreetMap points of interest (nodes with a given tag) inside a bounding box.
// Uses the public Overpass API, which needs no API key.
// Blocking call: run it off the main thread.
object OsmPointOfInterestFetcher {

    data class PointOfInterest(val name: String?, val point: GeoPoint)

    const val TAG = "OsmPointOfInterest"
    private const val OVERPASS_URL = "https://overpass-api.de/api/interpreter"
    // The public Overpass server often answers 504 when busy, so a failed request is retried.
    private const val MAX_ATTEMPTS = 3
    private const val RETRY_DELAY_MS = 5000L

    // tagFilter is an Overpass tag filter, e.g. "\"amenity\"=\"drinking_water\"" for water fountains.
    // Returns null when every attempt failed.
    fun fetchNodes(context: Context, tagFilter: String, bounds: BoundingBox): List<PointOfInterest>? {
        val query = String.format(
            Locale.US,
            "[out:json][timeout:25];node[%s](%f,%f,%f,%f);out;",
            tagFilter, bounds.latSouth, bounds.lonWest, bounds.latNorth, bounds.lonEast
        )

        Log.log(context, TAG, "Query for $tagFilter: $query")
        repeat(MAX_ATTEMPTS) { attempt ->
            requestNodes(context, query)?.let {
                Log.log(context, TAG, "$tagFilter: ${it.size} nodes returned")
                return it
            }
            Log.log(context, TAG, "Overpass request failed (attempt ${attempt + 1} of $MAX_ATTEMPTS)")
            if (attempt < MAX_ATTEMPTS - 1) Thread.sleep(RETRY_DELAY_MS)
        }
        return null
    }

    // Returns null when the request fails, so the caller can retry.
    private fun requestNodes(context: Context, query: String): List<PointOfInterest>? {
        return try {
            val connection = (URL(OVERPASS_URL).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = 10000
                // Overpass can take 40 seconds or more to answer when the server is busy.
                readTimeout = 60000
                setRequestProperty("User-Agent", "Cyclope/1.0")
                setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            }
            connection.outputStream.use { it.write("data=${URLEncoder.encode(query, "UTF-8")}".toByteArray()) }
            if (connection.responseCode !in 200..299) {
                Log.log(context, TAG, "Overpass HTTP ${connection.responseCode}")
                return null
            }

            val responseBody = connection.inputStream.bufferedReader().use { it.readText() }
            val json = JSONObject(responseBody)
            // Overpass answers HTTP 200 with a "remark" (and no elements) when the query times out.
            json.optString("remark").takeIf { it.isNotEmpty() }?.let { Log.log(context, TAG, "Overpass remark: $it") }
            val elements = json.optJSONArray("elements") ?: return emptyList()
            val pointsOfInterest = mutableListOf<PointOfInterest>()
            for (i in 0 until elements.length()) {
                val element = elements.getJSONObject(i)
                val name = element.optJSONObject("tags")?.optString("name")?.takeIf { it.isNotEmpty() }
                pointsOfInterest.add(PointOfInterest(name, GeoPoint(element.getDouble("lat"), element.getDouble("lon"))))
            }
            pointsOfInterest
        } catch (e: Exception) {
            Log.log(context, TAG, "Overpass request error: ${e.message}")
            null
        }
    }
}
