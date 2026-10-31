#!/usr/bin/env bash
# 一把跑完所有校验。
#
# 用法：
#   bash verify.sh
#
# 需要：
#   · node 18+（跑测试）
#   · tsc（类型检查）—— 没装的话会提示
#   · python + opencv（二维码解码验证）—— 缺了会跳过那一步

set -e
cd "$(dirname "$0")"

echo "══════════════════════════════════════════════"
echo " 1/4  TypeScript 类型检查"
echo "══════════════════════════════════════════════"

TSC=""
for c in "tsc" "D:/App/_android/tsc-tmp/node_modules/.bin/tsc" "./node_modules/.bin/tsc"; do
  if command -v "$c" >/dev/null 2>&1 || [ -x "$c" ]; then TSC="$c"; break; fi
done

if [ -z "$TSC" ]; then
  echo "⚠ 没找到 tsc，跳过。装法：npm i -g typescript"
else
  "$TSC" --noEmit -p tsconfig.json && echo "✓ 零错误"
fi

echo ""
echo "══════════════════════════════════════════════"
echo " 2/4  编译到 tests/out（供后续校验使用）"
echo "══════════════════════════════════════════════"

if [ -n "$TSC" ]; then
  "$TSC" -p tsconfig.json --outDir tests/out --module commonjs --target ES2020
  echo "✓ 已编译"
else
  echo "⚠ 跳过（没有 tsc）"
fi

echo ""
echo "══════════════════════════════════════════════"
echo " 3/4  wxml 与 ts 的一致性"
echo "══════════════════════════════════════════════"

python check.py

echo ""
echo "══════════════════════════════════════════════"
echo " 4/4  核心逻辑测试"
echo "══════════════════════════════════════════════"

node tests/run.js

echo ""
echo "══════════════════════════════════════════════"
echo " 附加：二维码真机级解码验证"
echo "══════════════════════════════════════════════"

if python -c "import cv2" 2>/dev/null; then
  python tests/verify_qr.py
else
  echo "⚠ 没有 opencv，跳过。装法：pip install opencv-python-headless"
fi

echo ""
echo "全部完成 ✓"