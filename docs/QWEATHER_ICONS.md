# 天气图标（和风官方图标库）

首页天气卡用的图标不是自己画的，是**和风天气官方图标**转成的 VectorDrawable。

- 上游：<https://github.com/qwd/Icons>
- 展示页：<https://icons.qweather.com/>
- 版本：2026-11-20 取的 `main` 分支
- 许可：**MIT**（全文见本文末尾）

## 用了哪几个

只挑了「形状差别够大」的十种，放在 `app/src/main/res/drawable/ic_qweather_*.xml`：

| 文件名 | 上游 | 天气现象代码 |
|---|---|---|
| `ic_qweather_sunny` | `100-fill.svg` | 100 晴、900 热 |
| `ic_qweather_moon` | `150-fill.svg` | 150 夜间晴 |
| `ic_qweather_cloudy` | `101-fill.svg` | 101~103 多云、901 冷 |
| `ic_qweather_cloud_moon` | `151-fill.svg` | 151~153 夜间多云 |
| `ic_qweather_overcast` | `104-fill.svg` | 104 阴 |
| `ic_qweather_storm` | `302-fill.svg` | 302~304 雷阵雨 |
| `ic_qweather_rain` | `305-fill.svg` | 300~399 各类雨 |
| `ic_qweather_snow` | `400-fill.svg` | 400~499 各类雪 |
| `ic_qweather_fog` | `501-fill.svg` | 500~515 雾、霾、沙尘 |
| `ic_qweather_unknown` | `999-fill.svg` | 未知 / 新码 |

选的 `-fill`（实心）一档：46dp 的主图和 20dp 的预报小图都要能一眼看清，
线性版本在 20dp 下细得几乎看不见。

## 为什么可以直接 tint

上游 SVG 是 `fill="currentColor"` 的单色图形，转成 VectorDrawable 后
`fillColor` 统一填黑，再由 Compose 的 `Icon(tint = …)` 上色 ——
所以它会跟着主题色和深浅色走，和 App 里其他图标是同一种质感。

转的时候只处理 `<path>`；上游这套图标全部是纯 path（1~2 条），
没有 `<circle>`/`<defs>`/渐变，转换是机械的，不存在丢效果的问题。
`fill-rule="evenodd"` 映射成 `android:fillType="evenOdd"`。

## 换图标怎么做

1. 从上游取 `icons/<code>-fill.svg`
2. viewBox 是 `0 0 16 16`，把每条 `<path d="…">` 原样写成
   `<path android:fillColor="#FF000000" android:pathData="…"/>`
3. 文件名按上表来；新增映射时改 `TodayExtraCards.kt` 里的 `glyphRes()`

## License

```
MIT License

Copyright (c) 2021 QWeather 和风天气

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```
