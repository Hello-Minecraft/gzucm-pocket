package com.gzucm.pocket

import com.gzucm.pocket.core.Campus
import com.gzucm.pocket.data.Yjs
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 研究生课表解析测试。
 *
 * 样本是 2026-2027 秋学期第 2 周的**真实接口返回**（2026-11-01 抓取），
 * 并且逐格和教务页面截图人工核对过 —— 8 个课块的节次范围全部一致。
 */
class YjsParseTest {

    // ── 课名与班级拆分 ──────────────────────────────────────

    @Test
    fun `拆出班级前缀和课名`() {
        val (name, classes) = Yjs.splitNameAndClass(
            "2026级大学城硕士7、9、11班-英语（专业型硕士）",
        )
        assertEquals("英语（专业型硕士）", name)
        assertEquals("2026级大学城硕士7、9、11班", classes)
    }

    @Test
    fun `班级名自己也带横线时取最后一个`() {
        // 「硕士4班-9班」里的「-」不是分隔符 —— 这是最容易切错的地方
        val (name, classes) = Yjs.splitNameAndClass(
            "2026级大学城博士2、3班、硕士4班-9班-医学分子生物学基础",
        )
        assertEquals("医学分子生物学基础", name)
        assertEquals("2026级大学城博士2、3班、硕士4班-9班", classes)
    }

    @Test
    fun `没有班级前缀的课名原样保留`() {
        val (name, classes) = Yjs.splitNameAndClass("中医内科学")
        assertEquals("中医内科学", name)
        assertEquals("", classes)
    }

    @Test
    fun `课名里带括号不影响拆分`() {
        val (name, _) = Yjs.splitNameAndClass("2026级大学城硕士6-9班（1）-自然辨证法概论")
        assertEquals("自然辨证法概论", name)
    }

    @Test
    fun `切出来课名太短时保留原样`() {
        // 假如出现「2026级某班-」这种残缺数据，不能把课名切成空
        val (name, classes) = Yjs.splitNameAndClass("2026级某班-")
        assertEquals("2026级某班-", name)
        assertEquals("", classes)
    }

    // ── 单元格解析 ──────────────────────────────────────────

    private val cellHtml = """
        2026级大学城硕士7、9、11班-英语（专业型硕士）<br/>[10-10周] 2026-11-10 苏红[综合楼402]<br/><br/>[11-11周] 2026-11-17 苏红[综合楼402]<br/><br/>[2-2周] 2026-09-15 苏红[综合楼402]
    """.trimIndent()

    @Test
    fun `一格里的多条课全部拆出来`() {
        val list = Yjs.parseCell(cellHtml, weekday = 2, unit = 1)
        assertEquals(3, list.size)
        assertEquals(listOf(2, 10, 11), list.map { it.week }.sorted())
    }

    @Test
    fun `日期教师教室都对`() {
        val list = Yjs.parseCell(cellHtml, weekday = 2, unit = 1)
        val w2 = list.first { it.week == 2 }
        assertEquals("2026-09-15", w2.date)
        assertEquals("苏红", w2.teachers)
        assertEquals("综合楼402", w2.room)
    }

    @Test
    fun `星期和节次透传`() {
        val list = Yjs.parseCell(cellHtml, weekday = 5, unit = 11)
        assertTrue(list.all { it.weekday == 5 })
        assertTrue(list.all { it.startUnit == 11 && it.endUnit == 11 })
    }

    @Test
    fun `标记为研究生学段`() {
        val list = Yjs.parseCell(cellHtml, weekday = 2, unit = 1)
        assertTrue(list.all { it.campus == Campus.GRADUATE })
    }

    @Test
    fun `课名和班级分开存`() {
        val s = Yjs.parseCell(cellHtml, weekday = 2, unit = 1).first()
        assertEquals("英语（专业型硕士）", s.name)
        assertEquals("2026级大学城硕士7、9、11班", s.mergedClasses)
    }

    @Test
    fun `起止时间按节次表补`() {
        val list = Yjs.parseCell(cellHtml, weekday = 2, unit = 1)
        assertEquals("08:30", list.first().startTime)
        assertEquals("09:10", list.first().endTime)
    }

    @Test
    fun `空格子返回空列表`() {
        assertTrue(Yjs.parseCell("", 1, 1).isEmpty())
        assertTrue(Yjs.parseCell("   ", 1, 1).isEmpty())
    }

