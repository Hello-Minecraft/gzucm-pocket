/**
 * 课表页。
 *
 * 布局全部用 rpx 现算：行高固定，课程块按 (top, height) 绝对定位，
 * 横向用百分比（left/width），这样不同屏宽下比例一致。
 *
 * 为什么不直接用网格/表格：
 * 一门课可能横跨多节，表格里做 rowspan 在 wx:for 下很难对齐，
 * 绝对定位反而更直观，也和 Android 版的 Placed 模型一致。
 */

import * as cache from '../../store/cache';
import * as week from '../../utils/week';
import * as layout from '../../utils/layout';

const app = getApp();

/** 每行（一节）的高度，rpx */
const ROW_H = 108;

Page({
  data: {
    semester: null as GZ.Semester | null,
    sessions: [] as GZ.CourseSession[],
    weeks: [] as number[],
    viewWeek: 1,
    todayWeek: 1,
    todayWeekday: 1,
    dayLabels: [] as Array<{ label: string; date: string }>,
    rows: layout.SLOTS,
    rowH: ROW_H,
    gridHeight: layout.SLOTS.length * ROW_H,
    placed: [] as any[],
    colLines: [14.28, 28.56, 42.84, 57.12, 71.4, 85.68],
    selectedKey: '',
    gridOn: false,
    syncing: false,
    progressLabel: '',
    progressPct: 0,
    stripScroll: 0,
  },

  onLoad() {
    this.setData({ gridOn: cache.gridLines() });
    this.refresh();
  },

  onShow() {
    this.refresh();
    /*
     * 从设置页改完「重叠课程」或「网格线」回来，要立刻反映出来。
     * refresh() 只在数据变了时重建，这里强制重算一次排布。
     */
    this.setData({ gridOn: cache.gridLines() }, () => this.rebuild());
    // 同步进度跟着 app 的全局状态走
    this.watchSync();
  },

  onUnload() {
    if (this.timer) clearInterval(this.timer);
  },

  /** 从缓存 + 全局状态重建页面数据 */
  refresh() {
    const sem: GZ.Semester | null = app.globalData.semester || cache.loadSemester();
    const sessions: GZ.CourseSession[] =
      app.globalData.sessions && app.globalData.sessions.length
        ? app.globalData.sessions
        : cache.loadSessions();

    if (!sem) {
      this.setData({ semester: null, sessions: [] });
      return;
    }

    const cw = week.currentWeek(sem);
    const tw = week.weekdayOf(week.todayStart());
    // 只在第一次进来时定位到"本周"，之后用户手动切过就别抢回去
    const viewWeek = this.data.viewWeek && this.data.semester
      ? Math.min(this.data.viewWeek, sem.totalWeeks)
      : cw;

    const weeks: number[] = [];
    for (let i = 1; i <= sem.totalWeeks; i++) weeks.push(i);

    this.setData(
      {
        semester: sem,
        sessions,
        weeks,
        todayWeek: cw,
        todayWeekday: tw,
        viewWeek,
        stripScroll: Math.max(0, (viewWeek - 3) * 70),
      },
      () => this.rebuild(),
    );
  },

  /** 按当前视图周重算排布 */
  rebuild() {
    const { sessions, viewWeek, semester } = this.data;
    if (!semester) return;

    /*
     * 「重叠课程」开关：
     *   开 → 同一天重叠的课并排分列（默认）
     *   关 → 全宽叠放，后一节盖住前一节，只看得见最上面那门
     *
     * 关掉这个开关的意义：有些人一格里塞两门课反而看不清，
     * 宁愿只看一门、点开详情再看另一门。
     */
    const splitOverlap = cache.overlapMode();

    const sem = semester as GZ.Semester;

    // 星期标签 + 日期
    const dayLabels: Array<{ label: string; date: string }> = [];
    for (let d = 1; d <= 7; d++) {
      const iso = week.dateOf(sem, viewWeek, d);
      const short = iso ? iso.slice(5) : '';
      dayLabels.push({ label: week.WEEKDAY_CN[d], date: short.replace('-', '/') });
    }

    // 每天单独排布，再平移到整周坐标系
    const placed: any[] = [];
    const dayWidthPct = 100 / 7;

    for (let d = 1; d <= 7; d++) {
      const list = layout.sessionsOf(sessions, viewWeek, d);
      const dayItems = splitOverlap
        ? layout.placeDay(list)
        : layout.placeDay(list).map((p) => ({ ...p, col: 0, cols: 1 }));
      for (const p of dayItems) {
        /*
         * 横向坐标全部是「整周宽度的百分比」。
         *
         * 算法：当天起点 = (d-1) * 1/7；块在当天的偏移 = col / cols * 1/7；
         * 块宽 = 1 / cols * 1/7。换成百分比就是下面这样。
         *
         * 每块左右各留 0.35% 的缝 —— 不留的话相邻两列会贴成一片，
         * 看起来像一整块而不是两门课。
         */
        const GAP = 0.35;
        const dayStart = ((d - 1) * 100) / 7;
        const unit = 100 / 7 / p.cols;
        placed.push({
          key: `${d}-${p.session.name}-${p.session.startUnit}-${p.col}`,
          session: p.session,
          left: dayStart + p.col * unit + GAP,
          width: Math.max(1, unit - GAP * 2),
          top: p.top * ROW_H + 3,
          height: p.span * ROW_H - 6,
          span: p.span,
          col: p.col,
          cols: p.cols,
        });
      }
    }

    this.setData({ placed, dayLabels });
  },

  onPickWeek(e: WxEvent) {
    const w = Number(e.currentTarget.dataset.week);
    if (!w || w === this.data.viewWeek) return;
    this.setData({ viewWeek: w, stripScroll: Math.max(0, (w - 3) * 70) }, () =>
      this.rebuild(),
    );
  },

  onTapCourse(e: WxEvent) {
    const item = (e.detail && e.detail.item) as GZ.CourseSession;
    if (!item) return;
    // 存到全局，详情页直接取（数据量小，不走 storage）
    getApp().globalData.detailSession = item;
    wx.navigateTo({ url: '/pages/detail/detail' });
  },

  onToggleGrid() {
    const v = !this.data.gridOn;
    cache.setGridLines(v);
    this.setData({ gridOn: v });
  },

  onOpenSettings() {
    wx.navigateTo({ url: '/pages/settings/settings' });
  },

  onSync() {
    const app2 = getApp();
    if (app2.globalData.syncing) return;
    app2
      .syncSchedule(true)
      .then((r: { ok: boolean; message: string }) => {
        wx.showToast({ title: r.message, icon: r.ok ? 'success' : 'none' });
        if (r.ok) this.refresh();
      })
      .catch(() => {
        wx.showToast({ title: '同步失败', icon: 'none' });
      });
  },

  /** 跟随全局同步状态刷进度条 */
  watchSync() {
    if (this.timer) clearInterval(this.timer);
    this.timer = setInterval(() => {
      const g = getApp().globalData;
      if (!g.syncing && !this.data.syncing) return;
      const p = g.progress || { done: 0, total: 0, label: '' };
      const pct = p.total > 0 ? Math.round((p.done / p.total) * 100) : 0;
      this.setData({
        syncing: !!g.syncing,
        progressLabel: p.label || '',
        progressPct: pct,
      });
      if (!g.syncing) {
        clearInterval(this.timer);
        this.refresh();
      }
    }, 400);
  },

  /** 下拉同步（给 scroll-view 用，暂不开启，留接口） */
  onPullDown() {
    this.onSync();
  },
});