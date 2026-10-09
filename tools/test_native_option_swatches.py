import copy
import unittest
import numpy as np
from build_native_option_swatches import tint_colors, interpolate
from prepare_native_swatch_bindings import rgba


class NativeOptionSwatchesTest(unittest.TestCase):
    def setUp(self):
        self.source = 'a' * 64 + '.png'
        self.row = dict(customization=dict(color='blue', style='simple'), mode=11,
                        label='Simple', optionClass='NativeOption', size=[1, 1], scale=1,
                        drawOperations=[dict(image=self.source, operation='rect',
                                             geometry=[0, 0, 1, 1], tint=[1.001, .5, .5, .13])])
        self.proof = dict(axis='color', sources={self.source:dict(role='foreground')})

    def test_preserves_native_extended_color_and_fixed_source_alpha(self):
        colors = tint_colors(self.row, self.proof, self.row)
        self.assertEqual(colors['foreground'].tolist(), [1.001, .5, .5, .13])
        for value in ([0, 0, 0, -1], [0, 0, 0, 1.01], [float('nan'), 0, 0, 1]):
            with self.assertRaises(ValueError):
                rgba(value)

    def test_rejects_changed_native_option_geometry_and_other_custom_fields(self):
        changed = copy.deepcopy(self.row)
        changed['drawOperations'][0]['geometry'][1] = 1
        with self.assertRaises(ValueError):
            tint_colors(changed, self.proof, self.row)
        changed = copy.deepcopy(self.row)
        changed['customization']['dial'] = 'circular'
        with self.assertRaises(ValueError):
            tint_colors(changed, self.proof, self.row)

    def test_native_interpolation_retains_half_byte_precision_and_source_gamut(self):
        stops = np.array([[1.1, 0, 0, 1], [1.001, .5, 0, 1], [.5, 1, 0, 1]])
        np.testing.assert_array_equal(interpolate(stops, 50), stops[1])
        self.assertGreater(interpolate(stops, 25)[0], 1)
        self.assertEqual(interpolate(stops, 75)[1], .75)


if __name__ == '__main__':
    unittest.main()
