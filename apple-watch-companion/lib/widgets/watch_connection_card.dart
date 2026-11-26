import 'package:flutter/cupertino.dart';
import '../l10n/strings.dart';
import '../models/pairing_strategy.dart';
import '../models/watch_connection_state.dart';
import '../theme/ios_colors.dart';
import '../theme/companion_spacing.dart';
import 'setup_progress_label.dart';

class WatchConnectionCard extends StatelessWidget {
  final WatchConnectionState state;
  final bool showChevron;
  const WatchConnectionCard({
    super.key,
    required this.state,
    this.showChevron = false,
  });

  static String statusText(WatchConnectionState state, [AppLocalizations? l10n]) {
    final s = l10n ?? Strings.current;
    if (!state.bridgeAvailable) return s.bridgeUnavailable;
    if (state.setupRunning && !state.operationalEligible) {
      return setupProgressLabel(state);
    }
    if (state.status == 'STOPPING') return s.connectionStopping;
    if (state.connected) return s.connected;
    if (state.bluetoothLink) return s.bluetoothLinkConnected;
    if (state.status == 'CONNECTING') return s.connectionConnecting;
    if (!state.identityKnown) return s.identityLoading;
    return state.hasPair ? s.disconnected : s.readyToConnect;
  }

  @override
  Widget build(BuildContext context) {
    final s = Strings.of(context);
    return Container(
      padding: const EdgeInsets.all(CompanionSpacing.cardInset),
      decoration: BoxDecoration(
        color: IosColors.secondaryGroupedBackground,
        borderRadius: BorderRadius.circular(16),
      ),
      child: Row(
        children: [
          Container(
            width: 48,
            height: 60,
            decoration: BoxDecoration(
              borderRadius: BorderRadius.circular(12),
              color: IosColors.systemOrange.withValues(alpha: 0.1),
            ),
            child: const Icon(
              CupertinoIcons.time,
              color: IosColors.systemOrange,
              size: 30,
            ),
          ),
          const SizedBox(width: 12),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  state.hasPair || state.discoveredProductType.isNotEmpty
                      ? state.modelName
                      : s.noPairedWatch,
                  style: const TextStyle(
                    fontSize: 20,
                    fontWeight: FontWeight.w700,
                    color: IosColors.label,
                  ),
                ),
                if (state.productType.isNotEmpty)
                  Padding(
                    padding: const EdgeInsets.only(top: 5),
                    child: Text(
                      state.productType,
                      style: const TextStyle(
                        color: IosColors.secondaryLabel,
                        fontSize: 13,
                      ),
                    ),
                  ),
                if (state.pairingStrategy != null &&
                    state.pairingStrategy! < 4 &&
                    (state.hasPair ||
                        state.setupRunning ||
                        state.bluetoothLink ||
                        state.connected))
                  Padding(
                    padding: const EdgeInsets.only(top: 6),
                    child: Container(
                      padding: const EdgeInsets.symmetric(
                        horizontal: 8,
                        vertical: 2,
                      ),
                      decoration: BoxDecoration(
                        color: IosColors.systemOrange.withValues(alpha: 0.16),
                        borderRadius: BorderRadius.circular(10),
                      ),
                      child: Text(
                        s.legacyStrategyBadge(
                          PairingStrategyInfo.badgeFor(state.pairingStrategy!),
                        ),
                        style: const TextStyle(
                          fontSize: 11,
                          fontWeight: FontWeight.w600,
                          color: IosColors.systemOrange,
                        ),
                      ),
                    ),
                  ),
                const SizedBox(height: 6),
                Row(
                  children: [
                    if (state.setupWorking || !state.setupRunning && state.busy)
                      const CupertinoActivityIndicator(radius: 6)
                    else
                      Icon(
                        CupertinoIcons.circle_fill,
                        size: 8,
                        color: state.connected
                            ? IosColors.systemGreen
                            : IosColors.systemGray,
                      ),
                    const SizedBox(width: 7),
                    Expanded(
                      child: Text(
                        statusText(state, s),
                        style: TextStyle(
                          fontSize: 13,
                          color: state.connected
                              ? IosColors.systemGreen
                              : IosColors.secondaryLabel,
                        ),
                      ),
                    ),
                  ],
                ),
              ],
            ),
          ),
          if (showChevron) ...[
            const SizedBox(width: 8),
            const Icon(
              CupertinoIcons.chevron_forward,
              color: IosColors.systemGray2,
              size: 16,
            ),
          ],
        ],
      ),
    );
  }
}
