import '../../l10n/strings.dart';
import 'package:flutter/cupertino.dart';
import 'package:flutter/material.dart';
import 'package:provider/provider.dart';
import '../../models/watch_face.dart';
import '../../providers/watch_provider.dart';
import '../../theme/ios_colors.dart';
import '../../widgets/watch_face_view.dart';
import '../../services/watch_face_files.dart';
import 'native_face_presentation.dart';

class FaceCustomizerScreen extends StatefulWidget {
  final WatchFace face;
  final bool isNewAddition;

  const FaceCustomizerScreen({
    super.key,
    required this.face,
    this.isNewAddition = true,
  });

  @override
  State<FaceCustomizerScreen> createState() => _FaceCustomizerScreenState();
}

class _FaceCustomizerScreenState extends State<FaceCustomizerScreen> {
  late WatchFace _editedFace;
  int _selectedColorIndex = 0;
  double _scrubOffsetSeconds = 0.0;
  bool _isScrubbing = false;

  final List<Color> _palette = const [
    IosColors.ultraOrange,
    Color(0xFFFF3B30), // Product RED
    Color(0xFFCCFF00), // Nike Volt
    Color(0xFF0A84FF), // Deep Blue
    Color(0xFF5E5CE6), // Indigo / Purple
    Color(0xFFE5E5EA), // Titanium White / Alpine
    Color(0xFFD4AF37), // Hermès Gold
    Color(0xFF30D158), // Clover Green
    Color(0xFFFF9F0A), // Sunset Amber
    Color(0xFF2C2C2E), // Space Black
  ];

  @override
  void initState() {
    super.initState();
    _editedFace = widget.face;
    _selectedColorIndex = _palette.indexWhere(
      (c) => c.value == widget.face.primaryColor.value,
    );
    if (_selectedColorIndex < 0) _selectedColorIndex = 0;
  }

