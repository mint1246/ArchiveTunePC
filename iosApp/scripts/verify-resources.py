#!/usr/bin/env python3
"""Reject Android vector dimension aliases and non-hex colors unsupported by Compose's Apple loader."""
from pathlib import Path
import re
import xml.etree.ElementTree as ET

root = Path(__file__).resolve().parents[2]
namespace = '{http://schemas.android.com/apk/res/android}'
hex_color = re.compile(r'^#(?:[0-9a-fA-F]{6}|[0-9a-fA-F]{8})$')
errors = []
for path in sorted((root / 'shared/src/commonMain/composeResources/drawable').glob('*.xml')):
    vector = ET.parse(path).getroot()
    if vector.tag != 'vector':
        continue
    for dimension in ('width', 'height'):
        value = vector.get(namespace + dimension, '')
        if not re.fullmatch(r'\d+(?:\.\d+)?(?:dp|px)', value):
            errors.append(f'{path.name}: {dimension}={value!r} is unsupported by the Apple vector loader')
    for elem in vector.iter():
        for attr in ('fillColor', 'strokeColor', 'tint'):
            val = elem.get(namespace + attr)
            if val is not None and not hex_color.fullmatch(val):
                errors.append(f'{path.name}: {attr}={val!r} is unsupported by the Apple vector loader (must be #RRGGBB or #AARRGGBB)')
if errors:
    raise SystemExit('\n'.join(errors))
print('Shared vector dimensions and colors are compatible with the Apple loader.')
