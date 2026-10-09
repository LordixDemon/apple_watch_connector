#!/usr/bin/env python3
"""Build matching constant-time-size validators from the native character set."""
import argparse
import json
from pathlib import Path


def ranges(document):
    if (document.get('version') != 1 or document.get('checked') != 0x110000 - 0x800
            or document.get('utf16Limit') != 5):
        raise ValueError('Incomplete native monogram character set')
    result = document.get('emojiRanges')
    if not isinstance(result, list) or not result or len(result) > 4096:
        raise ValueError('Invalid native monogram ranges')
    last = -2
    for row in result:
        if (not isinstance(row, list) or len(row) != 2
                or any(type(v) is not int for v in row)
                or row[0] <= last + 1 or row[1] < row[0] or row[1] > 0x10ffff
                or row[0] <= 0xdfff and row[1] >= 0xd800):
            raise ValueError('Unverified native monogram range')
        last = row[1]
    return result


def generate(document):
    values = ranges(document)
    rows = ['    ' + ', '.join(str(v) for pair in values[i:i + 6] for v in pair) + ','
            for i in range(0, len(values), 6)]
    ints = '\n'.join(rows)
    java = '''package dev.applewatchandroid.bridge;

/** Generated from watchOS 26.2 / 23S303 TextInput's exact emoji character set.
 * String length uses UTF-16 units, as NSString does. No ASCII-only restriction. */
final class NativeMonogramTextRules {
    private NativeMonogramTextRules() { }
    private static final int[] EMOJI = {
%s
    };
    static boolean valid(String text) {
        if (text == null || text.isEmpty() || text.length() > 5) return false;
        for (int index = 0; index < text.length();) {
            int scalar = text.codePointAt(index);
            if (scalar >= 0xd800 && scalar <= 0xdfff || emoji(scalar)) return false;
            index += Character.charCount(scalar);
        }
        return true;
    }
    private static boolean emoji(int scalar) {
        int low = 0, high = EMOJI.length / 2;
        while (low < high) {
            int middle = (low + high) >>> 1;
            if (scalar < EMOJI[2 * middle]) high = middle;
            else if (scalar > EMOJI[2 * middle + 1]) low = middle + 1;
            else return true;
        }
        return false;
    }
}
''' % ints
    dart = '''/// Generated from watchOS 26.2 / 23S303 TextInput's exact emoji character set.
/// UTF-16 length matches NSString; malformed surrogate pairs are not serializable.
abstract final class NativeMonogramTextRules {
  static const _emoji = <int>[
%s
  ];
  static bool valid(String text) {
    if (text.isEmpty || text.length > 5) return false;
    return validCharacters(text);
  }
  /// Editing permits longer/empty text; Foundation truncation occurs on commit.
  static bool validCharacters(String text) {
    for (var index = 0; index < text.length; index++) {
      var scalar = text.codeUnitAt(index);
      if (scalar >= 0xd800 && scalar <= 0xdbff) {
        if (++index >= text.length) return false;
        final low = text.codeUnitAt(index);
        if (low < 0xdc00 || low > 0xdfff) return false;
        scalar = 0x10000 + ((scalar - 0xd800) << 10) + low - 0xdc00;
      } else if (scalar >= 0xdc00 && scalar <= 0xdfff) {
        return false;
      }
      if (_containsEmoji(scalar)) return false;
    }
    return true;
  }
  static bool _containsEmoji(int scalar) {
    var low = 0, high = _emoji.length ~/ 2;
    while (low < high) {
      final middle = (low + high) >> 1;
      if (scalar < _emoji[2 * middle]) {
        high = middle;
      } else if (scalar > _emoji[2 * middle + 1]) {
        low = middle + 1;
      } else {
        return true;
      }
    }
    return false;
  }
}
''' % ints
    return java, dart


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('probe', type=Path)
    parser.add_argument('--java', required=True, type=Path)
    parser.add_argument('--dart', required=True, type=Path)
    args = parser.parse_args()
    java, dart = generate(json.loads(args.probe.read_text()))
    args.java.write_text(java)
    args.dart.write_text(dart)
