import json
import unittest
from pathlib import Path
from copy import deepcopy
from build_native_gallery_collections import build, customization
from prepare_native_gallery_previews import prepare
from build_native_face_previews import key


class NativeGalleryCollectionsTest(unittest.TestCase):
    def test_nested_native_values_are_preserved_but_paths_identities_and_recipes_are_not_values(self):
        self.assertEqual(customization({'color': {'slots': {'0': 'light-blue'}}, 'style': 'ghost'}),
                         {'color': {'slots': {'0': 'light-blue'}}, 'style': 'ghost'})
        for value in [None, 2, True, [], {False: 'wrong'}, {'': 'wrong'}, {'style': 'a' * 257}]:
            with self.subTest(value=value), self.assertRaises(ValueError):
                customization(value)

    def test_factory_projection_never_carries_complication_identity_or_other_source_fields(self):
        face = {'face type': 'bundle', 'bundle id': 'com.apple.Face',
                'customization': {'style': 'native'}, 'complications': {'slot': {'intent': 'SIMULATOR'}},
                'resource directory': '/simulator/photos', 'metrics': {'history': 'private'}}
        capture = {'schema': 1, 'runtime': 'watchOS26.2/23S303',
                   'collections': [{'title': f'Family {i}', 'faces': [deepcopy(face) for _ in range(4 if i else 15)]}
                                   for i in range(53)]}
        result = build(capture, {'templates': [{'family': 'bundle:com.apple.Face'}]})
        self.assertEqual(sum(len(r['variants']) for r in result['sections']), 223)
        self.assertEqual(result['sections'][0]['variants'][0],
                         {'family': 'bundle:com.apple.Face', 'customization': {'style': 'native'}})
        self.assertNotIn('SIMULATOR', json.dumps(result))
        self.assertNotIn('/simulator', json.dumps(result))
        with self.assertRaises(ValueError):
            build({**capture, 'collections': capture['collections'][:-1]}, {'templates': []})
        with self.assertRaises(ValueError):
            build({**capture, 'runtime': 'unverified'}, {'templates': []})

    def test_shipped_capture_retains_factory_counts_and_explicit_unresolved_families(self):
        root = Path(__file__).resolve().parents[1]
        value = json.loads((root / 'apple-watch-companion/assets/native_faces/gallery_collections_26_2.json').read_text())
        self.assertEqual(value['factoryVariants'], 223)
        self.assertEqual(value['factorySections'], 53)
        self.assertEqual(len(value['sections']), 50)
        self.assertEqual(sum(len(r['variants']) for r in value['sections']), 210)
        self.assertEqual(sum(value['unresolvedFamilies'].values()), 13)
        for row in value['sections']:
            for variant in row['variants']:
                self.assertEqual(set(variant), {'family', 'customization'})

    def test_snapshot_requests_project_only_native_appearance_and_skip_existing(self):
        variant = {'family': 'type:utility', 'customization': {'color': 'native-blue'}}
        metadata = {'sections': [{'variants': [variant, variant]}]}
        profiles = {'templates': [{'family': 'type:utility', 'requiresResources': False,
                    'configuration': {'face type': 'utility', 'version': 7,
                                      'complications': {'top': 'SIMULATOR'},
                                      'resource directory': '/simulator', 'history': 'private'}}]}
        rows = prepare(metadata, profiles, {})
        self.assertEqual(len(rows), 1)
        self.assertEqual(rows[0]['configuration'], {'face type': 'utility', 'version': 7,
                                                   'customization': variant['customization']})
        self.assertEqual(prepare(metadata, profiles, {key('type:utility', variant['customization']): 'verified'}), [])
        with self.assertRaises(ValueError):
            prepare({'sections': [{'variants': [{**variant, 'intent': 'SIMULATOR'}]}]}, profiles, {})
        profiles['templates'][0]['requiresResources'] = True
        with self.assertRaises(ValueError):
            prepare(metadata, profiles, {})


if __name__ == '__main__':
    unittest.main()