  @override
  Widget build(BuildContext context) {
    final provider = context.read<WatchProvider>();

    return CupertinoPageScaffold(
      backgroundColor: IosColors.systemBackground,
      navigationBar: CupertinoNavigationBar(
        backgroundColor: const Color(0xCC121212),
        middle: Text(_editedFace.title),
        previousPageTitle: Strings.current.faceGallery,
        trailing: CupertinoButton(
          padding: EdgeInsets.zero,
          onPressed: () {
            provider.saveFaceDesign(_editedFace);
            Navigator.pop(context);
          },
          child: Text(
            Strings.current.save,
            style: const TextStyle(
              color: IosColors.systemOrange,
              fontWeight: FontWeight.w700,
              fontSize: 15,
            ),
          ),
        ),
      ),
      child: SafeArea(
        child: ListView(
          children: [
            const SizedBox(height: 16),

            // --- Live Watch Face Preview with 60fps & Scrubbing ---
            Center(
              child: Hero(
                tag: 'face_${widget.face.id}',
                child: WatchFaceView(
                  face: _editedFace,
                  size: 215,
                  showLiveTime: !_isScrubbing,
                  scrubSeconds: _scrubOffsetSeconds,
                ),
              ),
            ),
            const SizedBox(height: 20),

            // --- Interactive Time Travel / Digital Crown Scrubber ---
            Padding(
              padding: const EdgeInsets.symmetric(horizontal: 20),
              child: Container(
                padding: const EdgeInsets.all(14),
                decoration: BoxDecoration(
                  color: IosColors.secondaryGroupedBackground,
                  borderRadius: BorderRadius.circular(14),
                ),
                child: Column(
                  children: [
                    Row(
                      mainAxisAlignment: MainAxisAlignment.spaceBetween,
                      children: [
                        Row(
                          children: [
                            Icon(
                              CupertinoIcons.clock,
                              color: IosColors.systemOrange,
                              size: 16,
                            ),
                            SizedBox(width: 8),
                            Text(
                              Strings.current.digitalCrownTimeTravel,
                              style: TextStyle(
                                color: IosColors.secondaryLabel,
                                fontSize: 12,
                                fontWeight: FontWeight.w700,
                              ),
                            ),
                          ],
                        ),
                        if (_scrubOffsetSeconds != 0)
                          GestureDetector(
                            onTap: () {
                              setState(() {
                                _scrubOffsetSeconds = 0;
                                _isScrubbing = false;
                              });
                            },
                            child: Text(
                              Strings.current.reset,
                              style: TextStyle(
                                color: IosColors.systemOrange,
                                fontSize: 13,
                                fontWeight: FontWeight.w600,
                              ),
                            ),
                          ),
                      ],
                    ),
                    const SizedBox(height: 8),
                    CupertinoSlider(
                      value: _scrubOffsetSeconds,
                      min: -43200, // -12 hours
                      max: 43200, // +12 hours
                      activeColor: IosColors.systemOrange,
                      onChanged: (val) {
                        setState(() {
                          _isScrubbing = true;
                          _scrubOffsetSeconds = val;
                        });
                      },
                      onChangeEnd: (_) {
                        setState(() {
                          _isScrubbing = false;
                        });
                      },
                    ),
                  ],
                ),
              ),
            ),
            const SizedBox(height: 20),

            // --- Color Picker Palette ---
            Padding(
              padding: const EdgeInsets.symmetric(horizontal: 20),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    Strings.current.colorPalette,
                    style: TextStyle(
                      color: IosColors.secondaryLabel,
                      fontSize: 13,
                      fontWeight: FontWeight.w600,
                      letterSpacing: -0.1,
                    ),
                  ),
                  const SizedBox(height: 12),
                  SizedBox(
                    height: 50,
                    child: ListView.separated(
                      scrollDirection: Axis.horizontal,
                      itemCount: _palette.length,
                      separatorBuilder: (context, index) =>
                          const SizedBox(width: 14),
                      itemBuilder: (context, index) {
                        final color = _palette[index];
                        final isSelected = _selectedColorIndex == index;

                        return GestureDetector(
                          onTap: () {
                            setState(() {
                              _selectedColorIndex = index;
                              _editedFace = _editedFace.copyWith(
                                primaryColor: color,
                              );
                            });
                          },
                          child: Container(
                            width: 44,
                            height: 44,
                            decoration: BoxDecoration(
                              color: color,
                              shape: BoxShape.circle,
                              border: isSelected
                                  ? Border.all(color: Colors.white, width: 3.5)
                                  : null,
                              boxShadow: [
                                if (isSelected)
                                  BoxShadow(
                                    color: color.withValues(alpha: 0.6),
                                    blurRadius: 10,
                                    spreadRadius: 2,
                                  ),
                              ],
                            ),
                          ),
                        );
                      },
                    ),
                  ),
                ],
              ),
            ),

            // --- Night Mode Switch (Ultra exclusive) ---
            if (_editedFace.isUltraExclusive) ...[
              const SizedBox(height: 20),
              Padding(
                padding: const EdgeInsets.symmetric(horizontal: 20),
                child: Container(
                  padding: const EdgeInsets.symmetric(
                    horizontal: 16,
                    vertical: 12,
                  ),
                  decoration: BoxDecoration(
                    color: IosColors.secondaryGroupedBackground,
                    borderRadius: BorderRadius.circular(14),
                  ),
                  child: Row(
                    mainAxisAlignment: MainAxisAlignment.spaceBetween,
                    children: [
                      Row(
                        children: [
                          Icon(
                            CupertinoIcons.moon_fill,
                            color: Color(0xFFFF2D55),
                            size: 20,
                          ),
                          SizedBox(width: 12),
                          Text(
                            Strings.current.nightMode,
                            style: TextStyle(
                              color: IosColors.label,
                              fontSize: 16,
                              fontWeight: FontWeight.w600,
                            ),
                          ),
                        ],
                      ),
                      CupertinoSwitch(
                        value: _editedFace.nightMode,
                        activeTrackColor: const Color(0xFFFF2D55),
                        onChanged: (val) {
                          setState(() {
                            _editedFace = _editedFace.copyWith(nightMode: val);
                          });
                        },
                      ),
                    ],
                  ),
                ),
              ),
            ],

            if (_editedFace.family == WatchFaceFamily.wayfinder) ...[
              const SizedBox(height: 24),
              // --- Bezel Style Selector for Wayfinder ---
              Padding(
                padding: const EdgeInsets.symmetric(horizontal: 20),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(
                      Strings.current.bezelStyle,
                      style: TextStyle(
                        color: IosColors.secondaryLabel,
                        fontSize: 13,
                        fontWeight: FontWeight.w600,
                        letterSpacing: -0.1,
                      ),
                    ),
                    const SizedBox(height: 12),
                    Row(
                      children: [
                        _buildStyleButton(
                          Strings.current.elevationIncline,
                          BezelStyle.elevation,
                        ),
                        const SizedBox(width: 10),
                        _buildStyleButton(
                          Strings.current.seconds,
                          BezelStyle.seconds,
                        ),
                        const SizedBox(width: 10),
                        _buildStyleButton(
                          Strings.current.noBezel,
                          BezelStyle.none,
                        ),
                      ],
                    ),
                  ],
                ),
              ),
            ],

