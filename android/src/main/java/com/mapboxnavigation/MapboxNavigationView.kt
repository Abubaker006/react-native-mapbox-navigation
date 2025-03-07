package com.mapboxnavigation

import android.annotation.SuppressLint
import android.content.res.Configuration
import android.content.res.Resources
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import com.facebook.react.bridge.Arguments
import com.facebook.react.uimanager.ThemedReactContext
import com.facebook.react.uimanager.events.RCTEventEmitter
import com.mapbox.api.directions.v5.DirectionsCriteria
import com.mapbox.api.directions.v5.models.DirectionsWaypoint
import com.mapbox.api.directions.v5.models.RouteOptions
import com.mapbox.bindgen.Expected
import com.mapbox.common.location.Location
import com.mapbox.geojson.Point
import com.mapbox.maps.CameraOptions
import com.mapbox.maps.EdgeInsets
import com.mapbox.maps.ImageHolder
import com.mapbox.maps.plugin.LocationPuck2D
import com.mapbox.maps.plugin.animation.camera
import com.mapbox.maps.plugin.locationcomponent.location
import com.mapbox.navigation.base.TimeFormat
import com.mapbox.navigation.base.extensions.applyDefaultNavigationOptions
import com.mapbox.navigation.base.extensions.applyLanguageAndVoiceUnitOptions
import com.mapbox.navigation.base.formatter.DistanceFormatterOptions
import com.mapbox.navigation.base.formatter.UnitType
import com.mapbox.navigation.base.options.NavigationOptions
import com.mapbox.navigation.base.route.NavigationRoute
import com.mapbox.navigation.base.route.NavigationRouterCallback
import com.mapbox.navigation.base.route.RouterFailure
import com.mapbox.navigation.base.route.RouterOrigin
import com.mapbox.navigation.base.trip.model.RouteLegProgress
import com.mapbox.navigation.base.trip.model.RouteProgress
import com.mapbox.navigation.core.MapboxNavigation
import com.mapbox.navigation.core.MapboxNavigationProvider
import com.mapbox.navigation.core.arrival.ArrivalObserver
import com.mapbox.navigation.core.directions.session.RoutesObserver
import com.mapbox.navigation.core.formatter.MapboxDistanceFormatter
import com.mapbox.navigation.core.trip.session.LocationMatcherResult
import com.mapbox.navigation.core.trip.session.LocationObserver
import com.mapbox.navigation.core.trip.session.RouteProgressObserver
import com.mapbox.navigation.core.trip.session.VoiceInstructionsObserver
import com.mapbox.navigation.tripdata.maneuver.api.MapboxManeuverApi
import com.mapbox.navigation.tripdata.progress.api.MapboxTripProgressApi
import com.mapbox.navigation.tripdata.progress.model.DistanceRemainingFormatter
import com.mapbox.navigation.tripdata.progress.model.EstimatedTimeToArrivalFormatter
import com.mapbox.navigation.tripdata.progress.model.PercentDistanceTraveledFormatter
import com.mapbox.navigation.tripdata.progress.model.TimeRemainingFormatter
import com.mapbox.navigation.tripdata.progress.model.TripProgressUpdateFormatter
import com.mapbox.navigation.ui.base.util.MapboxNavigationConsumer
import com.mapbox.navigation.ui.components.maneuver.model.ManeuverPrimaryOptions
import com.mapbox.navigation.ui.components.maneuver.model.ManeuverSecondaryOptions
import com.mapbox.navigation.ui.components.maneuver.model.ManeuverSubOptions
import com.mapbox.navigation.ui.components.maneuver.model.ManeuverViewOptions
import com.mapbox.navigation.ui.components.maneuver.view.MapboxManeuverView
import com.mapbox.navigation.ui.components.tripprogress.view.MapboxTripProgressView
import com.mapbox.navigation.ui.maps.NavigationStyles
import com.mapbox.navigation.ui.maps.camera.NavigationCamera
import com.mapbox.navigation.ui.maps.camera.data.MapboxNavigationViewportDataSource
import com.mapbox.navigation.ui.maps.camera.lifecycle.NavigationBasicGesturesHandler
import com.mapbox.navigation.ui.maps.camera.state.NavigationCameraState
import com.mapbox.navigation.ui.maps.camera.transition.NavigationCameraTransitionOptions
import com.mapbox.navigation.ui.maps.location.NavigationLocationProvider
import com.mapbox.navigation.ui.maps.route.RouteLayerConstants.TOP_LEVEL_ROUTE_LINE_LAYER_ID
import com.mapbox.navigation.ui.maps.route.arrow.api.MapboxRouteArrowApi
import com.mapbox.navigation.ui.maps.route.arrow.api.MapboxRouteArrowView
import com.mapbox.navigation.ui.maps.route.arrow.model.RouteArrowOptions
import com.mapbox.navigation.ui.maps.route.line.api.MapboxRouteLineApi
import com.mapbox.navigation.ui.maps.route.line.api.MapboxRouteLineView
import com.mapbox.navigation.ui.maps.route.line.model.MapboxRouteLineApiOptions
import com.mapbox.navigation.ui.maps.route.line.model.MapboxRouteLineViewOptions
import com.mapbox.navigation.ui.maps.route.line.model.RouteLineColorResources
import com.mapbox.navigation.voice.api.MapboxSpeechApi
import com.mapbox.navigation.voice.api.MapboxVoiceInstructionsPlayer
import com.mapbox.navigation.voice.model.SpeechAnnouncement
import com.mapbox.navigation.voice.model.SpeechError
import com.mapbox.navigation.voice.model.SpeechValue
import com.mapbox.navigation.voice.model.SpeechVolume
import com.mapboxnavigation.databinding.NavigationViewBinding
import java.util.Locale

