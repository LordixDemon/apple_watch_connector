import copy
import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch
from build_native_face_previews import key, merge_entries, write_requests
from native_preview_catalog import read_catalog, write_catalog
from native_preview_replacement import FAMILY, POLICY, validate_replacement, merge_replacement
import stage_native_face_previews as staging


class NativeMaskReplacementTest(unittest.TestCase):
    def setUp(self):
        config = {'face type': 'bundle', 'version': 4,
                  'bundle id': 'com.apple.NTKCollieFaceBundle',
                  'customization': {'style': 'unicorn', 'color': 'purple'}}
        self.row = {'key': key(FAMILY, config['customization']),
                    'family': FAMILY, 'configuration': config}
        self.other = key('type:unrelated', {})
        self.old = 'assets/native_faces/configured_previews/' + 'a' * 64 + '.webp'
        self.new = 'assets/native_faces/configured_previews/' + 'b' * 64 + '.webp'
        self.existing = {self.row['key']: self.old, self.other: self.old}
        self.manifest = {'version': 1, 'family': FAMILY, 'maskPolicy': POLICY,
                         'sourceRoot': 'c' * 64, 'entries': {self.row['key']: self.old}}

    def test_ordinary_merge_still_rejects_replacement(self):
        with self.assertRaises(ValueError):
            merge_entries(self.existing, {self.row['key']: self.new})
        expected = validate_replacement(self.manifest, self.existing, [self.row], 'c' * 64)
        changed = merge_replacement(self.existing, {self.row['key']: self.new}, expected)
        self.assertEqual(changed, {self.row['key']: self.new, self.other: self.old})

    def test_stale_reference_source_policy_and_incomplete_coverage_rejected(self):
        for field, value in [('sourceRoot', 'd' * 64), ('maskPolicy', 'unknown'),
                             ('entries', {self.row['key']: self.new}), ('version', True)]:
            manifest = dict(self.manifest, **{field: value})
            with self.subTest(field=field), self.assertRaises(ValueError):
                validate_replacement(manifest, self.existing, [self.row], 'c' * 64)
        for rows in [[], [self.row, self.row]]:
            with self.assertRaises(ValueError):
                validate_replacement(self.manifest, self.existing, rows, 'c' * 64)
        with self.assertRaises(ValueError):
            merge_replacement(self.existing, {self.other: self.new}, self.manifest['entries'])

    def test_wrong_native_family_resources_and_appearance_key_rejected(self):
        for field, value in [('bundle id', 'other'), ('customData', {}),
                             ('resource directory', ''), ('complications', {}),
                             ('customization', {'style': 'ghost'})]:
            row = copy.deepcopy(self.row)
            row['configuration'][field] = value
            with self.subTest(field=field), self.assertRaises(ValueError):
                validate_replacement(self.manifest, self.existing, [row], 'c' * 64)

    def test_real_staging_proves_pixels_preserves_unrelated_and_rejects_tampering(self):
        from PIL import Image
        import hashlib
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            assets, requests, target = root / 'assets', root / 'requests', root / 'staged'
            (assets / 'configured_previews').mkdir(parents=True)
            image = Image.new('RGBA', (422, 514), (10, 20, 30, 128))
            image.save(assets / 'configured_previews' / Path(self.old).name, 'WEBP', lossless=True, exact=True)
            old_bytes = (assets / 'configured_previews' / Path(self.old).name).read_bytes()
            write_catalog(assets, self.existing)
            write_requests(requests, [self.row])
            (requests / 'png').mkdir()
            image.putpixel((210, 0), (100, 110, 120, 240))
            image.save(requests / 'png' / (self.row['key'] + '.png'))
            report_path = requests / 'png' / 'requests-report.json'
            report = {'requested': 1, 'rendered': 1, 'rejected': 0,
                      'rejections': [], 'maskPolicy': POLICY}
            report_path.write_text(json.dumps(report))
            manifest = dict(self.manifest, sourceRoot=hashlib.sha256(
                (assets / 'configured_previews.json').read_bytes()).hexdigest())
            path = root / 'replacement.json'
            path.write_text(json.dumps(manifest))
            with patch.object(staging, 'ASSETS', assets):
                staging.stage(requests, target, 1, 'color', path)
                changed = json.loads((target / 'manifest.json').read_text())
                changed['entries'][self.other] = self.new
                (target / 'manifest.json').write_text(json.dumps(changed))
                with self.assertRaises(ValueError):
                    staging.verify(target)
                changed['entries'][self.other] = self.old
                (target / 'manifest.json').write_text(json.dumps(changed))
                report_path.write_text(json.dumps(dict(report, maskPolicy='ordinary-simulator')))
                with self.assertRaises(ValueError):
                    staging.verify(target)
                report_path.write_text(json.dumps(report))
                staging.promote(target)
                _, result = read_catalog(assets)
                self.assertEqual(result[self.other], self.old)
                self.assertNotEqual(result[self.row['key']], self.old)
                self.assertEqual((assets / 'configured_previews' / Path(self.old).name).read_bytes(), old_bytes)


if __name__ == '__main__':
    unittest.main()
