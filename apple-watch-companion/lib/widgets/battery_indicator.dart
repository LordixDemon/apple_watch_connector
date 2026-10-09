import 'package:flutter/cupertino.dart';
import 'package:flutter/material.dart';
import '../theme/ios_colors.dart';

class BatteryIndicator extends StatelessWidget {
  final int batteryLevel;
  final bool isCharging;
  final double fontSize;
  final bool showPercentage;

  const BatteryIndicator({
    super.key,
    required this.batteryLevel,
    this.isCharging = false,
    this.fontSize = 14,
    this.showPercentage = true,
  });

  Color get _batteryColor {
    if (batteryLevel < 0) return IosColors.secondaryLabel;
    if (isCharging) return IosColors.systemGreen;
    if (batteryLevel <= 10) return IosColors.systemRed;
    if (batteryLevel <= 20) return IosColors.systemYellow;
    return IosColors.systemGreen;
  }

  @override
  Widget build(BuildContext context) {
    return Row(
      mainAxisSize: MainAxisSize.min,
      children: [
        if (showPercentage) ...[
          Text(
            batteryLevel < 0 ? '—' : '$batteryLevel%',
            style: TextStyle(
              color: IosColors.label,
              fontSize: fontSize,
              fontWeight: FontWeight.w600,
              letterSpacing: -0.2,
            ),
          ),
          const SizedBox(width: 4),
        ],
        Container(
          width: 24,
          height: 12,
          padding: const EdgeInsets.all(1.5),
          decoration: BoxDecoration(
            border: Border.all(
              color: IosColors.secondaryLabel,
              width: 1.2,
            ),
            borderRadius: BorderRadius.circular(3.5),
          ),
          child: Row(
            children: [
              Expanded(
                child: ClipRRect(
                  borderRadius: BorderRadius.circular(1.5),
                  child: FractionallySizedBox(
                    alignment: Alignment.centerLeft,
                    widthFactor: batteryLevel < 0 ? 0 : (batteryLevel / 100).clamp(0.05, 1.0),
                    child: Container(
                      color: _batteryColor,
                    ),
                  ),
                ),
              ),
            ],
          ),
        ),
        Container(
          width: 1.5,
          height: 4,
          decoration: BoxDecoration(
            color: IosColors.secondaryLabel,
            borderRadius: const BorderRadius.only(
              topRight: Radius.circular(1),
              bottomRight: Radius.circular(1),
            ),
          ),
        ),
        if (isCharging) ...[
          const SizedBox(width: 3),
          const Icon(
            CupertinoIcons.bolt_fill,
            color: IosColors.systemGreen,
            size: 12,
          ),
        ],
      ],
    );
  }
}