@SuppressLint("ViewConstructor")
class MapboxNavigationView(private val context: ThemedReactContext) : FrameLayout(context.baseContext) {
  private companion object {
    private const val BUTTON_ANIMATION_DURATION = 1500L
  }

  private var origin: Point? = null
  private var destination: Point? = null
  private var destinationTitle: String = "Destination"
  private var waypoints: List<Point> = listOf()
  private var waypointLegs: List<WaypointLegs> = listOf()
  private var distanceUnit: String = DirectionsCriteria.IMPERIAL
  private var locale = Locale.getDefault()

  private var binding: NavigationViewBinding = NavigationViewBinding.inflate(LayoutInflater.from(context), this, true)
  private var viewportDataSource = MapboxNavigationViewportDataSource(binding.mapView.mapboxMap)
  private var navigationCamera = NavigationCamera(
    binding.mapView.mapboxMap,
    binding.mapView.camera,
    viewportDataSource
  )
  private var mapboxNavigation: MapboxNavigation? = null

  private val pixelDensity = Resources.getSystem().displayMetrics.density
  private val overviewPadding: EdgeInsets by lazy { EdgeInsets(140.0 * pixelDensity, 40.0 * pixelDensity, 120.0 * pixelDensity, 40.0 * pixelDensity) }
  private val landscapeOverviewPadding: EdgeInsets by lazy { EdgeInsets(30.0 * pixelDensity, 380.0 * pixelDensity, 110.0 * pixelDensity, 20.0 * pixelDensity) }
  private val followingPadding: EdgeInsets by lazy { EdgeInsets(180.0 * pixelDensity, 40.0 * pixelDensity, 150.0 * pixelDensity, 40.0 * pixelDensity) }
  private val landscapeFollowingPadding: EdgeInsets by lazy { EdgeInsets(30.0 * pixelDensity, 380.0 * pixelDensity, 110.0 * pixelDensity, 40.0 * pixelDensity) }

