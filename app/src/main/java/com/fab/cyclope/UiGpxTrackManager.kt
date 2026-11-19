package com.fab.cyclope

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Point
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.location.Location
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ImageSpan
import android.util.Xml
import android.widget.TextView
import androidx.core.content.ContextCompat
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import java.io.InputStream
import kotlin.math.hypot
import kotlin.math.roundToLong

class UiGpxTrackManager(
    private val context: Context,
    private val deps: Deps,
    private val config: Config,
    private val rider: RiderConfig,
    private val gpxHistoryStore: GpxHistoryStore,
    private val onGpxLoaded: () -> Unit,
    private val onTrackChanged: () -> Unit
) {
    data class Deps(
        val mapViewProvider: () -> MapView?,
        val altitudeGraphView: UiAltitudeGraphView,
        val gpxFileNameText: TextView,
        val currentMapStyleMaxZoomProvider: () -> Int,
        val isMapFollowPausedProvider: () -> Boolean
    )

    data class Config(
        val gpxPickerDownloadsUri: String,
        val gpxTrackStrokeWidth: Float,
        val gpxTrackStrokeColor: Int,
        val gpxTrackCasingColor: Int,
        val gpxTrackCasingExtraWidth: Float,
        val gpxBeginMarkerColor: Int,
        val gpxEndMarkerColor: Int,
        val positionMarkerSizeDp: Int,
        val positionMarkerColor: Int,
        val gpxEndpointMarkerSizeDp: Int,
        val maxDirectionArrowMarkers: Int,
        val directionArrowSizeDp: Int,
        val nearGpxTrackThresholdMeters: Float,
        val snapPositionOnGpxTrackWhenNear: Boolean,
        val userMarkerScreenYRatio: Float,
        val pointOfInterestMaxDistanceFromTrackMeters: Float,
        val drawPointSpacingMeters: Float,
        val inclinationMaxSections: Int,
        val inclinationYellowFromPercent: Double,
        val inclinationRedFromPercent: Double,
        val inclinationGreenColor: Int,
        val inclinationYellowColor: Int,
        val inclinationRedColor: Int,
        val inclinationDescentColor: Int
    )

    private data class GpxTrackPoint(
        val point: GeoPoint,
        val elevationMeters: Double?,
        val timeMillis: Long?
    )

    private data class GpxTrack(
        val index: Int,
        val name: String?,
        val points: List<GpxTrackPoint>
    )

    private data class TrackMatch(
        val index: Int,
        val distanceMeters: Float
    )

    private var locationMarker: Marker? = null
    private var gpxTrackOverlays: List<Polyline> = emptyList()
    private var gpxTrackCasingOverlays: List<Polyline> = emptyList()
    private var endpointMarkers: List<Pair<Marker, Marker>> = emptyList()
    private var pointOfInterestMarkers: List<Marker> = emptyList()
    private var directionArrowMarkers: List<Marker> = emptyList()
    private var isPointsOfInterestEnabled = true
    // Incremented on every GPX load; the Overpass results below belong to the load with this id.
    private var gpxLoadId = 0
    private var cachedPointsOfInterestLoadId = -1
    private var cachedPointsOfInterest: List<Pair<GeoPoint, Int>> = emptyList()
    private var loadedTrackPoints: List<GpxTrackPoint> = emptyList()

    enum class TrackState { ENABLED, INVERTED, DISABLED }

    private var sourceTracks: List<GpxTrack> = emptyList()
    private var trackStates: List<TrackState> = emptyList()
    private var isGpxDirectionReversed = false
    private var loadedGpxDisplayName: String? = null
    private var latestDistanceToEndKmIfNearTrack: Double? = null
    private var latestRemainingAscentMetersIfNearTrack: Double? = null
    private var cumulativeTrackAscentMeters: List<Double> = emptyList()
    private var totalTrackDistanceKm: Double? = null
    private var recordedDurationMinutes: Long? = null
    private var sourceAscentMeters = 0.0
    private var lastMapPoint: GeoPoint? = null
    private var cumulativeTrackDistancesKm: List<Double> = emptyList()
    private var drawnTrackPoints: List<GeoPoint> = emptyList()
    // Index of the last on-track match; the next search starts around it. Reset when the track changes.
    private var lastMatchIndex: Int? = null

    fun createGpxPickerIntent(): Intent {
        return Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            // Allow broad selection; we validate .gpx extension after pick.
            type = "*/*"
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
            putExtra(DocumentsContract.EXTRA_INITIAL_URI, Uri.parse(config.gpxPickerDownloadsUri))
        }
    }

    fun loadGpxTrackFromUri(uri: Uri, latestLocation: Location?) {
        val displayName = getDisplayName(uri)
        if (displayName != null && !displayName.endsWith(".gpx", ignoreCase = true)) {
            Log.log(context, "GpxTrack", "Not a .gpx file: $displayName")
            return
        }

        val tracks = try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                parseGpxTracks(input)
            } ?: emptyList()
        } catch (e: Exception) {
            Log.log(context, "GpxTrack", "Failed to read selected GPX $uri: ${e.message}")
            return
        }

        if (tracks.isEmpty()) {
            Log.log(context, "GpxTrack", "No valid points found in selected GPX $uri")
            return
        }

        setSourceGpxTrack(tracks, latestLocation)
        setLoadedGpxDisplayName(displayName ?: "")

        gpxHistoryStore.remember(
            uri = uri,
            name = displayName ?: "",
            lengthKm = totalTrackDistanceKm ?: 0.0,
            ascentMeters = sourceAscentMeters,
            durationMinutes = recordedDurationMinutes
        )
    }

    fun loadGpxTrackFromAssets(assetFileName: String, latestLocation: Location?) {
        val tracks = try {
            context.assets.open(assetFileName).use { input ->
                parseGpxTracks(input)
            }
        } catch (_: Exception) {
            // Optional test file: ignore when missing or invalid.
            return
        }

        setSourceGpxTrack(tracks, latestLocation)
        setLoadedGpxDisplayName(assetFileName)
    }

    fun trackStates(): List<TrackState> = trackStates

    // Tap toggles a track between enabled and inverted; disabled tracks are unchanged.
    fun cycleTrackState(index: Int, latestLocation: Location?) {
        if (index !in trackStates.indices) return
        val current = trackStates[index]
        if (current == TrackState.DISABLED) return
        val updated = trackStates.toMutableList()
        updated[index] = if (current == TrackState.ENABLED) TrackState.INVERTED else TrackState.ENABLED
        trackStates = updated
        applyGpxDirectionToCurrentTrack(zoomToTrack = false, latestLocation = latestLocation)
    }

    // Double tap toggles a track disabled; the last enabled track cannot be disabled.
    fun toggleTrackDisabled(index: Int, latestLocation: Location?) {
        if (index !in trackStates.indices) return
        val updated = trackStates.toMutableList()
        updated[index] = if (updated[index] == TrackState.DISABLED) TrackState.ENABLED else TrackState.DISABLED
        if (updated.all { it == TrackState.DISABLED }) return
        trackStates = updated
        applyGpxDirectionToCurrentTrack(zoomToTrack = false, latestLocation = latestLocation)
    }

    // Moves a track in the route order; the selector and altitude plot follow this order.
    fun moveTrack(from: Int, to: Int, latestLocation: Location?) {
        if (from == to || from !in trackStates.indices || to !in trackStates.indices) return
        sourceTracks = sourceTracks.toMutableList().apply { add(to, removeAt(from)) }
            .mapIndexed { position, track -> track.copy(index = position) }
        trackStates = trackStates.toMutableList().apply { add(to, removeAt(from)) }
        applyGpxDirectionToCurrentTrack(zoomToTrack = false, latestLocation = latestLocation)
    }

    fun setDirectionReversed(reversed: Boolean, zoomToTrack: Boolean, latestLocation: Location?) {
        isGpxDirectionReversed = reversed
        applyGpxDirectionToCurrentTrack(zoomToTrack = zoomToTrack, latestLocation = latestLocation)
    }

    fun setPointsOfInterestEnabled(enabled: Boolean) {
        isPointsOfInterestEnabled = enabled
        val map = deps.mapViewProvider() ?: return
        if (loadedTrackPoints.isNotEmpty()) loadPointsOfInterestAlongTrack(map, loadedTrackPoints)
    }

    // Compute nearest-track match once and reuse it for GPX-related updates.
    fun onLocationUpdate(location: Location, shouldUpdateMapLocation: Boolean) {
        val match = nearestTrackMatch(location)
        updateAltitudeGraphMarker(match)
        if (shouldUpdateMapLocation) {
            updateMapLocation(location, match)
        }
        latestDistanceToEndKmIfNearTrack = distanceToGpxEndKmIfNearTrack(match)
        latestRemainingAscentMetersIfNearTrack = remainingAscentMetersIfNearTrack(match)
    }

    fun latestDistanceToGpxEndKmIfNearTrack(): Double? = latestDistanceToEndKmIfNearTrack

    fun estimatedRemainingMinutesIfNearTrack(): Long? {
        val remainingKm = latestDistanceToEndKmIfNearTrack ?: return null
        val remainingAscent = latestRemainingAscentMetersIfNearTrack ?: return null
        return TimeToDestinationEstimation.estimateMinutes(
            remainingKm = remainingKm,
            remainingAscentMeters = remainingAscent,
            flatSpeedKmh = rider.flatSpeedKmh,
            climbMetersPerHour = rider.climbMetersPerHour
        )
    }

    fun latestDoneDistanceKmIfNearTrack(): Double? {
        val routeKm = totalTrackDistanceKm ?: return null
        val remainingKm = latestDistanceToEndKmIfNearTrack ?: return null
        return (routeKm - remainingKm).coerceIn(0.0, routeKm)
    }

    // Length of the whole route: the enabled tracks in the selected order (gaps between tracks not counted).
    fun routeDistanceKm(): Double? = totalTrackDistanceKm

    private fun updateAltitudeGraphMarker(match: TrackMatch?) {
        // Pick the nearest GPX sample to current GPS location and project it on altitude graph.
        if (match == null || !isNearGpxTrack(match.distanceMeters)) {
            deps.altitudeGraphView.setHighlightedIndex(null)
            return
        }

        val graphIndex = nearestTrackIndexWithAltitude(match.index)
        deps.altitudeGraphView.setHighlightedIndex(graphIndex)
    }

    fun recenterOnLastPoint() {
        val map = deps.mapViewProvider() ?: return
        val point = lastMapPoint ?: return
        if (!deps.isMapFollowPausedProvider()) {
            applyFollowCenterOffset(map)
            map.controller.setCenter(point)
        }
        map.invalidate()
    }

    // Lower-third anchor used only while following the live position.
    private fun applyFollowCenterOffset(map: MapView) {
        map.setMapCenterOffset(0, (map.height * (config.userMarkerScreenYRatio - 0.5f)).toInt())
    }

    // Fit the map to the whole route, same as right after a GPX load.
    fun zoomToWholeTrack() {
        zoomToPoints(drawnTrackPoints)
    }

    private fun updateMapLocation(location: Location, trackMatch: TrackMatch?) {
        val map = deps.mapViewProvider() ?: return

        val isUserNearGpxTrack = trackMatch?.let { isNearGpxTrack(it.distanceMeters) } == true
        val canSnapMarkerToGpxTrack = config.snapPositionOnGpxTrackWhenNear &&
                trackMatch != null &&
                isUserNearGpxTrack

        // GPX-track snap:
        // use isNearGpxTrack(...) as the proximity gate, then snap to nearest GPX point.
        // Otherwise draw marker at raw GPS location.
        val matchedTrackPoint = if (canSnapMarkerToGpxTrack && trackMatch != null) {
            loadedTrackPoints[trackMatch.index].point
        } else {
            null
        }

        val point = matchedTrackPoint ?: GeoPoint(location.latitude, location.longitude)
        lastMapPoint = point

        // Create marker once, then only update its position.
        if (locationMarker == null) {
            locationMarker = Marker(map).apply {
                position = point
                icon = createPositionMarkerIcon()
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                infoWindow = null
                setOnMarkerClickListener { _, _ -> true }
            }
            map.overlays.add(locationMarker)
        } else {
            locationMarker?.position = point
        }

        // Keep camera centered on user position, unless centering is paused after a map touch or GPX load.
        if (!deps.isMapFollowPausedProvider()) {
            applyFollowCenterOffset(map)
            map.controller.setCenter(point)
        }

        map.invalidate()
    }

    // Distance from the rider to the end of the whole route (all enabled tracks, in the selected order).
    private fun distanceToGpxEndKmIfNearTrack(match: TrackMatch?): Double? {
        if (match == null || !isNearGpxTrack(match.distanceMeters)) return null
        val distancesKm = cumulativeTrackDistancesKm
        if (match.index !in distancesKm.indices) return null
        return distancesKm.last() - distancesKm[match.index]
    }

    // Ascent from the rider to the end of the whole route; used for the time to destination.
    private fun remainingAscentMetersIfNearTrack(match: TrackMatch?): Double? {
        if (match == null || !isNearGpxTrack(match.distanceMeters)) return null
        val ascentMeters = cumulativeTrackAscentMeters
        if (match.index !in ascentMeters.indices) return null
        return ascentMeters.last() - ascentMeters[match.index]
    }

    private fun getDisplayName(uri: Uri): String? {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (nameIndex >= 0 && cursor.moveToFirst()) {
                return cursor.getString(nameIndex)
            }
        }
        return uri.lastPathSegment
    }

    private fun setLoadedGpxDisplayName(name: String?) {
        loadedGpxDisplayName = name
        Log.log(
            context,
            "GpxTrack",
            "Loaded GPX: ${name ?: "(no name)"}, ${sourceTracks.sumOf { it.points.size }} points in ${sourceTracks.size} tracks"
        )

        val resolved = loadedGpxDisplayName?.takeIf { it.isNotBlank() } ?: ""
        val withoutExtension = resolved.substringBeforeLast('.', resolved).ifBlank { resolved }
        deps.gpxFileNameText.text = buildGpxFileNameText(withoutExtension)
    }

    // The name is on the first line; length and ascent are inline icons on the second line, using values computed once when the track is loaded.
    private fun buildGpxFileNameText(name: String): CharSequence {
        val lengthKm = totalTrackDistanceKm ?: return name
        val distancePlaceholder = "\uFFFC"
        val ascentPlaceholder = "\uFFFD"
        val timerPlaceholder = "\uFFFE"
        val durationText = recordedDurationMinutes?.let { formatDurationText(it) }
        val timerPart = if (durationText != null) "  $timerPlaceholder$durationText" else ""
        val text = "$name\n$distancePlaceholder${"%.0fkm".format(lengthKm)}  $ascentPlaceholder${
            "%.0fm".format(sourceAscentMeters)
        }$timerPart"
        val spannable = SpannableString(text)
        val iconSizePx = deps.gpxFileNameText.textSize.toInt()
        ContextCompat.getDrawable(context, R.drawable.ic_distance)?.let { distanceIcon ->
            distanceIcon.setTint(Color.LTGRAY)
            distanceIcon.setBounds(0, 0, iconSizePx, iconSizePx)
            val start = text.indexOf(distancePlaceholder)
            spannable.setSpan(
                ImageSpan(distanceIcon, ImageSpan.ALIGN_CENTER),
                start,
                start + 1,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
        ContextCompat.getDrawable(context, R.drawable.ic_ascent)?.let { ascentIcon ->
            ascentIcon.setTint(Color.LTGRAY)
            ascentIcon.setBounds(0, 0, iconSizePx, iconSizePx)
            val start = text.indexOf(ascentPlaceholder)
            spannable.setSpan(
                ImageSpan(ascentIcon, ImageSpan.ALIGN_CENTER),
                start,
                start + 1,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
        if (durationText != null) ContextCompat.getDrawable(context, R.drawable.ic_timer)?.let { timerIcon ->
            timerIcon.setTint(Color.LTGRAY)
            timerIcon.setBounds(0, 0, iconSizePx, iconSizePx)
            val start = text.indexOf(timerPlaceholder)
            spannable.setSpan(
                ImageSpan(timerIcon, ImageSpan.ALIGN_CENTER),
                start,
                start + 1,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
        return spannable
    }

    private fun setSourceGpxTrack(tracks: List<GpxTrack>, latestLocation: Location?) {
        if (tracks.isEmpty()) return
        onGpxLoaded()
        sourceTracks = tracks
        gpxLoadId++
        trackStates = tracks.map { TrackState.ENABLED }
        // Total ascent of the file, shown in the header and the history. Computed in file order over all tracks,
        // so reversing, disabling or reordering tracks does not change it.
        sourceAscentMeters = cumulativeAscentMeters(tracks).lastOrNull() ?: 0.0
        // Same for the recorded duration: it is a property of the file, not of the current direction or enabled tracks.
        recordedDurationMinutes = computeRecordedDurationMinutes(tracks)
        applyGpxDirectionToCurrentTrack(zoomToTrack = true, latestLocation = latestLocation)
        onTrackChanged()
    }

    private fun applyGpxDirectionToCurrentTrack(zoomToTrack: Boolean, latestLocation: Location?) {
        if (sourceTracks.isEmpty()) return

        val directedTracks = (if (isGpxDirectionReversed) sourceTracks.asReversed() else sourceTracks)
            .filter { trackStates[it.index] != TrackState.DISABLED }
            .map {
                val isInverted = trackStates[it.index] == TrackState.INVERTED
                if (isInverted != isGpxDirectionReversed) it.copy(points = it.points.asReversed()) else it
            }

        applyGpxTrack(directedTracks, zoomToTrack)

        latestLocation?.let { location ->
            onLocationUpdate(location, shouldUpdateMapLocation = true)
        }
        onTrackChanged()
    }

    private fun isWithinDistanceOfTrack(point: GeoPoint, trackPoints: List<GeoPoint>): Boolean =
        trackPoints.any { distanceMetersBetween(point, it) <= config.pointOfInterestMaxDistanceFromTrackMeters }

    fun onZoomChanged() {
        deps.mapViewProvider()?.let { separateEndpointMarkers(it) }
    }

    private fun createEndpointMarker(map: MapView, position: GeoPoint, color: Int, label: String) = Marker(map).apply {
        this.position = position
        icon = createGpxEndpointMarkerIcon(color, label)
        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
        infoWindow = null
        setOnMarkerClickListener { _, _ -> true }
    }

    // Fans out any endpoint icons that land on the same spot, including a track's End over the next track's Begin.
    private fun separateEndpointMarkers(map: MapView) {
        val markers = endpointMarkers.flatMap { listOf(it.first, it.second) }
        val placedPx = ArrayList<Point>()
        markers.forEach { marker ->
            val sizePx = marker.icon?.intrinsicWidth?.toFloat() ?: return@forEach
            val px = map.projection.toPixels(marker.position, null)
            val overlaps = placedPx.count { hypot((px.x - it.x).toFloat(), (px.y - it.y).toFloat()) < sizePx * 0.8f }
            // Each overlap shifts the icon another 0.8 icon-widths right (anchor < 0.5 moves it right).
            marker.setAnchor(Marker.ANCHOR_CENTER - overlaps * 0.8f, Marker.ANCHOR_CENTER)
            placedPx.add(px)
        }
        map.invalidate()
    }

    private fun keepLocationMarkerOnTop(map: MapView) {
        locationMarker?.let { marker ->
            map.overlays.remove(marker)
            map.overlays.add(marker)
        }
    }

    private fun keepEndpointMarkersOnTop(map: MapView) {
        endpointMarkers.flatMap { listOf(it.first, it.second) }.forEach { marker ->
            map.overlays.remove(marker)
            map.overlays.add(marker)
        }
    }

    // Fetches water fountains and restaurants/bars inside the GPX file's bounding box in the background, then marks
    // the ones near the enabled tracks on the map. Overpass results are kept per loaded file, so reordering,
    // reversing or disabling tracks only filters them again without a new request.
    private fun loadPointsOfInterestAlongTrack(map: MapView, trackPoints: List<GpxTrackPoint>) {
        pointOfInterestMarkers.forEach { map.overlays.remove(it) }
        pointOfInterestMarkers = emptyList()
        if (!isPointsOfInterestEnabled) return

        val loadId = gpxLoadId
        val cached = if (cachedPointsOfInterestLoadId == loadId) cachedPointsOfInterest else null
        val allFilePoints = sourceTracks.flatMap { it.points }
        val bounds = BoundingBox(
            allFilePoints.maxOf { it.point.latitude },
            allFilePoints.maxOf { it.point.longitude },
            allFilePoints.minOf { it.point.latitude },
            allFilePoints.minOf { it.point.longitude }
        )

        val drawnPoints = drawnTrackPoints
        Thread {
            val all: List<Pair<GeoPoint, Int>>
            var isComplete = false
            if (cached != null) {
                all = cached
            } else {
                val fountains = OsmPointOfInterestFetcher.fetchNodes(context, "\"amenity\"=\"drinking_water\"", bounds)
                    ?.map { it.point to R.drawable.ic_water }
                val foodPlaces =
                    OsmPointOfInterestFetcher.fetchNodes(context, "\"amenity\"~\"^(restaurant|bar)\$\"", bounds)
                        ?.map { it.point to R.drawable.ic_food }
                all = fountains.orEmpty() + foodPlaces.orEmpty()
                isComplete = fountains != null && foodPlaces != null
            }
            val nearTrack = all.filter { isWithinDistanceOfTrack(it.first, drawnPoints) }
            map.post {
                // Keep complete results for this file; failed requests are retried next time.
                if (isComplete && loadId == gpxLoadId) {
                    cachedPointsOfInterestLoadId = loadId
                    cachedPointsOfInterest = all
                }
                // Ignore results if another track was loaded while the request was running.
                if (loadedTrackPoints !== trackPoints || !isPointsOfInterestEnabled) return@post
                // Remove markers from an earlier request that finished after this one started.
                pointOfInterestMarkers.forEach { map.overlays.remove(it) }

                pointOfInterestMarkers = nearTrack.map { (point, iconRes) ->
                    Marker(map).apply {
                        position = point
                        icon = ContextCompat.getDrawable(context, iconRes)
                        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                        infoWindow = null
                        setOnMarkerClickListener { _, _ -> true }
                    }
                }
                Log.log(
                    context,
                    OsmPointOfInterestFetcher.TAG,
                    "Showing ${pointOfInterestMarkers.size} points of interest within ${config.pointOfInterestMaxDistanceFromTrackMeters} m of track"
                )
                map.overlays.addAll(pointOfInterestMarkers)
                keepEndpointMarkersOnTop(map)
                keepLocationMarkerOnTop(map)
                map.invalidate()
            }
        }.start()
    }

    // Draws the track, endpoint and arrow markers, and altitude graph; runs on load or direction change.
    private fun applyGpxTrack(tracks: List<GpxTrack>, zoomToTrack: Boolean) {
        val map = deps.mapViewProvider() ?: return
        val trackPoints = tracks.flatMap { it.points }
        if (trackPoints.isEmpty()) return

        // Flat list index range of each track; no distance or ascent is counted across tracks.
        val trackFlatRanges = ArrayList<IntRange>().also { ranges ->
            var trackStart = 0
            for (track in tracks) {
                ranges.add(trackStart until trackStart + track.points.size)
                trackStart += track.points.size
            }
        }
        val trackStartIndices = trackFlatRanges.map { it.first }.toSet()
        loadedTrackPoints = trackPoints
        lastMatchIndex = null
        latestDistanceToEndKmIfNearTrack = null
        totalTrackDistanceKm = null
        val points = trackPoints.map { it.point }

        map.overlays.removeAll(gpxTrackCasingOverlays.toSet())
        map.overlays.removeAll(gpxTrackOverlays.toSet())
        // Decimated copy for drawing only; full points stay in loadedTrackPoints for distance and snapping.
        // One polyline per track so no line is drawn across the gap between tracks.
        val drawTracks = tracks.map { track ->
            val drawn = ArrayList<GeoPoint>()
            track.points.forEachIndexed { index, trackPoint ->
                if (index == 0 || index == track.points.lastIndex || distanceMetersBetween(
                        drawn.last(),
                        trackPoint.point
                    ) >= config.drawPointSpacingMeters
                ) {
                    drawn.add(trackPoint.point)
                }
            }
            drawn
        }
        val drawPoints = drawTracks.flatten()
        drawnTrackPoints = drawPoints
        Log.log(
            context,
            "GpxTrack",
            "Original GPX track points: ${trackPoints.size}, tracks: ${drawTracks.size}, drawn decimated track points: ${drawPoints.size}"
        )
        gpxTrackCasingOverlays = drawTracks.map { track ->
            Polyline().apply {
                setPoints(track)
                outlinePaint.color = config.gpxTrackCasingColor
                outlinePaint.strokeWidth = config.gpxTrackStrokeWidth + config.gpxTrackCasingExtraWidth
            }
        }
        gpxTrackOverlays = drawTracks.map { track ->
            Polyline().apply {
                setPoints(track)
                outlinePaint.color = config.gpxTrackStrokeColor
                outlinePaint.strokeWidth = config.gpxTrackStrokeWidth
            }
        }
        map.overlays.addAll(gpxTrackCasingOverlays)
        map.overlays.addAll(gpxTrackOverlays)

        endpointMarkers.forEach { (begin, end) ->
            map.overlays.remove(begin)
            map.overlays.remove(end)
        }

        // One B/E pair per track, numbered like the selector circles when the file has several tracks.
        val isMultiTrack = sourceTracks.size > 1
        endpointMarkers = tracks.map { track ->
            val suffix = if (isMultiTrack) "${track.index + 1}" else ""
            createEndpointMarker(map, track.points.first().point, config.gpxBeginMarkerColor, "B$suffix") to
                    createEndpointMarker(map, track.points.last().point, config.gpxEndMarkerColor, "E$suffix")
        }

        endpointMarkers.forEach { (begin, end) ->
            map.overlays.add(begin)
            map.overlays.add(end)
        }
        separateEndpointMarkers(map)

        loadPointsOfInterestAlongTrack(map, trackPoints)

        // Keep current-position marker above GPX overlays.
        keepLocationMarkerOnTop(map)

        val cumulativeDistancesKm = ArrayList<Double>(trackPoints.size)
        var accumulatedMeters = 0.0
        for (index in trackPoints.indices) {
            if (index > 0 && index !in trackStartIndices) {
                accumulatedMeters += distanceMetersBetween(
                    trackPoints[index - 1].point,
                    trackPoints[index].point
                )
            }
            cumulativeDistancesKm.add(accumulatedMeters / 1000.0)
        }

        cumulativeTrackDistancesKm = cumulativeDistancesKm

        // Ascent so far at each point, in riding direction over the enabled tracks; used for the remaining ascent (ETA).
        cumulativeTrackAscentMeters = cumulativeAscentMeters(tracks)
        totalTrackDistanceKm = cumulativeDistancesKm.lastOrNull()
        addDirectionArrowMarkers(map, points, cumulativeDistancesKm)

        val altitudes = trackPoints.map { it.elevationMeters }
        deps.altitudeGraphView.setAltitudes(altitudes)
        deps.altitudeGraphView.setCumulativeDistancesKm(cumulativeDistancesKm)
        deps.altitudeGraphView.setInclinationSections(
            buildInclinationSections(
                altitudes = altitudes,
                cumulativeDistancesKm = cumulativeDistancesKm,
                maxSections = config.inclinationMaxSections,
                colorForPercent = ::inclinationColor
            )
        )
        deps.altitudeGraphView.setHighlightedIndex(null)

        if (zoomToTrack) {
            // After loading a GPX, zoom map to fit that route.
            zoomToPoints(points)
        }
        map.invalidate()
    }

    private fun inclinationColor(percent: Double): Int = when {
        percent < 0 -> config.inclinationDescentColor
        percent < config.inclinationYellowFromPercent -> config.inclinationGreenColor
        percent < config.inclinationRedFromPercent -> config.inclinationYellowColor
        else -> config.inclinationRedColor
    }

    private fun parseGpxTracks(input: InputStream): List<GpxTrack> {
        val tracks = ArrayList<GpxTrack>()
        var trackPoints = ArrayList<GpxTrackPoint>()
        var trackName: String? = null
        var isInsideTrack = false
        fun finishTrack() {
            if (trackPoints.isNotEmpty()) tracks.add(GpxTrack(tracks.size, trackName, trackPoints))
            trackPoints = ArrayList()
            trackName = null
        }

        val parser = Xml.newPullParser().apply {
            setInput(input, null)
        }

        var currentPointTag: String? = null
        var currentLat: Double? = null
        var currentLon: Double? = null
        var currentElevation: Double? = null
        var currentTimeMillis: Long? = null

        var eventType = parser.eventType
        while (eventType != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
            when (eventType) {
                org.xmlpull.v1.XmlPullParser.START_TAG -> {
                    when (parser.name) {
                        "trk", "rte" -> {
                            finishTrack()
                            isInsideTrack = true
                        }

                        "name" -> {
                            if (isInsideTrack && currentPointTag == null && trackName == null) {
                                trackName = parser.nextText().trim()
                            }
                        }

                        "trkpt", "rtept" -> {
                            currentPointTag = parser.name
                            currentLat = parser.getAttributeValue(null, "lat")?.toDoubleOrNull()
                            currentLon = parser.getAttributeValue(null, "lon")?.toDoubleOrNull()
                            currentElevation = null
                            currentTimeMillis = null
                        }

                        "ele" -> {
                            if (currentPointTag != null) {
                                currentElevation = parser.nextText().toDoubleOrNull()
                            }
                        }

                        "time" -> {
                            if (currentPointTag != null) {
                                currentTimeMillis = runCatching {
                                    java.time.Instant.parse(parser.nextText().trim()).toEpochMilli()
                                }.getOrNull()
                            }
                        }
                    }
                }

                org.xmlpull.v1.XmlPullParser.END_TAG -> {
                    if (currentPointTag != null && parser.name == currentPointTag) {
                        val lat = currentLat
                        val lon = currentLon
                        if (lat != null && lon != null) {
                            trackPoints.add(
                                GpxTrackPoint(
                                    point = GeoPoint(lat, lon),
                                    elevationMeters = currentElevation,
                                    timeMillis = currentTimeMillis
                                )
                            )
                        }
                        currentPointTag = null
                        currentLat = null
                        currentLon = null
                        currentElevation = null
                        currentTimeMillis = null
                    }
                }
            }
            eventType = parser.next()
        }
        finishTrack()
        return tracks
    }

    private fun zoomToPoints(points: List<GeoPoint>) {
        val map = deps.mapViewProvider() ?: return
        if (points.isEmpty()) return

        var minLat = Double.POSITIVE_INFINITY
        var maxLat = Double.NEGATIVE_INFINITY
        var minLon = Double.POSITIVE_INFINITY
        var maxLon = Double.NEGATIVE_INFINITY

        for (point in points) {
            if (point.latitude < minLat) minLat = point.latitude
            if (point.latitude > maxLat) maxLat = point.latitude
            if (point.longitude < minLon) minLon = point.longitude
            if (point.longitude > maxLon) maxLon = point.longitude
        }

        val hasArea = maxLat > minLat || maxLon > minLon
        if (!hasArea) {
            map.controller.setCenter(points.first())
            map.controller.setZoom(deps.currentMapStyleMaxZoomProvider().toDouble())
            return
        }

        val bounds = BoundingBox(maxLat, maxLon, minLat, minLon)
        fitBoundsWhenMapIsReady(bounds)
    }

    private fun fitBoundsWhenMapIsReady(bounds: BoundingBox, attemptsLeft: Int = 10) {
        val map = deps.mapViewProvider() ?: return

        if (map.width > 0 && map.height > 0) {
            // Overview centers the whole route in the screen center (no follow offset).
            map.setMapCenterOffset(0, 0)
            val borderPx = (minOf(map.width, map.height) * 0.08f).toInt().coerceAtLeast(0)
            map.zoomToBoundingBox(bounds, true, borderPx)
            return
        }

        if (attemptsLeft <= 0) return
        map.post {
            fitBoundsWhenMapIsReady(bounds, attemptsLeft - 1)
        }
    }


    // Finds the GPX point that matches the current location, following the rider's progress so that
    // laps of a loop and the two legs of an out-and-back route are not confused.
    // 1. Search a window around the previous match (200 m behind to 2 km ahead).
    // 2. Otherwise search the rest of the track ahead of that window, then the part behind it.
    // 3. Otherwise (off-track) return the nearest point without remembering it.
    // Returns the point index in loadedTrackPoints and its distance (meters).
    private fun nearestTrackMatch(location: Location): TrackMatch? {
        if (loadedTrackPoints.isEmpty()) return null
        val windowBehindKm = 0.2
        val windowAheadKm = 2.0
        val distancesKm = cumulativeTrackDistancesKm
        val last = lastMatchIndex?.takeIf {
            it in loadedTrackPoints.indices && distancesKm.size == loadedTrackPoints.size
        }

        val match = if (last != null) {
            var from = last
            while (from > 0 && distancesKm[last] - distancesKm[from - 1] <= windowBehindKm) from--
            var to = last
            while (to < loadedTrackPoints.lastIndex && distancesKm[to + 1] - distancesKm[last] <= windowAheadKm) to++
            firstPassMatch(location, from, to)
                ?: firstPassMatch(location, to + 1, loadedTrackPoints.lastIndex)
                ?: firstPassMatch(location, 0, from - 1)
        } else {
            firstPassMatch(location, 0, loadedTrackPoints.lastIndex)
        }

        if (match != null) {
            lastMatchIndex = match.index
            return match
        }
        return nearestPointMatch(location)
    }

    // Among points from..to, finds the first pass (consecutive points within the near-track threshold)
    // and returns its closest point. Null when no point in the range is near.
    private fun firstPassMatch(location: Location, from: Int, to: Int): TrackMatch? {
        var best: TrackMatch? = null
        for (index in from..to) {
            val distance = distanceToTrackPoint(location, index)
            if (isNearGpxTrack(distance)) {
                if (best == null || distance < best.distanceMeters) best = TrackMatch(index, distance)
            } else if (best != null) {
                break // the first pass has ended
            }
        }
        return best
    }

    private fun nearestPointMatch(location: Location): TrackMatch? {
        var best: TrackMatch? = null
        for (index in loadedTrackPoints.indices) {
            val distance = distanceToTrackPoint(location, index)
            if (best == null || distance < best.distanceMeters) best = TrackMatch(index, distance)
        }
        return best
    }

    private val distanceResult = FloatArray(1)

    private fun distanceToTrackPoint(location: Location, index: Int): Float {
        val point = loadedTrackPoints[index].point
        Location.distanceBetween(location.latitude, location.longitude, point.latitude, point.longitude, distanceResult)
        return distanceResult[0]
    }

    private fun isNearGpxTrack(distanceMeters: Float): Boolean {
        return distanceMeters <= config.nearGpxTrackThresholdMeters
    }

    private fun nearestTrackIndexWithAltitude(startIndex: Int): Int? {
        if (startIndex !in loadedTrackPoints.indices) return null
        if (loadedTrackPoints[startIndex].elevationMeters != null) return startIndex

        var offset = 1
        while (startIndex - offset >= 0 || startIndex + offset < loadedTrackPoints.size) {
            val left = startIndex - offset
            if (left >= 0 && loadedTrackPoints[left].elevationMeters != null) {
                return left
            }

            val right = startIndex + offset
            if (right < loadedTrackPoints.size && loadedTrackPoints[right].elevationMeters != null) {
                return right
            }
            offset++
        }
        return null
    }

    private fun distanceMetersBetween(from: GeoPoint, to: GeoPoint): Float {
        val distanceResult = FloatArray(1)
        Location.distanceBetween(
            from.latitude,
            from.longitude,
            to.latitude,
            to.longitude,
            distanceResult
        )
        return distanceResult[0]
    }

    // Running total of ascent (meters) at every point of the given tracks, in order. Sums each positive elevation
    // step between consecutive points; points without elevation are skipped. Nothing is counted across the gap
    // between two tracks. Raw GPS altitude is not smoothed, so noise can inflate the ascent.
    private fun cumulativeAscentMeters(tracks: List<GpxTrack>): List<Double> {
        val result = ArrayList<Double>(tracks.sumOf { it.points.size })
        var ascent = 0.0
        for (track in tracks) {
            var previousElevation: Double? = null
            for (point in track.points) {
                val elevation = point.elevationMeters
                val previous = previousElevation
                if (elevation != null && previous != null && elevation > previous) ascent += elevation - previous
                previousElevation = elevation
                result.add(ascent)
            }
        }
        return result
    }

    // Elapsed time between the earliest and latest timestamp of each track, summed over all tracks. This is recorded
    // time (stops included), not moving time. Using min/max makes it independent of point order, so it is the same
    // for an inverted track. Tracks with fewer than two timestamps count as 0; null when no track has any timestamp.
    private fun computeRecordedDurationMinutes(tracks: List<GpxTrack>): Long? {
        var totalMillis = 0L
        var hasTimestamps = false
        for (track in tracks) {
            val times = track.points.mapNotNull { it.timeMillis }
            if (times.isEmpty()) continue
            hasTimestamps = true
            totalMillis += times.max() - times.min()
        }
        return if (hasTimestamps) totalMillis / 60000 else null
    }

    private fun createPositionMarkerIcon(): BitmapDrawable {
        val sizePx = dpToPx(config.positionMarkerSizeDp).coerceAtLeast(24)
        val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        val center = sizePx / 2f
        val radius = sizePx * 0.40f

        val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = config.positionMarkerColor
        }
        val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = Color.WHITE
            strokeWidth = sizePx * 0.08f
        }
        val trianglePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = Color.WHITE
        }

        canvas.drawCircle(center, center, radius, fillPaint)
        canvas.drawCircle(center, center, radius, borderPaint)

        val triHalfWidth = radius * 0.35f
        val triTopY = center - radius * 0.42f
        val triBottomY = center + radius * 0.28f
        val triangle = Path().apply {
            moveTo(center, triTopY)
            lineTo(center + triHalfWidth, triBottomY)
            lineTo(center - triHalfWidth, triBottomY)
            close()
        }
        canvas.drawPath(triangle, trianglePaint)

        return BitmapDrawable(context.resources, bitmap)
    }

    private fun addDirectionArrowMarkers(map: MapView, points: List<GeoPoint>, cumulativeDistancesKm: List<Double>) {
        directionArrowMarkers.forEach { map.overlays.remove(it) }
        val totalKm = cumulativeDistancesKm.last()
        if (totalKm <= 0.0) return
        // Evenly spaced arrows, none at the start or end of the track.
        val intervalKm = totalKm / (config.maxDirectionArrowMarkers + 1)
        val markers = ArrayList<Marker>()
        var targetKm = intervalKm
        var index = 1
        repeat(config.maxDirectionArrowMarkers) {
            while (index < points.lastIndex && cumulativeDistancesKm[index] < targetKm) index++
            markers.add(Marker(map).apply {
                position = points[index]
                // The chevron is pre-rotated to the track bearing. Flat markers then turn with the map.
                icon = createDirectionArrowIcon(points[index - 1].bearingTo(points[index]).toFloat())
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                setFlat(true)
                infoWindow = null
                setOnMarkerClickListener { _, _ -> true }
            })
            targetKm += intervalKm
        }
        directionArrowMarkers = markers
        map.overlays.addAll(markers)
        keepEndpointMarkersOnTop(map)
        keepLocationMarkerOnTop(map)
    }

    private fun createDirectionArrowIcon(bearingDegrees: Float): BitmapDrawable {
        val sizePx = dpToPx(config.directionArrowSizeDp).coerceAtLeast(12)
        val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = Color.argb(90, 0, 0, 0)
            strokeWidth = sizePx * 0.40f
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        val chevronPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = Color.WHITE
            strokeWidth = sizePx * 0.24f
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        val chevron = Path().apply {
            moveTo(sizePx * 0.2f, sizePx * 0.65f)
            lineTo(sizePx * 0.5f, sizePx * 0.35f)
            lineTo(sizePx * 0.8f, sizePx * 0.65f)
        }
        canvas.save()
        canvas.rotate(bearingDegrees, sizePx / 2f, sizePx / 2f)
        canvas.drawPath(chevron, shadowPaint)
        canvas.drawPath(chevron, chevronPaint)
        canvas.restore()

        return BitmapDrawable(context.resources, bitmap)
    }

    private fun createGpxEndpointMarkerIcon(fillColor: Int, label: String): BitmapDrawable {
        val sizePx = dpToPx(config.gpxEndpointMarkerSizeDp).coerceAtLeast(18)
        val shadowPaddingPx = dpToPx(2).coerceAtLeast(2)
        val bitmapSizePx = sizePx + shadowPaddingPx * 2
        val bitmap = Bitmap.createBitmap(bitmapSizePx, bitmapSizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        val center = bitmapSizePx / 2f
        val radius = sizePx * 0.38f

        val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = fillColor
            setShadowLayer(shadowPaddingPx * 0.75f, 0f, shadowPaddingPx * 0.4f, Color.argb(128, 0, 0, 0))
        }
        val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = Color.WHITE
            strokeWidth = sizePx * 0.11f
        }
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textAlign = Paint.Align.CENTER
            textSize = sizePx * 0.50f
            typeface = Typeface.DEFAULT_BOLD
        }

        canvas.drawCircle(center, center, radius, fillPaint)
        canvas.drawCircle(center, center, radius, borderPaint)

        val textBaselineY = center - (textPaint.ascent() + textPaint.descent()) / 2f
        canvas.drawText(label, center, textBaselineY, textPaint)

        return BitmapDrawable(context.resources, bitmap)
    }

    private fun dpToPx(dp: Int): Int = (dp * context.resources.displayMetrics.density).toInt()
}

// Descents are not subtracted, so the estimate is conservative on downhill-heavy routes.
object TimeToDestinationEstimation {
    fun estimateMinutes(
        remainingKm: Double,
        remainingAscentMeters: Double,
        flatSpeedKmh: Double,
        climbMetersPerHour: Double
    ): Long {
        val hours = remainingKm / flatSpeedKmh + remainingAscentMeters / climbMetersPerHour
        return (hours * 60.0).roundToLong()
    }
}
