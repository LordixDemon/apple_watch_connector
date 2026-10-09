import copy
import hashlib
import tempfile
import unittest
from pathlib import Path
from PIL import Image
from build_native_pigments import build, companion_complex_swatches, native_layout, native_slider_support
from build_native_pigment_row import validate as validate_row


class NativePigmentsTest(unittest.TestCase):
    def test_raw_slider_support_is_independent_of_view_permission(self):
        rows, profiles = self.fixture()
        rows[0]['sections'][0]['options'][0]['slider'] = False
        with tempfile.TemporaryDirectory() as folder:
            metadata, _ = build(rows, profiles, Path(folder))
        original = copy.deepcopy(metadata)
        capture = copy.deepcopy(rows)
        capture[0]['sections'][0]['options'][0].update(
            supportsSliderDeclared=True, supportsSlider=True, viewAllowsSlider=False,
            pigmentClass='NTKPigmentEditOption', optionClass='NTKPigmentEditOption')
        changed = native_slider_support(metadata, rows, capture)
        self.assertEqual(changed['families']['type:fixture'][0]['editable'], ['standard.orange'])
        self.assertEqual(changed['pigments'], original['pigments'])
        self.assertEqual(metadata, original)
        for mutate in [
            lambda x: x.clear(),
            lambda x: x.append(copy.deepcopy(x[0])),
            lambda x: x[0].update(family='unknown'),
            lambda x: x[0].update(sections=[None]),
            lambda x: x[0]['baseline'].update(style='other'),
            lambda x: x[0]['sections'][0]['options'].clear(),
            lambda x: x[0]['sections'][0]['options'][0].update(supportsSliderDeclared=False),
            lambda x: x[0]['sections'][0]['options'][0].update(supportsSlider=1),
            lambda x: x[0]['sections'][0]['options'][0].update(viewAllowsSlider=True),
            lambda x: x[0]['sections'][0]['options'][0].update(pigmentClass='Unproven'),
            lambda x: x[0]['sections'][0]['options'][0]['customization'].update(style='changed'),
        ]:
            bad = copy.deepcopy(capture); mutate(bad)
            with self.assertRaises(ValueError): native_slider_support(metadata, rows, bad)

    def test_original_row_capture_matches_only_the_actual_color_collection(self):
        import json
        value = json.loads((Path(__file__).parent / 'data/ios266-pigment-layout.json').read_text())
        capture = json.loads((Path(__file__).parent.parent / 'apple-watch-companion/test/fixtures/native_faces/pigment_row.json').read_text())
        validate_row(capture, value)
        for mutate in [
            lambda x: x.update(collectionType=0),
            lambda x: x.update(windowAttached=False),
            lambda x: x.update(tableStyle=0),
            lambda x: x['tableCellInTable'].__setitem__(0, 0),
            lambda x: x['contentInset'].__setitem__(1, 1 + 1/3),
            lambda x: x.update(mode=True),
            lambda x: x.update(rowHeight=115 + 1/3),
            lambda x: x.update(cellLabels=[{'text': 'Multicolor'}]),
            lambda x: x.update(pigmentClassOptionsDescription='Multicolor'),
            lambda x: x['swatchFrame'].__setitem__(1, 8),
            lambda x: x['headerLabels'][0].update(pointSize=13),
            lambda x: x['headerLabels'][0].update(rgba=[1, 1, 1, 1]),
            lambda x: x['headerLabels'][0].update(text='MULTICOLOR'),
            lambda x: x['headerLabels'][0]['frame'].__setitem__(0, 0),
        ]:
            bad = copy.deepcopy(capture); mutate(bad)
            with self.assertRaises(ValueError): validate_row(bad, value)
        for key, invalid in [('swatchVerticalInset', True), ('headerLineHeight', float('nan')),
                             ('compactHorizontalInset', 1), ('headerMaxFontSize', 16),
                             ('swatchVerticalInset', 1), ('unknown', 1)]:
            bad = copy.deepcopy(value); bad['row'][key] = invalid
            with self.assertRaises(ValueError): native_layout(bad)

    def test_original_picker_layout_is_separate_and_strict(self):
        value = __import__('json').loads((Path(__file__).parent / 'data/ios266-pigment-layout.json').read_text())
        self.assertEqual(native_layout(value), value)
        for key, invalid in [('diameter', True), ('headerGap', float('nan')),
                             ('topInset', -1), ('headerFontSize', 0),
                             ('headerMaxFontSize', 16), ('expandedHorizontalInset', 15)]:
            changed = copy.deepcopy(value); changed['picker'][key] = invalid
            with self.assertRaises(ValueError): native_layout(changed)
        changed = copy.deepcopy(value); changed['picker']['unknown'] = 12
        with self.assertRaises(ValueError): native_layout(changed)

    def test_native_layout_rejects_guessed_corrupt_or_unbounded_metrics(self):
        valid = {'version': 1, 'compactDiameter': 36, 'expandedDiameter': 127 / 3,
                 'expandedAbovePhysicalPixels': 750}
        self.assertEqual(native_layout(valid), valid)
        for changed in [
            {**valid, 'version': True}, {**valid, 'expandedDiameter': float('nan')},
            {**valid, 'compactDiameter': 100}, {**valid, 'expandedAbovePhysicalPixels': 0},
            {**valid, 'unproven': 42}, {k: v for k, v in valid.items() if k != 'version'},
        ]:
            with self.assertRaises(ValueError): native_layout(changed)

    def test_strip_metrics_reject_paths_bad_types_and_overlapping_cells(self):
        value = __import__('json').loads((Path(__file__).parent / 'data/ios266-pigment-layout.json').read_text())
        for key, invalid in [('plusImage', '../native.png'), ('dividerWidth', True),
                             ('plusHeight', float('nan')), ('swatchSpacing', 1),
                             ('expandedOutlineWidth', 2), ('unknown', 12)]:
            changed = copy.deepcopy(value); changed['strip'][key] = invalid
            with self.assertRaises(ValueError): native_layout(changed)

    def test_native_slider_classification_survives_missing_gradient(self):
        rows, profiles = self.fixture()
        rows[0]['sections'][0]['options'][0]['stops'] = [None, None, None]
        with tempfile.TemporaryDirectory() as folder:
            metadata, _ = build(rows, profiles, Path(folder))
        self.assertEqual(metadata['families']['type:fixture'][0]['editable'], ['standard.orange'])
        self.assertNotIn('stops', metadata['pigments'][0])

    def complex_fixture(self, folder):
        rows, profiles = self.fixture()
        option = rows[0]['sections'][0]['options'][0]
        option.update(multi=True, slider=False, rgba=None, image='native.png')
        second = copy.deepcopy(rows[0])
        second['family'] = 'type:second'
        profiles.append({**profiles[0], 'family': 'type:second'})
        rows.append(second)
        watch, companion = Path(folder) / 'watch', Path(folder) / 'companion'
        watch.mkdir(); companion.mkdir()
        Image.new('RGBA', (2, 2), (255, 64, 0, 255)).save(watch / 'native.png')
        Image.new('RGBA', (8, 8), (0, 64, 255, 255)).save(companion / 'native.png')
        metadata, images = build(rows, profiles, watch)
        return rows, metadata, images, companion

    def test_companion_complex_image_changes_only_proven_family_reference(self):
        with tempfile.TemporaryDirectory() as folder:
            rows, metadata, images, directory = self.complex_fixture(folder)
            original = copy.deepcopy(metadata)
            result, payloads = companion_complex_swatches(
                metadata, images, rows, [copy.deepcopy(rows[0])], directory)
            self.assertEqual(metadata, original)
            first = result['families']['type:fixture'][0]['values'][0][1]
            second = result['families']['type:second'][0]['values'][0][1]
            self.assertNotEqual(first, second)
            self.assertEqual(second, 0)
            self.assertEqual(result['pigments'][second], metadata['pigments'][0])
            leaf = result['pigments'][first]['image']
            self.assertEqual(payloads[leaf], (directory / 'native.png').read_bytes())
            self.assertEqual(len(payloads), 2)
            self.assertEqual(result['pigments'][first]['rgba'], None)
            self.assertNotIn('shadeImageMask', result['pigments'][first])
            self.assertEqual(images.keys(), {metadata['pigments'][0]['image']})

    def test_companion_cannot_guess_option_or_recolor_unknown_native_palette(self):
        with tempfile.TemporaryDirectory() as folder:
            rows, metadata, images, directory = self.complex_fixture(folder)
            mutations = [
                lambda r: r['sections'][0]['options'][0]['customization'].update(style='other'),
                lambda r: r['sections'][0]['options'][0].update(slider=True),
                lambda r: r['sections'][0]['options'][0].update(rgba=[1, 0, 0, 1]),
                lambda r: r['sections'][0]['options'][0].update(image='../native.png'),
                lambda r: r.update(paletteClass=None),
                lambda r: r['sections'][0]['options'].append(copy.deepcopy(r['sections'][0]['options'][0])),
            ]
            for mutate in mutations:
                companion = copy.deepcopy(rows[0]); mutate(companion)
                with self.assertRaises(ValueError):
                    companion_complex_swatches(metadata, images, rows, [companion], directory)
            with self.assertRaises(ValueError):
                companion_complex_swatches(metadata, images, rows, [rows[0], rows[0]], directory)

    def test_dynamic_complex_option_retains_its_native_non_multicolor_flag(self):
        with tempfile.TemporaryDirectory() as folder:
            rows, metadata, images, directory = self.complex_fixture(folder)
            rows[0]['sections'][0]['options'][0]['multi'] = False
            companion = copy.deepcopy(rows[0])
            result, _ = companion_complex_swatches(metadata, images, rows, [companion], directory)
            index = result['families']['type:fixture'][0]['values'][0][1]
            self.assertNotEqual(index, 0)
            companion['sections'][0]['options'][0]['multi'] = True
            with self.assertRaises(ValueError):
                companion_complex_swatches(metadata, images, rows, [companion], directory)

    def fixture(self):
        customization = {'color': 'standard.orange', 'style': 'native-style',
                         'unknown': {'keep': True}}
        option = {'customization': customization, 'label': 'ORANGE',
                  'slider': True, 'rgba': [.5, .5, .5, 1],
                  'fraction': .5, 'fullname': 'standard.orange',
                  'stops': [[1, 1, 1, 1], [.5, .5, .5, 1], [0, 0, 0, 1]],
                  'percentTokens': [{**customization, 'color': 'standard.orange' if p == 50
                                    else f'standard.orange:{p / 100:.2f}'} for p in range(101)],
                  'samples': [{'fraction': f, 'rgba': [1-f, 1-f, 1-f, 1]} for f in [0, .25, .5, .75, 1]]}
        rows = [{'family': 'type:fixture', 'baseline': {**customization, 'color': 'other'},
                 'pigmentUI': True, 'pigmentEditOption': True, 'paletteClass': 'NativeFixturePalette',
                 'sections': [{'mode': 10, 'options': [option]}]}]
        profiles = [{'family': 'type:fixture', 'options': {'color': [{'value': 'standard.orange'}]}}]
        return rows, profiles

    def test_missing_legacy_gradient_keeps_selection_but_never_invents_shades(self):
        rows, profiles = self.fixture()
        with tempfile.TemporaryDirectory() as folder:
            metadata, _ = build(rows, profiles, Path(folder))
            self.assertEqual(metadata['pigments'][0]['base'], 'standard.orange')
            rows[0]['sections'][0]['options'][0]['stops'] = [None, None, None]
            metadata, _ = build(rows, profiles, Path(folder))
            self.assertEqual(metadata['pigments'], [{'rgba': [.5, .5, .5, 1]}])

    def test_native_serialization_other_fields_and_gradient_must_match(self):
        rows, profiles = self.fixture()
        mutations = [
            lambda o: o['percentTokens'][50].update(color='standard.orange:0.50'),
            lambda o: o['percentTokens'][25].update(style='unexpected'),
            lambda o: o['samples'][1].update(rgba=[0, 0, 0, 1]),
            lambda o: o.update(rgba=[float('nan'), 0, 0, 1]),
            lambda o: o.update(customization={**o['customization'], 'style': 'unexpected'}),
            lambda o: o.update(customization={**o['customization'], 'color': 'invented'}),
        ]
        with tempfile.TemporaryDirectory() as folder:
            for mutate in mutations:
                changed = copy.deepcopy(rows)
                mutate(changed[0]['sections'][0]['options'][0])
                with self.assertRaises(ValueError):
                    build(changed, profiles, Path(folder))

    def test_non_pigment_controllers_remain_generic(self):
        rows, profiles = self.fixture()
        rows[0]['pigmentUI'] = False
        with tempfile.TemporaryDirectory() as folder:
            metadata, images = build(rows, profiles, Path(folder))
            self.assertEqual(metadata['families'], {})
            self.assertEqual(images, {})

    def test_unattached_view_cannot_publish_white_fallback_as_native_palette(self):
        rows, profiles = self.fixture()
        with tempfile.TemporaryDirectory() as folder:
            for value in [None, '']:
                rows[0]['paletteClass'] = value
                with self.assertRaisesRegex(ValueError, 'controller palette unavailable'):
                    build(rows, profiles, Path(folder))
            rows[0].pop('pigmentEditOption')
            with self.assertRaisesRegex(ValueError, 'controller palette unavailable'):
                build(rows, profiles, Path(folder))

    def test_native_image_and_primary_color_remain_separate_and_deduplicated(self):
        rows, profiles = self.fixture()
        option = rows[0]['sections'][0]['options'][0]
        option['image'] = 'native.png'
        second = copy.deepcopy(rows[0])
        second['family'] = 'type:second'
        profiles.append({**profiles[0], 'family': 'type:second'})
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder) / 'native.png'
            Image.new('RGBA', (2, 2), (255, 64, 0, 255)).save(path)
            metadata, images = build([rows[0], second], profiles, Path(folder))
            self.assertEqual(len(images), 1)
            self.assertEqual(len(metadata['pigments']), 1)
            pigment = metadata['pigments'][0]
            self.assertEqual(pigment['rgba'], [.5, .5, .5, 1])
            self.assertTrue(pigment['shadeImageMask'])
            self.assertEqual(pigment['image'], hashlib.sha256(path.read_bytes()).hexdigest() + '.png')
            option['image'] = '../native.png'
            with self.assertRaisesRegex(ValueError, 'swatch path'):
                build(rows, profiles, Path(folder))

    def test_multicolor_image_cannot_be_tinted_as_a_native_shade_mask(self):
        rows, profiles = self.fixture()
        option = rows[0]['sections'][0]['options'][0]
        option['image'] = 'native.png'
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder) / 'native.png'
            image = Image.new('RGBA', (2, 2), (255, 64, 0, 255))
            image.putpixel((0, 0), (0, 64, 255, 255))
            image.save(path)
            with self.assertRaisesRegex(ValueError, 'not monochrome'):
                build(rows, profiles, Path(folder))
            # The same native image remains usable when the option exposes no
            # shade: retain its pixels rather than inventing recoloring.
            option['slider'] = False
            metadata, _ = build(rows, profiles, Path(folder))
            self.assertNotIn('shadeImageMask', metadata['pigments'][0])


if __name__ == '__main__':
    unittest.main()
