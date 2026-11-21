"""Renders the app icon: a raised, lit speech bubble with embossed AI sparkles.

    python render_icon.py [--res DIR] [--preview FILE]

The icon is drawn from a height map (so the bevel, the highlights and the shadows are computed, not painted): the bubble and
the sparkles are raised, light comes from the top left, and a soft shadow falls to the bottom right. It writes
  * the adaptive icon's foreground at every density (res/mipmap-*/ic_launcher_foreground.png),
  * its background and a one-colour version for themed icons (res/drawable/ic_launcher_background.xml, ic_launcher_monochrome.xml),
  * a full icon for the About screen (res/drawable-nodpi/ic_aismo_logo.png) and one for the README (docs/images/aismo-icon.png).
Needs Pillow and numpy. Everything is in dp on a 108 x 108 canvas, the size of an adaptive icon layer; the launcher shows
the middle 72 x 72 and keeps the rest for its mask, so the art stays inside the central 66.
"""
import argparse, math, pathlib
import numpy as np
from PIL import Image, ImageDraw

CANVAS = 108                 # dp
SCALE = 12                   # pixels per dp for the master render
N = CANVAS * SCALE
SS = 2                       # supersampling for the shape masks

# ---------------------------------------------------------------- the shapes, as paths in dp
def bubble():
    x0, y0, x1, y1, r = 26.0, 30.0, 82.0, 68.0, 12.0
    return [
        ("M", x0 + r, y0), ("L", x1 - r, y0), ("Q", x1, y0, x1, y0 + r), ("L", x1, y1 - r), ("Q", x1, y1, x1 - r, y1),
        ("L", 52, y1), ("Q", 46, 75, 33.5, 79), ("Q", 38.5, 73, 39, y1),
        ("L", x0 + r, y1), ("Q", x0, y1, x0, y1 - r), ("L", x0, y0 + r), ("Q", x0, y0, x0 + r, y0), ("Z",),
    ]

def sparkle(cx, cy, radius, waist=0.16):
    k = radius * waist
    return [
        ("M", cx, cy - radius), ("Q", cx + k, cy - k, cx + radius, cy), ("Q", cx + k, cy + k, cx, cy + radius),
        ("Q", cx - k, cy + k, cx - radius, cy), ("Q", cx - k, cy - k, cx, cy - radius), ("Z",),
    ]

BIG = sparkle(52.5, 48.5, 15.5)
SMALL = sparkle(70.5, 38.0, 6.0)
TINY = sparkle(37.5, 58.0, 4.0)

def polygon(path, steps=24):
    points, cur = [], None
    for op in path:
        if op[0] == "M" or op[0] == "L":
            cur = (op[1], op[2]); points.append(cur)
        elif op[0] == "Q":
            (cx, cy), (x, y) = (op[1], op[2]), (op[3], op[4])
            sx, sy = cur
            for i in range(1, steps + 1):
                t = i / steps
                points.append(((1 - t) ** 2 * sx + 2 * (1 - t) * t * cx + t * t * x, (1 - t) ** 2 * sy + 2 * (1 - t) * t * cy + t * t * y))
            cur = (x, y)
    return points

def mask(*paths):
    """Anti-aliased coverage 0..1 of the union of paths, on the N x N master grid."""
    big = Image.new("L", (N * SS, N * SS), 0)
    d = ImageDraw.Draw(big)
    for p in paths:
        d.polygon([(x * SCALE * SS, y * SCALE * SS) for x, y in polygon(p)], fill=255)
    return np.asarray(big.resize((N, N), Image.LANCZOS), dtype=np.float32) / 255.0

