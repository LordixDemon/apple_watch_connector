import '../../l10n/strings.dart';
import 'package:flutter/cupertino.dart';
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
import 'general_settings_screen.dart';
import 'notifications_screen.dart';
import 'health_activity_screen.dart';

class MyWatchTab extends StatelessWidget {
  const MyWatchTab({super.key});

  @override
  Widget build(BuildContext context) {
    final s = Strings.of(context);
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
            largeTitle: Text(s.myWatch),
            backgroundColor: const Color(0xCC121212),
            trailing: CupertinoButton(
              padding: EdgeInsets.zero,
              child: Text(
                s.allWatches,
                style: const TextStyle(
                  color: IosColors.systemOrange,
                  fontSize: 16,
                  fontWeight: FontWeight.w600,
                ),
              ),
              onPressed: () => _openConnection(context),
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
                    onTap: () => _openConnection(context),
                    child: WatchConnectionCard(
                      state: connection,
                      showChevron: true,
                    ),
                  ),
                ),
                if (watch.isConnected && connection.features.telemetry)
                  Padding(
                    padding: const EdgeInsets.fromLTRB(16, 12, 16, 6),
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
                if (connection.features.watchSettings ||
                    connection.features.notifications ||
                    connection.features.health)
                  IosListSection(
                    children: [
                      if (connection.features.watchSettings)
                        IosListTile(
                          icon: CupertinoIcons.gear_alt_fill,
                          iconBackgroundColor: IosColors.systemGray,
                          title: s.general,
                          onTap: () {
                            Navigator.of(context).push(
                              CupertinoPageRoute(
                                builder: (_) => const GeneralSettingsScreen(),
                              ),
                            );
                          },
                        ),
                      if (connection.features.notifications)
                        IosListTile(
                          icon: CupertinoIcons.bell_fill,
                          iconBackgroundColor: IosColors.systemRed,
                          title: s.notifications,
                          onTap: () {
                            Navigator.of(context).push(
                              CupertinoPageRoute(
                                builder: (_) => const NotificationsScreen(),
                              ),
                            );
                          },
                        ),
                      if (connection.features.health)
                        IosListTile(
                          icon: CupertinoIcons.heart_fill,
                          iconBackgroundColor: IosColors.activityRed,
                          title: s.activityHealth,
                          onTap: () {
                            Navigator.of(context).push(
                              CupertinoPageRoute(
                                builder: (_) => const HealthActivityScreen(),
                              ),
                            );
                          },
                        ),
                    ],
                  ),
                const SizedBox(height: CompanionSpacing.pageInset),
              ],
            ),
          ),
        ],
      ),
    );
  }

  void _openConnection(BuildContext context) {
    Navigator.of(
      context,
    ).push(CupertinoPageRoute(builder: (_) => const WatchConnectionScreen()));
  }
}
