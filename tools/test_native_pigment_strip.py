import copy
import hashlib
import tempfile
import unittest
from pathlib import Path
from PIL import Image
from build_native_pigment_strip import build


class StripTest(unittest.TestCase):
    def fixture(self, folder):
        rows = []
        for name, size in [('_plusImage', (127, 126)), ('_dividerImage', (9, 126))]:
            path = Path(folder) / 'image.png'
            Image.new('RGBA', size, (152, 152, 152, 255)).save(path)
            data = path.read_bytes(); leaf = hashlib.sha256(data).hexdigest() + '.png'
            path.rename(Path(folder) / leaf)
            rows.append({'selector': name, 'width': size[0] / 3, 'height': size[1] / 3,
                         'scale': 3, 'renderingMode': 0, 'image': leaf})
        return {'version': 1, 'cellClass': 'NTKCFaceDetailPigmentEditOptionCell', 'rows': rows}

    def test_rectangular_native_bytes_are_preserved(self):
        with tempfile.TemporaryDirectory() as folder:
            capture = self.fixture(folder)
            metadata, images = build(capture, Path(folder))
            self.assertEqual(metadata['dividerWidth'], 3)
            self.assertEqual(metadata['plusWidth'], 127 / 3)
            self.assertEqual(len(images), 2)
            for leaf, data in images.items(): self.assertEqual(data, (Path(folder) / leaf).read_bytes())

    def test_unverified_geometry_selector_hash_or_image_is_rejected(self):
        with tempfile.TemporaryDirectory() as folder:
            capture = self.fixture(folder)
            for change in [lambda r: r.update(selector='_unknown'), lambda r: r.update(scale=True),
                           lambda r: r.update(width=40), lambda r: r.update(image='../image.png'),
                           lambda r: r.update(renderingMode=1)]:
                bad = copy.deepcopy(capture); change(bad['rows'][0])
                with self.assertRaises(ValueError): build(bad, Path(folder))
            bad = copy.deepcopy(capture); bad['rows'][1] = bad['rows'][0]
            with self.assertRaises(ValueError): build(bad, Path(folder))
            leaf = capture['rows'][0]['image']
            Image.new('RGBA', (127, 126), (0, 0, 0, 0)).save(Path(folder) / leaf)
            with self.assertRaises(ValueError): build(capture, Path(folder))
