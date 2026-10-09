import copy
import hashlib
import io
import json
import tempfile
import unittest
from pathlib import Path
from PIL import Image
from prepare_native_swatch_bindings import infer


class NativeSwatchBindingsTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        output = io.BytesIO()
        Image.new('RGBA', (1, 1), (255, 255, 255, 128)).save(output, 'PNG')
        self.source = output.getvalue()
        self.leaf = hashlib.sha256(self.source).hexdigest() + '.png'
        self.directories = []
        self.captures = []
        for index, color in enumerate(('blue', 'white', 'red')):
            directory = self.root / str(index)
            directory.mkdir()
            (directory / self.leaf).write_bytes(self.source)
            custom = dict(color=color, style='simple')
            capture = dict(family='type:fixture', customization=custom, rows=[dict(
                mode=11, label='Simple', optionClass='NativeOption', size=[1, 1], scale=1,
                customization=custom.copy(), image='11-0.png', drawOperations=[dict(
                    image=self.leaf, operation='rect', geometry=[0, 0, 1, 1],
                    imageSize=[1, 1], imageScale=1, orientation=0,
                    tint=[[0, 0, 1, 1], [1, 1, 1, 1], [1, 0, 0, 1]][index])])])
            self.directories.append(directory)
            self.captures.append(capture)
        self.write()

    def write(self):
        for directory, capture in zip(self.directories, self.captures):
            (directory / 'capture.json').write_text(json.dumps(capture))

    def test_roles_require_independent_contexts_and_complete_option_identity(self):
        bases, proof = infer(self.directories)
        self.assertEqual(len(bases), 2)
        self.assertEqual(set(bases[0]['sources']), {self.leaf})
        self.assertEqual(proof['rows'][0]['customization']['style'], 'simple')
        with self.assertRaises(ValueError):
            infer(self.directories[:2])
        self.captures[2]['rows'][0]['customization']['style'] = 'different'
        self.write()
        with self.assertRaises(ValueError):
            infer(self.directories)

    def test_rejects_geometry_and_source_bytes_changes(self):
        original = copy.deepcopy(self.captures)
        self.captures[1]['rows'][0]['drawOperations'][0]['geometry'][0] = 1
        self.write()
        with self.assertRaises(ValueError):
            infer(self.directories)
        self.captures = original
        self.write()
        (self.directories[1] / self.leaf).write_bytes(b'different')
        with self.assertRaises(ValueError):
            infer(self.directories)

    def test_rejects_declared_scale_without_corresponding_source_pixels(self):
        for capture in self.captures:
            capture['rows'][0]['drawOperations'][0]['imageScale'] = 2
        self.write()
        with self.assertRaises(ValueError):
            infer(self.directories)

    def test_rejects_same_source_with_two_native_tints(self):
        for capture in self.captures:
            call = copy.deepcopy(capture['rows'][0]['drawOperations'][0])
            call['tint'] = [.5, .5, .5, 1]
            capture['rows'][0]['drawOperations'].append(call)
        self.write()
        with self.assertRaises(ValueError):
            infer(self.directories)


if __name__ == '__main__':
    unittest.main()
