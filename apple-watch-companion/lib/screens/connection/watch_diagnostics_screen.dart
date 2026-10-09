import 'package:flutter/cupertino.dart';
import 'package:flutter/services.dart';
import 'package:provider/provider.dart';
import '../../l10n/strings.dart';
import '../../providers/watch_connection_provider.dart';
import '../../theme/ios_colors.dart';
import '../../widgets/ios_list_section.dart';
import '../../widgets/ios_list_tile.dart';
import 'optical_pairing_lab_screen.dart';

class WatchDiagnosticsScreen extends StatelessWidget {
  const WatchDiagnosticsScreen({super.key});
  Future<void> _run(
    BuildContext context,
    WatchConnectionProvider connection,
    String method,
    String label,
  ) async {
    final s = Strings.current;
    final confirmed = await showCupertinoDialog<bool>(
      context: context,
      builder: (ctx) => CupertinoAlertDialog(
        title: Text(label),
        content: Text(s.advancedToolDetail),
        actions: [
          CupertinoDialogAction(
            onPressed: () => Navigator.pop(ctx, false),
            child: Text(s.cancel),
          ),
          CupertinoDialogAction(
            onPressed: () => Navigator.pop(ctx, true),
            child: Text(s.advancedToolQuestion),
          ),
        ],
      ),
    );
    if (confirmed == true) await connection.command(method);
  }

  @override
  Widget build(BuildContext context) {
    final connection = context.watch<WatchConnectionProvider>(),
        state = connection.state,
        s = Strings.current;
    final tools = {
      'auditStockBond': s.stockBondAudit,
      'importStockBond': s.stockBondImport,
      'alignStockIdentity': s.stockIdentityAlignment,
      'probeStockReconnect': s.stockReconnectProbe,
    };
    final ready =
        state.bridgeAvailable &&
        state.identityKnown &&
        !state.connected &&
        !state.busy &&
        !connection.sending;
    return CupertinoPageScaffold(
      backgroundColor: IosColors.systemBackground,
      navigationBar: CupertinoNavigationBar(middle: Text(s.watchDiagnostics)),
      child: SafeArea(
        child: ListView(
          children: [
            if (connection.platform.opticalPairing)
              IosListSection(
                children: [
                  IosListTile(
                    title: s.opticalPairingLab,
                    onTap: () => Navigator.of(context).push(
                      CupertinoPageRoute<void>(
                        builder: (_) => const OpticalPairingLabScreen(),
                      ),
                    ),
                  ),
                ],
              ),
            if (connection.platform.androidDiagnostics)
              IosListSection(
                header: s.advancedConnectionTools,
                footer: s.advancedToolHint,
                children: [
                  for (final tool in tools.entries)
                    IosListTile(
                      title: tool.value,
                      onTap: ready
                          ? () =>
                                _run(context, connection, tool.key, tool.value)
                          : null,
                    ),
                ],
              ),
            Padding(
              padding: const EdgeInsets.symmetric(horizontal: 20),
              child: Row(
                children: [
                  Expanded(
                    child: Text(
                      s.bridgeJournal,
                      style: const TextStyle(
                        color: IosColors.label,
                        fontWeight: FontWeight.w600,
                      ),
                    ),
                  ),
                  CupertinoButton(
                    onPressed: state.journal.isEmpty
                        ? null
                        : () async {
                            await Clipboard.setData(
                              ClipboardData(text: state.journal),
                            );
                            if (context.mounted) {
                              await showCupertinoDialog<void>(
                                context: context,
                                builder: (ctx) => CupertinoAlertDialog(
                                  content: Text(s.journalCopied),
                                  actions: [
                                    CupertinoDialogAction(
                                      onPressed: () => Navigator.pop(ctx),
                                      child: Text(s.ok),
                                    ),
                                  ],
                                ),
                              );
                            }
                          },
                    child: Text(s.copyBridgeJournal),
                  ),
                ],
              ),
            ),
            Padding(
              padding: const EdgeInsets.all(20),
              child: Text(
                state.journal.isEmpty ? '—' : state.journal,
                style: const TextStyle(
                  color: IosColors.secondaryLabel,
                  fontFamily: 'monospace',
                  fontSize: 11,
                  height: 1.4,
                ),
              ),
            ),
            const SizedBox(height: 32),
          ],
        ),
      ),
    );
  }
}
