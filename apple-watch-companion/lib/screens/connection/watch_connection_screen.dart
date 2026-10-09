import 'package:flutter/cupertino.dart';
import 'package:provider/provider.dart';
import '../../l10n/strings.dart';
import '../../providers/watch_connection_provider.dart';
import '../../theme/ios_colors.dart';
import '../../widgets/ios_list_section.dart';
import '../../widgets/ios_list_tile.dart';
import '../../widgets/watch_connection_card.dart';
import '../../widgets/watch_setup_panel.dart';
import '../../widgets/bluetooth_adapter_section.dart';
import 'watch_diagnostics_screen.dart';
import 'optical_pairing_lab_screen.dart';
import '../../services/optical/optical_reader.dart';

class WatchConnectionScreen extends StatelessWidget {
  const WatchConnectionScreen({super.key});
  Future<void> _scan(BuildContext context) async {
    if (!context.read<WatchConnectionProvider>().platform.opticalPairing) {
      return;
    }
    final candidate = await Navigator.of(context).push<OpticalCandidate>(
      CupertinoPageRoute(
        builder: (_) => const OpticalPairingCameraScreen(recognize: true),
      ),
    );
    if (candidate == null || !context.mounted) return;
    final s = Strings.current;
    final connection = context.read<WatchConnectionProvider>();
    final previousPair = connection.state.hasPair
        ? connection.state.pairId
        : null;
    final replace = connection.state.hasPair;
    final accepted = await showCupertinoDialog<bool>(
      context: context,
      builder: (ctx) => CupertinoAlertDialog(
        title: Text(s.opticalRecognizedTitle),
        content: Text(
          replace
              ? s.opticalReplaceDetail(candidate.advertisedName)
              : s.opticalRecognizedDetail(candidate.advertisedName),
        ),
        actions: [
          CupertinoDialogAction(
            onPressed: () => Navigator.pop(ctx, false),
            child: Text(s.cancel),
          ),
          CupertinoDialogAction(
            isDefaultAction: true,
            onPressed: () => Navigator.pop(ctx, true),
            child: Text(replace ? s.opticalReplacePair : s.opticalStartPairing),
          ),
        ],
      ),
    );
    try {
      if (accepted != true || !context.mounted) return;
      await connection.pairOptically(
        candidate.token,
        previousPair: previousPair,
      );
    } finally {
      await OpticalReader.discard(candidate.token);
    }
  }

  Future<void> _confirm(
    BuildContext context,
    WatchConnectionProvider connection,
  ) async {
    final s = Strings.current;
    final pairId = connection.state.pairId;
    final confirmed = await showCupertinoDialog<bool>(
      context: context,
      builder: (context) => CupertinoAlertDialog(
        title: Text(s.confirmWatchFaceQuestion),
        content: Text(
          connection.state.setupRunning
              ? s.confirmWatchFaceActiveDetail
              : s.confirmWatchFaceDetail,
        ),
        actions: [
          CupertinoDialogAction(
            onPressed: () => Navigator.pop(context, false),
            child: Text(s.cancel),
          ),
          CupertinoDialogAction(
            onPressed: () => Navigator.pop(context, true),
            child: Text(s.confirmWatchFace),
          ),
        ],
      ),
    );
    if (confirmed == true) await connection.confirmSetup(pairId);
  }

  Future<void> _resumeSync(
    BuildContext context,
    WatchConnectionProvider connection,
  ) async {
    final s = Strings.current;
    final pairId = connection.state.pairId;
    final resume = await showCupertinoDialog<bool>(
      context: context,
      builder: (ctx) => CupertinoAlertDialog(
        title: Text(s.resumeWatchSync),
        content: Text(s.resumeWatchSyncHint),
        actions: [
          CupertinoDialogAction(
            onPressed: () => Navigator.pop(ctx, false),
            child: Text(s.cancel),
          ),
          CupertinoDialogAction(
            onPressed: () => Navigator.pop(ctx, true),
            child: Text(s.resumeWatchSync),
          ),
        ],
      ),
    );
    if (resume == true) await connection.resumeSetup(pairId);
  }

