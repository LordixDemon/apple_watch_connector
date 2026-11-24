import struct
import unittest

from compare_watch_firmware import architecture_slice, compare, instruction_differences, sections


def thin32(payload=b"pairingVersion\0ChipID\0"):
    offset = 28 + 56 + 68
    header = struct.pack("<7I", 0xFEEDFACE, 0x0200000C, 1, 2, 1, 124, 0)
    segment = struct.pack("<II16s8I", 1, 124, b"__TEXT", 0x1000, offset + len(payload),
                          0, offset + len(payload), 5, 5, 1, 0)
    section = struct.pack("<16s16s9I", b"__cstring", b"__TEXT", 0x1000 + offset,
                          len(payload), offset, 0, 0, 0, 2, 0, 0)
    return header + segment + section + payload


class FirmwareComparisonTest(unittest.TestCase):
    def test_arm64e_and_fat64_headers(self):
        payload = b"pairingVersion\0"
        offset = 32 + 72 + 80
        header = struct.pack("<8I", 0xFEEDFACF, 0x0100000C, 0x80000002, 6, 1, 152, 0, 0)
        segment = struct.pack("<II16s4Q4I", 0x19, 152, b"__TEXT", 0x100000000,
                              offset + len(payload), 0, offset + len(payload), 5, 5, 1, 0)
        section = struct.pack("<16s16s2Q8I", b"__cstring", b"__TEXT", 0x100000000 + offset,
                              len(payload), offset, 0, 0, 0, 2, 0, 0, 0)
        data = header + segment + section + payload
        fat = struct.pack(">II", 0xCAFEBABF, 1) + struct.pack(">IIQQII", 0x0100000C,
                          0x80000002, 40, len(data), 0, 0) + data
        self.assertEqual(architecture_slice(fat, "arm64e"), data)
        self.assertEqual(sections(data)["__TEXT.__cstring"], payload)

    def test_selects_matching_universal_slice(self):
        data = thin32()
        fat = struct.pack(">7I", 0xCAFEBABE, 1, 0x0200000C, 1, 28, len(data), 0) + data
        self.assertEqual(architecture_slice(fat, "arm64_32"), data)
        self.assertTrue(compare(data, fat, "arm64_32")["slice_equal"])
        with self.assertRaises(ValueError):
            architecture_slice(fat, "arm64e")

    def test_reports_actual_string_changes(self):
        report = compare(thin32(), thin32(b"pairingVersion\0NewKey\0"), "arm64_32")
        self.assertEqual(report["strings"]["__cstring"]["left_only"], ["ChipID"])
        self.assertEqual(report["strings"]["__cstring"]["right_only"], ["NewKey"])
        self.assertFalse(report["strings"]["__objc_methname"]["available"])

    def test_rejects_truncation_and_invalid_load_commands(self):
        data = thin32()
        with self.assertRaises(ValueError):
            sections(data[:-1])
        malformed = bytearray(data)
        struct.pack_into("<I", malformed, 32, 4)
        with self.assertRaises(ValueError):
            sections(malformed)
        fat = struct.pack(">7I", 0xCAFEBABE, 1, 0x0200000C, 1, 28, len(data) + 4, 0) + data
        with self.assertRaises(ValueError):
            architecture_slice(fat, "arm64_32")

    def test_address_candidates_preserve_instruction_registers(self):
        a = struct.pack("<I", 0x90000000)
        b = struct.pack("<I", 0x90000020)
        self.assertTrue(instruction_differences(a, b)["address_encoding_candidates_only"])
        b = struct.pack("<I", 0x90000001)
        self.assertFalse(instruction_differences(a, b)["address_encoding_candidates_only"])

    def test_branch_address_candidate_is_distinct_from_changed_opcode(self):
        branch = instruction_differences(struct.pack("<I", 0x14000000), struct.pack("<I", 0x14000001))
        self.assertTrue(branch["address_encoding_candidates_only"])
        changed_opcode = instruction_differences(struct.pack("<I", 0x14000000), struct.pack("<I", 0x94000000))
        self.assertFalse(changed_opcode["address_encoding_candidates_only"])
        constant = instruction_differences(struct.pack("<I", 0x52800000), struct.pack("<I", 0x52800020))
        self.assertFalse(constant["address_encoding_candidates_only"])


if __name__ == "__main__":
    unittest.main()