  private lateinit var maneuverApi: MapboxManeuverApi
  private lateinit var tripProgressApi: MapboxTripProgressApi
  private var isVoiceInstructionsMuted = false
    set(value) {
      field = value
      if (value) {
        binding.soundButton.muteAndExtend(BUTTON_ANIMATION_DURATION)
        voiceInstructionsPlayer?.volume(SpeechVolume(0f))
      } else {
        binding.soundButton.unmuteAndExtend(BUTTON_ANIMATION_DURATION)
        voiceInstructionsPlayer?.volume(SpeechVolume(1f))
      }
    }
  private lateinit var speechApi: MapboxSpeechApi
  private var voiceInstructionsPlayer: MapboxVoiceInstructionsPlayer? = null
  private val navigationLocationProvider = NavigationLocationProvider()
  private val routeLineViewOptions: MapboxRouteLineViewOptions by lazy {
    MapboxRouteLineViewOptions.Builder(context)
      .routeLineColorResources(RouteLineColorResources.Builder().build())
      .routeLineBelowLayerId("road-label-navigation")
      .build()
  }
  private val routeLineApiOptions: MapboxRouteLineApiOptions by lazy { MapboxRouteLineApiOptions.Builder().build() }
  private val routeLineView by lazy { MapboxRouteLineView(routeLineViewOptions) }
  private val routeLineApi: MapboxRouteLineApi by lazy { MapboxRouteLineApi(routeLineApiOptions) }
  private val routeArrowApi: MapboxRouteArrowApi by lazy { MapboxRouteArrowApi() }
  private val routeArrowOptions by lazy { RouteArrowOptions.Builder(context).withAboveLayerId(TOP_LEVEL_ROUTE_LINE_LAYER_ID).build() }
  private val routeArrowView: MapboxRouteArrowView by lazy { MapboxRouteArrowView(routeArrowOptions) }

  private val voiceInstructionsObserver = VoiceInstructionsObserver { voiceInstructions ->
    speechApi.generate(voiceInstructions, speechCallback)
  }
  private val speechCallback = MapboxNavigationConsumer<Expected<SpeechError, SpeechValue>> { expected ->
    expected.fold(
      { error -> voiceInstructionsPlayer?.play(error.fallback, voiceInstructionsPlayerCallback) },
      { value -> voiceInstructionsPlayer?.play(value.announcement, voiceInstructionsPlayerCallback) }
    )
  }
  private val voiceInstructionsPlayerCallback = MapboxNavigationConsumer<SpeechAnnouncement> { value -> speechApi.clean(value) }

  private val locationObserver = object : LocationObserver {
    var firstLocationUpdateReceived = false
    override fun onNewRawLocation(rawLocation: Location) {}
    override fun onNewLocationMatcherResult(locationMatcherResult: LocationMatcherResult) {
      val enhancedLocation = locationMatcherResult.enhancedLocation
      navigationLocationProvider.changePosition(enhancedLocation, locationMatcherResult.keyPoints)
      viewportDataSource.onLocationChanged(enhancedLocation)
      viewportDataSource.evaluate()
      if (!firstLocationUpdateReceived) {
        firstLocationUpdateReceived = true
        navigationCamera.requestNavigationCameraToOverview(stateTransitionOptions = NavigationCameraTransitionOptions.Builder().maxDuration(0).build())
      }
      val event = Arguments.createMap().apply {
        putDouble("longitude", enhancedLocation.longitude)
        putDouble("latitude", enhancedLocation.latitude)
        putDouble("heading", enhancedLocation.bearing ?: 0.0)
        putDouble("accuracy", enhancedLocation.horizontalAccuracy ?: 0.0)
      }
      context.getJSModule(RCTEventEmitter::class.java).receiveEvent(id, "onLocationChange", event)
    }
  }

