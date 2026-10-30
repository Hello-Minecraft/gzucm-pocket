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

/** 考试安排 */
data class ExamItem(
    val course: String,
    val date: String,
    val time: String,
    val room: String,
    val seat: String,
    val type: String,
)

/** 首页聚合数据 */
data class HomeData(
    val semester: Semester,
    val sessions: List<CourseSession>,
    val power: PowerInfo?,
    val today: LocalDate = LocalDate.now(),
)
