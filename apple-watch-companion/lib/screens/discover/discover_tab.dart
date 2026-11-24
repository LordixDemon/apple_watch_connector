import '../../l10n/strings.dart';
import 'package:flutter/cupertino.dart';
import 'package:provider/provider.dart';
import '../../models/watch_features.dart';
import '../../providers/watch_connection_provider.dart';
import '../../theme/ios_colors.dart';
import '../../widgets/ios_list_section.dart';
import '../../widgets/ios_list_tile.dart';
import '../connection/watch_connection_screen.dart';
import '../connection/watch_diagnostics_screen.dart';
import '../my_watch/notifications_screen.dart';
import '../my_watch/software_update_screen.dart';
import 'gesture_guide_screen.dart';

class DiscoverTab extends StatelessWidget {
  const DiscoverTab({super.key});

  @override
  Widget build(BuildContext context) {
    final s = Strings.of(context);
    final features = context.select<WatchConnectionProvider, WatchFeatures>(
      (c) => c.state.features,
    );
    return CupertinoPageScaffold(
      backgroundColor: IosColors.systemBackground,
      child: CustomScrollView(
        slivers: [
          CupertinoSliverNavigationBar(
            largeTitle: Text(s.discover),
            backgroundColor: const Color(0xCC121212),
          ),
          SliverToBoxAdapter(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: [
                const SizedBox(height: 8),
                // Featured Banner
                Padding(
                  padding: const EdgeInsets.symmetric(horizontal: 16),
                  child: Container(
                    padding: const EdgeInsets.all(12),
                    decoration: BoxDecoration(
                      gradient: const LinearGradient(
                        colors: [Color(0xFF2C1000), Color(0xFF140800)],
                        begin: Alignment.topLeft,
                        end: Alignment.bottomRight,
                      ),
                      borderRadius: BorderRadius.circular(16),
                      border: Border.all(
                        color: IosColors.ultraOrange.withValues(alpha: 0.4),
                      ),
                    ),
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Row(
                          children: [
                            const Icon(
                              CupertinoIcons.sparkles,
                              color: IosColors.ultraOrange,
                              size: 20,
                            ),
                            const SizedBox(width: 8),
                            Flexible(
                              child: Text(
                                s.newInWatchos11,
                                style: const TextStyle(
                                  color: IosColors.ultraOrange,
                                  fontSize: 13,
                                  fontWeight: FontWeight.w700,
                                  letterSpacing: 0.5,
                                ),
                              ),
                            ),
                          ],
                        ),
                        const SizedBox(height: 10),
                        Text(
                          s.appleWatchUltra2OnAndroid,
                          style: const TextStyle(
                            color: IosColors.label,
                            fontSize: 22,
                            fontWeight: FontWeight.w800,
                            letterSpacing: -0.4,
                          ),
                        ),
                        const SizedBox(height: 6),
                        Text(
                          s.syncNotificationsCallsWorkoutsAndWatchFacesWithYour,
                          style: const TextStyle(
                            color: IosColors.secondaryLabel,
                            fontSize: 14,
                            height: 1.4,
                          ),
                        ),
                      ],
                    ),
                  ),
                ),
                const SizedBox(height: 6),
                IosListSection(
                  header: s.guidesTips,
                  children: [
                    IosListTile(
                      icon: CupertinoIcons.hand_point_right_fill,
                      iconBackgroundColor: IosColors.systemBlue,
                      title: s.gesturesControls,
                      subtitle: s.doubleTapDigitalCrownAndActionButton,
                      onTap: () {
                        Navigator.of(context).push(
                          CupertinoPageRoute(
                            builder: (_) => const GestureGuideScreen(),
                          ),
                        );
                      },
                    ),
                    IosListTile(
                      icon: CupertinoIcons.link,
                      iconBackgroundColor: IosColors.systemOrange,
                      title: s.watchConnection,
                      subtitle: s.watchConnectionSubtitle,
                      onTap: () {
                        Navigator.of(context).push(
                          CupertinoPageRoute(
                            builder: (_) => const WatchConnectionScreen(),
                          ),
                        );
                      },
                    ),
                    if (features.notifications)
                      IosListTile(
                        icon: CupertinoIcons.bell_fill,
                        iconBackgroundColor: IosColors.systemPurple,
                        title: s.notificationMirroring,
                        subtitle: s.setUpSmsCallsAndMessagingApps,
                        onTap: () {
                          Navigator.of(context).push(
                            CupertinoPageRoute(
                              builder: (_) => const NotificationsScreen(),
                            ),
                          );
                        },
                      ),
                    if (features.softwareUpdates)
                      IosListTile(
                        icon: CupertinoIcons.arrow_down_circle_fill,
                        iconBackgroundColor: IosColors.systemTeal,
                        title: s.softwareUpdate,
                        onTap: () {
                          Navigator.of(context).push(
                            CupertinoPageRoute(
                              builder: (_) => const SoftwareUpdateScreen(),
                            ),
                          );
                        },
                      ),
                  ],
                ),
                IosListSection(
                  header: s.resources,
                  children: [
                    IosListTile(
                      icon: CupertinoIcons.question_circle_fill,
                      iconBackgroundColor: IosColors.systemGreen,
                      title: s.halSupportDiagnostics,
                      onTap: () {
                        Navigator.of(context).push(
                          CupertinoPageRoute(
                            builder: (_) => const WatchDiagnosticsScreen(),
                          ),
                        );
                      },
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
}