# ---------------------------------------------------------------- numerics
def gaussian(a, sigma):
    """Gaussian blur by FFT, with zero padding so nothing wraps around."""
    pad = int(sigma * 4)
    p = np.pad(a, pad)
    fy = np.fft.fftfreq(p.shape[0])[:, None]; fx = np.fft.rfftfreq(p.shape[1])[None, :]
    kernel = np.exp(-2 * (math.pi ** 2) * sigma ** 2 * (fx ** 2 + fy ** 2))
    out = np.fft.irfft2(np.fft.rfft2(p) * kernel, s=p.shape)
    return out[pad:-pad, pad:-pad].astype(np.float32)

def shift(a, dx, dy):
    out = np.zeros_like(a)
    dx, dy = int(round(dx)), int(round(dy))
    ys, xs = slice(max(dy, 0), N + min(dy, 0)), slice(max(dx, 0), N + min(dx, 0))
    yd, xd = slice(max(-dy, 0), N + min(-dy, 0)), slice(max(-dx, 0), N + min(-dx, 0))
    out[ys, xs] = a[yd, xd]
    return out

def normalize(v):
    return v / np.linalg.norm(v)

def lerp(a, b, t):
    return a + (b - a) * t[..., None]

# ---------------------------------------------------------------- the background (also written as a vector)
BACKGROUND_STOPS = [(0.0, (0x4F, 0xA6, 0xFF)), (0.55, (0x25, 0x5B, 0xE0)), (1.0, (0x3A, 0x22, 0xA8))]
GLOW = dict(cx=30.0, cy=24.0, radius=78.0, alpha=0.42)

def render_background():
    ys, xs = np.mgrid[0:N, 0:N].astype(np.float32) / SCALE
    t = np.clip((xs + ys) / (2 * CANVAS), 0, 1)
    img = np.zeros((N, N, 3), np.float32)
    for (o0, c0), (o1, c1) in zip(BACKGROUND_STOPS, BACKGROUND_STOPS[1:]):
        sel = (t >= o0) & (t <= o1)
        f = ((t - o0) / (o1 - o0))[..., None]
        seg = np.array(c0, np.float32) + (np.array(c1, np.float32) - np.array(c0, np.float32)) * f
        img = np.where(sel[..., None], seg, img)
    d = np.clip(np.hypot(xs - GLOW["cx"], ys - GLOW["cy"]) / GLOW["radius"], 0, 1)
    glow = (GLOW["alpha"] * (1 - d))[..., None]
    return np.clip(img * (1 - glow) + 255.0 * glow, 0, 255)

