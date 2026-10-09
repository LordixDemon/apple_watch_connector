import '../../l10n/strings.dart';
import 'package:flutter/cupertino.dart';
import 'package:provider/provider.dart';
import '../../models/watch_settings.dart';
import '../../providers/watch_provider.dart';
import '../../theme/ios_colors.dart';
import '../../widgets/ios_list_section.dart';
import '../../widgets/ios_list_tile.dart';

class ActionButtonScreen extends StatelessWidget {
  const ActionButtonScreen({super.key});

  @override
  Widget build(BuildContext context) {
    final provider = context.watch<WatchProvider>();
    final settings = provider.settings;

    return CupertinoPageScaffold(
      backgroundColor: IosColors.systemBackground,
      navigationBar: CupertinoNavigationBar(
        backgroundColor: Color(0xCC121212),
        middle: Text(Strings.current.actionButton),
        previousPageTitle: Strings.current.myWatch,
      ),
      child: SafeArea(
        child: ListView(
          children: [
            const SizedBox(height: 16),
            // Ultra Action Button Visual Card
            Padding(
              padding: const EdgeInsets.symmetric(horizontal: 16),
              child: Container(
                padding: const EdgeInsets.all(18),
                decoration: BoxDecoration(
                  gradient: const LinearGradient(
                    colors: [Color(0xFF2C2C2E), Color(0xFF1C1C1E)],
                    begin: Alignment.topLeft,
                    end: Alignment.bottomRight,
                  ),
                  borderRadius: BorderRadius.circular(16),
                  border: Border.all(
                    color: IosColors.actionButtonOrange.withOpacity(0.3),
                  ),
                ),
                child: Row(
                  children: [
                    Container(
                      width: 48,
                      height: 48,
                      decoration: BoxDecoration(
                        color: IosColors.actionButtonOrange.withOpacity(0.2),
                        borderRadius: BorderRadius.circular(12),
                      ),
                      child: const Icon(
                        CupertinoIcons.slider_horizontal_below_rectangle,
                        color: IosColors.actionButtonOrange,
                        size: 26,
                      ),
                    ),
                    const SizedBox(width: 14),
                    Expanded(
                      child: Column(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        children: [
                          Text(
                            Strings.current.appleWatchUltra2ActionButton,
                            style: TextStyle(
                              color: IosColors.label,
                              fontSize: 16,
                              fontWeight: FontWeight.w700,
                            ),
                          ),
                          SizedBox(height: 4),
                          Text(
                            Strings
                                .current
                                .quickAccessToWorkoutsWaypointsTheStopwatchAndThe,
                            style: TextStyle(
                              color: IosColors.secondaryLabel,
                              fontSize: 13,
                            ),
                          ),
                        ],
                      ),
                    ),
                  ],
                ),
              ),
            ),
            const SizedBox(height: 12),
            IosListSection(
              header: Strings.current.action,
              children: [
                _buildActionTile(
                  context,
                  title: Strings.current.workout,
                  icon: CupertinoIcons.play_circle_fill,
                  target: ActionButtonTarget.workout,
                  selected:
                      settings.actionButtonTarget == ActionButtonTarget.workout,
                ),
                _buildActionTile(
                  context,
                  title: Strings.current.stopwatch,
                  icon: CupertinoIcons.stopwatch,
                  target: ActionButtonTarget.stopwatch,
                  selected:
                      settings.actionButtonTarget ==
                      ActionButtonTarget.stopwatch,
                ),
                _buildActionTile(
                  context,
                  title: Strings.current.compassWaypoint,
                  icon: CupertinoIcons.compass,
                  target: ActionButtonTarget.waypoint,
                  selected:
                      settings.actionButtonTarget ==
                      ActionButtonTarget.waypoint,
                ),
                _buildActionTile(
                  context,
                  title: Strings.current.backtrack,
                  icon: CupertinoIcons.arrow_counterclockwise,
                  target: ActionButtonTarget.backtrack,
                  selected:
                      settings.actionButtonTarget ==
                      ActionButtonTarget.backtrack,
                ),
                _buildActionTile(
                  context,
                  title: Strings.current.diveDepth,
                  icon: CupertinoIcons.drop,
                  target: ActionButtonTarget.dive,
                  selected:
                      settings.actionButtonTarget == ActionButtonTarget.dive,
                ),
                _buildActionTile(
                  context,
                  title: Strings.current.flashlight,
                  icon: CupertinoIcons.lightbulb_fill,
                  target: ActionButtonTarget.flashlight,
                  selected:
                      settings.actionButtonTarget ==
                      ActionButtonTarget.flashlight,
                ),
              ],
            ),
            IosListSection(
              header: Strings.current.safetySiren,
              footer: Strings.current.holdTheActionButtonOnAppleWatchUltraTo,
              children: [
                IosListTile(
                  title: Strings.current.holdForSiren,
                  trailing: CupertinoSwitch(
                    value: settings.holdForSiren,
                    activeTrackColor: IosColors.systemGreen,
                    onChanged: (val) {
                      provider.updateSettings(
                        settings.copyWith(holdForSiren: val),
                      );
                    },
                  ),
                  showChevron: false,
                ),
              ],
            ),
          ],
        ),
      ),
    );
  }

  Widget _buildActionTile(
    BuildContext context, {
    required String title,
    required IconData icon,
    required ActionButtonTarget target,
    required bool selected,
  }) {
    final provider = context.read<WatchProvider>();
    final settings = provider.settings;

    return IosListTile(
      icon: icon,
      iconBackgroundColor: IosColors.actionButtonOrange,
      title: title,
      trailing: selected
          ? const Icon(
              CupertinoIcons.checkmark,
              color: IosColors.systemOrange,
              size: 18,
            )
          : null,
      showChevron: false,
      onTap: () {
        provider.updateSettings(
          settings.copyWith(actionButtonTarget: target, actionButtonApp: target.name),
        );
      },
    );
  }
}
