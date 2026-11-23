#!/usr/bin/env python3
"""Reject Android vector dimension aliases unsupported by Compose's Apple loader."""
from pathlib import Path
import re
import xml.etree.ElementTree as ET

root = Path(__file__).resolve().parents[2]
namespace = '{http://schemas.android.com/apk/res/android}'
errors = []
for path in (root / 'shared/src/commonMain/composeResources/drawable').glob('*.xml'):
    vector = ET.parse(path).getroot()
    if vector.tag != 'vector':
        continue
    for dimension in ('width', 'height'):
        value = vector.get(namespace + dimension, '')
        if not re.fullmatch(r'\d+(?:\.\d+)?(?:dp|px)', value):
            errors.append(f'{path.name}: {dimension}={value!r} is unsupported by the Apple vector loader')
if errors:
    raise SystemExit('\n'.join(errors))
print('Shared vector dimensions are compatible with the Apple loader.')
