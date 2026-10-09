import '../../l10n/strings.dart';
import 'package:flutter/cupertino.dart';
import '../../theme/ios_colors.dart';
import '../../widgets/ios_list_section.dart';
import '../../widgets/ios_list_tile.dart';
import 'gesture_guide_screen.dart';

class DiscoverTab extends StatelessWidget {
  const DiscoverTab({super.key});

  @override
  Widget build(BuildContext context) {
    return CupertinoPageScaffold(
      backgroundColor: IosColors.systemBackground,
      child: CustomScrollView(
        slivers: [
          CupertinoSliverNavigationBar(
            largeTitle: Text(Strings.current.discover),
            backgroundColor: Color(0xCC121212),
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
                        color: IosColors.ultraOrange.withOpacity(0.4),
                      ),
                    ),
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Row(
                          children: [
                            Icon(
                              CupertinoIcons.sparkles,
                              color: IosColors.ultraOrange,
                              size: 20,
                            ),
                            SizedBox(width: 8),
                            Flexible(
                              child: Text(
                                Strings.current.newInWatchos11,
                                style: TextStyle(
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
                          Strings.current.appleWatchUltra2OnAndroid,
                          style: TextStyle(
                            color: IosColors.label,
                            fontSize: 22,
                            fontWeight: FontWeight.w800,
                            letterSpacing: -0.4,
                          ),
                        ),
                        const SizedBox(height: 6),
                        Text(
                          Strings
                              .current
                              .syncNotificationsCallsWorkoutsAndWatchFacesWithYour,
                          style: TextStyle(
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
                  header: Strings.current.guidesTips,
                  children: [
                    IosListTile(
                      icon: CupertinoIcons.hand_point_right_fill,
                      iconBackgroundColor: IosColors.systemBlue,
                      title: Strings.current.gesturesControls,
                      subtitle:
                          Strings.current.doubleTapDigitalCrownAndActionButton,
                      onTap: () {
                        Navigator.of(context).push(
                          CupertinoPageRoute(
                            builder: (_) => const GestureGuideScreen(),
                          ),
                        );
                      },
                    ),
                    IosListTile(
                      icon: CupertinoIcons.heart_fill,
                      iconBackgroundColor: IosColors.systemRed,
                      title: Strings.current.healthWorkouts,
                      subtitle: Strings
                          .current
                          .setUpHeartRateZonesGpsRoutesAndActivity,
                      onTap: () {},
                    ),
                    IosListTile(
                      icon: CupertinoIcons.compass_fill,
                      iconBackgroundColor: IosColors.systemOrange,
                      title: Strings.current.compassBacktrack,
                      subtitle:
                          Strings.current.navigateWithoutAnInternetConnection,
                      onTap: () {},
                    ),
                    IosListTile(
                      icon: CupertinoIcons.bell_fill,
                      iconBackgroundColor: IosColors.systemPurple,
                      title: Strings.current.notificationMirroring,
                      subtitle: Strings.current.setUpSmsCallsAndMessagingApps,
                      onTap: () {},
                    ),
                  ],
                ),
                IosListSection(
                  header: Strings.current.resources,
                  children: [
                    IosListTile(
                      icon: CupertinoIcons.book_fill,
                      iconBackgroundColor: IosColors.systemTeal,
                      title: Strings.current.appleWatchUserGuide,
                      onTap: () {},
                    ),
                    IosListTile(
                      icon: CupertinoIcons.question_circle_fill,
                      iconBackgroundColor: IosColors.systemGreen,
                      title: Strings.current.halSupportDiagnostics,
                      onTap: () {},
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
