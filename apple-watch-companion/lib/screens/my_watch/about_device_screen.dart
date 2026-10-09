import '../../l10n/strings.dart';
import 'package:flutter/cupertino.dart';
import 'package:flutter/material.dart';
import 'package:provider/provider.dart';
import '../../providers/watch_provider.dart';
import '../../theme/ios_colors.dart';
import '../../widgets/ios_list_section.dart';
import '../../widgets/ios_list_tile.dart';
import '../../models/watch_telemetry.dart';
import '../../widgets/watch_telemetry_panel.dart';

class AboutDeviceScreen extends StatelessWidget {
  const AboutDeviceScreen({super.key});

  @override
  Widget build(BuildContext context) {
    final provider = context.watch<WatchProvider>();
    final watch = provider.device;
    final data = watch.telemetry;

    return CupertinoPageScaffold(
      backgroundColor: IosColors.systemBackground,
      navigationBar: CupertinoNavigationBar(
        backgroundColor: Color(0xCC121212),
        middle: Text(Strings.current.about),
        previousPageTitle: Strings.current.general,
      ),
      child: SafeArea(
        child: ListView(
          children: [
            const SizedBox(height: 12),
            Padding(
              padding: const EdgeInsets.symmetric(horizontal: 16),
              child: WatchTelemetryPanel(
                telemetry: data,
                connected: watch.isConnected,
                refresh: provider.refreshDeviceInfo,
              ),
            ),
            IosListSection(
              children: [
                IosListTile(
                  title: Strings.current.name,
                  trailingText: watch.name,
                  showChevron: false,
                ),
                IosListTile(
                  title: Strings.current.model,
                  trailingText: watch.model,
                  showChevron: false,
                ),
                IosListTile(
                  title: Strings.current.modelNumber,
                  trailingText: watch.modelIdentifier,
                  showChevron: false,
                ),
                IosListTile(
                  title: Strings.current.watchCase,
                  trailingText: '${watch.caseMaterial} • ${watch.caseSize}',
                  showChevron: false,
                ),
                IosListTile(
                  title: Strings.current.watchosVersion,
                  trailingText: watch.watchOsVersion,
                  showChevron: false,
                ),
                IosListTile(
                  title: Strings.current.serialNumber,
                  trailingText: watch.serialNumber,
                  showChevron: false,
                ),
              ],
            ),
            IosListSection(
              header: Strings.current.networkAddresses,
              children: [
                IosListTile(
                  title: Strings.current.bluetooth,
                  trailingText: watch.bluetoothAddress,
                  showChevron: false,
                ),
                IosListTile(
                  title: Strings.current.wiFiAddress,
                  trailingText: watch.wifiAddress,
                  showChevron: false,
                ),
                IosListTile(
                  title: 'SEID',
                  trailingText: watch.seId.isNotEmpty ? watch.seId : '—',
                  showChevron: false,
                ),
              ],
            ),
            IosListSection(
              header: Strings.current.storage,
              children: [
                IosListTile(
                  title: Strings.current.available,
                  trailingText: WatchTelemetry.formatBytes(
                    data.availableStorageBytes,
                  ),
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
                IosListTile(
                  title: Strings.current.userApps,
                  trailingText: data.numberOfApps?.toString() ?? '—',
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
