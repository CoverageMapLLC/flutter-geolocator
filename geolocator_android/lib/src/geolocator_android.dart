import 'dart:async';

import 'package:flutter/services.dart';
import 'package:geolocator_android/geolocator_android.dart';
import 'package:geolocator_platform_interface/geolocator_platform_interface.dart';
import 'package:uuid/uuid.dart';

/// An implementation of [GeolocatorPlatform] that uses method channels.
class GeolocatorAndroid extends GeolocatorPlatform {
  /// The method channel used to interact with the native platform.
  static const _methodChannel = MethodChannel('flutter.baseflow.com/geolocator_android');

  /// The event channel used to receive [Position] updates from the native
  /// platform.
  static const _eventChannel = EventChannel('flutter.baseflow.com/geolocator_updates_android');

  /// The event channel used to receive [LocationServiceStatus] updates from the
  /// native platform.
  static const _serviceStatusEventChannel =
      EventChannel('flutter.baseflow.com/geolocator_service_updates_android');

  /// Registers this class as the default instance of [GeolocatorPlatform].
  static void registerWith() {
    GeolocatorPlatform.instance = GeolocatorAndroid();
  }

  /// On Android devices you can set [forcedLocationManager]
  /// to true to force the plugin to use the [LocationManager] to determine the
  /// position instead of the [FusedLocationProviderClient]. On iOS this is
  /// ignored.
  bool forcedLocationManager = false;

  Stream<ServiceStatus>? _serviceStatusStream;
  StreamSubscription<dynamic>? _positionEventSubscription;
  final Map<String, StreamController<Position>> _positionStreamControllers =
      <String, StreamController<Position>>{};

  final Uuid _uuid = const Uuid();

  @override
  Future<LocationPermission> checkPermission() async {
    try {
      // ignore: omit_local_variable_types
      final int permission = await _methodChannel.invokeMethod('checkPermission');

      return permission.toLocationPermission();
    } on PlatformException catch (e) {
      final error = _handlePlatformException(e);

      throw error;
    }
  }

  @override
  Future<LocationPermission> requestPermission() async {
    try {
      // ignore: omit_local_variable_types
      final int permission = await _methodChannel.invokeMethod('requestPermission');

      return permission.toLocationPermission();
    } on PlatformException catch (e) {
      final error = _handlePlatformException(e);

      throw error;
    }
  }

  @override
  Future<bool> isLocationServiceEnabled() async =>
      _methodChannel.invokeMethod<bool>('isLocationServiceEnabled').then((value) => value ?? false);

  @override
  Future<Position?> getLastKnownPosition({
    bool forceLocationManager = false,
  }) async {
    try {
      final parameters = <String, dynamic>{
        'forceLocationManager': forceLocationManager,
      };

      final positionMap = await _methodChannel.invokeMethod('getLastKnownPosition', parameters);

      return positionMap != null ? AndroidPosition.fromMap(positionMap) : null;
    } on PlatformException catch (e) {
      final error = _handlePlatformException(e);

      throw error;
    }
  }

  @override
  Future<LocationAccuracyStatus> getLocationAccuracy() async {
    final int accuracy = await _methodChannel.invokeMethod('getLocationAccuracy');
    return LocationAccuracyStatus.values[accuracy];
  }

  @override
  Future<Position> getCurrentPosition({
    LocationSettings? locationSettings,
    String? requestId,
  }) async {
    requestId = requestId ?? _uuid.v4();

    try {
      Future<dynamic> positionFuture;

      final Duration? timeLimit = locationSettings?.timeLimit;

      positionFuture = _methodChannel.invokeMethod(
        'getCurrentPosition',
        {
          ...?locationSettings?.toJson(),
          'requestId': requestId,
        },
      );

      if (timeLimit != null) {
        positionFuture = positionFuture.timeout(timeLimit);
      }

      final positionMap = await positionFuture;
      return AndroidPosition.fromMap(positionMap);
    } on TimeoutException {
      final parameters = <String, dynamic>{
        'requestId': requestId,
      };
      _methodChannel.invokeMethod(
        'cancelGetCurrentPosition',
        parameters,
      );
      rethrow;
    } on PlatformException catch (e) {
      final error = _handlePlatformException(e);

      throw error;
    }
  }

