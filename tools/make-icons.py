"""从官方鲸鱼图生成 Android 全套启动图标。

- 抠掉白色背景（官方图是白底）
- 鲸鱼缩到画布的 1/3（用户明确要求"鲸鱼体积缩小到三分之一大"）
- 生成：传统图标 ic_launcher.png（5 个密度）+ 自适应图标前景 ic_launcher_foreground.png
        + mipmap-anydpi-v26/ic_launcher.xml + values/ic_launcher_background.xml
- 另外导出一张 preview.png 方便人工确认
"""
import os
from PIL import Image

ROOT = r"D:\DeepSeek Harness\DSH-GitWorkspace\DSH-development-project-1"
SRC = os.path.join(ROOT, "assets", "icons", "deepseek-whale-official.jpg")
RES = os.path.join(ROOT, "app", "src", "main", "res")

WHALE_FRACTION = 1 / 6          # 鲸鱼占画布比例（改这一个数字即可调大小）
BACKGROUND = (255, 255, 255, 255)  # 图标底色（白色，和官方图一致）

im = Image.open(SRC).convert("RGBA")
px = im.load()
w, h = im.size
for y in range(h):
    for x in range(w):
        r, g, b, a = px[x, y]
        if r > 232 and g > 232 and b > 232:
            px[x, y] = (r, g, b, 0)

bbox = im.getbbox()
whale = im.crop(bbox)
side = max(whale.size)
sq = Image.new("RGBA", (side, side), (0, 0, 0, 0))
sq.paste(whale, ((side - whale.width) // 2, (side - whale.height) // 2), whale)
whale = sq
print("whale bbox:", bbox, "-> square:", whale.size)

DENSITY = {"mdpi": 1, "hdpi": 1.5, "xhdpi": 2, "xxhdpi": 3, "xxxhdpi": 4}


def render(path, size, bg):
    canvas = Image.new("RGBA", (size, size), bg)
    tw = max(1, int(round(size * WHALE_FRACTION)))
    scaled = whale.resize((tw, tw), Image.LANCZOS)
    off = (size - tw) // 2
    canvas.paste(scaled, (off, off), scaled)
    canvas.save(path, "PNG")
    return path


for name, scale in DENSITY.items():
    folder = os.path.join(RES, "mipmap-" + name)
    os.makedirs(folder, exist_ok=True)
    render(os.path.join(folder, "ic_launcher.png"), int(round(48 * scale)), BACKGROUND)
    render(os.path.join(folder, "ic_launcher_foreground.png"),
           int(round(108 * scale)), (0, 0, 0, 0))
    print(name, "ok")

anydpi = os.path.join(RES, "mipmap-anydpi-v26")
os.makedirs(anydpi, exist_ok=True)
with open(os.path.join(anydpi, "ic_launcher.xml"), "w", encoding="utf-8") as f:
    f.write(
        '<?xml version="1.0" encoding="utf-8"?>\n'
        '<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">\n'
        '    <background android:drawable="@color/ic_launcher_background"/>\n'
        '    <foreground android:drawable="@mipmap/ic_launcher_foreground"/>\n'
        '</adaptive-icon>\n'
    )

with open(os.path.join(RES, "values", "ic_launcher_background.xml"), "w", encoding="utf-8") as f:
    f.write(
        '<?xml version="1.0" encoding="utf-8"?>\n'
        '<resources>\n'
        '    <color name="ic_launcher_background">#FFFFFF</color>\n'
        '</resources>\n'
    )

preview = Image.new("RGB", (560, 260), (245, 245, 245))
legacy = Image.open(os.path.join(RES, "mipmap-xxxhdpi", "ic_launcher.png")).resize((220, 220), Image.LANCZOS)
preview.paste(legacy, (30, 20), legacy)
fg = Image.open(os.path.join(RES, "mipmap-xxxhdpi", "ic_launcher_foreground.png")).resize((220, 220), Image.LANCZOS)
preview.paste(fg, (310, 20), fg)
out = os.path.join(ROOT, "assets", "icons", "preview.png")
preview.save(out)
print("preview:", out)
