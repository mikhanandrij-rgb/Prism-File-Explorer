"""Builds the upscaler benchmark clip used by UpscalerBenchmarkTest.

A synthetic anime-style frame (flat cel colours, dark outlines of varying width, hair strands,
eyes, hatching, gradients, text) is drawn at 1920x1080 and used as ground truth. The clip is
that frame shrunk 4.5x to 426x240 and encoded with x264 the way web releases are; the player
shows it at 960x540, i.e. the same 2.25x ratio as 480p -> 1080p.

Outputs (repository paths):
  app/src/androidTest/assets/upscale_benchmark.mp4   the clip
  app/src/androidTest/assets/upscale_benchmark_gt.png ground truth at 960x540 (display size)

Usage: python3 scripts/make_upscale_benchmark.py   (needs pillow, imageio-ffmpeg)
"""
import math
import os
import random
import subprocess
import tempfile

import imageio_ffmpeg
from PIL import Image, ImageDraw, ImageFilter, ImageFont

W, H = 1920, 1080
OUT = os.path.join(os.path.dirname(__file__), "..", "app", "src", "androidTest", "assets")
rng = random.Random(7)
LINE = (34, 24, 30)


def bezier(p0, p1, p2, p3, n=48):
    pts = []
    for i in range(n + 1):
        t = i / n
        a, b, c, d = (1 - t) ** 3, 3 * t * (1 - t) ** 2, 3 * t * t * (1 - t), t ** 3
        pts.append((a * p0[0] + b * p1[0] + c * p2[0] + d * p3[0], a * p0[1] + b * p1[1] + c * p2[1] + d * p3[1]))
    return pts


def stroke(draw, pts, width, fill=LINE):
    draw.line(pts, fill=fill, width=width, joint="curve")
    r = width / 2
    for x, y in (pts[0], pts[-1]):
        draw.ellipse((x - r, y - r, x + r, y + r), fill=fill)