  @override
  Stream<ServiceStatus> getServiceStatusStream() {
    if (_serviceStatusStream != null) {
      return _serviceStatusStream!;
    }
    var serviceStatusStream = _serviceStatusEventChannel.receiveBroadcastStream();

    _serviceStatusStream = serviceStatusStream
        .map((dynamic element) => ServiceStatus.values[element as int])
        .handleError((error) {
      _serviceStatusStream = null;
      if (error is PlatformException) {
        error = _handlePlatformException(error);
      }
      throw error;
    });

    return _serviceStatusStream!;
  }

  @override
  Stream<Position> getPositionStream({
    LocationSettings? locationSettings,
  }) {
    final settings = locationSettings?.toJson();
    final timeLimit = locationSettings?.timeLimit;
    final requestId = _uuid.v4();
    Future<void>? startRequestFuture;

    late StreamController<Position> streamController;
    streamController = StreamController<Position>.broadcast(
      onListen: () {
        startRequestFuture = (() async {
          _ensurePositionEventSubscription();
          _positionStreamControllers[requestId] = streamController;

          try {
            await _methodChannel.invokeMethod<void>(
              'startPositionUpdates',
              <String, dynamic>{
                ...?settings,
                'requestId': requestId,
              },
            );
          } on MissingPluginException {
            // Backward-compatible no-op for environments that don't expose the
            // start/stop position update methods yet.
          } on PlatformException catch (e) {
            _positionStreamControllers.remove(requestId);
            streamController.addError(_handlePlatformException(e));
            await streamController.close();
            await _cancelPositionEventSubscriptionIfUnused();
          }
        })();
      },
      onCancel: () async {
        await startRequestFuture;
        await _stopPositionUpdates(requestId);
      },
    );

    var positionStream = streamController.stream;

    if (timeLimit != null) {
      positionStream = positionStream.timeout(
        timeLimit,
        onTimeout: (sink) {
          (startRequestFuture ?? Future<void>.value()).then((_) {
            _stopPositionUpdates(requestId);
          });
          sink.addError(
            TimeoutException(
              'Time limit reached while waiting for position update.',
              timeLimit,
            ),
          );
          sink.close();
        },
      );
    }

    return positionStream;
  }

  void _ensurePositionEventSubscription() {
    if (_positionStreamControllers.isEmpty && _positionEventSubscription != null) {
      _positionEventSubscription!.cancel();
      _positionEventSubscription = null;
    }

    if (_positionEventSubscription != null) {
      return;
    }

    _positionEventSubscription = _eventChannel.receiveBroadcastStream().listen(
      (dynamic event) {
        if (event is! Map) {
          return;
        }

        final data = event.cast<String, dynamic>();
        final eventType = data['eventType'] as String?;

        if (eventType == null && data.containsKey('latitude')) {
          final position = AndroidPosition.fromMap(data);
          for (final controller in _positionStreamControllers.values) {
            controller.add(position);
          }
          return;
        }

        final requestId = data['requestId'] as String?;
        if (requestId == null) {
          return;
        }

        final controller = _positionStreamControllers[requestId];
        if (controller == null) {
          return;
        }

        switch (eventType) {
          case 'position':
            final positionMap = data['position'];
            if (positionMap is Map) {
              controller.add(
                AndroidPosition.fromMap(positionMap.cast<String, dynamic>()),
              );
            }
            break;
          case 'error':
            final errorCode = data['errorCode'] as String? ?? 'LOCATION_UPDATE_FAILURE';
            final errorDescription = data['errorDescription'] as String?;
            final exception = _handlePlatformException(
              PlatformException(
                code: errorCode,
                message: errorDescription,
              ),
            );
            controller.addError(exception);
            break;
          default:
            break;
        }
      },
      onError: (dynamic error) {
        if (error is PlatformException) {
          error = _handlePlatformException(error);
        }
        for (final controller in _positionStreamControllers.values) {
          controller.addError(error);
        }
      },
      onDone: () {
        for (final controller in _positionStreamControllers.values) {
          controller.close();
        }
        _positionStreamControllers.clear();
        _positionEventSubscription = null;
      },
    );
  }

