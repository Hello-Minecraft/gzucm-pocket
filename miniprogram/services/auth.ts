/**
 * 扫码登录。
 *
 * 为什么只做扫码：
 *  · 密码登录要复刻 AES-ECB + 图片验证码，且**密码会经过小程序**。
 *    Android 版能做到"不碰密码"（用户在 WebView 里输给学校页面），
 *    小程序做不到，这是产品性质上的降级，不做。
 *  · CAS 统一认证要跑 tac.min.js，小程序没 DOM。
 *
 * 扫码的流程全在 HTTP 上，小程序能做：
 *   ① POST /new/qrcodeLogin/qrcode        → qrcodeId + confirmUrl
 *   ② 本地画二维码（confirmUrl 是相对路径，拼上域名）
 *   ③ 轮询 /new/qrcodeLogin/qrcodeStatus  → status 0/1/2
 *   ④ status=2 时返回 url，请求一次即落地 JSESSIONID
 *
 * 注意：轮询间隔别太快。学校那边 1800 秒有效，2.5 秒一次足够，
 * 一分钟也才 24 次请求，属于正常浏览器的量级。
 */

import * as http from './request';
import * as cookie from './cookie';

const POLL_INTERVAL = 2500;
const MAX_POLL = 720; // 1800s / 2.5s

export interface QrInfo {
  qrcodeId: string;
  /** 需要拼上域名才能生成可扫的码 */
  scanUrl: string;
}

/** 扫码状态 */
export type QrStatus = 'waiting' | 'scanned' | 'confirmed' | 'expired';

export interface QrStatusResult {
  status: QrStatus;
  /** status=confirmed 时才有，请求它即可完成登录 */
  url?: string;
  /** status=scanned 时有值 */
  userLabel?: string;
}

/** 第一步：申请一个二维码 */
export async function createQr(): Promise<QrInfo> {
  const text = await http.post(
    '/new/qrcodeLogin/qrcode',
    { qrcodeId: '' },
    http.JW_BASE + '/',
  );
  const root = http.parseJson(text, '二维码响应');
  if (Number(root.code) < 0) {
    throw new http.NetError('http', root.message || '二维码获取失败');
  }
  // data 是「JSON 字符串套在 JSON 里」，要再 parse 一次
  let info: any = {};
  try {
    info = typeof root.data === 'string' ? JSON.parse(root.data) : root.data || {};
  } catch (e) {
    throw new http.NetError('parse', '二维码数据异常');
  }
  const id = info.qrcodeId || '';
  const confirm = info.confirmUrl || '';
  if (!id || !confirm) {
    throw new http.NetError('parse', '二维码数据不完整');
  }
  // confirmUrl 是 "/#/qrcodeConfirm/showConfirm?..."，补上协议和域名才是可扫的
  const scanUrl = confirm.indexOf('http') === 0 ? confirm : http.JW_BASE + confirm;
  return { qrcodeId: id, scanUrl };
}

/** 第二步：查一次状态（单次，不循环） */
export async function checkQr(qrcodeId: string): Promise<QrStatusResult> {
  const text = await http.post(
    '/new/qrcodeLogin/qrcodeStatus',
    { qrcodeId },
    http.JW_BASE + '/',
  );
  const root = http.parseJson(text, '扫码状态');
  if (Number(root.code) < 0) {
    return { status: 'expired' };
  }
  let info: any = {};
  try {
    info = typeof root.data === 'string' ? JSON.parse(root.data) : root.data || {};
  } catch (e) {
    return { status: 'expired' };
  }
  const st = String(info.status === undefined ? '-1' : info.status);

  if (st === '0') return { status: 'waiting' };
  if (st === '1') {
    const kind = info.usertype === 't' ? '工号' : '学号';
    const label = info.userbh ? `${kind} ${info.userbh}` : '';
    const name = info.userxm ? ` ${info.userxm}` : '';
    return { status: 'scanned', userLabel: (label + name).trim() };
  }
  if (st === '2' && info.url) {
    return { status: 'confirmed', url: String(info.url) };
  }
  return { status: 'expired' };
}

/**
 * 第三步：拿着确认后的 url 去请求一次，落下 JSESSIONID。
 *
 * url 可能是绝对地址也可能相对，统一拼成完整地址再交给 HTTP 层。
 * 这里不走 http.post —— 那个只允许白名单路径；登录回跳地址是动态的。
 */
export function finishLogin(url: string): Promise<void> {
  return new Promise((resolve, reject) => {
    const full = url.indexOf('http') === 0 ? url : http.JW_BASE + url;
    const host = http.JW_HOST;
    const ck = cookie.header(host, '/');
    const header: Record<string, string> = { 'X-Requested-With': 'XMLHttpRequest' };
    if (ck) header['Cookie'] = ck;

    // 走云函数转发：云函数支持任意 /new/ 下的路径吗？不支持。
    // 所以这里单独开一个能力——见 services/request.ts 的 finishLogin 说明。
    wx.cloud.callFunction({
      name: 'jwproxy',
      data: {
        host: http.JW_BASE,
        path: '',
        absoluteUrl: full,
        method: 'GET',
        form: {},
        cookie: ck,
      },
      success: (res) => {
        const r = (res.result || {}) as {
          status?: number;
          setCookies?: string[];
          error?: string;
        };
        if (r.error) {
          reject(new http.NetError('unreachable', r.error));
          return;
        }
        if (r.setCookies && r.setCookies.length) {
          cookie.absorb(host, r.setCookies);
        }
        resolve();
      },
      fail: (e) => {
        reject(new http.NetError('unreachable', (e && e.errMsg) || '登录请求失败'));
      },
    });
  });
}

/**
 * 完整轮询。onUpdate 每变一次状态回调一次，直到 confirmed / expired / 超时。
 * 返回 'confirmed' | 'expired' | 'timeout'
 */
export async function pollUntilDone(
  qrcodeId: string,
  onUpdate: (r: QrStatusResult) => void,
  shouldStop?: () => boolean,
): Promise<{ result: 'confirmed' | 'expired' | 'timeout'; url?: string }> {
  for (let k = 0; k < MAX_POLL; k++) {
    if (shouldStop && shouldStop()) return { result: 'timeout' };
    let r: QrStatusResult;
    try {
      r = await checkQr(qrcodeId);
    } catch (e) {
      // 网络抖一下不算失败，继续轮询
      await sleep(POLL_INTERVAL);
      continue;
    }
    onUpdate(r);
    if (r.status === 'confirmed') return { result: 'confirmed', url: r.url };
    if (r.status === 'expired') return { result: 'expired' };
    await sleep(POLL_INTERVAL);
  }
  return { result: 'timeout' };
}

function sleep(ms: number): Promise<void> {
  return new Promise((r) => setTimeout(r, ms));
}

/** 退出登录 */
export function logout(): void {
  cookie.clear(http.JW_HOST);
}