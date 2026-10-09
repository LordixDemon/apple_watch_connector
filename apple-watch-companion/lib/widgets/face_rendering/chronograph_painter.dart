import 'dart:math' as math;
import 'package:flutter/material.dart';

class ChronographPainter extends CustomPainter {
  final Color primaryColor;
  final DateTime time;

  ChronographPainter({required this.primaryColor, required this.time});

  @override
  void paint(Canvas canvas, Size size) {
    final center = Offset(size.width / 2, size.height / 2);

    // Main tachymeter scale
    canvas.drawCircle(
      center,
      size.width * 0.44,
      Paint()
        ..color = primaryColor.withValues(alpha: 0.25)
        ..style = PaintingStyle.stroke
        ..strokeWidth = 1.5,
    );

    // Subdial 1: 60-second subdial
    final sub1Center = Offset(center.dx, center.dy - size.width * 0.16);
    _drawSubdial(
      canvas,
      sub1Center,
      size.width * 0.12,
      (time.second / 60.0) * 2 * math.pi,
    );

    // Subdial 2: 30-minute subdial
    final sub2Center = Offset(center.dx, center.dy + size.width * 0.16);
    _drawSubdial(
      canvas,
      sub2Center,
      size.width * 0.12,
      (time.minute / 30.0) * 2 * math.pi,
    );
  }

  void _drawSubdial(
    Canvas canvas,
    Offset center,
    double radius,
    double handAngle,
  ) {
    canvas.drawCircle(
      center,
      radius,
      Paint()
        ..color = const Color(0xFF1E1E22)
        ..style = PaintingStyle.fill,
    );
    canvas.drawCircle(
      center,
      radius,
      Paint()
        ..color = primaryColor.withValues(alpha: 0.4)
        ..style = PaintingStyle.stroke
        ..strokeWidth = 0.8,
    );

    // Small subdial hand
    final handPaint = Paint()
      ..color = primaryColor
      ..strokeWidth = 1.2
      ..strokeCap = StrokeCap.round;
    canvas.drawLine(
      center,
      Offset(
        center.dx + radius * 0.8 * math.sin(handAngle),
        center.dy - radius * 0.8 * math.cos(handAngle),
      ),
      handPaint,
    );
  }

  @override
  bool shouldRepaint(covariant ChronographPainter oldDelegate) => true;
}
