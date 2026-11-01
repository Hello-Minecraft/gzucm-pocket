# 研究生课表接入方案

> 调研日期：2026-11-01
> 目标：让 App 同时支持本科（`jw.gzucm.edu.cn`）和研究生（`yjsglxt.gzucm.edu.cn`）
> 已定方案：**手动选择学段 + 两次登录**

---

## 一、实测结论

### 1.1 两套系统毫无关联

| 项 | 本科 | 研究生 |
|---|---|---|
| 域名 | `jw.gzucm.edu.cn` | `yjsglxt.gzucm.edu.cn` |
| 技术栈 | 金智 Wisedu + layui | ASP.NET MVC + jQuery EasyUI |
| 路径前缀 | `/new/` | `/gmis5/` |
| 认证 | CAS（`authserver`）+ `JSESSIONID` | 独立登录页 + 自己的 Cookie |
| 会话 | 独立 | 独立 |

**没有单点登录。** 两个系统的 Cookie 互不通用，必须分别登录。

### 1.2 研究生系统已登录时能看到什么

已用真实账号（学号 2026 开头，2026 级硕士）验证：

```
当前学期: 2026-2027秋学期
菜单：个人管理 / 培养管理 / 学籍异动 / 研工管理 / 科研管理 / 开题与中期 / 毕业与学位
```

关键入口：

| 功能 | 路径 |
|---|---|
| 学生课表查询 | `/gmis5/student/pygl/xskbcx` |
| 课程成绩查询 | `/gmis5/student/pygl/xscjcx` |

---

## 二、课表接口（已跑通）

### 2.1 接口定义

```
POST /gmis5/student/pygl/py_kbcx_ew1
Content-Type: application/x-www-form-urlencoded; charset=UTF-8
X-Requested-With: XMLHttpRequest

kblx=xs&termcode=48&skksz=&skjsz=&xzbj=&jh=
```

| 参数 | 含义 | 备注 |
|---|---|---|
| `kblx` | 课表类型 | 固定 `xs`（学生） |
| `termcode` | 学期码 | 见 §2.4 |
| `skksz` | 开始周 | 空 = 全部 |
| `skjsz` | 结束周 | 空 = 全部 |
| `xzbj` | 行政班级 | 空 = 本人 |
| `jh` | 集合/计划 | 空 = 全部 |

### 2.2 返回结构

```json
{
  "rows": [
    {
      "jcid": 11,
      "mc": "上午1",
      "sjbz": "上午",
      "jzsj": "08:30-09:10",
      "bj1": null,
      "bj2": "9ff1704f-4ab4-4f75-bbd7-f0c50bb398d4",
      "bj3": null, "bj4": null, "bj5": null, "bj6": null, "bj7": null,
      "z1": "",
      "z2": "2026级大学城硕士7、9、11班-英语（专业型硕士）<br/>[10-10周] 2026-11-10 苏红[综合楼402]<br/><br/>[11-11周] 2026-11-17 苏红[综合楼402]<br/><br/>...",
      "z3": "", "z4": "", "z5": "", "z6": "", "z7": "",
      "selected": true
    }
  ]
}
```

**一行 = 一个节次**（不是一节课）。`z1..z7` 对应周日..周六，`bj1..bj7` 是各格的课程 ID（为 null 表示没课）。

实测 15 行（14 个节次 + 1 个表头行）。

### 2.3 【关键】格子内容是 HTML 字符串

一个格子里可能有**多门课**，格式固定：

```
课程名<br/>[周次] yyyy-MM-dd 教师[教室]<br/><br/>[周次] yyyy-MM-dd 教师[教室]<br/><br/>...
```

拆解规则：

1. 按 `<br/><br/>` 切开 → 每段是一节课的「[周次] 日期 教师[教室]」
2. 第一段 `<br/>` 之前是**课程名**
3. 每段正则：`\[(\d+)-(\d+)周\]\s*(\d{4}-\d{2}-\d{2})\s*(\S+?)\[(.+?)\]`

**注意**：一格里有多个 `bj` 非 null 时，说明这一格塞了多门不同的课（如 `bj2` 和 `bj5` 都有值），
对应 `z2` 和 `z5` 里其实是同一段 HTML —— 数据冗余，去重即可。

### 2.4 学期码

```
GET /gmis5/student/default/bindterm
→ [{ termcode: "48", termname: "2026-2027秋学期", selected: true }, ... ]
```

31 个学期，`termcode` 是递增整数（48 = 2026-2027 秋），**不是**本科那种 `202601` 格式。

### 2.5 节次表

```
GET /gmis5/student/pygl/xkkbcx_bindLoad
→ { jclist: [{ jcbh: 1, id: 11, jcmc: "上午1" }, ... ] }
```

14 节，**连续编号**：

