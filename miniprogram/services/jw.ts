/**
 * 教务系统接口。
 *
 * 从 Android 的 data/Jw.kt 逐行移植。**解析逻辑一处都不能"优化"** ——
 * 那里每一条奇怪的判断都是踩过坑留下的。
 */

import * as http from './request';
import * as week from '../utils/week';

const BASE = http.JW_BASE;
const REF = (p: string) => BASE + p;

/** 取字符串字段，null/undefined 一律变 '' */
function s(o: any, k: string): string {
  if (!o || o[k] === null || o[k] === undefined) return '';
  return String(o[k]);
}

/** 取数字字段 */
function n(o: any, k: string): number {
  const v = parseFloat(s(o, k));
  return isFinite(v) ? v : 0;
}

/** 取整数 */
function i(o: any, k: string): number {
  const v = parseInt(s(o, k).replace(/^0+/, '') || '0', 10);
  return isFinite(v) ? v : 0;
}

// ── 学期 ──────────────────────────────────────────────────

/**
 * 教学校历：给出第 1 周的真实日期。
 * POST /new/xlxx/data  body: xnxqdm=202601
 */
export async function fetchSemester(code: string): Promise<GZ.Semester | null> {
  const text = await http.post(
    '/new/xlxx/data',
    { xnxqdm: code },
    REF('/new/welcome.page?ui=new'),
  );
  const root = http.parseJson(text, '校历响应');

  // data 可能是字符串/数字（鉴权失败的响应就是），直接当数组用会抛异常
  const groups = Array.isArray(root.data) ? root.data : null;
  if (!groups) return null;

  let firstMondayMs = NaN;
  let maxWeek = 0;

  for (const group of groups) {
    if (!Array.isArray(group)) continue;
    for (const rowEl of group) {
      if (!Array.isArray(rowEl) || !rowEl.length) continue;
      const head = rowEl[0];
      if (!head || typeof head !== 'object') continue;
      if (s(head, 'type') !== 'zc') continue;
      const wk = parseInt(s(head, 'value'), 10);
      if (!isFinite(wk)) continue;
      if (wk > maxWeek) maxWeek = wk;

      if (wk === 1 && isNaN(firstMondayMs)) {
        const days: string[] = [];
        for (let k = 1; k < rowEl.length; k++) {
          const cell = rowEl[k];
          if (cell && typeof cell === 'object' && s(cell, 'type') === 'day') {
            const ft = s(cell, 'fullText');
            if (ft) days.push(ft);
          }
        }
        if (days.length) {
          // 表头从「星期日」开始，所以第一格是周日 → 周一要 +1 天
          const sun = week.parseDate(days[0]);
          if (!isNaN(sun)) firstMondayMs = sun + 86400000;
        }
      }
    }
  }

  if (isNaN(firstMondayMs)) return null;
  if (maxWeek <= 0) return null;

  return {
    code,
    name: week.semesterName(code),
    firstMonday: week.fmtDate(firstMondayMs),
    // 周数以教务周历实际给出的为准，不硬造；夹到合理区间防脏数据
    totalWeeks: Math.min(30, Math.max(10, maxWeek)),
  };
}

// ── 课表 ──────────────────────────────────────────────────

/** 整学期课表。一次拿全，本地按周切。 */
export async function fetchSessions(sem: GZ.Semester): Promise<GZ.CourseSession[]> {
  const d1 = week.fmtDate(week.parseDate(sem.firstMonday) - 86400000);
  const d2 = week.fmtDate(
    week.parseDate(sem.firstMonday) + (sem.totalWeeks + 2) * 7 * 86400000,
  );
  const text = await http.post(
    '/new/student/xsgrkb/getCalendarWeekDatas',
    { xnxqdm: sem.code, zc: '', d1: `${d1} 00:00:00`, d2: `${d2} 00:00:00` },
    REF('/new/student/xsgrkb/main.page'),
  );
  const root = http.parseJson(text, '课表响应');
  const arr = Array.isArray(root.data) ? root.data : [];
  return parseTotal(arr);
}

