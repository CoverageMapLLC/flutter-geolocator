package com.baseflow.geolocator;

import android.app.Activity;
import android.content.Context;
import android.location.Location;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;

import com.baseflow.geolocator.errors.ErrorCodes;
import com.baseflow.geolocator.errors.PermissionUndefinedException;
import com.baseflow.geolocator.location.FlutterLocationServiceListener;
import com.baseflow.geolocator.location.ForegroundNotificationOptions;
import com.baseflow.geolocator.location.GeolocationManager;
import com.baseflow.geolocator.location.LocationAccuracyStatus;
import com.baseflow.geolocator.location.LocationAccuracyManager;
import com.baseflow.geolocator.location.LocationClient;
import com.baseflow.geolocator.location.LocationMapper;
import com.baseflow.geolocator.location.LocationOptions;
import com.baseflow.geolocator.permission.LocationPermission;
import com.baseflow.geolocator.permission.PermissionManager;
import com.baseflow.geolocator.utils.Utils;

import io.flutter.plugin.common.BinaryMessenger;
import io.flutter.plugin.common.MethodCall;
import io.flutter.plugin.common.MethodChannel;

import java.util.HashMap;
import java.util.Map;

/**
 * Translates incoming Geolocator MethodCalls into well formed Java function calls for {@link
 * GeolocationManager}.
 */
class MethodCallHandlerImpl implements MethodChannel.MethodCallHandler {

  private static final String TAG = "MethodCallHandlerImpl";
  private final PermissionManager permissionManager;
  private final GeolocationManager geolocationManager;
  private final LocationAccuracyManager locationAccuracyManager;
  private final StreamHandlerImpl streamHandler;

  @VisibleForTesting final Map<String, LocationClient> pendingCurrentPositionLocationClients;
  @VisibleForTesting final Map<String, LocationClient> pendingPositionStreamLocationClients;
  @VisibleForTesting final Map<String, String> pendingForegroundStreamLeases;

  @Nullable private Context context;

  @Nullable private Activity activity;
  @Nullable private GeolocatorLocationService foregroundLocationService;

  MethodCallHandlerImpl(
      PermissionManager permissionManager,
      GeolocationManager geolocationManager,
      LocationAccuracyManager locationAccuracyManager,
      StreamHandlerImpl streamHandler
  ) {
    this.permissionManager = permissionManager;
    this.geolocationManager = geolocationManager;
    this.locationAccuracyManager = locationAccuracyManager;
    this.streamHandler = streamHandler;
    this.pendingCurrentPositionLocationClients = new HashMap<>();
    this.pendingPositionStreamLocationClients = new HashMap<>();
    this.pendingForegroundStreamLeases = new HashMap<>();
  }

  @Nullable private MethodChannel channel;

  @Override
  public void onMethodCall(@NonNull MethodCall call, @NonNull MethodChannel.Result result) {
    switch (call.method) {
      case "checkPermission":
        onCheckPermission(result);
        break;
      case "isLocationServiceEnabled":
        onIsLocationServiceEnabled(result);
        break;
      case "requestPermission":
        onRequestPermission(result);
        break;
      case "getLastKnownPosition":
        onGetLastKnownPosition(call, result);
        break;
      case "getLocationAccuracy":
        getLocationAccuracy(result, this.context);
        break;
      case "getCurrentPosition":
        onGetCurrentPosition(call, result);
        break;
      case "cancelGetCurrentPosition":
        onCancelGetCurrentPosition(call, result);
        break;
      case "startPositionUpdates":
        onStartPositionUpdates(call, result);
        break;
      case "stopPositionUpdates":
        onStopPositionUpdates(call, result);
        break;
      case "openAppSettings":
        boolean hasOpenedAppSettings = Utils.openAppSettings(this.context);
        result.success(hasOpenedAppSettings);
        break;
      case "openLocationSettings":
        boolean hasOpenedLocationSettings = Utils.openLocationSettings(this.context);
        result.success(hasOpenedLocationSettings);
        break;
      case "aquireForegroundMode":
        onAquireForegroundMode(call, result);
        break;
      case "updateForegroundSettings":
        onUpdateForegroundSettings(call, result);
        break;
      case "releaseForegroundMode":
        onReleaseForegroundMode(call, result);
        break;
      default:
        result.notImplemented();
        break;
    }
  }

