/**
 * 统一请求层。
 *
 * 两条通道，自动选择：
 *
 *  ① 直连（DIRECT）：wx.request 直接打 jw.gzucm.edu.cn。
 *     前提是域名已配进小程序的 request 白名单 —— 学校域名你加不进去，
 *     所以正式环境基本走不通，只能「不校验合法域名」的开发模式用。
 *
 *  ② 云函数转发（PROXY）：wx.cloud.callFunction 打到自己的云函数，
 *     由云函数代发 HTTP。云函数出口不受小程序域名白名单限制。
 *     这是唯一能在正式环境工作的方式。
 *
 * 关键设计：**Cookie 始终存在手机本地**，每次请求由客户端把 Cookie 串
 * 作为参数传给云函数，云函数原样塞进请求头，转发完就丢掉。
 * 云函数不落盘、不记日志、不持有会话 —— 它只是一根管子。
 */

import * as cookie from './cookie';

export const JW_HOST = 'jw.gzucm.edu.cn';
export const JW_BASE = 'https://jw.gzucm.edu.cn';

/** 请求通道。true = 走云函数（正式环境），false = 直连（开发调试） */
let useProxy = true;
/** 云函数名，部署时保持一致 */
const FN = 'jwproxy';

export function setUseProxy(v: boolean): void {
  useProxy = v;
}

export function isProxy(): boolean {
  return useProxy;
}

/** 请求错误：和 Android 的 NetError 对齐，方便照着翻文案 */
export class NetError extends Error {
  kind: 'unreachable' | 'unauthorized' | 'http' | 'parse' | 'noauth';
  code: number;
  detail: string;

  constructor(kind: NetError['kind'], message: string, code = 0, detail = '') {
    super(message);
    this.kind = kind;
    this.code = code;
    this.detail = detail;
    this.name = 'NetError';
  }
}

interface RawResp {
  status: number;
  body: string;
  setCookies: string[];
}

/** 云函数调用（正式通道） */
function callProxy(
  method: 'GET' | 'POST',
  path: string,
  form: Record<string, string>,
  referer: string,
): Promise<RawResp> {
  return new Promise((resolve, reject) => {
    const ck = cookie.header(JW_HOST, path);
    wx.cloud.callFunction({
      name: FN,
      data: {
        host: 'https://' + JW_HOST,
        path,
        method,
        form,
        referer,
        cookie: ck,
      },
      success: (res) => {
        const r = (res.result || {}) as {
          status?: number;
          body?: string;
          setCookies?: string[];
          error?: string;
        };
        if (r.error) {
          reject(new NetError('unreachable', r.error));
          return;
        }
        const status = r.status || 0;
        const body = r.body || '';
        const setCookies = r.setCookies || [];
        if (setCookies.length) cookie.absorb(JW_HOST, setCookies);
        resolve({ status, body, setCookies });
      },
      fail: (e) => {
        reject(new NetError('unreachable', (e && e.errMsg) || '云函数调用失败'));
      },
    });
  });
}