            const SizedBox(height: 24),
            // --- Interactive Complications Slots List ---
            Padding(
              padding: const EdgeInsets.symmetric(horizontal: 20),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    Strings.current.complicationsTapToChange,
                    style: TextStyle(
                      color: IosColors.secondaryLabel,
                      fontSize: 13,
                      fontWeight: FontWeight.w600,
                      letterSpacing: -0.1,
                    ),
                  ),
                  const SizedBox(height: 12),
                  Container(
                    decoration: BoxDecoration(
                      color: IosColors.secondaryGroupedBackground,
                      borderRadius: BorderRadius.circular(14),
                    ),
                    child: Column(
                      children: _editedFace.complications.entries.map((entry) {
                        return GestureDetector(
                          behavior: HitTestBehavior.opaque,
                          onTap: () => _pickComplicationForSlot(entry.key),
                          child: Padding(
                            padding: const EdgeInsets.symmetric(
                              horizontal: 16,
                              vertical: 12,
                            ),
                            child: Row(
                              children: [
                                Icon(
                                  entry.value.icon,
                                  color: IosColors.systemOrange,
                                  size: 20,
                                ),
                                const SizedBox(width: 12),
                                Expanded(
                                  child: Column(
                                    crossAxisAlignment:
                                        CrossAxisAlignment.start,
                                    children: [
                                      Text(
                                        _slotName(entry.key),
                                        style: const TextStyle(
                                          color: IosColors.label,
                                          fontSize: 16,
                                          fontWeight: FontWeight.w500,
                                        ),
                                      ),
                                      Text(
                                        entry.value.subtitle,
                                        style: const TextStyle(
                                          color: IosColors.secondaryLabel,
                                          fontSize: 13,
                                        ),
                                      ),
                                    ],
                                  ),
                                ),
                                Text(
                                  entry.value.title,
                                  style: const TextStyle(
                                    color: IosColors.systemOrange,
                                    fontSize: 14,
                                    fontWeight: FontWeight.w600,
                                  ),
                                ),
                                const SizedBox(width: 6),
                                const Icon(
                                  CupertinoIcons.chevron_right,
                                  color: IosColors.tertiaryLabel,
                                  size: 14,
                                ),
                              ],
                            ),
                          ),
                        );
                      }).toList(),
                    ),
                  ),
                ],
              ),
            ),

            const SizedBox(height: 28),
            // --- Action Buttons (Add / Export) ---
            Padding(
              padding: const EdgeInsets.symmetric(horizontal: 20),
              child: Column(
                children: [
                  CupertinoButton(
                    color: IosColors.systemOrange,
                    borderRadius: BorderRadius.circular(14),
                    onPressed: () {
                      provider.saveFaceDesign(_editedFace);
                      Navigator.pop(context);
                    },
                    child: Row(
                      mainAxisAlignment: MainAxisAlignment.center,
                      children: [
                        const Icon(CupertinoIcons.checkmark_alt, size: 18),
                        const SizedBox(width: 8),
                        Text(
                          Strings.current.saveLayoutOnPhone,
                          style: const TextStyle(
                            color: Colors.white,
                            fontSize: 16,
                            fontWeight: FontWeight.w700,
                          ),
                        ),
                      ],
                    ),
                  ),
                  const SizedBox(height: 12),
                  CupertinoButton(
                    color: IosColors.secondaryGroupedBackground,
                    borderRadius: BorderRadius.circular(14),
                    onPressed: () async {
                      final archiveBytes = provider.exportWatchFace(
                        _editedFace,
                      );
                      try {
                        final saved = await WatchFaceFiles().save(
                          archiveBytes,
                          'companion-layout.watchlayout',
                        );
                        if (context.mounted && saved) {
                          _showExportSuccessDialog(context);
                        }
                      } catch (error) {
                        if (context.mounted) {
                          await showFaceFileError(context, error);
                        }
                      }
                    },
                    child: Row(
                      mainAxisAlignment: MainAxisAlignment.center,
                      children: [
                        Icon(
                          CupertinoIcons.share,
                          color: IosColors.label,
                          size: 18,
                        ),
                        SizedBox(width: 8),
                        Text(
                          Strings.current.exportLocalFaceLayout,
                          style: TextStyle(
                            color: IosColors.label,
                            fontSize: 15,
                            fontWeight: FontWeight.w600,
                          ),
                        ),
                      ],
                    ),
                  ),
                ],
              ),
            ),
            const SizedBox(height: 32),
          ],
        ),
      ),
    );
  }

  Widget _buildStyleButton(String label, BezelStyle style) {
    final isSelected = _editedFace.bezelStyle == style;

    return Expanded(
      child: GestureDetector(
        onTap: () {
          setState(() {
            _editedFace = _editedFace.copyWith(bezelStyle: style);
          });
        },
        child: Container(
          padding: const EdgeInsets.symmetric(vertical: 10),
          decoration: BoxDecoration(
            color: isSelected
                ? IosColors.systemOrange
                : IosColors.secondaryGroupedBackground,
            borderRadius: BorderRadius.circular(10),
          ),
          child: Center(
            child: Text(
              label,
              style: TextStyle(
                color: isSelected ? Colors.white : IosColors.label,
                fontSize: 12,
                fontWeight: isSelected ? FontWeight.w700 : FontWeight.w500,
              ),
            ),
          ),
        ),
      ),
    );
  }

  void _pickComplicationForSlot(ComplicationSlot slot) {
    showCupertinoModalPopup<void>(
      context: context,
      builder: (ctx) => CupertinoActionSheet(
        title: Text(
          Strings.current.chooseAComplicationFor((_slotName(slot)).toString()),
        ),
        actions: ComplicationItem.allPresetItems.map((item) {
          return CupertinoActionSheetAction(
            onPressed: () {
              setState(() {
                final updated = Map<ComplicationSlot, ComplicationItem>.from(
                  _editedFace.complications,
                );
                updated[slot] = item;
                _editedFace = _editedFace.copyWith(complications: updated);
              });
              Navigator.pop(ctx);
            },
            child: Row(
              mainAxisAlignment: MainAxisAlignment.center,
              children: [
                Icon(item.icon, color: IosColors.systemOrange, size: 18),
                const SizedBox(width: 8),
                Text(item.title),
              ],
            ),
          );
        }).toList(),
        cancelButton: CupertinoActionSheetAction(
          isDestructiveAction: true,
          onPressed: () => Navigator.pop(ctx),
          child: Text(Strings.current.cancel),
        ),
      ),
    );
  }

  void _showExportSuccessDialog(BuildContext context) {
    showCupertinoDialog(
      context: context,
      builder: (ctx) => CupertinoAlertDialog(
        title: Text(Strings.current.localFaceLayoutSaved),
        content: Text(Strings.current.localFaceLayoutSavedHint),
        actions: [
          CupertinoDialogAction(
            isDefaultAction: true,
            child: Text(Strings.current.ok),
            onPressed: () => Navigator.pop(ctx),
          ),
        ],
      ),
    );
  }

  String _slotName(ComplicationSlot slot) {
    switch (slot) {
      case ComplicationSlot.topSubdial:
        return Strings.current.topSubdial;
      case ComplicationSlot.topLeft:
        return Strings.current.topLeft;
      case ComplicationSlot.topRight:
        return Strings.current.topRight;
      case ComplicationSlot.center:
        return Strings.current.center;
      case ComplicationSlot.bottomLeft:
        return Strings.current.bottomLeft;
      case ComplicationSlot.bottomRight:
        return Strings.current.bottomRight;
      case ComplicationSlot.bottomSubdial:
        return Strings.current.bottomSubdial;
      case ComplicationSlot.bezel:
        return Strings.current.bezel;
    }
  }
}
