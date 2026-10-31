/**
 * 设置页。
 *
 * 所有设置项都只是「读写 cache.ts + 立即生效」，没有中间状态。
 * 唯一例外是「请求通道」：切换后要重启小程序才生效，所以给了明确提示。
 */

import * as cache from '../../store/cache';
import * as request from '../../services/request';
import * as auth from '../../services/auth';
import { ago } from '../../utils/format';

Page({
  data: {
    loggedIn: false,

    tabOptions: ['今日', '课表', '成绩'],
    startTab: 0,
    overlapMode: false,
    gridLines: false,

    bgPath: '',
    bgAlphaPct: 18,

    sessionsCount: 0,
    syncAtText: '',
    gradesCount: 0,
    gradesAtText: '',
    usageText: '',

    isProxy: true,
  },

  onLoad() {
    this.reload();
  },

  onShow() {
    this.reload();
  },

  reload() {
    const syncAt = cache.syncAt();
    const gradesAt = cache.gradesAt();
    this.setData({
      loggedIn: request.hasAuth(),
      startTab: cache.startTab(),
      overlapMode: cache.overlapMode(),
      gridLines: cache.gridLines(),
      bgPath: cache.bgPath(),
      bgAlphaPct: Math.round(cache.bgAlpha() * 100),
      sessionsCount: cache.loadSessions().length,
      syncAtText: syncAt ? ago(syncAt) : '还没同步',
      gradesCount: cache.loadGrades().length,
      gradesAtText: gradesAt ? ago(gradesAt) : '还没同步',
      usageText: `${cache.usageKB()} KB`,
      isProxy: request.isProxy(),
    });
  },

  onBack() {
    wx.navigateBack({ delta: 1 });
  },

  // ── 课表设置 ────────────────────────────────────────────

  onPickStartTab(e: WxEvent) {
    const idx = Number(e.currentTarget.dataset.idx);
    if (idx === this.data.startTab) return;
    cache.setStartTab(idx);
    this.setData({ startTab: idx });
  },

  onToggleOverlap(e: any) {
    const v = !!(e.detail && e.detail.value);
    cache.setOverlapMode(v);
    this.setData({ overlapMode: v });
  },

  onToggleGrid(e: any) {
    const v = !!(e.detail && e.detail.value);
    cache.setGridLines(v);
    this.setData({ gridLines: v });
  },

  // ── 背景 ────────────────────────────────────────────────

  onPickBg() {
    wx.chooseMedia({
      count: 1,
      mediaType: ['image'],
      success: (res) => {
        const temp = res.tempFiles && res.tempFiles[0];
        if (!temp) return;
        // 临时文件重启就没了，必须另存一份到用户目录
        wx.getFileSystemManager().saveFile({
          tempFilePath: temp.tempFilePath,
          success: (saved) => {
            const old = cache.bgPath();
            // 换新图之前把旧的删掉，不然会越堆越多
            if (old) {
              wx.getFileSystemManager().unlink({ filePath: old, fail: () => {} });
            }
            cache.setBgPath(saved.savedFilePath);
            this.setData({ bgPath: saved.savedFilePath });
            wx.showToast({ title: '已设置', icon: 'success' });
          },
          fail: () => {
            wx.showToast({ title: '保存失败', icon: 'none' });
          },
        });
      },
      fail: () => {
        /* 用户取消，不提示 */
      },
    });
  },

  onClearBg() {
    const old = cache.bgPath();
    if (old) {
      wx.getFileSystemManager().unlink({ filePath: old, fail: () => {} });
    }
    cache.setBgPath('');
    this.setData({ bgPath: '' });
  },

  onAlphaChanging(e: any) {
    // 拖动过程中只更新显示，不写存储（避免每帧写一次）
    const v = Number(e.detail && e.detail.value);
    this.setData({ bgAlphaPct: v });
  },

  onAlphaChange(e: any) {
    const v = Number(e.detail && e.detail.value);
    cache.setBgAlpha(v / 100);
    this.setData({ bgAlphaPct: v });
  },

  // ── 数据 ────────────────────────────────────────────────

  onSyncSchedule() {
    const app = getApp();
    if (app.globalData.syncing) {
      wx.showToast({ title: '正在同步中', icon: 'none' });
      return;
    }
    wx.showLoading({ title: '同步课表…', mask: true });
    app
      .syncSchedule(true)
      .then((r: { ok: boolean; message: string }) => {
        wx.hideLoading();
        wx.showToast({ title: r.message, icon: r.ok ? 'success' : 'none' });
        this.reload();
      })
      .catch(() => {
        wx.hideLoading();
        wx.showToast({ title: '同步失败', icon: 'none' });
      });
  },

  onSyncGrades() {
    const app = getApp();
    wx.showLoading({ title: '同步成绩…', mask: true });
    app
      .syncGrades()
      .then((r: { ok: boolean; message: string }) => {
        wx.hideLoading();
        wx.showToast({ title: r.message, icon: r.ok ? 'success' : 'none' });
        this.reload();
      })
      .catch(() => {
        wx.hideLoading();
        wx.showToast({ title: '同步失败', icon: 'none' });
      });
  },

  onClearData() {
    wx.showModal({
      title: '清除本地数据',
      content: '课表和成绩缓存会被清掉，下次打开需要重新同步。界面设置会保留。',
      confirmText: '清除',
      success: (res) => {
        if (!res.confirm) return;
        cache.clearData();
        getApp().globalData.sessions = [];
        getApp().globalData.semester = null;
        this.reload();
        wx.showToast({ title: '已清除', icon: 'success' });
      },
    });
  },

  // ── 网络 ────────────────────────────────────────────────

  onToggleProxy(e: any) {
    const direct = !!(e.detail && e.detail.value);
    const nextProxy = !direct;
    request.setUseProxy(nextProxy);
    this.setData({ isProxy: nextProxy });
    wx.showToast({
      title: nextProxy ? '已切换到云函数' : '已切换到直连',
      icon: 'none',
    });
  },

  // ── 退出登录 ────────────────────────────────────────────

  onLogout() {
    wx.showModal({
      title: '退出登录',
      content: '会清掉登录会话和所有缓存，下次要重新扫码。',
      confirmText: '退出',
      success: (res) => {
        if (!res.confirm) return;
        auth.logout();
        cache.clearData();
        wx.reLaunch({ url: '/pages/login/login' });
      },
    });
  },
});