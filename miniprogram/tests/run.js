/**
 * 核心逻辑测试。
 *
 * 不用 Jest（这台机器不装 npm 依赖），直接 node 跑：
 *   node tests/run.js
 *
 * 测的是「错了会静默出错」的部分：
 *   · 周次换算 —— 错了会让周日课程整体错位一周（Android 版出过线上 bug）
 *   · 绩点口径 —— 错了数字看起来正常但是错的
 *   · 排布算法 —— 错了课程块会互相压住
 *   · 二维码 —— 错了扫不出来，但界面上看起来一切正常
 *
 * 因此测试文件先进 tsc 编成 JS 再断言 —— 编译产物放在 tests/out/。
 */

/* eslint-disable */
const assert = require('assert');
const path = require('path');

const OUT = path.join(__dirname, 'out');

const week = require(path.join(OUT, 'utils/week.js'));
const layout = require(path.join(OUT, 'utils/layout.js'));
const gpa = require(path.join(OUT, 'utils/gpa.js'));
const fmt = require(path.join(OUT, 'utils/format.js'));
const qr = require(path.join(OUT, 'utils/qrcode.js'));

let pass = 0;
let fail = 0;
const failures = [];

function t(name, fn) {
  try {
    fn();
    pass++;
  } catch (e) {
    fail++;
    failures.push({ name, msg: e.message });
  }
}

// ── 周次换算 ────────────────────────────────────────────
// 学期第 1 周周一 = 2026-09-07

const SEM = {
  code: '202601',
  name: '2026-2027 学年 第一学期',
  firstMonday: '2026-09-07',
  totalWeeks: 19,
};

t('weekOf 第 1 周周一 → 1', () => {
  assert.strictEqual(week.weekOf(SEM, week.parseDate('2026-09-07')), 1);
});

t('weekOf 第 1 周周日 → 1（周一制）', () => {
  // 09-13 是第 1 周的周日，仍属第 1 周
  assert.strictEqual(week.weekOf(SEM, week.parseDate('2026-09-13')), 1);
});

t('weekOf 第 2 周周一 → 2', () => {
  assert.strictEqual(week.weekOf(SEM, week.parseDate('2026-09-14')), 2);
});

t('weekOf 学期开始前一天 → 0', () => {
  assert.strictEqual(week.weekOf(SEM, week.parseDate('2026-09-06')), 0);
});

t('dateOf 第 8 周周日 → 2026-10-25', () => {
  // 第 8 周周一 = 09-07 + 7*7 = 10-26，周日 = 11-01
  // 注意这里测的是「第 8 周的第 7 天」= 11-01
  assert.strictEqual(week.dateOf(SEM, 8, 7), '2026-11-01');
});

t('dateOf 第 8 周周一 → 2026-10-26', () => {
  assert.strictEqual(week.dateOf(SEM, 8, 1), '2026-10-26');
});

t('weekdayOf 2026-09-07 是周一', () => {
  assert.strictEqual(week.weekdayOf(week.parseDate('2026-09-07')), 1);
});

t('weekdayOf 2026-09-13 是周日（不是 0）', () => {
  assert.strictEqual(week.weekdayOf(week.parseDate('2026-09-13')), 7);
});

t('教务周日的课要落到上一周（核心 bug 回归）', () => {
  // 教务「第 8 周」是 10-25(周日)~10-31(周六)
  // 10-25 在 App 的周一制里属于第 7 周
  const d = week.parseDate('2026-10-25');
  assert.strictEqual(week.weekOf(SEM, d), 7, '10-25 应落到第 7 周');
  const d2 = week.parseDate('2026-10-26');
  assert.strictEqual(week.weekOf(SEM, d2), 8, '10-26 应落到第 8 周');
});

t('guessSemesterCode 8 月算第一学期', () => {
  assert.strictEqual(week.guessSemesterCode(new Date(2026, 7, 15)), '202601');
});

t('guessSemesterCode 7 月算第二学期', () => {
  assert.strictEqual(week.guessSemesterCode(new Date(2026, 6, 15)), '202502');
});

t('previousSemesterCode 秋季 → 上一年春季', () => {
  assert.strictEqual(week.previousSemesterCode('202601'), '202502');
});

t('previousSemesterCode 春季 → 同年秋季', () => {
  // 202502 = 2025-2026 学年第二学期，前一个是 2025-2026 学年第一学期 = 202501
  assert.strictEqual(week.previousSemesterCode('202502'), '202501');
});

// ── 数字格式化 ──────────────────────────────────────────

t('d2 处理浮点边界 8.575 → 8.58（不是 8.57）', () => {
  assert.strictEqual(fmt.d2(8.575), '8.58');
});

