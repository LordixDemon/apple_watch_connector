import '../../l10n/strings.dart';
import 'package:flutter/cupertino.dart';
import 'package:provider/provider.dart';
import '../../providers/watch_provider.dart';
import '../../theme/ios_colors.dart';
import '../../widgets/ios_list_section.dart';
import '../../widgets/ios_list_tile.dart';

class HealthActivityScreen extends StatelessWidget {
  const HealthActivityScreen({super.key});

  @override
  Widget build(BuildContext context) {
    final provider = context.watch<WatchProvider>();
    final settings = provider.settings;

    if (!provider.hasHealthObservation) {
      return CupertinoPageScaffold(
        navigationBar: CupertinoNavigationBar(
          middle: Text(Strings.current.activityHealth),
        ),
        child: SafeArea(
          child: Center(
            child: Padding(
              padding: EdgeInsets.all(24),
              child: Text(
                Strings.current.noHealthDataReceivedFromTheWatchYet,
                textAlign: TextAlign.center,
              ),
            ),
          ),
        ),
      );
    }

    return CupertinoPageScaffold(
      backgroundColor: IosColors.systemBackground,
      navigationBar: CupertinoNavigationBar(
        backgroundColor: Color(0xCC121212),
        middle: Text(Strings.current.activityHealth),
        previousPageTitle: Strings.current.myWatch,
      ),
      child: SafeArea(
        child: ListView(
          children: [
            const SizedBox(height: 12),

            // --- Live Health & Activity Rings Telemetry Card ---
            Padding(
              padding: const EdgeInsets.symmetric(horizontal: 16),
              child: Container(
                padding: const EdgeInsets.all(18),
                decoration: BoxDecoration(
                  color: IosColors.secondaryGroupedBackground,
                  borderRadius: BorderRadius.circular(16),
                  border: Border.all(
                    color: IosColors.separator.withValues(alpha: 0.5),
                  ),
                ),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Row(
                      mainAxisAlignment: MainAxisAlignment.spaceBetween,
                      children: [
                        Text(
                          Strings.current.watchMeasurements,
                          style: TextStyle(
                            color: IosColors.secondaryLabel,
                            fontSize: 12,
                            fontWeight: FontWeight.w700,
                            letterSpacing: 0.5,
                          ),
                        ),
                        Row(
                          children: [
                            Container(
                              width: 8,
                              height: 8,
                              decoration: const BoxDecoration(
                                color: IosColors.systemGreen,
                                shape: BoxShape.circle,
                              ),
                            ),
                            const SizedBox(width: 6),
                            Text(
                              Strings.current.live,
                              style: TextStyle(
                                color: IosColors.systemGreen,
                                fontSize: 11,
                                fontWeight: FontWeight.w800,
                              ),
                            ),
                          ],
                        ),
                      ],
                    ),
                    const SizedBox(height: 16),
                    Row(
                      children: [
                        // Heart rate
                        Expanded(
                          child: Container(
                            padding: const EdgeInsets.all(12),
                            decoration: BoxDecoration(
                              color: const Color(0xFF1E1E20),
                              borderRadius: BorderRadius.circular(12),
                            ),
                            child: Column(
                              crossAxisAlignment: CrossAxisAlignment.start,
                              children: [
                                Row(
                                  children: [
                                    Icon(
                                      CupertinoIcons.heart_fill,
                                      color: IosColors.systemRed,
                                      size: 18,
                                    ),
                                    SizedBox(width: 6),
                                    Text(
                                      Strings.current.heartRate,
                                      style: TextStyle(
                                        color: IosColors.secondaryLabel,
                                        fontSize: 13,
                                      ),
                                    ),
                                  ],
                                ),
                                const SizedBox(height: 8),
                                Text(
                                  '${provider.health.heartRateBpm.toInt()}',
                                  style: const TextStyle(
                                    color: IosColors.label,
                                    fontSize: 26,
                                    fontWeight: FontWeight.w800,
                                  ),
                                ),
                                Text(
                                  Strings.current.bpm,
                                  style: TextStyle(
                                    color: IosColors.secondaryLabel,
                                    fontSize: 11,
                                  ),
                                ),
                              ],
                            ),
                          ),
                        ),
                        const SizedBox(width: 12),
                        // Steps
                        Expanded(
                          child: Container(
                            padding: const EdgeInsets.all(12),
                            decoration: BoxDecoration(
                              color: const Color(0xFF1E1E20),
                              borderRadius: BorderRadius.circular(12),
                            ),
                            child: Column(
                              crossAxisAlignment: CrossAxisAlignment.start,
                              children: [
                                Row(
                                  children: [
                                    Icon(
                                      CupertinoIcons.flame_fill,
                                      color: IosColors.systemOrange,
                                      size: 18,
                                    ),
                                    SizedBox(width: 6),
                                    Text(
                                      Strings.current.steps,
                                      style: TextStyle(
                                        color: IosColors.secondaryLabel,
                                        fontSize: 13,
                                      ),
                                    ),
                                  ],
                                ),
                                const SizedBox(height: 8),
                                Text(
                                  '${provider.health.stepCount}',
                                  style: const TextStyle(
                                    color: IosColors.label,
                                    fontSize: 26,
                                    fontWeight: FontWeight.w800,
                                  ),
                                ),
                                Text(
                                  Strings.current.km(
                                    ((provider.health.distanceMeters / 1000)
                                            .toStringAsFixed(1))
                                        .toString(),
                                  ),
                                  style: const TextStyle(
                                    color: IosColors.secondaryLabel,
                                    fontSize: 11,
                                  ),
                                ),
                              ],
                            ),
                          ),
                        ),
                      ],
                    ),
                    const SizedBox(height: 16),
                    // Activity Rings Progress Bars
                    _buildRingBar(
                      Strings.current.move,
                      Strings.current.kcal(
                        (provider.health.activeCalories.toInt()).toString(),
                        (provider.health.calorieGoal.toInt()).toString(),
                      ),
                      provider.health.activeCalories /
                          provider.health.calorieGoal,
                      IosColors.activityRed,
                    ),
                    const SizedBox(height: 10),
                    _buildRingBar(
                      Strings.current.exercise,
                      Strings.current.min(
                        (provider.health.exerciseMinutes).toString(),
                        (provider.health.exerciseGoalMinutes).toString(),
                      ),
                      provider.health.exerciseMinutes /
                          provider.health.exerciseGoalMinutes,
                      IosColors.activityGreen,
                    ),
                    const SizedBox(height: 10),
                    _buildRingBar(
                      Strings.current.stand,
                      Strings.current.hr(
                        (provider.health.standHours).toString(),
                        (provider.health.standGoalHours).toString(),
                      ),
                      provider.health.standHours /
                          provider.health.standGoalHours,
                      IosColors.activityBlue,
                    ),
                  ],
                ),
              ),
            ),
            const SizedBox(height: 16),
            IosListSection(
              header: Strings.current.activityRings,
              children: [
                IosListTile(
                  icon: CupertinoIcons.flame_fill,
                  iconBackgroundColor: IosColors.activityRed,
                  title: Strings.current.standReminders,
                  subtitle:
                      Strings.current.remindsYouToStandAndMove10MinutesBefore,
                  trailing: CupertinoSwitch(
                    value: settings.standReminders,
                    activeColor: IosColors.systemGreen,
                    onChanged: (val) {
                      provider.updateSettings(
                        settings.copyWith(standReminders: val),
                      );
                    },
                  ),
                  showChevron: false,
                ),
                IosListTile(
                  icon: CupertinoIcons.sparkles,
                  iconBackgroundColor: IosColors.activityGreen,
                  title: Strings.current.dailyCoaching,
                  trailing: CupertinoSwitch(
                    value: settings.dailyCoaching,
                    activeColor: IosColors.systemGreen,
                    onChanged: (val) {
                      provider.updateSettings(
                        settings.copyWith(dailyCoaching: val),
                      );
                    },
                  ),
                  showChevron: false,
                ),
                IosListTile(
                  icon: CupertinoIcons.rosette,
                  iconBackgroundColor: IosColors.systemYellow,
                  title: Strings.current.goalCompletions,
                  trailing: CupertinoSwitch(
                    value: settings.goalCompletions,
                    activeColor: IosColors.systemGreen,
                    onChanged: (val) {
                      provider.updateSettings(
                        settings.copyWith(goalCompletions: val),
                      );
                    },
                  ),
                  showChevron: false,
                ),
              ],
            ),
            IosListSection(
              header: Strings.current.heartBreathing,
              children: [
                IosListTile(
                  icon: CupertinoIcons.heart_fill,
                  iconBackgroundColor: IosColors.systemRed,
                  title: Strings.current.highHeartRateNotifications,
                  trailingText: Strings.current.bpm2(
                    (settings.highHeartRateThreshold).toString(),
                  ),
                  onTap: () {},
                ),
                IosListTile(
                  icon: CupertinoIcons.heart,
                  iconBackgroundColor: IosColors.systemPink,
                  title: Strings.current.lowHeartRateNotifications,
                  trailingText: Strings.current.bpm3(
                    (settings.lowHeartRateThreshold).toString(),
                  ),
                  onTap: () {},
                ),
                IosListTile(
                  icon: CupertinoIcons.drop_fill,
                  iconBackgroundColor: IosColors.systemBlue,
                  title: Strings.current.bloodOxygen,
                  trailing: CupertinoSwitch(
                    value: settings.bloodOxygenEnabled,
                    activeColor: IosColors.systemGreen,
                    onChanged: (val) {
                      provider.updateSettings(
                        settings.copyWith(bloodOxygenEnabled: val),
                      );
                    },
                  ),
                  showChevron: false,
                ),
                IosListTile(
                  icon: CupertinoIcons.moon_fill,
                  iconBackgroundColor: IosColors.systemIndigo,
                  title: Strings.current.sleepTracking,
                  trailing: CupertinoSwitch(
                    value: settings.sleepTracking,
                    activeColor: IosColors.systemGreen,
                    onChanged: (val) {
                      provider.updateSettings(
                        settings.copyWith(sleepTracking: val),
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

  Widget _buildRingBar(
    String title,
    String valueText,
    double progress,
    Color color,
  ) {
    final clamped = progress.clamp(0.0, 1.0);
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Row(
          mainAxisAlignment: MainAxisAlignment.spaceBetween,
          children: [
            Text(
              title,
              style: const TextStyle(
                color: IosColors.label,
                fontSize: 13,
                fontWeight: FontWeight.w600,
              ),
            ),
            Text(
              valueText,
              style: TextStyle(
                color: color,
                fontSize: 12,
                fontWeight: FontWeight.w700,
              ),
            ),
          ],
        ),
        const SizedBox(height: 6),
        ClipRRect(
          borderRadius: BorderRadius.circular(4),
          child: Container(
            height: 7,
            width: double.infinity,
            color: color.withValues(alpha: 0.2),
            alignment: Alignment.centerLeft,
            child: FractionallySizedBox(
              widthFactor: clamped,
              child: Container(color: color),
            ),
          ),
        ),
      ],
    );
  }
}
