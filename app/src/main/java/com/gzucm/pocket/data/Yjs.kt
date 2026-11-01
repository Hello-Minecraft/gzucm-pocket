package com.gzucm.pocket.data

import com.gzucm.pocket.core.Campus
import com.gzucm.pocket.core.CourseGrade
import com.gzucm.pocket.core.CourseSession
import com.gzucm.pocket.core.Net
import com.gzucm.pocket.core.NetError
import com.gzucm.pocket.core.Semester
import com.gzucm.pocket.core.SlotEndTimes
import com.gzucm.pocket.core.SlotTimes
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import java.time.LocalDate

/**
 * 广中医研究生系统（yjsglxt.gzucm.edu.cn）客户端。
 *
 * 和本科那套（`Jw.kt`）几乎没有任何相似之处：
 *   · 技术栈是 ASP.NET MVC + jQuery EasyUI，路径前缀 `/gmis5/`
 *   · 认证是独立登录页，**没有 CAS 单点**，和本科 Cookie 互不通用
 *   · 课表接口一次返回整学期，但**每个格子是一段 HTML 字符串**，要自己拆
 *   · 节次 14 节连续（本科 11 节跳号），每节 40 分钟（本科 45）
 *   · 学期码是整数（48），不是 `202601`
 *
 * ## 关于周次
 *
 * 研究生接口**直接给每节课的真实日期**（`2026-11-10`），
 * 所以本科那个「教务周从周日算、App 从周一算」的错位问题在这里**不存在**。
 * 周次直接从方括号 `[10-10周]` 里取，不需要反推。
 */
object Yjs {

    const val BASE = "https://yjsglxt.gzucm.edu.cn"
    private const val PREFIX = "$BASE/gmis5"

    /**
     * 默认抓多少周。
     *
     * 教务的学期长度：实测课表里出现过第 20 周，本科那边是 19~20 周。
     * 多抓一周的代价只是多一次请求（32ms），少抓则可能整周课表都不见，
     * 所以取 20 保守一点。
     */
    const val DEFAULT_WEEKS = 20

    /**
     * 匹配一条「周次安排」：`[18-18周] 2027-01-07 高理文[医工楼309]`
     *
     * 这是拆分一格多门课的**核心识别特征** ——
     * 能匹配上的是周次行，匹配不上的是课名行。
     *
     * 各段的含义：
     *   · `\[(\d+)-(\d+)周]`  起止周（第 1 个捕获组当周次用）
     *   · `(\d{4}-\d{2}-\d{2})` 实际上课日期
     *   · `(\S+?)`            教师姓名（不跨空白，长度自适应）
     *   · `\[(.+?)]`          教室
     *
     * 用 `\s*` 而不是 `\s+`：有些记录是 `[7-7周]2026-10-22`（周次和日期之间
     * 没有空格），用 `\s+` 会整条漏掉。
     */
    private val WEEK_ENTRY =
        Regex("""\[(\d+)-(\d+)周]\s*(\d{4}-\d{2}-\d{2})\s+(\S+?)\[(.+?)]""")

    // ── 学期 ─────────────────────────────────────────────────────────

