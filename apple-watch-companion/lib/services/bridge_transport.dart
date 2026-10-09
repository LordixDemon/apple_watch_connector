import 'package:flutter/services.dart';

/// Platform boundary. Tests can supply events and receipts without a native host.
abstract interface class BridgeTransport {
  Stream<dynamic> get events;
  Future<dynamic> invoke(String method, [Map<String, dynamic>? arguments]);
}

/// Transports with owned resources are closed by the shared service once.
abstract interface class OwnedBridgeTransport implements BridgeTransport {
  void dispose();
}

final class PlatformBridgeTransport implements BridgeTransport {
  static const _methods = MethodChannel(
    'dev.applewatchandroid.companion/bridge',
  );
  static const _events = EventChannel('dev.applewatchandroid.companion/events');

  const PlatformBridgeTransport();

  @override
  Stream<dynamic> get events => _events.receiveBroadcastStream();

  @override
  Future<dynamic> invoke(String method, [Map<String, dynamic>? arguments]) =>
      _methods.invokeMethod<dynamic>(method, arguments);
}