t('d2 处理 1.005 → 1.01（不是 1.00）', () => {
  assert.strictEqual(fmt.d2(1.005), '1.01');
});

t('trim1 去掉多余 0：2.00 → 2', () => {
  assert.strictEqual(fmt.trim1(2.0), '2');
});

t('trim1 保留有效小数：2.50 → 2.5', () => {
  assert.strictEqual(fmt.trim1(2.5), '2.5');
});

t('trim1 保留两位有效：2.55 → 2.6（一位小数四舍五入）', () => {
  assert.strictEqual(fmt.trim1(2.55), '2.6');
});

// ── 节次与排布 ──────────────────────────────────────────

t('slotIndex 找到第 1 节 → 0', () => {
  assert.strictEqual(layout.slotIndex(1), 0);
});

t('slotIndex 跳号：第 5 节不存在 → -1', () => {
  assert.strictEqual(layout.slotIndex(5), -1);
});

t('rowRange 1-2 节 → 从 0 开始占 2 行', () => {
  const r = layout.rowRange(1, 2);
  assert.strictEqual(r.top, 0);
  assert.strictEqual(r.span, 2);
});

t('rowRange 3-4 节 → 从第 2 行开始占 2 行', () => {
  const r = layout.rowRange(3, 4);
  assert.strictEqual(r.top, 2);
  assert.strictEqual(r.span, 2);
});

t('rowRange 跨中午 4-6 节（4→6 跳过 5）', () => {
  // 4 在 index 3，6 在 index 4
  const r = layout.rowRange(4, 6);
  assert.strictEqual(r.top, 3);
  assert.strictEqual(r.span, 2);
});

t('placeDay 两门不冲突的课 → 各占整宽', () => {
  const a = { name: 'A', startUnit: 1, endUnit: 2, weekday: 1 };
  const b = { name: 'B', startUnit: 3, endUnit: 4, weekday: 1 };
  const r = layout.placeDay([a, b]);
  assert.strictEqual(r.length, 2);
  r.forEach((p) => assert.strictEqual(p.cols, 1, '不冲突应各占整宽'));
});

t('placeDay 两门重叠 → 并排 2 列', () => {
  const a = { name: 'A', startUnit: 1, endUnit: 4, weekday: 1 };
  const b = { name: 'B', startUnit: 3, endUnit: 6, weekday: 1 };
  const r = layout.placeDay([a, b]);
  assert.strictEqual(r.length, 2);
  r.forEach((p) => assert.strictEqual(p.cols, 2, '重叠应并排'));
});

t('placeDay 三门互相重叠 → 3 列', () => {
  const a = { name: 'A', startUnit: 1, endUnit: 4, weekday: 1 };
  const b = { name: 'B', startUnit: 2, endUnit: 5, weekday: 1 };
  const c = { name: 'C', startUnit: 3, endUnit: 6, weekday: 1 };
  const r = layout.placeDay([a, b, c]);
  r.forEach((p) => assert.strictEqual(p.cols, 3));
});

t('placeDay 首尾相接不算冲突（1-2 与 3-4）', () => {
  const a = { name: 'A', startUnit: 1, endUnit: 2, weekday: 1 };
  const b = { name: 'B', startUnit: 3, endUnit: 4, weekday: 1 };
  const r = layout.placeDay([a, b]);
  r.forEach((p) => assert.strictEqual(p.cols, 1));
});

t('placeDay 完全重叠也算冲突（同一时段两门课）', () => {
  const a = { name: 'A', startUnit: 1, endUnit: 2, weekday: 1 };
  const b = { name: 'B', startUnit: 1, endUnit: 2, weekday: 1 };
  const r = layout.placeDay([a, b]);
  r.forEach((p) => assert.strictEqual(p.cols, 2, '完全重合必须分开，不能叠在一起'));
});

t('placeDay 两簇分开各自算列数', () => {
  // 早上一簇 2 门，下午 1 门 —— 下午应占整宽，不被早上的 2 列拖累
  const a = { name: 'A', startUnit: 1, endUnit: 4, weekday: 1 };
  const b = { name: 'B', startUnit: 2, endUnit: 3, weekday: 1 };
  const c = { name: 'C', startUnit: 6, endUnit: 7, weekday: 1 };
  const r = layout.placeDay([a, b, c]);
  const pm = r.find((p) => p.session.name === 'C');
  assert.strictEqual(pm.cols, 1, '下午单独一门应占整宽');
});

// ── 绩点口径 ────────────────────────────────────────────

function g(name, category, credit, gp, passed) {
  return {
    term: '2025-2026-1',
    name,
    score: passed ? 85 : 50,
    gpa: gp,
    credit,
    category,
    classify: '',
    nature: '考试',
    remark: '',
    passed,
  };
}