  /**
   * Registers this instance as a method call handler on the given {@code messenger}.
   *
   * <p>Stops any previously started and unstopped calls.
   *
   * <p>This should be cleaned with {@link #stopListening} once the messenger is disposed of.
   */
  void startListening(Context context, BinaryMessenger messenger) {
    if (channel != null) {
      Log.w(TAG, "Setting a method call handler before the last was disposed.");
      stopListening();
    }

    channel = new MethodChannel(messenger, "flutter.baseflow.com/geolocator_android");
    channel.setMethodCallHandler(this);
    this.context = context;
  }

  /**
   * Clears this instance from listening to method calls.
   *
   * <p>Does nothing if {@link #startListening} hasn't been called, or if we're already stopped.
   */
  void stopListening() {
    if (channel == null) {
      Log.d(TAG, "Tried to stop listening when no MethodChannel had been initialized.");
      return;
    }

    stopAllPositionStreams();

    channel.setMethodCallHandler(null);
    channel = null;
  }

  void setActivity(@Nullable Activity activity) {
    this.activity = activity;
  }

  void setForegroundLocationService(
      @Nullable GeolocatorLocationService foregroundLocationService) {
    this.foregroundLocationService = foregroundLocationService;
  }

  private void onCheckPermission(MethodChannel.Result result) {
    try {
      LocationPermission permission = permissionManager.checkPermissionStatus(context);
      result.success(permission.toInt());
    } catch (PermissionUndefinedException e) {
      ErrorCodes errorCode = ErrorCodes.permissionDefinitionsNotFound;
      result.error(errorCode.toString(), errorCode.toDescription(), null);
    }
  }

  private void onIsLocationServiceEnabled(MethodChannel.Result result) {
    geolocationManager.isLocationServiceEnabled(
        context,
        new FlutterLocationServiceListener(result)
    );
  }

  private void onRequestPermission(MethodChannel.Result result) {
    try {
      permissionManager.requestPermission(
          activity,
          (LocationPermission permission) -> result.success(permission.toInt()),
          (ErrorCodes errorCode) ->
            result.error(errorCode.toString(), errorCode.toDescription(), null)
        );
    } catch (PermissionUndefinedException e) {
      ErrorCodes errorCode = ErrorCodes.permissionDefinitionsNotFound;
      result.error(errorCode.toString(), errorCode.toDescription(), null);
    }
  }

  private void getLocationAccuracy(MethodChannel.Result result, Context context) {
    final LocationAccuracyStatus status =
        locationAccuracyManager.getLocationAccuracy(
            context,
            (ErrorCodes errorCode) ->
            result.error(errorCode.toString(), errorCode.toDescription(), null)
        );
    if (status != null) {
      result.success(status.ordinal());
    }
  }

  private void onGetLastKnownPosition(MethodCall call, MethodChannel.Result result) {
    try {
      if (!permissionManager.hasPermission(context)) {
        result.error(
            ErrorCodes.permissionDenied.toString(),
            ErrorCodes.permissionDenied.toDescription(),
            null);
        return;
      }
    } catch (PermissionUndefinedException e) {
      result.error(
          ErrorCodes.permissionDefinitionsNotFound.toString(),
          ErrorCodes.permissionDefinitionsNotFound.toDescription(),
          null);
      return;
    }

    Boolean forceLocationManager = call.argument("forceLocationManager");

    geolocationManager.getLastKnownPosition(
        context,
        forceLocationManager != null && forceLocationManager,
        (Location location) -> result.success(LocationMapper.toHashMap(location)),
        (ErrorCodes errorCode) ->
        result.error(errorCode.toString(), errorCode.toDescription(), null)
    );
  }

