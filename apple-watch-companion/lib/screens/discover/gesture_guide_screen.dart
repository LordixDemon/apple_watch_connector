import '../../l10n/strings.dart';
import 'package:flutter/cupertino.dart';
import '../../theme/ios_colors.dart';
import '../../widgets/ios_list_section.dart';
import '../../widgets/ios_list_tile.dart';

class GestureGuideScreen extends StatelessWidget {
  const GestureGuideScreen({super.key});

  @override
  Widget build(BuildContext context) {
    return CupertinoPageScaffold(
      backgroundColor: IosColors.systemBackground,
      navigationBar: CupertinoNavigationBar(
        backgroundColor: Color(0xCC121212),
        middle: Text(Strings.current.gesturesControls),
        previousPageTitle: Strings.current.discover,
      ),
      child: SafeArea(
        child: ListView(
          children: [
            const SizedBox(height: 16),
            Padding(
              padding: const EdgeInsets.symmetric(horizontal: 16),
              child: Container(
                padding: const EdgeInsets.all(20),
                decoration: BoxDecoration(
                  gradient: const LinearGradient(
                    colors: [Color(0xFF2C2C2E), Color(0xFF1C1C1E)],
                    begin: Alignment.topLeft,
                    end: Alignment.bottomRight,
                  ),
                  borderRadius: BorderRadius.circular(16),
                ),
                child: Column(
                  children: [
                    Icon(
                      CupertinoIcons.hand_raised_fill,
                      color: IosColors.systemOrange,
                      size: 48,
                    ),
                    SizedBox(height: 12),
                    Text(
                      Strings.current.appleWatchUltra2Controls,
                      style: TextStyle(
                        color: IosColors.label,
                        fontSize: 20,
                        fontWeight: FontWeight.w700,
                      ),
                    ),
                    SizedBox(height: 6),
                    Text(
                      Strings.current.useDoubleTapTheDigitalCrownAndTheOrange,
                      textAlign: TextAlign.center,
                      style: TextStyle(
                        color: IosColors.secondaryLabel,
                        fontSize: 14,
                      ),
                    ),
                  ],
                ),
              ),
            ),
            const SizedBox(height: 16),
            IosListSection(
              header: Strings.current.essentialGestures,
              children: [
                IosListTile(
                  icon: CupertinoIcons.hand_point_right_fill,
                  iconBackgroundColor: IosColors.systemBlue,
                  title: Strings.current.doubleTap,
                  subtitle:
                      Strings.current.tapYourIndexFingerAndThumbTogetherTwiceTo,
                  showChevron: false,
                ),
                IosListTile(
                  icon: CupertinoIcons.circle,
                  iconBackgroundColor: IosColors.systemOrange,
                  title: Strings.current.digitalCrown,
                  subtitle:
                      Strings.current.turnToScrollThroughListsAndZoomOrPress,
                  showChevron: false,
                ),
                IosListTile(
                  icon: CupertinoIcons.slider_horizontal_below_rectangle,
                  iconBackgroundColor: IosColors.actionButtonOrange,
                  title: Strings.current.orangeActionButton,
                  subtitle: Strings.current.startAWorkoutMarkAWaypointOrHoldTo,
                  showChevron: false,
                ),
                IosListTile(
                  icon: CupertinoIcons.drop_fill,
                  iconBackgroundColor: IosColors.systemTeal,
                  title: Strings.current.waterLock,
                  subtitle: Strings
                      .current
                      .preventsAccidentalTouchesInWaterUseTheDigitalCrown,
                  showChevron: false,
                ),
              ],
            ),
          ],
        ),
      ),
    );
  }
}
