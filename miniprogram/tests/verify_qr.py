#!/usr/bin/env python3
"""
二维码真机级验证：用 OpenCV 的解码器读回来。

只断言「像素对了」不够 —— 二维码错一位也能看起来像样，但扫不出来。
唯一可信的验证是拿独立的解码器读。

做法：
  1. 用 node 调 utils/qrcode 生成矩阵，打印成文本
  2. 这里把矩阵渲染成 PNG（带标准 4 格静区、缩放 8 倍）
  3. cv2.QRCodeDetector 解码，比对原文
"""

import subprocess
import sys
import os
import json

import cv2
import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)

CASES = [
    'HELLO',
    '广中医',
    'https://jw.gzucm.edu.cn/#/qrcodeConfirm/showConfirm?qrcodeId=qrcodeLogin_ea41c39e6d7c408599717c106295944c',
    'https://jw.gzucm.edu.cn',
    'a' * 100,
    'x' * 200,
    '0123456789' * 12,
]


def build_matrix(text):
    """调 node 生成矩阵，返回 (size, [[bool]])"""
    script = f'''
const q = require({json.dumps(os.path.join(HERE, 'out', 'utils', 'qrcode.js'))});
const m = q.encode({json.dumps(text)});
if (!m) {{ console.log('NULL'); process.exit(0); }}
const rows = m.modules.map(r => r.map(v => (v ? '1' : '0')).join(''));
console.log(JSON.stringify({{ size: m.size, rows }}));
'''
    p = subprocess.run(['node', '-e', script], capture_output=True, text=True, encoding='utf-8')
    if p.returncode != 0:
        raise RuntimeError(p.stderr)
    out = p.stdout.strip()
    if out == 'NULL':
        return None
    data = json.loads(out)
    size = data['size']
    mat = [[c == '1' for c in row] for row in data['rows']]
    assert len(mat) == size and all(len(r) == size for r in mat)
    return size, mat


def render(size, mat, scale=10, quiet=4):
    """渲染成白底黑图的 PNG 数组"""
    n = size + quiet * 2
    img = np.ones((n * scale, n * scale), dtype=np.uint8) * 255
    for y in range(size):
        for x in range(size):
            if mat[y][x]:
                y0 = (y + quiet) * scale
                x0 = (x + quiet) * scale
                img[y0:y0 + scale, x0:x0 + scale] = 0
    return img


def main():
    det = cv2.QRCodeDetector()
    ok = 0
    bad = []

    for text in CASES:
        built = build_matrix(text)
        if built is None:
            bad.append((text[:30], '生成失败（返回 null）'))
            continue
        size, mat = built
        img = render(size, mat)

        data, pts, _ = det.detectAndDecode(img)
        if not data:
            # 换个更宽松的解码器再试（OpenCV 有 QRCodeDetectorAruco）
            try:
                det2 = cv2.QRCodeDetectorAruco()
                data, pts, _ = det2.detectAndDecode(img)
            except Exception:
                pass

        if data == text:
            ok += 1
            print(f'  ✓ {size:2d}x{size:<2d} {text[:48]!r}')
        else:
            bad.append((text[:40], f'解码得到 {data[:40]!r}'))

    print(f'\n解码验证：{ok}/{len(CASES)} 通过')
    if bad:
        print(f'\n失败 {len(bad)} 项：')
        for t, why in bad:
            print(f'  ✗ {t!r} — {why}')
        return 1
    return 0


if __name__ == '__main__':
    sys.exit(main())