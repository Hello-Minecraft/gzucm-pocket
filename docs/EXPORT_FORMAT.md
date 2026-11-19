# 课表导出格式

App 内入口：**设置 → 导出课表数据**，两个选项：

| 选项 | 产物 | 带什么 |
|---|---|---|
| 复制分享串 | 一行 `GZP2:…`，约 1700 字符 | 课名 / 星期 / 节次 / 周次 / 教室 |
| 导出 JSON 文件 | 缩进明文 `.json` | 上面全部 **+** 教师 / 类别 / 教学方式 / 配色 / 课程代码 / 逐周上课内容 |

两条路的落差是**故意的**：微信单条文本上限约 2000 字，要塞进去就得砍字段。
最贵的一列是**任课教师** —— 见习轮转一周一个带教老师，115 个名字各不相同、
几乎不重复，deflate 压不动，光这一列就 1400 多字符。所以串里不带教师，
要教师请用文件（或让对方发文件）。

## 分享串（GZP2）

```
GZP2:<Base64URL(raw deflate(紧凑 JSON))>
```

- `GZP2:` 是前缀兼版本号。看到 `GZP1:` 是 2026-11-18 之前的旧串，本版不再生成。
- raw deflate = RFC 1951（无 zlib/gzip 头尾），浏览器 `DecompressionStream('deflate-raw')` 直接解。
- Base64URL（`-` `_`，无 `=`），避免 `+` `/` 被链接识别、输入法转义。

### 为什么换 GZP2：见习轮转把 GZP1 撑爆了

GZP1 是把「课程模板」原样压一遍，模板的合并键是 (课名, 星期, 节次, 教室, 教师…) **全等**。
见习轮转恰好每周都换科室和带教老师 → 14 门课炸成 **151 条模板**，实测 **5124 字符**，
微信根本发不出去。

GZP2 换三件事，同一份数据 **5124 → 1751**：

1. **字典化**：课名只有 14 个、教室 29 个，却被 151 条模板各写一遍。提到 `names` / `rooms` 数组里，行内只留下标。
2. **分组只看到「课名 + 星期 + 起止节」**：教室和周次退化成叶子 `["4", 3]`（第 4 周 → 教室 3 号）。151 条压成 42 组。
3. **周次写区间**：`[4]`→`"4"`、`[5,6]`→`"5-6"`、`[1..18]`→`"1-18"`。

### 紧凑 JSON 的形状

```json
{
  "sem":   ["2026-09-07", 20],
  "cov":   "1-20",
  "slots": ["08:30-09:10", "09:20-10:00", "..."],
  "names": ["外科学(2)", "中医儿科学"],
  "rooms": ["综教楼820（智慧课室）", "一附院乳腺科"],
  "courses": [
    [0, 1, 7, 9, [["4", 1], ["15", 0], ["5-6", 1]]]
  ]
}
```

| 字段 | 含义 |
|---|---|
| `sem` | `[第 1 周周一, 总周数]` |
| `cov` | 实际覆盖到的周（区间写法）。App 首屏只抓本周、其余后台补，缺周时靠它提示 |
| `slots` | 1~14 节作息，`"起-止"` |
| `names` / `rooms` | 课名、教室字典，行内用下标引用 |
| `courses` | 每行 `[名字下标, 星期, 起节, 止节, 叶子数组]` |

叶子数组每项 `[周次串, 教室下标]`；同一组课不同周去不同科室，就是多个叶子。

## 网页端解码（复制即用）

```js
// ① 解压 + 展开成「和文件同构」的形状，后面的渲染代码两种来源共用
async function decodeTimetable(text) {
  const token = (text.match(/GZP2:[A-Za-z0-9_-]+/) || [])[0];
  if (!token) throw new Error('没找到 GZP2: 开头的课表串');
  const b64 = token.slice(5).replace(/-/g, '+').replace(/_/g, '/');
  const bin = Uint8Array.from(atob(b64 + '='.repeat((4 - b64.length % 4) % 4)), c => c.charCodeAt(0));
  const stream = new Blob([bin]).stream().pipeThrough(new DecompressionStream('deflate-raw'));
  return expand(JSON.parse(await new Response(stream).text()));
}

function expandWeeks(s) {
  if (!s) return [];
  return s.split(',').flatMap(p => {
    const [a, b] = p.split('-').map(Number);
    return b === undefined ? [a] : Array.from({ length: b - a + 1 }, (_, i) => a + i);
  });
}

function expand(c) {
  const courses = [];
  for (const [ni, wd, su, eu, cells] of c.courses) {
    const byRoom = new Map();          // 同一组里教室不同 → 拆成多条（和文件版一致）
    for (const [ws, ri] of cells) {
      const list = byRoom.get(ri) || [];
      list.push(...expandWeeks(ws));
      byRoom.set(ri, list);
    }
    for (const [ri, weeks] of byRoom) {
      courses.push({
        name: c.names[ni], weekday: wd, startUnit: su, endUnit: eu,
        room: c.rooms[ri], weeks: [...new Set(weeks)].sort((a, b) => a - b),
      });
    }
  }
  return {
    firstMonday: c.sem[0], totalWeeks: c.sem[1],
    coveredWeeks: expandWeeks(c.cov), slots: c.slots, courses,
  };
}
```

