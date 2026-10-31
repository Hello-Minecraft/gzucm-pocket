/**
 * 小程序入口。
 *
 * 只做三件事：
 *   ① 初始化云开发（云函数转发必需）
 *   ② 检查登录态，没登录就跳登录页
 *   ③ 提供全局的同步编排（课表 + 成绩），页面只负责展示
 */

import * as cache from './store/cache';
import * as jw from './services/jw';
import * as request from './services/request';
import * as week from './utils/week';

interface GlobalData {
  semester: GZ.Semester | null;
  sessions: GZ.CourseSession[];
  syncing: boolean;
  syncError: string;
  /** 同步进度，给界面画进度条 */
  progress: GZ.SyncProgress;
}

App({
  globalData: {
    semester: null,
    sessions: [],
    syncing: false,
    syncError: '',
    progress: { done: 0, total: 0, label: '' },
  },

  onLaunch() {
    // 云开发：云函数 jwproxy 是唯一能在正式环境访问教务的通道
    if (wx.cloud) {
      wx.cloud.init({ traceUser: false });
    } else {
      // 基础库太老，强制降级为直连（只能在开发工具里用）
      request.setUseProxy(false);
    }

    // 先把缓存铺上，避免首屏空白
    const sem = cache.loadSemester();
    const list = cache.loadSessions();
    if (sem && list.length) {
      this.globalData.semester = sem;
      this.globalData.sessions = list;
    }

    // 没会话就跳登录页（不阻塞启动，让首页先渲染空态）
    if (!request.hasAuth()) {
      wx.reLaunch({ url: '/pages/login/login' });
      return;
    }

    /*
     * 默认启动页。
     *
     * tabBar 的第一个 tab 是「今日」，小程序启动时默认就落在那里。
     * 如果用户设了别的，这里主动跳一次。
     *
     * 用 reLaunch 而不是 switchTab：启动瞬间还没进任何页面，
     * switchTab 在 onLaunch 里调不成立（会报 "not in tabBar page"）。
     * 代价是多一次页面初始化，但只在启动时发生一次，可接受。
     */
    const tab = cache.startTab();
    if (tab === 1) {
      wx.reLaunch({ url: '/pages/schedule/schedule' });
    } else if (tab === 2) {
      wx.reLaunch({ url: '/pages/grades/grades' });
    }
  },

  /**
   * 同步课表。
   *
   * 策略与 Android 版一致：
   *  · 先拿校历（学期第一周周一 + 总周数）
   *  · 再跳着抓：当前周单独抓（要 sknrjj 上课内容），其余周并发抓
   *  · 单周失败不拖垮整体，那周留空，下次补
   */
  async syncSchedule(force?: boolean): Promise<{ ok: boolean; message: string }> {
    if (this.globalData.syncing) return { ok: false, message: '正在同步中' };
    this.globalData.syncing = true;
    this.globalData.syncError = '';

    try {
      // ① 学期：优先用缓存，没有就按当前日期猜
      let sem = cache.loadSemester();
      if (!sem || force) {
        const code = sem ? sem.code : week.guessSemesterCode();
        const fetched = await jw.fetchSemester(code);
        if (fetched) {
          sem = fetched;
          cache.saveSemester(sem);
        } else if (!sem) {
          throw new request.NetError('parse', '拿不到校历数据');
        }
      }
      this.globalData.semester = sem;

      // ② 当前周：单独抓，能拿到 sknrjj（上课内容）
      const cw = week.currentWeek(sem);
      this.globalData.progress = {
        done: 0,
        total: sem.totalWeeks + 1,
        label: `正在抓第 ${cw} 周`,
      };

      let current: GZ.CourseSession[] = [];
      try {
        current = await jw.fetchWeek(sem, cw);
      } catch (e) {
        // 当前周抓失败不算致命，全量抓会补上
        current = [];
      }

      // ③ 其余周并发抓
      this.globalData.progress = {
        done: 1,
        total: sem.totalWeeks + 1,
        label: '正在抓其余周次',
      };
      const rest = await jw.fetchWeeksExcept(sem, cw, (done, total) => {
        this.globalData.progress = {
          done: done + 1,
          total: total + 1,
          label: `正在抓其余周次 ${done}/${total}`,
        };
      });

      // ④ 合并：当前周那批优先（信息更全），其余周去掉重复的同一节
      const merged: GZ.CourseSession[] = current.slice();
      const seen: Record<string, number> = {};
      for (const s of current) seen[keyOf(s)] = 1;
      for (const s of rest) {
        const k = keyOf(s);
        if (seen[k]) continue;
        seen[k] = 1;
        merged.push(s);
      }

      merged.sort((a, b) => a.week - b.week || a.weekday - b.weekday || a.startUnit - b.startUnit);

      this.globalData.sessions = merged;
      cache.saveSessions(merged);
      this.globalData.progress = { done: 0, total: 0, label: '' };
      return { ok: true, message: `已同步 ${merged.length} 节课` };
    } catch (e) {
      const msg = e instanceof request.NetError ? e.message : '同步失败';
      this.globalData.syncError = msg;
      this.globalData.progress = { done: 0, total: 0, label: '' };
      if (e instanceof request.NetError && e.kind === 'unauthorized') {
        wx.reLaunch({ url: '/pages/login/login' });
        return { ok: false, message: '登录已过期，请重新登录' };
      }
      return { ok: false, message: msg };
    } finally {
      this.globalData.syncing = false;
    }
  },

  /** 同步成绩（三门一起拉） */
  async syncGrades(): Promise<{ ok: boolean; message: string }> {
    try {
      const g = await jw.fetchCourseGrades();
      cache.saveGrades(g);
      // 考级和体测失败不影响主流程
      try {
        const lv = await jw.fetchLevelGrades();
        cache.saveLevels(lv);
      } catch (e) {
        /* 忽略 */
      }
      try {
        const ft = await jw.fetchFitness();
        cache.saveFitness(ft);
      } catch (e) {
        /* 忽略 */
      }
      return { ok: true, message: `已同步 ${g.length} 门成绩` };
    } catch (e) {
      const msg = e instanceof request.NetError ? e.message : '成绩同步失败';
      if (e instanceof request.NetError && e.kind === 'unauthorized') {
        wx.reLaunch({ url: '/pages/login/login' });
        return { ok: false, message: '登录已过期，请重新登录' };
      }
      return { ok: false, message: msg };
    }
  },
});

/** 判断重复用的键：同一门课、同一周、同一天、同一起始节，就算同一节 */
function keyOf(s: GZ.CourseSession): string {
  return `${s.name}|${s.week}|${s.weekday}|${s.startUnit}|${s.endUnit}|${s.room}`;
}