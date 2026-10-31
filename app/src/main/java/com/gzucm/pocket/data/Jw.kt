package com.gzucm.pocket.data

import com.gzucm.pocket.core.CourseGrade
import com.gzucm.pocket.core.CourseSession
import com.gzucm.pocket.core.ExamItem
import com.gzucm.pocket.core.FitnessGrade
import com.gzucm.pocket.core.FitnessItem
import com.gzucm.pocket.core.LevelGrade
import com.gzucm.pocket.core.Net
import com.gzucm.pocket.core.NetError
import com.gzucm.pocket.core.Semester
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.LocalDate

/**
 * 广中医教务系统（jw.gzucm.edu.cn）客户端。
 *
 * 全部接口都用 JSESSIONID 会话鉴权，POST 表单 + X-Requested-With。
 * 字段名以 2026-10-28 实测为准，详见 gzucm-probe 里的规格文档。
 */
object Jw {

    const val BASE = "https://jw.gzucm.edu.cn"

    // ── 学期 ─────────────────────────────────────────────────────────────

    /** 按当前日期推断学期代码：8 月及以后算当年第一学期 */
    fun guessSemesterCode(today: LocalDate = LocalDate.now()): String =
        if (today.monthValue >= 8) "${today.year}01" else "${today.year - 1}02"

    /** 上一个学期代码（秋季→上一年春季，春季→上一年秋季） */
    fun previousSemesterCode(code: String): String {
        val year = code.take(4).toIntOrNull() ?: return code
        return if (code.endsWith("01")) "${year - 1}02" else "${year}01"
    }

    /**
     * 教学校历：给出第 1 周的真实日期。
     * `POST /new/xlxx/data` body `xnxqdm=202601`
     */
    fun fetchSemester(code: String): Semester? {
        val text = Net.postForm(
            "$BASE/new/xlxx/data",
            mapOf("xnxqdm" to code),
            referer = "$BASE/new/welcome.page?ui=new",
        )
        val root = runCatching { Net.json.parseToJsonElement(text).jsonObject }
            .getOrElse { throw NetError.Parse("校历响应") }

        var firstMonday: LocalDate? = null
        var maxWeek = 0
        // 用安全转换：data 可能是字符串/数字（鉴权失败的响应就是），
        // 直接 .jsonArray 会抛 IllegalArgumentException
        val groups = root["data"] as? kotlinx.serialization.json.JsonArray ?: return null
        for (group in groups) {
            for (rowEl in (group as? kotlinx.serialization.json.JsonArray).orEmpty()) {
                val row = (rowEl as? kotlinx.serialization.json.JsonArray).orEmpty()
                if (row.isEmpty()) continue
                val head = row[0].jsonObject
                if (head.str("type") != "zc") continue
                val week = head.str("value").toIntOrNull() ?: continue
                if (week > maxWeek) maxWeek = week
                if (week == 1 && firstMonday == null) {
                    val days = row.drop(1).mapNotNull { cell ->
                        val o = cell.jsonObject
                        if (o.str("type") == "day") o.str("fullText") else null
                    }
                    if (days.isNotEmpty()) {
                        // 表头从「星期日」开始，所以第一天是周日
                        firstMonday = runCatching { LocalDate.parse(days.first()).plusDays(1) }.getOrNull()
                    }
                }
            }
        }
        val start = firstMonday ?: return null
        return Semester.of(
            code = code,
            name = semesterName(code),
            firstMonday = start,
            // 周数以教务周历实际给出的为准，不硬造。
            // 周历解析不出周次（maxWeek<=0）视为数据异常，宁可报错也别猜。
            totalWeeks = if (maxWeek <= 0) return null else maxWeek.coerceIn(10, 30),
        )
    }

    fun semesterName(code: String): String {
        val year = code.take(4).toIntOrNull() ?: return code
        return if (code.endsWith("01")) "$year-${year + 1} 学年 第一学期" else "${year - 1}-$year 学年 第二学期"
    }

    // ── 课表 ─────────────────────────────────────────────────────────────

