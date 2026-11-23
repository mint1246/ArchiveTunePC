#!/usr/bin/env python3
"""Compare captured frames without masking content or correcting layout differences."""
import argparse
import hashlib
import json
from pathlib import Path
from PIL import Image, ImageChops, ImageEnhance


def compare(android_path, ios_path, output, android_scale=3, ios_scale=1):
    def frame(path, scale):
        if scale <= 0:
            raise ValueError('Scale must be positive')
        source = Image.open(path).convert('RGB')
        width, height = source.size
        if width % scale or height % scale:
            raise ValueError(f'{path}: capture dimensions do not divide by pixel density')
        return source.resize((width // scale, height // scale), Image.Resampling.LANCZOS), {
            'path': str(path), 'sha256': hashlib.sha256(Path(path).read_bytes()).hexdigest(),
            'physicalSize': [width, height], 'pixelDensity': scale,
        }
    android, android_info = frame(android_path, android_scale)
    ios, ios_info = frame(ios_path, ios_scale)
    if android.size != ios.size:
        raise ValueError(f'Logical viewports differ: {android.size} vs {ios.size}; capture matched viewports')
    output = Path(output)
    output.mkdir(parents=True, exist_ok=True)
    diff = ImageChops.difference(android, ios)
    pixels = list(diff.getdata())
    count = len(pixels)
    changed = sum(any(channel != 0 for channel in pixel) for pixel in pixels)
    report = {
        'android': android_info, 'ios': ios_info, 'logicalSize': list(android.size),
        'changedPixels': changed, 'totalPixels': count,
        'changedFraction': changed / count,
        'meanAbsoluteChannelError': sum(sum(pixel) for pixel in pixels) / (count * 3),
        'maximumChannelError': max(max(pixel) for pixel in pixels),
        'differenceBounds': diff.getbbox(),
        'identicalPixels': changed == 0,
        'stateVerified': False,
        'note': 'No UI differences are masked. Matching pixels alone do not certify behavior or feature parity.',
    }
    android.save(output / 'android.png')
    ios.save(output / 'ios.png')
    diff.save(output / 'difference.png')
    ImageEnhance.Brightness(diff).enhance(4).save(output / 'difference-x4.png')
    Image.blend(android, ios, .5).save(output / 'overlay.png')
    (output / 'metrics.json').write_text(json.dumps(report, indent=2) + '\n')
    return report


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--android', required=True, type=Path)
    parser.add_argument('--ios', required=True, type=Path)
    parser.add_argument('--output', required=True, type=Path)
    parser.add_argument('--android-scale', type=int, default=3)
    parser.add_argument('--ios-scale', type=int, default=1)
    args = parser.parse_args()
    print(json.dumps(compare(args.android, args.ios, args.output, args.android_scale, args.ios_scale), indent=2))


if __name__ == '__main__':
    main()