  /**
   * Retrieves the current position.
   *
   * <p>Listens to location updates until it receives a location or encounters an error.
   *
   * <p>To manually cancel this request, call {@link #onCancelGetCurrentPosition}.
   */
  private void onGetCurrentPosition(MethodCall call, MethodChannel.Result result) {
    try {
      if (!permissionManager.hasPermission(context)) {
        result.error(
            ErrorCodes.permissionDenied.toString(),
            ErrorCodes.permissionDenied.toDescription(),
            null);
        return;
      }
    } catch (PermissionUndefinedException e) {
      result.error(
          ErrorCodes.permissionDefinitionsNotFound.toString(),
          ErrorCodes.permissionDefinitionsNotFound.toDescription(),
          null);
      return;
    }

    @SuppressWarnings("unchecked")
    Map<String, Object> map = (Map<String, Object>) call.arguments;
    boolean forceLocationManager = false;
    if (map.get("forceLocationManager") != null) {
      forceLocationManager = (boolean) map.get("forceLocationManager");
    }
    LocationOptions locationOptions = LocationOptions.parseArguments(map);
    String requestId = (String) map.get("requestId");

    final boolean[] replySubmitted = {false};

    LocationClient locationClient =
      geolocationManager.createLocationClient(
        context,
        forceLocationManager,
        locationOptions
      );
    pendingCurrentPositionLocationClients.put(requestId, locationClient);

    geolocationManager.startPositionUpdates(
        locationClient,
        activity,
        (Location location) -> {
          if (replySubmitted[0]) {
            return;
          }

          replySubmitted[0] = true;
          geolocationManager.stopPositionUpdates(locationClient);
          pendingCurrentPositionLocationClients.remove(requestId);
          result.success(LocationMapper.toHashMap(location));
        },
        (ErrorCodes errorCode) -> {
          if (replySubmitted[0]) {
            return;
          }

          replySubmitted[0] = true;
          geolocationManager.stopPositionUpdates(locationClient);
          pendingCurrentPositionLocationClients.remove(requestId);
          result.error(errorCode.toString(), errorCode.toDescription(), null);
        }
    );
  }

  /**
   * Cancels a request for the current position that was initiated with {@link #onGetCurrentPosition}.
   */
  private void onCancelGetCurrentPosition(MethodCall call, MethodChannel.Result result) {
    @SuppressWarnings("unchecked")
    Map<String, Object> arguments = (Map<String, Object>) call.arguments;
    String requestId = (String) arguments.get("requestId");

    LocationClient locationClient = this.pendingCurrentPositionLocationClients.get(requestId);
    if (locationClient != null) {
      locationClient.stopPositionUpdates();
    }
    this.pendingCurrentPositionLocationClients.remove(requestId);

    result.success(null);
  }

  @SuppressWarnings("unchecked")
  private void onStartPositionUpdates(MethodCall call, MethodChannel.Result result) {
    if (!streamHandler.hasActiveEventSink()) {
      result.error(
          ErrorCodes.errorWhileAcquiringPosition.toString(),
          "Position update event channel is not listening.",
          null);
      return;
    }

    try {
      if (!permissionManager.hasPermission(context)) {
        result.error(
            ErrorCodes.permissionDenied.toString(),
            ErrorCodes.permissionDenied.toDescription(),
            null);
        return;
      }
    } catch (PermissionUndefinedException e) {
      result.error(
          ErrorCodes.permissionDefinitionsNotFound.toString(),
          ErrorCodes.permissionDefinitionsNotFound.toDescription(),
          null);
      return;
    }

    Map<String, Object> map = (Map<String, Object>) call.arguments;
    String requestId = map == null ? null : (String) map.get("requestId");
    if (requestId == null || requestId.isEmpty()) {
      result.error("ARGUMENT_ERROR", "requestId is required.", null);
      return;
    }

    boolean forceLocationManager = false;
    if (map != null && map.get("forceLocationManager") != null) {
      forceLocationManager = (boolean) map.get("forceLocationManager");
    }

    LocationOptions locationOptions = LocationOptions.parseArguments(map);
    ForegroundNotificationOptions foregroundNotificationOptions = null;
    if (map != null) {
      foregroundNotificationOptions =
          ForegroundNotificationOptions.parseArguments(
              (Map<String, Object>) map.get("foregroundNotificationConfig"));
    }

    if (foregroundNotificationOptions != null) {
      if (foregroundLocationService == null) {
        result.error("LOCATION_SERVICE_NOT_INITIALIZED", "Foreground service is not initialized.", null);
        return;
      }

      String leaseId = foregroundLocationService.acquireStreamForegroundMode(foregroundNotificationOptions);
      if (leaseId == null) {
        result.error("ARGUMENT_ERROR", "foregroundNotificationConfig is required.", null);
        return;
      }

      pendingForegroundStreamLeases.put(requestId, leaseId);

      boolean started =
          foregroundLocationService.startLocationService(
              requestId,
              forceLocationManager,
              locationOptions,
              (Location location) -> streamHandler.emitPosition(requestId, location),
              (ErrorCodes errorCodes) -> {
                streamHandler.emitError(requestId, errorCodes);
                stopPositionUpdatesInternal(requestId);
              }
          );

      if (!started) {
        pendingForegroundStreamLeases.remove(requestId);
        foregroundLocationService.releaseStreamForegroundMode(leaseId);
        result.success(null);
        streamHandler.emitError(requestId, ErrorCodes.errorWhileAcquiringPosition);
        return;
      }

      result.success(null);
      return;
    }

    LocationClient locationClient =
        geolocationManager.createLocationClient(
            context,
            forceLocationManager,
            locationOptions
        );

    pendingPositionStreamLocationClients.put(requestId, locationClient);

    geolocationManager.startPositionUpdates(
        locationClient,
        activity,
        (Location location) -> streamHandler.emitPosition(requestId, location),
        (ErrorCodes errorCodes) -> {
          streamHandler.emitError(requestId, errorCodes);
          stopPositionUpdatesInternal(requestId);
        }
    );

    result.success(null);
  }