    /**
     * 整学期课表。
     * `POST /new/student/xsgrkb/getCalendarWeekDatas`，一次拿全，本地按周切。
     */
    fun fetchSessions(semester: Semester): List<CourseSession> {
        val d1 = semester.firstMonday.minusDays(1)
        val d2 = d1.plusDays((semester.totalWeeks + 2) * 7L)
        val text = Net.postForm(
            "$BASE/new/student/xsgrkb/getCalendarWeekDatas",
            mapOf(
                "xnxqdm" to semester.code,
                "zc" to "",
                "d1" to "$d1 00:00:00",
                "d2" to "$d2 00:00:00",
            ),
            referer = "$BASE/new/student/xsgrkb/main.page",
        )
        val root = runCatching { Net.json.parseToJsonElement(text).jsonObject }
            .getOrElse { throw NetError.Parse("课表响应") }
        val arr = root["data"]?.jsonArray ?: return emptyList()

        val tokenRe = Regex("^(.*)-(\\d{1,2})$")
        val out = ArrayList<CourseSession>(arr.size * 3)

        for (el in arr) {
            val o = el.jsonObject
            val name = o.str("kcmc")
            if (name.isBlank()) continue
            val weekday = o.str("xq").toIntOrNull() ?: continue
            if (weekday !in 1..7) continue

            val startUnit = o.str("ps").toIntOrNull() ?: 0
            val endUnit = (o.str("pe").toIntOrNull() ?: startUnit).coerceAtLeast(startUnit)
            val mainRoom = o.roomName()

            /*
             * 逐周教室：`jxcdmc2` 形如「场地A-3,场地B-4」。
             * 主字段 jxcdmc 有值时以它为准，避免逐周串被误当主场地。
             */
            val c2 = o.str("jxcdmc2").trim()
            val pairs: List<Pair<Int, String>> = if (c2.isNotBlank()) {
                c2.split(",").mapNotNull { tok ->
                    val t = tok.trim()
                    if (t.isEmpty()) return@mapNotNull null
                    val m = tokenRe.find(t)
                    if (m == null) null
                    else {
                        val week = m.groupValues[2].toIntOrNull() ?: return@mapNotNull null
                        val room = if (mainRoom.isNotBlank()) mainRoom
                        else m.groupValues[1].trim()
                        week to room
                    }
                }
            } else {
                o.str("zc").split(",").mapNotNull { w ->
                    val zcWeek = w.trim().toIntOrNull() ?: return@mapNotNull null
                    /*
                     * 总表没有 qsrq，只能用 zc 换算，换算规则同上：
                     * 教务的周是周日~周六，App 的是周一~周日 ——
                     * 教务第 N 周的**周日**在 App 里属于第 N-1 周
                     * （比如教务第 8 周的周日 10-25，是 App 第 7 周的周日）。
                     * 周日且 N=1 时算出来是第 0 周，说明那天在学期开始之前，
                     * 直接丢掉（return@mapNotNull null）。
                     */
                    val appWeek = if (weekday == 7) zcWeek - 1 else zcWeek
                    if (appWeek < 1) return@mapNotNull null
                    appWeek to mainRoom
                }
            }
            if (pairs.isEmpty()) continue

            for ((week, room) in pairs) {
                out += CourseSession(
                    name = name,
                    code = o.str("kcdm"),
                    teachers = o.str("teaxms"),
                    week = week,
                    weekday = weekday,
                    startUnit = startUnit,
                    endUnit = endUnit,
                    startTime = o.str("qssj").take(5),
                    endTime = o.str("jssj").take(5),
                    room = room,
                    category = o.str("jxhjmc"),
                    teachingMode = o.str("jxmsmc"),
                    mergedClasses = o.str("jxbmc"),
                    studyHours = o.str("xs"),
                    courseSeq = o.str("kcbh"),
                    schoolColor = o.str("bgcolor"),
                )
            }
        }
        return out
    }

    /**
     * 按周查询课表：同一接口把 `zc` 传成具体周次。
     *
     * 与整学期总表（`zc` 留空）相比，**行数完全一致**，但多出三个字段：
     *   - `sknrjj` 上课内容（如「性早熟」「王泰林」）—— 总表没有
     *   - `qsrq` / `jsrq` 这一节课的真实日期 —— 总表没有
     * 另外总表的 `jxcdmc2`（逐周教室）在这里不存在，因为每周单独查，
     * `jxcdmc` 就是本周的教室。
     */
    fun fetchWeek(semester: Semester, week: Int): List<CourseSession> {
        val d1 = semester.firstMonday.minusDays(1)
        val d2 = d1.plusDays((semester.totalWeeks + 2) * 7L)
        val text = Net.postForm(
            "$BASE/new/student/xsgrkb/getCalendarWeekDatas",
            mapOf(
                "xnxqdm" to semester.code,
                "zc" to "$week",
                "d1" to "$d1 00:00:00",
                "d2" to "$d2 00:00:00",
            ),
            referer = "$BASE/new/student/xsgrkb/main.page",
        )
        return parseWeekly(text, week, semester)
    }

