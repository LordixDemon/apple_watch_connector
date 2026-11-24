import '../../l10n/strings.dart';
import 'package:flutter/cupertino.dart';
import 'package:provider/provider.dart';
import '../../providers/watch_provider.dart';
import '../../theme/ios_colors.dart';
import '../../widgets/ios_list_section.dart';
import '../../widgets/ios_list_tile.dart';

class HealthActivityScreen extends StatelessWidget {
  const HealthActivityScreen({super.key});

  static double _safeProgress(num value, num goal) {
    if (goal <= 0 || !value.isFinite || !goal.isFinite) return 0.0;
    final ratio = value / goal;
    if (!ratio.isFinite) return 0.0;
    return ratio.clamp(0.0, 1.0).toDouble();
  }

  @override
  Widget build(BuildContext context) {
    final provider = context.watch<WatchProvider>();
    final settings = provider.settings;
    final s = Strings.of(context);

    if (!provider.hasHealthObservation) {
      return CupertinoPageScaffold(
        navigationBar: CupertinoNavigationBar(middle: Text(s.activityHealth)),
        child: SafeArea(
          child: Center(
            child: Padding(
              padding: const EdgeInsets.all(24),
              child: Text(
                s.noHealthDataReceivedFromTheWatchYet,
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
        backgroundColor: const Color(0xCC121212),
        middle: Text(s.activityHealth),
        previousPageTitle: s.myWatch,
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
                          s.watchMeasurements,
                          style: const TextStyle(
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
                              s.live,
                              style: const TextStyle(
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
                                    const Icon(
                                      CupertinoIcons.heart_fill,
                                      color: IosColors.systemRed,
                                      size: 18,
                                    ),
                                    const SizedBox(width: 6),
                                    Text(
                                      s.heartRate,
                                      style: const TextStyle(
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
                                  s.bpm,
                                  style: const TextStyle(
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
                                    const Icon(
                                      CupertinoIcons.flame_fill,
                                      color: IosColors.systemOrange,
                                      size: 18,
                                    ),
                                    const SizedBox(width: 6),
                                    Text(
                                      s.steps,
                                      style: const TextStyle(
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
                                  s.km(
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
                      s.move,
                      s.kcal(
                        (provider.health.activeCalories.toInt()).toString(),
                        (provider.health.calorieGoal.toInt()).toString(),
                      ),
                      _safeProgress(
                        provider.health.activeCalories,
                        provider.health.calorieGoal,
                      ),
                      IosColors.activityRed,
                    ),
                    const SizedBox(height: 10),
                    _buildRingBar(
                      s.exercise,
                      s.min(
                        (provider.health.exerciseMinutes).toString(),
                        (provider.health.exerciseGoalMinutes).toString(),
                      ),
                      _safeProgress(
                        provider.health.exerciseMinutes,
                        provider.health.exerciseGoalMinutes,
                      ),
                      IosColors.activityGreen,
                    ),
                    const SizedBox(height: 10),
                    _buildRingBar(
                      s.stand,
                      s.hr(
                        (provider.health.standHours).toString(),
                        (provider.health.standGoalHours).toString(),
                      ),
                      _safeProgress(
                        provider.health.standHours,
                        provider.health.standGoalHours,
                      ),
                      IosColors.activityBlue,
                    ),
                  ],
                ),
              ),
            ),
            const SizedBox(height: 16),
            IosListSection(
              header: s.activityRings,
              children: [
                IosListTile(
                  icon: CupertinoIcons.flame_fill,
                  iconBackgroundColor: IosColors.activityRed,
                  title: s.standReminders,
                  subtitle: s.remindsYouToStandAndMove10MinutesBefore,
                  trailing: CupertinoSwitch(
                    value: settings.standReminders,
                    activeTrackColor: IosColors.systemGreen,
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
                  title: s.dailyCoaching,
                  trailing: CupertinoSwitch(
                    value: settings.dailyCoaching,
                    activeTrackColor: IosColors.systemGreen,
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
                  title: s.goalCompletions,
                  trailing: CupertinoSwitch(
                    value: settings.goalCompletions,
                    activeTrackColor: IosColors.systemGreen,
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
              header: s.heartBreathing,
              children: [
                IosListTile(
                  icon: CupertinoIcons.heart_fill,
                  iconBackgroundColor: IosColors.systemRed,
                  title: s.highHeartRateNotifications,
                  trailingText: s.bpm2(
                    (settings.highHeartRateThreshold).toString(),
                  ),
                  showChevron: false,
                ),
                IosListTile(
                  icon: CupertinoIcons.heart,
                  iconBackgroundColor: IosColors.systemPink,
                  title: s.lowHeartRateNotifications,
                  trailingText: s.bpm3(
                    (settings.lowHeartRateThreshold).toString(),
                  ),
                  showChevron: false,
                ),
                IosListTile(
                  icon: CupertinoIcons.drop_fill,
                  iconBackgroundColor: IosColors.systemBlue,
                  title: s.bloodOxygen,
                  trailing: CupertinoSwitch(
                    value: settings.bloodOxygenEnabled,
                    activeTrackColor: IosColors.systemGreen,
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
                  title: s.sleepTracking,
                  trailing: CupertinoSwitch(
                    value: settings.sleepTracking,
                    activeTrackColor: IosColors.systemGreen,
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
    final clamped = progress.isFinite ? progress.clamp(0.0, 1.0) : 0.0;
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
