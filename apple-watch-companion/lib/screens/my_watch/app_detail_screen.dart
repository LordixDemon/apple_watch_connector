import '../../l10n/strings.dart';
import 'package:flutter/cupertino.dart';
import 'package:flutter/material.dart';
import 'package:provider/provider.dart';
import '../../models/watch_app_item.dart';
import '../../providers/watch_provider.dart';
import '../../theme/ios_colors.dart';
import '../../widgets/ios_list_section.dart';
import '../../widgets/ios_list_tile.dart';

class AppDetailScreen extends StatelessWidget {
  final WatchAppItem app;

  const AppDetailScreen({super.key, required this.app});

  @override
  Widget build(BuildContext context) {
    final provider = context.watch<WatchProvider>();
    final currentApp = provider.installedApps.firstWhere(
      (a) => a.id == app.id,
      orElse: () => app,
    );

    return CupertinoPageScaffold(
      backgroundColor: IosColors.systemBackground,
      navigationBar: CupertinoNavigationBar(
        backgroundColor: const Color(0xCC121212),
        middle: Text(currentApp.name),
        previousPageTitle: Strings.current.myWatch,
      ),
      child: SafeArea(
        child: ListView(
          children: [
            const SizedBox(height: 24),
            Center(
              child: Column(
                children: [
                  Container(
                    width: 72,
                    height: 72,
                    decoration: BoxDecoration(
                      color: currentApp.iconBackgroundColor,
                      borderRadius: BorderRadius.circular(16),
                      boxShadow: [
                        BoxShadow(
                          color: currentApp.iconBackgroundColor.withOpacity(
                            0.4,
                          ),
                          blurRadius: 16,
                          offset: const Offset(0, 4),
                        ),
                      ],
                    ),
                    child: Icon(currentApp.icon, color: Colors.white, size: 38),
                  ),
                  const SizedBox(height: 12),
                  Text(
                    currentApp.name,
                    style: const TextStyle(
                      color: IosColors.label,
                      fontSize: 22,
                      fontWeight: FontWeight.w700,
                    ),
                  ),
                  const SizedBox(height: 4),
                  Text(
                    Strings.current.version(
                      (currentApp.developer).toString(),
                      (currentApp.version).toString(),
                    ),
                    style: const TextStyle(
                      color: IosColors.secondaryLabel,
                      fontSize: 14,
                    ),
                  ),
                ],
              ),
            ),
            const SizedBox(height: 24),
            IosListSection(
              children: [
                IosListTile(
                  title: Strings.current.showAppOnAppleWatch,
                  trailing: CupertinoSwitch(
                    value: currentApp.showOnWatch,
                    activeTrackColor: IosColors.systemGreen,
                    onChanged: (val) {
                      provider.toggleApp(currentApp.id, val);
                    },
                  ),
                  showChevron: false,
                ),
              ],
            ),
            if (currentApp.description.isNotEmpty)
              Padding(
                padding: const EdgeInsets.symmetric(
                  horizontal: 32,
                  vertical: 12,
                ),
                child: Text(
                  currentApp.description,
                  style: const TextStyle(
                    color: IosColors.secondaryLabel,
                    fontSize: 14,
                    height: 1.4,
                  ),
                ),
              ),
          ],
        ),
      ),
    );
  }
}