    /**
     * 解析按周查询的响应。每行就是一次具体的课，不需要再展开周次串。
     *
     * **[semester] 必须传**：周次不能直接信接口传进去的 `zc`。
     *
     * 踩过的坑（用户反馈「周日的课对不上日期」）：
     * 教务的「第 N 周」是**周日 ~ 周六**，App 的「第 N 周」是**周一 ~ 周日**。
     * 传 `zc=8` 拿回来的是 10-25(周日)~10-31(周六)，
     * 如果全标成 week=8，App 日历里第 8 周却是 10-26~11-01 ——
     * 于是「第 8 周周日」列上挂着 10-25 的课，日期和周次互相矛盾。
     *
     * 现在改用每行的 `qsrq`（教务给的真实日期）反推周次，
     * 周次和日期就永远自洽：10-25 会落到第 7 周，11-01 落到第 8 周。
     */
    internal fun parseWeekly(
        text: String,
        fallbackWeek: Int,
        semester: Semester? = null,
    ): List<CourseSession> {
        val root = runCatching { Net.json.parseToJsonElement(text).jsonObject }
            .getOrElse { return emptyList() }
        val arr = root["data"]?.jsonArray ?: return emptyList()

        val out = ArrayList<CourseSession>(arr.size)
        for (el in arr) {
            val o = runCatching { el.jsonObject }.getOrNull() ?: continue
            val name = o.str("kcmc")
            if (name.isBlank()) continue
            val weekday = o.str("xq").toIntOrNull() ?: continue
            if (weekday !in 1..7) continue

            val startUnit = o.str("ps").toIntOrNull() ?: 0
            val endUnit = (o.str("pe").toIntOrNull() ?: startUnit).coerceAtLeast(startUnit)

            /*
             * 周次：优先用本行真实日期反推（见上面注释），
             * 拿不到日期时才退回接口参数。
             */
            val realDate = o.str("qsrq").take(10)
            val weekFromDate = if (semester != null && realDate.length == 10) {
                runCatching { semester.weekOf(java.time.LocalDate.parse(realDate)) }
                    .getOrNull()
                    ?.takeIf { it in 1..semester.totalWeeks }
            } else {
                null
            }
            // 正常就是单周；万一服务端返回多周，逐周展开
            val weeks = if (weekFromDate != null) {
                listOf(weekFromDate)
            } else {
                o.str("zc").split(",")
                    .mapNotNull { it.trim().toIntOrNull() }
                    .ifEmpty { listOf(fallbackWeek) }
            }

            for (w in weeks) {
                out += CourseSession(
                    name = name,
                    code = o.str("kcdm"),
                    teachers = o.str("teaxms"),
                    week = w,
                    weekday = weekday,
                    startUnit = startUnit,
                    endUnit = endUnit,
                    startTime = o.str("qssj").take(5),
                    endTime = o.str("jssj").take(5),
                    room = o.roomName(week = w),
                    category = o.str("jxhjmc"),
                    teachingMode = o.str("jxmsmc"),
                    mergedClasses = o.str("jxbmc"),
                    studyHours = o.str("xs"),
                    courseSeq = o.str("kcbh"),
                    schoolColor = o.str("bgcolor"),
                    content = o.str("sknrjj"),
                    date = o.str("qsrq").take(10),
                    teachLink = o.str("jxhjmc"),
                    teachForm = o.str("jxxsmc"),
                )
            }
        }
        return out.sortedWith(compareBy({ it.week }, { it.weekday }, { it.startUnit }))
    }

    /**
     * 抓取整学期**逐周明细**：对 1..totalWeeks 逐周请求，并发上限 5。
     *
     * 为什么不用一次总表：总表没有上课内容和真实日期。
     * 兜底：如果全部周都失败（比如接口变了），退回总表，至少还有课表可看。
     */
    suspend fun fetchDetailedSessions(semester: Semester): List<CourseSession> = coroutineScope {
        val total = semester.totalWeeks.coerceIn(1, 30)
        val gate = Semaphore(5)

        val perWeek = (1..total).map { w ->
            async(Dispatchers.IO) {
                gate.withPermit {
                    runCatching { fetchWeek(semester, w) }
                        .getOrElse { emptyList() }
                }
            }
        }.awaitAll()

        val merged = perWeek.flatten()
        if (merged.isEmpty()) {
            // 全挂 → 退回总表
            withContext(Dispatchers.IO) { fetchSessions(semester) }
        } else {
            merged.sortedWith(compareBy({ it.week }, { it.weekday }, { it.startUnit }))
        }
    }