# ---------------------------------------------------------------- the raised bubble with sparkles
def render_foreground():
    m_bubble, m_big, m_small, m_tiny = mask(bubble()), mask(BIG), mask(SMALL), mask(TINY)
    m_stars = np.maximum(m_big, np.maximum(m_small, m_tiny))
    dp = SCALE

    # Height: a rounded pillow for the bubble, a crisp rim, and the sparkles standing on top of it.
    height = 1.00 * gaussian(m_bubble, 2.2 * dp) + 0.75 * gaussian(m_bubble, 0.7 * dp) \
        + 1.00 * gaussian(m_stars, 0.8 * dp) + 0.55 * gaussian(m_stars, 2.0 * dp)
    gy, gx = np.gradient(height)
    k = 5.5 * dp
    normal = np.dstack([-gx * k, -gy * k, np.ones_like(height)])
    normal /= np.linalg.norm(normal, axis=2, keepdims=True)

    light = normalize(np.array([-0.55, -0.70, 0.55]))
    half = normalize(light + np.array([0, 0, 1.0]))
    diffuse = np.clip(normal @ light, 0, 1)
    specular = np.clip(normal @ half, 0, 1) ** 60
    broad = np.clip(normal @ half, 0, 1) ** 8

    # Surface colours: a pearly white bubble, and sparkles that run from cyan to violet.
    ys, xs = np.mgrid[0:N, 0:N].astype(np.float32) / SCALE
    t = np.clip(((xs - 38) + (ys - 33)) / 40.0, 0, 1)
    star_color = lerp(np.broadcast_to(np.array([46, 224, 255], np.float32), (N, N, 3)), np.broadcast_to(np.array([150, 92, 255], np.float32), (N, N, 3)), t)
    bubble_color = lerp(np.broadcast_to(np.array([255, 255, 255], np.float32), (N, N, 3)), np.broadcast_to(np.array([200, 214, 245], np.float32), (N, N, 3)), np.clip((ys - 30) / 50.0, 0, 1))
    star_weight = np.clip(m_stars * 1.0, 0, 1)[..., None]
    base = bubble_color * (1 - star_weight) + star_color * star_weight

    shade = 0.58 + 0.62 * diffuse
    lit = base * shade[..., None]
    lit += (255 - lit) * (0.60 * specular + 0.08 * broad)[..., None] * (0.7 + 0.3 * star_weight)
    # The sparkles stand on the bubble, so they throw a small shadow on it.
    on_bubble = np.clip(m_bubble - m_stars, 0, 1)
    cast = np.clip(gaussian(shift(m_stars, 0.7 * dp, 1.4 * dp), 1.0 * dp) * 0.40, 0, 1) * on_bubble
    lit *= (1 - cast)[..., None]
    # Make the sparkles livelier: they stay colourful in the shade.
    lit = np.where(star_weight > 0, np.clip(lit * 1.10 + 12 * star_weight, 0, 255), lit)
    rgb = np.clip(lit, 0, 255)

    # A shadow on the background: a wide soft one falling to the bottom right and a tight contact shadow.
    soft = np.clip(gaussian(shift(m_bubble, 1.6 * dp, 3.2 * dp), 2.6 * dp) * 0.55, 0, 1)
    contact = np.clip(gaussian(shift(m_bubble, 0.4 * dp, 0.9 * dp), 0.7 * dp) * 0.45, 0, 1)
    shadow = 1 - (1 - soft) * (1 - contact)
    shadow_rgb = np.array([8, 14, 70], np.float32)

    alpha = np.clip(m_bubble, 0, 1)
    out_rgb = rgb * alpha[..., None] + shadow_rgb * (1 - alpha[..., None]) * 1.0
    out_a = alpha + shadow * (1 - alpha)
    out_rgb = np.where(out_a[..., None] > 1e-4, (rgb * alpha[..., None] + shadow_rgb * (shadow * (1 - alpha))[..., None]) / np.maximum(out_a[..., None], 1e-4), 0)
    return np.dstack([np.clip(out_rgb, 0, 255), out_a * 255.0]).astype(np.float32)

# ---------------------------------------------------------------- output
def to_image(arr, size):
    mode = "RGBA" if arr.shape[2] == 4 else "RGB"
    im = Image.fromarray(np.clip(arr + 0.5, 0, 255).astype(np.uint8), mode)
    return im.resize((size, size), Image.LANCZOS) if size != N else im

def composite(background, foreground):
    a = foreground[..., 3:4] / 255.0
    return background * (1 - a) + foreground[..., :3] * a

def rounded(im, radius_fraction=0.225):
    size = im.size[0]
    m = Image.new("L", (size * 4, size * 4), 0)
    ImageDraw.Draw(m).rounded_rectangle([0, 0, size * 4 - 1, size * 4 - 1], radius=int(size * 4 * radius_fraction), fill=255)
    out = im.convert("RGBA"); out.putalpha(m.resize((size, size), Image.LANCZOS))
    return out

