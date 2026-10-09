import json
import tempfile
import unittest
from pathlib import Path
from native_preview_catalog import read_catalog, write_catalog, IMAGE_PREFIX


class NativePreviewCatalogTest(unittest.TestCase):
    def test_sharded_roundtrip_more_than_ten_thousand_entries(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            entries = {f'{i:02x}{j:062x}': IMAGE_PREFIX + 'a' * 64 + '.nfp'
                       for i in range(16) for j in range(1000)}
            written = write_catalog(root, entries)
            self.assertEqual(len(written['shards']), 16)
            self.assertLess((root / 'configured_previews.json').stat().st_size, 4000)
            self.assertEqual(read_catalog(root)[1], entries)
            write_catalog(root, entries)
            self.assertEqual(read_catalog(root)[1], entries)
            self.assertEqual(len(list((root / 'configured_preview_indexes').iterdir())), 16)

    def test_corrupt_shard_and_false_count_are_rejected(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            entry = {'aa' + '0' * 62: IMAGE_PREFIX + 'a' * 64 + '.webp'}
            written = write_catalog(root, entry)
            reference = written['shards']['aa']
            target = root / 'configured_preview_indexes' / Path(reference['asset']).name
            target.write_bytes(target.read_bytes()[:-1])
            with self.assertRaises(ValueError):
                read_catalog(root)
            target.unlink()
            write_catalog(root, entry)
            written['shards']['aa']['count'] = 2
            (root / 'configured_previews.json').write_text(json.dumps(written))
            with self.assertRaises(ValueError):
                read_catalog(root)


if __name__ == '__main__':
    unittest.main()