| 节次 | 名称 | 时间（从 `jzsj` 拿） |
|---|---|---|
| 1-4 | 上午1-4 | 08:30 起，每节 40 + 课间 10 |
| 5-10 | 下午5-10 | 14:00 起 |
| 11-14 | 晚上11-14 | 19:00 起 |

**与本科完全不同**：本科是 11 节且**跳号**（没有 5、10、11），每节 45 + 5 分钟。

---

## 三、成绩接口（已跑通，但无数据）

```
GET /gmis5/student/pygl/xscjcx_list
X-Requested-With: XMLHttpRequest
```

返回：

```json
{
  "xwklist": [],      // 学位课
  "fxwklist": [],     // 非学位课
  "xftj": [{
    "xh": "20261120283",
    "xwxf": 0, "fxwxf": 0, "sjxf": 0, "zxf": 0,
    "yxxwxf": 19, "yxfxwxf": 19, "yxzxf": 19
  }]
}
```

**当前两个列表都是空的** —— 2026 级新生还没成绩。但学分统计已有：已修 19 学分。

字段含义推断（待有数据后验证）：

- `xwxf` 学位课学分 / `fxwxf` 非学位课学分
- `yxxwxf` 已修学位课学分 / `yxfxwxf` 已修非学位课学分 / `yxzxf` 已修总学分

**这是研究生特有的口径**：分「学位课 / 非学位课」，不是本科的「必修 / 选修 / 限选」。

---

## 四、与现有模型的对齐

### 4.1 能直接映射的

| 我们的模型 | 研究生来源 | 处理 |
|---|---|---|
| `name` | `z*` 里第一个 `<br/>` 之前 | 直接 |
| `week` | `[N-N周]` 的第一个 N | 直接 |
| `weekday` | `z1..z7` 的**下标** | `index + 1` |
| `startUnit` / `endUnit` | 行号 `jcbh` | 直接（但要换节次表） |
| `date` | 正则里的 `yyyy-MM-dd` | 直接 |
| `teachers` | 正则里的教师名 | 直接 |
| `room` | 方括号里的教室 | 直接 |

### 4.2 对不上的

| 项 | 差异 | 处理 |
|---|---|---|
| 节次表 | 14 节连续 vs 11 节跳号 | **按学段切换节次表** |
| 单节时长 | 40 分钟 vs 45 分钟 | 同上 |
| 学期码 | 整数 48 vs `202601` | 各用各的，不统一 |
| 成绩分类 | 学位课/非学位课 vs 必修/选修 | 各用各的口径 |
| 周次对齐 | **研究生直接给真实日期**，不存在本科那个「教务周从周日算」的坑 | 不用特殊处理 |
| `content`（上课内容） | 研究生接口**没有** | 留空 |
| `schoolColor` | 研究生接口**没有** | 按课名派生 |

**好消息**：研究生接口直接给日期，本科那个踩了很久的「周次错位」问题在研究生这边不存在。

---

## 五、实施方案

### 5.1 数据模型改动

`Models.kt` 加一个字段，标记这条数据来自哪个系统：

```kotlin
enum class Campus { UNDERGRAD, GRADUATE }

data class CourseSession(
    // ... 现有字段不动
    val campus: Campus = Campus.UNDERGRAD,   // 新增，给默认值兼容老缓存
)
```

**必须放最后并给默认值** —— 老缓存反序列化不能炸（这是上次 `classify` 字段踩过的教训）。

### 5.2 新增文件

```
core/
  Campus.kt          学段枚举 + 节次表定义（两套）
data/
  Yjs.kt             研究生系统客户端（解析 HTML 字符串）
```

`Campus.kt` 里要放两套节次表：

```kotlin
/** 本科：11 节，跳号，45+5 分钟 */
val UNDERGRAD_SLOTS = listOf(
    Slot(1, "08:30"), Slot(2, "09:20"), Slot(3, "10:20"), Slot(4, "11:10"),
    Slot(6, "14:00"), Slot(7, "14:50"), Slot(8, "15:40"), Slot(9, "16:30"),
    Slot(12, "19:00"), Slot(13, "19:50"), Slot(14, "20:40"),
)

/** 研究生：14 节，连续，40+10 分钟 */
val GRADUATE_SLOTS = listOf(
    Slot(1, "08:30"), Slot(2, "09:20"), Slot(3, "10:10"), Slot(4, "11:00"),
    Slot(5, "14:00"), ... // 待实测确认每节时间
)
```

> ⚠️ 研究生各节的准确时间要从 `jzsj` 字段实测拿，不能推算。

### 5.3 `Yjs.kt` 核心解析