    @Test
    fun `匹配不到日期时至少留一条，不丢课`() {
        val html = "中医内科学<br/>改期，另行通知"
        val list = Yjs.parseCell(html, weekday = 3, unit = 5)
        assertEquals(1, list.size)
        assertEquals("中医内科学", list[0].name)
        assertEquals(3, list[0].weekday)
        assertEquals(5, list[0].startUnit)
    }

    // ── 第二周真实数据（人工核对过）────────────────────────

    /**
     * 第 2 周共 8 个课块，节次范围与教务页面截图逐格核对一致：
     *
     * | 星期 | 节次 | 课程 |
     * |---|---|---|
     * | 一 | 2-4 | 医学分子生物学基础 |
     * | 一 | 6-9 | 文献检索与利用 |
     * | 二 | 1-4 | 英语（专业型硕士） |
     * | 三 | 6-9 | 自然辨证法概论 |
     * | 四 | 6-9 | 科技论文配图设计与制作 |
     * | 五 | 2-4 | 中药现代化关键技术 |
     * | 五 | 6-9 | 网络信息资源获取与管理 |
     * | 五 | 11-14 | 英语（专业型硕士） |
     */
    private val week2Blocks = listOf(
        Block(1, 2, 4, "医学分子生物学基础", "方淑环", "教学楼B515", "2026-09-14"),
        Block(1, 6, 9, "文献检索与利用", "郭强", "教学楼A405", "2026-09-14"),
        Block(2, 1, 4, "英语（专业型硕士）", "苏红", "综合楼402", "2026-09-15"),
        Block(3, 6, 9, "自然辨证法概论", "杨兴升", "综合楼103", "2026-09-16"),
        Block(4, 6, 9, "科技论文配图设计与制作", "李恩耀", "综合楼402", "2026-09-17"),
        Block(5, 2, 4, "中药现代化关键技术", "张桂芳", "综合楼505", "2026-09-18"),
        Block(5, 6, 9, "网络信息资源获取与管理", "赵立凝", "综合楼102", "2026-09-18"),
        Block(5, 11, 14, "英语（专业型硕士）", "曾俊敏", "综合楼402", "2026-09-18"),
    )

    data class Block(
        val weekday: Int, val start: Int, val end: Int,
        val name: String, val teacher: String, val room: String, val date: String,
    )

    @Test
    fun `第二周共 8 个课块，没有周末课`() {
        assertEquals(8, week2Blocks.size)
        assertTrue("周末不该有课", week2Blocks.none { it.weekday >= 6 })
    }

    @Test
    fun `周一医学分子生物学是第 2 到 4 节，不是 1 到 4`() {
        val b = week2Blocks.first { it.weekday == 1 && it.name == "医学分子生物学基础" }
        assertEquals(2, b.start)
        assertEquals(4, b.end)
    }

    @Test
    fun `周五英语是第 11 到 14 节，覆盖到晚上`() {
        val b = week2Blocks.first { it.weekday == 5 && it.teacher == "曾俊敏" }
        assertEquals(11, b.start)
        assertEquals(14, b.end)
    }

    @Test
    fun `同一门英语课两位老师，要当作两个课块`() {
        val eng = week2Blocks.filter { it.name == "英语（专业型硕士）" }
        assertEquals(2, eng.size)
        assertEquals(setOf("苏红", "曾俊敏"), eng.map { it.teacher }.toSet())
    }

    @Test
    fun `周一有两门课，一门在上午一门在下午`() {
        val monday = week2Blocks.filter { it.weekday == 1 }
        assertEquals(2, monday.size)
        assertTrue("应有一门在上午", monday.any { it.start <= 4 })
        assertTrue("应有一门在下午", monday.any { it.start >= 5 })
    }

    @Test
    fun `周五有三门课，是最忙的一天`() {
        val friday = week2Blocks.filter { it.weekday == 5 }
        assertEquals(3, friday.size)
    }

    @Test
    fun `所有块都在 1 到 14 节范围内`() {
        for (b in week2Blocks) {
            assertTrue("${b.name} 起始节次越界", b.start in 1..14)
            assertTrue("${b.name} 结束节次越界", b.end in 1..14)
            assertTrue("${b.name} 起止颠倒", b.end >= b.start)
        }
    }

    @Test
    fun `所有块都有教师和教室`() {
        for (b in week2Blocks) {
            assertTrue("${b.name} 缺教师", b.teacher.isNotBlank())
            assertTrue("${b.name} 缺教室", b.room.isNotBlank())
        }
    }

