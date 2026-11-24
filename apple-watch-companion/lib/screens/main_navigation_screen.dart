import '../l10n/strings.dart';
import 'package:flutter/cupertino.dart';
import 'package:provider/provider.dart';
import '../theme/ios_colors.dart';
import '../providers/watch_connection_provider.dart';
import 'my_watch/my_watch_tab.dart';
import 'face_gallery/face_gallery_tab.dart';
import 'discover/discover_tab.dart';

class MainNavigationScreen extends StatefulWidget {
  const MainNavigationScreen({super.key});

  @override
  State<MainNavigationScreen> createState() => _MainNavigationScreenState();
}

class _MainNavigationScreenState extends State<MainNavigationScreen> {
  final CupertinoTabController _tabController = CupertinoTabController();
  int _currentIndex = 0;
  bool _facesAvailable = false;

  @override
  void dispose() {
    _tabController.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final s = Strings.of(context);
    final faces = context.select<WatchConnectionProvider, bool>(
      (c) => c.state.features.nativeFaces,
    );
    if (faces != _facesAvailable) {
      if (_currentIndex > 0) {
        _currentIndex = _facesAvailable && _currentIndex == 1
            ? 0
            : faces
            ? 2
            : 1;
      }
      _facesAvailable = faces;
      final targetIndex = _currentIndex;
      WidgetsBinding.instance.addPostFrameCallback((_) {
        if (mounted && _tabController.index != targetIndex) {
          _tabController.index = targetIndex;
        }
      });
    }
    final tabEntries = <(String, Widget)>[
      ('myWatch', const MyWatchTab()),
      if (faces) ('faceGallery', const FaceGalleryTab()),
      ('discover', const DiscoverTab()),
    ];
    if (_currentIndex >= tabEntries.length) _currentIndex = 0;

    return CupertinoTabScaffold(
      controller: _tabController,
      backgroundColor: IosColors.systemBackground,
      tabBar: CupertinoTabBar(
        backgroundColor: const Color(0xEE121212),
        activeColor: IosColors.systemOrange,
        inactiveColor: IosColors.systemGray2,
        iconSize: 24,
        currentIndex: _currentIndex,
        onTap: (index) {
          _tabController.index = index;
          setState(() => _currentIndex = index);
        },
        items: [
          BottomNavigationBarItem(
            icon: const Icon(CupertinoIcons.time_solid),
            label: s.myWatch,
          ),
          if (faces)
            BottomNavigationBarItem(
              icon: const Icon(CupertinoIcons.clock_fill),
              label: s.faceGallery,
            ),
          BottomNavigationBarItem(
            icon: const Icon(CupertinoIcons.compass_fill),
            label: s.discover,
          ),
        ],
      ),
      tabBuilder: (context, index) {
        final (tabKey, tabWidget) = tabEntries[index];
        return CupertinoTabView(
          key: ValueKey(tabKey),
          builder: (context) => tabWidget,
        );
      },
    );
  }
}
