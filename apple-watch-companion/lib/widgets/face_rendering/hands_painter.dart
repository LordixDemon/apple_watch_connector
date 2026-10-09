import 'dart:math' as math;
import 'package:flutter/material.dart';

class HandsPainter extends CustomPainter {
  final DateTime time;
  final Color accentColor;
  final bool isNightMode;

  HandsPainter({
    required this.time,
    required this.accentColor,
    required this.isNightMode,
  });

  @override
  void paint(Canvas canvas, Size size) {
    final center = Offset(size.width / 2, size.height / 2);

    // Continuous smooth angles including fractional seconds & milliseconds
    final millis = time.millisecond / 1000.0;
    final exactSeconds = time.second + millis;
    final exactMinutes = time.minute + exactSeconds / 60.0;
    final exactHours = (time.hour % 12) + exactMinutes / 60.0;

    final hourAngle = exactHours * 30 * math.pi / 180;
    final minuteAngle = exactMinutes * 6 * math.pi / 180;
    final secondAngle = exactSeconds * 6 * math.pi / 180;

    final handColor = isNightMode ? const Color(0xFFFF2D55) : Colors.white;

    // Hour hand
    final hourPaint = Paint()
      ..color = handColor
      ..strokeWidth = size.width * 0.034
      ..strokeCap = StrokeCap.round;

    final hourLen = size.width * 0.22;
    canvas.drawLine(
      center,
      Offset(
        center.dx + hourLen * math.sin(hourAngle),
        center.dy - hourLen * math.cos(hourAngle),
      ),
      hourPaint,
    );

    // Minute hand
    final minutePaint = Paint()
      ..color = handColor
      ..strokeWidth = size.width * 0.026
      ..strokeCap = StrokeCap.round;

    final minuteLen = size.width * 0.32;
    canvas.drawLine(
      center,
      Offset(
        center.dx + minuteLen * math.sin(minuteAngle),
        center.dy - minuteLen * math.cos(minuteAngle),
      ),
      minutePaint,
    );

    // Second hand (Orange / Accent sweep)
    final secondPaint = Paint()
      ..color = accentColor
      ..strokeWidth = 1.5
      ..strokeCap = StrokeCap.round;

    final secondLen = size.width * 0.37;
    final tailLen = size.width * 0.08;

    // Front sweep
    canvas.drawLine(
      center,
      Offset(
        center.dx + secondLen * math.sin(secondAngle),
        center.dy - secondLen * math.cos(secondAngle),
      ),
      secondPaint,
    );
    // Counterbalance tail
    canvas.drawLine(
      center,
      Offset(
        center.dx - tailLen * math.sin(secondAngle),
        center.dy + tailLen * math.cos(secondAngle),
      ),
      secondPaint,
    );

    // Center pin
    canvas.drawCircle(center, size.width * 0.03, Paint()..color = accentColor);
    canvas.drawCircle(
      center,
      size.width * 0.012,
      Paint()..color = Colors.black,
    );
  }

  @override
  bool shouldRepaint(covariant HandsPainter oldDelegate) => true;
}