def draw_scene():
    img = Image.new("RGB", (W, H))
    d = ImageDraw.Draw(img)
    # Sky gradient (tests banding) and a sun disc.
    for y in range(H):
        t = y / H
        d.line([(0, y), (W, y)], fill=(int(120 + 100 * t), int(170 + 60 * t), int(235 - 20 * t)))
    d.ellipse((1500, 90, 1720, 310), fill=(255, 238, 190))
    # Buildings with windows (straight lines, fine regular detail).
    x = 0
    while x < W:
        bw, bh = rng.randint(140, 260), rng.randint(250, 520)
        col = rng.choice([(96, 104, 140), (120, 110, 150), (80, 92, 120), (140, 128, 160)])
        d.rectangle((x, H - bh, x + bw, H), fill=col, outline=LINE, width=4)
        for wy in range(H - bh + 24, H - 30, 38):
            for wx in range(x + 18, x + bw - 30, 34):
                d.rectangle((wx, wy, wx + 16, wy + 22), fill=(250, 226, 150) if rng.random() < .4 else (60, 70, 98))
        x += bw + rng.randint(4, 30)
    # Character: face, neck, shoulders with cel shading.
    skin, shade = (252, 222, 204), (236, 190, 176)
    d.polygon([(700, 1080), (760, 820), (1160, 820), (1220, 1080)], fill=(70, 80, 150), outline=LINE, width=6)
    d.rectangle((900, 700, 1020, 840), fill=skin, outline=LINE, width=5)
    d.ellipse((740, 260, 1180, 800), fill=skin, outline=LINE, width=7)
    d.chord((740, 260, 1180, 800), 20, 160, fill=shade)
    # Eyes: outlines, iris gradient, highlights, lashes.
    for cx in (860, 1060):
        d.ellipse((cx - 62, 470, cx + 62, 600), fill=(255, 255, 255), outline=LINE, width=6)
        for r in range(52, 0, -2):
            t = r / 52
            d.ellipse((cx - r * .8, 535 - r, cx + r * .8, 535 + r), fill=(int(150 * t + 60), int(40 + 30 * t), int(90 + 80 * t)))
        d.ellipse((cx - 16, 520, cx + 16, 552), fill=LINE)
        d.ellipse((cx - 30, 492, cx - 8, 514), fill=(255, 255, 255))
        d.ellipse((cx + 14, 548, cx + 24, 558), fill=(255, 255, 255))
        for k in range(7):
            a = math.pi * (1.05 + k * .13)
            stroke(d, [(cx + 70 * math.cos(a), 480 + 30 * math.sin(a)), (cx + 92 * math.cos(a), 470 + 46 * math.sin(a))], 3)
        stroke(d, bezier((cx - 70, 455), (cx - 30, 425), (cx + 30, 425), (cx + 72, 450)), 9)
    stroke(d, bezier((945, 640), (955, 660), (965, 665), (975, 660)), 3)  # nose
    stroke(d, bezier((900, 700), (940, 725), (990, 725), (1030, 698)), 4)  # mouth
    # Blush hatching (very thin parallel lines).
    for cx in (820, 1100):
        for k in range(6):
            stroke(d, [(cx - 30 + k * 11, 650), (cx - 42 + k * 11, 672)], 2, fill=(230, 120, 130))
    # Hair: many strands of varying width, highlight band.
    hair, hair_dark = (60, 50, 110), (38, 30, 80)
    d.pieslice((690, 160, 1230, 760), 180, 360, fill=hair, outline=LINE, width=7)
    for k in range(34):
        x0 = 700 + k * 15 + rng.randint(-6, 6)
        x3 = x0 + rng.randint(-90, 90)
        y3 = rng.randint(430, 900) if k % 5 else rng.randint(700, 1000)
        pts = bezier((x0, 250), (x0 + rng.randint(-60, 60), 380), (x3 + rng.randint(-40, 40), y3 - 150), (x3, y3))
        stroke(d, pts, rng.choice([3, 4, 5, 6, 8]), fill=hair_dark if k % 3 else LINE)
    d.arc((800, 260, 1120, 420), 200, 340, fill=(170, 160, 230), width=14)
    # Text and thin UI lines (very fine detail).
    try:
        font = ImageFont.truetype("DejaVuSans-Bold.ttf", 64)
        small = ImageFont.truetype("DejaVuSans.ttf", 30)
    except OSError:
        font = small = ImageFont.load_default()
    d.text((80, 70), "Facet Files", font=font, fill=(255, 255, 255), stroke_width=4, stroke_fill=LINE)
    d.text((84, 160), "Episode 12 - upscaler benchmark", font=small, fill=LINE)
    for k in range(12):
        d.line([(1300 + k * 40, 760), (1500 + k * 40, 1060)], fill=LINE, width=2)
    return img


def main():
    gt = draw_scene()
    os.makedirs(OUT, exist_ok=True)
    gt.resize((960, 540), Image.LANCZOS).save(os.path.join(OUT, "upscale_benchmark_gt.png"))
    # A typical release: downscaled with a soft kernel, then compressed.
    small = gt.filter(ImageFilter.GaussianBlur(1.2)).resize((426, 240), Image.BICUBIC)
    with tempfile.TemporaryDirectory() as tmp:
        frame = os.path.join(tmp, "f.png")
        small.save(frame)
        subprocess.run([
            imageio_ffmpeg.get_ffmpeg_exe(), "-y", "-loglevel", "error", "-loop", "1", "-i", frame,
            "-t", "4", "-r", "24", "-vf", "format=yuv420p", "-c:v", "libx264", "-preset", "slow",
            "-tune", "animation", "-crf", "24", "-colorspace", "bt709", "-color_primaries", "bt709",
            "-color_trc", "bt709", "-color_range", "tv", "-movflags", "+faststart",
            os.path.join(OUT, "upscale_benchmark.mp4"),
        ], check=True)


if __name__ == "__main__":
    main()
