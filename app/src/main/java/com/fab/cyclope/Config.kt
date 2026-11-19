package com.fab.cyclope

import android.graphics.Color

data class AppConfig(
    val runtime: RuntimeConfig,
    val mapRotation: MapRotationConfig,
    val system: SystemConfig,
    val layout: LayoutConfig,
    val gpx: UiGpxTrackManager.Config,
    val rider: RiderConfig,
    val gpxHistory: GpxHistoryStore.Config,
    val settingsPanel: UiSettingsPanel.Config,
    val largeNumbers: UiLargeNumbersPanel.Config,
    val weather: UiWeatherPanel.Config,
    val scaleLegend: UiMapScaleLegend.Config
)

data class RuntimeConfig(
    val locationRequestCode: Int,
    val gpsUpdateIntervalMs: Long,
    val uiRefreshIntervalMs: Long,
    val mapUpdateMinDistanceMeters: Float,
    val lastKnownLocationMaxAgeMs: Long
)

data class MapRotationConfig(
    val minMovementMeters: Float,
    val minSpeedKmh: Float,
    val animationIntervalMs: Long,
    val manualRotationAutofollowPauseMs: Long,
    val followPauseAfterTouchOrLoadMs: Long,
    val animationFollowFactor: Float,
    val animationMaxStepDegrees: Float,
    val snapEpsilonDegrees: Float
)

data class RiderConfig(
    val flatSpeedKmh: Double,      // Average speed on flat terrain used for time-to-destination.
    val climbMetersPerHour: Double // Ascent rate used for time-to-destination.
)

data class SystemConfig(
    val brightness100Percent: Int,
    val osmContact: String
)

data class LayoutConfig(
    val backgroundColor: Int,
    val mapOverlayBackgroundColor: Int,
    val gpxFileNameTextSizeSp: Float,
    val weatherTextPaddingHorizontalDp: Int,
    val weatherTextPaddingVerticalDp: Int,
    val outerPaddingDp: Int,
    val sectionGapDp: Int,
    val altitudeGraphHeightDp: Int,
    val bigNumbersHeightDp: Int,
    val mapZoomButtonsBottomMarginDp: Int,
    val contentBottomMarginDp: Int,
    val defaultGpxAssetFile: String
)

object AppConfigs {
    private val backgroundColor = Color.parseColor("#000000") // black
    private val controlSurfaceColor = Color.parseColor("#1A1A1A") // dark gray

