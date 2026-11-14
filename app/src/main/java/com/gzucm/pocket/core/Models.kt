package com.gzucm.pocket.core

import kotlinx.serialization.Serializable
import java.time.LocalDate

/** 一条上课记录（已按「周」展开） */
@Serializable
data class CourseSession(
    val name: String,
    val code: String,
    val teachers: String,
    val week: Int,
    val weekday: Int,
    val startUnit: Int,
    val endUnit: Int,
    val startTime: String,
    val endTime: String,
    val room: String,
    val category: String,
    /** 教学模式，如 PBL / 讲授 */
    val teachingMode: String = "",
    /** 合班班级，逗号分隔 */
    val mergedClasses: String = "",
    /** 课时数 */
    val studyHours: String = "",
    /** 课序号 */
    val courseSeq: String = "",
    /** 学校给这门课配的色值，如 #009688 */
    val schoolColor: String = "",
    /**
     * 上课内容（`sknrjj`），如「性早熟」「王泰林」。
     * 只有按周查询（zc=周次）才会返回 —— 整学期总表没有这个字段。
     */
    val content: String = "",
    /** 这一节课的真实日期（`qsrq`），ISO 格式 yyyy-MM-dd。总表也没有。 */
    val date: String = "",
    /** 教学环节（`jxhjmc`），如「实验教学」「理论教学」 */
    val teachLink: String = "",
    /** 教学形式（`jxxsmc`），如「线下教学」 */
    val teachForm: String = "",
    /**
     * 这条数据来自哪个系统。
     *
     * 放在最后 + 给默认值：老缓存里没有这个字段，反序列化不能炸。
     * 默认 UNDERGRAD 是对的 —— 升级前所有数据都是本科的。
     */
    val campus: Campus = Campus.UNDERGRAD,
)

/**
 * 学期。firstMonday 来自教学校历接口，用于把「第 N 周」翻译成真实日期。
 * 存成 ISO 字符串以便直接序列化缓存。
 */
@Serializable
data class Semester(
    val code: String,
    val name: String,
    val firstMondayIso: String,
    val totalWeeks: Int,
) {
    val firstMonday: LocalDate get() = LocalDate.parse(firstMondayIso)

    fun weekOf(date: LocalDate): Int {
        val days = java.time.temporal.ChronoUnit.DAYS.between(firstMonday, date).toInt()
        return if (days < 0) 0 else days / 7 + 1
    }

    fun dateOf(week: Int, weekday: Int): LocalDate =
        firstMonday.plusDays(((week - 1) * 7 + (weekday - 1)).toLong())

    companion object {
        fun of(code: String, name: String, firstMonday: LocalDate, totalWeeks: Int) =
            Semester(code, name, firstMonday.toString(), totalWeeks)
    }
}

/** 课程成绩一行 */
@Serializable
data class CourseGrade(
    val term: String,
    val name: String,
    val score: Double,
    val gpa: Double,
    val credit: Double,
    val category: String,
    val nature: String,
    val remark: String,
    val passed: Boolean,
    /**
     * 课程分类（教务的 `kcflmc`）。只有部分课带值，比如公共选修课会细分
     * 「美育类」「科学技术与文明类」「人文社科与素养类」等。
     *
     * 放在最后并给默认值：一是老缓存没这个字段，反序列化不能炸；
     * 二是调用方大多按位置传参，插在中间会把 nature / remark 整体错位。
     */
    val classify: String = "",
)

/** 考级成绩（四六级等） */
@Serializable
data class LevelGrade(
    val name: String,
    val term: String,
    val total: Double,
    val level: String,
    val date: String,
    val parts: List<Pair<String, String>>,
)

/** 体测单项 */
@Serializable
data class FitnessItem(val label: String, val value: String, val score: String, val grade: String)

/** 体测成绩 */
@Serializable
data class FitnessGrade(
    val term: String,
    val total: String,
    val level: String,
    val items: List<FitnessItem>,
)

/** 电费账户信息 */
@Serializable
data class PowerInfo(
    val balance: Double,
    val unit: String,
    val roomLabel: String,
    val token: String,
    val schoolAreaNo: String,
    val buildingNo: String,
    val roomNum: String,
)

/** 某天的用电明细 */
@Serializable
data class PowerDaily(val date: String, val usage: Double, val remain: Double, val total: Double)

/**
 * 考试详情里的一行（中文标签 + 原始值）。
 *
 * 教务返回的键名是 `jkteaxms` `kslbmc` 这种拼音缩写，直接摆给用户看不懂，
 * 所以在抓取时就翻好，详情页只负责显示。
 */
@Serializable
data class ExamDetail(val label: String, val value: String)

/**
 * 考试安排。
 *
 * 主字段（课程/日期/时间/地点/座位/类型）拿来在课表和列表里直接展示，
 * 其余字段收进 [details]，点开详情才显示。
 *
 * 日期和时间保留原始字符串：`ksrq` 是 `2026-11-14`，`kssj` 是
 * `14:30:00--17:30:00`（两个连字符，秒位全是 0）。解析成分钟只在
 * [startMinute] / [endMinute] 里做，解析不出来就返回 null，不猜。
 */
@Serializable
data class ExamItem(
    val course: String,
    val date: String,
    val time: String,
    val room: String,
    val seat: String,
    val type: String,
    val details: List<ExamDetail> = emptyList(),
) {
    /** 开考分钟（当天第几分钟）。`kssj` 为空或格式变了就返回 null */
    val startMinute: Int? get() = parseMinute(time, 0)

    /** 结束分钟。没有结束时间时给 2 小时，够画出块又不至于太长 */
    val endMinute: Int? get() = parseMinute(time, 1) ?: startMinute?.plus(120)

    private fun parseMinute(raw: String, index: Int): Int? {
        val segs = raw.split(Regex("-+")).map { it.trim() }.filter { it.isNotEmpty() }
        val seg = segs.getOrNull(index) ?: return null
        val m = Regex("(\\d{1,2}):(\\d{2})").find(seg) ?: return null
        val h = m.groupValues[1].toIntOrNull() ?: return null
        val min = m.groupValues[2].toIntOrNull() ?: return null
        if (h !in 0..23 || min !in 0..59) return null
        return h * 60 + min
    }
}

/** 首页聚合数据 */
data class HomeData(
    val semester: Semester,
    val sessions: List<CourseSession>,
    val power: PowerInfo?,
    val today: LocalDate = LocalDate.now(),
)