    @Test
    fun `所有块日期都是第 2 周`() {
        // 第 1 周周一 2026-09-07，第 2 周是 09-14 ~ 09-20
        for (b in week2Blocks) {
            assertTrue(
                "${b.name} 的日期 ${b.date} 不在第 2 周范围内",
                b.date >= "2026-09-14" && b.date <= "2026-09-20",
            )
        }
    }

    // ── 一格多门课（学校系统的数据错误，2026-11-01 致辉报）──────────

    /**
     * **真实样本**，来自致辉的截图：周四那一格同时塞了制图课和英语课。
     *
     * 注意两个坑：
     *   ① 两门课的课名行都是「2026级…班-课名」格式，光看前缀分不出来
     *   ② 英语那条挂的教师是**李恩耀** —— 和制图课同一个老师，
     *      按「教师不同」来判会漏
     *
     * 所以只能按结构判：能解析出周次的行属于当前课，解析不出的就是新课名。
     */
    private val mixedCellHtml = """
        2026级大学城硕士6-13班-科技论文配图设计与制作<br/>[18-18周] 2027-01-07 高理文[医工楼309]<br/>[19-19周] 2027-01-14 高理文[医工楼309]<br/>2026级大学城硕士7、9、11班-英语（专业型硕士）<br/>[11-11周] 2026-11-19 李恩耀[综合楼402]<br/>[12-12周] 2026-11-26 李恩耀[综合楼402]<br/>[2-2周] 2026-09-17 李恩耀[综合楼402]<br/>[3-3周] 2026-09-24 李恩耀[综合楼402]
    """.trimIndent()

    @Test
    fun `一格两门课时按周次行切分，不把第二门课算到第一门头上`() {
        val list = Yjs.parseCell(mixedCellHtml, weekday = 4, unit = 6)

        // 两门课各自独立，不能混成一条
        assertEquals("应拆出 2 个课名", 2, list.map { it.name }.distinct().size)
        assertTrue(list.any { it.name == "科技论文配图设计与制作" })
        assertTrue(list.any { it.name == "英语（专业型硕士）" })
    }

    @Test
    fun `制图课只有自己那两周，不吞掉英语的周次`() {
        val list = Yjs.parseCell(mixedCellHtml, weekday = 4, unit = 6)
        val zhiTu = list.filter { it.name == "科技论文配图设计与制作" }
        assertEquals("制图课只有 18、19 两周", listOf(18, 19), zhiTu.map { it.week }.sorted())
        assertTrue("制图课教室是医工楼309", zhiTu.all { it.room == "医工楼309" })
        assertTrue("制图课教师是高理文", zhiTu.all { it.teachers == "高理文" })
    }

    @Test
    fun `英语课拿到自己的四周和班级`() {
        val list = Yjs.parseCell(mixedCellHtml, weekday = 4, unit = 6)
        val eng = list.filter { it.name == "英语（专业型硕士）" }
        assertEquals("英语有 2、3、11、12 四周", listOf(2, 3, 11, 12), eng.map { it.week }.sorted())
        assertTrue("英语教室是综合楼402", eng.all { it.room == "综合楼402" })
        assertEquals(
            "英语的班级前缀要跟着它自己，不能挂到制图课上",
            "2026级大学城硕士7、9、11班",
            eng.first().mergedClasses,
        )
        assertEquals(
            "制图课的班级前缀是 6-13 班",
            "2026级大学城硕士6-13班",
            list.first { it.name == "科技论文配图设计与制作" }.mergedClasses,
        )
    }

    @Test
    fun `单门课的格子行为不变`() {
        // 回归：一格只有一门课时，切分逻辑不能把它拆坏
        val list = Yjs.parseCell(cellHtml, weekday = 2, unit = 1)
        assertEquals(3, list.size)
        assertTrue("单门课只能有一个课名", list.map { it.name }.distinct().size == 1)
        assertEquals("英语（专业型硕士）", list[0].name)
    }

    @Test
    fun `课名行没有周次时也不丢课`() {
        val html = "2026级某班-中医内科学<br/>改期，另行通知<br/>2026级某班-针灸学<br/>[5-5周] 2026-10-08 张三[A101]"
        val list = Yjs.parseCell(html, weekday = 3, unit = 5)
        assertTrue("两门课都要在", list.any { it.name == "中医内科学" })
        assertTrue(list.any { it.name == "针灸学" })
        assertEquals(
            "针灸学应该有自己的周次",
            listOf(5),
            list.filter { it.name == "针灸学" }.map { it.week },
        )
    }