兼容性：`DecompressionStream('deflate-raw')` 要 Chrome/Edge 80+、Safari 16.4+、Firefox 113+。
要用 Node 验：`zlib.inflateRawSync(Buffer.from(b64, 'base64url'))`（Python 是 `zlib.decompress(raw, -15)`）。

## 文件（完整版）

文件是 **schemaVersion 1** 的明文 JSON，**缩进两格、UTF-8、无 BOM**，直接 `JSON.parse` /
`fetch(...).json()`，不需要任何解码步骤。

```js
const data = JSON.parse(await input.files[0].text());
```

文件名形如 `广中医口袋课表-20261118-0042.json`（导出时刻，分钟级）。
App 落盘在 `cacheDir/exports/`，通过 `FileProvider`（authority `<包名>.fileprovider`）
以 `content://` 交给系统分享面板，MIME `application/json`。

```json
{
  "schemaVersion": 1,
  "kind": "gzucm-pocket-timetable",
  "exportedAt": "2026-11-18T01:20:00+08:00",
  "app": { "versionName": "1.3.6", "versionCode": 10306 },
  "semester": { "code": "202601", "name": "2026-2027 学年 第一学期",
                "firstMonday": "2026-09-07", "totalWeeks": 20 },
  "coverage": { "complete": true, "weeks": [1, 2, 3] },
  "slots": [ { "unit": 1, "start": "08:30", "end": "09:10" } ],
  "courses": [
    { "id": "c1", "name": "外科学(2)", "weekday": 1, "startUnit": 7, "endUnit": 9,
      "weeks": [4], "code": "101227331", "teachers": "邓韫洁",
      "startTime": "14:50", "endTime": "17:10", "room": "一附院乳腺科",
      "category": "实验教学", "teachingMode": "现场教学", "schoolColor": "#5FB878" }
  ],
  "contents": [ { "courseId": "c1", "week": 4, "content": "乳腺查体" } ]
}
```

**空字段会被省掉**（`encodeDefaults = false`）：`teachingMode`、`schoolColor`、`code`、
`room` 这些都可能不存在，读的时候 `?? ''` 兜一下。

### semester

第 N 周第 W 天 = `firstMonday + (N-1)×7 + (W-1)` 天。有了它网页不用跟 App 要日期。

### coverage

补周完成前导出会缺周且**从课程数据本身看不出来**，所以单独给一段：
`complete=false` 时提示「数据不完整，回 App 同步后重导」。

### slots（作息表）

1~14 节连续编号，每节 40 分钟（大课间：第 2 节 10:00 下课、第 3 节 10:20 上课，
所以别自己按 40 分钟链式推，直接用这张表）。

### courses（渲染用这个）

- **已按周合并**：同一门课同一时段（教室、教师、教学模式都一样）只占一条，`weeks` 升序去重。
- ⚠️ **见习轮转会拆得很碎**：每周换科室/带教，就是一周一条。20 周的一门课能拆出十几二十条 ——
  这是真实数据，不是 bug。
- `weekday`：**1 = 周一 … 7 = 周日**。教务接口是周日开头，App 同步时已经转换，这里直接用。
- `id` 规则：按「星期 → 起始节 → 课程名 → 教室」排序后从 `c1` 递增，同一份数据每次导出都稳定。
- `startTime` / `endTime` 是教务给的原始起止，可能不等于 `slots` 推出来的值，**优先用课程自带的这两个**。

```js
const thisWeek = data.courses.filter(c => c.weeks.includes(week));
```

### contents（只有文件有）

逐周上课内容。串里不带 —— 它是唯一按周增长的部分。格式：

```json
"contents": [ { "courseId": "c1", "week": 8, "content": "咳嗽" } ]
```

网页端按 `courseId + week` 查；查不到就是那周没标内容。**分享串没有这个键**，别硬读。

## 不在范围内的东西

- **日程 / 截止任务**、**考试安排**：v1 不导（2026-11-18 定）。要加就升 `schemaVersion`，别原地塞字段。
- 手动的重叠课轮换顺序、主题色、提醒设置等纯本机偏好也不导。

## 体积参考（实测）

| 数据 | 分享串 GZP1 | 分享串 GZP2 | 文件 |
|---|---|---|---|
| 见习轮转：151 条模板 / 20 周（真实数据） | 5124 字符 ❌ | **1751 字符** ✅ | 约 36 KB |
| 普通学期：25 门 × 18 周 | ~1300 字符 | 约 **650 字符** | 约 25 KB |

微信上限按 2000 卡；单测里有一条闸门盯着「见习轮转数据必须 < 1900 字符」。
