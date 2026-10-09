import 'package:flutter/cupertino.dart';
import 'package:flutter/material.dart';
import '../models/watch_face.dart';
import '../theme/ios_colors.dart';
import 'watch_face_view.dart';

/// Ultra-realistic Apple Watch Ultra 2 hardware rendering card with titanium case,
/// orange action button accent, crown guard, and interactive face.
class WatchRenderCard extends StatelessWidget {
  final WatchFace? activeFace;
  final double scale;
  final VoidCallback? onTap;

  const WatchRenderCard({
    super.key,
    required this.activeFace,
    this.scale = 1.0,
    this.onTap,
  });

  @override
  Widget build(BuildContext context) {
    final width = 170.0 * scale;
    final height = 210.0 * scale;

    return GestureDetector(
      onTap: onTap,
      child: Center(
        child: SizedBox(
          width:
              width + 28 * scale, // include Digital Crown / button protrusion
          height: height + 60 * scale, // include strap connectors
          child: Stack(
            alignment: Alignment.center,
            children: [
              // --- Top & Bottom Ocean / Alpine Orange Straps ---
              Positioned(
                top: 0,
                child: Container(
                  width: width * 0.70,
                  height: 38 * scale,
                  decoration: BoxDecoration(
                    color: const Color(0xFFFF5E00), // Orange Ultra Alpine Loop
                    borderRadius: BorderRadius.vertical(
                      top: Radius.circular(8 * scale),
                    ),
                    boxShadow: [
                      BoxShadow(
                        color: Colors.black.withOpacity(0.4),
                        blurRadius: 8,
                        offset: const Offset(0, -2),
                      ),
                    ],
                  ),
                ),
              ),
              Positioned(
                bottom: 0,
                child: Container(
                  width: width * 0.70,
                  height: 38 * scale,
                  decoration: BoxDecoration(
                    color: const Color(0xFFFF5E00),
                    borderRadius: BorderRadius.vertical(
                      bottom: Radius.circular(8 * scale),
                    ),
                    boxShadow: [
                      BoxShadow(
                        color: Colors.black.withOpacity(0.4),
                        blurRadius: 8,
                        offset: const Offset(0, 4),
                      ),
                    ],
                  ),
                ),
              ),

              // --- Titanium Case Body ---
              Container(
                width: width,
                height: height,
                decoration: BoxDecoration(
                  gradient: const LinearGradient(
                    colors: [
                      Color(0xFFB5B3AE), // Titanium highlight
                      Color(0xFF8C8A85), // Titanium natural base
                      Color(0xFF6B6965), // Titanium shadow
                    ],
                    begin: Alignment.topLeft,
                    end: Alignment.bottomRight,
                  ),
                  borderRadius: BorderRadius.circular(width * 0.28),
                  boxShadow: [
                    BoxShadow(
                      color: Colors.black.withOpacity(0.7),
                      blurRadius: 24 * scale,
                      spreadRadius: 4 * scale,
                      offset: const Offset(0, 8),
                    ),
                  ],
                ),
                padding: EdgeInsets.all(5.0 * scale),
                child: Container(
                  decoration: BoxDecoration(
                    color: Colors.black,
                    borderRadius: BorderRadius.circular(width * 0.24),
                    border: Border.all(
                      color: const Color(0xFF2C2C2E),
                      width: 1.5 * scale,
                    ),
                  ),
                  child: ClipRRect(
                    borderRadius: BorderRadius.circular(width * 0.24),
                    child: activeFace == null
                        ? const Center(
                            child: Icon(
                              CupertinoIcons.clock,
                              color: IosColors.secondaryLabel,
                            ),
                          )
                        : WatchFaceView(
                            face: activeFace!,
                            size: width - 14 * scale,
                            showLiveTime: true,
                          ),
                  ),
                ),
              ),

              // --- Digital Crown & Crown Guard (Right side) ---
              Positioned(
                right: 0,
                top: height * 0.40,
                child: Column(
                  children: [
                    // Digital Crown with Orange Ring
                    Container(
                      width: 10 * scale,
                      height: 32 * scale,
                      decoration: BoxDecoration(
                        gradient: const LinearGradient(
                          colors: [Color(0xFF9E9C98), Color(0xFF6E6C68)],
                        ),
                        borderRadius: BorderRadius.horizontal(
                          right: Radius.circular(5 * scale),
                        ),
                        border: Border(
                          right: BorderSide(
                            color: IosColors.ultraOrange,
                            width: 2.0 * scale,
                          ),
                        ),
                      ),
                    ),
                    SizedBox(height: 10 * scale),
                    // Side Button
                    Container(
                      width: 6 * scale,
                      height: 22 * scale,
                      decoration: BoxDecoration(
                        color: const Color(0xFF7E7C78),
                        borderRadius: BorderRadius.horizontal(
                          right: Radius.circular(3 * scale),
                        ),
                      ),
                    ),
                  ],
                ),
              ),

              // --- Orange Action Button (Left side) ---
              Positioned(
                left: 2 * scale,
                top: height * 0.48,
                child: Container(
                  width: 8 * scale,
                  height: 36 * scale,
                  decoration: BoxDecoration(
                    color: IosColors.actionButtonOrange,
                    borderRadius: BorderRadius.horizontal(
                      left: Radius.circular(4 * scale),
                    ),
                    boxShadow: [
                      BoxShadow(
                        color: IosColors.actionButtonOrange.withOpacity(0.5),
                        blurRadius: 4,
                      ),
                    ],
                  ),
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }
}
