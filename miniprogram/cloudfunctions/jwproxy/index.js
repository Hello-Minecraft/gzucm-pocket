/**
 * 教务接口转发云函数 jwproxy
 *
 * 只做一件事：把小程序传来的请求原样发到 jw.gzucm.edu.cn，把响应原样带回。
 *
 * ⚠️ 设计底线（不是可选项）：
 *  ① 不落盘 —— 不写数据库、不写对象存储、不写临时文件
 *  ② 不记日志 —— console 里绝不输出 cookie、账号、响应体
 *  ③ 不持有会话 —— Cookie 每次由客户端传入，转发完立刻随请求结束丢弃
 *  ④ 只放行白名单路径，不做通用代理（避免变成任意 URL 的跳板）
 *
 * 它是一根管子，不是中间人。
 */

'use strict';

const http = require('http');
const https = require('https');
const { URL } = require('url');

/** 只允许转发教务的这几个接口，别的路径一律拒绝 */
const ALLOWED_PATHS = [
  '/new/xlxx/data',
  '/new/student/xsgrkb/getCalendarWeekDatas',
  '/new/student/xskccj/kccjDatas',
  '/new/student/xskjcj/datas',
  '/new/student/xstccj/datas',
  '/new/qrcodeLogin/qrcode',
  '/new/qrcodeLogin/qrcodeStatus',
];

function isAllowed(path) {
  return ALLOWED_PATHS.indexOf(path) >= 0;
}

/** 发一次 HTTPS 请求，返回 {status, body, setCookies} */
function httpRequest(options, body) {
  return new Promise((resolve, reject) => {
    const req = https.request(options, (res) => {
      const chunks = [];
      res.on('data', (c) => chunks.push(c));
      res.on('end', () => {
        // 响应可能是 html/jpeg，一律按 utf8 取 —— 只转发 JSON 和 HTML 接口
        const text = Buffer.concat(chunks).toString('utf8');
        let setCookies = res.headers['set-cookie'] || [];
        if (!Array.isArray(setCookies)) setCookies = [String(setCookies)];
        resolve({ status: res.statusCode || 0, body: text, setCookies });
      });
    });
    req.on('error', (e) => reject(e));
    req.setTimeout(20000, () => {
      req.destroy(new Error('upstream timeout'));
    });
    if (body) req.write(body);
    req.end();
  });
}

exports.main = async (event) => {
  const { host, path, method, form, referer, cookie, absoluteUrl } = event || {};

  // ── 入参校验 ──────────────────────────────────────────────
  if (typeof host !== 'string' || host !== 'https://jw.gzucm.edu.cn') {
    return { error: 'bad host' };
  }

  /*
   * 两种模式：
   *  · 普通接口：path 必须在白名单里
   *  · 登录回跳（absoluteUrl）：url 是学校动态给的，没法预先列白名单，
   *    但仍然死死限定「必须是 jw.gzucm.edu.cn 下的 https 地址」——
   *    不做通用代理，避免变成任意 URL 的跳板。
   */
  let targetUrl;
  if (typeof absoluteUrl === 'string' && absoluteUrl) {
    let u;
    try {
      u = new URL(absoluteUrl);
    } catch (e) {
      return { error: 'bad url' };
    }
    if (u.protocol !== 'https:' || u.hostname !== 'jw.gzucm.edu.cn') {
      return { error: 'url not allowed' };
    }
    targetUrl = u;
  } else {
    if (typeof path !== 'string' || !isAllowed(path)) {
      return { error: 'path not allowed' };
    }
    targetUrl = new URL(host + path);
  }

  const verb = method === 'GET' ? 'GET' : 'POST';

  // ── 拼请求 ────────────────────────────────────────────────
  let bodyBuf = null;
  const headers = {
    // 教务要求；缺了会返回 -401「疑似非法请求」
    'X-Requested-With': 'XMLHttpRequest',
    'User-Agent':
      'Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) ' +
      'Chrome/131.0.0.0 Mobile Safari/537.36',
    Accept: 'application/json, text/javascript, */*; q=0.01',
  };
  if (verb === 'POST') {
    const params = new URLSearchParams();
    Object.keys(form || {}).forEach((k) => params.append(k, String(form[k])));
    bodyBuf = Buffer.from(params.toString(), 'utf8');
    headers['Content-Type'] = 'application/x-www-form-urlencoded';
    headers['Content-Length'] = bodyBuf.length;
  }
  // Referer 只有云函数能设 —— 小程序侧设不了，这里是补齐
  if (typeof referer === 'string' && referer) headers.Referer = referer;
  if (typeof cookie === 'string' && cookie) headers.Cookie = cookie;

  const options = {
    hostname: targetUrl.hostname,
    port: 443,
    path: targetUrl.pathname + targetUrl.search,
    method: verb,
    headers,
  };

  try {
    const r = await httpRequest(options, bodyBuf);
    // ⚠️ 这里只返回，不打印
    return { status: r.status, body: r.body, setCookies: r.setCookies };
  } catch (e) {
    return { error: 'upstream failed: ' + ((e && e.message) || 'unknown') };
  }
};