    val Default = AppConfig(
        runtime = RuntimeConfig(
            locationRequestCode = 1001,
            gpsUpdateIntervalMs = 2000L,
            uiRefreshIntervalMs = 1000L,
            mapUpdateMinDistanceMeters = 1f, // try to set this to 25f to understand if doing so the device actually consumes less power.
            lastKnownLocationMaxAgeMs = 5 * 60 * 1000L // At startup, the last known GPS position is ignored if older than this (5 min).
        ),
        mapRotation = MapRotationConfig(
            minMovementMeters = 2.5f, // Rotation target is updated only after this much movement (filters GPS jitter).
            minSpeedKmh = 2.0f, // Ignore heading updates at very low speed to avoid stationary jitter rotation.
            animationIntervalMs = 150L, // Animation tick for smooth rotation toward target heading.
            manualRotationAutofollowPauseMs = 5000L, // After a manual 2-finger rotate, temporarily pause auto-rotation.
            followPauseAfterTouchOrLoadMs = 10000L, // After a map touch or a GPX load, pause map centering and rotation for this long.
            animationFollowFactor = 0.18f, // Follow factor: higher = faster reaction, lower = smoother/slower.
            animationMaxStepDegrees = 6f, // Hard cap on degrees changed per animation tick.
            snapEpsilonDegrees = 0.5f // If very close to target, snap directly to avoid micro-oscillations.
        ),
        system = SystemConfig(
            brightness100Percent = 255,
            osmContact = "contact: cyclope@email.com"
        ),
        layout = LayoutConfig(
            backgroundColor = backgroundColor,
            mapOverlayBackgroundColor = Color.argb(190, 0, 0, 0), // black slidely transparent
            gpxFileNameTextSizeSp = 16f,
            weatherTextPaddingHorizontalDp = 6,
            weatherTextPaddingVerticalDp = 2,
            outerPaddingDp = 8,
            sectionGapDp = 7,
            altitudeGraphHeightDp = 120,
            bigNumbersHeightDp = 105,
            mapZoomButtonsBottomMarginDp = 13,
            contentBottomMarginDp = 6,
            defaultGpxAssetFile = "Barcelona 3 tracks roadbike.gpx"
        ),
        gpx = UiGpxTrackManager.Config(
            gpxPickerDownloadsUri = "content://com.android.providers.downloads.documents/root/downloads",
            gpxTrackStrokeWidth = 29f,
            gpxTrackStrokeColor = Color.rgb(120, 190, 255),
            gpxTrackCasingColor = Color.argb(153, 13, 30, 51), // dark navy outline around the track
            gpxTrackCasingExtraWidth = 9f, // total extra width (px) of the outline around the track stroke
            gpxBeginMarkerColor = Color.parseColor("#2ECC71"), // green
            gpxEndMarkerColor = Color.parseColor("#E74C3C"),   // red
            positionMarkerSizeDp = 36,
            positionMarkerColor = Color.rgb(120, 190, 255),    // light blue
            gpxEndpointMarkerSizeDp = 26,
            maxDirectionArrowMarkers = 55, // Number of white direction arrows drawn evenly along the GPX track.
            directionArrowSizeDp = 15, // Size of the direction arrow icon.
            nearGpxTrackThresholdMeters = 250f, // distance used to start considering you on the gpx. Used for marker snapping,
            // altitude highlight, to-finish distance and progress bar. some GPX tracks have points that can be more than 250m
            // apart, if that is the case, the app will show an unknown arrival time for a brief time.
            snapPositionOnGpxTrackWhenNear = false,
            userMarkerScreenYRatio = 0.72f, // Keep user marker a little below the lower third while following position.
            pointOfInterestMaxDistanceFromTrackMeters = 480f, // Points of interest farther than this amount from GPX track are not shown.
            drawPointSpacingMeters = 2f, // Min distance between drawn track points; higher = faster to draw on screen but coarser, lower = smoother but slower.
            inclinationMaxSections = 80, // Max colored altitude sections, split where the profile deviates most from a straight line (peaks and valleys first).
            inclinationYellowFromPercent = 3.0, // Absolute gradient from which a section is yellow.
            inclinationRedFromPercent = 5.4, // Absolute gradient from which a section is red.
            inclinationGreenColor = Color.argb(120, 46, 204, 113),
            inclinationYellowColor = Color.argb(160, 255, 214, 0),
            inclinationRedColor = Color.argb(160, 255, 23, 68),
            inclinationDescentColor = Color.argb(80, 200, 200, 200) // neutral gray for descents
        ),
        rider = RiderConfig(
            flatSpeedKmh = 29.0,
            climbMetersPerHour = 920.0
        ),
        gpxHistory = GpxHistoryStore.Config(
            maxEntries = 20, // Number of recently opened GPX files kept in the recent list.
            dialogWidthFraction = 0.9f, // Recent GPX dialog width as a fraction of the screen width.
            dialogMaxHeightFraction = 0.7f // Recent GPX dialog maximum height as a fraction of the screen height. Longer lists scroll.
        ),
        settingsPanel = UiSettingsPanel.Config(
            animationDurationMs = 180L,
            minVisibleHandleHeightDp = 1,
            backgroundColor = backgroundColor,
            controlSurfaceColor = controlSurfaceColor,
            showDebugStatusSection = true,
            settingsPanelVerticalGapDp = 18,
            settingsPanelHandleVerticalPaddingDp = 12,
            batteryCurrentSmoothingWindowSamples = 65,
            batteryCurrentSampleIntervalMs = 1000L
        ),
        largeNumbers = UiLargeNumbersPanel.Config(
            controlSurfaceColor = controlSurfaceColor,
            bigNumbersTextSizeSp = 56f,
            unitSizeRatio = 0.25f,
            columnGapDp = 4,
            smoothingWindowSize = 3 // Speed and altitude are averaged over this many GPS fixes (3 fixes = ~6 s at 2 s per fix).
        ),
        weather = UiWeatherPanel.Config(
            weatherTextSizeSp = 16f,
            refreshIntervalSeconds = 19 * 60L, // 19 minutes
            retryOnFailureIntervalSeconds = 2 * 60L // 2 minutes
        ),
        scaleLegend = UiMapScaleLegend.Config(
            // Length of the white scale line, in screen pixels (not dp). The legend picks the round distance
            // (10 m … 1000 km) whose line is closest to targetLinePx without exceeding maxLinePx.
            minLinePx = 60.0, // shortest line it aims for
            maxLinePx = 240.0, // longest line, sets the widest legend (250 px = 89 dp on a 450 dpi phone)
            targetLinePx = 240.0 // preferred line length; also used when tapping the legend to zoom
        )
    )
}
