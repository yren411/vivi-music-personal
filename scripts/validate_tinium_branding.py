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
full_color = np.asarray(Image.open(ROOT / 'branding/Tinium Logo with Background.png').convert('RGBA'))
source_opaque = full_color[full_color[:, :, 3] == 255][:, :3]
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
    # The gradient disk is the full-color icon, not the themed/status silhouette.
    # A disk alpha mask alone would produce an unrecognizable solid white circle.
    assert not np.array_equal(pixels[:, :, 3], monochrome[:, :, 3])
    assert 0 < np.count_nonzero(monochrome[:, :, 3]) < np.count_nonzero(pixels[:, :, 3])
    assert not np.array_equal(pixels[:, :, :3], monochrome[:, :, :3])
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

# Prominent background branding preserves the owner-supplied file exactly.
background_path = ROOT / 'branding/Tinium Logo with Background.png'
assert hashlib.sha256(background_path.read_bytes()).hexdigest() == '639e64c5844484b4dec27bd1f3e38df3258c01946b33789a478fd423a4275be3'
assert background_path.read_bytes() == (RES / 'drawable-nodpi/tinium_logo_background.png').read_bytes()
background_logo = Image.open(background_path).convert('RGBA')
assert background_logo.size == (1080, 1080) and background_logo.getchannel('A').getextrema() == (0, 255)
ns = '{http://schemas.android.com/apk/res/android}'
splash_style = ET.parse(RES / 'values/tinium_splash.xml').getroot().find("style[@name='Theme.vivimusic.Starting']")
assert splash_style.get('parent') == 'Theme.SplashScreen'
assert splash_style.find("item[@name='windowSplashScreenAnimatedIcon']").text == '@drawable/tinium_splash_logo'
wrapper = ET.parse(RES / 'drawable/tinium_splash_logo.xml').getroot()
canvas, artwork = wrapper.findall('item')
assert canvas.get(ns+'width') == canvas.get(ns+'height') == '288dp'
assert canvas.find('shape/solid').get(ns+'color') == '@android:color/transparent'
assert artwork.get(ns+'width') == artwork.get(ns+'height') == '180dp'
assert artwork.get(ns+'gravity') == 'center' and artwork.get(ns+'drawable') == '@drawable/tinium_logo_background'
ys, xs = np.nonzero(np.asarray(background_logo.getchannel('A')))
radius = np.sqrt((xs-539.5)**2+(ys-539.5)**2).max() * 180 / 1080
assert radius < 96, 'Background artwork would be clipped by the splash safe-circle mask'
component = (ROOT / 'app/src/main/kotlin/com/music/vivi/ui/component/TiniumBrandLogo.kt').read_text()
assert 'R.drawable.tinium_logo_background' in component and 'ContentScale.Fit' in component and 'colorFilter = null' in component
for relative in ('MainActivity.kt', 'WelcomeActivity.kt', 'ui/screens/settings/AboutScreen.kt', 'ui/screens/wrapped/pages/WrappedIntro.kt', 'ui/screens/wrapped/pages/ConclusionPage.kt'):
    code = (ROOT / 'app/src/main/kotlin/com/music/vivi' / relative).read_text()
    assert 'TiniumBrandLogo(' in code
main = (ROOT / 'app/src/main/kotlin/com/music/vivi/MainActivity.kt').read_text()
assert 'TiniumBrandLogo(modifier = Modifier.size(36.dp))' in main and 'currentTitleRes?.let { stringResource(it) }' in main
compact = (ROOT / 'app/src/main/kotlin/com/music/vivi/ui/component/LyricsImageCard.kt').read_text()
assert 'R.drawable.tinium_logo' in compact and '.size(16.dp)' in compact
manifest = ET.parse(ROOT / 'app/src/main/AndroidManifest.xml').getroot()
activities = manifest.findall('application/activity')
for name in ('.MainActivity', '.WelcomeActivity'):
    assert next(a for a in activities if a.get(ns+'name') == name).get(ns+'theme') == '@style/Theme.vivimusic.Starting'
assert ET.parse(RES / 'values-night/tinium_splash.xml').getroot().find('color').text == '#121212'
assert ET.parse(RES / 'mipmap-anydpi-v26/ic_launcher.xml').getroot().find('monochrome') is not None
print('PASS: unchanged background PNG bytes; tint-free Fit UI; retained compact mark; both startup activities; splash artwork radius', round(radius, 2), 'dp < 96dp safe radius; existing adaptive/themed icons.')

for density, scale in (('mdpi', 1), ('hdpi', 1.5), ('xhdpi', 2), ('xxhdpi', 3), ('xxxhdpi', 4)):
    legacy = np.asarray(Image.open(RES / f'mipmap-{density}/ic_launcher.png').convert('RGBA'))
    assert np.array_equal(legacy, np.asarray(Image.open(RES / f'mipmap-{density}/ic_launcher_round.png').convert('RGBA')))
    assert legacy[int(legacy.shape[0]*0.1), legacy.shape[1]//2, 3] > 200
    assert legacy[0, 0, 3] == 0
for relative in ('app/src/main/ic_launcher-playstore.png', 'assets/ic_launcher-playstore.png', 'fastlane/metadata/android/en-US/images/icon.png'):
    icon = Image.open(ROOT / relative).convert('RGBA')
    assert icon.size == (512, 512)
    expected = np.asarray(Image.open(ROOT / 'branding/Tinium Logo with Background.png').convert('RGBA').resize((512,512), Image.Resampling.LANCZOS))
    actual = np.asarray(icon)
    assert np.array_equal(actual[:,:,3], expected[:,:,3])
    assert np.array_equal(actual[expected[:,:,3] > 0], expected[expected[:,:,3] > 0])
update_notification = (ROOT / 'app/src/main/kotlin/com/music/vivi/vivimusic/UpdateNotificationHelper.kt').read_text()
media_notification = (ROOT / 'app/src/main/kotlin/com/music/vivi/playback/BrandedMediaNotificationProvider.kt').read_text()
assert '.setLargeIcon(Icon.createWithResource(context, R.drawable.tinium_logo_background))' in update_notification
assert 'getLargeIcon() != null' in media_notification and 'R.drawable.tinium_logo_background' in media_notification
print('PASS: background launcher/store icons; alpha-only themed/status silhouettes; full-color notification branding with album artwork preserved.')