  private val routeProgressObserver = RouteProgressObserver { routeProgress ->
    if (routeProgress.fractionTraveled.toDouble() != 0.0) {
      viewportDataSource.onRouteProgressChanged(routeProgress)
    }
    viewportDataSource.evaluate()
    val style = binding.mapView.mapboxMap.style
    if (style != null) {
      val maneuverArrowResult = routeArrowApi.addUpcomingManeuverArrow(routeProgress)
      routeArrowView.renderManeuverUpdate(style, maneuverArrowResult)
    }
    val maneuvers = maneuverApi.getManeuvers(routeProgress)
    maneuvers.fold(
      { error -> Log.w("Maneuvers error:", error.throwable) },
      {
        val maneuverViewOptions = ManeuverViewOptions.Builder()
          .primaryManeuverOptions(ManeuverPrimaryOptions.Builder().textAppearance(R.style.PrimaryManeuverTextAppearance).build())
          .secondaryManeuverOptions(ManeuverSecondaryOptions.Builder().textAppearance(R.style.ManeuverTextAppearance).build())
          .subManeuverOptions(ManeuverSubOptions.Builder().textAppearance(R.style.ManeuverTextAppearance).build())
          .stepDistanceTextAppearance(R.style.StepDistanceRemainingAppearance)
          .build()
        binding.maneuverView.visibility = View.VISIBLE
        binding.maneuverView.updateManeuverViewOptions(maneuverViewOptions)
        binding.maneuverView.renderManeuvers(maneuvers)
      }
    )
    binding.tripProgressView.render(tripProgressApi.getTripProgress(routeProgress))
    val event = Arguments.createMap().apply {
      putDouble("distanceTraveled", routeProgress.distanceTraveled.toDouble())
      putDouble("durationRemaining", routeProgress.durationRemaining)
      putDouble("fractionTraveled", routeProgress.fractionTraveled.toDouble())
      putDouble("distanceRemaining", routeProgress.distanceRemaining.toDouble())
    }
    context.getJSModule(RCTEventEmitter::class.java).receiveEvent(id, "onRouteProgressChange", event)
  }

  private val routesObserver = RoutesObserver { routeUpdateResult ->
    if (routeUpdateResult.navigationRoutes.isNotEmpty()) {
      routeLineApi.setNavigationRoutes(routeUpdateResult.navigationRoutes) { value ->
        binding.mapView.mapboxMap.style?.apply { routeLineView.renderRouteDrawData(this, value) }
      }
      viewportDataSource.onRouteChanged(routeUpdateResult.navigationRoutes.first())
      viewportDataSource.evaluate()
    } else {
      val style = binding.mapView.mapboxMap.style
      if (style != null) {
        routeLineApi.clearRouteLine { value -> routeLineView.renderClearRouteLineValue(style, value) }
        routeArrowView.render(style, routeArrowApi.clearArrows())
      }
      viewportDataSource.clearRouteData()
      viewportDataSource.evaluate()
    }
  }

  private val arrivalObserver = object : ArrivalObserver {
    override fun onWaypointArrival(routeProgress: RouteProgress) = onArrival(routeProgress)
    override fun onNextRouteLegStart(routeLegProgress: RouteLegProgress) {}
    override fun onFinalDestinationArrival(routeProgress: RouteProgress) = onArrival(routeProgress)
  }

  init {
    onCreate()
  }

  private fun onCreate() {
    mapboxNavigation = if (MapboxNavigationProvider.isCreated()) {
      MapboxNavigationProvider.retrieve()
    } else {
      MapboxNavigationProvider.create(NavigationOptions.Builder(context).build())
    }
  }

  @SuppressLint("MissingPermission")
  private fun initNavigation() {
    if (origin == null || destination == null) {
      sendErrorToReact("origin and destination are required")
      return
    }

    val initialCameraOptions = CameraOptions.Builder().zoom(14.0).center(origin).build()
    binding.mapView.mapboxMap.setCamera(initialCameraOptions)

    binding.mapView.camera.addCameraAnimationsLifecycleListener(NavigationBasicGesturesHandler(navigationCamera))
    navigationCamera.registerNavigationCameraStateChangeObserver { navigationCameraState ->
      when (navigationCameraState) {
        NavigationCameraState.TRANSITION_TO_FOLLOWING, NavigationCameraState.FOLLOWING -> binding.recenter.visibility = View.INVISIBLE
        NavigationCameraState.TRANSITION_TO_OVERVIEW, NavigationCameraState.OVERVIEW, NavigationCameraState.IDLE -> binding.recenter.visibility = View.VISIBLE
      }
    }

    if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) {
      viewportDataSource.overviewPadding = landscapeOverviewPadding
      viewportDataSource.followingPadding = landscapeFollowingPadding
    } else {
      viewportDataSource.overviewPadding = overviewPadding
      viewportDataSource.followingPadding = followingPadding
    }

    val unitType = if (distanceUnit == "imperial") UnitType.IMPERIAL else UnitType.METRIC
    val distanceFormatterOptions = DistanceFormatterOptions.Builder(context).unitType(unitType).build()

