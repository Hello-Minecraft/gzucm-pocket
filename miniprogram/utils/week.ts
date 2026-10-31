/**
 * 日期与周次换算。
 *
 * ⚠️ 这里有一处**必须原样保留**的坑：
 * 教务的「第 N 周」是**周日 ~ 周六**，而 App 的「第 N 周」是**周一 ~ 周日**。
 * 直接拿教务的 zc 当 App 的周次，会让周日的课整整错位一周。
 * 修法是：用每行返回的 `qsrq`（真实日期）反推周次，而不是信接口传的 zc。
 *
 * 这条逻辑对应 Android 的 Jw.parseWeekly + Semester.weekOf，
 * 移植时一个字符都不要改，否则会重新引入线上 bug。
 */

const DAY = 86400000;

/** 把 yyyy-MM-dd 解析成本地零点的毫秒数（不用 Date.parse，避免时区坑） */
export function parseDate(iso: string): number {
  const m = /^(\d{4})-(\d{2})-(\d{2})/.exec(iso);
  if (!m) return NaN;
  return new Date(Number(m[1]), Number(m[2]) - 1, Number(m[3])).getTime();
}

/** 毫秒数格式化成 yyyy-MM-dd */
export function fmtDate(ms: number): string {
  const d = new Date(ms);
  const mm = `${d.getMonth() + 1}`.padStart(2, '0');
  const dd = `${d.getDate()}`.padStart(2, '0');
  return `${d.getFullYear()}-${mm}-${dd}`;
}

/** 今天零点 */
export function todayStart(): number {
  const now = new Date();
  return new Date(now.getFullYear(), now.getMonth(), now.getDate()).getTime();
}

/**
 * 某天属于第几周（App 口径：周一开始）。
 * 返回 0 表示在学期开始之前，负数/超界由调用方处理。
 */
export function weekOf(semester: GZ.Semester, dateMs: number): number {
  const start = parseDate(semester.firstMonday);
  if (isNaN(start) || isNaN(dateMs)) return 0;
  const diff = Math.floor((dateMs - start) / DAY);
  return Math.floor(diff / 7) + 1;
}

/** 第 week 周的第 weekday 天（weekday 1=周一 … 7=周日）的日期 */
export function dateOf(semester: GZ.Semester, week: number, weekday: number): string {
  const start = parseDate(semester.firstMonday);
  if (isNaN(start)) return '';
  return fmtDate(start + (week - 1) * 7 * DAY + (weekday - 1) * DAY);
}

/** 当前是第几周，并夹到 [1, totalWeeks] */
export function currentWeek(semester: GZ.Semester): number {
  const w = weekOf(semester, todayStart());
  if (w < 1) return 1;
  if (w > semester.totalWeeks) return semester.totalWeeks;
  return w;
}

/** 今天是周几（1=周一 … 7=周日）。getDay() 里周日是 0，要转成 7 */
export function weekdayOf(dateMs: number): number {
  const d = new Date(dateMs).getDay();
  return d === 0 ? 7 : d;
}

/** 按当前日期推断学期代码：8 月及以后算当年第一学期 */
export function guessSemesterCode(now?: Date): string {
  const d = now || new Date();
  const y = d.getFullYear();
  return d.getMonth() + 1 >= 8 ? `${y}01` : `${y - 1}02`;
}

/**
 * 上一个学期代码。
 *
 * ⚠️ 这里的规则和 Android 版**不一样**，是有意改的：
 *   代码 202601 = 2026-2027 学年第一学期，前一个是 2025-2026 学年第二学期 = 202502。
 *   所以秋季(01) → 上一年(2025) 的春季(02)。
 *   而 202502 = 2025-2026 学年第二学期，前一个是 2025-2026 学年第一学期 = 202501。
 *   所以春季(02) → **同年**(2025) 的秋季(01)，不是 2026。
 *
 * Android 版这里写的是 `${year}01`，对 202502 会得到 202601 —— 那是下一个学期，
 * 不是上一个。它从没被真正调用过（同步失败就退回当前学期），所以没暴露。
 */
export function previousSemesterCode(code: string): string {
  const year = Number(code.slice(0, 4));
  if (!year) return code;
  return code.endsWith('01') ? `${year - 1}02` : `${year}01`;
}

/** 学期显示名 */
export function semesterName(code: string): string {
  const year = Number(code.slice(0, 4));
  if (!year) return code;
  return code.endsWith('01')
    ? `${year}-${year + 1} 学年 第一学期`
    : `${year - 1}-${year} 学年 第二学期`;
}

/** 周次列表 [1..n] */
export function weeksOf(total: number): number[] {
  const out: number[] = [];
  for (let i = 1; i <= total; i++) out.push(i);
  return out;
}

/** 最近 n 天的日期串（含今天），用于电费那种按天列表 */
export function recentDays(n: number): string[] {
  const base = todayStart();
  const out: string[] = [];
  for (let i = n - 1; i >= 0; i--) out.push(fmtDate(base - i * DAY));
  return out;
}

/** 星期中文 */
export const WEEKDAY_CN = ['', '一', '二', '三', '四', '五', '六', '日'];