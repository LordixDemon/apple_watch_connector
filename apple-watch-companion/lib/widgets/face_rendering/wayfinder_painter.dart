import 'dart:math' as math;
import 'package:flutter/material.dart';
import '../../models/watch_face.dart';

class WayfinderPainter extends CustomPainter {
  final Color color;
  final bool nightMode;
  final BezelStyle bezelStyle;
  final DateTime time;

  WayfinderPainter({
    required this.color,
    required this.nightMode,
    required this.bezelStyle,
    required this.time,
  });

  @override
  void paint(Canvas canvas, Size size) {
    final center = Offset(size.width / 2, size.height / 2);
    final radius = size.width * 0.45;
    final activeColor = nightMode ? const Color(0xFFFF2D55) : color;

    final paint = Paint()
      ..color = activeColor
      ..style = PaintingStyle.stroke
      ..strokeWidth = 1.0;

    // Outer bezel boundary
    canvas.drawCircle(center, radius, paint);

    // Inner dial track
    final innerRadius = radius * 0.78;
    canvas.drawCircle(
      center,
      innerRadius,
      Paint()
        ..color = activeColor.withValues(alpha: 0.25)
        ..style = PaintingStyle.stroke
        ..strokeWidth = 0.8,
    );

    // 60-second / degree ticks
    for (int i = 0; i < 60; i++) {
      final angle = (i * 6) * math.pi / 180;
      final isMajor = i % 5 == 0;
      final len = isMajor ? size.width * 0.045 : size.width * 0.02;

      final p1 = Offset(
        center.dx + radius * math.sin(angle),
        center.dy - radius * math.cos(angle),
      );
      final p2 = Offset(
        center.dx + (radius - len) * math.sin(angle),
        center.dy - (radius - len) * math.cos(angle),
      );

      paint.strokeWidth = isMajor ? 1.8 : 0.8;
      paint.color = isMajor ? activeColor : activeColor.withValues(alpha: 0.6);
      canvas.drawLine(p1, p2, paint);
    }

    // Compass Cardinal Marks (N, E, S, W)
    final textPainter = TextPainter(textDirection: TextDirection.ltr);
    final cardinals = ['N', 'E', 'S', 'W'];
    for (int j = 0; j < 4; j++) {
      final angle = (j * 90) * math.pi / 180;
      final dist = radius * 0.88;
      final pos = Offset(
        center.dx + dist * math.sin(angle),
        center.dy - dist * math.cos(angle),
      );

      textPainter.text = TextSpan(
        text: cardinals[j],
        style: TextStyle(
          color: cardinals[j] == 'N' && !nightMode
              ? const Color(0xFFFF3B30)
              : activeColor,
          fontSize: size.width * 0.045,
          fontWeight: FontWeight.w900,
        ),
      );
      textPainter.layout();
      textPainter.paint(
        canvas,
        Offset(pos.dx - textPainter.width / 2, pos.dy - textPainter.height / 2),
      );
    }

    // Center crosshair
    final crossHairPaint = Paint()
      ..color = activeColor.withValues(alpha: 0.2)
      ..strokeWidth = 0.8;
    canvas.drawLine(
      Offset(center.dx - 12, center.dy),
      Offset(center.dx + 12, center.dy),
      crossHairPaint,
    );
    canvas.drawLine(
      Offset(center.dx, center.dy - 12),
      Offset(center.dx, center.dy + 12),
      crossHairPaint,
    );
  }

  @override
  bool shouldRepaint(covariant WayfinderPainter oldDelegate) =>
      color != oldDelegate.color ||
      nightMode != oldDelegate.nightMode ||
      bezelStyle != oldDelegate.bezelStyle;
}
