package com.baseflow.geolocator;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.Notification;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.location.Location;
import android.net.wifi.WifiManager;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.util.Log;

import androidx.annotation.Nullable;

import com.baseflow.geolocator.errors.ErrorCodes;
import com.baseflow.geolocator.errors.ErrorCallback;
import com.baseflow.geolocator.location.BackgroundNotification;
import com.baseflow.geolocator.location.ForegroundNotificationOptions;
import com.baseflow.geolocator.location.GeolocationManager;
import com.baseflow.geolocator.location.LocationClient;
import com.baseflow.geolocator.location.LocationOptions;
import com.baseflow.geolocator.location.PositionChangedCallback;

import io.flutter.plugin.common.EventChannel;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class GeolocatorLocationService extends Service {
  private static final String TAG = "FlutterGeolocator";
  private static final int ONGOING_NOTIFICATION_ID = 75415;
  private static final String CHANNEL_ID = "geolocator_channel_01";
  private final String WAKELOCK_TAG = "GeolocatorLocationService:Wakelock";
  private final String WIFILOCK_TAG = "GeolocatorLocationService:WifiLock";
  private final LocalBinder binder = new LocalBinder(this);
  // Service is foreground
  private boolean isForeground = false;
  private int connectedEngines = 0;
  private int listenerCount = 0;
  @Nullable private Activity activity = null;
  @Nullable private GeolocationManager geolocationManager = null;
  private final Map<String, LocationClient> foregroundLocationClients = new ConcurrentHashMap<>();

  @Nullable private PowerManager.WakeLock wakeLock = null;
  @Nullable private WifiManager.WifiLock wifiLock = null;

  @Nullable private BackgroundNotification backgroundNotification = null;
  @Nullable private ForegroundNotificationOptions foregroundNotificationOptions = null;
  private final Set<String> explicitForegroundLeaseIds = new HashSet<>();
  private final Set<String> streamForegroundLeaseIds = new HashSet<>();

  @Override
  public void onCreate() {
    super.onCreate();
    Log.d(TAG, "Creating service.");
  }

  @Override
  public int onStartCommand(Intent intent, int flags, int startId) {
    return START_STICKY;
  }

  @Nullable
  @Override
  public IBinder onBind(Intent intent) {
    Log.d(TAG, "Binding to location service.");
    return binder;
  }

  @Override
  public boolean onUnbind(Intent intent) {
    Log.d(TAG, "Unbinding from location service.");
    return super.onUnbind(intent);
  }

  @Override
  public void onDestroy() {
    Log.d(TAG, "Destroying location service.");

    stopLocationService();
    disableBackgroundMode();
    geolocationManager = null;
    backgroundNotification = null;

    Log.d(TAG, "Destroyed location service.");
    super.onDestroy();
  }

  public boolean canStopLocationService(boolean cancellationRequested) {
    if (cancellationRequested) {
      return listenerCount == 1;
    }
    return connectedEngines == 0;
  }

  public void flutterEngineConnected() {

    connectedEngines++;
    Log.d(TAG, "Flutter engine connected. Connected engine count " + connectedEngines);
  }

  public void flutterEngineDisconnected() {

    connectedEngines--;
    Log.d(TAG, "Flutter engine disconnected. Connected engine count " + connectedEngines);
  }

  public boolean startLocationService(
      String requestId,
      boolean forceLocationManager,
      LocationOptions locationOptions,
      PositionChangedCallback positionChangedCallback,
      ErrorCallback errorCallback) {

    if (foregroundLocationClients.containsKey(requestId)) {
      return true;
    }

    if (geolocationManager == null) {
      return false;
    }

    LocationClient locationClient =
        geolocationManager.createLocationClient(
            this.getApplicationContext(),
            forceLocationManager,
            locationOptions
        );

    foregroundLocationClients.put(requestId, locationClient);
    listenerCount++;

    geolocationManager.startPositionUpdates(
        locationClient,
        activity,
        positionChangedCallback,
        errorCallback
    );

    return true;
  }

  public void stopLocationService(String requestId) {
    Log.d(TAG, "Stopping location service for request " + requestId);

    if (geolocationManager == null) {
      return;
    }

    LocationClient locationClient = foregroundLocationClients.remove(requestId);
    if (locationClient != null) {
      listenerCount = Math.max(0, listenerCount - 1);
      geolocationManager.stopPositionUpdates(locationClient);
    }
  }

  public void stopLocationService() {
    if (geolocationManager == null) {
      foregroundLocationClients.clear();
      listenerCount = 0;
      return;
    }

    for (LocationClient locationClient : foregroundLocationClients.values()) {
      geolocationManager.stopPositionUpdates(locationClient);
    }

    foregroundLocationClients.clear();
    listenerCount = 0;
  }

  @Nullable
  public String acquireExplicitForegroundMode(@Nullable ForegroundNotificationOptions options) {
    boolean isEnabled = ensureForegroundModeEnabled(options);
    if (!isEnabled) {
      return null;
    }

    String leaseId = UUID.randomUUID().toString();
    explicitForegroundLeaseIds.add(leaseId);
    return leaseId;
  }

  public boolean releaseExplicitForegroundMode(String leaseId) {
    boolean wasRemoved = explicitForegroundLeaseIds.remove(leaseId);
    if (!wasRemoved) {
      return false;
    }

    disableBackgroundModeIfNeeded();
    return true;
  }

  @Nullable
  public String acquireStreamForegroundMode(@Nullable ForegroundNotificationOptions options) {
    boolean isEnabled = ensureForegroundModeEnabled(options);
    if (!isEnabled) {
      return null;
    }

    String leaseId = UUID.randomUUID().toString();
    streamForegroundLeaseIds.add(leaseId);
    return leaseId;
  }

  public boolean releaseStreamForegroundMode(String leaseId) {
    boolean wasRemoved = streamForegroundLeaseIds.remove(leaseId);
    if (!wasRemoved) {
      return false;
    }

    disableBackgroundModeIfNeeded();
    return true;
  }

  @SuppressWarnings("deprecation")
  public void disableBackgroundMode() {
    explicitForegroundLeaseIds.clear();
    streamForegroundLeaseIds.clear();
    disableBackgroundModeInternal();
  }

  private boolean ensureForegroundModeEnabled(@Nullable ForegroundNotificationOptions options) {
    if (options != null) {
      foregroundNotificationOptions = options;
    }

    if (foregroundNotificationOptions == null) {
      Log.e(TAG, "Cannot enable foreground mode without foreground settings.");
      return false;
    }

    if (backgroundNotification != null) {
      Log.d(TAG, "Service already in foreground mode.");
      changeNotificationOptions(foregroundNotificationOptions);
    } else {
      Log.d(TAG, "Start service in foreground mode.");

      backgroundNotification = new BackgroundNotification(
          this.getApplicationContext(),
          CHANNEL_ID,
          ONGOING_NOTIFICATION_ID,
          foregroundNotificationOptions
      );

      backgroundNotification.updateChannel(
          foregroundNotificationOptions.getNotificationChannelName()
      );

      Notification notification = backgroundNotification.build();
      startForeground(ONGOING_NOTIFICATION_ID, notification);
      isForeground = true;
    }

    obtainWakeLocks(foregroundNotificationOptions);
    return true;
  }

  private void disableBackgroundModeIfNeeded() {
    if (!explicitForegroundLeaseIds.isEmpty() || !streamForegroundLeaseIds.isEmpty()) {
      return;
    }

    disableBackgroundModeInternal();
  }

  @SuppressWarnings("deprecation")
  private void disableBackgroundModeInternal() {
    if (isForeground) {
      Log.d(TAG, "Stop service in foreground.");
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
        stopForeground(Service.STOP_FOREGROUND_REMOVE);
      } else {
        stopForeground(true);
      }
      releaseWakeLocks();
      isForeground = false;
      backgroundNotification = null;
    }
  }

  public void changeNotificationOptions(ForegroundNotificationOptions options) {
    foregroundNotificationOptions = options;
    if (backgroundNotification != null) {
      backgroundNotification.updateOptions(options, isForeground);
      obtainWakeLocks(options);
    }
  }

  public void setActivity(@Nullable Activity activity) {
    this.activity = activity;
  }

  public void setGeolocationManager(@Nullable GeolocationManager geolocationManager) {
    this.geolocationManager = geolocationManager;
  }

  private void releaseWakeLocks() {
    if (wakeLock != null && wakeLock.isHeld()) {
      wakeLock.release();
      wakeLock = null;
    }
    if (wifiLock != null && wifiLock.isHeld()) {
      wifiLock.release();
      wifiLock = null;
    }
  }

  @SuppressLint("WakelockTimeout")
  private void obtainWakeLocks(ForegroundNotificationOptions options) {
    releaseWakeLocks();
    if (options.isEnableWakeLock()) {
      PowerManager powerManager =
          (PowerManager) getApplicationContext().getSystemService(Context.POWER_SERVICE);
      if (powerManager != null) {
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKELOCK_TAG);
        wakeLock.setReferenceCounted(false);
        wakeLock.acquire();
      }
    }
    if (options.isEnableWifiLock()) {
      WifiManager wifiManager =
          (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
      if (wifiManager != null) {
        wifiLock = wifiManager.createWifiLock(getWifiLockType(), WIFILOCK_TAG);
        wifiLock.setReferenceCounted(false);
        wifiLock.acquire();
      }
    }
  }

  @SuppressWarnings("deprecation")
  private int getWifiLockType() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
      return WifiManager.WIFI_MODE_FULL_HIGH_PERF;
    }
    return WifiManager.WIFI_MODE_FULL_LOW_LATENCY;
  }

  class LocalBinder extends Binder {
    private final GeolocatorLocationService locationService;

    LocalBinder(GeolocatorLocationService locationService) {
      this.locationService = locationService;
    }

    public GeolocatorLocationService getLocationService() {
      return locationService;
    }
  }
}