```kotlin
/**
 * 解析一格 HTML。
 *
 * 格式：课程名<br/>[周次] 日期 教师[教室]<br/><br/>[周次] 日期 教师[教室]...
 *
 * 一个格子可能有多个 bj 非空（同格多门课），调用方去重。
 */
internal fun parseCell(html: String, weekday: Int, jcbh: Int): List<CourseSession> {
    if (html.isBlank()) return emptyList()
    val parts = html.split("<br/><br/>")
    // 第一段里第一个 <br/> 之前是课名
    val name = parts.firstOrNull()?.substringBefore("<br/>")?.trim() ?: return emptyList()
    if (name.isBlank()) return emptyList()

    val re = Regex("""\[(\d+)-(\d+)周]\s*(\d{4}-\d{2}-\d{2})\s+(\S+?)\[(.+?)]""")
    return parts.mapNotNull { seg ->
        val m = re.find(seg) ?: return@mapNotNull null
        CourseSession(
            name = name,
            week = m.groupValues[1].toIntOrNull() ?: return@mapNotNull null,
            weekday = weekday,
            startUnit = jcbh,
            endUnit = jcbh,     // 研究生一行就是一个节次，不跨节
            date = m.groupValues[3],
            teachers = m.groupValues[4],
            room = m.groupValues[5],
            campus = Campus.GRADUATE,
            // ... 其余字段给默认值
        )
    }
}
```

### 5.4 登录流程改动

现在 `LoginScreen` 是一段 WebView。改成：

```kotlin
enum class Stage { Jw, Ecard, Graduate }

// 本科：Jw → Ecard（现有逻辑不动）
// 研究生：直接打开 https://yjsglxt.gzucm.edu.cn/gmis5/ ，登录成功后抓 Cookie
```

判定「登录成功」：URL 落到 `/gmis5/student/default/index` 或 `/gmis5/student/pygl/*`。

### 5.5 存储改动

`SessionStore` 加：

```kotlin
var campus: String?          // "undergrad" / "graduate"
fun saveCookies(host, ...)   // 已经按 host 存，研究生域名自然分开
```

Cookie 那块**不用改** —— `PersistentCookieJar` 本来就是按 host 分的。

### 5.6 UI 改动

| 位置 | 改动 |
|---|---|
| 设置页 | 加「学段」选择项（本科 / 研究生） |
| 课表页 | 节次列按学段取不同表；周次条按学期总周数 |
| 成绩页 | 研究生按「学位课 / 非学位课」分组显示 |
| 今日页 | 节次换算按学段 |

---

## 六、工作量与顺序

| 阶段 | 内容 | 预估 |
|---|---|---|
| 1 | `Campus.kt` + `Yjs.kt`（解析层 + 单测） | 核心，最该先做 |
| 2 | 登录流程加研究生分支 | 比本科简单（无 CAS 跳转） |
| 3 | 设置页加学段选择 + 存储 | 小 |
| 4 | 课表页节次表切换 | 中（要验证布局不崩） |
| 5 | 成绩页研究生口径 | 中（**没数据，只能按字段名推断**） |

---

## 七、需要你确认的问题

1. **研究生各节的准确时间** —— 我只能从 `jzsj` 拿到已排课的节次时间，
   没有课的节次（比如第 5、6 节）拿不到。要么你查一下作息表，要么我按 40+10 推算后你校核。

2. **成绩口径** —— 现在没数据，`xwklist` / `fxwklist` 的字段名都是猜的。
   等你有成绩了我再抓一次确认。

3. **周次上限** —— 研究生学期多少周？本科是 19 周。研究生课表里见过第 16 周，可能更多。

4. **要不要保留本科那套** —— 如果 App 主要给研究生用，本科那套可以藏着；反之亦然。
   手动选学段意味着两套都要维护。

---

## 八、风险

| 风险 | 说明 |
|---|---|
| 节次时间算错 | 直接影响「下一节课」和上课提醒，必须实测 |
| 成绩字段猜错 | 没数据验证，可能上线后才发现口径不对 |
| HTML 解析脆弱 | 学校改版式就崩，需要写宽松的正则 + 失败兜底 |
| 两套 UI 都要维护 | 节次表、成绩口径、学期码格式全不同 |
| 登录体验变差 | 研究生用户要登两次（本科教务 + 研究生系统），如果他也用本科 |

---

## 九、已完成的验证

- ✅ 课表接口 `py_kbcx_ew1` 跑通，15 行数据完整
- ✅ 学期列表 `bindterm` 跑通，31 个学期
- ✅ 节次表 `xkkbcx_bindLoad` 跑通，14 节
- ✅ 成绩接口 `xscjcx_list` 跑通（结构清楚，数据为空）
- ✅ 确认两套系统无单点登录
- ⬜ 研究生各节准确时间（待确认）
- ⬜ 成绩字段真实含义（待有数据）
- ⬜ 学期总周数（待确认）

---

## 十、临时探针已清理

调研用的 `.js` 脚本全部删除，临时打开的浏览器标签页已关闭。