/** 按周查询。多出 sknrjj（上课内容）和 qsrq（真实日期）两个字段。 */
export async function fetchWeek(sem: GZ.Semester, wk: number): Promise<GZ.CourseSession[]> {
  const d1 = week.fmtDate(week.parseDate(sem.firstMonday) - 86400000);
  const d2 = week.fmtDate(
    week.parseDate(sem.firstMonday) + (sem.totalWeeks + 2) * 7 * 86400000,
  );
  const text = await http.post(
    '/new/student/xsgrkb/getCalendarWeekDatas',
    { xnxqdm: sem.code, zc: String(wk), d1: `${d1} 00:00:00`, d2: `${d2} 00:00:00` },
    REF('/new/student/xsgrkb/main.page'),
  );
  const root = http.parseJson(text, `第 ${wk} 周课表`);
  const arr = Array.isArray(root.data) ? root.data : [];
  return parseWeekly(arr, wk, sem);
}

/** 读教室名。优先级：jxcdmc → jxcdmc2 里匹配当周 → cdmc */
function roomName(o: any, wk?: number): string {
  const main = s(o, 'jxcdmc').trim();
  if (main) return main;

  const c2 = s(o, 'jxcdmc2').trim();
  if (c2) {
    // 形如「场地A-3,场地B-4」，按周挑出本周那一条
    const re = /^(.*)-(\d{1,2})$/;
    let fallback = '';
    for (const tokRaw of c2.split(',')) {
      const tok = tokRaw.trim();
      if (!tok) continue;
      const m = re.exec(tok);
      if (!m) continue;
      const w = parseInt(m[2], 10);
      const name = m[1].trim();
      if (name) fallback = name;
      if (wk !== undefined && w === wk) return name;
    }
    if (fallback) return fallback;
  }

  // 有些排课只填了场地代码
  return s(o, 'cdmc').trim();
}

/** 解析整学期总表（zc 为空时拿到的那份） */
function parseTotal(arr: any[]): GZ.CourseSession[] {
  const out: GZ.CourseSession[] = [];
  for (const el of arr) {
    if (!el || typeof el !== 'object') continue;
    const name = s(el, 'kcmc');
    if (!name) continue;
    const weekday = parseInt(s(el, 'xq'), 10);
    if (!isFinite(weekday) || weekday < 1 || weekday > 7) continue;

    const startUnit = parseInt(s(el, 'ps'), 10) || 0;
    const endRaw = parseInt(s(el, 'pe'), 10) || startUnit;
    const endUnit = Math.max(endRaw, startUnit);
    const mainRoom = roomName(el);

    // 逐周教室展开。没有 jxcdmc2 时退回 zc 拆周次
    const pairs: Array<{ w: number; room: string }> = [];
    const c2 = s(el, 'jxcdmc2').trim();
    if (c2) {
      const re = /^(.*)-(\d{1,2})$/;
      for (const tokRaw of c2.split(',')) {
        const tok = tokRaw.trim();
        if (!tok) continue;
        const m = re.exec(tok);
        if (!m) continue;
        const w = parseInt(m[2], 10);
        if (!isFinite(w) || w < 1) continue;
        pairs.push({ w, room: mainRoom || m[1].trim() });
      }
    } else {
      for (const tokRaw of s(el, 'zc').split(',')) {
        const zcWeek = parseInt(tokRaw.trim(), 10);
        if (!isFinite(zcWeek)) continue;
        /*
         * 总表没有 qsrq，只能用 zc 换算：
         * 教务第 N 周的**周日**，在 App 里属于第 N-1 周。
         * N=1 的周日算出来是第 0 周 → 那天在学期开始之前，丢掉。
         */
        const appWeek = weekday === 7 ? zcWeek - 1 : zcWeek;
        if (appWeek < 1) continue;
        pairs.push({ w: appWeek, room: mainRoom });
      }
    }
    if (!pairs.length) continue;

    for (const { w, room } of pairs) {
      out.push({
        name,
        code: s(el, 'kcdm'),
        teachers: s(el, 'teaxms'),
        week: w,
        weekday,
        startUnit,
        endUnit,
        startTime: s(el, 'qssj').slice(0, 5),
        endTime: s(el, 'jssj').slice(0, 5),
        room,
        category: s(el, 'jxhjmc'),
        teachingMode: s(el, 'jxmsmc'),
        mergedClasses: s(el, 'jxbmc'),
        studyHours: s(el, 'xs'),
        courseSeq: s(el, 'kcbh'),
        schoolColor: s(el, 'bgcolor'),
      });
    }
  }
  return out;
}