  Future<void> _stopPositionUpdates(String requestId) async {
    _positionStreamControllers.remove(requestId);

    try {
      await _methodChannel.invokeMethod<void>(
        'stopPositionUpdates',
        <String, dynamic>{
          'requestId': requestId,
        },
      );
    } on MissingPluginException {
      // Backward-compatible no-op for environments that don't expose the
      // start/stop position update methods yet.
    } on PlatformException {
      // Ignored intentionally; best effort cleanup.
    }

    await _cancelPositionEventSubscriptionIfUnused();
  }

  Future<void> _cancelPositionEventSubscriptionIfUnused() async {
    if (_positionStreamControllers.isNotEmpty || _positionEventSubscription == null) {
      return;
    }

    await _positionEventSubscription?.cancel();
    _positionEventSubscription = null;
  }

  @override
  Future<LocationAccuracyStatus> requestTemporaryFullAccuracy({
    required String purposeKey,
  }) async {
    try {
      final int status = await _methodChannel.invokeMethod(
        'requestTemporaryFullAccuracy',
        <String, dynamic>{
          'purposeKey': purposeKey,
        },
      );
      return LocationAccuracyStatus.values[status];
    } on PlatformException catch (e) {
      final error = _handlePlatformException(e);
      throw error;
    }
  }

  @override
  Future<bool> openAppSettings() async =>
      _methodChannel.invokeMethod<bool>('openAppSettings').then((value) => value ?? false);

  @override
  Future<bool> openLocationSettings() async =>
      _methodChannel.invokeMethod<bool>('openLocationSettings').then((value) => value ?? false);

  /// Acquires Android foreground mode.
  ///
  /// If [foregroundNotificationConfig] is omitted, the native layer will use
  /// previously stored foreground settings.
  Future<String> aquireForegroundMode({
    ForegroundNotificationConfig? foregroundNotificationConfig,
  }) async {
    final leaseId = await _methodChannel.invokeMethod<String>(
      'aquireForegroundMode',
      <String, dynamic>{
        'foregroundNotificationConfig': foregroundNotificationConfig?.toJson(),
      },
    );

    if (leaseId == null || leaseId.isEmpty) {
      throw PlatformException(
        code: 'INVALID_FOREGROUND_LEASE',
        message: 'No foreground lease id was returned by the platform.',
      );
    }

    return leaseId;
  }

  /// Updates foreground settings (notification and locks).
  ///
  /// This can be called before foreground mode is acquired. In that case,
  /// settings are persisted and used as defaults when foreground mode is
  /// acquired later.
  Future<void> updateForegroundSettings(
      {required ForegroundNotificationConfig foregroundNotificationConfig}) {
    return _methodChannel.invokeMethod<void>(
      'updateForegroundSettings',
      <String, dynamic>{
        'foregroundNotificationConfig': foregroundNotificationConfig.toJson(),
      },
    );
  }

  /// Releases Android foreground mode.
  Future<void> releaseForegroundMode({required String leaseId}) {
    return _methodChannel.invokeMethod<void>(
      'releaseForegroundMode',
      <String, dynamic>{
        'leaseId': leaseId,
      },
    );
  }

  Exception _handlePlatformException(PlatformException exception) {
    switch (exception.code) {
      case 'ACTIVITY_MISSING':
        return ActivityMissingException(exception.message);
      case 'LOCATION_SERVICES_DISABLED':
        return const LocationServiceDisabledException();
      case 'LOCATION_SUBSCRIPTION_ACTIVE':
        return const AlreadySubscribedException();
      case 'PERMISSION_DEFINITIONS_NOT_FOUND':
        return PermissionDefinitionsNotFoundException(exception.message);
      case 'PERMISSION_DENIED':
        return PermissionDeniedException(exception.message);
      case 'PERMISSION_REQUEST_IN_PROGRESS':
        return PermissionRequestInProgressException(exception.message);
      case 'LOCATION_UPDATE_FAILURE':
        return PositionUpdateException(exception.message);
      default:
        return exception;
    }
  }
}
