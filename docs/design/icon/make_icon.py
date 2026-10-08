"""Generates the Sift adaptive icon (Android vector XMLs + a preview SVG) from one source of truth.

Canvas 108x108; everything that matters stays inside the central circle r=33 (adaptive-icon safe zone).
"""
import sys, os

out_res, out_svg = sys.argv[1], sys.argv[2]


def rrect(x, y, w, h, r):
    return (f"M{x + r},{y} H{x + w - r} a{r},{r} 0 0 1 {r},{r} V{y + h - r} a{r},{r} 0 0 1 -{r},{r} "
            f"H{x + r} a{r},{r} 0 0 1 -{r},-{r} V{y + r} a{r},{r} 0 0 1 {r},-{r} Z")


def sparkle(cx, cy, r):
    return (f"M{cx},{cy - r} Q{cx},{cy} {cx + r},{cy} Q{cx},{cy} {cx},{cy + r} "
            f"Q{cx},{cy} {cx - r},{cy} Q{cx},{cy} {cx},{cy - r} Z")


CARD = (39, 36, 30, 40, 4.5)          # x, y, w, h, radius (centre = 54,56)
PIVOT = (54, 56)
card_path = rrect(*CARD)
pic_path = rrect(42, 39, 24, 24, 2.5)
wave_path = "M42,57 C47,51 52,55 56,56 C60,57 63,53 66,52 V63 H42 Z"
line1 = rrect(42, 67, 15, 2.2, 1.1)
line2 = rrect(42, 71.2, 9, 2.2, 1.1)
big_spark = sparkle(71, 35, 8.5)
small_spark = sparkle(58, 30, 3.6)

# (rotation, translate x, translate y, fill, alpha)
back = (-17, -2, 1, "#B79AF0", 1.0)
mid = (10, 2, 0.5, "#E5D8FF", 1.0)
top = (-2, 0, 0, "#FFFFFF", 1.0)

LIME = "#C6F24E"
VIOLET = "#6B3FD4"
LILAC = "#D9C9FA"
LINE = "#C9B6F2"

# ---------- Android: background ----------
background = f"""<?xml version="1.0" encoding="utf-8"?>
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:aapt="http://schemas.android.com/aapt"
    android:width="108dp"
    android:height="108dp"
    android:viewportWidth="108"
    android:viewportHeight="108">
    <path android:pathData="M0,0h108v108h-108z">
        <aapt:attr name="android:fillColor">
            <gradient
                android:type="linear"
                android:startX="0"
                android:startY="0"
                android:endX="108"
                android:endY="108">
                <item android:offset="0" android:color="#FF7C55E6" />
                <item android:offset="1" android:color="#FF4B22A8" />
            </gradient>
        </aapt:attr>
    </path>
</vector>
"""


def android_group(rot, tx, ty, body):
    return (f'    <group android:rotation="{rot}" android:pivotX="{PIVOT[0]}" android:pivotY="{PIVOT[1]}" '
            f'android:translateX="{tx}" android:translateY="{ty}">\n{body}    </group>\n')


def apath(data, color, alpha=1.0, extra=""):
    a = f'\n            android:fillAlpha="{alpha}"' if alpha != 1.0 else ""
    c = "#FF" + color[1:]
    return f'        <path\n            android:pathData="{data}"\n            android:fillColor="{c}"{a}{extra} />\n'


fg = ['<?xml version="1.0" encoding="utf-8"?>\n'
      '<vector xmlns:android="http://schemas.android.com/apk/res/android"\n'
      '    android:width="108dp"\n    android:height="108dp"\n'
      '    android:viewportWidth="108"\n    android:viewportHeight="108">\n']
fg.append(android_group(*back[:3], apath(card_path, back[3])))
fg.append(android_group(*mid[:3], apath(card_path, mid[3])))
top_body = (apath(card_path, top[3]) + apath(pic_path, LILAC) + apath(wave_path, VIOLET)
            + f'        <path\n            android:pathData="M56,43.5 m-4.3,0 a4.3,4.3 0 1 0 8.6,0 a4.3,4.3 0 1 0 -8.6,0"\n'
              f'            android:fillColor="#FF{LIME[1:]}" />\n'
            + apath(line1, LINE) + apath(line2, LINE))
fg.append(android_group(top[0], top[1], top[2], top_body))
fg.append(f'    <path\n        android:pathData="{big_spark}"\n        android:fillColor="#FF{LIME[1:]}" />\n')
fg.append(f'    <path\n        android:pathData="{small_spark}"\n        android:fillColor="#FF{LIME[1:]}" />\n')
fg.append('</vector>\n')
foreground = "".join(fg)

# ---------- Android: monochrome (alpha-only silhouette; picture is cut out of the top card) ----------
mono = ['<?xml version="1.0" encoding="utf-8"?>\n'
        '<vector xmlns:android="http://schemas.android.com/apk/res/android"\n'
        '    android:width="108dp"\n    android:height="108dp"\n'
        '    android:viewportWidth="108"\n    android:viewportHeight="108">\n']
