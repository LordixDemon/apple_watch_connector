import '../../l10n/strings.dart';
import 'package:flutter/cupertino.dart';
import 'package:provider/provider.dart';
import '../../providers/watch_provider.dart';
import '../../theme/ios_colors.dart';
import '../../widgets/native_watch_settings_panel.dart';

class OrientationScreen extends StatelessWidget {
  const OrientationScreen({super.key});
  @override
  Widget build(BuildContext context) {
    final provider = context.watch<WatchProvider>();
    return CupertinoPageScaffold(
      backgroundColor: IosColors.systemBackground,
      navigationBar: CupertinoNavigationBar(
        middle: Text(Strings.current.orientationTime),
        previousPageTitle: Strings.current.general,
      ),
      child: SafeArea(
        child: ListView(
          children: [
            const SizedBox(height: 12),
            NativeWatchSettingsPanel(
              settings: provider.nativeSettings,
              onChange: provider.setWatchSetting,
            ),
          ],
        ),
      ),
    );
  }
}