t('counted 专业限选课要计入', () => {
  assert.strictEqual(gpa.counted('专业限选课'), true);
});

t('counted 公共选修课不计入', () => {
  assert.strictEqual(gpa.counted('公共选修课'), false);
});

t('counted 通识课要计入（里面有体育、毛概，都是必修）', () => {
  assert.strictEqual(gpa.counted('通识课'), true);
});

t('counted 空类别默认计入', () => {
  assert.strictEqual(gpa.counted(''), true);
});

t('weighted 排除公选和挂科', () => {
  const list = [
    g('必修A', '专业课', 4, 4.0, true),
    g('公选B', '公共选修课', 2, 3.0, true),
    g('挂科C', '专业课', 3, 0.0, false),
  ];
  const r = gpa.weighted(list);
  assert.strictEqual(r.credit, 4, '只算必修那门');
  assert.strictEqual(r.gpa, 4.0);
});

t('weighted 加权平均正确', () => {
  const list = [
    g('A', '专业课', 4, 4.0, true),
    g('B', '专业课', 2, 2.0, true),
  ];
  // (4*4 + 2*2) / 6 = 20/6 = 3.333
  const r = gpa.weighted(list);
  assert.ok(Math.abs(r.gpa - 20 / 6) < 1e-9, `实际 ${r.gpa}`);
});

t('passedCredit 只算及格', () => {
  const list = [
    g('A', '专业课', 4, 4.0, true),
    g('B', '专业课', 3, 0.0, false),
  ];
  assert.strictEqual(gpa.passedCredit(list), 4);
});

// ── 学分构成 ────────────────────────────────────────────

function pg(name, classify, credit) {
  return {
    term: 't',
    name,
    score: 85,
    gpa: 3.5,
    credit,
    category: '公共选修课',
    classify,
    nature: '考试',
    remark: '',
    passed: true,
  };
}

t('creditRows 公选按 classify 分组', () => {
  const list = [
    pg('A', '美育类', 2),
    pg('B', '美育类', 2),
    pg('C', '网络课程类', 3),
  ];
  const r = gpa.creditRows(list, true);
  assert.strictEqual(r.rows.length, 2);
  const mei = r.rows.find((x) => x.label === '美育类');
  assert.strictEqual(mei.credit, 4);
});

t('creditRows 满 4 分的条要顶满（分母 = 4）', () => {
  const list = [pg('A', '美育类', 2), pg('B', '美育类', 2), pg('C', '网络课程类', 3)];
  const r = gpa.creditRows(list, true);
  const mei = r.rows.find((x) => x.label === '美育类');
  assert.strictEqual(mei.ratio, 1, '4/4 应该等于 1');
  const net = r.rows.find((x) => x.label === '网络课程类');
  assert.ok(Math.abs(net.ratio - 0.75) < 1e-9, '3/4 应该是 0.75');
});

t('creditRows 修超时分母退到最高那类（6 分）', () => {
  const list = [
    pg('A', '美育类', 2),
    pg('B', '美育类', 2),
    pg('C', '美育类', 2), // 美育 6 分
    pg('D', '网络课程类', 4),
  ];
  const r = gpa.creditRows(list, true);
  const mei = r.rows.find((x) => x.label === '美育类');
  assert.strictEqual(mei.ratio, 1, '6/6 顶满');
  const net = r.rows.find((x) => x.label === '网络课程类');
  assert.ok(Math.abs(net.ratio - 4 / 6) < 1e-9, `4/6 ≈ 0.667，实际 ${net.ratio}`);
});

t('creditRows 未分类不丢弃', () => {
  const list = [pg('A', '', 2), pg('B', '美育类', 2)];
  const r = gpa.creditRows(list, true);
  assert.ok(r.rows.some((x) => x.label === '未分类'), '空分类应归到未分类');
  assert.strictEqual(r.total, 4, '总数要包含未分类');
});

t('creditRows 总学分视角按 category 分组', () => {
  const list = [
    g('A', '专业课', 4, 4.0, true),
    g('B', '专业基础课', 5, 3.0, true),
    g('C', '专业课', 3, 3.5, true),
  ];
  const r = gpa.creditRows(list, false);
  assert.strictEqual(r.rows.length, 2);
  const pro = r.rows.find((x) => x.label === '专业课');
  assert.strictEqual(pro.credit, 7);
});

t('creditRows 不算挂科（跟已修学分对齐）', () => {
  const list = [g('A', '专业课', 4, 4.0, true), g('B', '专业课', 2, 0.0, false)];
  const r = gpa.creditRows(list, false);
  assert.strictEqual(r.total, 4, '挂科的 2 学分不该算进去');
});

