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
    final provider = context.watch<WatchProvider>();
    final watch = provider.device;
    final data = watch.telemetry;
    return CupertinoPageScaffold(
      backgroundColor: IosColors.systemBackground,
      navigationBar: CupertinoNavigationBar(
        backgroundColor: Color(0xCC121212),
        middle: Text(Strings.current.storage),
        previousPageTitle: Strings.current.general,
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
              header: Strings.current.watchStorage,
              children: [
                IosListTile(
                  title: Strings.current.available,
                  trailingText: WatchTelemetry.formatBytes(
                    data.availableStorageBytes,
                  ),
                  showChevron: false,
                ),
                IosListTile(
                  title: Strings.current.reclaimable,
                  trailingText: WatchTelemetry.formatBytes(
                    data.purgeableSpaceBytes,
                  ),
                  showChevron: false,
                ),
                IosListTile(
                  title: Strings.current.purgeableData,
                  trailingText: WatchTelemetry.formatBytes(
                    data.userDeletableSpaceBytes,
                  ),
                  showChevron: false,
                ),
              ],
            ),
            IosListSection(
              header: Strings.current.watchContent,
              children: [
                IosListTile(
                  title: Strings.current.userApps,
                  trailingText: data.numberOfApps?.toString() ?? '—',
                  showChevron: false,
                ),
                IosListTile(
                  title: Strings.current.songs,
                  trailingText: data.numberOfSongs?.toString() ?? '—',
                  showChevron: false,
                ),
                IosListTile(
                  title: Strings.current.photos,
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
