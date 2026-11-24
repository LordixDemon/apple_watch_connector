import '../../l10n/strings.dart';
import 'package:flutter/cupertino.dart';
import 'package:provider/provider.dart';
import '../../models/watch_telemetry.dart';
import '../../providers/watch_provider.dart';
import '../../theme/ios_colors.dart';
import '../../widgets/ios_list_section.dart';
import '../../widgets/ios_list_tile.dart';
import '../../widgets/watch_telemetry_panel.dart';

class StorageScreen extends StatelessWidget {
  const StorageScreen({super.key});
  @override
  Widget build(BuildContext context) {
    final s = Strings.of(context);
    final provider = context.watch<WatchProvider>();
    final watch = provider.device;
    final data = watch.telemetry;
    return CupertinoPageScaffold(
      backgroundColor: IosColors.systemBackground,
      navigationBar: CupertinoNavigationBar(
        backgroundColor: const Color(0xCC121212),
        middle: Text(s.storage),
        previousPageTitle: s.general,
      ),
      child: SafeArea(
        child: ListView(
          children: [
            Padding(
              padding: const EdgeInsets.all(16),
              child: WatchTelemetryPanel(
                telemetry: data,
                connected: watch.isConnected,
                refresh: provider.refreshDeviceInfo,
              ),
            ),
            IosListSection(
              header: s.watchStorage,
              children: [
                IosListTile(
                  title: s.available,
                  trailingText: WatchTelemetry.formatBytes(
                    data.availableStorageBytes,
                  ),
                  showChevron: false,
                ),
                IosListTile(
                  title: s.reclaimable,
                  trailingText: WatchTelemetry.formatBytes(
                    data.purgeableSpaceBytes,
                  ),
                  showChevron: false,
                ),
                IosListTile(
                  title: s.purgeableData,
                  trailingText: WatchTelemetry.formatBytes(
                    data.userDeletableSpaceBytes,
                  ),
                  showChevron: false,
                ),
              ],
            ),
            IosListSection(
              header: s.watchContent,
              children: [
                IosListTile(
                  title: s.userApps,
                  trailingText: data.numberOfApps?.toString() ?? '—',
                  showChevron: false,
                ),
                IosListTile(
                  title: s.songs,
                  trailingText: data.numberOfSongs?.toString() ?? '—',
                  showChevron: false,
                ),
                IosListTile(
                  title: s.photos,
                  trailingText: data.numberOfPhotos?.toString() ?? '—',
                  showChevron: false,
                ),
              ],
            ),
          ],
        ),
      ),
    );
  }
}
