import 'package:flutter/cupertino.dart';
import 'package:flutter_localizations/flutter_localizations.dart';
import '../l10n/strings.dart';
import '../theme/ios_theme.dart';
import '../screens/main_navigation_screen.dart';

class AppleWatchCompanionApp extends StatelessWidget {
  const AppleWatchCompanionApp({super.key});

  @override
  Widget build(BuildContext context) {
    return CupertinoApp(
      onGenerateTitle: (_) => Strings.current.watch,
      localizationsDelegates: const [
        Strings.delegate,
        GlobalCupertinoLocalizations.delegate,
        GlobalMaterialLocalizations.delegate,
        GlobalWidgetsLocalizations.delegate,
      ],
      supportedLocales: Strings.supportedLocales,
      debugShowCheckedModeBanner: false,
      theme: IosTheme.cupertinoDarkTheme,
      builder: (context, child) => LayoutBuilder(
        builder: (context, constraints) {
          final width = constraints.maxWidth.clamp(0.0, 860.0);
          return ColoredBox(
            color: IosTheme.cupertinoDarkTheme.scaffoldBackgroundColor,
            child: Center(
              child: SizedBox(
                width: width,
                child: MediaQuery(
                  data: MediaQuery.of(
                    context,
                  ).copyWith(size: Size(width, constraints.maxHeight)),
                  child: child ?? const SizedBox(),
                ),
              ),
            ),
          );
        },
      ),
      home: const MainNavigationScreen(),
    );
  }
}
