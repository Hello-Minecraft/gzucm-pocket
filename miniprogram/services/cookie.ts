/**
 * Cookie 管理。
 *
 * ⚠️ 核心约束（从 Android 版 Net.kt 的坑直接搬过来）：
 * **必须按 (name, path) 存，不能只按 name。**
 *
 * 广中医一卡通在同一个域名下有两个同名 JSESSIONID：
 *   Path=/user  ← 业务接口用这个
 *   Path=/sso   ← SSO 跳转用这个
 * 只按 name 存，后者会覆盖前者 → 发请求时带错 Session → 服务端返回登录页。
 *
 * 小程序和浏览器不同：wx.request **不会自动管理 Cookie**，
 * 响应里的 Set-Cookie 要自己从 res.cookies 捞出来、自己存、自己塞回 header。
 * 这反而让我们能完整控制 path 语义（浏览器里 JS 是读不到 HttpOnly Cookie 的）。
 */

const KEY = 'gz_cookies_v1';

/** host -> (name|path -> value) */
type CookieStore = Record<string, Record<string, string>>;

function load(): CookieStore {
  try {
    const raw = wx.getStorageSync(KEY);
    if (!raw || typeof raw !== 'string') return {};
    const obj = JSON.parse(raw);
    return obj && typeof obj === 'object' ? obj : {};
  } catch (e) {
    return {};
  }
}

function save(store: CookieStore): void {
  try {
    wx.setStorageSync(KEY, JSON.stringify(store));
  } catch (e) {
    // 存储满了就算了，下次重新登录
  }
}

/** 从一条 Set-Cookie 里拆出 name / value / path */
function parseSetCookie(line: string): { name: string; value: string; path: string } | null {
  const parts = line.split(';');
  const first = (parts[0] || '').trim();
  const eq = first.indexOf('=');
  if (eq <= 0) return null;
  const name = first.slice(0, eq).trim();
  const value = first.slice(eq + 1).trim();
  if (!name) return null;
  // 默认 path：取 / ，够用；学校这两个 Cookie 都显式给了 path
  let path = '/';
  for (let i = 1; i < parts.length; i++) {
    const seg = parts[i].trim();
    const lower = seg.toLowerCase();
    if (lower.startsWith('path=')) {
      const p = seg.slice(5).trim();
      if (p) path = p;
    }
  }
  return { name, value, path };
}

/** 把服务端返回的 Set-Cookie 数组并入存储 */
export function absorb(host: string, setCookies: string[] | undefined): void {
  if (!setCookies || !setCookies.length) return;
  const store = load();
  const bucket = store[host] || (store[host] = {});
  for (const line of setCookies) {
    const c = parseSetCookie(line);
    if (!c) continue;
    // 过期的不存（Expires 在过去），顺手清掉同 key 的旧值
    if (/expires=thu,\s*01\s*jan\s*1970/i.test(line)) {
      delete bucket[`${c.name}|${c.path}`];
      continue;
    }
    bucket[`${c.name}|${c.path}`] = c.value;
  }
  store[host] = bucket;
  save(store);
}

/**
 * 拼出该 host + path 下应该带的 Cookie 头。
 *
 * 匹配规则按 RFC 6265：请求路径以 Cookie 的 path 为前缀才带上；
 * 多个 path 都能匹配时，长的优先（更具体的那个覆盖）。
 */
export function header(host: string, reqPath: string): string {
  const store = load();
  const bucket = store[host];
  if (!bucket) return '';
  const matched: Array<{ name: string; value: string; pathLen: number }> = [];
  for (const key of Object.keys(bucket)) {
    const idx = key.lastIndexOf('|');
    if (idx <= 0) continue;
    const name = key.slice(0, idx);
    const path = key.slice(idx + 1);
    if (reqPath.indexOf(path) !== 0) continue;
    matched.push({ name, value: bucket[key], pathLen: path.length });
  }
  // 同名时长的 path 优先（后写覆盖前写）
  matched.sort((a, b) => a.pathLen - b.pathLen);
  const byName: Record<string, string> = {};
  for (const m of matched) byName[m.name] = m.value;
  return Object.keys(byName)
    .map((k) => `${k}=${byName[k]}`)
    .join('; ');
}

/** 该 host 有没有 JSESSIONID（判断登录态用） */
export function hasSession(host: string, reqPath?: string): boolean {
  const h = header(host, reqPath || '/');
  return /(^|;\s*)JSESSIONID=/.test(h);
}

/** 清空某个 host 的 Cookie（退出登录） */
export function clear(host?: string): void {
  if (!host) {
    save({});
    return;
  }
  const store = load();
  delete store[host];
  save(store);
}

/** 全部 Cookie（调试用，正式环境不打印） */
export function dump(): CookieStore {
  return load();
}

/** 是否存过任何会话 */
export function any(): boolean {
  const store = load();
  return Object.keys(store).some((h) => Object.keys(store[h]).length > 0);
}