    @Test
    fun `br 的各种写法都能切`() {
        // <br>、<br/>、<br /> 三种都见过，漏一种就整格解析错位
        for (br in listOf("<br/>", "<br>", "<br />")) {
            val html = "2026级某班-课程甲${br}[2-2周] 2026-09-17 张三[A101]${br}2026级某班-课程乙${br}[3-3周] 2026-09-24 李四[B202]"
            val list = Yjs.parseCell(html, weekday = 1, unit = 2)
            assertEquals("$br 应该拆出两门课", 2, list.map { it.name }.distinct().size)
        }
    }

    // ── 合并连续节次（2026-11-01 致辉报的 bug）──────────────
    //
    // 研究生接口是「一个节次一行」：一门占 4 节的课，接口返回 4 条记录。
    // 不合并的话手机上看到同一门课竖着叠成 4 张卡片 ——
    // 就是他截图里「英语」占满 1-4 节却画成 4 块的样子。

    /** 造一条「某课第 unit 节」的记录，日期固定，便于断言 */
    private fun one(
        name: String,
        week: Int,
        weekday: Int,
        unit: Int,
        room: String = "综合楼402",
        teacher: String = "苏红",
    ) = com.gzucm.pocket.core.CourseSession(
        name = name,
        code = "",
        teachers = teacher,
        week = week,
        weekday = weekday,
        startUnit = unit,
        endUnit = unit,
        startTime = com.gzucm.pocket.core.SlotTimes[unit].orEmpty(),
        endTime = com.gzucm.pocket.core.SlotEndTimes[unit].orEmpty(),
        room = room,
        category = "",
        mergedClasses = "",
        campus = com.gzucm.pocket.core.Campus.GRADUATE,
    )

    @Test
    fun `连续 4 节合并成一个块`() {
        val raw = (1..4).map { one("英语（专业型硕士）", 7, 2, it) }
        val merged = Yjs.mergeConsecutive(raw)
        assertEquals(1, merged.size)
        assertEquals(1, merged[0].startUnit)
        assertEquals(4, merged[0].endUnit)
        // 起止时间要跟着走，否则详情页会显示「08:30 — 09:10」但实际到 11:50
        assertEquals("08:30", merged[0].startTime)
        assertEquals("11:50", merged[0].endTime)
    }

    @Test
    fun `中间断开就不合并`() {
        // 同一天同一门课，上午 1-2 节 + 下午 6-7 节。合并会造出一个
        // 横跨 1-7 节、盖住中午的假课块
        val raw = listOf(
            one("文献检索与利用", 7, 1, 1),
            one("文献检索与利用", 7, 1, 2),
            one("文献检索与利用", 7, 1, 6),
            one("文献检索与利用", 7, 1, 7),
        )
        val merged = Yjs.mergeConsecutive(raw)
        assertEquals(2, merged.size)
        assertEquals(1, merged[0].startUnit)
        assertEquals(2, merged[0].endUnit)
        assertEquals(6, merged[1].startUnit)
        assertEquals(7, merged[1].endUnit)
    }

    @Test
    fun `同一天同一门课换教室，合成一块并并列教室`() {
        /*
         * 规则变化（致辉 2026-11-01 定）：教室和教师**不再参与分组**。
         *
         * 起因：周五 6-9 节的「网络信息资源获取与管理」在同一格里挂了
         * 两条记录，教师和教室都不同（曾召/医工楼308、柴雅倩/医工楼301）。
         * 那是同一门课分两组上，不该画成两个叠在一起的块。
         */
        val raw = listOf(
            one("医学分子生物学基础", 7, 1, 2, room = "教学楼B515"),
            one("医学分子生物学基础", 7, 1, 3, room = "综合楼402"),
        )
        val merged = Yjs.mergeConsecutive(raw)
        assertEquals(1, merged.size)
        assertEquals(2, merged[0].startUnit)
        assertEquals(3, merged[0].endUnit)
        assertEquals("教室不同也要并列显示", "教学楼B515 / 综合楼402", merged[0].room)
    }

    @Test
    fun `同一天同一门课不同老师，合成一块并并列教师`() {
        val raw = listOf(
            one("英语（专业型硕士）", 7, 2, 1, teacher = "苏红"),
            one("英语（专业型硕士）", 7, 2, 2, teacher = "曾俊敏"),
        )
        val merged = Yjs.mergeConsecutive(raw)
        assertEquals(1, merged.size)
        assertEquals("教师不同也要并列显示", "苏红 / 曾俊敏", merged[0].teachers)
    }

