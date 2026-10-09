import 'package:flutter/widgets.dart';
import 'package:provider/provider.dart';
import '../providers/watch_provider.dart';
import '../providers/watch_connection_provider.dart';
import '../services/settings_storage.dart';
import '../services/watch_bridge_service.dart';
import '../services/watch_face_api_service.dart';
import '../services/companion_backend.dart';

/// Application composition root. The bridge is shared and disposed once here.
class CompanionScope extends StatelessWidget {
  final SettingsStorage storage;
  final WatchBridgeService Function()? bridgeFactory;
  final WatchFaceApiService Function()? faceApiFactory;
  final Widget child;

  const CompanionScope({
    super.key,
    required this.storage,
    required this.child,
    this.bridgeFactory,
    this.faceApiFactory,
  });

  @override
  Widget build(BuildContext context) => MultiProvider(
    providers: [
      Provider<WatchBridgeService>(
        create: (_) => (bridgeFactory ?? createCompanionBackend)(),
        dispose: (_, bridge) => bridge.dispose(),
      ),
      ChangeNotifierProvider<WatchProvider>(
        create: (context) => WatchProvider(
          storage: storage,
          bridge: context.read<WatchBridgeService>(),
          apiService: faceApiFactory?.call(),
          ownsApiService: true,
        ),
      ),
      ChangeNotifierProvider<WatchConnectionProvider>(
        create: (context) =>
            WatchConnectionProvider(bridge: context.read<WatchBridgeService>()),
      ),
    ],
    child: child,
  );
}
