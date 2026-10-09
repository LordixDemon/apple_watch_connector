import json
import tempfile
import unittest
from pathlib import Path
from build_native_face_previews import key, write_requests
from factor_native_face_previews import decode, pack_pixels
from stage_native_face_previews import grouped, inputs


class NativePreviewStagingTest(unittest.TestCase):
    def test_exact_shared_plane_preserves_alpha_and_every_native_channel(self):
        import numpy as np
        first = np.array([[[255, 1, 2, 0], [3, 4, 5, 128]],
                          [[6, 7, 8, 255], [255, 1, 2, 0]]], dtype=np.uint8)
        second = first.copy()
        second[0, 1] = [10, 20, 30, 64]
        before = first.copy()
        plane, frames, classes = pack_pixels([first, second])
        self.assertEqual(classes, 3)
        self.assertEqual(decode(frames[0], plane), (2, 2, first.tobytes()))
        self.assertEqual(decode(frames[1], plane), (2, 2, second.tobytes()))
        self.assertTrue(np.array_equal(first, before))
        self.assertIsNone(pack_pixels([first, second], max_classes=2))

    def test_grouping_keeps_family_and_every_other_native_style_field(self):
        def row(family, color, style):
            return {'family': family, 'configuration': {
                'customization': {'color': color, 'style': style},
                'complications': {'opaque': 'not image grouping data'}}}
        rows = [row('type:a', 'red', 'one'), row('type:a', 'green', 'one'),
                row('type:b', 'red', 'one'), row('type:a', 'red', 'two')]
        groups = grouped(rows, 'color')
        self.assertEqual(sorted(len(v) for v in groups.values()), [1, 1, 2])
        self.assertTrue(all('complications' not in k for k in groups))

    def test_native_input_requires_terminal_report_and_all_accepted_pngs(self):
        config = {'face type': 'fixture'}
        row = {'key': key('type:fixture', {}), 'family': 'type:fixture', 'configuration': config}
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            write_requests(directory, [row], 1)
            page = json.loads((directory / 'requests-pages.json').read_text())['pages'][0]['file']
            png = directory / 'png'
            png.mkdir()
            with self.assertRaises(FileNotFoundError):
                inputs(directory)
            report = png / (Path(page).stem + '-report.json')
            report.write_text(json.dumps({'requested': 1, 'rendered': 1, 'rejected': 0, 'rejections': []}))
            with self.assertRaises(ValueError):
                inputs(directory)
            (png / (row['key'] + '.png')).write_bytes(b'existence only; native pixels verified separately')
            self.assertEqual(inputs(directory), ([row], 0))
            report.write_text(json.dumps({'requested': 1, 'rendered': 0, 'rejected': 1,
                'rejections': [{'key': row['key'], 'reason': 'native-normalization'}]}))
            self.assertEqual(inputs(directory), ([], 1))
            # Rejected leftovers must not be promoted, even if a PNG exists.
            report.write_text(json.dumps({'requested': 1, 'rendered': 0, 'rejected': 1,
                'rejections': [{'key': 'f' * 64, 'reason': 'native-normalization'}]}))
            with self.assertRaises(ValueError):
                inputs(directory)


if __name__ == '__main__':
    unittest.main()