  @override
  Widget build(BuildContext context) {
    final connection = context.watch<WatchConnectionProvider>();
    final state = connection.state, s = Strings.current;
    final enabled = state.bridgeAvailable && !connection.sending;
    final receipt = connection.receipt;
    return CupertinoPageScaffold(
      backgroundColor: IosColors.systemBackground,
      navigationBar: CupertinoNavigationBar(middle: Text(s.allWatches)),
      child: SafeArea(
        child: ListView(
          padding: const EdgeInsets.symmetric(vertical: 12),
          children: [
            BluetoothAdapterSection(connection: connection),
            Padding(
              padding: const EdgeInsets.symmetric(horizontal: 16),
              child: WatchConnectionCard(state: state),
            ),
            const SizedBox(height: 8),
            Padding(
              padding: const EdgeInsets.symmetric(horizontal: 20),
              child: Text(
                !state.bridgeAvailable
                    ? connection.platform.codeOnlyPairing
                          ? s.desktopCoreUnavailable
                          : s.bridgeUnavailableHint
                    : !state.identityKnown
                    ? s.identityReadError
                    : !state.hasPair
                    ? s.noPairedWatchHint
                    : state.needsWatchConfirmation
                    ? s.watchActivatedHint
                    : !state.operationalEligible
                    ? s.pendingWatchSetup
                    : s.oneWatchBackend,
                style: const TextStyle(
                  fontSize: 14,
                  color: IosColors.secondaryLabel,
                  height: 1.4,
                ),
              ),
            ),
            if (connection.platform.codeOnlyPairing && state.pairingAvailable)
              Padding(
                padding: const EdgeInsets.symmetric(
                  horizontal: 20,
                  vertical: 12,
                ),
                child: Text(
                  s.pairingCodeOnlyHint,
                  style: const TextStyle(
                    fontSize: 14,
                    color: IosColors.secondaryLabel,
                  ),
                ),
              ),
            if (state.backendError.isNotEmpty)
              Padding(
                padding: const EdgeInsets.symmetric(
                  horizontal: 20,
                  vertical: 12,
                ),
                child: Text(
                  state.backendError,
                  style: const TextStyle(
                    fontSize: 13,
                    color: IosColors.systemRed,
                  ),
                ),
              ),
            if (enabled && !state.pairingAvailable)
              Padding(
                padding: const EdgeInsets.symmetric(
                  horizontal: 20,
                  vertical: 12,
                ),
                child: Text(
                  s.watchPairingUnavailable,
                  style: const TextStyle(
                    fontSize: 14,
                    color: IosColors.secondaryLabel,
                  ),
                ),
              ),
            if (enabled &&
                !state.bluetoothPermission &&
                !connection.platform.codeOnlyPairing)
              Padding(
                padding: const EdgeInsets.all(16),
                child: CupertinoButton.filled(
                  onPressed: () =>
                      connection.command('requestConnectionPermissions'),
                  child: Text(s.connectionPermissions),
                ),
              ),
            if (!state.operationalEligible &&
                (state.setupRunning ||
                    state.pinRequired ||
                    state.challengeId != null ||
                    state.setupPhase == 'FAILED' ||
                    state.bluetoothLink ||
                    state.needsWatchConfirmation)) ...[
              const SizedBox(height: 20),
              Padding(
                padding: const EdgeInsets.symmetric(horizontal: 16),
                child: WatchSetupPanel(connection: connection),
              ),
            ],
            const SizedBox(height: 20),
            Padding(
              padding: const EdgeInsets.symmetric(horizontal: 16),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.stretch,
                children: [
                  if (state.needsWatchConfirmation) ...[
                    CupertinoButton.filled(
                      onPressed: enabled && state.canConfirmSetup
                          ? () => _confirm(context, connection)
                          : null,
                      child: Text(s.finishWatchSetup),
                    ),
                    const SizedBox(height: 8),
                    Text(
                      s.confirmWatchFaceHint,
                      style: const TextStyle(
                        color: IosColors.secondaryLabel,
                        fontSize: 13,
                      ),
                    ),
                    if (state.connected || state.busy || state.bluetoothLink)
                      CupertinoButton(
                        onPressed:
                            enabled &&
                                state.status != 'STOPPING' &&
                                state.setupPhase != 'STOPPING'
                            ? connection.disconnect
                            : null,
                        child: Text(s.stopSetup),
                      )
                    else
                      CupertinoButton(
                        onPressed: enabled && state.canResumeSetup
                            ? () => _resumeSync(context, connection)
                            : null,
                        child: Text(s.watchStillSettingUp),
                      ),
                  ] else if (state.connected ||
                      state.busy ||
                      state.bluetoothLink)
                    CupertinoButton.filled(
                      onPressed:
                          enabled &&
                              state.status != 'STOPPING' &&
                              state.setupPhase != 'STOPPING'
                          ? connection.disconnect
                          : null,
                      child: Text(
                        state.setupRunning && !state.operationalEligible
                            ? s.stopSetup
                            : s.disconnectWatch,
                      ),
                    )
                  else if (state.hasPair && state.operationalEligible)
                    CupertinoButton.filled(
                      onPressed:
                          enabled &&
                              state.canConnect &&
                              state.bluetoothPermission
                          ? connection.connect
                          : null,
                      child: Text(s.connectWatch),
                    )
                  else if (state.hasPair)
                    CupertinoButton.filled(
                      onPressed: enabled && state.canResumeSetup
                          ? () => connection.resumeSetup(state.pairId)
                          : null,
                      child: Text(s.resumeWatchSetup),
                    )
                  else if (state.discoveryAvailable && !state.hasPair)
                    CupertinoButton.filled(
                      onPressed: enabled && state.canDiscover
                          ? connection.discover
                          : null,
                      child: Text(s.findNearbyWatch),
                    )
                  else
                    CupertinoButton.filled(
                      onPressed:
                          enabled && state.canPair && state.bluetoothPermission
                          ? connection.pair
                          : null,
                      child: Text(s.pairAppleWatch),
                    ),
                  if (receipt != null)
                    Padding(
                      padding: const EdgeInsets.only(top: 12),
                      child: Text(
                        receipt == 'APPLIED'
                            ? s.bluetoothAdapterSaved
                            : receipt == 'QUEUED'
                            ? s.connectionRequestAccepted
                            : receipt == 'OPENED' ||
                                  receipt == 'PERMISSION_REQUIRED'
                            ? s.connectionPermissionRequired
                            : receipt == 'BUSY'
                            ? s.connectionBusy
                            : receipt == 'WATCH_PROTOCOL_UNAVAILABLE'
                            ? s.watchPairingUnavailable
                            : s.connectionRequestRejected,
                        style: const TextStyle(
                          fontSize: 13,
                          color: IosColors.secondaryLabel,
                        ),
                        textAlign: TextAlign.center,
                      ),
                    ),
                ],
              ),
            ),
            IosListSection(
              children: [
                if (connection.platform.opticalPairing)
                  IosListTile(
                    icon: CupertinoIcons.camera,
                    iconBackgroundColor: IosColors.systemBlue,
                    title: s.opticalPairWithCamera,
                    onTap:
                        enabled &&
                            !state.busy &&
                            !state.connected &&
                            state.bluetoothPermission
                        ? () => _scan(context)
                        : null,
                  ),
                if (state.wifiAvailable)
                  IosListTile(
                    icon: CupertinoIcons.wifi,
                    iconBackgroundColor: IosColors.systemBlue,
                    title: s.syncCurrentWifi,
                    onTap:
                        enabled && state.connected && state.operationalEligible
                        ? () async {
                            final status = await connection.command('syncWifi');
                            if (context.mounted) {
                              await showCupertinoDialog<void>(
                                context: context,
                                builder: (ctx) => CupertinoAlertDialog(
                                  title: Text(s.syncCurrentWifi),
                                  content: Text(
                                    status == 'QUEUED'
                                        ? s.wifiReceiptHint
                                        : s.connectionRequestRejected,
                                  ),
                                  actions: [
                                    CupertinoDialogAction(
                                      onPressed: () => Navigator.pop(ctx),
                                      child: Text(s.ok),
                                    ),
                                  ],
                                ),
                              );
                            }
                          }
                        : null,
                  ),
                IosListTile(
                  icon: CupertinoIcons.waveform_path,
                  iconBackgroundColor: IosColors.systemGray,
                  title: s.watchDiagnostics,
                  onTap: () => Navigator.of(context).push(
                    CupertinoPageRoute(
                      builder: (_) => const WatchDiagnosticsScreen(),
                    ),
                  ),
                ),
              ],
            ),
            if (state.hasPair)
              IosListSection(
                header: s.savedWatch,
                children: [
                  IosListTile(
                    title: s.hardwareIdentity,
                    trailingText: state.productType.isEmpty
                        ? '—'
                        : state.productType,
                    showChevron: false,
                  ),
                  IosListTile(
                    title: s.systemBuild,
                    trailingText: state.buildVersion.isEmpty
                        ? '—'
                        : state.buildVersion,
                    showChevron: false,
                  ),
                  Padding(
                    padding: const EdgeInsets.all(16),
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Text(
                          s.pairedIdentity,
                          style: const TextStyle(
                            color: IosColors.secondaryLabel,
                            fontSize: 12,
                          ),
                        ),
                        const SizedBox(height: 5),
                        Text(
                          state.pairId,
                          style: const TextStyle(
                            color: IosColors.label,
                            fontSize: 12,
                          ),
                        ),
                      ],
                    ),
                  ),
                ],
              ),
            const SizedBox(height: 32),
          ],
        ),
      ),
    );
  }
}
