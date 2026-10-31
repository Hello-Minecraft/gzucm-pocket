/**
 * 登录页：扫码登录。
 *
 * 状态机：
 *   loading  → 正在申请二维码
 *   waiting  → 码已出，等扫
 *   scanned  → 已扫码，等确认
 *   expired  → 码过期，让用户重取
 *   error    → 网络/接口出错
 *
 * 轮询用 setTimeout 自己排，不用 setInterval —— 每次请求耗时不固定，
 * setInterval 会堆积请求，学校那边可能触发风控。
 */

import * as auth from '../../services/auth';
import * as request from '../../services/request';
import * as cache from '../../store/cache';
import { encode } from '../../utils/qrcode';

Page({
  data: {
    state: 'loading' as 'loading' | 'waiting' | 'scanned' | 'expired' | 'error',
    qrReady: false,
    scannedWho: '',
    errorText: '',
  },

  /** 当前二维码 id */
  qrcodeId: '',
  /** 轮询是否还在跑 */
  polling: false,
  /** 页面是否还活着（切走后停止轮询） */
  alive: true,

  onLoad() {
    this.alive = true;
    this.refreshQr();
  },

  onUnload() {
    this.alive = false;
    this.polling = false;
  },

  onHide() {
    // 切后台就停轮询，省流量也少打扰学校服务器
    this.polling = false;
  },

  onShow() {
    // 回到前台，如果码还在且没确认，接着轮询
    if (this.data.state === 'waiting' || this.data.state === 'scanned') {
      this.polling = true;
      this.schedulePoll();
    }
  },

  /** 申请新码 */
  refreshQr() {
    this.polling = false;
    this.setData({ state: 'loading', qrReady: false, scannedWho: '', errorText: '' });

    auth
      .createQr()
      .then((info) => {
        if (!this.alive) return;
        this.qrcodeId = info.qrcodeId;
        this.setData({ state: 'waiting' }, () => {
          this.drawQr(info.scanUrl);
          this.polling = true;
          this.schedulePoll();
        });
      })
      .catch((e) => {
        if (!this.alive) return;
        const msg = e instanceof request.NetError ? e.message : '获取二维码失败';
        this.setData({ state: 'error', errorText: msg });
      });
  },

  /** 用 canvas 2d 画二维码 */
  drawQr(text: string) {
    const qr = encode(text);
    if (!qr) {
      this.setData({ state: 'error', errorText: '二维码内容过长' });
      return;
    }

    const query = wx.createSelectorQuery();
    query
      .select('#qr')
      .fields({ node: true, size: true } as any, (res: any) => {
        if (!res || !res.node) {
          // 拿不到 canvas，退化成提示（真机上不应该发生）
          this.setData({ qrReady: false, state: 'error', errorText: '画布初始化失败' });
          return;
        }
        const canvas = res.node;
        const ctx = canvas.getContext('2d');
        const dpr = wx.getSystemInfoSync().pixelRatio || 2;
        const cssW = res.width || 200;
        const cssH = res.height || 200;
        canvas.width = cssW * dpr;
        canvas.height = cssH * dpr;
        ctx.scale(dpr, dpr);

        // 白底
        ctx.fillStyle = '#ffffff';
        ctx.fillRect(0, 0, cssW, cssH);

        const n = qr.size;
        // 留 4 格静区（扫描器需要安静边界）
        const quiet = 4;
        const cell = cssW / (n + quiet * 2);
        ctx.fillStyle = '#000000';
        for (let y = 0; y < n; y++) {
          for (let x = 0; x < n; x++) {
            if (!qr.modules[y][x]) continue;
            ctx.fillRect((x + quiet) * cell, (y + quiet) * cell, cell + 0.5, cell + 0.5);
          }
        }
        this.setData({ qrReady: true });
      })
      .exec();
  },

  /** 排下一次轮询 */
  schedulePoll() {
    if (!this.polling || !this.alive) return;
    setTimeout(() => this.doPoll(), 2500);
  },

  doPoll() {
    if (!this.polling || !this.alive) return;
    const id = this.qrcodeId;
    if (!id) return;

    auth
      .checkQr(id)
      .then((r) => {
        if (!this.alive || !this.polling) return;

        if (r.status === 'confirmed' && r.url) {
          this.polling = false;
          this.setData({ state: 'loading' });
          this.finishLogin(r.url);
          return;
        }
        if (r.status === 'expired') {
          this.polling = false;
          this.setData({ state: 'expired' });
          return;
        }
        if (r.status === 'scanned') {
          if (this.data.state !== 'scanned') {
            this.setData({ state: 'scanned', scannedWho: r.userLabel || '' });
          }
        } else if (this.data.state === 'scanned') {
          // 从 scanned 退回 waiting（用户取消确认），刷新界面
          this.setData({ state: 'waiting', scannedWho: '' });
        }
        this.schedulePoll();
      })
      .catch(() => {
        // 网络抖动不终止，继续轮询
        this.schedulePoll();
      });
  },

  /** 拿确认后的 url 换 Cookie，然后进首页 */
  finishLogin(url: string) {
    auth
      .finishLogin(url)
      .then(() => {
        if (!request.hasAuth()) {
          // 请求成功但没拿到会话，说明学校那边没认
          this.setData({ state: 'error', errorText: '登录没有生效，请再试一次' });
          return;
        }
        cache.setLoginAt();
        wx.showToast({ title: '登录成功', icon: 'success' });
        // 进首页并立刻开始同步
        setTimeout(() => {
          wx.switchTab({ url: '/pages/today/today' });
          const app = getApp();
          app.syncSchedule(true).then((r: any) => {
            if (!r.ok) {
              wx.showToast({ title: r.message || '课表同步失败', icon: 'none' });
            }
          });
        }, 400);
      })
      .catch((e) => {
        const msg = e instanceof request.NetError ? e.message : '登录请求失败';
        this.setData({ state: 'error', errorText: msg });
      });
  },

  /** 复制链接，让用户能在别的浏览器里打开（备选路径） */
  onCopyLink() {
    if (!this.qrcodeId) return;
    wx.setClipboardData({ data: this.qrcodeId });
  },
});