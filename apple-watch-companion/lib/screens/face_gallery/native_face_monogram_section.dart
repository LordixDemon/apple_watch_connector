import 'package:flutter/cupertino.dart';
import '../../l10n/strings.dart';
import '../../services/native_face_monogram.dart';
import '../../widgets/ios_list_section.dart';
import '../../widgets/ios_list_tile.dart';

/// Shares native monogram presentation between installed and gallery drafts.
class NativeFaceMonogramSection extends StatelessWidget {
  final NativeFaceMonogram monogram;
  final Map<String, dynamic> configuration;
  final ValueChanged<bool>? onChanged;
  final VoidCallback? onEdit;
  final String? text, status;
  final bool hasTextEditor;
  const NativeFaceMonogramSection({
    super.key,
    required this.monogram,
    required this.configuration,
    this.onChanged,
    this.onEdit,
    this.text,
    this.status,
    this.hasTextEditor = false,
  });

  @override
  Widget build(BuildContext context) {
    final value = monogram.value(configuration);
    return IosListSection(
      footer: value == true ? status : null,
      children: [
        IosListTile(
          title: Strings.current.nativeFaceMonogram,
          showChevron: false,
          trailing: Semantics(
            label: Strings.current.nativeFaceMonogram,
            child: CupertinoSwitch(
              value: value ?? false,
              onChanged: value == null ? null : onChanged,
            ),
          ),
        ),
        if (value == true && hasTextEditor)
          IosListTile(
            title: Strings.current.nativeFaceMonogram,
            trailingText: text,
            enabled: onEdit != null,
            onTap: onEdit,
          ),
      ],
    );
  }
}
