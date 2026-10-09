import 'dart:typed_data';
import 'package:flutter_test/flutter_test.dart';
import 'package:apple_watch_companion/services/optical/optical_frame.dart';

void main() {
  test('planar padded YUV becomes interleaved UV without luma or padding', () {
    final u = Uint8List.fromList([10, 11, 99, 12, 13]);
    final v = Uint8List.fromList([20, 21, 88, 22, 23]);
    final frame = OpticalFrame.fromYuv420(
      4,
      4,
      OpticalPlane(u, 3, 1),
      OpticalPlane(v, 3, 1),
    );
    expect(frame.width, 2);
    expect(frame.height, 2);
    expect(frame.uv, [10, 20, 11, 21, 12, 22, 13, 23]);
    u[0] = 0;
    expect(frame.uv[0], 10);
  });

  test('aliased Android NV21 planes respect pixel strides and UV order', () {
    final shared = Uint8List.fromList([20, 10, 21, 11, 99, 99, 22, 12, 23, 13]);
    final frame = OpticalFrame.fromYuv420(
      4,
      4,
      OpticalPlane(Uint8List.sublistView(shared, 1), 6, 2),
      OpticalPlane(shared, 6, 2),
    );
    expect(frame.uv, [10, 20, 11, 21, 12, 22, 13, 23]);
  });

  test(
    'truncated and overlapping rows and unbounded dimensions fail closed',
    () {
      final bytes = Uint8List(4), valid = OpticalPlane(Uint8List(4), 2, 1);
      for (final invalid in [
        OpticalPlane(bytes, 1, 1),
        OpticalPlane(bytes, 2, 2),
        OpticalPlane(bytes, 2, 0),
        OpticalPlane(bytes, 100, 1),
      ]) {
        expect(
          () => OpticalFrame.fromYuv420(4, 4, invalid, valid),
          throwsFormatException,
        );
      }
      for (final dimensions in [(0, 4), (3, 4), (4, 3), (4096, 2160)]) {
        expect(
          () => OpticalFrame.fromYuv420(
            dimensions.$1,
            dimensions.$2,
            valid,
            valid,
          ),
          throwsFormatException,
        );
      }
    },
  );
}
