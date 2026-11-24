import 'package:flutter/cupertino.dart';
import 'package:flutter/material.dart' show SelectableText;
import 'package:flutter/services.dart';
import 'package:provider/provider.dart';
import '../../l10n/strings.dart';
import '../../providers/watch_connection_provider.dart';
import '../../theme/ios_colors.dart';
import '../../widgets/ios_list_section.dart';
import '../../widgets/ios_list_tile.dart';
import '../../widgets/watch_features_panel.dart';
import 'optical_pairing_lab_screen.dart';

class WatchDiagnosticsScreen extends StatefulWidget {
  const WatchDiagnosticsScreen({super.key});

  @override
  State<WatchDiagnosticsScreen> createState() => _WatchDiagnosticsScreenState();
}

class _WatchDiagnosticsScreenState extends State<WatchDiagnosticsScreen> {
  final ScrollController _scroll = ScrollController();
  final TextEditingController _filterController = TextEditingController();
  String _filter = '';

  @override
  void dispose() {
    _scroll.dispose();
    _filterController.dispose();
    super.dispose();
  }

  Future<void> _run(
    BuildContext context,
    WatchConnectionProvider connection,
    String method,
    String label,
  ) async {
    final s = Strings.of(context);
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

  String _filteredJournal(String journal) {
    final query = _filter.trim().toLowerCase();
    if (query.isEmpty || journal.isEmpty) return journal;
    final lines = journal
        .split('\n')
        .where((line) => line.toLowerCase().contains(query))
        .toList();
    return lines.join('\n');
  }

  void _jumpToBottom() {
    if (!_scroll.hasClients) return;
    _scroll.animateTo(
      _scroll.position.maxScrollExtent,
      duration: const Duration(milliseconds: 220),
      curve: Curves.easeOut,
    );
  }

  @override
  Widget build(BuildContext context) {
    final connection = context.watch<WatchConnectionProvider>(),
        state = connection.state,
        s = Strings.of(context);
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
          controller: _scroll,
          children: [
            WatchFeaturesPanel(state: state),
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
            ValueListenableBuilder<String>(
              valueListenable: connection.journalListenable,
              builder: (context, journal, _) {
                final filtered = _filteredJournal(journal);
                return Column(
                  crossAxisAlignment: CrossAxisAlignment.stretch,
                  children: [
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
                          if (journal.isNotEmpty)
                            CupertinoButton(
                              padding: const EdgeInsets.symmetric(
                                horizontal: 8,
                              ),
                              onPressed: _jumpToBottom,
                              child: Text(s.jumpToBottom),
                            ),
                          CupertinoButton(
                            padding: const EdgeInsets.symmetric(horizontal: 8),
                            onPressed: journal.isEmpty
                                ? null
                                : () async {
                                    await Clipboard.setData(
                                      ClipboardData(text: journal),
                                    );
                                    if (context.mounted) {
                                      await showCupertinoDialog<void>(
                                        context: context,
                                        builder: (ctx) => CupertinoAlertDialog(
                                          content: Text(s.journalCopied),
                                          actions: [
                                            CupertinoDialogAction(
                                              onPressed: () =>
                                                  Navigator.pop(ctx),
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
                    if (journal.isNotEmpty)
                      Padding(
                        padding: const EdgeInsets.fromLTRB(20, 8, 20, 0),
                        child: CupertinoSearchTextField(
                          controller: _filterController,
                          placeholder: s.filterJournal,
                          onChanged: (value) => setState(() => _filter = value),
                        ),
                      ),
                    Padding(
                      padding: const EdgeInsets.all(20),
                      child: SelectableText(
                        journal.isEmpty
                            ? '—'
                            : filtered.isEmpty
                            ? s.noMatchingJournalLines
                            : filtered,
                        style: const TextStyle(
                          color: IosColors.secondaryLabel,
                          fontFamily: 'monospace',
                          fontSize: 11,
                          height: 1.4,
                        ),
                      ),
                    ),
                  ],
                );
              },
            ),
            const SizedBox(height: 32),
          ],
        ),
      ),
    );
  }
}
