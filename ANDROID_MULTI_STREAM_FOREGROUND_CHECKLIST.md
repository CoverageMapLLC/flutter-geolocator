# Android Multi-Stream and Foreground Service Refactor Checklist

## 1) Contract and behavior decisions
- [x] Confirm behavior: multiple concurrent position subscriptions are supported on Android.
- [x] Confirm behavior: last foreground notification configuration wins.
- [x] Confirm behavior: canceling one subscription does not revert notification settings globally.
- [x] Confirm behavior: foreground service can be controlled independently through Android-specific API.
- [ ] Define precedence: explicit foreground-service API versus stream-level foreground configuration.
- [ ] Define backward-compatible behavior for legacy AndroidSettings.foregroundNotificationConfig.

## 2) Public API shape (Android-specific)
- [x] Add Android API to enable or acquire foreground service with ForegroundNotificationConfig.
- [x] Add Android API to update foreground notification configuration while service is active.
- [x] Add Android API to disable or release foreground service.
- [x] Add API documentation with clear ownership rules and examples.

## 3) Dart plugin changes (geolocator_android)
- [x] Remove single cached position stream behavior.
- [x] Implement per-request stream lifecycle with requestId for each getPositionStream call.
- [x] Maintain request registry in Dart (requestId -> StreamController).
- [x] Start native request on first listener for each logical stream.
- [x] Stop native request when that logical stream is canceled.
- [x] Demultiplex native events by requestId into the matching stream.
- [x] Preserve per-stream timeout behavior and error propagation.
- [x] Keep service status stream behavior unchanged.

## 4) Native Android architecture changes
- [x] Add method-channel methods: startPositionUpdates(requestId, settings).
- [x] Add method-channel methods: stopPositionUpdates(requestId).
- [x] Add method-channel methods for enable, update, disable foreground service.
- [x] Introduce request registry in service layer (requestId -> active client/request state).
- [x] Ensure each request has independent LocationClient lifecycle.
- [x] Replace single locationClient plus listenerCount model with per-request tracking.
- [x] Emit request-scoped events including requestId and event type.
- [x] Route native errors as request-scoped events so one request does not tear down all streams.

## 5) Foreground service ownership model
- [x] Implement foreground ownership tracking (lease or equivalent reference model).
- [x] Support explicit foreground API ownership independent from stream lifecycles.
- [x] Support stream-level foreground ownership for legacy foregroundNotificationConfig.
- [ ] Apply latest active notification settings immediately.
- [x] Do not revert to previous settings when one stream is canceled.
- [x] Stop foreground mode only when no active foreground owners remain.

## 6) Backward compatibility
- [x] Keep Geolocator.getPositionStream signature unchanged.
- [x] Preserve AndroidSettings fields and serialization format where possible.
- [x] Ensure apps using one stream continue to work without code changes.
- [x] Keep legacy foregroundNotificationConfig behavior functional with improved lifecycle semantics.
- [x] Review exception behavior around AlreadySubscribedException for Android flows.

## 7) Testing
- [x] Update Dart tests that currently expect same stream instance for repeated getPositionStream calls.
- [ ] Add tests for concurrent streams with different settings.
- [ ] Add tests for canceling one stream without affecting others.
- [ ] Add tests for request-scoped timeout and request-scoped errors.
- [ ] Add tests for last-configuration-wins notification updates.
- [ ] Add tests verifying no automatic notification rollback on stream cancel.
- [x] Add tests for explicit foreground API lifecycle (enable, update, disable).
- [ ] Add native unit tests for request registry add/remove and foreground ownership transitions.

## 8) Documentation and migration notes
- [x] Update geolocator_android README with new Android-specific foreground API.
- [ ] Add examples for switching accuracy/settings while app remains in background.
- [ ] Document that Android background restrictions still apply when starting foreground mode from background.
- [ ] Add changelog entries describing behavior updates and compatibility notes.

## 9) Validation and rollout
- [x] Run geolocator_android test suite.
- [ ] Run affected package tests across workspace.
- [ ] Verify example app behavior in foreground and background transitions.
- [ ] Verify notification updates while active streams are reconfigured.
- [ ] Final review for API docs, edge cases, and regression risks.
