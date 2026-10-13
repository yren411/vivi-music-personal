"""Derive Android branding from the unchanged official PNG (requires Pillow/numpy)."""
from pathlib import Path
import hashlib

import numpy as np
from PIL import Image

ROOT = Path(__file__).resolve().parents[1]
RES = ROOT / 'app/src/main/res'
SOURCE = ROOT / 'branding/Tinium Logo.png'
original_bytes = SOURCE.read_bytes()
logo = Image.open(SOURCE).convert('RGBA')
assert logo.getextrema()[3][0] == 0, 'Official logo must retain transparency'

def fit(size, content_size=None, radius=None, monochrome=False):
    if radius is not None:
        ys, xs = np.nonzero(np.asarray(logo.getchannel('A')))
        max_radius = np.sqrt((xs - (logo.width-1)/2)**2 + (ys - (logo.height-1)/2)**2).max()
        scale = radius / max_radius
    else:
        content_size = content_size or min(size)
        scale = min(content_size / logo.width, content_size / logo.height)
    resized = logo.resize((round(logo.width * scale), round(logo.height * scale)), Image.Resampling.LANCZOS)
    if monochrome:
        mask = resized.getchannel('A')
        resized = Image.new('RGBA', resized.size, 'white')
        resized.putalpha(mask)
    canvas = Image.new('RGBA', size)
    canvas.alpha_composite(resized, ((size[0] - resized.width)//2, (size[1] - resized.height)//2))
    return canvas

def save(image, relative):
    path = ROOT / relative
    path.parent.mkdir(parents=True, exist_ok=True)
    image.save(path, optimize=True)

# The exact source remains available to Compose; legacy IDs keep their consumers working.
ui_logo = RES / 'drawable-nodpi/tinium_logo.png'
ui_logo.parent.mkdir(parents=True, exist_ok=True)
ui_logo.write_bytes(original_bytes)
for name in ('icon', 'vivi_music_small_icon'):
    save(fit((512, 512)), f'app/src/main/res/drawable/{name}.png')

for density, scale in (('mdpi', 1), ('hdpi', 1.5), ('xhdpi', 2), ('xxhdpi', 3), ('xxxhdpi', 4)):
    size = round(108 * scale)
    # Fit every non-transparent pixel inside the 66 dp adaptive safe circle,
    # with a 3 dp margin for resampling. No mask, shadow or decoration is added.
    foreground = fit((size, size), radius=30 * scale)
    mask = fit((size, size), radius=30 * scale, monochrome=True)
    save(foreground, f'app/src/main/res/mipmap-{density}/ic_launcher_foreground.png')
    save(mask, f'app/src/main/res/mipmap-{density}/ic_launcher_monochrome.png')
    save(Image.new('RGBA', (size, size)), f'app/src/main/res/mipmap-{density}/ic_launcher_background.png')
    legacy_size = round(48 * scale)
    legacy = fit((legacy_size, legacy_size), radius=22 * scale)
    save(legacy, f'app/src/main/res/mipmap-{density}/ic_launcher.png')
    save(legacy, f'app/src/main/res/mipmap-{density}/ic_launcher_round.png')
    save(foreground, f'app/src/main/res/drawable-{density}/ic_launcher_foreground.png')
    notification_size = round(24 * scale)
    notification = fit((notification_size, notification_size), content_size=round(22 * scale), monochrome=True)
    save(notification, f'app/src/main/res/drawable-{density}/vivimusicnotification.png')

# Remove conflicting fallback formats for these same Android resource names.
for relative in ('drawable/ic_launcher_foreground.webp', 'drawable/ic_launcher_monochrome.webp', 'drawable/vivimusicnotification.png'):
    path = RES / relative
    if path.exists():
        path.unlink()

# Existing wrapped artwork is retained beneath a source-derived branding header.
# The old wordmark occupies the top band; only that band is replaced.
for name in ('viviwrapped_v1', 'viviwrapped_v2'):
    original_path = ROOT / f'branding/upstream/{name}.jpg'
    if original_path.exists():
        artwork = Image.open(original_path).convert('RGBA')
        band_height = round(artwork.height * 0.25)
        # Keep the black background already used by the original cover.
        artwork.paste((0, 0, 0, 255), (0, 0, artwork.width, band_height))
        brand = fit((artwork.width, band_height), content_size=round(band_height * 0.84))
        artwork.alpha_composite(brand, (0, 0))
        save(artwork, f'app/src/main/res/drawable/{name}.png')
        old_path = RES / f'drawable/{name}.jpg'
        if old_path.exists():
            old_path.unlink()

banner_path = RES / 'mipmap-xhdpi/tv_banner.png'
banner_size = Image.open(banner_path).size
save(fit(banner_size, content_size=round(banner_size[1] * 0.84)), 'app/src/main/res/mipmap-xhdpi/tv_banner.png')
save(fit((512, 512), radius=234), 'fastlane/metadata/android/en-US/images/icon.png')
save(fit((512, 512), radius=234), 'assets/ic_launcher-playstore.png')
official_banner = ROOT / 'assets/bannerofficial.png'
size = Image.open(official_banner).size
save(fit(size, content_size=round(size[1] * 0.84)), 'assets/bannerofficial.png')

assert SOURCE.read_bytes() == original_bytes, 'Source logo was modified'
print('Generated Tinium assets from', hashlib.sha256(original_bytes).hexdigest())
