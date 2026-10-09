import unittest
from build_native_edit_sections import build


class NativeEditSectionsTest(unittest.TestCase):
    def test_native_order_and_tokens_are_preserved(self):
        profiles = [{'family': 'fixture', 'options': {
            'style': [{'value': 'first'}, {'value': 'second'}],
            'color': [{'value': 'red'}, {'value': 'blue'}],
        }}]
        def section(field, values, kind, mode):
            return {'slot': None, 'mode': mode, 'collectionType': kind,
                    'options': [{'customization': {field: value}} for value in values]}
        row = {'family': 'fixture', 'baseline': {}, 'sections': [
            section('color', ['blue', 'red'], 1, 10),
            section('style', ['second', 'first'], 0, 15),
        ]}
        result = build([row], profiles)['families']['fixture']
        self.assertEqual(result['order'], ['color', 'style'])
        self.assertEqual(result['sections'], [{'field': 'style', 'mode': 15,
                         'collectionType': 0, 'values': ['second', 'first']}])
        row['sections'][1]['options'][0]['customization']['style'] = 'invented'
        with self.assertRaises(ValueError):
            build([row], profiles)

    def test_duplicate_options_and_families_are_rejected(self):
        profile = {'family': 'fixture', 'options': {'style': [{'value': 'a'}]}}
        section = {'slot': None, 'mode': 15, 'collectionType': 0,
                   'options': [{'customization': {'style': 'a'}}]}
        row = {'family': 'fixture', 'baseline': {}, 'sections': [section]}
        with self.assertRaises(ValueError):
            build([row, row], [profile])
        section['options'] *= 2
        with self.assertRaises(ValueError):
            build([row], [profile])

    def test_slotted_and_multiple_fields_cannot_be_flattened(self):
        row = {'family': 'fixture', 'baseline': {}, 'sections': [
            {'slot': '0', 'mode': 10, 'collectionType': 1,
             'options': [{'customization': {'color': {'slots': {'0': 'red'}}}}]},
            {'slot': None, 'mode': 15, 'collectionType': 0,
             'options': [{'customization': {'style': 'a', 'detail': 'b'}}]},
        ]}
        self.assertEqual(build([row], [{'family': 'fixture'}])['families']['fixture'],
                         {'order': [], 'sections': []})


if __name__ == '__main__':
    unittest.main()
