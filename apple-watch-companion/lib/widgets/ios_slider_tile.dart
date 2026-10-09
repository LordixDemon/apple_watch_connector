import 'package:flutter/cupertino.dart';
import 'package:flutter/material.dart';
import '../theme/ios_colors.dart';

class IosSliderTile extends StatelessWidget {
  final IconData? leftIcon;
  final IconData? rightIcon;
  final double value;
  final ValueChanged<double> onChanged;
  final Color activeColor;

  const IosSliderTile({
    super.key,
    this.leftIcon,
    this.rightIcon,
    required this.value,
    required this.onChanged,
    this.activeColor = IosColors.systemOrange,
  });

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 8),
      child: Row(
        children: [
          if (leftIcon != null) ...[
            Icon(leftIcon, color: IosColors.secondaryLabel, size: 18),
            const SizedBox(width: 12),
          ],
          Expanded(
            child: CupertinoSlider(
              value: value,
              activeColor: activeColor,
              thumbColor: Colors.white,
              onChanged: onChanged,
            ),
          ),
          if (rightIcon != null) ...[
            const SizedBox(width: 12),
            Icon(rightIcon, color: IosColors.secondaryLabel, size: 24),
          ],
        ],
      ),
    );
  }
}
