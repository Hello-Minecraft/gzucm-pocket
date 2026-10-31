/**
 * 数字格式化。
 *
 * 为什么不用 toFixed：
 *   JS 的 toFixed 有浮点问题 —— (1.005).toFixed(2) 得到 "1.00"，
 *   (8.575).toFixed(2) 得到 "8.57"。成绩和绩点会被用户盯着看，不能用它。
 *   这里先放大 10^n 再四舍五入，然后再除，跟 Kotlin 的 String.format 对齐。
 */

function round(v: number, digits: number): number {
  const f = Math.pow(10, digits);
  // +1e-9 是补偿二进制浮点在边界上的误差（8.575 * 100 = 857.4999999）
  return Math.round(v * f + (v >= 0 ? 1e-9 : -1e-9)) / f;
}

/** 保留 n 位小数 */
export function fixed(v: number, digits: number): string {
  if (!isFinite(v)) return '--';
  return round(v, digits).toFixed(digits);
}

/** 一位小数 */
export function d1(v: number): string {
  return fixed(v, 1);
}

/** 两位小数 */
export function d2(v: number): string {
  return fixed(v, 2);
}

/** 去掉尾部多余的 0：2.00 → 2，2.50 → 2.5，2.55 → 2.55 */
export function trim(v: number, digits: number): string {
  const s = fixed(v, digits);
  return s.indexOf('.') >= 0 ? s.replace(/\.?0+$/, '') : s;
}

/** 保留一位、去掉多余的 0（学分最常用） */
export function trim1(v: number): string {
  return trim(v, 1);
}

/** 保留两位、去掉多余的 0（电费度数） */
export function trim2(v: number): string {
  return trim(v, 2);
}

/** 千分位，用于大数字（如总人数） */
export function thousands(v: number): string {
  const s = String(Math.round(v));
  return s.replace(/\B(?=(\d{3})+(?!\d))/g, ',');
}

/** 秒数格式化成「约 X 分钟」 */
export function minutes(sec: number): string {
  const m = Math.round(sec / 60);
  if (m < 1) return '不到 1 分钟';
  return `约 ${m} 分钟`;
}

/** 时间戳 → MM-dd HH:mm */
export function shortTime(ts: number): string {
  if (!ts) return '';
  const d = new Date(ts);
  const mm = `${d.getMonth() + 1}`.padStart(2, '0');
  const dd = `${d.getDate()}`.padStart(2, '0');
  const hh = `${d.getHours()}`.padStart(2, '0');
  const mi = `${d.getMinutes()}`.padStart(2, '0');
  return `${mm}-${dd} ${hh}:${mi}`;
}

/** 相对时间：刚刚 / 5 分钟前 / 3 小时前 / 2 天前 */
export function ago(ts: number): string {
  if (!ts) return '';
  const diff = Date.now() - ts;
  if (diff < 60000) return '刚刚';
  if (diff < 3600000) return `${Math.floor(diff / 60000)} 分钟前`;
  if (diff < 86400000) return `${Math.floor(diff / 3600000)} 小时前`;
  return `${Math.floor(diff / 86400000)} 天前`;
}