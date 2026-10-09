import 'package:flutter/cupertino.dart';
import 'package:flutter/material.dart';
import 'ios_colors.dart';

/// Cupertino & Material dark theme configured to mirror Apple's iOS Watch.app.
class IosTheme {
  IosTheme._();

  static CupertinoThemeData get cupertinoDarkTheme {
    return const CupertinoThemeData(
      brightness: Brightness.dark,
      primaryColor: IosColors.systemOrange,
      primaryContrastingColor: IosColors.label,
      barBackgroundColor: Color(0xCC121212),
      scaffoldBackgroundColor: IosColors.systemBackground,
      textTheme: CupertinoTextThemeData(
        primaryColor: IosColors.label,
        textStyle: TextStyle(
          color: IosColors.label,
          fontSize: 17.0,
          letterSpacing: -0.4,
          fontFamily: '.SF Pro Text',
        ),
        navTitleTextStyle: TextStyle(
          inherit: false,
          color: IosColors.label,
          fontSize: 17.0,
          fontWeight: FontWeight.w600,
          letterSpacing: -0.4,
        ),
        navLargeTitleTextStyle: TextStyle(
          inherit: false,
          color: IosColors.label,
          fontSize: 34.0,
          fontWeight: FontWeight.w700,
          letterSpacing: 0.37,
        ),
        actionTextStyle: TextStyle(
          color: IosColors.systemOrange,
          fontSize: 17.0,
          letterSpacing: -0.4,
        ),
        tabLabelTextStyle: TextStyle(
          fontSize: 10.0,
          fontWeight: FontWeight.w500,
          letterSpacing: -0.2,
        ),
      ),
    );
  }

  static ThemeData get materialDarkTheme {
    return ThemeData(
      brightness: Brightness.dark,
      useMaterial3: true,
      scaffoldBackgroundColor: IosColors.systemBackground,
      colorScheme: const ColorScheme.dark(
        primary: IosColors.systemOrange,
        secondary: IosColors.systemBlue,
        surface: IosColors.secondarySystemBackground,
        error: IosColors.systemRed,
        onPrimary: Colors.black,
        onSurface: IosColors.label,
      ),
      cardTheme: CardThemeData(
        color: IosColors.secondarySystemBackground,
        elevation: 0,
        shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(12)),
      ),
      dividerTheme: const DividerThemeData(
        color: IosColors.separator,
        thickness: 0.5,
        space: 0.5,
      ),
      appBarTheme: const AppBarTheme(
        backgroundColor: Colors.transparent,
        elevation: 0,
        centerTitle: true,
        iconTheme: IconThemeData(color: IosColors.systemOrange),
        titleTextStyle: TextStyle(
          color: IosColors.label,
          fontSize: 17,
          fontWeight: FontWeight.w600,
          letterSpacing: -0.4,
        ),
      ),
      cupertinoOverrideTheme: cupertinoDarkTheme,
    );
  }
}
