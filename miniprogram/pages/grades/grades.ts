/**
 * 成绩页。
 *
 * 三个 tab 的数据一次全拉，切换不重新请求 —— 成绩不常变，没必要每次切都联网。
 */

import * as cache from '../../store/cache';
import * as gpa from '../../utils/gpa';
import * as fmt from '../../utils/format';

const app = getApp();

Page({
  data: {
    tabs: [
      { key: 'course', label: '课程' },
      { key: 'level', label: '考级' },
      { key: 'fitness', label: '体测' },
    ],
    activeTab: 0,

    grades: [] as GZ.CourseGrade[],
    levels: [] as any[],
    fitness: [] as any[],
    terms: [] as any[],

    gpa: '0.00',
    avgScore: '0.0',
    passedCredit: '0',

    // 学分构成
    creditOpen: false,
    publicOnly: false,
    creditRows: [] as any[],
    creditTotal: '0',
    creditCount: 0,

    syncing: false,
    needLogin: false,
  },

  onLoad() {
    this.load();
  },

  onShow() {
    this.load();
  },

  load() {
    const grades = cache.loadGrades();
    const levels = cache.loadLevels();
    const fitness = cache.loadFitness();

    if (!grades.length) {
      this.setData({
        grades: [],
        levels: levels.map((l: any) => this.decorateLevel(l)),
        fitness: fitness.map((f: any) => this.decorateFitness(f)),
        needLogin: !cache.loginAt(),
      });
      return;
    }

    const w = gpa.weighted(grades);
    const terms = gpa.byTerm(grades).map((t) => ({
      term: t.term,
      count: t.list.length,
      gpa: fmt.d2(gpa.termGpa(t.list)),
      list: t.list.map((g) => ({
        ...g,
        key: `${g.term}|${g.name}|${g.credit}`,
        scoreText: fmt.trim1(g.score),
        creditText: g.credit > 0 ? fmt.trim1(g.credit) : '',
        fail: !g.passed,
      })),
    }));

    this.setData({
      grades,
      levels: levels.map((l: any) => this.decorateLevel(l)),
      fitness: fitness.map((f: any) => this.decorateFitness(f)),
      terms,
      gpa: fmt.d2(w.gpa),
      avgScore: fmt.d1(gpa.avgScore(grades)),
      passedCredit: fmt.trim1(gpa.passedCredit(grades)),
      needLogin: false,
    });

    this.rebuildCredit();
  },

  decorateLevel(l: any): any {
    return {
      ...l,
      key: `${l.name}|${l.term}`,
      totalText: fmt.trim1(l.total),
      parts: l.parts || [],
    };
  },

  decorateFitness(f: any): any {
    return { ...f, key: f.term };
  },

  /** 重算学分构成 */
  rebuildCredit() {
    if (!this.data.creditOpen) return;
    const r = gpa.creditRows(this.data.grades, this.data.publicOnly);
    this.setData({
      creditRows: r.rows.map((row) => ({
        label: row.label,
        ratio: row.ratio,
        creditText: fmt.trim1(row.credit),
      })),
      creditTotal: fmt.trim1(r.total),
      creditCount: r.count,
    });
  },

  onSwitchTab(e: WxEvent) {
    const idx = Number(e.currentTarget.dataset.idx);
    if (idx === this.data.activeTab) return;
    this.setData({ activeTab: idx });
  },

  onToggleCredit() {
    const v = !this.data.creditOpen;
    this.setData({ creditOpen: v }, () => this.rebuildCredit());
  },

  onSwitchCreditScope(e: WxEvent) {
    const pub = Number(e.currentTarget.dataset.pub) === 1;
    if (pub === this.data.publicOnly) return;
    this.setData({ publicOnly: pub }, () => this.rebuildCredit());
  },

  onSync() {
    if (this.data.syncing) return;
    this.setData({ syncing: true });
    app
      .syncGrades()
      .then((r: { ok: boolean; message: string }) => {
        wx.showToast({ title: r.message, icon: r.ok ? 'success' : 'none' });
        this.setData({ syncing: false });
        if (r.ok) {
          cache.setLoginAt();
          this.load();
        } else if (r.message.indexOf('登录') >= 0) {
          this.setData({ needLogin: true });
        }
      })
      .catch(() => {
        this.setData({ syncing: false });
        wx.showToast({ title: '同步失败', icon: 'none' });
      });
  },

  onTapGrade(e: WxEvent) {
    const term = e.currentTarget.dataset.term;
    const name = e.currentTarget.dataset.name;
    // 找出来详情页要展示的完整记录
    const g = this.data.grades.find((x: GZ.CourseGrade) => x.term === term && x.name === name);
    if (!g) return;
    getApp().globalData.detailGrade = g;
    wx.navigateTo({ url: '/pages/detail/detail?type=grade' });
  },

  onGoLogin() {
    wx.reLaunch({ url: '/pages/login/login' });
  },
});