/** 直连（开发通道，需要勾「不校验合法域名」） */
function callDirect(
  method: 'GET' | 'POST',
  path: string,
  form: Record<string, string>,
  referer: string,
): Promise<RawResp> {
  return new Promise((resolve, reject) => {
    const ck = cookie.header(JW_HOST, path);
    const header: Record<string, string> = {
      // 教务接口要求带这个头，否则返回 -401「疑似非法请求」
      'X-Requested-With': 'XMLHttpRequest',
    };
    if (method === 'POST') header['Content-Type'] = 'application/x-www-form-urlencoded';
    if (ck) header['Cookie'] = ck;
    // 注意：小程序不允许设 Referer，这里写了也会被丢弃。留着只为阅读时知道意图
    if (referer) header['Referer'] = referer;

    wx.request({
      url: JW_BASE + path,
      method,
      header,
      data: form,
      timeout: 25000,
      success: (res) => {
        // iOS/Android 都可能给 cookies 数组，也可能只给 header['Set-Cookie']
        let setCookies: string[] = [];
        const anyRes = res as unknown as { cookies?: string[]; header?: Record<string, string> };
        if (anyRes.cookies && anyRes.cookies.length) {
          setCookies = anyRes.cookies;
        } else if (anyRes.header) {
          const raw = anyRes.header['Set-Cookie'] || anyRes.header['set-cookie'];
          if (raw) setCookies = String(raw).split(/,(?=[^;,]+=)/);
        }
        if (setCookies.length) cookie.absorb(JW_HOST, setCookies);
        resolve({
          status: res.statusCode,
          body: typeof res.data === 'string' ? res.data : JSON.stringify(res.data),
          setCookies,
        });
      },
      fail: (e) => {
        reject(new NetError('unreachable', (e && e.errMsg) || '网络不可达'));
      },
    });
  });
}

/**
 * 这段文本是不是统一身份认证的登录页。
 *
 * 学校会话过期时**不返 401**：302 跳 CAS，最终 200 返回登录页 HTML。
 * 不识别的话，上层会拿登录页去 JSON.parse，报「数据格式不对」——
 * 用户看到莫名其妙的话，其实只是要重新登录。
 *
 * 判据和 Android 版一模一样：
 *  ① 以 < 开头（JSON 直接排除，零误判）+ 含「统一身份认证」或 authserver + execution
 *  ② JSON 形式：code:-401 / 含「尚未登录」「已在别处登录」
 */
export function looksLikeLoginPage(text: string): boolean {
  if (!text) return false;
  const t = text.replace(/^\s+/, '');

  if (t.charAt(0) === '<') {
    return (
      t.indexOf('统一身份认证') >= 0 ||
      (t.indexOf('authserver.gzucm.edu.cn') >= 0 && t.indexOf('execution') >= 0)
    );
  }

  if (t.charAt(0) === '{' && t.length < 4000) {
    if (t.indexOf('"-401"') >= 0 || t.indexOf(':-401') >= 0) return true;
    if (t.indexOf('尚未登录') >= 0 || t.indexOf('已在别处登录') >= 0) return true;
  }
  return false;
}

/** 真正的请求入口：带登录页判定、错误分类 */
async function send(
  method: 'GET' | 'POST',
  path: string,
  form: Record<string, string>,
  referer: string,
): Promise<string> {
  const resp = useProxy
    ? await callProxy(method, path, form, referer)
    : await callDirect(method, path, form, referer);

  const { status, body } = resp;

  if (status >= 200 && status < 300) {
    if (looksLikeLoginPage(body)) {
      throw new NetError('unauthorized', '登录已过期', status);
    }
    return body;
  }
  if (status === 401 || status === 403) {
    throw new NetError('unauthorized', `登录已失效（HTTP ${status}）`, status);
  }
  if (!status) {
    throw new NetError('unreachable', '没有拿到响应');
  }
  throw new NetError('http', `服务器返回 ${status}`, status, body.slice(0, 120));
}

/** 表单 POST */
export function post(path: string, form: Record<string, string>, referer: string): Promise<string> {
  return send('POST', path, form, referer);
}

/** GET */
export function get(path: string, referer: string): Promise<string> {
  return send('GET', path, {}, referer);
}

/** 解析 JSON，失败抛 Parse */
export function parseJson(text: string, what: string): any {
  try {
    const t = text.replace(/^\s+/, '');
    if (t.charAt(0) !== '{' && t.charAt(0) !== '[') {
      throw new Error('not json');
    }
    return JSON.parse(t);
  } catch (e) {
    throw new NetError('parse', `数据解析失败：${what}`);
  }
}

/** 当前是否已登录（有 JSESSIONID 就算） */
export function hasAuth(): boolean {
  return cookie.hasSession(JW_HOST);
}