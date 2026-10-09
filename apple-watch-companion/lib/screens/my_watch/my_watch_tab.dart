import '../../l10n/strings.dart';
import 'package:flutter/cupertino.dart';
import 'package:flutter/material.dart';
import 'package:provider/provider.dart';
import '../../providers/watch_provider.dart';
import '../../theme/ios_colors.dart';
import '../../theme/companion_spacing.dart';
import '../../widgets/ios_list_section.dart';
import '../../widgets/ios_list_tile.dart';
import '../../widgets/native_face_collection_panel.dart';
import '../face_gallery/native_face_library_screen.dart';
import '../face_gallery/native_face_editor_screen.dart';
import '../../models/native_face_collection.dart';
import '../face_gallery/native_face_presentation.dart';
import '../../widgets/watch_connection_card.dart';
import '../../providers/watch_connection_provider.dart';
import '../connection/watch_connection_screen.dart';
import '../../widgets/watch_telemetry_panel.dart';
import '../../widgets/phone_find_panel.dart';
import '../../widgets/watch_features_panel.dart';
import 'general_settings_screen.dart';
import 'display_brightness_screen.dart';
import 'sounds_haptics_screen.dart';
import 'notifications_screen.dart';
import 'passcode_screen.dart';
import 'action_button_screen.dart';
import 'health_activity_screen.dart';
import 'emergency_sos_screen.dart';
import 'app_detail_screen.dart';

class MyWatchTab extends StatelessWidget {
  const MyWatchTab({super.key});

