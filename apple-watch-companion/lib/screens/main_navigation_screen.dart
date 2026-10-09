import '../l10n/strings.dart';
import 'package:flutter/cupertino.dart';
import '../theme/ios_colors.dart';
import 'my_watch/my_watch_tab.dart';
import 'face_gallery/face_gallery_tab.dart';
import 'discover/discover_tab.dart';
import 'package:provider/provider.dart';
import '../providers/watch_connection_provider.dart';

class MainNavigationScreen extends StatefulWidget {
  const MainNavigationScreen({super.key});

  @override
  State<MainNavigationScreen> createState() => _MainNavigationScreenState();
}

class _MainNavigationScreenState extends State<MainNavigationScreen> {
  int _currentIndex = 0;
  bool _facesAvailable = false;

  @override
  Widget build(BuildContext context) {
    final faces = context
        .watch<WatchConnectionProvider>()
        .state
        .features
        .nativeFaces;
    if (faces != _facesAvailable) {
      if (_currentIndex > 0) {
        _currentIndex = _facesAvailable && _currentIndex == 1
            ? 0
            : faces
            ? 2
            : 1;
      }
      _facesAvailable = faces;
    }
    final tabs = <Widget>[
      const MyWatchTab(),
      if (faces) const FaceGalleryTab(),
      const DiscoverTab(),
    ];
    if (_currentIndex >= tabs.length) _currentIndex = 0;
    return CupertinoTabScaffold(
      key: ValueKey(faces),
      backgroundColor: IosColors.systemBackground,
      tabBar: CupertinoTabBar(
        backgroundColor: const Color(0xEE121212),
        activeColor: IosColors.systemOrange,
        inactiveColor: IosColors.systemGray2,
        iconSize: 24,
        currentIndex: _currentIndex,
        onTap: (index) => setState(() => _currentIndex = index),
        items: [
          BottomNavigationBarItem(
            icon: Icon(CupertinoIcons.time_solid),
            label: Strings.current.myWatch,
          ),
          if (faces)
            BottomNavigationBarItem(
              icon: Icon(CupertinoIcons.clock_fill),
              label: Strings.current.faceGallery,
            ),
          BottomNavigationBarItem(
            icon: Icon(CupertinoIcons.compass_fill),
            label: Strings.current.discover,
          ),
        ],
      ),
      tabBuilder: (context, index) {
        return CupertinoTabView(builder: (context) => tabs[index]);
      },
    );
  }
}
