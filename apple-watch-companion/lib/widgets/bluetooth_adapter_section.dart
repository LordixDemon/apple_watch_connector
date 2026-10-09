import 'package:flutter/cupertino.dart';
import '../l10n/strings.dart';
import '../providers/watch_connection_provider.dart';
import '../theme/ios_colors.dart';
import 'ios_list_section.dart';
import 'ios_list_tile.dart';

/// Shared connection UI; exposed by capability rather than OS checks in widgets.
class BluetoothAdapterSection extends StatelessWidget {
  final WatchConnectionProvider connection;
  const BluetoothAdapterSection({super.key, required this.connection});

  @override
  Widget build(BuildContext context) {
    final state = connection.state, s = Strings.current;
    if (!state.adapterSelectionAvailable) return const SizedBox.shrink();
    final editable = state.canSelectAdapter && !connection.sending;
    final error = switch (state.adapterSelectionError) {
      'ADAPTER_PREFERENCE_FAILED' => s.bluetoothAdapterSaveFailed,
      'ADAPTER_DISCOVERY_FAILED' => s.bluetoothAdapterDiscoveryFailed,
      _ => '',
    };
    final hint = state.adapterSelectionBusy
        ? s.bluetoothAdapterDisconnectHint
        : switch (state.adapterSelectionStatus) {
            'LOADING' => s.bluetoothAdaptersLoading,
            'MISSING' => s.bluetoothAdapterMissingHint,
            'ERROR' => error,
            _ =>
              state.bluetoothAdapters.isEmpty
                  ? s.bluetoothAdaptersEmpty
                  : s.bluetoothAdapterChoiceHint,
          };
    return IosListSection(
      header: s.bluetoothAdapter,
      footer: hint,
      children: [
        if (state.adapterSelectionStatus == 'MISSING')
          IosListTile(
            title: s.bluetoothAdapterMissing,
            subtitle: state.selectedAdapterId.toUpperCase(),
            showChevron: false,
          ),
        for (final adapter in state.bluetoothAdapters)
          IosListTile(
            key: ValueKey('bluetooth-adapter-${adapter.id}'),
            title: adapter.name.isEmpty ? s.bluetoothController : adapter.name,
            subtitle:
                '${adapter.controller} · ${adapter.address.toUpperCase()}',
            icon: CupertinoIcons.bluetooth,
            iconBackgroundColor: IosColors.systemBlue,
            showChevron: false,
            trailing: state.selectedAdapterId == adapter.id
                ? const Icon(
                    CupertinoIcons.check_mark,
                    color: IosColors.systemBlue,
                  )
                : null,
            onTap: editable && state.selectedAdapterId != adapter.id
                ? () => connection.selectAdapter(adapter.id)
                : null,
          ),
        IosListTile(
          title: s.refreshBluetoothAdapters,
          icon: CupertinoIcons.refresh,
          showChevron: false,
          trailing: state.adapterInventoryRefreshing
              ? const CupertinoActivityIndicator()
              : null,
          enabled: editable && !state.adapterInventoryRefreshing,
          onTap: editable && !state.adapterInventoryRefreshing
              ? connection.refreshAdapters
              : null,
        ),
      ],
    );
  }
}
