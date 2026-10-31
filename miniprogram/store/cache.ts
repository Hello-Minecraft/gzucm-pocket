/**
 * 本地缓存 + 用户设置。
 *
 * 只存"重新拉一次也不心疼"的东西：课表、成绩、学期信息。
 * **不存 Cookie**（那在 services/cookie.ts 里单独管），更不存密码。
 *
 * 数据版本号：算法改了（比如修了周次错位）就 +1，旧缓存判定失效，强制重拉。
 * 这是 Android v1.3.0 的做法，必须照抄 —— 不升版本号，老用户升级后
 * 看到的还是旧算法算出来的错数据，会以为没修好。
 */

const K = {
  semester: 'gz_semester',
  sessions: 'gz_sessions',
  sessionsVer: 'gz_sessions_ver',
  grades: 'gz_grades',
  levels: 'gz_levels',
  fitness: 'gz_fitness',
  gradesVer: 'gz_grades_ver',
  syncAt: 'gz_sync_at',
  gradesAt: 'gz_grades_at',
  // 设置
  startTab: 'gz_start_tab',
  overlapMode: 'gz_overlap',
  gridLines: 'gz_grid',
  bgPath: 'gz_bg_path',
  bgAlpha: 'gz_bg_alpha',
  loginAt: 'gz_login_at',
};

/** 课表算法版本。改周次/日期换算时 +1 */
export const SESSIONS_VER = 2;
/** 成绩解析版本。新增字段时 +1（v2 加了 classify/kcflmc） */
export const GRADES_VER = 2;

function get(key: string): string | null {
  try {
    const v = wx.getStorageSync(key);
    return v === '' || v === undefined || v === null ? null : String(v);
  } catch (e) {
    return null;
  }
}

function set(key: string, v: string): void {
  try {
    wx.setStorageSync(key, v);
  } catch (e) {
    // 存储满：先清课表再试一次
    try {
      wx.removeStorageSync(K.sessions);
      wx.setStorageSync(key, v);
    } catch (e2) {
      /* 放弃 */
    }
  }
}

function getJson<T>(key: string): T | null {
  const raw = get(key);
  if (!raw) return null;
  try {
    return JSON.parse(raw) as T;
  } catch (e) {
    return null;
  }
}

function setJson(key: string, v: any): void {
  try {
    set(key, JSON.stringify(v));
  } catch (e) {
    /* 忽略 */
  }
}

// ── 学期 ──────────────────────────────────────────────────

export function loadSemester(): GZ.Semester | null {
  return getJson<GZ.Semester>(K.semester);
}

export function saveSemester(s: GZ.Semester): void {
  setJson(K.semester, s);
}

// ── 课表 ──────────────────────────────────────────────────

export function loadSessions(): GZ.CourseSession[] {
  const ver = Number(get(K.sessionsVer) || '0');
  if (ver < SESSIONS_VER) {
    // 算法升级过，旧缓存不可信，丢掉
    wx.removeStorageSync(K.sessions);
    return [];
  }
  const list = getJson<GZ.CourseSession[]>(K.sessions);
  return Array.isArray(list) ? list : [];
}

export function saveSessions(list: GZ.CourseSession[]): void {
  setJson(K.sessions, list);
  set(K.sessionsVer, String(SESSIONS_VER));
  set(K.syncAt, String(Date.now()));
}

export function syncAt(): number {
  return Number(get(K.syncAt) || '0');
}

// ── 成绩 ──────────────────────────────────────────────────

export function loadGrades(): GZ.CourseGrade[] {
  const ver = Number(get(K.gradesVer) || '0');
  if (ver < GRADES_VER) {
    wx.removeStorageSync(K.grades);
    return [];
  }
  const list = getJson<GZ.CourseGrade[]>(K.grades);
  return Array.isArray(list) ? list : [];
}

export function saveGrades(list: GZ.CourseGrade[]): void {
  setJson(K.grades, list);
  set(K.gradesVer, String(GRADES_VER));
  set(K.gradesAt, String(Date.now()));
}

export function gradesAt(): number {
  return Number(get(K.gradesAt) || '0');
}

export function loadLevels(): GZ.LevelGrade[] {
  const list = getJson<GZ.LevelGrade[]>(K.levels);
  return Array.isArray(list) ? list : [];
}

export function saveLevels(list: GZ.LevelGrade[]): void {
  setJson(K.levels, list);
}

export function loadFitness(): GZ.FitnessGrade[] {
  const list = getJson<GZ.FitnessGrade[]>(K.fitness);
  return Array.isArray(list) ? list : [];
}

export function saveFitness(list: GZ.FitnessGrade[]): void {
  setJson(K.fitness, list);
}

// ── 设置 ──────────────────────────────────────────────────

export function startTab(): number {
  return Number(get(K.startTab) || '0');
}

export function setStartTab(v: number): void {
  set(K.startTab, String(v));
}

/**
 * 重叠课程是否并排分列。
 *
 * 默认 **true** —— 并排是默认行为，开关是用来"关掉并排"的。
 * 之前写成读不到就 false，等于默认把同一时段的两门课叠成一门，
 * 用户会以为课表丢了课。
 */
export function overlapMode(): boolean {
  const v = get(K.overlapMode);
  return v === null ? true : v === '1';
}

export function setOverlapMode(v: boolean): void {
  set(K.overlapMode, v ? '1' : '0');
}

export function gridLines(): boolean {
  return get(K.gridLines) === '1';
}

export function setGridLines(v: boolean): void {
  set(K.gridLines, v ? '1' : '0');
}

export function bgPath(): string {
  return get(K.bgPath) || '';
}

export function setBgPath(p: string): void {
  set(K.bgPath, p);
}

export function bgAlpha(): number {
  const v = Number(get(K.bgAlpha) || '0.18');
  return isFinite(v) ? Math.min(0.6, Math.max(0, v)) : 0.18;
}

export function setBgAlpha(v: number): void {
  set(K.bgAlpha, String(v));
}

export function loginAt(): number {
  return Number(get(K.loginAt) || '0');
}

export function setLoginAt(): void {
  set(K.loginAt, String(Date.now()));
}

/** 退出登录：清会话和所有业务缓存，保留界面设置 */
export function clearData(): void {
  [
    K.semester,
    K.sessions,
    K.sessionsVer,
    K.grades,
    K.levels,
    K.fitness,
    K.gradesVer,
    K.syncAt,
    K.gradesAt,
    K.loginAt,
  ].forEach((k) => {
    try {
      wx.removeStorageSync(k);
    } catch (e) {
      /* 忽略 */
    }
  });
}

/** 当前占用（KB），设置页显示用 */
export function usageKB(): number {
  try {
    const info = wx.getStorageInfoSync();
    return Math.round(info.currentSize);
  } catch (e) {
    return 0;
  }
}