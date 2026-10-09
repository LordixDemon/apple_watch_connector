import 'package:flutter/cupertino.dart';
import '../../services/native_pigment_layout.dart';

/// Original section title and selected option subtitle share the first baseline.
class NativeFacePigmentHeader extends StatelessWidget {
  final NativePigmentRowLayout layout;
  final String title, subtitle;
  final bool expanded;
  const NativeFacePigmentHeader({
    super.key,
    required this.layout,
    required this.title,
    required this.subtitle,
    required this.expanded,
  });

  @override
  Widget build(BuildContext context) {
    final scaler = MediaQuery.textScalerOf(
      context,
    ).clamp(maxScaleFactor: layout.headerMaxFontSize / layout.headerFontSize);
    final scale = scaler.scale(layout.headerFontSize) / layout.headerFontSize;
    final pixels = MediaQuery.devicePixelRatioOf(context);
    final lineHeight =
        (layout.headerLineHeight * scale * pixels).ceil() / pixels;
    final style = TextStyle(
      fontSize: layout.headerFontSize,
      height: layout.headerLineHeight / layout.headerFontSize,
      fontWeight: FontWeight.normal,
      color: CupertinoColors.label.resolveFrom(context),
    );
    return Padding(
      padding: EdgeInsets.symmetric(
        horizontal: layout.horizontalInset(expanded),
      ),
      child: Padding(
        padding: EdgeInsets.only(top: layout.headerTopInset * scale),
        child: SizedBox(
          height: lineHeight,
          child: LayoutBuilder(
            builder: (context, constraints) {
              final painter = TextPainter(
                text: TextSpan(text: title, style: style),
                textDirection: Directionality.of(context),
                textScaler: scaler,
                maxLines: 1,
              )..layout(maxWidth: constraints.maxWidth);
              final titleWidth = painter.width;
              painter.dispose();
              final gap = layout.headerLabelGap.clamp(
                0.0,
                (constraints.maxWidth - titleWidth).clamp(0.0, double.infinity),
              );
              return Row(
                crossAxisAlignment: CrossAxisAlignment.baseline,
                textBaseline: TextBaseline.alphabetic,
                children: [
                  SizedBox(
                    width: titleWidth,
                    child: Text(
                      title,
                      style: style,
                      textScaler: scaler,
                      maxLines: 1,
                      overflow: TextOverflow.ellipsis,
                    ),
                  ),
                  SizedBox(width: gap),
                  Expanded(
                    child: Text(
                      subtitle,
                      style: style.copyWith(
                        color: CupertinoColors.secondaryLabel.resolveFrom(
                          context,
                        ),
                      ),
                      textScaler: scaler,
                      textAlign: TextAlign.end,
                      maxLines: 1,
                      overflow: TextOverflow.ellipsis,
                    ),
                  ),
                ],
              );
            },
          ),
        ),
      ),
    );
  }
}
