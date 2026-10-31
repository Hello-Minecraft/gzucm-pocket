/// <reference path="./wx/index.d.ts" />

/** 数据模型：与 Android 版 core/Models.kt 一一对应，字段名保持一致便于对照 */
declare namespace GZ {

  /** 学期。firstMonday 是第 1 周的周一（教务给的是第 1 周周日，要 +1 天） */
  interface Semester {
    code: string;
    name: string;
    /** ISO 日期 yyyy-MM-dd */
    firstMonday: string;
    totalWeeks: number;
  }

  /** 一节课（某周某天的某一节） */
  interface CourseSession {
    name: string;
    code: string;
    teachers: string;
    week: number;
    weekday: number;
    startUnit: number;
    endUnit: number;
    startTime: string;
    endTime: string;
    room: string;
    category: string;
    teachingMode?: string;
    mergedClasses?: string;
    studyHours?: string;
    courseSeq?: string;
    schoolColor?: string;
    /** sknrjj 上课内容，只有按周查询才有 */
    content?: string;
    /** qsrq 真实日期 yyyy-MM-dd，只有按周查询才有 */
    date?: string;
  }

  /** 课程成绩 */
  interface CourseGrade {
    term: string;
    name: string;
    score: number;
    gpa: number;
    credit: number;
    /** kcdlmc 课程大类：公共选修课 / 专业基础课 … */
    category: string;
    /** kcflmc 课程分类：美育类 / 科学技术与文明类 …，只有部分课有 */
    classify: string;
    nature: string;
    remark: string;
    passed: boolean;
  }

  /** 考级成绩 */
  interface LevelGrade {
    name: string;
    term: string;
    total: number;
    level: string;
    date: string;
    parts: Array<{ label: string; value: string }>;
  }

  /** 体测单项 */
  interface FitnessItem {
    label: string;
    value: string;
    score: string;
    extra: string;
  }

  /** 体测成绩 */
  interface FitnessGrade {
    term: string;
    total: string;
    level: string;
    items: FitnessItem[];
  }

  /** 已登录会话（Cookie 按 host 分组，值是 name=value 串） */
  interface AuthSession {
    jw: string;
    savedAt: number;
  }

  /** 课表抓取进度 */
  interface SyncProgress {
    done: number;
    total: number;
    label: string;
  }
}