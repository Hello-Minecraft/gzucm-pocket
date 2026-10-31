/**
 * 课程排布：把同一天的课按重叠情况分成列。
 *
 * Android 版在这上面返工过两轮：
 *   第一版只支持层叠，平板上一堆课糊在一起；
 *   第二版加了"长度差超一半就层叠"的例外，用户反馈"怎么都没分开"。
 * 最终方案：**手机上重叠就并排分列，不做任何层叠**。
 * 小程序屏幕更窄，并排是最稳的选择，所以直接采用最终版。
 *
 * 关键公式（Android 版踩过坑）：
 *   块 c 的起点 = c * inset，宽度 = 1 - inset，
 *   相邻两块的重叠区 = 1 - 2 * inset（**不是 inset**）
 */

/** 节次表。注意编号跳号：没有 5、10、11 节（学校作息如此） */
export const SLOTS: Array<{ unit: number; start: string; end: string }> = [
  { unit: 1, start: '08:30', end: '09:15' },
  { unit: 2, start: '09:20', end: '10:05' },
  { unit: 3, start: '10:20', end: '11:05' },
  { unit: 4, start: '11:10', end: '11:55' },
  { unit: 6, start: '14:00', end: '14:45' },
  { unit: 7, start: '14:50', end: '15:35' },
  { unit: 8, start: '15:40', end: '16:25' },
  { unit: 9, start: '16:30', end: '17:15' },
  { unit: 12, start: '19:00', end: '19:45' },
  { unit: 13, start: '19:50', end: '20:35' },
  { unit: 14, start: '20:40', end: '21:25' },
];

/** 一格棋盘的可见节次序号（按 SLOTS 顺序） */
export const SLOT_COUNT = SLOTS.length;

/** 节次 → 行号（0-based）。找不到返回 -1 */
export function slotIndex(unit: number): number {
  for (let k = 0; k < SLOTS.length; k++) {
    if (SLOTS[k].unit === unit) return k;
  }
  return -1;
}

/**
 * 一个课程块在网格里占的行范围。
 * 起点取 startUnit 的行号；终点取 endUnit 的下一行（含）。
 * 如果 endUnit 不在表里（比如 5 节），往后找最近的已有序号。
 */
export function rowRange(startUnit: number, endUnit: number): { top: number; span: number } {
  let top = slotIndex(startUnit);
  if (top < 0) {
    // 找不到起点就往上靠，保证不丢课
    for (let k = SLOTS.length - 1; k >= 0; k--) {
      if (SLOTS[k].unit <= startUnit) {
        top = k;
        break;
      }
    }
    if (top < 0) top = 0;
  }
  let endIdx = slotIndex(endUnit);
  if (endIdx < 0) {
    endIdx = top;
    for (let k = 0; k < SLOTS.length; k++) {
      if (SLOTS[k].unit <= endUnit && k > endIdx) endIdx = k;
    }
  }
  if (endIdx < top) endIdx = top;
  return { top, span: endIdx - top + 1 };
}

export interface Placed {
  session: GZ.CourseSession;
  /** 0-based 列索引（横向第几块） */
  col: number;
  /** 该列占的份数（并排时 n 等分） */
  cols: number;
  /** 起始行 */
  top: number;
  /** 占几行 */
  span: number;
}

/** 两节课在时间上是否相交（首尾相接不算冲突） */
function intersects(a: GZ.CourseSession, b: GZ.CourseSession): boolean {
  return !(a.endUnit < b.startUnit || b.endUnit < a.startUnit);
}

/**
 * 把一天的课排开。
 *
 * 算法：
 *  ① 先把互相有交集的课聚成「簇」（可传递：A撞B、B撞C，则三者同簇）
 *  ② 每簇内部按开始节次排序，贪心分配列 —— 每块放进第一个不冲突的列
 *  ③ 簇的列数 = 用到的最多列数，簇内所有块都按这个列数等分
 *
 * 为什么按簇而不是全图：一簇 2 门课时，按理只该占一半宽，
 * 但如果全图按最多列数算，只有 2 门课的那天会被压成 1/3 宽，很难看。
 */
