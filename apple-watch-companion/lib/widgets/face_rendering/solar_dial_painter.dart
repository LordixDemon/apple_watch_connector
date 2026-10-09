import 'dart:math' as math;
import 'package:flutter/material.dart';

class SolarDialPainter extends CustomPainter {
  final Color primaryColor;
  final DateTime time;

  SolarDialPainter({required this.primaryColor, required this.time});

  @override
  void paint(Canvas canvas, Size size) {
    final center = Offset(size.width / 2, size.height / 2);
    final radius = size.width * 0.42;

    // Solar cycle: 24 hours mapped to 360 degrees
    final totalHours =
        time.hour + (time.minute / 60.0) + (time.second / 3600.0);
    final sunAngle = (totalHours / 24.0) * 2 * math.pi;

    // Dual-tone daylight/night background
    final horizonRect = Rect.fromCircle(center: center, radius: radius);
    final dayPaint = Paint()
      ..shader = LinearGradient(
        begin: Alignment.topCenter,
        end: Alignment.bottomCenter,
        colors: [primaryColor.withValues(alpha: 0.3), const Color(0xFF0D1B2A)],
      ).createShader(horizonRect);

    canvas.drawCircle(center, radius, dayPaint);

    // Horizon line
    canvas.drawLine(
      Offset(center.dx - radius, center.dy),
      Offset(center.dx + radius, center.dy),
      Paint()
        ..color = primaryColor.withValues(alpha: 0.5)
        ..strokeWidth = 1.0,
    );

    // Solar sinus curve
    final path = Path();
    for (double x = -radius; x <= radius; x += 2) {
      final normalizedX = x / radius;
      final y = -math.sin(normalizedX * math.pi) * (radius * 0.45);
      if (x == -radius) {
        path.moveTo(center.dx + x, center.dy + y);
      } else {
        path.lineTo(center.dx + x, center.dy + y);
      }
    }
    canvas.drawPath(
      path,
      Paint()
        ..color = primaryColor.withValues(alpha: 0.6)
        ..style = PaintingStyle.stroke
        ..strokeWidth = 1.5,
    );

    // Sun orb along the curve
    final sunX = radius * math.sin(sunAngle - math.pi / 2);
    final sunY = -math.sin((sunX / radius) * math.pi) * (radius * 0.45);
    final sunPos = Offset(center.dx + sunX, center.dy + sunY);

    // Glowing sun
    canvas.drawCircle(sunPos, 8, Paint()..color = const Color(0xFFFFD60A));
    canvas.drawCircle(sunPos, 14, Paint()..color = const Color(0x55FFD60A));
  }

  @override
  bool shouldRepaint(covariant SolarDialPainter oldDelegate) => true;
}
