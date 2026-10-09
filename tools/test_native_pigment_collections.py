import copy
import unittest
from build_native_pigment_collections import build


class CollectionsTest(unittest.TestCase):
    def setUp(self):
        self.probe = {'defaults': ['seasons.native.color'], 'families': [{
            'family': 'type:test', 'baseline': {'color': 'locked.white', 'style': 'round'},
            'defaultCustomization': {'color': 'locked.white'},
            'values': [{'customization': {'color': 'locked.white', 'style': 'round'},
                        'fullname': 'locked.white', 'collection': 'locked', 'title': 'Locked', 'addable': False},
                       {'customization': {'color': 'seasons.native.color', 'style': 'round'},
                        'fullname': 'seasons.native.color', 'collection': 'seasons.native',
                        'title': 'Native Season', 'addable': True}]}]}
        self.palette = {'pigments': [{'rgba': [1, 1, 1, 1]}], 'families': {
            'type:test': [{'field': 'color', 'mode': 10,
                           'values': [['locked.white', 0], ['seasons.native.color', 0]]}]}}

    def test_native_groups_defaults_and_locked_options(self):
        result = build(self.probe, self.palette)
        self.assertEqual(result['colors'][1]['titles'], {'en': 'Native Season'})
        self.assertTrue(result['colors'][1]['automatic'])
        self.assertFalse(result['colors'][0]['addable'])

    def test_missing_duplicate_and_cross_field_mutations_are_rejected(self):
        for mutate in [lambda r: r['values'].pop(),
                       lambda r: r['values'].append(copy.deepcopy(r['values'][0])),
                       lambda r: r['values'][0]['customization'].update(style='changed'),
                       lambda r: r['defaultCustomization'].update(color='unknown')]:
            probe = copy.deepcopy(self.probe)
            mutate(probe['families'][0])
            with self.assertRaises(ValueError): build(probe, self.palette)

    def test_verified_shade_defaults_require_canonical_tokens(self):
        palette = copy.deepcopy(self.palette)
        palette['pigments'][0]['base'] = 'locked.white'
        self.probe['families'][0]['defaultCustomization']['color'] = 'locked.white:0.00'
        self.assertEqual(build(self.probe, palette)['families']['type:test']['default'], 'locked.white:0.00')
        self.probe['families'][0]['defaultCustomization']['color'] = 'locked.white:0.50'
        with self.assertRaises(ValueError): build(self.probe, palette)


if __name__ == '__main__': unittest.main()
