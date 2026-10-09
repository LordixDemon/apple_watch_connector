import tempfile
import unittest
import zipfile
from pathlib import Path
from verify_native_preview_apk import PACKAGED, verify


class NativePreviewApkTest(unittest.TestCase):
    def test_exact_generation_and_refusal_of_stale_content_missing_and_extra_files(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            assets = root / 'assets'
            assets.mkdir()
            for directory in ['configured_previews', 'configured_preview_indexes', 'pigment_swatches', 'previews']:
                (assets / directory).mkdir()
            (assets / 'configured_previews.json').write_bytes(b'root')
            (assets / 'watchos_26_2.json').write_bytes(b'profiles')
            (assets / 'previews/default.png').write_bytes(b'native default')
            (assets / 'edit_sections_26_2.json').write_bytes(b'editor')
            (assets / 'pigments_26_2.json').write_bytes(b'pigments')
            (assets / 'pigment_swatches/a.png').write_bytes(b'swatch')
            (assets / 'configured_previews/a.nfp').write_bytes(b'frame')
            (assets / 'configured_preview_indexes/a.json').write_bytes(b'index')
            base = {PACKAGED + str(path.relative_to(assets)): path.read_bytes()
                    for path in assets.rglob('*') if path.is_file()}
            base['lib/arm64-v8a/libapp.so'] = b'ordinary application'
            apk = root / 'app.apk'

            def write(entries):
                with zipfile.ZipFile(apk, 'w') as archive:
                    for name, data in entries.items():
                        archive.writestr(name, data)

            write(base)
            self.assertEqual(verify(apk, assets)['managedAssetCount'], 8)
            for entries in [
                {**base, PACKAGED + 'configured_previews/a.nfp': b'wrong'},
                {**base, PACKAGED + 'edit_sections_26_2.json': b'wrong!'},
                {**base, PACKAGED + 'pigments_26_2.json': b'wrong!!!'},
                {**base, PACKAGED + 'pigment_swatches/a.png': b'wrong!'},
                {**base, PACKAGED + 'previews/default.png': b'wrong default'},
                {**base, PACKAGED + 'watchos_26_2.json': b'wrong profiles'},
                {k: v for k, v in base.items() if not k.endswith('default.png')},
                {**base, PACKAGED + 'pigment_swatches/stale.png': b'stale'},
                {k: v for k, v in base.items() if not k.endswith('a.nfp')},
                {**base, PACKAGED + 'configured_previews/stale.webp': b'stale'},
                {**base, 'assets/flutter_assets/test/fixtures/native.json': b'fixture'},
                {**base, 'lib/arm64-v8a/libapp.so': b'NATIVE_FACE_PROFILE_DONE'},
            ]:
                write(entries)
                with self.assertRaises(ValueError):
                    verify(apk, assets)
            write({**base, 'lib/arm64-v8a/libapp.so': b'NATIVE_FACE_PROFILE_DONE'})
            self.assertTrue(verify(apk, assets, allow_profile=True)['profileAllowed'])


if __name__ == '__main__':
    unittest.main()
