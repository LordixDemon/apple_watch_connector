import 'package:flutter/cupertino.dart';
import '../theme/ios_colors.dart';
import '../theme/companion_spacing.dart';

/// Grouped iOS list tile matching iOS Settings / Watch.app style.
class IosListTile extends StatelessWidget {
  final String title;
  final String? subtitle;
  final Widget? leading;
  final IconData? icon;
  final Color? iconColor;
  final Color? iconBackgroundColor;
  final Widget? trailing;
  final String? trailingText;
  final VoidCallback? onTap;
  final bool showChevron;
  final bool isDestructive;
  final bool enabled;

  const IosListTile({
    super.key,
    required this.title,
    this.subtitle,
    this.leading,
    this.icon,
    this.iconColor = CupertinoColors.white,
    this.iconBackgroundColor = IosColors.systemBlue,
    this.trailing,
    this.trailingText,
    this.onTap,
    this.showChevron = true,
    this.isDestructive = false,
    this.enabled = true,
  });

  @override
  Widget build(BuildContext context) {
    final content = ConstrainedBox(
      constraints: const BoxConstraints(
        minHeight: CompanionSpacing.minimumTapExtent,
      ),
      child: Padding(
        padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 8),
        child: Row(
          children: [
            if (leading != null) ...[
              leading!,
              const SizedBox(width: 12),
            ] else if (icon != null) ...[
              Container(
                width: 29,
                height: 29,
                decoration: BoxDecoration(
                  color: iconBackgroundColor,
                  borderRadius: BorderRadius.circular(7),
                ),
                child: Icon(icon, color: iconColor, size: 18),
              ),
              const SizedBox(width: 12),
            ],
            Expanded(child: _labels(context)),
            if (trailing != null) ...[
              const SizedBox(width: 8),
              trailing!,
            ] else if (enabled && showChevron && onTap != null) ...[
              const SizedBox(width: 8),
              const Icon(
                CupertinoIcons.chevron_forward,
                color: IosColors.systemGray2,
                size: 16,
              ),
            ],
          ],
        ),
      ),
    );
    // Read-only rows need no ink controller, gesture recognizer or Material
    // subtree. Interactive rows use the same Cupertino press/focus behavior
    // as the surrounding app instead of nesting a second component system.
    if (!enabled) return Semantics(enabled: false, child: content);
    if (onTap == null) return content;
    return CupertinoButton(
      onPressed: onTap,
      padding: EdgeInsets.zero,
      minimumSize: const Size.square(CompanionSpacing.minimumTapExtent),
      child: content,
    );
  }

  Widget _labels(BuildContext context) {
    final titleStyle = TextStyle(
      color: !enabled
          ? IosColors.tertiaryLabel
          : isDestructive
          ? IosColors.systemRed
          : IosColors.label,
      fontSize: 16,
      fontWeight: FontWeight.w400,
      letterSpacing: -0.3,
    );
    final valueStyle = TextStyle(
      color: enabled ? IosColors.secondaryLabel : IosColors.tertiaryLabel,
      fontSize: 16,
      letterSpacing: -0.3,
    );
    Widget labels({bool valueBelow = false}) => Column(
      mainAxisSize: MainAxisSize.min,
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Text(title, style: titleStyle),
        if (subtitle != null) ...[
          const SizedBox(height: 2),
          Text(
            subtitle!,
            style: const TextStyle(
              color: IosColors.secondaryLabel,
              fontSize: 13,
              letterSpacing: -0.1,
            ),
          ),
        ],
        if (valueBelow) ...[
          const SizedBox(height: 2),
          Text(trailingText!, style: valueStyle),
        ],
      ],
    );
    if (trailingText == null) return labels();
    return LayoutBuilder(
      builder: (context, constraints) {
        double textWidth(String value, TextStyle style) {
          final painter = TextPainter(
            text: TextSpan(
              text: value,
              style: DefaultTextStyle.of(context).style.merge(style),
            ),
            textDirection: Directionality.of(context),
            textScaler: MediaQuery.textScalerOf(context),
          )..layout();
          final width = painter.width;
          painter.dispose();
          return width;
        }

        // Reflow the value rather than squeezing the title or clipping either.
        final fits =
            textWidth(title, titleStyle) +
                8 +
                textWidth(trailingText!, valueStyle) <=
            constraints.maxWidth;
        if (!fits) return labels(valueBelow: true);
        return Row(
          children: [
            Expanded(child: labels()),
            const SizedBox(width: 8),
            Text(trailingText!, style: valueStyle),
          ],
        );
      },
    );
  }
}
