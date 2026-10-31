/**
 * 详情页。课程和成绩共用一个页面，靠 ?type= 区分。
 *
 * 数据不走 storage 中转：调用方把对象塞进 globalData.detailSession / detailGrade，
 * 这里直接取。理由是小程序 storage 有序列化开销，而这两个对象本来就是内存里的。
 */

import * as week from '../../utils/week';
import * as layout from '../../utils/layout';
import * as cache from '../../store/cache';
import * as fmt from '../../utils/format';

Page({
  data: {
    isGrade: false,
    course: null as GZ.CourseSession | null,
    grade: null as GZ.CourseGrade | null,
    color: 'var(--primary)',
    weekLabel: '',
    timeLabel: '',
    dateLabel: '',
    extra: [] as Array<{ k: string; v: string }>,
    siblings: [] as any[],
    scoreText: '',
    gpaText: '',
    creditText: '',
  },

  onLoad(query: any) {
    const type = (query && query.type) || '';
    if (type === 'grade') {
      this.loadGrade();
    } else {
      this.loadCourse();
    }
  },

  onBack() {
    wx.navigateBack({ delta: 1 });
  },

  // ── 课程 ────────────────────────────────────────────────

  loadCourse() {
    const app = getApp();
    const item = app.globalData.detailSession as GZ.CourseSession | null;
    if (!item) {
      wx.showToast({ title: '数据已失效', icon: 'none' });
      return;
    }

    const sem: GZ.Semester | null = app.globalData.semester || cache.loadSemester();

    // 节次标签：起始–结束节
    const timeLabel = `${item.startTime}–${item.endTime} · 第 ${item.startUnit}${
      item.endUnit > item.startUnit ? '–' + item.endUnit : ''
    } 节`;

    // 日期：优先用 qsrq（按周抓到的才有），否则按学期推算
    let dateLabel = '—';
    if (item.date) {
      dateLabel = `${item.date} 周${week.WEEKDAY_CN[item.weekday]}`;
    } else if (sem) {
      const iso = week.dateOf(sem, item.week, item.weekday);
      if (iso) dateLabel = `${iso} 周${week.WEEKDAY_CN[item.weekday]}`;
    }

    // 教学信息：只显示有值的
    const extra: Array<{ k: string; v: string }> = [];
    if (item.mergedClasses) extra.push({ k: '教学班', v: item.mergedClasses });
    if (item.studyHours) extra.push({ k: '学时', v: item.studyHours });
    if (item.courseSeq) extra.push({ k: '课程编号', v: item.courseSeq });
    if (item.code) extra.push({ k: '课程代码', v: item.code });

    // 同一天的其他课（去掉自己）
    const sessions: GZ.CourseSession[] =
      app.globalData.sessions && app.globalData.sessions.length
        ? app.globalData.sessions
        : cache.loadSessions();
    const sameDay = layout
      .sessionsOf(sessions, item.week, item.weekday)
      .filter((s) => !(s.name === item.name && s.startUnit === item.startUnit))
      .sort((a, b) => a.startUnit - b.startUnit);

    this.setData({
      isGrade: false,
      course: item,
      weekLabel: `第 ${item.week} 周`,
      timeLabel,
      dateLabel,
      extra,
      siblings: sameDay.map((s, k) => ({ ...s, key: `${s.name}-${s.startUnit}-${k}` })),
      color: 'var(--primary)',
    });

    wx.setNavigationBarTitle({ title: item.name });
  },

  onTapSibling(e: WxEvent) {
    const idx = Number(e.currentTarget.dataset.idx);
    const s = this.data.siblings[idx];
    if (!s) return;
    getApp().globalData.detailSession = s;
    this.loadCourse();
  },

  // ── 成绩 ────────────────────────────────────────────────

  loadGrade() {
    const app = getApp();
    const g = app.globalData.detailGrade as GZ.CourseGrade | null;
    if (!g) {
      wx.showToast({ title: '数据已失效', icon: 'none' });
      return;
    }
    this.setData({
      isGrade: true,
      grade: g,
      scoreText: fmt.trim1(g.score),
      gpaText: g.gpa > 0 ? fmt.d1(g.gpa) : '—',
      creditText: g.credit > 0 ? `${fmt.trim1(g.credit)} 学分` : '—',
    });
    wx.setNavigationBarTitle({ title: g.name });
  },
});