import 'package:flutter/material.dart';

class InfographPainter extends CustomPainter {
  final Color primaryColor;
  final bool nightMode;

  InfographPainter({required this.primaryColor, required this.nightMode});

  @override
  void paint(Canvas canvas, Size size) {
    final center = Offset(size.width / 2, size.height / 2);
    final activeColor = nightMode ? const Color(0xFFFF2D55) : primaryColor;

    // Outer 60-second dial track
    canvas.drawCircle(
      center,
      size.width * 0.40,
      Paint()
        ..color = activeColor.withValues(alpha: 0.3)
        ..style = PaintingStyle.stroke
        ..strokeWidth = 1,
    );

    // 4 Corner subdial arcs
    final rSub = size.width * 0.12;
    final offsets = [
      Offset(center.dx - size.width * 0.18, center.dy - size.width * 0.18),
      Offset(center.dx + size.width * 0.18, center.dy - size.width * 0.18),
      Offset(center.dx - size.width * 0.18, center.dy + size.width * 0.18),
      Offset(center.dx + size.width * 0.18, center.dy + size.width * 0.18),
    ];

    for (final off in offsets) {
      canvas.drawCircle(
        off,
        rSub,
        Paint()
          ..color = const Color(0xFF161618)
          ..style = PaintingStyle.fill,
      );
      canvas.drawCircle(
        off,
        rSub,
        Paint()
          ..color = activeColor.withValues(alpha: 0.3)
          ..style = PaintingStyle.stroke
          ..strokeWidth = 0.8,
      );
    }
  }

  @override
  bool shouldRepaint(covariant InfographPainter oldDelegate) =>
      primaryColor != oldDelegate.primaryColor ||
      nightMode != oldDelegate.nightMode;
}
