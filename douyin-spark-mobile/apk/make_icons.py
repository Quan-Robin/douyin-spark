#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""根据一张源图生成 Android 各密度图标。

用法:
    python make_icons.py                    # 用 res/icon/icon-source.png(没有就用内置占位图)
    python make_icons.py 我的图.png          # 指定源图

源图要求:正方形、建议 1024x1024、PNG(可带透明通道)。
生成结果写入 res/mipmap-*/ic_launcher.png 与 ic_launcher_round.png。
"""
from __future__ import annotations

import math
import os
import sys

try:
    from PIL import Image, ImageDraw, ImageFilter
except ImportError:  # pragma: no cover
    print("需要 Pillow:pip install pillow")
    raise SystemExit(1)

HERE = os.path.dirname(os.path.abspath(__file__))
# 源图放在工程根目录(不要放进 res/ —— 那会被 aapt2 当成资源编译,白白打进 APK)
SOURCE_CANDIDATES = [
    os.path.join(HERE, "icon-source.png"),
    os.path.join(HERE, "res", "icon", "icon-source.png"),  # 旧路径,兼容
]
# Android 各密度下 launcher 图标的边长
DENSITIES = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}
BASE = 1024


def rounded_mask(size: int, radius_ratio: float) -> Image.Image:
    m = Image.new("L", (size, size), 0)
    d = ImageDraw.Draw(m)
    r = int(size * radius_ratio)
    d.rounded_rectangle([0, 0, size - 1, size - 1], radius=r, fill=255)
    return m


def circle_mask(size: int) -> Image.Image:
    m = Image.new("L", (size, size), 0)
    d = ImageDraw.Draw(m)
    d.ellipse([0, 0, size - 1, size - 1], fill=255)
    return m


def flame_points(cx: float, base_y: float, height: float, half_w: float, lean: float):
    """生成一个火焰轮廓(左右对称的泪滴形,顶端略向右偏)。"""
    pts = []
    n = 240
    for i in range(n + 1):
        t = i / n                       # 0=底部 1=顶端
        y = base_y - t * height
        w = math.sin(math.pi * (t ** 0.58)) ** 1.25
        x = cx + lean * (t ** 2.4) + w * half_w
        pts.append((x, y))
    for i in range(n, -1, -1):
        t = i / n
        y = base_y - t * height
        w = math.sin(math.pi * (t ** 0.58)) ** 1.25
        x = cx + lean * (t ** 2.2) - w * half_w
        pts.append((x, y))
    return pts


def placeholder() -> Image.Image:
    """内置占位图:深色圆角底 + 抖音红到橙的渐变火焰 + 一颗火星。"""
    size = BASE
    img = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    # 背景:竖向渐变
    bg = Image.new("RGBA", (size, size))
    px = bg.load()
    top, bottom = (26, 26, 34), (36, 10, 20)
    for y in range(size):
        k = y / (size - 1)
        px_row = (int(top[0] + (bottom[0] - top[0]) * k),
                  int(top[1] + (bottom[1] - top[1]) * k),
                  int(top[2] + (bottom[2] - top[2]) * k), 255)
        for x in range(size):
            px[x, y] = px_row
    img.paste(bg, (0, 0), rounded_mask(size, 0.22))

    # 火焰:外层红 -> 内层橙黄,用竖向渐变裁出轮廓
    flame = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    d = ImageDraw.Draw(flame)
    # 主焰(顶端向右偏)+ 左侧小火舌,叠加起来更像火焰而不是水滴
    d.polygon(flame_points(size * 0.52, size * 0.80, size * 0.50, size * 0.155, size * 0.055),
              fill=(254, 44, 85, 255))
    d.polygon(flame_points(size * 0.40, size * 0.72, size * 0.30, size * 0.085, -size * 0.02),
              fill=(254, 44, 85, 255))
    d.polygon(flame_points(size * 0.5, size * 0.78, size * 0.33, size * 0.105, size * 0.02),
              fill=(255, 122, 69, 255))
    d.polygon(flame_points(size * 0.5, size * 0.76, size * 0.17, size * 0.055, size * 0.01),
              fill=(255, 199, 89, 255))
    # 火星
    d.ellipse([size * 0.66, size * 0.235, size * 0.72, size * 0.295], fill=(255, 255, 255, 235))

    glow = flame.filter(ImageFilter.GaussianBlur(size * 0.02))
    img.alpha_composite(glow)
    img.alpha_composite(flame)

    # 圆角外裁掉
    out = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    out.paste(img, (0, 0), rounded_mask(size, 0.22))
    return out


def main() -> int:
    if len(sys.argv) > 1:
        src_path = sys.argv[1]
    else:
        src_path = next((p for p in SOURCE_CANDIDATES if os.path.isfile(p)), SOURCE_CANDIDATES[0])
    if os.path.isfile(src_path):
        print("源图:", src_path)
        src = Image.open(src_path).convert("RGBA")
        w, h = src.size
        side = min(w, h)
        src = src.crop(((w - side) // 2, (h - side) // 2, (w - side) // 2 + side, (h - side) // 2 + side))
        if side != BASE:
            src = src.resize((BASE, BASE), Image.LANCZOS)
    else:
        print("未找到源图 %s,使用内置占位图(放一张 1024x1024 的 PNG 到该路径即可替换)" % src_path)
        src = placeholder()

    square = src
    round_icon = Image.new("RGBA", (BASE, BASE), (0, 0, 0, 0))
    round_icon.paste(src, (0, 0), circle_mask(BASE))

    written = []
    for name, px in DENSITIES.items():
        folder = os.path.join(HERE, "res", "mipmap-" + name)
        os.makedirs(folder, exist_ok=True)
        # 只生成一张方形图标:启动器要圆形时会自己套遮罩,再存一份 ic_launcher_round
        # 等于把图标体积翻倍(APK 里白白多 ~11KB)
        for fname, im in (("ic_launcher", square),):
            base = os.path.join(folder, fname)
            small = im.resize((px, px), Image.LANCZOS)
            # 图标是有限色阶的图形,量化成 256 色(保留透明通道)
            try:
                small = small.quantize(colors=256, method=Image.FASTOCTREE)
            except Exception:
                pass
            # 用 WebP 而不是 PNG:同画质体积约为 PNG 的 1/3(Android 4.3+ 原生支持)
            out = base + ".webp"
            small.save(out, "WEBP", quality=95, method=6)
            # 清掉同名旧 PNG,否则 aapt2 会报资源重复
            stale = base + ".png"
            if os.path.exists(stale):
                os.remove(stale)
            written.append(os.path.relpath(out, HERE))
    print("已生成 %d 个图标文件,例如 %s" % (len(written), written[0]))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