    /**
     * 学期列表：`GET /gmis5/student/default/bindterm`
     *
     * 返回 31 个学期，`termcode` 是递增整数：
     * 48 = 2026-2027 秋，47 = 2025-2026 春，依此类推。
     */
    fun fetchTerms(): List<YjsTerm> {
        val text = Net.get("$PREFIX/student/default/bindterm", referer = "$PREFIX/student/default/index")
        val root = runCatching { Net.json.parseToJsonElement(text) }.getOrNull()
        val arr = root as? JsonArray ?: return emptyList()
        return arr.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val code = o.str("termcode")
            val name = o.str("termname").trim()
            if (code.isBlank() || name.isBlank()) null
            else YjsTerm(code, name, o.bool("selected"))
        }
    }

    /**
     * 当前学期。
     *
     * 研究生系统的学期码是递增整数，**不能按日期推**（不像本科的 `202601`）。
     * 所以只能问服务端：`bindterm` 里 `selected=true` 的那个就是。
     * 拿不到就退回列表第一个（最新学期）。
     */
    fun fetchCurrentTerm(): YjsTerm? {
        val list = fetchTerms()
        return list.firstOrNull { it.selected } ?: list.firstOrNull()
    }

    /**
     * 把学期码翻译成 App 的 [Semester]。
     *
     * 研究生系统的校历接口没找到，`firstMonday` 只能靠**课表里出现的日期**反推：
     * 拿 firstName 日以前最早的那个周日当学期起点，再算周次。
     *
     * 更稳的做法是：从课表数据里找出「第 1 周」那一行，用它的日期。
     * 但如果第 1 周没有课，就得靠推算 —— 这里用后者兜底。
     */
    fun buildSemester(term: YjsTerm, sessions: List<CourseSession>): Semester {
        val firstMonday = inferFirstMonday(sessions)
        // 总周数：用出现过的最大周次，至少 20 周（研究生学期通常 20 周左右）
        val maxWeek = sessions.maxOfOrNull { it.week } ?: 0

        return Semester.of(
            code = term.code,
            name = term.name,
            firstMonday = firstMonday,
            totalWeeks = maxWeek.coerceAtLeast(20).coerceAtMost(30),
        )
    }

    /**
     * 反推学期第一周的周一。
     *
     * ⚠️ **不能拿「最早一节课的日期」往前推**（2026-11-01 致辉报的 bug）。
     *
     * 研究生第 1 周经常没课（比如这次的账号：第 1 周空白，第 2 周才开始上课）。
     * 用最早日期当第一周，整个学期就整体错后一周：
     * 「第 1 周」显示空，「第 2 周」显示的其实是第 1 周的课。
     *
     * 正确做法：**每节课自带日期和周次**，用它们反算第一周的周一。
     *
     * 对每个「第 N 周、星期 W、日期 D」的样本：
     *   该周的周一 = D - (W - 1)
     *   第一周的周一 = 该周周一 - (N - 1) * 7
     *
     * 所有样本算出来的第一周周一应该完全一致。取**出现次数最多**的那个，
     * 个别脏数据（日期写错）不会污染结果。
     *
     * 一个样本都没有时退回「今天」，至少不会崩。
     */
    internal fun inferFirstMonday(sessions: List<CourseSession>): LocalDate {
        val votes = HashMap<LocalDate, Int>(8)

        for (s in sessions) {
            if (s.week <= 0 || s.weekday !in 1..7) continue
            val d = s.date.takeIf { it.length == 10 }
                ?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
                ?: continue

            // 该日期所在周的周一，再往前推到第 1 周
            val mondayOfThatWeek = d.minusDays((d.dayOfWeek.value - 1).toLong())
            val candidate = mondayOfThatWeek.minusWeeks((s.week - 1).toLong())
            votes[candidate] = (votes[candidate] ?: 0) + 1
        }

        return votes.maxByOrNull { it.value }?.key ?: LocalDate.now()
    }

    // ── 课表 ─────────────────────────────────────────────────────────

    /**
     * 整学期课表：`POST /gmis5/student/pygl/py_kbcx_ew1`
     *
     * 参数：
     *   · `kblx`     = "xs"（学生课表）
     *   · `termcode` = 学期码
     *   · `skksz` / `skjsz` = 起止周（**两个给同一个值就是单周查询**）
     *   · `xzbj` / `jh` = 行政班级 / 集合，留空 = 本人
     *   · `jcid` 11..24 对应第 1..14 节，40 是「无节次」表头行
     *
     * **逐周抓，不再一次拿全学期**（致辉 2026-11-01 定）。
     *
     * 为什么改：全学期一次拿回来时，**同一格里混着 20 周的记录**，
     * 解析时必须从一堆 `[N-N周]` 里挑出当前周的，而学校的数据有残缺 ——
     * 比如「自然辨证法概论」某一门的周次行只出现在 6、7 节，
     * 8、9 节那一格缺了这条，于是拼块时 6-7 和 8-9 会断成两块。
     *
     * 逐周查的时候接口自己就筛过了（实测 `skksz=skjsz=7` 只返回第 7 周），
     * 一格基本就是「这一周这一节真正上的课」，缺行问题自然消失。
     *
     * 代价实测：全学期 69ms / 18.9KB，单周 32ms / 4.8KB，19 周约 600ms / 90KB。
     * 一次性同步，可接受。
     */
    fun fetchSessions(
        termCode: String,
        totalWeeks: Int = DEFAULT_WEEKS,
    ): List<CourseSession> {
        val all = ArrayList<CourseSession>(256)
        for (week in 1..totalWeeks) {
            all.addAll(fetchWeek(termCode, week))
        }
        // 跨周去重：同一门课同一节次在不同周可能出现完全相同的记录，
        // 这里按「课名+周次+星期+节次+教室+教师」去重
        val seen = HashSet<String>(all.size)
        return all.filter { s ->
            seen.add(
                "${s.name}|${s.week}|${s.weekday}|${s.startUnit}|${s.endUnit}|" +
                    "${s.room}|${s.teachers}",
            )
        }
    }

    /**
     * 抓某一周的课表。
     *
     * `skksz` 和 `skjsz` 传同一个值 = 只要这一周。
     */
    internal fun fetchWeek(termCode: String, week: Int): List<CourseSession> {
        val text = Net.postForm(
            "$PREFIX/student/pygl/py_kbcx_ew1",
            mapOf(
                "kblx" to "xs",
                "termcode" to termCode,
                "skksz" to week.toString(),
                "skjsz" to week.toString(),
                "xzbj" to "",
                "jh" to "",
            ),
            referer = "$PREFIX/student/pygl/xskbcx",
        )
        val root = runCatching { Net.json.parseToJsonElement(text).jsonObject }
            .getOrElse { throw NetError.Parse("研究生课表响应（第 $week 周）") }
        val rows = root["rows"]?.jsonArray ?: return emptyList()
        // 单周数据里的周次字段不可信（有的行还是写着别的周），
        // 以请求的周为准覆盖，避免混进相邻周
        return parseSessions(rows).map { it.copy(week = week) }
    }

    /**
     * 把接口的 `rows` 转成最终课表 —— **含合并连续节次**。
     *
     * 抽成单独函数是为了让测试能覆盖「解析 → 合并」的**完整链路**。
     *
     * 之前踩过一次：测试只调 [mergeConsecutive]，把 `fetchSessions` 里的合并
     * 调用删掉后测试依然全绿 —— 因为它压根没走那条路。
     * 现在测试测的是这个函数，删掉合并调用会立刻红。
     */
    internal fun parseSessions(rows: JsonArray): List<CourseSession> =
        mergeConsecutive(parseRows(rows))

    /**
     * 把「一课一节」合并成「一课一段」。
     *
     * 研究生接口是**一个节次一行**：一门占 4 节的课，`rows` 里就是 4 行，
     * 每行的同一列都挂着同样的课名和同样的 `[N-N周] 日期 教师[教室]`。
     * 直接拿去渲染，手机上看到的就是同一门课竖着叠成 4 张卡片
     * （2026-11-01 致辉报的「一节课被拆成好几节课」）。
     *
     * 学校自己的课表页是画成一个整块的，所以这里要还原回去。
     *
     * **合并条件必须全部相同**（少一个就会把两门课糊成一门）：
     *   周次、星期、课名、班级
     * 再加一条：节次**连续**。断开就不合并 —— 同一天同一门课
     * 上午一节、下午一节的排法不罕见（「1-2」+「6-7」），
     * 不判连续会合并出一个横跨 7 节、盖住中午的假课块。
     *
     * **教室和教师刻意不参与分组**（致辉 2026-11-01 定）。
     *
     * 原因：周五 6-9 节那格的「网络信息资源获取与管理」在同一格里挂了
     * **两条记录**，教师和教室都不同（曾召/医工楼308、柴雅倩/医工楼301）。
     * 那是同一门课分两组上，不该在课表上画成两个叠在一起的块。
     * 所以只按「课名 + 周次 + 星期 + 班级」分组，教师和教室在合并时
     * 并列保留（「曾召 / 柴雅倩」、「医工楼308 / 301」）。
     *
     * 代价：万一同一天同一门课真的换了教室（前两节 A 楼、后两节 B 楼），
     * 会被合成一块，教室显示成「A / B」。这种情况在研究生课表里
     * 比「分两组上」少见得多，接受这个取舍。
     *
     * 日期不参与分组：同一门课跨节次时每格里写的都是同一天，
     * 但万一学校漏写一格，参与分组会导致该节被拆出去。取第一段的即可。
     */
    internal fun mergeConsecutive(list: List<CourseSession>): List<CourseSession> {
        if (list.isEmpty()) return list

        val groups = list.groupBy {
            "${it.name}\u0001${it.week}\u0001${it.weekday}\u0001${it.mergedClasses}"
        }

        val out = ArrayList<CourseSession>(list.size)
        for (items in groups.values) {
            val sorted = items.sortedBy { it.startUnit }
            var cur: CourseSession? = null
            for (s in sorted) {
                cur = if (cur != null && s.startUnit <= cur.endUnit + 1) {
                    // 接上（相等也算：同一节重复出现时不要断成两块）
                    cur.copy(
                        endUnit = maxOf(cur.endUnit, s.endUnit),
                        endTime = if (s.endUnit >= cur.endUnit) s.endTime else cur.endTime,
                    )
                } else {
                    if (cur != null) out.add(cur)
                    s
                }
            }
            cur?.let { out.add(it) }
        }

        /*
         * 把并列的教师/教室写回。
         *
         * 单独一遍而不是在合并循环里做：合并过程中 cur 的 teachers/room
         * 一直取第一项，中途改会让后续判断失准。这里按同样的分组键
         * 重新算一次，直接覆盖。
         */
        val final = out.map { s ->
            val key = "${s.name}\u0001${s.week}\u0001${s.weekday}\u0001${s.mergedClasses}"
            val peers = groups[key].orEmpty()
            val t = peers.map { it.teachers }.filter { it.isNotBlank() }.distinct()
                .joinToString(" / ")
            val r = peers.map { it.room }.filter { it.isNotBlank() }.distinct()
                .joinToString(" / ")
            s.copy(
                teachers = t.ifBlank { s.teachers },
                room = r.ifBlank { s.room },
            )
        }

        // groupBy 会打乱顺序，排回来：先按星期，再按起始节次，最后按周次
        return final.sortedWith(compareBy({ it.weekday }, { it.startUnit }, { it.week }))
    }

    /**
     * 解析课表行。
     *
     * **一行 = 一个节次**，`z1..z7` 对应周日..周六。
     * 每格是一段 HTML：`课名<br/>[周次] 日期 教师[教室]<br/><br/>...`
     *
     * 同一格可能挂多门课（`bj2` 和 `bj5` 同时非空时，`z2`/`z5` 里其实是同一段内容），
     * 所以最后要按「课名 + 周次 + 日期」去重。
     */
    internal fun parseRows(rows: JsonArray): List<CourseSession> {
        val out = ArrayList<CourseSession>(64)
        val seen = HashSet<String>()

        for (el in rows) {
            val o = el as? JsonObject ?: continue
            // 节次号：jcid 是数据库 id（11,12...），jcbh 才是节次序号；两者都在时优先 jcbh
            val unit = o.intOrNull("jcbh") ?: o.intOrNull("jcid")?.let { it - 10 } ?: continue
            if (unit <= 0) continue

            for (day in 1..7) {
                val html = o.str("z$day")
                if (html.isBlank()) continue
                val list = parseCell(html, weekday = day, unit = unit)
                for (s in list) {
                    val key = "${s.name}|${s.week}|${s.weekday}|${s.startUnit}|${s.room}"
                    if (seen.add(key)) out.add(s)
                }
            }
        }
        return out
    }

    /**
     * 拆一格 HTML。
     *
     * ⚠️ **一格可能塞多门课** —— 这是学校系统自己的数据错误
     * （2026-11-01 致辉截图反馈：周四的制图课和英语课被塞进同一格）。
     *
     * 真实结构长这样：
     * ```
     * 2026级大学城硕士6-13班-科技论文配图设计与制作
     * <br/>[18-18周] 2027-01-07 高理文[医工楼309]
     * <br/>[19-19周] 2027-01-14 高理文[医工楼309]
     * <br/>2026级大学城硕士7、9、11班-英语（专业型硕士）    ← 第二门课的课名
     * <br/>[11-11周] 2026-11-19 李恩耀[综合楼402]
     * ```
     *
     * 旧实现取「第一个 `<br/>` 之前」当整格唯一的课名，于是英语的所有周次
     * 都被算到制图课头上 —— 那天的课表会显示成「科技论文配图设计与制作」
     * 连上十几周，而英语整个消失。
     *
     * **识别特征**（只看行的结构，不看具体课名）：
     *   · 能匹配 `[N-N周] 日期 教师[教室]` 的行 → 属于**当前**这门课的周次
     *   · 匹配不上的非空行 → **这是一门新课的课名**，在此切开
     *
     * 为什么不用「课名/教师比对」来判：学校的数据本身就会错配
     * （截图里英语那条挂的教师是李恩耀，和制图课同一个人），
     * 按内容比对只会越猜越错。而「课名行 vs 周次行」的结构差异是稳定的。
     *
     * **课名和班级分开存**（致辉 2026-11-01 定）：
     *   · `name` 只放纯课名，格子里显示，不然手机上会撑爆
     *   · `mergedClasses` 放班级前缀，详情页单独列一行
     */
    internal fun parseCell(html: String, weekday: Int, unit: Int): List<CourseSession> {
        if (html.isBlank()) return emptyList()

        // 起止时间从节次表拿（研究生接口不逐课给时间，只给节次行）
        val startTime = SlotTimes[unit].orEmpty()
        val endTime = SlotEndTimes[unit].orEmpty()

        /*
         * 切行。两层切分，缺一层就会把两门课糊在一起。
         *
         * ① 先按 `<br>` 切（三种写法都见过：`<br/>`、`<br>`、`<br />`）
         * ② **再按「周次记录后面紧跟课名」切** —— 学校的 HTML 里两门课之间
         *    偶尔**没有 `<br>`**，只有一个双空格：
         *
         *    `[7-7周] 2026-10-21 杨兴升[综合楼103]  2026级…（2）-新时代…`
         *                                              ↑ 这里没有 <br>
         *
         *    只按 `<br>` 切的话，第二门课的课名会被并进上一行，
         *    于是「新时代中国特色社会主义理论与实践研究」整门课消失，
         *    它的周次也会被算到前一门课头上。
         *
         * 第 ② 步的判据：一行里「[N-N周] …[教室]」后面还跟着非空白字符，
         * 那后面的部分就是新课名，在这里断开。
         */
        val rawLines = html.split(Regex("<br\\s*/?>"))
            .map { it.replace(Regex("<[^>]+>"), "").trim() }
            .filter { it.isNotEmpty() }

        val lines = ArrayList<String>(rawLines.size + 2)
        for (line in rawLines) {
            // 找本行最后一个周次记录的结束位置，看它后面还有没有内容
            var cut = -1
            for (m in WEEK_ENTRY.findAll(line)) cut = m.range.last + 1
            if (cut > 0 && cut < line.length) {
                val tail = line.substring(cut).trim()
                if (tail.isNotEmpty()) {
                    lines.add(line.substring(0, cut).trim())
                    lines.add(tail)
                    continue
                }
            }
            lines.add(line)
        }
        if (lines.isEmpty()) return emptyList()

        /*
         * 按「课名行」分块。
         *
         * 判据不是简单的「解析不出周次就是课名」——那样会把备注
         * （「改期，另行通知」）当成一门课。
         *
         * 两个结构特征一起用：
         *   ① **后面跟着至少一条周次行** —— 课名永远不是单独存在的
         *   ② 形如「20xx级…班-课名」或本身就是一段完整课名
         *
         * 只看 ① 不够：「中医内科学 / 改期另行通知 / 针灸学 / [5-5周]…」
         * 里「针灸学」后面确实跟着周次行，但它前面是一个备注，
         * 我一开始加的「当前块已有周次」条件反而把「针灸学」也一起丢了 ——
         * 因为「中医内科学」那一块还没拿到周次。
         *
         * 所以改用「像不像课名」判定 [looksLikeCourseName]，
         * 再看后面有没有周次兜底。备注短、没有班级前缀、
         * 也不像课程全名，会被过滤掉。
         */
        val blocks = ArrayList<Pair<String, ArrayList<String>>>(2)
        for ((i, line) in lines.withIndex()) {
            if (WEEK_ENTRY.containsMatchIn(line)) {
                // 周次行：挂到当前块；若前面没有课名行（数据残缺），
                // 用空名开一个块，至少别把这条数据丢了
                if (blocks.isEmpty()) blocks.add("" to arrayListOf(line))
                else blocks.last().second.add(line)
                continue
            }

            val nextIsWeek = lines.getOrNull(i + 1)?.let { WEEK_ENTRY.containsMatchIn(it) } == true
            when {
                // 第一行永远是课名（一格的第一个非空行不可能是备注）
                blocks.isEmpty() -> blocks.add(line to ArrayList())
                // 后面跟着周次，且这行本身像课名 → 另一门课
                nextIsWeek && looksLikeCourseName(line) -> blocks.add(line to ArrayList())
                // 其余：备注 / 补充说明，丢掉
                else -> Unit
            }
        }

        val out = ArrayList<CourseSession>(blocks.size * 2)
        for ((rawName, weekLines) in blocks) {
            val split = splitNameAndClass(rawName)
            val name = split.first
            val classes = split.second
            if (name.isBlank()) continue

            var added = 0
            for (line in weekLines) {
                for (m in WEEK_ENTRY.findAll(line)) {
                    val week = m.groupValues[1].toIntOrNull() ?: continue
                    if (week <= 0) continue
                    out.add(
                        CourseSession(
                            name = name,
                            code = "",
                            teachers = m.groupValues[4].trim(),
                            week = week,
                            weekday = weekday,
                            startUnit = unit,
                            endUnit = unit,
                            startTime = startTime,
                            endTime = endTime,
                            room = m.groupValues[5].trim(),
                            category = "",
                            mergedClasses = classes,
                            date = m.groupValues[3],
                            campus = Campus.GRADUATE,
                        ),
                    )
                    added++
                }
            }

            // 这一块没有任何可解析的周次（如「改期，另行通知」）：
            // 至少留一条，别把整门课丢了
            if (added == 0) {
                out.add(
                    CourseSession(
                        name = name,
                        code = "",
                        teachers = "",
                        week = 1,
                        weekday = weekday,
                        startUnit = unit,
                        endUnit = unit,
                        startTime = startTime,
                        endTime = endTime,
                        room = "",
                        category = "",
                        mergedClasses = classes,
                        campus = Campus.GRADUATE,
                    ),
                )
            }
        }
        return out
    }

    /**
     * 这一行像不像「课名」？
     *
     * 用途：一格塞多门课时，在中间切分。区分「新课名」和「备注」。
     *
     * 判断依据按可靠性排序：
     *   ① **有班级前缀**（`20xx级…班-`）→ 一定是课名。
     *      研究生课表里绝大多数课都带这个前缀，最可靠。
     *   ② 没有前缀时看长度：课名一般 ≥4 个字，备注（「调课」
     *      「另行通知」「线上」）通常很短。这条是兜底，
     *      因为有些课（「中医内科学」「文献检索与利用」）确实没前缀。
     *   ③ 排除明显是备注的写法：含「另行通知」「改期」「停课」
     *      「调至」「见群」这类词。
     *
     * 不做「和上一门课比对」：截图里英语那条挂的教师是李恩耀，
     * 和制图课同一个人 —— 学校的数据本身就会错配，按内容比只会越猜越错。
     */
    internal fun looksLikeCourseName(line: String): Boolean {
        val s = line.trim()
        if (s.isEmpty()) return false

        // ③ 明显是备注的
        if (REMARK_HINTS.any { s.contains(it) }) return false

        // ① 有班级前缀
        if (s.startsWith("20") && s.contains("级") && s.contains('-')) {
            // 还得真有课名（「2026级某班-」这种残缺不算）
            return splitNameAndClass(s).first.length >= 2
        }

        // ② 无前缀：足够长就算课名
        return s.length >= 4
    }

    /** 备注里常见的词。命中就不当课名 */
    private val REMARK_HINTS = listOf(
        "另行通知", "改期", "停课", "调课", "调至", "见群", "待定",
        "线上上课", "补课", "放假",
    )

    /**
     * 把「班级前缀 + 课名」拆成两段。
     *
     * | 原始 | name（格子里显示） | mergedClasses（详情页显示） |
     * |---|---|---|
     * | 2026级大学城硕士7、9、11班-英语（专业型硕士） | 英语（专业型硕士） | 2026级大学城硕士7、9、11班 |
     * | 2026级大学城博士2、3班、硕士4班-9班-医学分子生物学基础 | 医学分子生物学基础 | 2026级大学城博士2、3班、硕士4班-9班 |
     * | 中医内科学 | 中医内科学 | （空） |
     *
     * **规则**：课名开头是「20xx级」且含「-」时，取**最后一个**「-」切开。
     *
     * 为什么用最后一个：班级名自己也带「-」——
     * 「硕士4班-9班」的「-」如果当分隔符，会把「9班-医学分子生物学基础」当成课名。
     *
     * @return Pair(纯课名, 班级前缀)。没有前缀时第二项为空串。
     */
    internal fun splitNameAndClass(raw: String): Pair<String, String> {
        val s = raw.trim()
        // 不是「20xx级...」开头的，当纯课名
        if (!s.startsWith("20") || s.length < 8 || !s.contains("级")) {
            return s to ""
        }
        val idx = s.lastIndexOf('-')
        if (idx <= 0 || idx >= s.length - 1) return s to ""

        val classes = s.substring(0, idx).trim()
        val name = s.substring(idx + 1).trim()

        // 课名太短说明切错了（比如「英语-」），保留原样
        if (name.length < 2 || classes.isEmpty()) return s to ""
        return name to classes
    }

    // ── 成绩 ─────────────────────────────────────────────────────────

    /**
     * 课程成绩：`GET /gmis5/student/pygl/xscjcx_list`
     *
     * 返回 `xwklist`（学位课）/ `fxwklist`（非学位课）/ `xftj`（学分统计）。
     *
     * ⚠️ 这套口径是研究生特有的：**分「学位课 / 非学位课」**，
     * 不是本科的「必修 / 选修 / 限选」。
     * 字段名现在是按接口返回的 key 推的，等有真实成绩后要复核。
     */
    fun fetchGrades(): List<CourseGrade> {
        val text = Net.get("$PREFIX/student/pygl/xscjcx_list", referer = "$PREFIX/student/pygl/xscjcx")
        val root = runCatching { Net.json.parseToJsonElement(text).jsonObject }
            .getOrElse { throw NetError.Parse("研究生成绩响应") }

        val out = ArrayList<CourseGrade>(64)
        // 学位课 + 非学位课，都转成统一的 CourseGrade
        for ((key, label) in listOf("xwklist" to "学位课", "fxwklist" to "非学位课")) {
            val arr = root[key]?.jsonArray ?: continue
            for (el in arr) {
                val o = el as? JsonObject ?: continue
                val name = o.str("kcmc").ifBlank { o.str("kcmc_mc") }.trim()
                if (name.isBlank()) continue
                val score = o.str("cj").toDoubleOrNull()
                    ?: o.str("zpcj").toDoubleOrNull()
                    ?: continue
                out.add(
                    CourseGrade(
                        term = o.str("xq").ifBlank { o.str("xnxq") }.trim(),
                        name = name,
                        score = score,
                        // 研究生接口里没看到绩点字段，先留 0，等有数据再补
                        gpa = o.str("jd").toDoubleOrNull() ?: 0.0,
                        credit = o.str("xf").toDoubleOrNull() ?: 0.0,
                        category = label,
                        nature = o.str("ksxz").trim(),
                        remark = "",
                        passed = score >= 60,
                    ),
                )
            }
        }
        return out
    }

    // ── 学分统计 ─────────────────────────────────────────────────────

    /**
     * 学分统计：`xftj` 数组的第一项。
     *
     * 实测字段（2026 级硕士，还没成绩时）：
     *   yxxwxf = 19  已修学位课学分
     *   yxfxwxf = 19 已修非学位课学分
     *   yxzxf = 19   已修总学分
     *   xwxf / fxwxf / zxf = 0   （应该是「要求」学分，还没配？）
     */
    fun fetchCreditStat(): YjsCreditStat? {
        val text = Net.get("$PREFIX/student/pygl/xscjcx_list", referer = "$PREFIX/student/pygl/xscjcx")
        val root = runCatching { Net.json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return null
        val arr = root["xftj"]?.jsonArray ?: return null
        val o = arr.firstOrNull() as? JsonObject ?: return null
        return YjsCreditStat(
            degreeDone = o.str("yxxwxf").toDoubleOrNull() ?: 0.0,
            nonDegreeDone = o.str("yxfxwxf").toDoubleOrNull() ?: 0.0,
            totalDone = o.str("yxzxf").toDoubleOrNull() ?: 0.0,
            degreeRequired = o.str("xwxf").toDoubleOrNull() ?: 0.0,
            nonDegreeRequired = o.str("fxwxf").toDoubleOrNull() ?: 0.0,
            totalRequired = o.str("zxf").toDoubleOrNull() ?: 0.0,
        )
    }

    /** 研究生登录页地址。进 App 后从这里让用户登录。 */
    const val LOGIN_URL = "$PREFIX/student/default/index"

    /** 登录成功的判定：URL 落到 gmis5 的学生端页面 */
    fun isLoggedInUrl(url: String): Boolean {
        if (url.contains("authserver") || url.contains("/login")) return false
        return url.contains("/gmis5/student/")
    }
}

/** 研究生学期 */
@kotlinx.serialization.Serializable
data class YjsTerm(
    val code: String,
    val name: String,
    val selected: Boolean = false,
)

/** 研究生学分统计 */
@kotlinx.serialization.Serializable
data class YjsCreditStat(
    /** 已修学位课学分 */
    val degreeDone: Double,
    /** 已修非学位课学分 */
    val nonDegreeDone: Double,
    /** 已修总学分 */
    val totalDone: Double,
    /** 要求学位课学分（可能为 0，表示未配置） */
    val degreeRequired: Double,
    /** 要求非学位课学分 */
    val nonDegreeRequired: Double,
    /** 要求总学分 */
    val totalRequired: Double,
)

/*
 * JSON 取值复用 Jw.kt 里的 `JsonObject.str()`（internal，同包可见）。
 * 这里只补一个 int 和 bool —— 那两个是研究生接口特有的
 * （`jcbh` 是数字、`selected` 是布尔）。
 */

internal fun JsonObject.intOrNull(key: String): Int? =
    (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toIntOrNull()

internal fun JsonObject.bool(key: String): Boolean =
    (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.content == "true"