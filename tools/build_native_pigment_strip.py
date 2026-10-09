"""Validate and preserve the original rectangular UIKit action glyphs."""
import io
import math
from PIL import Image
from build_native_pigments import native_image


def build(capture, directory):
    if (not isinstance(capture, dict) or type(capture.get('version')) is not int or
            capture['version'] != 1 or capture.get('cellClass') != 'NTKCFaceDetailPigmentEditOptionCell' or
            not isinstance(capture.get('rows'), list) or len(capture['rows']) != 2):
        raise ValueError('Invalid native strip capture')
    metadata, images, seen = {}, {}, set()
    for row in capture['rows']:
        if not isinstance(row, dict):
            raise ValueError('Invalid native strip row')
        name = {'_plusImage': 'plus', '_dividerImage': 'divider'}.get(row.get('selector'))
        metrics = [row.get(k) for k in ('width', 'height', 'scale')]
        if (name is None or name in seen or
                any(type(v) not in (int, float) or not math.isfinite(v) for v in metrics) or
                any(not 0 < v <= 96 for v in metrics[:2]) or not 1 <= metrics[2] <= 4 or
                type(row.get('renderingMode')) is not int or row['renderingMode'] != 0):
            raise ValueError('Invalid native strip geometry')
        seen.add(name)
        leaf, data = native_image(directory, row.get('image'), square=False)
        if leaf != row['image']:
            raise ValueError('Native strip hash mismatch')
        with Image.open(io.BytesIO(data)) as image:
            if image.size != tuple(round(v * metrics[2]) for v in metrics[:2]):
                raise ValueError('Native strip PNG geometry mismatch')
            if not image.convert('RGBA').getchannel('A').getbbox():
                raise ValueError('Empty native strip artwork')
        metadata.update({name + 'Image': leaf, name + 'Width': metrics[0], name + 'Height': metrics[1]})
        images[leaf] = data
    return metadata, images
