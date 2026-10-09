import 'package:flutter/foundation.dart';
import 'bridge_transport.dart';
import 'companion_platform.dart';
import 'rust/rust_core_api.dart';
import 'rust/rust_bridge_transport.dart';
import 'watch_bridge_service.dart';

/// One composition boundary. Widgets/providers do not select OS backends.
WatchBridgeService createCompanionBackend({
  TargetPlatform? platform,
  CoreApi Function()? coreFactory,
}) {
  final target = platform ?? defaultTargetPlatform;
  if (target == TargetPlatform.android) return WatchBridgeService();
  BridgeTransport transport;
  try {
    if (target != TargetPlatform.macOS &&
        target != TargetPlatform.linux &&
        target != TargetPlatform.windows) {
      throw UnsupportedError('Desktop Bluetooth backend is unavailable');
    }
    transport = RustBridgeTransport((coreFactory ?? RustCoreApi.open)());
  } catch (error) {
    transport = UnavailableRustTransport(error.toString());
  }
  return WatchBridgeService(
    transport: transport,
    platform: CompanionPlatform.desktop,
  );
}
