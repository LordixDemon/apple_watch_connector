import copy
import json
import shutil
import tempfile
import unittest
from pathlib import Path
from build_native_pigment_check import build


class NativeCheckTest(unittest.TestCase):
    def setUp(self):
        self.fixture = Path(__file__).parent / 'fixtures/native_pigment_check'
        self.capture = json.loads((self.fixture / 'capture.json').read_text())
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.directory = Path(self.temp.name)
        assets = Path(__file__).parents[1] / 'apple-watch-companion/assets/native_faces/pigment_swatches'
        for row in self.capture['rows']:
            source = self.fixture / row['image']
            if not source.exists(): source = assets / row['image']
            shutil.copy2(source, self.directory / row['image'])

    def test_original_uikit_references_prove_two_unchanged_palette_images(self):
        metadata, images = build(self.capture, self.directory)
        self.assertEqual(len(images), 2)
        self.assertEqual(metadata['primaryTransform'], 'clampSRGB')
        self.assertAlmostEqual(metadata['width'], 112 / 3)
        for leaf, data in images.items():
            self.assertEqual(data, (self.directory / leaf).read_bytes())

    def test_standalone_light_corrupt_or_changed_geometry_cannot_be_promoted(self):
        mutations = [
            lambda r: r.update(appearance='light'),
            lambda r: r.update(version=True),
            lambda r: r['rows'][0].update(viewAppearance=1),
            lambda r: r['rows'][0].update(width=36),
            lambda r: r['rows'][0].update(tint=[1, 1, 1, 1]),
            lambda r: r['rows'][0].update(image='../native.png'),
            lambda r: r['rows'][0].update(selectedVisible=1.0),
            lambda r: r['rows'].append(copy.deepcopy(r['rows'][0])),
            lambda r: r['rows'][2].update(primary=[0, 1, 0, 1]),
        ]
        for mutate in mutations:
            changed = copy.deepcopy(self.capture); mutate(changed)
            with self.assertRaises(ValueError): build(changed, self.directory)
        path = self.directory / self.capture['rows'][0]['image']
        path.write_bytes(path.read_bytes()[:24])
        with self.assertRaises(ValueError): build(self.capture, self.directory)


if __name__ == '__main__': unittest.main()