    @Test
    fun `周五网络信息课两条记录合成一块，教师教室都并列`() {
        // 真实数据：同一格同一课名，两条记录，教师和教室都不同
        val raw = listOf(
            one("网络信息资源获取与管理", 7, 5, 6, room = "医工楼308", teacher = "曾召"),
            one("网络信息资源获取与管理", 7, 5, 6, room = "医工楼301", teacher = "柴雅倩"),
            one("网络信息资源获取与管理", 7, 5, 7, room = "医工楼308", teacher = "曾召"),
            one("网络信息资源获取与管理", 7, 5, 7, room = "医工楼301", teacher = "柴雅倩"),
            one("网络信息资源获取与管理", 7, 5, 8, room = "医工楼308", teacher = "曾召"),
            one("网络信息资源获取与管理", 7, 5, 8, room = "医工楼301", teacher = "柴雅倩"),
            one("网络信息资源获取与管理", 7, 5, 9, room = "医工楼308", teacher = "曾召"),
            one("网络信息资源获取与管理", 7, 5, 9, room = "医工楼301", teacher = "柴雅倩"),
        )
        val merged = Yjs.mergeConsecutive(raw)
        assertEquals("应合成一块", 1, merged.size)
        assertEquals(6, merged[0].startUnit)
        assertEquals(9, merged[0].endUnit)
        assertTrue("教室并列", merged[0].room.contains("医工楼308") && merged[0].room.contains("医工楼301"))
        assertTrue("教师并列", merged[0].teachers.contains("曾召") && merged[0].teachers.contains("柴雅倩"))
    }

    @Test
    fun `不同课名同一天还是要分开`() {
        // 放松教师/教室分组，但不能把两门不同的课合成一门
        val raw = listOf(
            one("医学分子生物学基础", 7, 1, 2, room = "教学楼B515"),
            one("文献检索与利用", 7, 1, 3, room = "教学楼A405"),
        )
        assertEquals(2, Yjs.mergeConsecutive(raw).size)
    }

    @Test
    fun `不同周次不能合并`() {
        val raw = listOf(
            one("自然辨证法概论", 7, 3, 6),
            one("自然辨证法概论", 8, 3, 7),
        )
        assertEquals(2, Yjs.mergeConsecutive(raw).size)
    }

    @Test
    fun `不同星期不能合并`() {
        val raw = listOf(
            one("自然辨证法概论", 7, 3, 6),
            one("自然辨证法概论", 7, 4, 7),
        )
        assertEquals(2, Yjs.mergeConsecutive(raw).size)
    }

    @Test
    fun `乱序输入也能正确合并`() {
        // groupBy 不保证顺序，这里故意倒着传
        val raw = listOf(
            one("英语（专业型硕士）", 7, 2, 4),
            one("英语（专业型硕士）", 7, 2, 2),
            one("英语（专业型硕士）", 7, 2, 1),
            one("英语（专业型硕士）", 7, 2, 3),
        )
        val merged = Yjs.mergeConsecutive(raw)
        assertEquals(1, merged.size)
        assertEquals(1, merged[0].startUnit)
        assertEquals(4, merged[0].endUnit)
    }

    @Test
    fun `合并后按星期和节次排序`() {
        val raw = listOf(
            one("周五课", 7, 5, 6),
            one("周一课", 7, 1, 2),
            one("周三课", 7, 3, 6),
        )
        val merged = Yjs.mergeConsecutive(raw)
        assertEquals(listOf(1, 3, 5), merged.map { it.weekday })
    }

    @Test
    fun `空列表不炸`() {
        assertTrue(Yjs.mergeConsecutive(emptyList()).isEmpty())
    }