def vector_background():
    stops = "\n".join(f'                <item android:offset="{o}" android:color="#FF{r:02X}{g:02X}{b:02X}" />' for o, (r, g, b) in BACKGROUND_STOPS)
    return f"""<?xml version="1.0" encoding="utf-8"?>
<!-- Generated by tools/icon/render_icon.py: a blue to indigo gradient with a soft light at the top left. -->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:aapt="http://schemas.android.com/aapt"
    android:width="108dp" android:height="108dp" android:viewportWidth="108" android:viewportHeight="108">
    <path android:pathData="M0,0h108v108h-108z">
        <aapt:attr name="android:fillColor">
            <gradient android:type="linear" android:startX="0" android:startY="0" android:endX="{2 * CANVAS // 2}" android:endY="{CANVAS}">
{stops}
            </gradient>
        </aapt:attr>
    </path>
    <path android:pathData="M0,0h108v108h-108z">
        <aapt:attr name="android:fillColor">
            <gradient android:type="radial" android:centerX="{GLOW['cx']}" android:centerY="{GLOW['cy']}" android:gradientRadius="{GLOW['radius']}">
                <item android:offset="0" android:color="#{int(GLOW['alpha'] * 255):02X}FFFFFF" />
                <item android:offset="1" android:color="#00FFFFFF" />
            </gradient>
        </aapt:attr>
    </path>
</vector>
"""

def path_data(path):
    out = []
    for op in path:
        if op[0] == "Z": out.append("Z")
        else: out.append(op[0] + " ".join(f"{v:g}" for v in op[1:]) if op[0] != "Q" else "Q" + f"{op[1]:g},{op[2]:g} {op[3]:g},{op[4]:g}")
    return "".join(f"{o} " if o[0] in "ML" and False else o for o in out)

def vector_monochrome():
    # One colour, for the themed icon: the bubble with the sparkles cut out of it (even-odd fill).
    paths = "".join(path_data(p).replace("M", " M", 1) for p in (bubble(), BIG, SMALL, TINY)).strip()
    return f"""<?xml version="1.0" encoding="utf-8"?>
<!-- Generated by tools/icon/render_icon.py: the themed (one colour) version of the icon. -->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="108dp" android:height="108dp" android:viewportWidth="108" android:viewportHeight="108">
    <path android:fillColor="#FF000000" android:fillType="evenOdd" android:pathData="{paths}" />
</vector>
"""

def main():
    here = pathlib.Path(__file__).resolve().parent
    root = here.parents[1]
    ap = argparse.ArgumentParser()
    ap.add_argument("--res", default=str(root / "app/src/main/res"))
    ap.add_argument("--docs", default=str(root / "docs/images"))
    ap.add_argument("--preview", help="write only a preview PNG of the whole icon here and stop")
    a = ap.parse_args()

    fg = render_foreground()
    bg = render_background()
    if a.preview:
        full = composite(bg, fg)
        lo = int(N * 18 / 108); hi = N - lo      # the part a launcher shows: the middle 72 dp
        pv = Image.fromarray(np.clip(full[lo:hi, lo:hi] + 0.5, 0, 255).astype(np.uint8), "RGB").resize((512, 512), Image.LANCZOS)
        rounded(pv).save(a.preview)
        return

    res = pathlib.Path(a.res)
    for density, px in {"mdpi": 108, "hdpi": 162, "xhdpi": 216, "xxhdpi": 324, "xxxhdpi": 432}.items():
        d = res / f"mipmap-{density}"; d.mkdir(parents=True, exist_ok=True)
        to_image(fg, px).save(d / "ic_launcher_foreground.png", optimize=True)
    (res / "drawable").mkdir(parents=True, exist_ok=True)
    (res / "drawable/ic_launcher_background.xml").write_text(vector_background())
    (res / "drawable/ic_launcher_monochrome.xml").write_text(vector_monochrome())

    full = composite(bg, fg)
    lo = int(N * 18 / 108); hi = N - lo
    logo = Image.fromarray(np.clip(full[lo:hi, lo:hi] + 0.5, 0, 255).astype(np.uint8), "RGB")
    (res / "drawable-nodpi").mkdir(parents=True, exist_ok=True)
    rounded(logo.resize((512, 512), Image.LANCZOS)).save(res / "drawable-nodpi/ic_aismo_logo.png", optimize=True)
    docs = pathlib.Path(a.docs); docs.mkdir(parents=True, exist_ok=True)
    rounded(logo.resize((512, 512), Image.LANCZOS)).save(docs / "aismo-icon.png", optimize=True)

main()
