import json
import tempfile
import unittest
from pathlib import Path

from build_native_face_previews import customization, key, merge_entries, read_entries, read_requests, write_requests


class NativePreviewManifestTest(unittest.TestCase):
    def test_paged_requests_cover_more_than_one_native_renderer_batch(self):
        rows = []
        for i in range(10001):
            config = {'face type': 'fixture', 'customization': {'color': str(i)}}
            rows.append({'key': key('type:fixture', customization(config)),
                         'family': 'type:fixture', 'configuration': config})
        with tempfile.TemporaryDirectory() as temporary:
            folder = Path(temporary)
            with self.assertRaises(ValueError):
                write_requests(folder, rows)
            write_requests(folder, rows, 4096)
            self.assertEqual(list(read_requests(folder)), rows)
            manifest = json.loads((folder / 'requests-pages.json').read_text())
            self.assertEqual([r['count'] for r in manifest['pages']], [4096, 4096, 1809])
            write_requests(folder, rows, 4096)
            self.assertEqual(json.loads((folder / 'requests-pages.json').read_text()), manifest)
            # Legacy generation can supersede a prior paged generation safely.
            write_requests(folder, rows[:2])
            self.assertFalse((folder / 'requests-pages.json').exists())
            self.assertEqual(list(read_requests(folder)), rows[:2])

    def test_request_pages_reject_tampering_paths_counts_and_repeated_keys(self):
        config = {'face type': 'fixture'}
        row = {'key': key('type:fixture', {}), 'family': 'type:fixture', 'configuration': config}
        with tempfile.TemporaryDirectory() as temporary:
            folder = Path(temporary)
            write_requests(folder, [row], 1)
            path = folder / 'requests-pages.json'
            original = json.loads(path.read_text())
            for change in ['file', 'count', 'bytes', 'sha256']:
                manifest = json.loads(json.dumps(original))
                manifest['pages'][0][change] = '../outside.json' if change == 'file' else 0
                path.write_text(json.dumps(manifest))
                with self.assertRaises(ValueError):
                    list(read_requests(folder))
            path.write_text(json.dumps({**original, 'count': 2}))
            with self.assertRaises(ValueError):
                list(read_requests(folder))
            path.write_text(json.dumps(original))
            page = folder / original['pages'][0]['file']
            page.write_bytes(page.read_bytes() + b' ')
            with self.assertRaises(ValueError):
                list(read_requests(folder))
            page.unlink()
            write_requests(folder, [row, row], 1)
            with self.assertRaises(ValueError):
                list(read_requests(folder))

    def test_absent_native_customization_has_empty_key_but_malformed_stays_invalid(self):
        config = {'face type': 'solar'}
        self.assertEqual(key('type:solar', customization(config)),
                         key('type:solar', {}))
        self.assertNotIn('customization', config)
        for value in [None, [], 'unknown']:
            with self.assertRaises(ValueError):
                customization({**config, 'customization': value})

    def test_merge_preserves_existing_styles_and_refuses_replacement(self):
        first, second = 'a' * 64, 'b' * 64
        original = {first: 'old.webp'}
        self.assertEqual(merge_entries(original, {second: 'new.webp'}),
                         {first: 'old.webp', second: 'new.webp'})
        self.assertEqual(original, {first: 'old.webp'})
        with self.assertRaises(ValueError):
            merge_entries(original, {first: 'substituted.webp'})
        values = {f'{i:064x}': 'x' for i in range(10000)}
        with self.assertRaises(ValueError):
            merge_entries(values, {f'{10000:064x}': 'x'})
        # More than ten thousand total styles are valid across bounded shards.
        self.assertEqual(len(merge_entries(values, {'ff' + 'a' * 62: 'x'})), 10001)

    def test_manifest_requires_compatible_locale_paths_and_existing_files(self):
        with tempfile.TemporaryDirectory() as temporary:
            assets = Path(temporary)
            folder = assets / 'configured_previews'
            folder.mkdir()
            image = folder / ('b' * 64 + '.webp')
            image.write_bytes(b'fixture file existence only; image pixels checked at promotion')
            manifest = {'version': 1, 'locale': 'en_US', 'blankComplications': True,
                        'entries': {'a' * 64: 'assets/native_faces/configured_previews/' + image.name}}
            source = assets / 'configured_previews.json'
            source.write_text(json.dumps(manifest))
            self.assertEqual(read_entries(assets), manifest['entries'])
            manifest['locale'] = 'ru_UA'
            source.write_text(json.dumps(manifest))
            with self.assertRaises(ValueError):
                read_entries(assets)
            manifest['locale'] = 'en_US'
            manifest['entries']['a' * 64] = '../outside.webp'
            source.write_text(json.dumps(manifest))
            with self.assertRaises(ValueError):
                read_entries(assets)
            manifest['entries']['a' * 64] = 'assets/native_faces/configured_previews/' + image.name
            source.write_text(json.dumps(manifest))
            image.unlink()
            with self.assertRaises(ValueError):
                read_entries(assets)


if __name__ == '__main__':
    unittest.main()