    /**
     * 先抓指定周（用于首屏），再并发补齐其余周。
     *
     * 为什么要拆：全量 20 周在好网络下只要 0.2 秒，但弱网时每次往返可能 1 秒以上，
     * 等全部回来才渲染就是几十秒白屏。先给当前周，用户 1 次往返就能看到课表。
     *
     * @param onEach 每完成一周回调一次（传入累计完成数），用于进度显示
     */
    suspend fun fetchWeeksExcept(
        semester: Semester,
        exclude: Int,
        onEach: (done: Int) -> Unit = {},
    ): List<CourseSession> = coroutineScope {
        /*
         * 范围要包到 totalWeeks + 1。
         *
         * 教务的周是周日~周六，App 的是周一~周日 ——
         * App 第 totalWeeks 周的**周日**落在教务第 totalWeeks+1 周里，
         * 只抓到 totalWeeks 就会漏掉学期最后一天（周日）的课。
         * 多抓一周的代价只是一次请求，漏一天课的代价是用户找不到课。
         */
        val lastWeek = semester.totalWeeks + 1
        val weeks = (1..lastWeek).filter { it != exclude }
        if (weeks.isEmpty()) return@coroutineScope emptyList()
        val gate = Semaphore(5)
        val counter = java.util.concurrent.atomic.AtomicInteger(0)
        weeks.map { w ->
            async(Dispatchers.IO) {
                gate.withPermit {
                    val r = runCatching { fetchWeek(semester, w) }.getOrElse { emptyList() }
                    onEach(counter.incrementAndGet())
                    r
                }
            }
        }.awaitAll().flatten()
    }

    // ── 成绩 ─────────────────────────────────────────────────────────────
    /** 课程成绩：`POST /new/student/xskccj/kccjDatas` */
    fun fetchCourseGrades(): List<CourseGrade> {
        val text = Net.postForm(
            "$BASE/new/student/xskccj/kccjDatas",
            mapOf(
                "xnxqdm" to "",
                "source" to "kccjlist",
                "ismax" to "1",
                "page" to "1",
                "rows" to "500",
                "sort" to "xnxqdm,kcmc",
                "order" to "desc,asc",
            ),
            referer = "$BASE/new/student/xskccj/main.page",
        )
        val root = runCatching { Net.json.parseToJsonElement(text).jsonObject }
            .getOrElse { throw NetError.Parse("成绩响应") }
        return root["rows"]?.jsonArray?.mapNotNull { el ->
            val o = el.jsonObject
            val name = o.str("kcmc")
            if (name.isBlank()) return@mapNotNull null
            val score = o.str("zcjfs").toDoubleOrNull() ?: return@mapNotNull null
            CourseGrade(
                term = o.str("xnxqmc"),
                name = name,
                score = score,
                gpa = o.str("cjjd").toDoubleOrNull() ?: 0.0,
                credit = o.str("xf").toDoubleOrNull() ?: 0.0,
                category = o.str("kcdlmc"),
                nature = o.str("ksxzmc"),
                remark = o.str("bz"),
                passed = score >= 60,
            )
        } ?: emptyList()
    }

    /** 考级成绩：`POST /new/student/xskjcj/datas` */
    fun fetchLevelGrades(): List<LevelGrade> {
        val text = Net.postForm(
            "$BASE/new/student/xskjcj/datas",
            mapOf("page" to "1", "rows" to "100", "sort" to "xnxqdm", "order" to "asc"),
            referer = "$BASE/new/student/xskjcj/xskjcjlist.page",
        )
        val root = runCatching { Net.json.parseToJsonElement(text).jsonObject }
            .getOrElse { throw NetError.Parse("考级响应") }
        return root["rows"]?.jsonArray?.mapNotNull { el ->
            val o = el.jsonObject
            val name = o.str("kjkcmc")
            if (name.isBlank()) return@mapNotNull null
            LevelGrade(
                name = name,
                term = o.str("xnxqmc"),
                total = o.str("zcj").toDoubleOrNull() ?: 0.0,
                level = o.str("djmc"),
                date = o.str("kssj"),
                parts = buildList {
                    val labels = listOf("听力", "阅读", "写作", "翻译", "其他")
                    val keys = listOf("xm1cj", "xm2cj", "xm3cj", "xm4cj", "xm5cj")
                    keys.forEachIndexed { i, k ->
                        val v = o.str(k)
                        if (v.isNotBlank() && v != "0.0") add(labels[i] to v)
                    }
                },
            )
        } ?: emptyList()
    }