  @SuppressWarnings("unchecked")
  private void onStopPositionUpdates(MethodCall call, MethodChannel.Result result) {
    Map<String, Object> arguments = (Map<String, Object>) call.arguments;
    String requestId = arguments == null ? null : (String) arguments.get("requestId");
    if (requestId == null || requestId.isEmpty()) {
      result.error("ARGUMENT_ERROR", "requestId is required.", null);
      return;
    }

    stopPositionUpdatesInternal(requestId);
    result.success(null);
  }

  private void stopPositionUpdatesInternal(String requestId) {
    LocationClient locationClient = pendingPositionStreamLocationClients.remove(requestId);
    if (locationClient != null) {
      geolocationManager.stopPositionUpdates(locationClient);
    }

    String leaseId = pendingForegroundStreamLeases.remove(requestId);
    if (leaseId != null && foregroundLocationService != null) {
      foregroundLocationService.stopLocationService(requestId);
      foregroundLocationService.releaseStreamForegroundMode(leaseId);
    }
  }

  private void stopAllPositionStreams() {
    for (String requestId : new HashMap<>(pendingPositionStreamLocationClients).keySet()) {
      stopPositionUpdatesInternal(requestId);
    }

    for (String requestId : new HashMap<>(pendingForegroundStreamLeases).keySet()) {
      stopPositionUpdatesInternal(requestId);
    }
  }

  @SuppressWarnings("unchecked")
  private void onAquireForegroundMode(MethodCall call, MethodChannel.Result result) {
    if (foregroundLocationService == null) {
      result.error("LOCATION_SERVICE_NOT_INITIALIZED", "Foreground service is not initialized.", null);
      return;
    }

    Map<String, Object> arguments = (Map<String, Object>) call.arguments;
    ForegroundNotificationOptions options =
        ForegroundNotificationOptions.parseArguments(
        arguments == null
          ? null
          : (Map<String, Object>) arguments.get("foregroundNotificationConfig")
      );

    String leaseId = foregroundLocationService.acquireExplicitForegroundMode(options);
    if (leaseId == null) {
      result.error(
          "ARGUMENT_ERROR",
          "No foreground settings available. Call updateForegroundSettings or provide foregroundNotificationConfig.",
          null
        );
      return;
    }

    result.success(leaseId);
  }

  @SuppressWarnings("unchecked")
  private void onUpdateForegroundSettings(MethodCall call, MethodChannel.Result result) {
    if (foregroundLocationService == null) {
      result.error("LOCATION_SERVICE_NOT_INITIALIZED", "Foreground service is not initialized.", null);
      return;
    }

    Map<String, Object> arguments = (Map<String, Object>) call.arguments;
    ForegroundNotificationOptions options =
        ForegroundNotificationOptions.parseArguments(
        arguments == null
          ? null
          : (Map<String, Object>) arguments.get("foregroundNotificationConfig")
      );

    if (options == null) {
      result.error("ARGUMENT_ERROR", "foregroundNotificationConfig is required.", null);
      return;
    }

    foregroundLocationService.changeNotificationOptions(options);
    result.success(null);
  }

  private void onReleaseForegroundMode(MethodCall call, MethodChannel.Result result) {
    if (foregroundLocationService == null) {
      result.error("LOCATION_SERVICE_NOT_INITIALIZED", "Foreground service is not initialized.", null);
      return;
    }

    @SuppressWarnings("unchecked")
    Map<String, Object> arguments = (Map<String, Object>) call.arguments;
    String leaseId = arguments == null ? null : (String) arguments.get("leaseId");
    if (leaseId == null || leaseId.isEmpty()) {
      result.error("ARGUMENT_ERROR", "leaseId is required.", null);
      return;
    }

    boolean released = foregroundLocationService.releaseExplicitForegroundMode(leaseId);
    if (!released) {
      result.error("INVALID_FOREGROUND_LEASE", "Unknown explicit foreground lease id.", null);
      return;
    }

    result.success(null);
  }
}