    maneuverApi = MapboxManeuverApi(MapboxDistanceFormatter(distanceFormatterOptions))
    tripProgressApi = MapboxTripProgressApi(
      TripProgressUpdateFormatter.Builder(context)
        .distanceRemainingFormatter(DistanceRemainingFormatter(distanceFormatterOptions))
        .timeRemainingFormatter(TimeRemainingFormatter(context))
        .percentRouteTraveledFormatter(PercentDistanceTraveledFormatter())
        .estimatedTimeToArrivalFormatter(EstimatedTimeToArrivalFormatter(context, TimeFormat.NONE_SPECIFIED))
        .build()
    )
    speechApi = MapboxSpeechApi(context, locale.language)
    voiceInstructionsPlayer = MapboxVoiceInstructionsPlayer(context, locale.language)

    binding.mapView.mapboxMap.loadStyle(NavigationStyles.NAVIGATION_DAY_STYLE) { style ->
      routeLineView.initializeLayers(style)
      startNavigation()
    }

    binding.stop.setOnClickListener {
      val event = Arguments.createMap().apply { putString("message", "Navigation Cancel") }
      context.getJSModule(RCTEventEmitter::class.java).receiveEvent(id, "onCancelNavigation", event)
    }
    binding.recenter.setOnClickListener {
      navigationCamera.requestNavigationCameraToFollowing()
      binding.routeOverview.showTextAndExtend(BUTTON_ANIMATION_DURATION)
    }
    binding.routeOverview.setOnClickListener {
      navigationCamera.requestNavigationCameraToOverview()
      binding.recenter.showTextAndExtend(BUTTON_ANIMATION_DURATION)
    }
    binding.soundButton.setOnClickListener { isVoiceInstructionsMuted = !isVoiceInstructionsMuted }

