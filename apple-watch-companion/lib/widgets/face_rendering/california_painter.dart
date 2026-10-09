import 'dart:math' as math;
import 'package:flutter/material.dart';

class CaliforniaPainter extends CustomPainter {
  final Color primaryColor;
  final bool nightMode;

  CaliforniaPainter({required this.primaryColor, required this.nightMode});

  @override
  void paint(Canvas canvas, Size size) {
    final center = Offset(size.width / 2, size.height / 2);
    final activeColor = nightMode ? const Color(0xFFFF2D55) : primaryColor;

    // Track frame
    final rect = Rect.fromCenter(
      center: center,
      width: size.width * 0.82,
      height: size.height * 0.82,
    );
    canvas.drawRRect(
      RRect.fromRectAndRadius(rect, const Radius.circular(24)),
      Paint()
        ..color = activeColor.withValues(alpha: 0.3)
        ..style = PaintingStyle.stroke
        ..strokeWidth = 1.0,
    );

    // California numerals: Roman on top (XII, I, II, X, XI), Arabic on bottom (4, 5, 7, 8)
    final textPainter = TextPainter(textDirection: TextDirection.ltr);
    final numerals = {
      0: 'XII',
      1: 'I',
      2: 'II',
      3: '—',
      4: '4',
      5: '5',
      6: '—',
      7: '7',
      8: '8',
      9: '—',
      10: 'X',
      11: 'XI',
    };

    final rText = size.width * 0.33;
    for (final entry in numerals.entries) {
      final angle = (entry.key * 30) * math.pi / 180;
      final pos = Offset(
        center.dx + rText * math.sin(angle),
        center.dy - rText * math.cos(angle),
      );

      textPainter.text = TextSpan(
        text: entry.value,
        style: TextStyle(
          color: activeColor,
          fontSize: size.width * 0.055,
          fontWeight: FontWeight.w800,
        ),
      );
      textPainter.layout();
      textPainter.paint(
        canvas,
        Offset(pos.dx - textPainter.width / 2, pos.dy - textPainter.height / 2),
      );
    }
  }

  @override
  bool shouldRepaint(covariant CaliforniaPainter oldDelegate) =>
      primaryColor != oldDelegate.primaryColor ||
      nightMode != oldDelegate.nightMode;
}
