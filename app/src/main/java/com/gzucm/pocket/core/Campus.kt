package com.gzucm.pocket.core

import kotlinx.serialization.Serializable

/**
 * 学段。
 *
 * 广中医的两套系统是分开的：
 *   · 本科 `jw.gzucm.edu.cn`（金智 + layui），按周抓课表，学期码 `202601`
 *   · 研究生 `yjsglxt.gzucm.edu.cn`（ASP.NET + EasyUI），一次拿全学期，学期码 `48`
 *
 * 登录、学期码、成绩口径、课表接口全都不一样，所以用这个枚举区分。
 * **作息时间相同** —— 两边都用同一张 [SlotTimes]。
 */
@Serializable
enum class Campus(val label: String) {
    UNDERGRAD("本科生"),
    GRADUATE("研究生"),
    ;

    companion object {
        /** 从存储读回来，认不出的一律当本科（老用户的数据都是本科） */
        fun parse(raw: String?): Campus =
            entries.firstOrNull { it.name == raw } ?: UNDERGRAD
    }
}

/**
 * 广中医作息。每节 40 分钟，课间 10 分钟。**本硕共用。**
 *
 * 节次编号是**连续**的 1–14。之前误以为跳号（没有 5、10、11）——
 * 那是「自己的课表里恰好没有这些节次的课」，不是系统没有。
 * 全校课表里 5 节 155 条、10 节 741 条、11 节 221 条（第 8 周实测）。
 * 研究生接口 `py_kbcx_ew1` 也返回 14 行，进一步印证。
 *
 * 时间来自教务周课表页的 businessHours，并与课程接口的 qssj 交叉核对：
 * 第 5 节 13:10、第 10 节 17:20 两处吻合；
 * 10/11 节再用「40 分钟课 + 10 分钟课间」推链验证：
 * 16:30(9) → 17:20(10) → 18:10(11) → 19:00(12，已确认)。
 *
 * 大课间：第 2 节 10:00 下课、第 3 节 10:20 上课，中间 20 分钟。
 */
val SlotTimes: Map<Int, String> = mapOf(
    1 to "08:30", 2 to "09:20", 3 to "10:20", 4 to "11:10", 5 to "13:10",
    6 to "14:00", 7 to "14:50", 8 to "15:40", 9 to "16:30", 10 to "17:20",
    11 to "18:10", 12 to "19:00", 13 to "19:50", 14 to "20:40",
)

/**
 * 下课时间 = 上课时间 + 40 分钟。
 *
 * 从 [SlotTimes] 推出来而不是手写一份，作息改了不会两边对不上。
 * 第 14 节 20:40 + 40 = 21:20，与「晚上 21:20 结束」一致。
 */
val SlotEndTimes: Map<Int, String> = SlotTimes.mapValues { (_, hhmm) ->
    val parts = hhmm.split(":")
    val h = parts.getOrNull(0)?.toIntOrNull()
    val m = parts.getOrNull(1)?.toIntOrNull()
    if (h == null || m == null) "" else {
        val total = h * 60 + m + 40
        "%02d:%02d".format((total / 60) % 24, total % 60)
    }
}

/** 全部可能的节次编号（连续 1..14） */
val ALL_UNITS: List<Int> = SlotTimes.keys.sorted()

/**
 * 从节次编号找行号（0 基数）。
 *
 * 现在节次是连续的，等价于 unit - 1，但保留这个函数 ——
 * 万一以后哪个学段跳号，改这一处就够，不用满项目找 `unit - 1`。
 */
fun rowIndexOf(unit: Int): Int = ALL_UNITS.indexOf(unit)

/** 一节课的起止时间：优先用数据自带的，没有就按节次表补 */
fun timesOf(session: CourseSession): Pair<String, String> {
    if (session.startTime.isNotBlank() && session.endTime.isNotBlank()) {
        return session.startTime to session.endTime
    }
    return (SlotTimes[session.startUnit] ?: "") to (SlotEndTimes[session.endUnit] ?: "")
}

/** 一节课占几个节次（1-4 节连上） */
fun spanOf(session: CourseSession): Int {
    val a = rowIndexOf(session.startUnit)
    val b = rowIndexOf(session.endUnit)
    if (a < 0 || b < 0 || b < a) return 1
    return b - a + 1
}