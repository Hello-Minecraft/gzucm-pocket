/**
 * 课程卡片。
 *
 * 颜色策略照抄 Android 版的 textColorFor：
 *   深色底 → 提亮到亮度 0.82、饱和度降 0.55
 *   浅色底 → 压暗到亮度 0.42、饱和度升 1.15
 *
 * 为什么不能直接用课程色当字色：课程色本身偏暗（橙 #B98A3E 之类），
 * 压在半透明深底上对比度只有 2:1，看着"发褐"，不合格。
 */

interface HSL {
  h: number;
  s: number;
  l: number;
}

function hexToRgb(hex: string): { r: number; g: number; b: number } {
  let h = (hex || '').replace('#', '').trim();
  if (h.length === 3) h = h[0] + h[0] + h[1] + h[1] + h[2] + h[2];
  if (h.length !== 6) return { r: 0, g: 0, b: 0 };
  const n = parseInt(h, 16);
  if (!isFinite(n)) return { r: 0, g: 0, b: 0 };
  return { r: (n >> 16) & 255, g: (n >> 8) & 255, b: n & 255 };
}

function rgbToHsl(r: number, g: number, b: number): HSL {
  const rr = r / 255;
  const gg = g / 255;
  const bb = b / 255;
  const max = Math.max(rr, gg, bb);
  const min = Math.min(rr, gg, bb);
  const l = (max + min) / 2;
  if (max === min) return { h: 0, s: 0, l };
  const d = max - min;
  const s = l > 0.5 ? d / (2 - max - min) : d / (max + min);
  let h = 0;
  if (max === rr) h = ((gg - bb) / d + (gg < bb ? 6 : 0)) / 6;
  else if (max === gg) h = ((bb - rr) / d + 2) / 6;
  else h = ((rr - gg) / d + 4) / 6;
  return { h: h * 360, s, l };
}

function hslToRgb(h: number, s: number, l: number): { r: number; g: number; b: number } {
  const hh = ((h % 360) + 360) % 360 / 360;
  if (s === 0) {
    const v = Math.round(l * 255);
    return { r: v, g: v, b: v };
  }
  const q = l < 0.5 ? l * (1 + s) : l + s - l * s;
  const p = 2 * l - q;
  const hue2rgb = (t: number) => {
    let tt = t;
    if (tt < 0) tt += 1;
    if (tt > 1) tt -= 1;
    if (tt < 1 / 6) return p + (q - p) * 6 * tt;
    if (tt < 1 / 2) return q;
    if (tt < 2 / 3) return p + (q - p) * (2 / 3 - tt) * 6;
    return p;
  };
  return {
    r: Math.round(hue2rgb(hh + 1 / 3) * 255),
    g: Math.round(hue2rgb(hh) * 255),
    b: Math.round(hue2rgb(hh - 1 / 3) * 255),
  };
}

function toHex(r: number, g: number, b: number): string {
  const f = (v: number) => {
    const s = Math.max(0, Math.min(255, v)).toString(16);
    return s.length === 1 ? '0' + s : s;
  };
  return '#' + f(r) + f(g) + f(b);
}

/** 派生课程色。dark = 当前是深色主题 */
function derive(color: string, dark: boolean): { bg: string; border: string; text: string } {
  const c = hexToRgb(color);
  const hsl = rgbToHsl(c.r, c.g, c.b);

  if (dark) {
    // 底色：原色压暗、低饱和，作为卡片背景
    const bg = hslToRgb(hsl.h, Math.min(0.5, hsl.s * 0.7), 0.20);
    const border = hslToRgb(hsl.h, Math.min(0.5, hsl.s * 0.7), 0.34);
    // 字色：提亮到 0.82，饱和降 0.55 —— 深底上对比度能到 6:1
    const text = hslToRgb(hsl.h, Math.min(1, hsl.s * 0.55), 0.82);
    return { bg: toHex(bg.r, bg.g, bg.b), border: toHex(border.r, border.g, border.b), text: toHex(text.r, text.g, text.b) };
  }
  // 浅色主题：底浅字深
  const bg = hslToRgb(hsl.h, Math.min(0.6, hsl.s * 0.9), 0.90);
  const border = hslToRgb(hsl.h, Math.min(0.6, hsl.s * 0.9), 0.76);
  const text = hslToRgb(hsl.h, Math.min(1, hsl.s * 1.15), 0.30);
  return { bg: toHex(bg.r, bg.g, bg.b), border: toHex(border.r, border.g, border.b), text: toHex(text.r, text.g, text.b) };
}

/**
 * 按课程名稳定地分配一个色相。
 *
 * 用简单字符串哈希：同一门课永远同一个色，换设备也一样。
 * 刻意不用随机 —— 每次进来颜色变会让人认不出哪门是哪门。
 */
function hueOf(name: string): number {
  let h = 0;
  for (let i = 0; i < name.length; i++) {
    h = (h * 31 + name.charCodeAt(i)) % 360;
  }
  return h;
}

/** 教务给了 bgcolor 就用它，没给就按课名推一个 */
function courseColor(item: GZ.CourseSession): string {
  const given = (item.schoolColor || '').trim();
  if (/^#?[0-9a-fA-F]{6}$/.test(given)) {
    return given.charAt(0) === '#' ? given : '#' + given;
  }
  const h = hueOf(item.name);
  // 固定饱和度/亮度，只变色相 —— 保证所有课在同一个明度层级上
  const rgb = hslToRgb(h, 0.42, 0.42);
  return toHex(rgb.r, rgb.g, rgb.b);
}

Component({
  properties: {
    /** 课程数据 */
    item: { type: Object, value: {} },
    /** 横向位置（百分比）*/
    left: { type: Number, value: 0 },
    /** 横向宽度（百分比）*/
    width: { type: Number, value: 100 },
    /** 纵向位置（rpx）*/
    top: { type: Number, value: 0 },
    /** 高度（rpx）*/
    height: { type: Number, value: 0 },
    /** 占几节 */
    span: { type: Number, value: 1 },
    /** 第几列 / 共几列 */
    col: { type: Number, value: 0 },
    cols: { type: Number, value: 1 },
    selected: { type: Boolean, value: false },
    dark: { type: Boolean, value: true },
  },

  data: {
    bg: 'transparent',
    border: 'transparent',
    textColor: '#ffffff',
  },

  observers: {
    'item, dark': function (this: WxComponentThis, item: any, dark: boolean) {
      if (!item || !item.name) return;
      const c = derive(courseColor(item), dark);
      this.setData({ bg: c.bg, border: c.border, textColor: c.text });
    },
  },

  methods: {
    onTap(this: WxComponentThis) {
      this.triggerEvent('tap', { item: this.data.item });
    },
  },
});