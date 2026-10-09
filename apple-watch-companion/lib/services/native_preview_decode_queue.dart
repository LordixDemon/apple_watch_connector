import 'dart:async';
import 'dart:collection';

/// Shared by indexed and component previews. Includes the platform codec step,
/// so only two full pixel buffers can be in flight across both formats.
final class NativePreviewDecodeQueue {
  static final shared = NativePreviewDecodeQueue();
  int _active = 0;
  int peak = 0;
  final _pending = Queue<void Function()>();
  int get active => _active;
  int get pending => _pending.length;

  Future<T> run<T>(Future<T> Function() task) {
    if (_pending.length >= 256) {
      return Future.error(
        const FormatException('Native preview queue exceeded.'),
      );
    }
    final result = Completer<T>();
    void start() {
      _active++;
      Future.sync(
        task,
      ).then(result.complete, onError: result.completeError).whenComplete(() {
        _active--;
        if (_pending.isNotEmpty) _pending.removeFirst()();
      });
    }

    if (_active < 2) {
      start();
    } else {
      _pending.add(start);
      if (_pending.length > peak) peak = _pending.length;
    }
    return result.future;
  }
}
