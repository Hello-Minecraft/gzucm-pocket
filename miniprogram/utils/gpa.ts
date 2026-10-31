/**
 * 加权绩点与学分统计。
 *
 * 口径与 Android 的 core/Gpa.kt 完全一致 —— 那里的每一条判断都有来历：
 *
 *   · 学校评优/保研用「必修 + 限选」，任选不计
 *   · 「通识课」里装的是体育、毛概、大学英语，**都是必修**，
 *     所以不能按「通识」二字排除（这是最容易写错的地方）
 *   · 挂了课的学分不能计入已修学分
 */

/** 这门课是否计入加权绩点 */
export function counted(category: string): boolean {
  const c = (category || '').trim();
  if (!c) return true;
  if (c.indexOf('限选') >= 0) return true; // 专业限选课要算
  if (c.indexOf('选修') >= 0 || c.indexOf('任选') >= 0) return false;
  return true;
}

/** Σ(绩点×学分) / Σ学分；只统计及格且计入口径的课 */
export function weighted(grades: GZ.CourseGrade[]): { gpa: number; credit: number } {
  let sum = 0;
  let credit = 0;
  for (const g of grades) {
    if (!g.passed) continue;
    if (!counted(g.category)) continue;
    if (!(g.credit > 0)) continue;
    sum += g.gpa * g.credit;
    credit += g.credit;
  }
  if (credit <= 0) return { gpa: 0, credit: 0 };
  return { gpa: sum / credit, credit };
}

/** 已修学分：所有及格课程的学分（不看口径） */
export function passedCredit(grades: GZ.CourseGrade[]): number {
  let c = 0;
  for (const g of grades) {
    if (g.passed) c += g.credit;
  }
  return c;
}

/** 平均分：全部课程（含挂科，那是真实分数） */
export function avgScore(grades: GZ.CourseGrade[]): number {
  if (!grades.length) return 0;
  let s = 0;
  for (const g of grades) s += g.score;
  return s / grades.length;
}

/** 是否为公共选修课 */
export function isPublicElective(g: GZ.CourseGrade): boolean {
  const c = g.category || '';
  return c.indexOf('公共选修') >= 0 || c.indexOf('通识选修') >= 0;
}

/**
 * 公选课每类要修满的学分（23 级铁涛班是 4）。
 * 只作为进度条分母的下限，不拿来判定"修够了没有" —— 各年级培养计划不同。
 */
export const PUBLIC_FULL = 4;

export interface CreditRow {
  label: string;
  credit: number;
  ratio: number;
}

/**
 * 学分构成。
 *
 * @param publicOnly false = 按课程大类汇总（分母是总和）
 *                   true  = 只看公选，按 kcflmc 细分
 *
 * 公选视角的分母：max(4, 各类最大值)。
 *   每类都没超 4 → 分母 4，满 4 分的条顶满，一眼看得出哪类还差；
 *   有人某类修到 6 分 → 分母退到 6，4 分的条变成 2/3，不会冲出格子。
 */
export function creditRows(
  grades: GZ.CourseGrade[],
  publicOnly: boolean,
): { rows: CreditRow[]; total: number; count: number } {
  // 只算及格的，跟「已修学分」卡片对齐，否则点开各项加起来比卡片数字大
  const passed = grades.filter((g) => g.passed);
  const list = publicOnly ? passed.filter(isPublicElective) : passed;

  const map: Record<string, number> = {};
  for (const g of list) {
    const key = publicOnly ? g.classify || '未分类' : g.category || '其他';
    map[key] = (map[key] || 0) + g.credit;
  }
  const rows = Object.keys(map)
    .map((k) => ({ label: k, credit: map[k] }))
    .sort((a, b) => b.credit - a.credit);

  let total = 0;
  for (const r of rows) total += r.credit;

  let denom = total;
  if (publicOnly) {
    let maxCredit = 0;
    for (const r of rows) if (r.credit > maxCredit) maxCredit = r.credit;
    denom = Math.max(PUBLIC_FULL, maxCredit);
  }
  if (denom <= 0) denom = 0.1;

  return {
    rows: rows.map((r) => ({ ...r, ratio: Math.min(1, r.credit / denom) })),
    total,
    count: list.length,
  };
}

/** 按学期分组，学期倒序（新的在前） */
export function byTerm(grades: GZ.CourseGrade[]): Array<{ term: string; list: GZ.CourseGrade[] }> {
  const map: Record<string, GZ.CourseGrade[]> = {};
  for (const g of grades) {
    const t = g.term || '未知学期';
    (map[t] || (map[t] = [])).push(g);
  }
  return Object.keys(map)
    .map((term) => ({ term, list: map[term].slice().sort((a, b) => b.score - a.score) }))
    .sort((a, b) => (a.term < b.term ? 1 : -1));
}

/** 某学期加权绩点 */
export function termGpa(list: GZ.CourseGrade[]): number {
  return weighted(list).gpa;
}