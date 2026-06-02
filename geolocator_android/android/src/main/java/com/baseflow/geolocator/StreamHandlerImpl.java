package com.baseflow.geolocator;

import android.content.Context;
import android.location.Location;
import android.util.Log;

import androidx.annotation.Nullable;

import com.baseflow.geolocator.errors.ErrorCodes;
import com.baseflow.geolocator.location.GeolocationManager;
import com.baseflow.geolocator.location.LocationMapper;
import com.baseflow.geolocator.permission.PermissionManager;

import java.util.HashMap;
import java.util.Map;

import io.flutter.plugin.common.BinaryMessenger;
import io.flutter.plugin.common.EventChannel;

class StreamHandlerImpl implements EventChannel.StreamHandler {
  private static final String TAG = "FlutterGeolocator";

  private final PermissionManager permissionManager;
  private final GeolocationManager geolocationManager;

  @Nullable private EventChannel channel;
  @Nullable private EventChannel.EventSink events;

  public StreamHandlerImpl(
      PermissionManager permissionManager,
      GeolocationManager geolocationManager) {
    this.permissionManager = permissionManager;
    this.geolocationManager = geolocationManager;
  }

  /**
   * Registers this instance as event stream handler on the given {@code messenger}.
   *
   * <p>Stops any previously started and unstopped calls.
   *
   * <p>This should be cleaned with {@link #stopListening} once the messenger is disposed of.
   */
  void startListening(Context context, BinaryMessenger messenger) {
    if (channel != null) {
      Log.w(TAG, "Setting a event call handler before the last was disposed.");
      stopListening();
    }

    channel = new EventChannel(messenger, "flutter.baseflow.com/geolocator_updates_android");
    channel.setStreamHandler(this);
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

    events = null;
    channel.setStreamHandler(null);
    channel = null;
  }

  @Override
  public void onListen(Object arguments, EventChannel.EventSink events) {
    this.events = events;
  }

  @Override
  public void onCancel(Object arguments) {
    events = null;
  }

  public boolean hasActiveEventSink() {
    return events != null;
  }

  public void emitPosition(String requestId, Location location) {
    if (events == null) {
      return;
    }

    Map<String, Object> payload = new HashMap<>();
    payload.put("requestId", requestId);
    payload.put("eventType", "position");
    payload.put("position", LocationMapper.toHashMap(location));
    events.success(payload);
  }

  public void emitError(String requestId, ErrorCodes errorCodes) {
    if (events == null) {
      return;
    }

    Map<String, Object> payload = new HashMap<>();
    payload.put("requestId", requestId);
    payload.put("eventType", "error");
    payload.put("errorCode", errorCodes.toString());
    payload.put("errorDescription", errorCodes.toDescription());
    events.success(payload);
  }
}
