import '../../l10n/strings.dart';
import 'package:flutter/cupertino.dart';
import 'package:flutter/services.dart';
import 'package:provider/provider.dart';
import '../../providers/watch_provider.dart';
import '../../theme/ios_colors.dart';
import '../../widgets/ios_list_section.dart';
import '../../widgets/ios_list_tile.dart';
import '../../models/watch_telemetry.dart';
import '../../widgets/watch_telemetry_panel.dart';

class AboutDeviceScreen extends StatefulWidget {
  const AboutDeviceScreen({super.key});

  @override
  State<AboutDeviceScreen> createState() => _AboutDeviceScreenState();
}

class _AboutDeviceScreenState extends State<AboutDeviceScreen> {
  String? _copiedLabel;

  bool _isKnown(String value) => value.isNotEmpty && value != '—';

  Future<void> _copyValue(String label, String value) async {
    await Clipboard.setData(ClipboardData(text: value));
    if (!mounted) return;
    setState(() => _copiedLabel = label);
  }

  @override
  Widget build(BuildContext context) {
    final provider = context.watch<WatchProvider>();
    final watch = provider.device;
    final data = watch.telemetry;
    final s = Strings.of(context);

    final hasMaterial = _isKnown(watch.caseMaterial);
    final hasSize = _isKnown(watch.caseSize);
    final caseValue = hasMaterial && hasSize
        ? '${watch.caseMaterial} • ${watch.caseSize}'
        : hasMaterial
        ? watch.caseMaterial
        : watch.caseSize;

    final hasBluetooth = _isKnown(watch.bluetoothAddress);
    final hasWifi = _isKnown(watch.wifiAddress);
    final hasSeId = _isKnown(watch.seId);
    final hasNetworkSection = hasBluetooth || hasWifi || hasSeId;

    return CupertinoPageScaffold(
      backgroundColor: IosColors.systemBackground,
      navigationBar: CupertinoNavigationBar(
        backgroundColor: const Color(0xCC121212),
        middle: Text(s.about),
        previousPageTitle: s.general,
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
            if (_copiedLabel != null)
              Padding(
                padding: const EdgeInsets.fromLTRB(20, 8, 20, 0),
                child: Text(
                  '${s.copiedToClipboard}: $_copiedLabel',
                  style: const TextStyle(
                    color: IosColors.systemGreen,
                    fontSize: 13,
                  ),
                ),
              ),
            IosListSection(
              children: [
                IosListTile(
                  title: s.name,
                  trailingText: watch.name,
                  showChevron: false,
                ),
                IosListTile(
                  title: s.model,
                  trailingText: watch.model,
                  showChevron: false,
                ),
                IosListTile(
                  title: s.modelNumber,
                  trailingText: watch.modelIdentifier,
                  showChevron: false,
                  onTap: _isKnown(watch.modelIdentifier)
                      ? () => _copyValue(s.modelNumber, watch.modelIdentifier)
                      : null,
                ),
                if (hasMaterial || hasSize)
                  IosListTile(
                    title: s.watchCase,
                    trailingText: caseValue,
                    showChevron: false,
                  ),
                IosListTile(
                  title: s.watchosVersion,
                  trailingText: watch.watchOsVersion,
                  showChevron: false,
                  onTap: _isKnown(watch.watchOsVersion)
                      ? () => _copyValue(s.watchosVersion, watch.watchOsVersion)
                      : null,
                ),
                if (_isKnown(watch.serialNumber))
                  IosListTile(
                    title: s.serialNumber,
                    trailingText: watch.serialNumber,
                    showChevron: false,
                    onTap: () => _copyValue(s.serialNumber, watch.serialNumber),
                  ),
                if (_isKnown(watch.id))
                  IosListTile(
                    title: s.pairedIdentity,
                    trailingText: watch.id,
                    showChevron: false,
                    onTap: () => _copyValue(s.pairedIdentity, watch.id),
                  ),
              ],
            ),
            if (hasNetworkSection)
              IosListSection(
                header: s.networkAddresses,
                children: [
                  if (hasBluetooth)
                    IosListTile(
                      title: s.bluetooth,
                      trailingText: watch.bluetoothAddress,
                      showChevron: false,
                      onTap: () =>
                          _copyValue(s.bluetooth, watch.bluetoothAddress),
                    ),
                  if (hasWifi)
                    IosListTile(
                      title: s.wiFiAddress,
                      trailingText: watch.wifiAddress,
                      showChevron: false,
                      onTap: () => _copyValue(s.wiFiAddress, watch.wifiAddress),
                    ),
                  if (hasSeId)
                    IosListTile(
                      title: 'SEID',
                      trailingText: watch.seId,
                      showChevron: false,
                      onTap: () => _copyValue('SEID', watch.seId),
                    ),
                ],
              ),
            IosListSection(
              header: s.storage,
              children: [
                IosListTile(
                  title: s.available,
                  trailingText: WatchTelemetry.formatBytes(
                    data.availableStorageBytes,
                  ),
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
                IosListTile(
                  title: s.userApps,
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