/**
 * 解析按周查询的响应。
 *
 * ⚠️ 周次**不能**直接用传进去的 zc，必须用 qsrq 反推（见 utils/week.ts 顶部注释）。
 * 否则周日的课会错位一整周。
 */
function parseWeekly(arr: any[], fallbackWeek: number, sem: GZ.Semester): GZ.CourseSession[] {
  const out: GZ.CourseSession[] = [];
  for (const el of arr) {
    if (!el || typeof el !== 'object') continue;
    const name = s(el, 'kcmc');
    if (!name) continue;
    const weekday = parseInt(s(el, 'xq'), 10);
    if (!isFinite(weekday) || weekday < 1 || weekday > 7) continue;

    const startUnit = parseInt(s(el, 'ps'), 10) || 0;
    const endRaw = parseInt(s(el, 'pe'), 10) || startUnit;
    const endUnit = Math.max(endRaw, startUnit);

    // 优先用本行真实日期反推周次；拿不到才退回接口参数
    const realDate = s(el, 'qsrq').slice(0, 10);
    let weekFromDate: number | null = null;
    if (realDate.length === 10) {
      const ms = week.parseDate(realDate);
      if (!isNaN(ms)) {
        const w = week.weekOf(sem, ms);
        if (w >= 1 && w <= sem.totalWeeks) weekFromDate = w;
      }
    }

    const weeks: number[] = [];
    if (weekFromDate !== null) {
      weeks.push(weekFromDate);
    } else {
      for (const tok of s(el, 'zc').split(',')) {
        const v = parseInt(tok.trim(), 10);
        if (isFinite(v) && v > 0) weeks.push(v);
      }
      if (!weeks.length) weeks.push(fallbackWeek);
    }

    for (const w of weeks) {
      out.push({
        name,
        code: s(el, 'kcdm'),
        teachers: s(el, 'teaxms'),
        week: w,
        weekday,
        startUnit,
        endUnit,
        startTime: s(el, 'qssj').slice(0, 5),
        endTime: s(el, 'jssj').slice(0, 5),
        room: roomName(el, w),
        category: s(el, 'jxhjmc'),
        teachingMode: s(el, 'jxmsmc'),
        mergedClasses: s(el, 'jxbmc'),
        studyHours: s(el, 'xs'),
        courseSeq: s(el, 'kcbh'),
        schoolColor: s(el, 'bgcolor'),
        content: s(el, 'sknrjj'),
        date: realDate,
      });
    }
  }
  return out;
}

/**
 * 抓所有周次，跳过 exclude。
 *
 * 并发 5 是 Android 版验证过的保守值。小程序 wx.request 上限是 10，
 * 但走云函数时每个调用都是一次 callFunction，并发太高会打满云函数配额。
 */
export async function fetchWeeksExcept(
  sem: GZ.Semester,
  exclude: number,
  onEach?: (done: number, total: number) => void,
): Promise<GZ.CourseSession[]> {
  // 多抓一周：最后一周的周日落在教务的下一周里
  const lastWeek = sem.totalWeeks + 1;
  const weeks: number[] = [];
  for (let w = 1; w <= lastWeek; w++) {
    if (w !== exclude) weeks.push(w);
  }

  const out: GZ.CourseSession[] = [];
  let done = 0;
  const CONCURRENCY = 5;

  for (let base = 0; base < weeks.length; base += CONCURRENCY) {
    const batch = weeks.slice(base, base + CONCURRENCY);
    const results = await Promise.all(
      batch.map(async (w) => {
        try {
          return await fetchWeek(sem, w);
        } catch (e) {
          // 单周失败不拖垮整体：那周留空，下次同步会补
          return [] as GZ.CourseSession[];
        } finally {
          done++;
          if (onEach) onEach(done, weeks.length);
        }
      }),
    );
    for (const r of results) out.push(...r);
  }
  return out;
}

// ── 成绩 ──────────────────────────────────────────────────

