package com.gzucm.pocket.core

/**
 * 加权学分绩点的口径。
 *
 * 教务只给每门课的绩点（cjjd）和学分（xf），**没有汇总接口**，
 * 所以这里要自己定口径。学校评优/保研用的是「必修 + 限选」，任选不计。
 *
 * 大类名有坑：「通识课」里装的是体育(3)、毛概、大学英语(3)、创新创业基础，
 * 都是必修。所以不能按「通识」二字排除，只认「选修且非限选」。
 */
object Gpa {

    /** 这门课是否计入加权绩点 */
    fun counted(category: String): Boolean {
        val c = category.trim()
        if (c.isEmpty()) return true
        // 限选要算（专业限选课），任选不算（公共选修课 / 任选课）
        if (c.contains("限选")) return true
        if (c.contains("选修") || c.contains("任选")) return false
        return true
    }

    /** Σ(绩点×学分) / Σ学分；只统计及格且计入口径的课 */
    fun weighted(grades: List<CourseGrade>): Pair<Double, Double> {
        val list = grades.filter { it.passed && counted(it.category) && it.credit > 0 }
        val credit = list.sumOf { it.credit }
        if (credit <= 0.0) return 0.0 to 0.0
        return list.sumOf { it.gpa * it.credit } / credit to credit
    }
}
