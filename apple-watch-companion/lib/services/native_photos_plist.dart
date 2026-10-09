import 'dart:convert';
import 'dart:typed_data';
import 'package:xml/xml.dart';
import 'native_binary_plist.dart';

/// Photos resources use ordinary property lists, not NSKeyedArchiver graphs.
/// Keep opaque data/date values and reject unsupported values before any write.
abstract final class NativePhotosPlist {
  static const maxBytes = 1024 * 1024;
  static Object decode(Uint8List bytes) {
    if (bytes.isEmpty || bytes.length > maxBytes) {
      throw const FormatException('Invalid Photos manifest size.');
    }
    if (bytes.length >= 8 &&
        ascii.decode(bytes.sublist(0, 8), allowInvalid: true) == 'bplist00') {
      return NativeBinaryPlist.decode(bytes);
    }
    final root = XmlDocument.parse(utf8.decode(bytes)).rootElement;
    if (root.name.local != 'plist' || root.childElements.length != 1) {
      throw const FormatException('Invalid Photos property list.');
    }
    var nodes = 0;
    Object read(XmlElement element, int depth) {
      if (++nodes > 16384 || depth > 32) {
        throw const FormatException('Photos manifest complexity exceeded.');
      }
      final children = element.childElements.toList();
      switch (element.name.local) {
        case 'dict':
          if (children.length.isOdd) {
            throw const FormatException('Invalid property list dictionary.');
          }
          final result = <String, Object>{};
          for (var i = 0; i < children.length; i += 2) {
            final key = children[i];
            if (key.name.local != 'key' || result.containsKey(key.innerText)) {
              throw const FormatException('Invalid property list key.');
            }
            result[key.innerText] = read(children[i + 1], depth + 1);
          }
          return result;
        case 'array':
          return children.map((v) => read(v, depth + 1)).toList();
        case 'string':
          return element.innerText;
        case 'integer':
          return int.parse(element.innerText);
        case 'real':
          final value = double.parse(element.innerText);
          if (!value.isFinite) {
            throw const FormatException('Non-finite property list value.');
          }
          return value;
        case 'true':
          return true;
        case 'false':
          return false;
        case 'data':
          return base64.decode(element.innerText.replaceAll(RegExp(r'\s'), ''));
        case 'date':
          return DateTime.parse(element.innerText).toUtc();
        default:
          throw const FormatException(
            'Unsupported Photos property list value.',
          );
      }
    }

    return read(root.childElements.single, 0);
  }

  static Uint8List encode(Object value) {
    var nodes = 0;
    String escape(String v) => v
        .replaceAll('&', '&amp;')
        .replaceAll('<', '&lt;')
        .replaceAll('>', '&gt;');
    String write(Object v, int depth) {
      if (++nodes > 16384 || depth > 32) {
        throw const FormatException('Photos manifest complexity exceeded.');
      }
      if (v is Map<String, dynamic>) {
        return '<dict>${v.entries.map((e) => '<key>${escape(e.key)}</key>${write(e.value as Object, depth + 1)}').join()}</dict>';
      }
      if (v is Uint8List) return '<data>${base64.encode(v)}</data>';
      if (v is List) {
        return '<array>${v.map((e) => write(e as Object, depth + 1)).join()}</array>';
      }
      if (v is DateTime) {
        if (v.microsecond != 0 || v.millisecond != 0) {
          throw const FormatException(
            'Fractional Photos date metadata requires binary preservation.',
          );
        }
        return '<date>${v.toUtc().toIso8601String().substring(0, 19)}Z</date>';
      }
      if (v is String) return '<string>${escape(v)}</string>';
      if (v is bool) return v ? '<true/>' : '<false/>';
      if (v is int) return '<integer>$v</integer>';
      if (v is double && v.isFinite) return '<real>$v</real>';
      throw const FormatException('Unsupported Photos property list value.');
    }

    final bytes = Uint8List.fromList(
      utf8.encode(
        '<?xml version="1.0" encoding="UTF-8"?>'
        '<plist version="1.0">${write(value, 0)}</plist>',
      ),
    );
    if (bytes.length > maxBytes) {
      throw const FormatException('Photos manifest size exceeded.');
    }
    return bytes;
  }
}