// ── 二维码 ──────────────────────────────────────────────

t('encode 生成合法尺寸（版本 1-10）', () => {
  const m = qr.encode('https://jw.gzucm.edu.cn/#/qrcodeConfirm/showConfirm?qrcodeId=x');
  assert.ok(m, '应能编码');
  const n = m.size;
  assert.ok(n >= 21 && n <= 57, `尺寸 ${n} 超出范围`);
  assert.strictEqual((n - 17) % 4, 0, '尺寸必须满足 17+4v');
});

t('encode 三个角有标准定位图案', () => {
  const m = qr.encode('HELLO');
  const n = m.size;
  /*
   * 标准 7x7 定位图案（外圈黑边框 + 白环 + 3x3 黑心）：
   *   #######
   *   #.....#
   *   #.###.#
   *   #.###.#
   *   #.###.#
   *   #.....#
   *   #######
   * 所以 (0,0)=黑 (1,1)=白 (2,2)=黑 (3,3)=黑 (5,5)=白
   */
  assert.strictEqual(m.modules[0][0], true, '左上 (0,0) 黑');
  assert.strictEqual(m.modules[1][1], false, '左上 (1,1) 白环');
  assert.strictEqual(m.modules[2][2], true, '左上 (2,2) 黑心');
  assert.strictEqual(m.modules[3][3], true, '左上 (3,3) 黑心');
  assert.strictEqual(m.modules[5][5], false, '左上 (5,5) 白环');
  assert.strictEqual(m.modules[0][6], true, '左上 (0,6) 边框');
  assert.strictEqual(m.modules[6][0], true, '左上 (6,0) 边框');

  // 右上：定位图案在右边，(n-7,0) 是它的左上角
  assert.strictEqual(m.modules[0][n - 1], true, '右上 (n-1,0) 黑');
  assert.strictEqual(m.modules[1][n - 2], false, '右上白环');

  // 左下：定位图案在底部，(0,n-7) 是它的左上角
  assert.strictEqual(m.modules[n - 1][0], true, '左下 (0,n-1) 黑');
  assert.strictEqual(m.modules[n - 2][1], false, '左下白环');
});

t('encode 定时图案交替', () => {
  const m = qr.encode('HELLO');
  // 水平定时线在第 6 行：从第 8 列到 size-9 列，偶数为黑
  for (let x = 8; x < m.size - 8; x++) {
    assert.strictEqual(m.modules[6][x], x % 2 === 0, `水平定时 (${x},6) 错`);
  }
  // 垂直定时线在第 6 列
  for (let y = 8; y < m.size - 8; y++) {
    assert.strictEqual(m.modules[y][6], y % 2 === 0, `垂直定时 (6,${y}) 错`);
  }
});

t('encode 长文本能编码', () => {
  const long = 'https://jw.gzucm.edu.cn/#/qrcodeConfirm/showConfirm?qrcodeId=qrcodeLogin_ea41c39e6d7c408599717c106295944c';
  const m = qr.encode(long);
  assert.ok(m, '长 URL 应能编码');
});

t('encode 大文本能选到更高版本', () => {
  // 100 字节在 M 级下需要 v6（尺寸 41）
  const m = qr.encode('a'.repeat(100));
  assert.ok(m);
  assert.ok(m.size >= 41, `尺寸应 ≥41，实际 ${m.size}`);
});

t('encode 版本 7 以上画了版本信息区', () => {
  // 150 字节需要 v8（尺寸 49），会启用版本信息
  const m = qr.encode('b'.repeat(150));
  assert.ok(m, '应能编码');
  assert.ok(m.size >= 45, `版本应 ≥7（尺寸≥45），实际 ${m.size}`);
  // 版本信息区在左下 (0..5, size-11..size-9) 与右上镜像，不应该是全白
  let dark = 0;
  const s = m.size;
  for (let i = 0; i < 18; i++) {
    const a = s - 11 + (i % 3);
    const b = Math.floor(i / 3);
    if (m.modules[b][a]) dark++;
  }
  assert.ok(dark > 0 && dark < 18, `版本信息应黑白混合，实际黑 ${dark}/18`);
});

// ── 结果 ────────────────────────────────────────────────

console.log('');
if (fail === 0) {
  console.log(`✓ 全部通过（${pass} 项）`);
  process.exit(0);
} else {
  console.log(`✗ ${fail} 项失败 / 共 ${pass + fail} 项\n`);
  for (const f of failures) {
    console.log(`  ✗ ${f.name}`);
    console.log(`    ${f.msg}`);
  }
  process.exit(1);
}