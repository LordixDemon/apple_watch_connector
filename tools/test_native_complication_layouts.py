import unittest
from copy import deepcopy
from build_native_complication_layouts import build


class NativeComplicationLayoutsTest(unittest.TestCase):
    def setUp(self):
        self.profile = {'family': 'fixture', 'slotFamilies': {'top left': [8], 'bottom': [8]},
                        'configuration': {'customization': {'style': 'a'}},
                        'options': {'style': [{'value': 'a'}, {'value': 'b'}]}}
        self.row = {'family': 'fixture', 'order': ['top-left', 'bottom'],
                    'wireKeys': {'top-left': 'top left', 'bottom': 'bottom'},
                    'fixed': [], 'monogram': None, 'constant': [],
                    'labels': {'top-left': 'Top Left', 'bottom': 'RESOURCE_KEY'},
                    'domains': {'style': ['a', 'b']},
                    'rules': {'style': {'a': ['bottom'], 'b': []}},
                    'checked': 2, 'normalized': 0}

    def test_preserves_proven_order_keys_rules_and_localizable_labels(self):
        result = build({'rows': [self.row]}, [self.profile])['families']['fixture']
        self.assertEqual(result['order'], ['top left', 'bottom'])
        self.assertEqual(result['rules'], {'style': {'a': ['bottom'], 'b': []}})
        self.assertEqual(result['labels'], {'top left': {'en': 'Top Left'}})
        self.row['fixed'] = ['top-left']
        self.row['monogram'] = 'bottom'
        self.assertEqual(build({'rows': [self.row]}, [self.profile])['families']['fixture']['excluded'], ['top left', 'bottom'])

    def test_unverified_aliases_options_and_duplicate_rows_cannot_ship(self):
        for key, value in [('wireKeys', {'top-left': 'top left', 'bottom': 'top left'}),
                           ('rules', {'style': {'a': [], 'invented': []}}),
                           ('domains', {'style': ['a', 'invented']}),
                           ('constant', ['unknown']), ('checked', 0), ('normalized', 1)]:
            row = {**deepcopy(self.row), key: value}
            with self.subTest(key=key), self.assertRaises(ValueError):
                build({'rows': [row]}, [self.profile])
        with self.assertRaises(ValueError):
            build({'rows': [self.row, self.row]}, [self.profile])

    def test_empty_rules_are_compressed_but_the_domain_remains(self):
        self.row['rules'] = {'style': {'a': [], 'b': []}}
        result = build({'rows': [self.row]}, [self.profile])['families']['fixture']
        self.assertEqual(result['rules'], {})
        self.assertEqual(result['domains'], {'style': ['a', 'b']})

    def test_monogram_requires_native_slot_and_serialization_proof(self):
        self.row['monogram'] = 'bottom'
        evidence = {'faces': [{'family': 'fixture', 'slot': 'bottom',
                              'enabled': {'app': 'monogram'}, 'disabled': None,
                              'configuration': {},
                              'enabledConfiguration': {'complications': {
                                  'top left': {'app': 'date'}, 'bottom': {'app': 'monogram'}}},
                              'disabledConfiguration': {'complications': {
                                  'top left': {'app': 'date'}}}}]}
        result = build({'rows': [self.row]}, [self.profile], evidence)['families']['fixture']
        self.assertEqual(result['monogram'], {'slot': 'bottom', 'enabled': {'app': 'monogram'}})
        for edit in ('slot', 'disabled', 'enabledConfiguration', 'disabledConfiguration'):
            bad = deepcopy(evidence)
            bad['faces'][0][edit] = ({'complications': {}} if 'Configuration' in edit
                                    else 'invented')
            with self.subTest(edit=edit), self.assertRaises(ValueError):
                build({'rows': [self.row]}, [self.profile], bad)
        with self.assertRaises(ValueError):
            build({'rows': [self.row]}, [self.profile], {'faces': []})


if __name__ == '__main__':
    unittest.main()
