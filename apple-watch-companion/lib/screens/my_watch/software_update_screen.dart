import '../../l10n/strings.dart';
import 'package:flutter/cupertino.dart';
import 'package:provider/provider.dart';
import '../../providers/watch_provider.dart';
import '../../theme/ios_colors.dart';
import '../../widgets/ios_list_section.dart';
import '../../widgets/ios_list_tile.dart';

class SoftwareUpdateScreen extends StatefulWidget {
  const SoftwareUpdateScreen({super.key});

  @override
  State<SoftwareUpdateScreen> createState() => _SoftwareUpdateScreenState();
}

class _SoftwareUpdateScreenState extends State<SoftwareUpdateScreen> {
  bool _isChecking = false;

  void _checkUpdate() {
    setState(() => _isChecking = true);
    Future.delayed(const Duration(seconds: 2), () {
      if (mounted) setState(() => _isChecking = false);
    });
  }

  @override
  Widget build(BuildContext context) {
    final watch = context.watch<WatchProvider>().device;

    return CupertinoPageScaffold(
      backgroundColor: IosColors.systemBackground,
      navigationBar: CupertinoNavigationBar(
        backgroundColor: Color(0xCC121212),
        middle: Text(Strings.current.softwareUpdate),
        previousPageTitle: Strings.current.general,
      ),
      child: SafeArea(
        child: ListView(
          children: [
            const SizedBox(height: 12),
            IosListSection(
              children: [
                IosListTile(
                  title: Strings.current.automaticUpdates,
                  trailingText: Strings.current.on,
                  onTap: () {},
                ),
                IosListTile(
                  title: Strings.current.betaUpdates,
                  trailingText: Strings.current.off,
                  onTap: () {},
                ),
              ],
            ),
            const SizedBox(height: 40),
            Center(
              child: Padding(
                padding: const EdgeInsets.symmetric(horizontal: 32),
                child: Column(
                  children: [
                    if (_isChecking) ...[
                      const CupertinoActivityIndicator(radius: 16),
                      const SizedBox(height: 16),
                      Text(
                        Strings.current.checkingForUpdates,
                        style: TextStyle(
                          color: IosColors.secondaryLabel,
                          fontSize: 15,
                        ),
                      ),
                    ] else ...[
                      const Icon(
                        CupertinoIcons.checkmark_seal_fill,
                        color: IosColors.systemGreen,
                        size: 54,
                      ),
                      const SizedBox(height: 16),
                      Text(
                        watch.watchOsVersion,
                        style: const TextStyle(
                          color: IosColors.label,
                          fontSize: 20,
                          fontWeight: FontWeight.w700,
                        ),
                      ),
                      const SizedBox(height: 8),
                      Text(
                        Strings.current.yourAppleWatchSoftwareIsUpToDate,
                        textAlign: TextAlign.center,
                        style: TextStyle(
                          color: IosColors.secondaryLabel,
                          fontSize: 15,
                        ),
                      ),
                      const SizedBox(height: 24),
                      CupertinoButton(
                        color: IosColors.secondarySystemBackground,
                        borderRadius: BorderRadius.circular(10),
                        onPressed: _checkUpdate,
                        child: Text(
                          Strings.current.checkAgain,
                          style: TextStyle(
                            color: IosColors.systemOrange,
                            fontWeight: FontWeight.w600,
                          ),
                        ),
                      ),
                    ],
                  ],
                ),
              ),
            ),
          ],
        ),
      ),
    );
  }
}