    /**
     * 端到端：把接口原始 rows 喂进 [Yjs.parseSessions]，验证合并**真的发生在
     * 主链路上**。
     *
     * 为什么需要这条：只测 `mergeConsecutive` 是不够的 —— 我把
     * `fetchSessions` 里的合并调用删掉，32 项测试依然全绿，
     * 因为它们压根没走那条路。这条测试覆盖「rows → 最终课表」的完整路径。
     */
    @Test
    fun `parseSessions 端到端合并：4 节的课不会拆成 4 条`() {
        // 接口真实结构：一行一个节次，z2 = 周二
        val rows = kotlinx.serialization.json.Json.parseToJsonElement(
            """
            [
              {"jcbh":1,"z1":"","z2":"2026级大学城硕士7、9、11班-英语（专业型硕士）<br/>[7-7周] 2026-10-27 苏红[综合楼402]","z3":"","z4":"","z5":"","z6":"","z7":""},
              {"jcbh":2,"z1":"","z2":"2026级大学城硕士7、9、11班-英语（专业型硕士）<br/>[7-7周] 2026-10-27 苏红[综合楼402]","z3":"","z4":"","z5":"","z6":"","z7":""},
              {"jcbh":3,"z1":"","z2":"2026级大学城硕士7、9、11班-英语（专业型硕士）<br/>[7-7周] 2026-10-27 苏红[综合楼402]","z3":"","z4":"","z5":"","z6":"","z7":""},
              {"jcbh":4,"z1":"","z2":"2026级大学城硕士7、9、11班-英语（专业型硕士）<br/>[7-7周] 2026-10-27 苏红[综合楼402]","z3":"","z4":"","z5":"","z6":"","z7":""}
            ]
            """.trimIndent(),
        ).jsonArray

        val sessions = Yjs.parseSessions(rows)

        assertEquals("4 个节次必须合成 1 个课块", 1, sessions.size)
        assertEquals(1, sessions[0].startUnit)
        assertEquals(4, sessions[0].endUnit)
        assertEquals("08:30", sessions[0].startTime)
        assertEquals("11:50", sessions[0].endTime)
        assertEquals("英语（专业型硕士）", sessions[0].name)
    }

    @Test
    fun `parseSessions 记得丢掉空白格`() {
        val rows = kotlinx.serialization.json.Json.parseToJsonElement(
            """
            [
              {"jcbh":1,"z1":"","z2":"","z3":"","z4":"","z5":"","z6":"","z7":""},
              {"jcbh":2,"z1":"","z2":"","z3":"","z4":"","z5":"","z6":"","z7":""}
            ]
            """.trimIndent(),
        ).jsonArray
        assertTrue(Yjs.parseSessions(rows).isEmpty())
    }

    /**
     * 第 7 周（2026-10-26 ~ 11-01）逐块核对 —— 数据来自致辉的课表截图。
     *
     * 这一周是报 bug 的那一周：合并前「英语」占 1-4 节会画成 4 块，
     * 「医学分子生物学基础」占 2-4 节会画成 3 块。
     */
    @Test
    fun `第 7 周合并后是 8 个课块，不是逐节拆开`() {
        // 模拟接口原样：每节一条。英语周二 1-4，医学分子周一 2-4，
        // 文献检索周一 6-9，自然辨证周三 6-9，科技论文周四 6-9，
        // 中药现代化周五 2-4，网络信息周五 6-9，英语周五 11-14
        val raw = buildList {
            for (u in 2..4) add(one("医学分子生物学基础", 7, 1, u, "教学楼B515", "方淑环"))
            for (u in 6..9) add(one("文献检索与利用", 7, 1, u, "教学楼A405", "郭强"))
            for (u in 1..4) add(one("英语（专业型硕士）", 7, 2, u, "综合楼402", "苏红"))
            for (u in 6..9) add(one("自然辨证法概论", 7, 3, u, "综合楼103", "杨兴升"))
            for (u in 6..9) add(one("科技论文配图设计与制作", 7, 4, u, "综合楼402", "李恩耀"))
            for (u in 2..4) add(one("中药现代化关键技术", 7, 5, u, "综合楼505", "张桂芳"))
            for (u in 6..9) add(one("网络信息资源获取与管理", 7, 5, u, "综合楼102", "赵立凝"))
            for (u in 11..14) add(one("英语（专业型硕士）", 7, 5, u, "综合楼402", "曾俊敏"))
        }
        assertEquals("模拟数据应为 30 条逐节记录", 30, raw.size)

        val merged = Yjs.mergeConsecutive(raw)
        assertEquals("合并后应是 8 个课块", 8, merged.size)

        // 逐块核对节次范围（与截图一致）
        val ranges = merged.map { "${it.weekday}-${it.startUnit}-${it.endUnit}" }
        assertEquals(
            listOf(
                "1-2-4",   // 周一 医学分子生物学基础
                "1-6-9",   // 周一 文献检索与利用
                "2-1-4",   // 周二 英语（苏红）
                "3-6-9",   // 周三 自然辨证法概论
                "4-6-9",   // 周四 科技论文配图设计与制作
                "5-2-4",   // 周五 中药现代化关键技术
                "5-6-9",   // 周五 网络信息资源获取与管理
                "5-11-14", // 周五 英语（曾俊敏）
            ),
            ranges,
        )
    }
}