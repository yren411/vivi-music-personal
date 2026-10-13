"""Validate packaged artwork properties without claiming Android UI rendering.

Run with Python, Pillow and numpy: python scripts/validate_tinium_branding.py
"""
from pathlib import Path
import hashlib
import re
import xml.etree.ElementTree as ET

import numpy as np
from PIL import Image

ROOT = Path(__file__).resolve().parents[1]
RES = ROOT / 'app/src/main/res'
source_path = ROOT / 'branding/Tinium Logo.png'
assert hashlib.sha256(source_path.read_bytes()).hexdigest() == '83d5f10d7fcf41a3e59e52d330507c0e42ef2b08a5f51ba23505e0ea44d9bf43'
source = np.asarray(Image.open(source_path).convert('RGBA'))
assert np.array_equal(source, np.asarray(Image.open(RES / 'drawable-nodpi/tinium_logo.png').convert('RGBA')))
source_opaque = source[source[:, :, 3] == 255][:, :3]
colors, counts = np.unique(source_opaque, axis=0, return_counts=True)
brand_colors = colors[np.argsort(counts)[-3:]]

for density, scale in (('mdpi', 1), ('hdpi', 1.5), ('xhdpi', 2), ('xxhdpi', 3), ('xxxhdpi', 4)):
    foreground = Image.open(RES / f'mipmap-{density}/ic_launcher_foreground.png').convert('RGBA')
    assert foreground.size == (round(108 * scale),) * 2
    pixels = np.asarray(foreground)
    ys, xs = np.nonzero(pixels[:, :, 3])
    radius = np.sqrt((xs-(foreground.width-1)/2)**2 + (ys-(foreground.height-1)/2)**2).max()
    assert radius <= 33 * scale, (density, radius)
    assert xs.max() - xs.min() + 1 >= 48 * scale, (density, 'logo too small')
    # Downsampling small stars can introduce a few rounding values at edges;
    # their palette must remain close to the original, rather than theme-tinted.
    opaque_colors = pixels[pixels[:, :, 3] >= 200][:, :3].astype(int)
    assert all(np.abs(opaque_colors - color.astype(int)).max(axis=1).min() <= 6 for color in brand_colors)
    monochrome = np.asarray(Image.open(RES / f'mipmap-{density}/ic_launcher_monochrome.png').convert('RGBA'))
    assert np.array_equal(pixels[:, :, 3], monochrome[:, :, 3])
    assert np.all(monochrome[monochrome[:, :, 3] > 0][:, :3] == 255)
    background = Image.open(RES / f'mipmap-{density}/ic_launcher_background.png').convert('RGBA')
    assert background.getchannel('A').getextrema() == (0, 0)
    for name in ('ic_launcher', 'ic_launcher_round'):
        assert Image.open(RES / f'mipmap-{density}/{name}.png').size == (round(48 * scale),) * 2
    notification = np.asarray(Image.open(RES / f'drawable-{density}/vivimusicnotification.png').convert('RGBA'))
    assert notification.shape[:2] == (round(24 * scale),) * 2
    assert np.all(notification[notification[:, :, 3] > 0][:, :3] == 255)
    assert notification[:, :, 3].min() == 0 and notification[:, :, 3].max() == 255

for name in ('viviwrapped_v1', 'viviwrapped_v2'):
    original = np.asarray(Image.open(ROOT / f'branding/upstream/{name}.jpg').convert('RGBA'))
    generated = np.asarray(Image.open(RES / f'drawable/{name}.png').convert('RGBA'))
    band_height = round(original.shape[0] * 0.25)
    assert np.array_equal(original[band_height:], generated[band_height:]), 'Wrapped artwork changed outside its branding band'
    assert not (RES / f'drawable/{name}.jpg').exists()

preserved = {'developer_name', 'wrapped_special_thanks', 'canvas_source_auto_desc', 'canvas_source_vivimusic', 'canvas_source_vivimusic_desc', 'upstream_developer_credit', 'upstream_feature_credit', 'upstream_vivi_version'}
for path in (ROOT / 'app/src').rglob('*.xml'):
    doc = ET.fromstring(path.read_bytes())
    if '/values' in path.as_posix():
        for item in doc.findall('string'):
            name = item.get('name')
            text = ''.join(item.itertext())
            assert name in preserved or not re.search(r'vivi', text, re.I), (path, name, 'unexpected old identity')
            if name == 'app_name':
                assert text == 'Tinium'

gradle = (ROOT / 'app/build.gradle.kts').read_text(encoding='utf-8')
assert 'applicationId = "com.yren411.tinium"' in gradle and 'applicationIdSuffix' not in gradle
assert 'namespace = "com.music.vivi"' in gradle
print('PASS: exact source pixels; five icon densities; full-color palette; safe-area geometry; alpha-only specialized assets; untouched lower Wrapped artwork; XML and branding audit; Tinium identity.')
