import 'package:flutter/cupertino.dart';
import '../l10n/strings.dart';
import '../models/watch_connection_state.dart';
import '../theme/ios_colors.dart';
import '../theme/companion_spacing.dart';
import 'setup_progress_label.dart';

class WatchConnectionCard extends StatelessWidget {
  final WatchConnectionState state;
  const WatchConnectionCard({super.key, required this.state});
  static String statusText(WatchConnectionState state) {
    final s = Strings.current;
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
  Widget build(BuildContext context) => Container(
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
                    : Strings.current.noPairedWatch,
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
                      statusText(state),
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
      ],
    ),
  );
}