  @override
  Widget build(BuildContext context) {
    final provider = context.watch<WatchProvider>();
    final watch = provider.device;
    void openFace(NativeWatchFace face, {bool fromLibrary = false}) {
      final collection = provider.faceCollection;
      final current = collection.face(face.id);
      if (current == null ||
          collection.pair == null ||
          collection.epoch == null) {
        return;
      }
      Navigator.of(context, rootNavigator: fromLibrary).push(
        CupertinoPageRoute(
          builder: (_) => NativeFaceEditorScreen(
            face: current,
            pair: collection.pair!,
            epoch: collection.epoch!,
          ),
        ),
      );
    }

    final connection = context.watch<WatchConnectionProvider>().state;

    return CupertinoPageScaffold(
      backgroundColor: IosColors.systemBackground,
      child: CustomScrollView(
        slivers: [
          CupertinoSliverNavigationBar(
            largeTitle: Text(Strings.current.myWatch),
            backgroundColor: const Color(0xCC121212),
            trailing: CupertinoButton(
              padding: EdgeInsets.zero,
              child: Text(
                Strings.current.allWatches,
                style: TextStyle(
                  color: IosColors.systemOrange,
                  fontSize: 16,
                  fontWeight: FontWeight.w600,
                ),
              ),
              onPressed: () {
                _showAllWatchesSheet(context);
              },
            ),
          ),
          SliverToBoxAdapter(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: [
                const SizedBox(height: CompanionSpacing.contentGap),

                Padding(
                  padding: const EdgeInsets.symmetric(horizontal: 16),
                  child: GestureDetector(
                    onTap: () => _showAllWatchesSheet(context),
                    child: WatchConnectionCard(state: connection),
                  ),
                ),
                const SizedBox(height: CompanionSpacing.sectionGap / 2),
                IosListSection(
                  children: [
                    IosListTile(
                      title: Strings.current.watchConnection,
                      trailingText: connection.hasPair
                          ? Strings.current.savedWatch
                          : Strings.current.pairAppleWatch,
                      onTap: () => _showAllWatchesSheet(context),
                    ),
                  ],
                ),
                WatchFeaturesPanel(state: connection),
                if (watch.isConnected && connection.features.telemetry)
                  Padding(
                    padding: const EdgeInsets.fromLTRB(16, 6, 16, 6),
                    child: WatchTelemetryPanel(
                      telemetry: watch.telemetry,
                      connected: watch.isConnected,
                      refresh: provider.refreshDeviceInfo,
                    ),
                  ),
                if (connection.features.phoneFind)
                  PhoneFindPanel(
                    state: provider.phoneFind,
                    stop: provider.stopPhonePing,
                    permission: provider.requestPhoneFlashPermission,
                  ),
                if (connection.features.nativeFaces)
                  NativeFaceCollectionPanel(
                    faceTitle: nativeFaceTitle,
                    openFace: openFace,
                    controller: provider.nativeFaces,
                    manage: () =>
                        Navigator.of(context, rootNavigator: true).push(
                          CupertinoPageRoute(
                            fullscreenDialog: true,
                            builder: (_) => NativeFaceLibraryScreen(
                              controller: provider.nativeFaces,
                              faceTitle: nativeFaceTitle,
                              openFace: (face) =>
                                  openFace(face, fromLibrary: true),
                            ),
                          ),
                        ),
                  ),
                // --- Main Settings Navigation List ---
                if (connection.features.watchSettings)
                  IosListSection(
                    children: [
                      IosListTile(
                        icon: CupertinoIcons.gear_alt_fill,
                        iconBackgroundColor: IosColors.systemGray,
                        title: Strings.current.general,
                        onTap: () {
                          Navigator.of(context).push(
                            CupertinoPageRoute(
                              builder: (_) => const GeneralSettingsScreen(),
                            ),
                          );
                        },
                      ),
                      if (connection.features.supports('displaySettings'))
                        IosListTile(
                          icon: CupertinoIcons.sun_max_fill,
                          iconBackgroundColor: IosColors.systemBlue,
                          title: Strings.current.displayBrightness,
                          onTap: () {
                            Navigator.of(context).push(
                              CupertinoPageRoute(
                                builder: (_) => const DisplayBrightnessScreen(),
                              ),
                            );
                          },
                        ),
                      if (connection.features.supports('soundSettings'))
                        IosListTile(
                          icon: CupertinoIcons.speaker_2_fill,
                          iconBackgroundColor: IosColors.systemPink,
                          title: Strings.current.soundsHaptics,
                          onTap: () {
                            Navigator.of(context).push(
                              CupertinoPageRoute(
                                builder: (_) => const SoundsHapticsScreen(),
                              ),
                            );
                          },
                        ),
                      if (connection.features.notifications)
                        IosListTile(
                          icon: CupertinoIcons.bell_fill,
                          iconBackgroundColor: IosColors.systemRed,
                          title: Strings.current.notifications,
                          onTap: () {
                            Navigator.of(context).push(
                              CupertinoPageRoute(
                                builder: (_) => const NotificationsScreen(),
                              ),
                            );
                          },
                        ),
                      if (connection.features.supports('passcode'))
                        IosListTile(
                          icon: CupertinoIcons.lock_shield_fill,
                          iconBackgroundColor: IosColors.systemIndigo,
                          title: Strings.current.passcode,
                          onTap: () {
                            Navigator.of(context).push(
                              CupertinoPageRoute(
                                builder: (_) => const PasscodeScreen(),
                              ),
                            );
                          },
                        ),
                      if (connection.features.supports('actionButton'))
                        IosListTile(
                          icon:
                              CupertinoIcons.slider_horizontal_below_rectangle,
                          iconBackgroundColor: IosColors.actionButtonOrange,
                          title: Strings.current.actionButton,
                          trailingText: provider.settings.actionButtonApp,
                          onTap: () {
                            Navigator.of(context).push(
                              CupertinoPageRoute(
                                builder: (_) => const ActionButtonScreen(),
                              ),
                            );
                          },
                        ),
                      if (connection.features.health)
                        IosListTile(
                          icon: CupertinoIcons.heart_fill,
                          iconBackgroundColor: IosColors.activityRed,
                          title: Strings.current.activityHealth,
                          onTap: () {
                            Navigator.of(context).push(
                              CupertinoPageRoute(
                                builder: (_) => const HealthActivityScreen(),
                              ),
                            );
                          },
                        ),
                      if (connection.features.supports('emergencySos'))
                        IosListTile(
                          icon: CupertinoIcons.bandage_fill,
                          iconBackgroundColor: IosColors.systemRed,
                          title: Strings.current.emergencySos,
                          onTap: () {
                            Navigator.of(context).push(
                              CupertinoPageRoute(
                                builder: (_) => const EmergencySosScreen(),
                              ),
                            );
                          },
                        ),
                    ],
                  ),

                // --- Installed Apps Section ---
                if (connection.features.appManagement)
                  IosListSection(
                    header: Strings.current.installedOnAppleWatch,
                    children: provider.installedApps.map((app) {
                      return IosListTile(
                        icon: app.icon,
                        iconBackgroundColor: app.iconBackgroundColor,
                        title: app.name,
                        trailingText: app.showOnWatch
                            ? Strings.current.installed
                            : Strings.current.hidden,
                        onTap: () {
                          Navigator.of(context).push(
                            CupertinoPageRoute(
                              builder: (_) => AppDetailScreen(app: app),
                            ),
                          );
                        },
                      );
                    }).toList(),
                  ),

                const SizedBox(height: CompanionSpacing.pageInset),
              ],
            ),
          ),
        ],
      ),
    );
  }

  void _showAllWatchesSheet(BuildContext context) {
    Navigator.of(
      context,
    ).push(CupertinoPageRoute(builder: (_) => const WatchConnectionScreen()));
  }
}
