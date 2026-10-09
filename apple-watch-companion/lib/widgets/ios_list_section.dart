import 'package:flutter/cupertino.dart';
import 'package:flutter/material.dart';
import '../theme/ios_colors.dart';
import '../theme/companion_spacing.dart';

/// Grouped iOS list container with optional header and footer.
class IosListSection extends StatelessWidget {
  final String? header;
  final Widget? headerWidget;
  final String? footer;
  final List<Widget> children;
  final EdgeInsetsGeometry margin;

  const IosListSection({
    super.key,
    this.header,
    this.headerWidget,
    this.footer,
    required this.children,
    this.margin = CompanionSpacing.sectionMargin,
  }) : assert(header == null || headerWidget == null);

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: margin,
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          ?headerWidget,
          if (header != null)
            Padding(
              padding: const EdgeInsets.only(left: 12, right: 12, bottom: 6),
              child: Text(
                header!.toUpperCase(),
                style: const TextStyle(
                  color: IosColors.secondaryLabel,
                  fontSize: 13,
                  fontWeight: FontWeight.w500,
                  letterSpacing: -0.1,
                ),
              ),
            ),
          Container(
            decoration: BoxDecoration(
              color: IosColors.secondaryGroupedBackground,
              borderRadius: BorderRadius.circular(12),
            ),
            child: ClipRRect(
              borderRadius: BorderRadius.circular(12),
              child: Column(children: _buildSeparatedChildren()),
            ),
          ),
          if (footer != null && footer!.isNotEmpty)
            Padding(
              padding: const EdgeInsets.only(left: 12, top: 6, right: 12),
              child: Text(
                footer!,
                style: const TextStyle(
                  color: IosColors.secondaryLabel,
                  fontSize: 13,
                  letterSpacing: -0.1,
                ),
              ),
            ),
        ],
      ),
    );
  }

  List<Widget> _buildSeparatedChildren() {
    final list = <Widget>[];
    for (int i = 0; i < children.length; i++) {
      list.add(children[i]);
      if (i < children.length - 1) {
        list.add(
          const Padding(
            padding: EdgeInsets.only(left: 53),
            child: Divider(
              height: 0.5,
              thickness: 0.5,
              color: IosColors.separator,
            ),
          ),
        );
      }
    }
    return list;
  }
}