/** 课程成绩：POST /new/student/xskccj/kccjDatas */
export async function fetchCourseGrades(): Promise<GZ.CourseGrade[]> {
  const text = await http.post(
    '/new/student/xskccj/kccjDatas',
    {
      xnxqdm: '',
      source: 'kccjlist',
      ismax: '1',
      page: '1',
      rows: '500',
      sort: 'xnxqdm,kcmc',
      order: 'desc,asc',
    },
    REF('/new/student/xskccj/main.page'),
  );
  const root = http.parseJson(text, '成绩响应');
  const rows = Array.isArray(root.rows) ? root.rows : [];
  const out: GZ.CourseGrade[] = [];
  for (const el of rows) {
    if (!el || typeof el !== 'object') continue;
    const name = s(el, 'kcmc');
    if (!name) continue;
    const scoreRaw = parseFloat(s(el, 'zcjfs'));
    if (!isFinite(scoreRaw)) continue;
    out.push({
      term: s(el, 'xnxqmc'),
      name,
      score: scoreRaw,
      gpa: n(el, 'cjjd'),
      credit: n(el, 'xf'),
      category: s(el, 'kcdlmc'),
      // kcdlmc 只到「公共选修课」，细分要靠 kcflmc
      classify: s(el, 'kcflmc'),
      nature: s(el, 'ksxzmc'),
      remark: s(el, 'bz'),
      passed: scoreRaw >= 60,
    });
  }
  return out;
}

/** 考级成绩：POST /new/student/xskjcj/datas */
export async function fetchLevelGrades(): Promise<GZ.LevelGrade[]> {
  const text = await http.post(
    '/new/student/xskjcj/datas',
    { page: '1', rows: '100', sort: 'xnxqdm', order: 'asc' },
    REF('/new/student/xskjcj/xskjcjlist.page'),
  );
  const root = http.parseJson(text, '考级响应');
  const rows = Array.isArray(root.rows) ? root.rows : [];
  const out: GZ.LevelGrade[] = [];
  for (const el of rows) {
    if (!el || typeof el !== 'object') continue;
    const name = s(el, 'kjkcmc');
    if (!name) continue;
    const parts: Array<{ label: string; value: string }> = [];
    const labels = ['听力', '阅读', '写作', '翻译', '其他'];
    const keys = ['xm1cj', 'xm2cj', 'xm3cj', 'xm4cj', 'xm5cj'];
    for (let k = 0; k < keys.length; k++) {
      const v = s(el, keys[k]);
      if (v && v !== '0.0') parts.push({ label: labels[k], value: v });
    }
    out.push({
      name,
      term: s(el, 'xnxqmc'),
      total: n(el, 'zcj'),
      level: s(el, 'djmc'),
      date: s(el, 'kssj'),
      parts,
    });
  }
  return out;
}

/** 体测成绩：POST /new/student/xstccj/datas（只取成绩，丢弃身份证/电话） */
export async function fetchFitness(): Promise<GZ.FitnessGrade[]> {
  const text = await http.post(
    '/new/student/xstccj/datas',
    { page: '1', rows: '100', sort: 'xnxqdm', order: 'asc' },
    REF('/new/student/xstccj'),
  );
  const root = http.parseJson(text, '体测响应');
  const rows = Array.isArray(root.rows) ? root.rows : [];
  const spec: Array<[string, string, string]> = [
    ['身高', 'sg', ''],
    ['体重', 'tz', 'tzpf'],
    ['肺活量', 'fhl', 'fhlpf'],
    ['50 米', 'wsm', 'wsmpf'],
    ['坐位体前屈', 'zwtqq', 'zwtqqpf'],
    ['立定跳远', 'ldty', 'ldtypf'],
    ['引体/仰卧', 'ytxsorywqz', 'ytxsorywqzpf'],
    ['800/1000 米', 'yqmorbbm', 'yqmorbbmpf'],
  ];
  const out: GZ.FitnessGrade[] = [];
  for (const el of rows) {
    if (!el || typeof el !== 'object') continue;
    const items: GZ.FitnessItem[] = [];
    for (const [label, vk, sk] of spec) {
      const v = s(el, vk);
      if (!v) continue;
      items.push({ label, value: v, score: s(el, sk), extra: '' });
    }
    out.push({
      term: s(el, 'xnxqmc'),
      total: s(el, 'zf'),
      level: s(el, 'dj'),
      items,
    });
  }
  return out;
}