export function placeDay(list: GZ.CourseSession[]): Placed[] {
  if (!list.length) return [];

  const sorted = list
    .slice()
    .sort((a, b) => (a.startUnit - b.startUnit) || (a.endUnit - b.endUnit));

  // ① 并查集式聚簇
  const clusterOf: number[] = new Array(sorted.length).fill(-1);
  let nextCluster = 0;
  for (let i = 0; i < sorted.length; i++) {
    if (clusterOf[i] >= 0) continue;
    const id = nextCluster++;
    clusterOf[i] = id;
    // 反复扩张，直到这一簇不再变长（传递性）
    let grew = true;
    while (grew) {
      grew = false;
      for (let k = 0; k < sorted.length; k++) {
        if (clusterOf[k] >= 0) continue;
        for (let j = 0; j < sorted.length; j++) {
          if (clusterOf[j] !== id) continue;
          if (intersects(sorted[k], sorted[j])) {
            clusterOf[k] = id;
            grew = true;
            break;
          }
        }
      }
    }
  }

  // ② 每簇内部分列
  const out: Placed[] = [];
  for (let id = 0; id < nextCluster; id++) {
    const members: number[] = [];
    for (let k = 0; k < sorted.length; k++) {
      if (clusterOf[k] === id) members.push(k);
    }
    // 每列当前占用到哪一节为止
    const colEnd: number[] = [];
    const assign: number[] = [];
    for (const idx of members) {
      const s = sorted[idx];
      let placed = -1;
      for (let c = 0; c < colEnd.length; c++) {
        if (colEnd[c] < s.startUnit) {
          placed = c;
          break;
        }
      }
      if (placed < 0) {
        placed = colEnd.length;
        colEnd.push(0);
      }
      colEnd[placed] = s.endUnit;
      assign.push(placed);
    }
    const cols = colEnd.length;
    members.forEach((idx, k) => {
      const s = sorted[idx];
      const rr = rowRange(s.startUnit, s.endUnit);
      out.push({ session: s, col: assign[k], cols, top: rr.top, span: rr.span });
    });
  }
  return out;
}

/** 取某周某天的课 */
export function sessionsOf(
  all: GZ.CourseSession[],
  wk: number,
  weekday: number,
): GZ.CourseSession[] {
  const out: GZ.CourseSession[] = [];
  for (const s of all) {
    if (s.week === wk && s.weekday === weekday) out.push(s);
  }
  return out;
}

/** 某周有几门课（去重）/ 几节 */
export function weekStats(
  all: GZ.CourseSession[],
  wk: number,
): { courses: number; sections: number } {
  const names: Record<string, number> = {};
  let sections = 0;
  for (const s of all) {
    if (s.week !== wk) continue;
    names[s.name] = 1;
    sections += Math.max(1, s.endUnit - s.startUnit + 1);
  }
  return { courses: Object.keys(names).length, sections };
}

/** 当前时间对应的下一节课（今天是周几 + 现在几点） */
export function nextSession(
  all: GZ.CourseSession[],
  wk: number,
  weekday: number,
  nowMinutes: number,
): GZ.CourseSession | null {
  const todays = sessionsOf(all, wk, weekday)
    .slice()
    .sort((a, b) => a.startUnit - b.startUnit);
  for (const s of todays) {
    const m = toMinutes(s.endTime) || 0;
    if (m > nowMinutes) return s;
  }
  return null;
}

/** "08:30" → 510 */
export function toMinutes(hhmm: string): number {
  const m = /^(\d{1,2}):(\d{2})$/.exec(hhmm || '');
  if (!m) return 0;
  return parseInt(m[1], 10) * 60 + parseInt(m[2], 10);
}

/** 现在几点（分钟） */
export function nowMinutes(): number {
  const d = new Date();
  return d.getHours() * 60 + d.getMinutes();
}

/** 正在上的课（开始 <= 现在 < 结束） */
export function currentSession(
  all: GZ.CourseSession[],
  wk: number,
  weekday: number,
  nowMin: number,
): GZ.CourseSession | null {
  const todays = sessionsOf(all, wk, weekday);
  for (const s of todays) {
    const a = toMinutes(s.startTime);
    const b = toMinutes(s.endTime);
    if (a && b && nowMin >= a && nowMin < b) return s;
  }
  return null;
}