    /** 体测成绩：`POST /new/student/xstccj/datas`（只取成绩，丢弃身份证/电话等） */
    fun fetchFitness(): List<FitnessGrade> {
        val text = Net.postForm(
            "$BASE/new/student/xstccj/datas",
            mapOf("page" to "1", "rows" to "100", "sort" to "xnxqdm", "order" to "asc"),
            referer = "$BASE/new/student/xstccj",
        )
        val root = runCatching { Net.json.parseToJsonElement(text).jsonObject }
            .getOrElse { throw NetError.Parse("体测响应") }
        return root["rows"]?.jsonArray?.map { el ->
            val o = el.jsonObject
            val items = listOf(
                Triple("身高", "sg", ""),
                Triple("体重", "tz", "tzpf"),
                Triple("肺活量", "fhl", "fhlpf"),
                Triple("50 米", "wsm", "wsmpf"),
                Triple("坐位体前屈", "zwtqq", "zwtqqpf"),
                Triple("立定跳远", "ldty", "ldtypf"),
                Triple("引体/仰卧", "ytxsorywqz", "ytxsorywqzpf"),
                Triple("800/1000 米", "yqmorbbm", "yqmorbbmpf"),
            ).mapNotNull { (label, vk, sk) ->
                val v = o.str(vk)
                if (v.isBlank()) return@mapNotNull null
                FitnessItem(label, v, o.str(sk), "")
            }
            FitnessGrade(
                term = o.str("xnxqmc"),
                total = o.str("zf"),
                level = o.str("dj"),
                items = items,
            )
        } ?: emptyList()
    }

    /** 考试安排：`POST /new/student/xsksrw/paginateXsksrw` */
    fun fetchExams(semesterCode: String): List<ExamItem> {
        val text = Net.postForm(
            "$BASE/new/student/xsksrw/paginateXsksrw",
            mapOf(
                "xnxqdm" to semesterCode,
                "page" to "1",
                "rows" to "60",
                "sort" to "zc,xq,jcdm2",
                "order" to "asc",
            ),
            referer = "$BASE/new/student/xsksrw/list.page",
        )
        val root = runCatching { Net.json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return emptyList()
        return root["rows"]?.jsonArray?.map { el ->
            val o = el.jsonObject
            ExamItem(
                course = o.str("kcmc"),
                date = o.str("rq").ifBlank { o.str("ksrq") },
                time = o.str("kssj").ifBlank { o.str("jcdm2") },
                room = o.str("cdmc").ifBlank { o.str("jxcdmc") },
                seat = o.str("zwh"),
                type = o.str("ksxzmc").ifBlank { o.str("kslxmc") },
            )
        } ?: emptyList()
    }

    /** 会话是否还活着（课表接口打一次，能解析出 code=0 就算活着） */
    fun ping(): Boolean = runCatching {
        val text = Net.postForm(
            "$BASE/new/student/xsgrkb/getCalendarWeekDatas",
            mapOf("xnxqdm" to "", "zc" to "", "d1" to "", "d2" to ""),
            referer = "$BASE/new/student/xsgrkb/main.page",
        )
        val root = Net.json.parseToJsonElement(text).jsonObject
        root.str("code") == "0"
    }.getOrDefault(false)
}

/**
 * 解析「教学场地」。
 *
 * 只取 `jxcdmc` 是不够的 —— 实际见过三种情况：
 *   ① `jxcdmc` 是空字符串（教务没配教室，线上课/场地待定）；
 *   ② 值里只有全角空格或零宽字符，看着有内容其实是空的，
 *      这种在教务网页上会渲染成一个方框，`isBlank()` 又判 true，
 *      于是详情页整行「地点」都不显示，用户以为 App 读丢了；
 *   ③ 场地放在 `jxcdmc2`（逐周教室，格式「场地-周次」）或 `cdmc` 里。
 *
 * 所以这里按优先级依次回退，并且**统一 trim**。
 * `str()` 本身不 trim，直接拿去判断会把「　」当成有值。
 */
internal fun JsonObject.roomName(week: Int? = null): String {
    val main = str("jxcdmc").trim()
    if (main.isNotBlank()) return main

    // 逐周教室：「场地A-3,场地B-4」，取匹配当前周的那条；没有周次信息就取第一段
    val weekly = str("jxcdmc2").trim()
    if (weekly.isNotBlank()) {
        val tokens = weekly.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        if (week != null) {
            val hit = tokens.firstOrNull { it.endsWith("-$week") }
            if (hit != null) return hit.substringBeforeLast("-").trim()
        }
        return tokens.firstOrNull()?.substringBeforeLast("-")?.trim().orEmpty()
    }

    return str("cdmc").trim()
}

/** 取字符串字段，兼容 JSON 里数字被写成 number 的情况 */
internal fun JsonObject.str(key: String): String {
    val el = this[key] ?: return ""
    return runCatching { el.jsonPrimitive.content }.getOrDefault("")
}
