import 'dart:convert';
import 'dart:ffi';
import 'dart:io';
import 'package:ffi/ffi.dart';

abstract interface class CoreApi {
  Map<String, dynamic> command(Map<String, dynamic> request);
}

/// A bundled versioned Rust ABI. Native allocations never escape this adapter.
final class RustCoreApi implements CoreApi {
  final Pointer<Utf8> Function(Pointer<Uint8>, int) _command;
  final void Function(Pointer<Utf8>) _free;
  RustCoreApi._(this._command, this._free);

  factory RustCoreApi.open() {
    final executable = File(Platform.resolvedExecutable).parent;
    final path = Platform.isMacOS
        ? '${executable.parent.path}/Frameworks/libwatch_core_ffi.dylib'
        : Platform.isLinux
        ? '${executable.path}/lib/libwatch_core_ffi.so'
        : Platform.isWindows
        ? '${executable.path}/watch_core_ffi.dll'
        : throw UnsupportedError('Rust core is unavailable on this platform');
    final library = DynamicLibrary.open(path);
    return RustCoreApi._(
      library.lookupFunction<
        Pointer<Utf8> Function(Pointer<Uint8>, Size),
        Pointer<Utf8> Function(Pointer<Uint8>, int)
      >('aw_core_command'),
      library.lookupFunction<
        Void Function(Pointer<Utf8>),
        void Function(Pointer<Utf8>)
      >('aw_core_free'),
    );
  }
  @override
  Map<String, dynamic> command(Map<String, dynamic> request) {
    final bytes = utf8.encode(jsonEncode(request));
    final input = calloc<Uint8>(bytes.length);
    Pointer<Utf8> output = nullptr;
    try {
      input.asTypedList(bytes.length).setAll(0, bytes);
      output = _command(input, bytes.length);
      if (output == nullptr) throw StateError('Rust core returned no response');
      return Map<String, dynamic>.from(
        jsonDecode(output.toDartString()) as Map,
      );
    } finally {
      if (output != nullptr) _free(output);
      calloc.free(input);
    }
  }
}
