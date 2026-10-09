import base64
import gzip
import hashlib
import json
import re
import unittest
from pathlib import Path
from build_native_foundation_text import generate

ROOT = Path(__file__).resolve().parent.parent


class FoundationDataTest(unittest.TestCase):
    def setUp(self):
        self.document = json.loads((ROOT / 'apple-watch-companion/test/fixtures/native_faces/foundation_text.json').read_text())

    def test_exact_generation_and_payload_round_trip(self):
        data, java = generate(self.document)
        expected = (ROOT / 'apple-watch-companion/android/app/src/main/java/dev/applewatchandroid/companion/apple_watch_companion/NativeFoundationTextPayload.java').read_text()
        self.assertEqual(expected, java)
        encoded = ''.join(re.findall(r'"([A-Za-z0-9+/=]+)"', java.split('BASE64_GZIP =', 1)[1]))
        self.assertEqual(data, gzip.decompress(base64.b64decode(encoded)))
        self.assertEqual(33855, len(data))
        self.assertIn(hashlib.sha256(data).hexdigest(), java)

    def test_incomplete_or_different_runtime_is_rejected(self):
        for key, value in [('schema', 1), ('checked', 1112063), ('uppercaseMatches', 1112063),
                           ('runtime', 'Version 26.6'), ('uppercase', []), ('letterRanges', [])]:
            with self.subTest(key=key), self.assertRaises(ValueError):
                generate({**self.document, key: value})

    def test_corrupt_tables_cannot_generate_binary(self):
        changes = [
            ('uppercase', [[0x61, 'a']]),
            ('uppercase', [[0x61, '\ud800']]),
            ('uppercase', [[True, 'B']]),
            ('uppercase', [[0x62, 'B'], [0x61, 'A']]),
            ('letterRanges', [[0xd7ff, 0xe000]]),
            ('nonBaseRanges', [[1, 3], [4, 6]]),
            ('combiningClasses', [[0x301, 0]]),
            ('combiningClasses', [[0x301, 256]]),
            ('greekDecomposition', [[0x1f84, [0x3b1, 0xd800]]]),
        ]
        for key, value in changes:
            with self.subTest(key=key, value=value), self.assertRaises(ValueError):
                generate({**self.document, key: value})


if __name__ == '__main__':
    unittest.main()
