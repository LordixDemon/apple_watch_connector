import 'package:flutter/widgets.dart';

/// Logical pixels shared by phone and desktop; independent of device DPI.
abstract final class CompanionSpacing {
  static const double pageInset = 16;
  static const double sectionGap = 12;
  static const double contentGap = 8;
  static const double cardInset = 12;
  static const double minimumTapExtent = 48;
  static const sectionMargin = EdgeInsets.symmetric(
    horizontal: pageInset,
    vertical: sectionGap / 2,
  );
}