mono.append(android_group(*back[:3], apath(card_path, "#000000", 0.5)))
cutout = card_path + " " + pic_path
mono.append(android_group(top[0], top[1], top[2],
                          apath(cutout, "#000000", 1.0, '\n            android:fillType="evenOdd"')
                          + apath(wave_path, "#000000", 1.0)
                          + f'        <path\n            android:pathData="M56,43.5 m-4.3,0 a4.3,4.3 0 1 0 8.6,0 a4.3,4.3 0 1 0 -8.6,0"\n'
                            f'            android:fillColor="#FF000000" />\n'))
mono.append(f'    <path\n        android:pathData="{big_spark}"\n        android:fillColor="#FF000000" />\n')
mono.append(f'    <path\n        android:pathData="{small_spark}"\n        android:fillColor="#FF000000" />\n')
mono.append('</vector>\n')
monochrome = "".join(mono)

adaptive = """<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@drawable/ic_launcher_background" />
    <foreground android:drawable="@drawable/ic_launcher_foreground" />
    <monochrome android:drawable="@drawable/ic_launcher_monochrome" />
</adaptive-icon>
"""

files = {
    "drawable/ic_launcher_background.xml": background,
    "drawable/ic_launcher_foreground.xml": foreground,
    "drawable/ic_launcher_monochrome.xml": monochrome,
    "mipmap-anydpi-v26/ic_launcher.xml": adaptive,
    "mipmap-anydpi-v26/ic_launcher_round.xml": adaptive,
}
for rel, text in files.items():
    path = os.path.join(out_res, rel)
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8", newline="\n") as f:
        f.write(text)


# ---------- SVG preview (same geometry) ----------
def sgroup(rot, tx, ty, body):
    return f'<g transform="translate({tx} {ty}) rotate({rot} {PIVOT[0]} {PIVOT[1]})">{body}</g>'


def sp(d, fill, op=1.0, rule=""):
    return f'<path d="{d}" fill="{fill}" fill-opacity="{op}" {rule}/>'


svg_top = (sp(card_path, top[3]) + sp(pic_path, LILAC) + sp(wave_path, VIOLET)
           + f'<circle cx="56" cy="43.5" r="4.3" fill="{LIME}"/>' + sp(line1, LINE) + sp(line2, LINE))
art = (sgroup(*back[:3], sp(card_path, back[3])) + sgroup(*mid[:3], sp(card_path, mid[3]))
       + sgroup(top[0], top[1], top[2], svg_top)
       + sp(big_spark, LIME) + sp(small_spark, LIME))
mono_art = (sgroup(*back[:3], sp(card_path, "#000", 0.5))
            + sgroup(top[0], top[1], top[2],
                     sp(cutout, "#000", 1.0, 'fill-rule="evenodd"') + sp(wave_path, "#000")
                     + '<circle cx="56" cy="43.5" r="4.3" fill="#000"/>')
            + sp(big_spark, "#000") + sp(small_spark, "#000"))
bg = ('<defs><linearGradient id="g" x1="0" y1="0" x2="108" y2="108" gradientUnits="userSpaceOnUse">'
      '<stop offset="0" stop-color="#7C55E6"/><stop offset="1" stop-color="#4B22A8"/></linearGradient>'
      '<clipPath id="sq"><rect x="0" y="0" width="108" height="108" rx="30"/></clipPath>'
      '<clipPath id="ci"><circle cx="54" cy="54" r="54"/></clipPath>'
      '<clipPath id="sm"><rect x="0" y="0" width="108" height="108" rx="14"/></clipPath></defs>')


def tile(x, clip, scale=1.0, mono_variant=False):
    inner = (f'<rect width="108" height="108" fill="#E8E0F5"/>'
             f'<g fill="none">{mono_art}</g>' if mono_variant else
             f'<rect width="108" height="108" fill="url(#g)"/>{art}')
    return f'<g transform="translate({x} 8) scale({scale})"><g clip-path="url(#{clip})">{inner}</g></g>'


svg = (f'<svg xmlns="http://schemas.android.com/apk/res/android" xmlns:s="http://www.w3.org/2000/svg" '
       f'viewBox="0 0 640 180" width="1280" height="360" xmlns="http://www.w3.org/2000/svg">'
       f'<rect width="640" height="180" fill="#F4EFFA"/>{bg}'
       + tile(10, "sq", 1.5) + tile(180, "ci", 1.5) + tile(350, "sq", 0.45) + tile(410, "sm", 0.45)
       + f'<g transform="translate(470 8) scale(1.5)"><g clip-path="url(#sq)"><rect width="108" height="108" fill="#E8E0F5"/>{mono_art}</g></g>'
       + '</svg>').replace('xmlns:s="http://www.w3.org/2000/svg" ', '').replace(
    'xmlns="http://schemas.android.com/apk/res/android" ', '')
with open(out_svg, "w", encoding="utf-8") as f:
    f.write(svg)
print("ok")
