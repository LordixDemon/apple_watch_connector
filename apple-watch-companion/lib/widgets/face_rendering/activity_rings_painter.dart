import 'dart:math' as math;
import 'package:flutter/material.dart';
import '../../theme/ios_colors.dart';

class ActivityRingsPainter extends CustomPainter {
  final double moveProgress;
  final double exerciseProgress;
  final double standProgress;
  final bool showCenterTime;
  final DateTime time;
  final Color primaryColor;

  ActivityRingsPainter({
    required this.moveProgress,
    required this.exerciseProgress,
    required this.standProgress,
    required this.showCenterTime,
    required this.time,
    required this.primaryColor,
  });

  @override
  void paint(Canvas canvas, Size size) {
    final center = Offset(size.width / 2, size.height / 2);
    final stroke = size.width * 0.11;

    final rMove = (size.width / 2) - stroke / 2;
    final rExercise = rMove - stroke * 1.15;
    final rStand = rExercise - stroke * 1.15;

    _drawRing(
      canvas,
      center,
      rMove,
      stroke,
      moveProgress,
      IosColors.activityRed,
    );
    _drawRing(
      canvas,
      center,
      rExercise,
      stroke,
      exerciseProgress,
      IosColors.activityGreen,
    );
    _drawRing(
      canvas,
      center,
      rStand,
      stroke,
      standProgress,
      IosColors.activityBlue,
    );

    if (showCenterTime) {
      final hour = time.hour.toString().padLeft(2, '0');
      final min = time.minute.toString().padLeft(2, '0');
      final textPainter = TextPainter(
        text: TextSpan(
          text: '$hour:$min',
          style: TextStyle(
            color: Colors.white,
            fontSize: size.width * 0.22,
            fontWeight: FontWeight.w800,
            letterSpacing: -1,
          ),
        ),
        textDirection: TextDirection.ltr,
      );
      textPainter.layout();
      textPainter.paint(
        canvas,
        Offset(
          center.dx - textPainter.width / 2,
          center.dy - textPainter.height / 2,
        ),
      );
    }
  }

  void _drawRing(
    Canvas canvas,
    Offset center,
    double radius,
    double stroke,
    double progress,
    Color color,
  ) {
    // Track background
    final bgPaint = Paint()
      ..color = color.withValues(alpha: 0.2)
      ..style = PaintingStyle.stroke
      ..strokeWidth = stroke;
    canvas.drawCircle(center, radius, bgPaint);

    // Active sweep arc
    final arcPaint = Paint()
      ..color = color
      ..style = PaintingStyle.stroke
      ..strokeWidth = stroke
      ..strokeCap = StrokeCap.round;

    final sweepAngle = (progress.clamp(0.0, 1.5)) * 2 * math.pi;
    canvas.drawArc(
      Rect.fromCircle(center: center, radius: radius),
      -math.pi / 2,
      sweepAngle,
      false,
      arcPaint,
    );
  }

  @override
  bool shouldRepaint(covariant ActivityRingsPainter oldDelegate) => true;
}
