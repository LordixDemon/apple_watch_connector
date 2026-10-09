import 'face_rendering/solar_dial_painter.dart';
import 'face_rendering/infograph_painter.dart';
import 'face_rendering/chronograph_painter.dart';
import 'face_rendering/california_painter.dart';
import 'face_rendering/activity_rings_painter.dart';
import 'face_rendering/hands_painter.dart';
import 'face_rendering/wayfinder_painter.dart';
import '../l10n/strings.dart';
import 'package:flutter/cupertino.dart';
import 'package:flutter/material.dart';
import 'package:flutter/scheduler.dart';
import '../models/watch_face.dart';
import '../theme/ios_colors.dart';

/// Authentic 60fps vector-rendered Apple Watch face view with live animations,
/// smooth sweeping hands, dynamic solar path, animated activity rings, and Time Travel scrub.
class WatchFaceView extends StatefulWidget {
  final WatchFace face;
  final double size;
  final bool showLiveTime;
  final DateTime? displayTime;
  final double?
  scrubSeconds; // For interactive Digital Crown / Time Travel scrub
  final VoidCallback? onTap;

  const WatchFaceView({
    super.key,
    required this.face,
    this.size = 180,
    this.showLiveTime = false,
    this.displayTime,
    this.scrubSeconds,
    this.onTap,
  });

  @override
  State<WatchFaceView> createState() => _WatchFaceViewState();
}

