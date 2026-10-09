import unittest
import numpy as np
from build_native_component_program import evaluate, pack


class NativeComponentProgramTest(unittest.TestCase):
    def test_half_byte_rounding_does_not_promote_the_adjacent_lower_double(self):
        rows = np.array([[[0, 0, 0, 255, 1, 1, 1]]], dtype=np.uint8)
        colors = {'role': [np.nextafter(.5, 0), .5, np.nextafter(.5, 1), 1]}
        self.assertEqual(evaluate(rows, ['role'], colors).tolist(), [[[0, 1, 1, 255]]])

    def test_basis_rejects_alpha_and_nonlinear_occlusion(self):
        black = np.array([[[10, 20, 30, 128]]], dtype=np.uint8)
        for white in (np.array([[[9, 20, 30, 128]]], dtype=np.uint8),
                      np.array([[[10, 20, 30, 127]]], dtype=np.uint8)):
            with self.assertRaises(ValueError):
                pack({'black': black, 'role': white}, ['role'])


if __name__ == '__main__':
    unittest.main()
