import base64
import json
import plistlib
import unittest
from build_synthetic_activation_fixtures import DESTINATION, fixtures


class SyntheticActivationTest(unittest.TestCase):
    def test_checked_in_resources_are_exact_generator_output(self):
        for name, data in fixtures().items():
            self.assertEqual((DESTINATION / name).read_bytes(), data, name)

    def test_wire_container_and_independent_binary_references_agree(self):
        data = fixtures()
        wire = plistlib.loads(data['activation-resolved-body-2202.bin'])
        self.assertEqual(wire, plistlib.loads(data['activation-record-2202-canonical.bplist']))
        self.assertEqual(wire['ActivationRecord'], plistlib.loads(data['activation-record-inner-2202.bplist']))

    def test_token_and_certificates_are_deliberately_synthetic(self):
        record = plistlib.loads(fixtures()['activation-record-inner-2202.bplist'])
        token = json.loads(base64.b64decode(record['AccountToken']))
        self.assertEqual(token['InternationalMobileEquipmentIdentity'], '000000000000000')
        self.assertEqual(token['SerialNumber'], 'SYNTHETIC-TEST')
        for key in ['AccountTokenCertificate', 'DeviceCertificate', 'UniqueDeviceCertificate']:
            self.assertTrue(record[key].startswith(b'SYNTHETIC'))


if __name__ == '__main__':
    unittest.main()