class _WatchFaceViewState extends State<WatchFaceView>
    with SingleTickerProviderStateMixin {
  late Ticker _ticker;
  DateTime _currentTime = DateTime.now();

  @override
  void initState() {
    super.initState();
    _currentTime = widget.displayTime ?? DateTime.now();
    _ticker = createTicker((_) {
      if (widget.showLiveTime && mounted) {
        setState(() {
          _currentTime = DateTime.now();
        });
      }
    });

    if (widget.showLiveTime) {
      _ticker.start();
    }
  }

  @override
  void didUpdateWidget(WatchFaceView oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (widget.showLiveTime != oldWidget.showLiveTime) {
      if (widget.showLiveTime) {
        _ticker.start();
      } else {
        _ticker.stop();
      }
    }
    if (widget.displayTime != null) {
      _currentTime = widget.displayTime!;
    }
  }

  @override
  void dispose() {
    _ticker.dispose();
    super.dispose();
  }

  DateTime get effectiveTime {
    var base =
        widget.displayTime ??
        (widget.showLiveTime ? _currentTime : DateTime.now());
    if (widget.scrubSeconds != null) {
      base = base.add(
        Duration(milliseconds: (widget.scrubSeconds! * 1000).toInt()),
      );
    }
    return base;
  }

  @override
  Widget build(BuildContext context) {
    final size = widget.size;
    final face = widget.face;
    final time = effectiveTime;

    return GestureDetector(
      onTap: widget.onTap,
      child: Container(
        width: size,
        height: size * 1.22, // 49mm Ultra aspect ratio (410x502)
        decoration: BoxDecoration(
          color: Colors.black,
          borderRadius: BorderRadius.circular(size * 0.22),
          border: Border.all(
            color: face.nightMode
                ? const Color(0x66FF2D55)
                : const Color(0xFF1E1E22),
            width: 2.0,
          ),
          boxShadow: [
            BoxShadow(
              color: face.nightMode
                  ? const Color(0x33FF2D55)
                  : Colors.black.withValues(alpha: 0.5),
              blurRadius: 18,
              spreadRadius: 2,
              offset: const Offset(0, 4),
            ),
          ],
        ),
        child: ClipRRect(
          borderRadius: BorderRadius.circular(size * 0.22),
          child: Stack(
            alignment: Alignment.center,
            children: [
              // 1. Dial Background / Family Specific Painter
              _buildFaceDial(time),

              // 2. Complications Overlay
              _buildComplications(),

              // 3. Hands (if analog)
              if (_isAnalogFamily(face.family)) _buildAnalogHands(time),

              // 4. Subtle glass glare sheen
              IgnorePointer(
                child: Container(
                  decoration: BoxDecoration(
                    gradient: LinearGradient(
                      begin: Alignment.topLeft,
                      end: Alignment.bottomRight,
                      colors: [
                        Colors.white.withValues(alpha: 0.05),
                        Colors.transparent,
                        Colors.black.withValues(alpha: 0.15),
                      ],
                    ),
                  ),
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }

  bool _isAnalogFamily(WatchFaceFamily family) {
    return family == WatchFaceFamily.wayfinder ||
        family == WatchFaceFamily.california ||
        family == WatchFaceFamily.chronographPro ||
        family == WatchFaceFamily.infograph ||
        family == WatchFaceFamily.solarDial ||
        family == WatchFaceFamily.activityAnalog;
  }

  Widget _buildFaceDial(DateTime time) {
    final face = widget.face;
    final size = widget.size;

    switch (face.family) {
      case WatchFaceFamily.wayfinder:
        return CustomPaint(
          size: Size(size, size * 1.22),
          painter: WayfinderPainter(
            color: face.primaryColor,
            nightMode: face.nightMode,
            bezelStyle: face.bezelStyle,
            time: time,
          ),
        );
      case WatchFaceFamily.ultraModular:
        return _buildUltraModularDial(time);
      case WatchFaceFamily.modular:
        return _buildModularDial(time);
      case WatchFaceFamily.california:
        return CustomPaint(
          size: Size(size, size * 1.22),
          painter: CaliforniaPainter(
            primaryColor: face.primaryColor,
            nightMode: face.nightMode,
          ),
        );
      case WatchFaceFamily.chronographPro:
        return CustomPaint(
          size: Size(size, size * 1.22),
          painter: ChronographPainter(
            primaryColor: face.primaryColor,
            time: time,
          ),
        );
      case WatchFaceFamily.infograph:
        return CustomPaint(
          size: Size(size, size * 1.22),
          painter: InfographPainter(
            primaryColor: face.primaryColor,
            nightMode: face.nightMode,
          ),
        );
      case WatchFaceFamily.solarDial:
        return CustomPaint(
          size: Size(size, size * 1.22),
          painter: SolarDialPainter(
            primaryColor: face.primaryColor,
            time: time,
          ),
        );
      case WatchFaceFamily.activityAnalog:
      case WatchFaceFamily.activityDigital:
        return _buildActivityDial(time);
      case WatchFaceFamily.nike:
        return _buildNikeDial(time);
    }
  }

  // --- ULTRA MODULAR ---
  Widget _buildUltraModularDial(DateTime time) {
    final size = widget.size;
    final face = widget.face;
    final hour = time.hour.toString().padLeft(2, '0');
    final min = time.minute.toString().padLeft(2, '0');
    final sec = time.second.toString().padLeft(2, '0');

    final textColor = face.nightMode
        ? const Color(0xFFFF2D55)
        : face.primaryColor;

    return Padding(
      padding: EdgeInsets.all(size * 0.08),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          Row(
            mainAxisAlignment: MainAxisAlignment.spaceBetween,
            children: [
              Flexible(
                child: Text(
                  face.bezelStyle == BezelStyle.incline
                      ? Strings.current.incline42
                      : Strings.current.north124,
                  maxLines: 1,
                  overflow: TextOverflow.ellipsis,
                  style: TextStyle(
                    color: textColor,
                    fontSize: size * 0.05,
                    fontWeight: FontWeight.w700,
                    letterSpacing: 0.5,
                  ),
                ),
              ),
              const SizedBox(width: 4),
              Text(
                Strings.current.message182M,
                style: TextStyle(
                  color: face.nightMode
                      ? const Color(0xAAFF2D55)
                      : IosColors.secondaryLabel,
                  fontSize: size * 0.05,
                  fontWeight: FontWeight.w600,
                ),
              ),
            ],
          ),
          const Spacer(),
          Center(
            child: FittedBox(
              fit: BoxFit.scaleDown,
              child: Row(
                mainAxisAlignment: MainAxisAlignment.center,
                crossAxisAlignment: CrossAxisAlignment.baseline,
                textBaseline: TextBaseline.alphabetic,
                children: [
                  Text(
                    '$hour:$min',
                    style: TextStyle(
                      color: textColor,
                      fontSize: size * 0.26,
                      fontWeight: FontWeight.w800,
                      letterSpacing: -1.5,
                    ),
                  ),
                  SizedBox(width: size * 0.02),
                  Text(
                    sec,
                    style: TextStyle(
                      color: face.nightMode
                          ? const Color(0xAAFF2D55)
                          : IosColors.secondaryLabel,
                      fontSize: size * 0.11,
                      fontWeight: FontWeight.w700,
                    ),
                  ),
                ],
              ),
            ),
          ),
          const Spacer(),
          Container(
            height: size * 0.22,
            padding: EdgeInsets.symmetric(horizontal: size * 0.04),
            decoration: BoxDecoration(
              color: face.nightMode
                  ? const Color(0x22FF2D55)
                  : const Color(0xFF1C1C1E),
              borderRadius: BorderRadius.circular(size * 0.06),
              border: face.nightMode
                  ? Border.all(color: const Color(0x44FF2D55))
                  : null,
            ),
            child: Row(
              children: [
                Icon(
                  CupertinoIcons.flame_fill,
                  color: face.nightMode
                      ? const Color(0xFFFF2D55)
                      : IosColors.activityRed,
                  size: size * 0.09,
                ),
                SizedBox(width: size * 0.02),
                Expanded(
                  child: Column(
                    mainAxisAlignment: MainAxisAlignment.center,
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text(
                        Strings.current.activity2,
                        style: TextStyle(
                          color: face.nightMode
                              ? const Color(0xFFFF2D55)
                              : IosColors.activityRed,
                          fontSize: size * 0.045,
                          fontWeight: FontWeight.w700,
                        ),
                      ),
                      Text(
                        Strings.current.message620800Kcal,
                        style: TextStyle(
                          color: face.nightMode
                              ? Colors.white
                              : IosColors.label,
                          fontSize: size * 0.055,
                          fontWeight: FontWeight.w600,
                        ),
                      ),
                    ],
                  ),
                ),
              ],
            ),
          ),
        ],
      ),
    );
  }

  // --- MODULAR ---
  Widget _buildModularDial(DateTime time) {
    final size = widget.size;
    final face = widget.face;
    final hour = time.hour.toString().padLeft(2, '0');
    final min = time.minute.toString().padLeft(2, '0');

    return Padding(
      padding: EdgeInsets.all(size * 0.08),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          Row(
            mainAxisAlignment: MainAxisAlignment.spaceBetween,
            children: [
              Flexible(
                child: Text(
                  Strings.current.today,
                  overflow: TextOverflow.ellipsis,
                  maxLines: 1,
                  style: TextStyle(
                    color: face.primaryColor,
                    fontSize: size * 0.065,
                    fontWeight: FontWeight.w700,
                  ),
                ),
              ),
              const SizedBox(width: 4),
              Text(
                '$hour:$min',
                style: TextStyle(
                  color: IosColors.label,
                  fontSize: size * 0.13,
                  fontWeight: FontWeight.w800,
                ),
              ),
            ],
          ),
          SizedBox(height: size * 0.03),
          Container(
            padding: EdgeInsets.all(size * 0.035),
            decoration: BoxDecoration(
              color: const Color(0xFF1C1C1E),
              borderRadius: BorderRadius.circular(size * 0.05),
            ),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  Strings.current.message1930Meeting,
                  maxLines: 1,
                  overflow: TextOverflow.ellipsis,
                  style: TextStyle(
                    color: face.primaryColor,
                    fontSize: size * 0.055,
                    fontWeight: FontWeight.w700,
                  ),
                ),
                Text(
                  Strings.current.watchSync,
                  maxLines: 1,
                  overflow: TextOverflow.ellipsis,
                  style: TextStyle(
                    color: IosColors.secondaryLabel,
                    fontSize: size * 0.05,
                  ),
                ),
              ],
            ),
          ),
          SizedBox(height: size * 0.03),
          Container(
            padding: EdgeInsets.all(size * 0.035),
            decoration: BoxDecoration(
              color: const Color(0xFF1C1C1E),
              borderRadius: BorderRadius.circular(size * 0.05),
            ),
            child: Row(
              children: [
                Icon(
                  CupertinoIcons.heart_fill,
                  color: IosColors.systemRed,
                  size: size * 0.08,
                ),
                SizedBox(width: size * 0.02),
                Expanded(
                  child: Text(
                    Strings.current.message72Bpm5Minago,
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                    style: TextStyle(
                      color: IosColors.label,
                      fontSize: size * 0.05,
                      fontWeight: FontWeight.w600,
                    ),
                  ),
                ),
              ],
            ),
          ),
        ],
      ),
    );
  }

  // --- ACTIVITY RINGS (Animated parametric canvas) ---
  Widget _buildActivityDial(DateTime time) {
    final size = widget.size;
    final isAnalog = widget.face.family == WatchFaceFamily.activityAnalog;

    return Center(
      child: CustomPaint(
        size: Size(size * 0.72, size * 0.72),
        painter: ActivityRingsPainter(
          moveProgress: 0.82,
          exerciseProgress: 0.65,
          standProgress: 0.90,
          showCenterTime: !isAnalog,
          time: time,
          primaryColor: widget.face.primaryColor,
        ),
      ),
    );
  }

  // --- NIKE BOUNCE ---
  Widget _buildNikeDial(DateTime time) {
    final size = widget.size;
    final face = widget.face;
    final hour = (time.hour % 12 == 0 ? 12 : time.hour % 12).toString().padLeft(
      2,
      '0',
    );
    final min = time.minute.toString().padLeft(2, '0');

    return Center(
      child: Column(
        mainAxisAlignment: MainAxisAlignment.center,
        children: [
          Text(
            hour,
            style: TextStyle(
              color: face.primaryColor,
              fontSize: size * 0.36,
              fontWeight: FontWeight.w900,
              height: 0.88,
              fontStyle: FontStyle.italic,
              letterSpacing: -2,
            ),
          ),
          Text(
            min,
            style: TextStyle(
              color: Colors.white,
              fontSize: size * 0.36,
              fontWeight: FontWeight.w900,
              height: 0.88,
              fontStyle: FontStyle.italic,
              letterSpacing: -2,
            ),
          ),
        ],
      ),
    );
  }

  // --- COMPLICATIONS OVERLAY ---
  Widget _buildComplications() {
    final face = widget.face;
    final size = widget.size;

    return Padding(
      padding: EdgeInsets.all(size * 0.07),
      child: Stack(
        children: [
          if (face.complications.containsKey(ComplicationSlot.topLeft))
            Align(
              alignment: Alignment.topLeft,
              child: _buildComplicationBadge(
                face.complications[ComplicationSlot.topLeft]!,
              ),
            ),
          if (face.complications.containsKey(ComplicationSlot.topRight))
            Align(
              alignment: Alignment.topRight,
              child: _buildComplicationBadge(
                face.complications[ComplicationSlot.topRight]!,
              ),
            ),
          if (face.complications.containsKey(ComplicationSlot.bottomLeft))
            Align(
              alignment: Alignment.bottomLeft,
              child: _buildComplicationBadge(
                face.complications[ComplicationSlot.bottomLeft]!,
              ),
            ),
          if (face.complications.containsKey(ComplicationSlot.bottomRight))
            Align(
              alignment: Alignment.bottomRight,
              child: _buildComplicationBadge(
                face.complications[ComplicationSlot.bottomRight]!,
              ),
            ),
        ],
      ),
    );
  }

  Widget _buildComplicationBadge(ComplicationItem item) {
    final size = widget.size;
    final face = widget.face;

    return Container(
      width: size * 0.17,
      height: size * 0.17,
      decoration: BoxDecoration(
        color: face.nightMode
            ? const Color(0x33FF2D55)
            : const Color(0x661C1C1E),
        shape: BoxShape.circle,
        border: Border.all(
          color: face.nightMode
              ? const Color(0x66FF2D55)
              : const Color(0x33FFFFFF),
          width: 0.8,
        ),
      ),
      child: Icon(
        item.icon,
        size: size * 0.08,
        color: face.nightMode ? const Color(0xFFFF2D55) : item.tintColor,
      ),
    );
  }

  // --- ANALOG HANDS WITH SMOOTH 60FPS SWEEP ---
  Widget _buildAnalogHands(DateTime time) {
    final size = widget.size;
    final face = widget.face;

    return CustomPaint(
      size: Size(size, size * 1.22),
      painter: HandsPainter(
        time: time,
        accentColor: face.nightMode
            ? const Color(0xFFFF2D55)
            : face.primaryColor,
        isNightMode: face.nightMode,
      ),
    );
  }
}

// ==========================================
// PAINTERS (Wayfinder, Hands, Rings, Solar, etc.)
// ==========================================
