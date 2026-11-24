import 'dart:async';
import 'package:flutter/cupertino.dart';
import 'package:provider/provider.dart';
import '../../l10n/strings.dart';
import '../../models/software_update_state.dart';
import '../../providers/watch_provider.dart';
import '../../theme/ios_colors.dart';

class SoftwareUpdateScreen extends StatefulWidget {
  const SoftwareUpdateScreen({super.key});
  @override
  State<SoftwareUpdateScreen> createState() => _SoftwareUpdateScreenState();
}

class _SoftwareUpdateScreenState extends State<SoftwareUpdateScreen> {
  Timer? _poll;
  bool _sending = false;
  String? _receipt;
  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (mounted) _refresh();
    });
    _poll = Timer.periodic(const Duration(seconds: 15), (_) {
      if (mounted && !_sending) _refresh();
    });
  }

  void _refresh() {
    final provider = context.read<WatchProvider>();
    if (provider.softwareUpdate.canQuery) {
      unawaited(
        provider.updateSoftware('querySoftwareUpdate', provider.softwareUpdate),
      );
    }
  }

  @override
  void dispose() {
    _poll?.cancel();
    super.dispose();
  }

  Future<void> _send(String method, SoftwareUpdateState baseline) async {
    if (_sending) return;
    if (method == 'installSoftwareUpdate') {
      final accepted = await showCupertinoDialog<bool>(
        context: context,
        builder: (context) => CupertinoAlertDialog(
          title: Text(Strings.current.updateInstall),
          content: Text(
            Strings.current.updateConfirm(
              baseline.targetVersion ?? '—',
              baseline.targetBuild ?? '—',
            ),
          ),
          actions: [
            CupertinoDialogAction(
              onPressed: () => Navigator.pop(context, false),
              child: Text(Strings.current.cancel),
            ),
            CupertinoDialogAction(
              onPressed: () => Navigator.pop(context, true),
              child: Text(Strings.current.updateInstall),
            ),
          ],
        ),
      );
      if (accepted != true || !mounted) return;
    }
    setState(() {
      _sending = true;
      _receipt = null;
    });
    String receipt;
    try {
      receipt = await context
          .read<WatchProvider>()
          .updateSoftware(method, baseline)
          .timeout(const Duration(seconds: 20));
    } on TimeoutException {
      receipt = 'UNKNOWN';
    }
    if (!mounted) return;
    setState(() {
      _sending = false;
      _receipt = receipt;
    });
  }

  String _status(SoftwareUpdateState state) {
    final s = Strings.current;
    if (!state.available) return s.updateUnavailable;
    if (!state.connected) return s.updateConnect;
    if (!state.known) return s.updateUnknown;
    return switch (state.phase) {
      'IDLE' =>
        state.scanCompleted ? s.yourAppleWatchSoftwareIsUpToDate : s.updateIdle,
      'CHECKING' => s.checkingForUpdates,
      'AVAILABLE' => s.updateAvailable,
      'DOWNLOADING_PREPARING' => s.updatePreparing,
      'WAITING_PREPARATION' => s.updateWaitingPreparation,
      'PREPARED' => s.updatePrepared,
      'WAITING_INSTALLATION' => s.updateWaitingInstallation,
      'INSTALLING' => s.updateInstalling,
      'VERIFYING_RESTART' => s.updateRestart,
      'COMPLETE' => s.updateComplete,
      'FAILED' => s.updateFailed,
      _ => s.updateUnknown,
    };
  }

  String _size(int value) =>
      '${(value / (1024 * 1024)).toStringAsFixed(0)} MiB';

  @override
  Widget build(BuildContext context) {
    final state = context.watch<WatchProvider>().softwareUpdate;
    final s = Strings.current;
    final now = DateTime.now().millisecondsSinceEpoch;
    final active = const {
      'CHECKING',
      'DOWNLOADING_PREPARING',
      'WAITING_PREPARATION',
      'WAITING_INSTALLATION',
      'INSTALLING',
      'VERIFYING_RESTART',
    }.contains(state.phase);
    return CupertinoPageScaffold(
      backgroundColor: IosColors.systemBackground,
      navigationBar: CupertinoNavigationBar(
        middle: Text(s.softwareUpdate),
        previousPageTitle: s.general,
      ),
      child: SafeArea(
        child: ListView(
          padding: const EdgeInsets.all(20),
          children: [
            const SizedBox(height: 16),
            if (active && state.connected)
              const CupertinoActivityIndicator(radius: 16),
            const SizedBox(height: 16),
            Text(
              _status(state),
              textAlign: TextAlign.center,
              style: const TextStyle(
                color: IosColors.label,
                fontSize: 20,
                fontWeight: FontWeight.w600,
              ),
            ),
            if (state.installedVersion != null)
              Padding(
                padding: const EdgeInsets.only(top: 12),
                child: Text(
                  'watchOS ${state.installedVersion} (${state.installedBuild})',
                  textAlign: TextAlign.center,
                ),
              ),
            if (state.targetVersion != null)
              Padding(
                padding: const EdgeInsets.only(top: 12),
                child: Text(
                  'watchOS ${state.targetVersion} (${state.targetBuild})',
                  textAlign: TextAlign.center,
                ),
              ),
            if (state.progress != null)
              Padding(
                padding: const EdgeInsets.only(top: 12),
                child: Text(
                  s.updateStageProgress(
                    (state.progress! * 100).toStringAsFixed(0),
                  ),
                  textAlign: TextAlign.center,
                ),
              ),
            if (state.stalled)
              Text(s.updateStalled, textAlign: TextAlign.center),
            if (state.errorCode != null)
              Text('${state.errorCode}', textAlign: TextAlign.center),
            if (state.installAttempted &&
                const {
                  'PREPARED',
                  'IDLE',
                  'AVAILABLE',
                  'FAILED',
                }.contains(state.phase))
              Padding(
                padding: const EdgeInsets.only(top: 12),
                child: Text(s.updateInstallUnconfirmed),
              ),
            const SizedBox(height: 20),
            if (state.downloadBytes != null)
              Text(s.updateDownloadSize(_size(state.downloadBytes!))),
            if (state.requiredSpaceBytes != null)
              Text(s.updateRequiredSpace(_size(state.requiredSpaceBytes!))),
            if (state.targetBuild != null &&
                !state.termsAccepted &&
                state.phase == 'AVAILABLE')
              Padding(
                padding: const EdgeInsets.symmetric(vertical: 16),
                child: Text(s.updateTerms),
              ),
            if (state.available)
              Padding(
                padding: const EdgeInsets.symmetric(vertical: 16),
                child: Text(
                  s.updateRequirements,
                  style: const TextStyle(color: IosColors.secondaryLabel),
                ),
              ),
            CupertinoButton(
              onPressed: !_sending && state.canQuery
                  ? () => _send('querySoftwareUpdate', state)
                  : null,
              child: Text(s.updateRefresh),
            ),
            CupertinoButton(
              onPressed: !_sending && state.canQuery && !active
                  ? () => _send('checkSoftwareUpdate', state)
                  : null,
              child: Text(s.checkAgain),
            ),
            if (state.phase == 'AVAILABLE')
              CupertinoButton.filled(
                onPressed: !_sending && state.canDownload(now)
                    ? () => _send('downloadSoftwareUpdate', state)
                    : null,
                child: Text(s.updateDownload),
              ),
            if (state.phase == 'PREPARED')
              CupertinoButton.filled(
                onPressed: !_sending && state.canInstall(now)
                    ? () => _send('installSoftwareUpdate', state)
                    : null,
                child: Text(s.updateInstall),
              ),
            if (_sending) const CupertinoActivityIndicator(),
            if (_receipt != null)
              Text(
                _receipt == 'QUEUED'
                    ? s.updateQueued
                    : _receipt == 'UNKNOWN'
                    ? s.updateUnconfirmed
                    : s.updateNotQueued,
                textAlign: TextAlign.center,
              ),
          ],
        ),
      ),
    );
  }
}
