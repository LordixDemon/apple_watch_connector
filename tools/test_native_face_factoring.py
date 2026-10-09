import hashlib
import tempfile
import unittest
import zlib
from pathlib import Path

from factor_native_face_previews import header, decode, partition_pixels
from build_native_face_previews import read_entries


class NativeFactoringTest(unittest.TestCase):
    def test_partition_refines_aliases_and_preserves_transparent_native_rgb(self):
        import numpy as np
        first = np.array([[[1, 2, 3, 0], [1, 2, 3, 0]],
                          [[9, 8, 7, 255], [1, 2, 3, 0]]], dtype=np.uint8)
        second = first.copy()
        second[0, 1] = [4, 5, 6, 0]
        ids, representatives = partition_pixels([first, second])
        self.assertEqual(len(representatives), 3)
        self.assertEqual(ids[0], ids[3])
        self.assertNotEqual(ids[0], ids[1])
        for original in [first, second]:
            palette = original.reshape(-1, 4)[representatives]
            self.assertEqual(palette[ids].tobytes(), original.tobytes())
        self.assertIsNone(partition_pixels([first, second], max_classes=2))

    def fixture(self):
        pixels = bytes([10, 20, 30, 255, 200, 50, 80, 128, 99, 98, 97, 0, 10, 20, 30, 255])
        plane = header(b'NFPM', 2, 2, 3) + zlib.compress(bytes([0, 0, 1, 0, 2, 0, 0, 0]))
        frame = (header(b'NFPT', 2, 2, 3) + hashlib.sha256(plane).digest()
                 + hashlib.sha256(pixels).digest() + zlib.compress(pixels[:12]))
        return frame, plane, pixels

    def test_reference_format_retains_exact_transparent_native_pixels(self):
        frame, plane, pixels = self.fixture()
        self.assertEqual(decode(frame, plane), (2, 2, pixels))
        with self.assertRaises(ValueError):
            decode(frame[:-1], plane)

    def test_invalid_identity_and_pixel_reference_are_refused(self):
        frame, plane, _ = self.fixture()
        bad = bytearray(frame)
        bad[48] ^= 1
        with self.assertRaises(ValueError):
            decode(bytes(bad), plane)
        with self.assertRaises(ValueError):
            decode(frame, header(b'NFPM', 2, 2, 3) + zlib.compress(b'bad'))

    def test_manifest_merge_requires_index_plane_and_version_two(self):
        import json
        frame, plane, _ = self.fixture()
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            files = root / 'configured_previews'
            files.mkdir()
            name = hashlib.sha256(frame).hexdigest() + '.nfp'
            (files / name).write_bytes(frame)
            manifest = {'version': 2, 'locale': 'en_US', 'blankComplications': True,
                        'entries': {'a' * 64: 'assets/native_faces/configured_previews/' + name}}
            source = root / 'configured_previews.json'
            source.write_text(json.dumps(manifest))
            with self.assertRaises(ValueError):
                read_entries(root)
            (files / (hashlib.sha256(plane).hexdigest() + '.nfpm')).write_bytes(plane)
            self.assertEqual(read_entries(root), manifest['entries'])
            manifest['version'] = 1
            source.write_text(json.dumps(manifest))
            with self.assertRaises(ValueError):
                read_entries(root)


if __name__ == '__main__':
    unittest.main()
