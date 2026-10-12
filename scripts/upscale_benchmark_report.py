"""Scores UpscalerBenchmarkTest screenshots against the ground truth.

For every <id>.png in the bench directory: crop the video area, compare its luma with
upscale_benchmark_gt.png (960x540) and print PSNR, SSIM, and edge fidelity (correlation of
Sobel magnitudes with the ground truth: rewards sharp lines in the right place, penalises
blur, halos and invented edges). Also writes a contact sheet of zoomed crops.

Usage: python3 scripts/upscale_benchmark_report.py <bench dir> <ground truth png> <out dir>
Needs numpy, pillow, scikit-image.
"""
import os
import sys

import numpy as np
from PIL import Image, ImageDraw
from skimage.filters import sobel
from skimage.metrics import peak_signal_noise_ratio, structural_similarity


def luma(img):
    a = np.asarray(img.convert("RGB"), dtype=np.float64) / 255.0
    return 0.2126 * a[..., 0] + 0.7152 * a[..., 1] + 0.0722 * a[..., 2]


def video_area(shot, size):
    """Crops the non-black video rectangle and resizes it to the ground-truth size."""
    a = np.asarray(shot.convert("L"), dtype=np.int32)
    cols = np.where(a.max(axis=0) > 16)[0]
    rows = np.where(a.max(axis=1) > 16)[0]
    box = (int(cols[0]), int(rows[0]), int(cols[-1]) + 1, int(rows[-1]) + 1)
    return shot.convert("RGB").crop(box).resize(size, Image.BICUBIC), box


def main():
    bench, gt_path, out = sys.argv[1:4]
    os.makedirs(out, exist_ok=True)
    gt = Image.open(gt_path).convert("RGB")
    g = luma(gt)
    ge = sobel(g)
    rows = []
    crops = {}
    for name in sorted(f for f in os.listdir(bench) if f.endswith(".png")):
        cid = name[:-4]
        img, box = video_area(Image.open(os.path.join(bench, name)), gt.size)
        y = luma(img)
        # Global brightness offsets (range conversion) are not what we rank: remove the mean shift.
        y = y - (y.mean() - g.mean())
        psnr = peak_signal_noise_ratio(g, np.clip(y, 0, 1), data_range=1.0)
        ssim = structural_similarity(g, np.clip(y, 0, 1), data_range=1.0)
        ye = sobel(y)
        edge = float(np.corrcoef(ge.ravel(), ye.ravel())[0, 1])
        sharp = float(ye.mean() / ge.mean())
        rows.append((cid, psnr, ssim, edge, sharp, box))
        crops[cid] = img
    rows.sort(key=lambda r: -r[2])
    lines = ["id                     PSNR   SSIM   edge   sharp(vs gt)  crop"]
    for cid, psnr, ssim, edge, sharp, box in rows:
        lines.append(f"{cid:22} {psnr:6.2f} {ssim:6.4f} {edge:6.4f} {sharp:6.2f}        {box}")
    report = "\n".join(lines)
    print(report)
    open(os.path.join(out, "scores.txt"), "w").write(report + "\n")

    # Contact sheet: face/hair region and text region, 2x nearest zoom, ground truth first.
    regions = [(330, 200, 610, 380), (30, 30, 310, 110)]
    order = ["ground_truth"] + [r[0] for r in rows]
    crops["ground_truth"] = gt
    tile_w = sum((r[2] - r[0]) * 2 for r in regions) + 10
    tile_h = max((r[3] - r[1]) * 2 for r in regions) + 26
    cols = 3
    sheet = Image.new("RGB", (cols * tile_w, ((len(order) + cols - 1) // cols) * tile_h), (20, 20, 20))
    d = ImageDraw.Draw(sheet)
    for i, cid in enumerate(order):
        x0, y0 = (i % cols) * tile_w, (i // cols) * tile_h
        d.text((x0 + 4, y0 + 4), cid, fill=(255, 255, 255))
        x = x0
        for reg in regions:
            part = crops[cid].crop(reg)
            part = part.resize((part.width * 2, part.height * 2), Image.NEAREST)
            sheet.paste(part, (x, y0 + 22))
            x += part.width + 10
    sheet.save(os.path.join(out, "contact_sheet.png"))


if __name__ == "__main__":
    main()
