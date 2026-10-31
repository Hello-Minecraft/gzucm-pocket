/** 学分进度条。ratio 是 0..1 的比例 */
Component({
  properties: {
    ratio: { type: Number, value: 0 },
  },
  data: {
    pct: 0,
  },
  observers: {
    ratio(this: WxComponentThis, v: number) {
      const r = isFinite(v) ? Math.max(0, Math.min(1, v)) : 0;
      this.setData({ pct: Math.round(r * 1000) / 10 });
    },
  },
});
