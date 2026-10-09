import 'package:flutter/material.dart';
import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';
import 'services/settings_storage.dart';
import 'app/companion_app.dart';
import 'app/companion_scope.dart';
export 'app/companion_app.dart';

Future<void> main() async {
  WidgetsFlutterBinding.ensureInitialized();

  // System bars belong to the Android shell; macOS uses its native window.
  if (defaultTargetPlatform == TargetPlatform.android) {
    SystemChrome.setSystemUIOverlayStyle(
      const SystemUiOverlayStyle(
        statusBarColor: Colors.transparent,
        statusBarIconBrightness: Brightness.light,
        systemNavigationBarColor: Color(0xFF121212),
        systemNavigationBarIconBrightness: Brightness.light,
      ),
    );
  }

  final storage = await SettingsStorage.init();
  runApp(
    CompanionScope(storage: storage, child: const AppleWatchCompanionApp()),
  );
}