    if (isVoiceInstructionsMuted) {
      binding.soundButton.mute()
      voiceInstructionsPlayer?.volume(SpeechVolume(0f))
    } else {
      binding.soundButton.unmute()
      voiceInstructionsPlayer?.volume(SpeechVolume(1f))
    }
  }

  private fun startNavigation() {
    binding.mapView.location.apply {
      setLocationProvider(navigationLocationProvider)
      locationPuck = LocationPuck2D(bearingImage = ImageHolder.from(com.mapbox.navigation.ui.maps.R.drawable.mapbox_navigation_puck_icon))
      puckBearingEnabled = true
      enabled = true
    }
    startRoute()
  }

  private fun startRoute() {
    mapboxNavigation?.registerRoutesObserver(routesObserver)
    mapboxNavigation?.registerArrivalObserver(arrivalObserver)
    mapboxNavigation?.registerRouteProgressObserver(routeProgressObserver)
    mapboxNavigation?.registerLocationObserver(locationObserver)
    mapboxNavigation?.registerVoiceInstructionsObserver(voiceInstructionsObserver)

    val coordinatesList = mutableListOf<Point>().apply {
      origin?.let { add(it) }
      addAll(waypoints)
      destination?.let { add(it) }
    }
    findRoute(coordinatesList)
  }

  private fun findRoute(coordinates: List<Point>) {
    val indices = mutableListOf(0).apply { addAll(waypointLegs.map { it.index }); add(coordinates.count() - 1) }
    val names = mutableListOf("origin").apply { addAll(waypointLegs.map { it.name }); add(destinationTitle) }

    mapboxNavigation?.requestRoutes(
      RouteOptions.builder()
        .applyDefaultNavigationOptions(DirectionsCriteria.PROFILE_CYCLING)
        .applyLanguageAndVoiceUnitOptions(context)
        .coordinatesList(coordinates)
        .waypointIndicesList(indices)
        .waypointNamesList(names)
        .language(locale.language)
        .steps(true)
        .voiceInstructions(true)
        .voiceUnits(distanceUnit)
        .build(),
      object : NavigationRouterCallback {
        override fun onCanceled(routeOptions: RouteOptions, routerOrigin: String) {}
        override fun onFailure(reasons: List<RouterFailure>, routeOptions: RouteOptions) {
          sendErrorToReact("Error finding route $reasons")
        }
        override fun onRoutesReady(routes: List<NavigationRoute>, routerOrigin: String) {
          setRouteAndStartNavigation(routes)
        }
      }
    )
  }

  @SuppressLint("MissingPermission")
  private fun setRouteAndStartNavigation(routes: List<NavigationRoute>) {
    mapboxNavigation?.setNavigationRoutes(routes)
    binding.soundButton.visibility = View.VISIBLE
    binding.routeOverview.visibility = View.VISIBLE
    binding.tripProgressCard.visibility = View.VISIBLE
    mapboxNavigation?.startTripSession(withForegroundService = true)
  }

  private fun onArrival(routeProgress: RouteProgress) {
    val leg = routeProgress.currentLegProgress
    if (leg != null) {
      val event = Arguments.createMap().apply {
        putInt("index", leg.legIndex)
        putDouble("latitude", leg.legDestination?.location?.latitude() ?: 0.0)
        putDouble("longitude", leg.legDestination?.location?.longitude() ?: 0.0)
      }
      context.getJSModule(RCTEventEmitter::class.java).receiveEvent(id, "onArrive", event)
    }
  }

  // Lifecycle Methods
  override fun onAttachedToWindow() {
    super.onAttachedToWindow()
    binding.mapView.onStart()
    if (mapboxNavigation?.getNavigationRoutes()?.isNotEmpty() == true) {
      binding.soundButton.visibility = View.VISIBLE
      binding.routeOverview.visibility = View.VISIBLE
      binding.tripProgressCard.visibility = View.VISIBLE
      startRoute()
    } else {
      initNavigation()
    }
  }

  override fun onDetachedFromWindow() {
    super.onDetachedFromWindow()
    binding.mapView.onStop()
    mapboxNavigation?.unregisterRoutesObserver(routesObserver)
    mapboxNavigation?.unregisterArrivalObserver(arrivalObserver)
    mapboxNavigation?.unregisterRouteProgressObserver(routeProgressObserver)
    mapboxNavigation?.unregisterLocationObserver(locationObserver)
    mapboxNavigation?.unregisterVoiceInstructionsObserver(voiceInstructionsObserver)
  }

  fun onStart() {
    binding.mapView.onStart()
  }

  fun onResume() {
    navigationCamera.requestNavigationCameraToFollowing()
  }

  fun onPause() {
    // No-op: Not needed for Maps SDK v11 in this context
  }

  fun onStop() {
    binding.mapView.onStop()
  }

  private fun onDestroy() {
    maneuverApi.cancel()
    routeLineApi.cancel()
    routeLineView.cancel()
    speechApi.cancel()
    voiceInstructionsPlayer?.shutdown()
    mapboxNavigation?.stopTripSession()
    binding.mapView.onDestroy()
    MapboxNavigationProvider.destroy() // Optional
  }

  fun onDropViewInstance() {
    onDestroy()
  }

  override fun requestLayout() {
    super.requestLayout()
    post(measureAndLayout)
  }

  private val measureAndLayout = Runnable {
    measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY))
    layout(left, top, right, bottom)
  }

  private fun sendErrorToReact(error: String?) {
    val event = Arguments.createMap().apply { putString("error", error) }
    context.getJSModule(RCTEventEmitter::class.java).receiveEvent(id, "onError", event)
  }

  fun setStartOrigin(origin: Point?) { this.origin = origin }
  fun setDestination(destination: Point?) { this.destination = destination }
  fun setDestinationTitle(title: String) { this.destinationTitle = title }
  fun setWaypointLegs(legs: List<WaypointLegs>) { this.waypointLegs = legs }
  fun setWaypoints(waypoints: List<Point>) { this.waypoints = waypoints }
  fun setDirectionUnit(unit: String) { this.distanceUnit = unit; initNavigation() }
  fun setLocal(language: String) { val locals = language.split("-"); locale = if (locals.size == 1) Locale(locals.first()) else Locale(locals.first(), locals.last()) }
  fun setMute(mute: Boolean) { this.isVoiceInstructionsMuted = mute }
  fun setShowCancelButton(show: Boolean) { binding.stop.visibility = if (show) View.VISIBLE else View.INVISIBLE }
}
