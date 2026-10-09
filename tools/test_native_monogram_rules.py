import unittest
from copy import deepcopy
from build_native_monogram_rules import generate, ranges


class NativeMonogramRulesTest(unittest.TestCase):
    def setUp(self):
        self.probe = {'version': 1, 'checked': 1112064, 'utf16Limit': 5,
                      'emojiRanges': [[169, 169], [174, 174], [0x1f600, 0x1f601]]}

    def test_native_ranges_generate_both_languages_without_ascii_restriction(self):
        java, dart = generate(self.probe)
        self.assertIn('169, 169, 174, 174, 128512, 128513,', java)
        self.assertIn('169, 169, 174, 174, 128512, 128513,', dart)
        self.assertIn('text.length() > 5', java)
        self.assertIn('text.length > 5', dart)

    def test_unverified_counts_overlaps_surrogates_and_unbounded_sets_are_rejected(self):
        for key, value in [('version', 2), ('checked', 1112063), ('utf16Limit', 4),
                           ('emojiRanges', [[2, 3], [3, 4]]), ('emojiRanges', [[2, 3], [4, 5]]),
                           ('emojiRanges', [[True, 3]]), ('emojiRanges', [[0xd800, 0xdfff]]),
                           ('emojiRanges', [[0x110000, 0x110000]])]:
            with self.subTest(key=key, value=value), self.assertRaises(ValueError):
                ranges({**deepcopy(self.probe), key: value})


if __name__ == '__main__':
    unittest.main()
