/**
 * 今日页。
 *
 * 核心是"焦点卡"：正在上的课优先，没有就取下一节。
 * 学生打开 App 十有八九就是想确认"我现在该去哪个教室"。
 */

import * as cache from '../../store/cache';
import * as week from '../../utils/week';
import * as layout from '../../utils/layout';

const app = getApp();

Page({
  data: {
    greeting: '今日',
    dateText: '',
    weekText: '',
    todayList: [] as any[],
    weekStats: { courses: 0, sections: 0 } as { courses: number; sections: number },
    focus: null as any,
    loading: true,
  },

  onLoad() {
    this.refresh();
  },

  onShow() {
    this.refresh();
  },

  onUnload() {
    if (this.timer) clearInterval(this.timer);
  },

  refresh() {
    const sem: GZ.Semester | null = app.globalData.semester || cache.loadSemester();
    const sessions: GZ.CourseSession[] =
      app.globalData.sessions && app.globalData.sessions.length
        ? app.globalData.sessions
        : cache.loadSessions();

    // 问候语按时间变 —— 小地方，但让人感觉 App 是活的
    const hour = new Date().getHours();
    const greeting = hour < 6 ? '夜深了' : hour < 11 ? '早上好' : hour < 14 ? '中午好' : hour < 18 ? '下午好' : '晚上好';

    if (!sem || !sessions.length) {
      const now = new Date();
      this.setData({
        greeting,
        dateText: `${now.getMonth() + 1} 月 ${now.getDate()} 日`,
        weekText: '',
        todayList: [],
        focus: null,
        loading: app.globalData.syncing,
      });
      return;
    }

    const cw = week.currentWeek(sem);
    const tw = week.weekdayOf(week.todayStart());
    const nowMin = layout.nowMinutes();
    const today = layout.sessionsOf(sessions, cw, tw).slice().sort(
      (a, b) => a.startUnit - b.startUnit,
    );

    const todayList = today.map((s, k) => {
      const end = layout.toMinutes(s.endTime);
      const start = layout.toMinutes(s.startTime);
      return {
        key: `${s.name}-${s.startUnit}-${k}`,
        session: s,
        past: end > 0 && end < nowMin,
        live: start > 0 && end > 0 && nowMin >= start && nowMin < end,
        color: 'var(--primary)',
      };
    });

    // 焦点：正在上 > 下一节 > 今天没有
    let focus: any = null;
    const live = layout.currentSession(sessions, cw, tw, nowMin);
    if (live) {
      focus = { session: live, live: true, countdown: this.countdownText(live.endTime, nowMin, false) };
    } else {
      const next = layout.nextSession(sessions, cw, tw, nowMin);
      if (next) {
        focus = { session: next, live: false, countdown: this.countdownText(next.startTime, nowMin, true) };
      }
    }

    const d = new Date();
    this.setData({
      greeting,
      dateText: `${d.getMonth() + 1} 月 ${d.getDate()} 日 周${week.WEEKDAY_CN[tw]}`,
      weekText: `第 ${cw} 周`,
      todayList,
      weekStats: layout.weekStats(sessions, cw),
      focus,
      loading: false,
    });

    this.startTick();
  },

  /** 每分钟重算倒计时，不用整页刷新 */
  startTick() {
    if (this.timer) clearInterval(this.timer);
    this.timer = setInterval(() => {
      const g = app.globalData;
      const sem: GZ.Semester | null = g.semester || cache.loadSemester();
      if (!sem) return;
      const sessions: GZ.CourseSession[] = g.sessions && g.sessions.length ? g.sessions : cache.loadSessions();
      const cw = week.currentWeek(sem);
      const tw = week.weekdayOf(week.todayStart());
      const nowMin = layout.nowMinutes();
      const live = layout.currentSession(sessions, cw, tw, nowMin);
      if (live) {
        this.setData({
          focus: { session: live, live: true, countdown: this.countdownText(live.endTime, nowMin, false) },
        });
      } else {
        const next = layout.nextSession(sessions, cw, tw, nowMin);
        this.setData({
          focus: next
            ? { session: next, live: false, countdown: this.countdownText(next.startTime, nowMin, true) }
            : null,
        });
      }
    }, 60000);
  },

  /** "还有 25 分钟" / "还剩 10 分钟下课" */
  countdownText(hhmm: string, nowMin: number, toStart: boolean): string {
    const target = layout.toMinutes(hhmm);
    if (!target) return '';
    const diff = target - nowMin;
    if (diff <= 0) return '';
    if (toStart) {
      if (diff <= 1) return '马上开始';
      if (diff < 60) return `还有 ${diff} 分钟`;
      const h = Math.floor(diff / 60);
      const m = diff % 60;
      return m ? `还有 ${h} 小时 ${m} 分钟` : `还有 ${h} 小时`;
    }
    return diff <= 1 ? '就要下课' : `还剩 ${diff} 分钟下课`;
  },

  onTapCourse(e: WxEvent) {
    const idx = Number(e.currentTarget.dataset.idx);
    const item = this.data.todayList[idx];
    if (!item) return;
    getApp().globalData.detailSession = item.session;
    wx.navigateTo({ url: '/pages/detail/detail' });
  },

  onOpenSettings() {
    wx.navigateTo({ url: '/pages/settings/settings' });
  },
});