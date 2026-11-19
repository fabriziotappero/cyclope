package com.fab.cyclope

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter

import android.content.pm.PackageManager

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.Uri

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapListener
import org.osmdroid.events.ScrollEvent
import org.osmdroid.events.ZoomEvent
import org.osmdroid.tileprovider.tilesource.XYTileSource
import org.osmdroid.util.MapTileIndex
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.gestures.RotationGestureOverlay
import java.io.File
import java.util.Locale
import kotlin.math.abs

class Main : AppCompatActivity() {

    private val appConfig = AppConfigs.Default

    private enum class ArcGisStyle(
        val label: String,
        val serviceName: String,
        val maxZoom: Int
    ) {
        STREET("Street", "World_Street_Map", 23),
        DARK("Dark Mode", "Canvas/World_Dark_Gray_Base", 16),
        TOPO("Climbs & Topography", "World_Topo_Map", 23),
        IMAGERY("Satellite", "World_Imagery", 23),
        TERRAIN("Terrain", "World_Terrain_Base", 13),
        RELIEF("Relief & Slopes", "World_Shaded_Relief", 13),
        NAT_GEO("Trails by NatGeo", "NatGeo_World_Map", 16),
    }

    private var currentMapStyle: ArcGisStyle = ArcGisStyle.STREET
    private lateinit var gpxFileNameText: TextView
    private lateinit var weatherText: TextView
    private lateinit var mapScaleLegend: UiMapScaleLegend
    private lateinit var weatherPanel: UiWeatherPanel
    private lateinit var gpxTrackSelector: UiGpxTrackSelector
    private lateinit var largeNumbersPanel: UiLargeNumbersPanel
    private lateinit var locationManager: LocationManager
    private var locationListener: LocationListener? = null
    private var locationUpdatesStartedAtMs = 0L
    private var hasReceivedFirstFix = false
    private val gpsStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            // GPS was off at startup, so no listener exists yet. Start updates once GPS is turned on.
            if (locationListener == null && hasLocationPermission() &&
                locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)
            ) {
                startLocationUpdates()
            }
        }
    }
    private var latestLocation: Location? = null

    private var mapView: MapView? = null
    private lateinit var altitudeGraphView: UiAltitudeGraphView
    private lateinit var gpxProgressBar: UiGpxProgressBar
    private lateinit var gpxTrackManager: UiGpxTrackManager
    private var lastMapUpdateLocation: Location? = null
    private var lastOrientationLocation: Location? = null
    private var targetMapOrientationDegrees = 0f
    private var isMapRotationSmoothingRunning = false
    private var manualRotationPauseUntilMs = 0L
    private var followPausedUntilMs = 0L
    private var lastWeatherRequestTimeMs = 0L
    private var isWeatherRequestInFlight = false
    private var wasLastWeatherRequestSuccessful = true

    private val mainHandler = Handler(Looper.getMainLooper())
    private var hasRequestedWriteSettings = false
    // Brightness chosen with the system slider while Cyclope was on screen; reused when Cyclope comes back.
    private var cyclopeBrightnessLevel: Int? = null
    private val brightnessPreferences by lazy { getSharedPreferences("cyclope_brightness", Context.MODE_PRIVATE) }

    private lateinit var settingsPanel: UiSettingsPanel


    private val pickGpxLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode != Activity.RESULT_OK) return@registerForActivityResult
            val gpxUri = result.data?.data ?: return@registerForActivityResult

            if (::settingsPanel.isInitialized) {
                settingsPanel.setExpanded(expanded = false, animate = false)
            }
            if (::gpxTrackManager.isInitialized) {
                gpxTrackManager.loadGpxTrackFromUri(gpxUri, latestLocation)
            }
        }

    private val uiRefreshRunnable = object : Runnable {
        override fun run() {
            settingsPanel.setStatus()
            if (::largeNumbersPanel.isInitialized) {
                largeNumbersPanel.refresh()
            }
            mainHandler.postDelayed(this, appConfig.runtime.uiRefreshIntervalMs)
        }
    }

    private val mapRotationSmoothingRunnable = object : Runnable {
        override fun run() {
            val keepRunning = applySmoothMapRotationStep()
            if (keepRunning) {
                mainHandler.postDelayed(this, appConfig.mapRotation.animationIntervalMs)
            } else {
                isMapRotationSmoothingRunning = false
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        configureOsmForScopedStorage()

        val root = FrameLayout(this).apply {
            setBackgroundColor(appConfig.layout.backgroundColor)
            keepScreenOn = true
        }

        val contentRoot = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(appConfig.layout.backgroundColor)
            val outerPaddingPx = dpToPx(appConfig.layout.outerPaddingDp)
            setPadding(outerPaddingPx, outerPaddingPx, outerPaddingPx, outerPaddingPx)
        }

        val sectionGapPx = dpToPx(appConfig.layout.sectionGapDp)
        val availableStyles = ArcGisStyle.entries.toTypedArray()

        // navigation map
        mapView = MapView(this).apply {
            id = View.generateViewId()
            setTileSource(arcGisTileSource(currentMapStyle))
            // Scale 256px tiles to device DPI so map features (roads/labels) appear larger.
            setTilesScaledToDpi(true)
            setMultiTouchControls(true)

            // Enable map rotation with a 2-finger gesture.
            overlays.add(RotationGestureOverlay(this).apply { isEnabled = true })

            // If user rotates manually, pause auto-rotation for a few seconds
            // so both behaviors do not fight each other.
            setOnTouchListener { _, event ->
                pauseMapFollowing()
                if (event.pointerCount >= 2) {
                    manualRotationPauseUntilMs =
                        System.currentTimeMillis() + appConfig.mapRotation.manualRotationAutofollowPauseMs
                    if (isMapRotationSmoothingRunning) {
                        mainHandler.removeCallbacks(mapRotationSmoothingRunnable)
                        isMapRotationSmoothingRunning = false
                    }
                    targetMapOrientationDegrees = normalizeBearingDegrees(mapOrientation)
                }
                false
            }

            zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
            zoomController.display.setAdditionalPixelMargins(
                0f,
                0f,
                0f,
                dpToPx(appConfig.layout.mapZoomButtonsBottomMarginDp).toFloat()
            )

            // Default startup zoom: max for current style (unless GPX fit overrides it).
            controller.setZoom(currentMapStyle.maxZoom.toDouble())

            // Keep user marker anchored while zooming by re-centering on latest position.
            addMapListener(object : MapListener {
                override fun onScroll(event: ScrollEvent?): Boolean {
                    if (::mapScaleLegend.isInitialized) {
                        mapScaleLegend.update()
                    }
                    return false
                }

                override fun onZoom(event: ZoomEvent?): Boolean {
                    if (::gpxTrackManager.isInitialized) {
                        gpxTrackManager.recenterOnLastPoint()
                        gpxTrackManager.onZoomChanged()
                    }
                    if (::mapScaleLegend.isInitialized) {
                        mapScaleLegend.update()
                    }
                    return false
                }
            })
        }

        // text for GPX name. This is
        gpxFileNameText = TextView(this).apply {
            setTextColor(Color.LTGRAY)
            textSize = appConfig.layout.gpxFileNameTextSizeSp
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.BLACK)
            // this padding and the size of the text decide the height of this sec
            setPadding(0, dpToPx(5), 0, 0)
            isSingleLine = false // name and stats are on separate lines
            ellipsize = null
            setHorizontallyScrolling(true)
            text = ""
            ContextCompat.getDrawable(context, R.drawable.ic_download)?.let { pathIcon ->
                pathIcon.setTint(Color.LTGRAY)
                val iconSizePx = (textSize * 1.9f).toInt()
                pathIcon.setBounds(0, 9, iconSizePx, iconSizePx + 9) // 9px down to center with two lines of text
                setCompoundDrawablesRelative(pathIcon, null, null, null)
            }
            compoundDrawablePadding = dpToPx(4)
        }

        val gpxHistoryStore = GpxHistoryStore(this, appConfig.gpxHistory)
        // Only the path icon on the left opens the recent GPX list.
        gpxFileNameText.setOnTouchListener { _, event ->
            val isIconTouch = event.x < gpxFileNameText.compoundPaddingLeft
            if (isIconTouch && event.action == MotionEvent.ACTION_DOWN) {
                gpxFileNameText.setBackgroundColor(Color.DKGRAY) // brighten the bar while pressed
            }
            if (event.action == MotionEvent.ACTION_UP || event.action == MotionEvent.ACTION_CANCEL) {
                gpxFileNameText.setBackgroundColor(Color.BLACK)
            }
            if (isIconTouch && event.action == MotionEvent.ACTION_UP) {
                UiGpxHistoryDialog(
                    this,
                    gpxHistoryStore,
                    onOpenFileRequested = { pickGpxLauncher.launch(gpxTrackManager.createGpxPickerIntent()) }
                ) { uri ->
                    if (::gpxTrackManager.isInitialized) {
                        gpxTrackManager.loadGpxTrackFromUri(uri, latestLocation)
                    }
                }.show()
            }
            isIconTouch
        }

        // text for temperature and rain probability
        weatherText = TextView(this).apply {
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dpToPx(6).toFloat()
                setColor(appConfig.layout.mapOverlayBackgroundColor)
                setStroke(dpToPx(1), Color.argb(90, 255, 255, 255)) // thin outline ring for contrast on the map
            }
            textSize = appConfig.weather.weatherTextSizeSp
            setShadowLayer(4f, 0f, 0f, Color.BLACK)
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            setPadding(
                dpToPx(appConfig.layout.weatherTextPaddingHorizontalDp),
                dpToPx(appConfig.layout.weatherTextPaddingVerticalDp),
                dpToPx(appConfig.layout.weatherTextPaddingHorizontalDp),
                dpToPx(appConfig.layout.weatherTextPaddingVerticalDp)
            )
            text = ""
            visibility = View.GONE
        }

        mapScaleLegend = UiMapScaleLegend(
            context = this,
            config = appConfig.scaleLegend,
            mapViewProvider = { mapView },
            latestLatitudeProvider = { latestLocation?.latitude },
            currentMapStyleMaxZoomProvider = { currentMapStyle.maxZoom },
            overlayBackgroundColor = appConfig.layout.mapOverlayBackgroundColor,
            onTapped = { pauseMapFollowing() }
        )

        weatherPanel = UiWeatherPanel(this, appConfig.weather)

        gpxTrackSelector = UiGpxTrackSelector(
            this,
            onTrackTapped = { index -> gpxTrackManager.cycleTrackState(index, latestLocation) },
            onTrackDoubleTapped = { index -> gpxTrackManager.toggleTrackDisabled(index, latestLocation) },
            onTrackMoved = { from, to -> gpxTrackManager.moveTrack(from, to, latestLocation) },
            onInteracted = { pauseMapFollowing() }
        )

        val mapContainer = FrameLayout(this).apply {
            clipChildren = false
            clipToPadding = false
            addView(
                mapView,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            )
            addView(
                weatherText,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    Gravity.END or Gravity.TOP
                ).apply {
                    topMargin = dpToPx(8)
                    marginEnd = dpToPx(8)
                }
            )
            addView(
                gpxTrackSelector.view,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    Gravity.START or Gravity.BOTTOM
                ).apply {
                    marginStart = dpToPx(6)
                    bottomMargin = dpToPx(6)
                }
            )
            addView(
                mapScaleLegend.view,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    Gravity.END or Gravity.BOTTOM
                ).apply {
                    marginEnd = dpToPx(6)
                    bottomMargin = dpToPx(6)
                }
            )
        }

        // altitude graph
        altitudeGraphView = UiAltitudeGraphView(this)
        altitudeGraphView.onDoubleTap = {
            pauseMapFollowing()
            // Show the whole route with north up, like a fresh load.
            mapView?.mapOrientation = 0f
            targetMapOrientationDegrees = 0f
            gpxTrackManager.zoomToWholeTrack()
        }
        gpxProgressBar = UiGpxProgressBar(this)

        gpxTrackManager = UiGpxTrackManager(
            context = this,
            deps = UiGpxTrackManager.Deps(
                mapViewProvider = { mapView },
                altitudeGraphView = altitudeGraphView,
                gpxFileNameText = gpxFileNameText,
                currentMapStyleMaxZoomProvider = { currentMapStyle.maxZoom },
                isMapFollowPausedProvider = { isMapFollowPaused() }
            ),
            config = appConfig.gpx,
            rider = appConfig.rider,
            gpxHistoryStore = gpxHistoryStore,
            onGpxLoaded = {
                pauseMapFollowing()
                // Show the loaded track with north up.
                mapView?.mapOrientation = 0f
                targetMapOrientationDegrees = 0f
            },
            onTrackChanged = {
                if (::gpxTrackSelector.isInitialized) {
                    gpxTrackSelector.update(gpxTrackManager.trackStates())
                }
                if (::largeNumbersPanel.isInitialized) {
                    largeNumbersPanel.refresh()
                }
                if (::gpxProgressBar.isInitialized) {
                    updateGpxProgressBar()
                }
            }
        )

        // main large number section
        largeNumbersPanel = UiLargeNumbersPanel(
            context = this,
            config = appConfig.largeNumbers,
            distanceKmProvider = { gpxTrackManager.latestDistanceToGpxEndKmIfNearTrack() }
        )
        val bigNumbersRow = largeNumbersPanel.view

        settingsPanel = UiSettingsPanel(
            context = this,
            config = appConfig.settingsPanel,
            isReverseGpxDirection = false,
            isPimpMyGpxTracks = true,
            mapStyleLabels = availableStyles.map { it.label },
            appVersionBuildText = "Cyclope v${BuildConfig.VERSION_NAME} Build ${BuildConfig.BUILD_TIME_UTC}",
            onMapStyleSelected = { position ->
                applyMapStyle(availableStyles[position])
            },
            onReverseGpxDirectionChanged = { isChecked ->
                gpxTrackManager.setDirectionReversed(
                    reversed = isChecked,
                    zoomToTrack = false,
                    latestLocation = latestLocation
                )
            },
            onPimpMyGpxTracksChanged = { isChecked ->
                gpxTrackManager.setPointsOfInterestEnabled(isChecked)
            }
        )
        val slidingSettingsPanel = settingsPanel.view

        // LET'S COMPOSE THE UI

        contentRoot.addView(
            mapContainer,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        )

        contentRoot.addView(
            gpxFileNameText,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )

        contentRoot.addView(
            altitudeGraphView,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dpToPx(appConfig.layout.altitudeGraphHeightDp)
            ).apply {
                topMargin = sectionGapPx
                bottomMargin = sectionGapPx
            }
        )

        contentRoot.addView(
            gpxProgressBar.view,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                //topMargin = sectionGapPx
            }
        )

        contentRoot.addView(
            bigNumbersRow,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dpToPx(appConfig.layout.bigNumbersHeightDp)
            ).apply {
                topMargin = sectionGapPx
                bottomMargin = sectionGapPx + dpToPx(6)
            }
        )

        root.addView(
            contentRoot,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            ).apply {
                bottomMargin = dpToPx(appConfig.layout.contentBottomMarginDp)
            }
        )

        root.addView(
            slidingSettingsPanel,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM
            )
        )

        setContentView(root)

        mapView?.post { mapScaleLegend.update() }

        largeNumbersPanel.refresh()
        updateGpxProgressBar()

        // Opened from another app (e.g. a file manager): load that file instead.
        if (!openGpxFromIntent(intent)) {
            // Reopen the last loaded GPX if it is still readable, otherwise load the bundled default.
            val lastGpx = gpxHistoryStore.entries().firstOrNull()
            if (lastGpx != null && gpxHistoryStore.isAvailable(lastGpx.uri)) {
                gpxTrackManager.loadGpxTrackFromUri(lastGpx.uri, latestLocation)
            } else {
                gpxTrackManager.loadGpxTrackFromAssets(appConfig.layout.defaultGpxAssetFile, latestLocation)
            }
        }

        // Request the lowest display refresh rate supported by this device.
        applyLowestRefreshRate()

        // Paint system bars black too
        window.navigationBarColor = appConfig.layout.backgroundColor
        window.statusBarColor = appConfig.layout.backgroundColor
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
        }

        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        ensureLocationPermissionAndStart()
        if (appConfig.settingsPanel.showDebugStatusSection) {
            mainHandler.post(uiRefreshRunnable)
        }
    }


    private fun configureOsmForScopedStorage() {
        val osmConfig = Configuration.getInstance()

        // Keep osmdroid cache in app-private storage to avoid scoped-storage issues.
        val basePath = File(cacheDir, "osmdroid")
        val tilePath = File(basePath, "tiles")
        if (!basePath.exists()) basePath.mkdirs()
        if (!tilePath.exists()) tilePath.mkdirs()

        osmConfig.osmdroidBasePath = basePath
        osmConfig.osmdroidTileCache = tilePath
        osmConfig.load(this, getSharedPreferences("osmdroid", Context.MODE_PRIVATE))
        osmConfig.userAgentValue = buildIdentifiableUserAgent()
    }

    private fun arcGisTileSource(style: ArcGisStyle): XYTileSource {
        return object : XYTileSource(
            "ArcGIS_${style.serviceName}",
            0,
            style.maxZoom,
            256,
            ".jpg",
            arrayOf("https://server.arcgisonline.com/ArcGIS/rest/services/${style.serviceName}/MapServer/tile/")
        ) {
            override fun getTileURLString(pMapTileIndex: Long): String {
                val z = MapTileIndex.getZoom(pMapTileIndex)
                val x = MapTileIndex.getX(pMapTileIndex)
                val y = MapTileIndex.getY(pMapTileIndex)
                // ArcGIS tile path order is /{z}/{y}/{x} (not /{z}/{x}/{y}).
                return "${getBaseUrl()}${z}/${y}/${x}"
            }
        }
    }

    private fun applyMapStyle(style: ArcGisStyle) {
        if (style == currentMapStyle) return

        val map = mapView ?: return

        // Preserve viewport when changing tile source.
        val previousZoom = map.zoomLevelDouble
        val previousCenter = map.mapCenter
        val clampedZoom = previousZoom.coerceIn(0.0, style.maxZoom.toDouble())

        currentMapStyle = style
        map.setTileSource(arcGisTileSource(style))
        map.controller.setCenter(previousCenter)
        map.controller.setZoom(clampedZoom)
        map.tileProvider.clearTileCache()
        map.invalidate()
        mapScaleLegend.update()
    }

    private fun updateGpxProgressBar() {
        if (!::gpxProgressBar.isInitialized || !::gpxTrackManager.isInitialized) return
        gpxProgressBar.setProgress(
            doneKm = gpxTrackManager.latestDoneDistanceKmIfNearTrack(),
            totalKm = gpxTrackManager.routeDistanceKm(),
            timeToDestinationMinutes = gpxTrackManager.estimatedRemainingMinutesIfNearTrack()
        )
    }

    private fun buildIdentifiableUserAgent(): String {
        val appLabel = applicationInfo.loadLabel(packageManager).toString().replace(" ", "")
        val versionName = try {
            packageManager.getPackageInfo(packageName, 0).versionName ?: "unknown"
        } catch (_: Exception) {
            "unknown"
        }
        return "$appLabel/$versionName (${appConfig.system.osmContact}; $packageName)"
    }

    private fun updateWeatherIfNeeded(location: Location) {
        val now = System.currentTimeMillis()
        val refreshIntervalMs = weatherPanel.refreshIntervalMs(wasLastWeatherRequestSuccessful)

        if (isWeatherRequestInFlight) return
        if (now - lastWeatherRequestTimeMs < refreshIntervalMs) return

        isWeatherRequestInFlight = true
        lastWeatherRequestTimeMs = now

        val latitude = location.latitude
        val longitude = location.longitude
        val elevationMeters = if (location.hasAltitude()) location.altitude else null

        Thread {
            val weatherSummary = try {
                weatherPanel.fetchWeatherSummary(latitude, longitude, elevationMeters)
            } catch (_: Exception) {
                null
            }

            runOnUiThread {
                if (weatherSummary == null) {
                    wasLastWeatherRequestSuccessful = false
                    weatherText.text = weatherPanel.formatWeatherSummaryWithIcons(
                        weatherPanel.placeholderSummary(),
                        weatherText.currentTextColor
                    )
                    weatherText.visibility = View.VISIBLE
                } else {
                    wasLastWeatherRequestSuccessful = true
                    weatherText.text = weatherPanel.formatWeatherSummaryWithIcons(
                        weatherSummary,
                        weatherText.currentTextColor
                    )
                    weatherText.visibility = View.VISIBLE
                }
                isWeatherRequestInFlight = false
            }
        }.start()
    }


    private fun ensureLocationPermissionAndStart() {
        if (hasLocationPermission()) {
            startLocationUpdates()
        } else {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                ),
                appConfig.runtime.locationRequestCode
            )
        }
    }

    private fun hasLocationPermission(): Boolean {
        val fineGranted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        val coarseGranted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        return fineGranted || coarseGranted
    }

    private fun startLocationUpdates() {
        if (!hasLocationPermission()) return

        val gpsEnabled = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)
        if (!gpsEnabled) {
            Log.log(this, "Location", "GPS is off; waiting for it to be turned on")
            return
        }

        // Never keep two listeners: remove the previous one before registering a new one.
        locationListener?.let { locationManager.removeUpdates(it) }
        locationUpdatesStartedAtMs = SystemClock.elapsedRealtime()
        hasReceivedFirstFix = false

        locationListener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                if (!hasReceivedFirstFix) {
                    hasReceivedFirstFix = true
                    val seconds = (SystemClock.elapsedRealtime() - locationUpdatesStartedAtMs) / 1000.0
                    Log.log(
                        this@Main,
                        "Location",
                        String.format(Locale.US, "First GPS fix after %.1f s (accuracy %.0f m)", seconds, location.accuracy)
                    )
                }
                latestLocation = location
                largeNumbersPanel.onLocation(location)
                updateMapOrientationForMovement(location)
                val shouldUpdateMap = shouldUpdateMapLocation(location)
                // Intensive: nearest-track search on every fix.
                gpxTrackManager.onLocationUpdate(location, shouldUpdateMap)
                // Light: progress bar and ETA arithmetic only.
                updateGpxProgressBar()
                mapScaleLegend.update()
                updateWeatherIfNeeded(location)

                if (shouldUpdateMap) {
                    lastMapUpdateLocation = Location(location)
                    // Map is following GPS during a ride: hide the unused track selector.
                    if (!isMapFollowPaused() && ::gpxTrackSelector.isInitialized) {
                        gpxTrackSelector.setVisible(false)
                    }
                }

                settingsPanel.setStatus()
                largeNumbersPanel.refresh()
            }

            // Required on purpose: these methods have no default implementation before Android 11.
            override fun onProviderDisabled(provider: String) {
                Log.log(this@Main, "Location", "Provider disabled: $provider")
            }

            override fun onProviderEnabled(provider: String) {
                Log.log(this@Main, "Location", "Provider enabled: $provider")
            }

            // Only called on Android 9 and older.
            @Deprecated("Deprecated in Java")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {
                val statusText = when (status) {
                    0 -> "out of service"
                    1 -> "temporarily unavailable"
                    2 -> "available"
                    else -> "unknown ($status)"
                }
                Log.log(this@Main, "Location", "Provider $provider status: $statusText")
            }
        }

        val listener = locationListener ?: return

        try {
            // GPS updates every gpsUpdateIntervalMs (Config.kt), with no minimum distance between fixes.
            locationManager.requestLocationUpdates(
                LocationManager.GPS_PROVIDER,
                appConfig.runtime.gpsUpdateIntervalMs,
                0f,
                listener
            )

            // Show last known GPS location immediately if available and recent enough.
            val lastKnown = try {
                locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
            } catch (_: SecurityException) {
                null
            }?.takeIf { location ->
                val ageMs = (SystemClock.elapsedRealtimeNanos() - location.elapsedRealtimeNanos) / 1_000_000
                val isRecent = ageMs <= appConfig.runtime.lastKnownLocationMaxAgeMs
                if (!isRecent) Log.log(this, "Location", "Ignoring last known location, ${ageMs / 60000} min old")
                isRecent
            }

            if (lastKnown != null) {
                latestLocation = lastKnown
                largeNumbersPanel.onLocation(lastKnown)
                updateMapOrientationForMovement(lastKnown)
                gpxTrackManager.onLocationUpdate(lastKnown, shouldUpdateMapLocation = true)
                updateGpxProgressBar()
                mapScaleLegend.update()
                updateWeatherIfNeeded(lastKnown)
                lastMapUpdateLocation = Location(lastKnown)
                settingsPanel.setStatus()
                largeNumbersPanel.refresh()
            }
        } catch (e: SecurityException) {
            Log.log(this, "Location", "Location permission denied: ${e.message}")
        }
    }

    private fun shouldUpdateMapLocation(location: Location): Boolean {
        val previous = lastMapUpdateLocation ?: return true
        // Avoid expensive map redraws when movement is tiny / mostly GPS jitter.
        return previous.distanceTo(location) >= appConfig.runtime.mapUpdateMinDistanceMeters
    }


    // Pauses map centering and rotation. Called on every map touch and on GPX load.
    private fun pauseMapFollowing() {
        followPausedUntilMs = System.currentTimeMillis() + appConfig.mapRotation.followPauseAfterTouchOrLoadMs
        if (::gpxTrackSelector.isInitialized) {
            gpxTrackSelector.setVisible(true)
        }
        if (isMapRotationSmoothingRunning) {
            mainHandler.removeCallbacks(mapRotationSmoothingRunnable)
            isMapRotationSmoothingRunning = false
        }
        targetMapOrientationDegrees = normalizeBearingDegrees(mapView?.mapOrientation ?: 0f)
    }

    private fun isMapFollowPaused(): Boolean = System.currentTimeMillis() < followPausedUntilMs

    private fun updateMapOrientationForMovement(currentLocation: Location) {
        if (isMapFollowPaused() || System.currentTimeMillis() < manualRotationPauseUntilMs) {
            lastOrientationLocation = Location(currentLocation)
            return
        }

        val previousLocation = lastOrientationLocation

        // First sample only initializes the orientation baseline.
        if (previousLocation == null) {
            lastOrientationLocation = Location(currentLocation)
            return
        }

        val speedKmh = if (currentLocation.hasSpeed()) currentLocation.speed * 3.6f else null
        // At very low speed (or stationary), ignore heading changes from GPS jitter.
        if (speedKmh != null && speedKmh < appConfig.mapRotation.minSpeedKmh) {
            lastOrientationLocation = Location(currentLocation)
            return
        }

        // Update heading target only after enough movement to reduce jitter.
        val movedDistanceMeters = previousLocation.distanceTo(currentLocation)
        if (movedDistanceMeters < appConfig.mapRotation.minMovementMeters) {
            // Keep baseline so movement can accumulate at low speed.
            return
        }

        val movementBearing = previousLocation.bearingTo(currentLocation)
        // osmdroid map orientation rotates the map itself; invert bearing so user motion points "up".
        targetMapOrientationDegrees = normalizeBearingDegrees(360f - movementBearing)
        lastOrientationLocation = Location(currentLocation)

        // Start smoother only when there is meaningful rotation to animate.
        startMapRotationSmoothingIfNeeded()
    }

    private fun startMapRotationSmoothingIfNeeded() {
        val map = mapView ?: return
        if (isMapRotationSmoothingRunning) return

        val currentOrientation = normalizeBearingDegrees(map.mapOrientation)
        val targetOrientation = normalizeBearingDegrees(targetMapOrientationDegrees)
        val delta = shortestSignedAngleDeltaDegrees(currentOrientation, targetOrientation)
        if (abs(delta) <= appConfig.mapRotation.snapEpsilonDegrees) return

        isMapRotationSmoothingRunning = true
        mainHandler.post(mapRotationSmoothingRunnable)
    }

    private fun applySmoothMapRotationStep(): Boolean {
        val map = mapView ?: return false

        val currentOrientation = normalizeBearingDegrees(map.mapOrientation)
        val targetOrientation = normalizeBearingDegrees(targetMapOrientationDegrees)
        val delta = shortestSignedAngleDeltaDegrees(currentOrientation, targetOrientation)

        // Close enough: snap to final angle and stop smoothing loop.
        if (abs(delta) <= appConfig.mapRotation.snapEpsilonDegrees) {
            map.mapOrientation = targetOrientation
            return false
        }

        // Move a fraction of remaining delta each tick, capped by max step.
        val step = (delta * appConfig.mapRotation.animationFollowFactor)
            .coerceIn(-appConfig.mapRotation.animationMaxStepDegrees, appConfig.mapRotation.animationMaxStepDegrees)

        map.mapOrientation = normalizeBearingDegrees(currentOrientation + step)
        return true
    }

    private fun shortestSignedAngleDeltaDegrees(from: Float, to: Float): Float {
        return ((to - from + 540f) % 360f) - 180f
    }

    private fun normalizeBearingDegrees(bearing: Float): Float {
        val normalized = bearing % 360f
        return if (normalized < 0f) normalized + 360f else normalized
    }

    // Screen brightness while Cyclope is on screen (onStart): system brightness in manual mode at 100%,
    // or at the level the rider chose with the system slider earlier in this session.
    // When Cyclope leaves the screen (onStop), the rider's original mode and level are restored.
    // The original is stored in SharedPreferences before anything is changed, so after a crash or kill
    // it is still restored the next time Cyclope leaves the screen.
    private fun applyCyclopeBrightness() {
        if (!Settings.System.canWrite(this)) {
            if (!hasRequestedWriteSettings) {
                hasRequestedWriteSettings = true
                startActivity(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:$packageName")))
            }
            return
        }
        val resolver = contentResolver
        // Keep an existing saved original: it means the last restore did not happen (crash or kill).
        if (!brightnessPreferences.contains(KEY_ORIGINAL_LEVEL)) {
            val mode = Settings.System.getInt(resolver, Settings.System.SCREEN_BRIGHTNESS_MODE, -1)
            val level = Settings.System.getInt(resolver, Settings.System.SCREEN_BRIGHTNESS, -1)
            if (mode < 0 || level < 0) return
            brightnessPreferences.edit()
                .putInt(KEY_ORIGINAL_MODE, mode)
                .putInt(KEY_ORIGINAL_LEVEL, level)
                .commit() // written to storage before the system setting is changed
        }
        val level = cyclopeBrightnessLevel ?: appConfig.system.brightness100Percent
        Settings.System.putInt(resolver, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
        Settings.System.putInt(resolver, Settings.System.SCREEN_BRIGHTNESS, level)
        Log.log(this, "Brightness", "Applied level $level (original saved: ${brightnessPreferences.getInt(KEY_ORIGINAL_LEVEL, -1)})")
    }

    private fun restoreOriginalBrightness() {
        if (!brightnessPreferences.contains(KEY_ORIGINAL_LEVEL) || !Settings.System.canWrite(this)) return
        val resolver = contentResolver
        // Remember the rider's slider choice for when Cyclope comes back.
        cyclopeBrightnessLevel = Settings.System.getInt(resolver, Settings.System.SCREEN_BRIGHTNESS, -1)
            .takeIf { it >= 0 }
        val mode = brightnessPreferences.getInt(KEY_ORIGINAL_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
        val level = brightnessPreferences.getInt(KEY_ORIGINAL_LEVEL, appConfig.system.brightness100Percent)
        // Level first while still in manual mode, then the original mode (e.g. back to automatic).
        val restored = Settings.System.putInt(resolver, Settings.System.SCREEN_BRIGHTNESS, level) &&
                Settings.System.putInt(resolver, Settings.System.SCREEN_BRIGHTNESS_MODE, mode)
        if (restored) {
            brightnessPreferences.edit().remove(KEY_ORIGINAL_MODE).remove(KEY_ORIGINAL_LEVEL).commit()
        }
        Log.log(this, "Brightness", "Restored level $level, mode $mode: ${if (restored) "ok" else "failed, will retry"}")
    }

    private fun applyLowestRefreshRate() {
        val currentDisplay = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            display
        } else {
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay
        } ?: return

        // Only modes with the current resolution: a mode is resolution + refresh rate, and picking
        // another resolution would make the screen switch resolution while Cyclope is shown.
        val currentMode = currentDisplay.mode
        val lowestMode = currentDisplay.supportedModes
            .filter { it.physicalWidth == currentMode.physicalWidth && it.physicalHeight == currentMode.physicalHeight }
            .minByOrNull { it.refreshRate } ?: return
        Log.log(
            this,
            "Display",
            "Requesting ${lowestMode.physicalWidth}x${lowestMode.physicalHeight} @ ${lowestMode.refreshRate} Hz " +
                    "(current ${currentMode.physicalWidth}x${currentMode.physicalHeight} @ ${currentMode.refreshRate} Hz)"
        )
        val params = window.attributes
        params.preferredDisplayModeId = lowestMode.modeId
        window.attributes = params
    }

    private fun dpToPx(dp: Int): Int = (dp * resources.displayMetrics.density).toInt()


    @Deprecated("Deprecated in Java")
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        if (requestCode == appConfig.runtime.locationRequestCode) {
            if (grantResults.isNotEmpty() && grantResults.any { it == PackageManager.PERMISSION_GRANTED }) {
                startLocationUpdates()
            } else {
                Log.log(this, "Location", "Location permission denied by the user")
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        openGpxFromIntent(intent)
    }

    // Returns true when the intent is a VIEW of a GPX file and that file was loaded.
    private fun openGpxFromIntent(intent: Intent?): Boolean {
        if (intent?.action != Intent.ACTION_VIEW) return false
        val gpxUri = intent.data ?: return false
        if (::gpxTrackManager.isInitialized) {
            gpxTrackManager.loadGpxTrackFromUri(gpxUri, latestLocation)
        }
        return true
    }

    override fun onStart() {
        super.onStart()
        ContextCompat.registerReceiver(
            this,
            gpsStateReceiver,
            IntentFilter(LocationManager.PROVIDERS_CHANGED_ACTION),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        applyCyclopeBrightness()
    }

    override fun onResume() {
        super.onResume()
        mapView?.onResume()
    }

    override fun onPause() {
        mapView?.onPause()
        super.onPause()
    }

    override fun onStop() {
        unregisterReceiver(gpsStateReceiver)
        restoreOriginalBrightness()
        super.onStop()
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(uiRefreshRunnable)
        mainHandler.removeCallbacks(mapRotationSmoothingRunnable)
        locationListener?.let { listener ->
            locationManager.removeUpdates(listener)
        }
        mapView?.onDetach()
        super.onDestroy()
    }

    private companion object {
        const val KEY_ORIGINAL_MODE = "original_mode"
        const val KEY_ORIGINAL_LEVEL = "original_level"
    }
}
