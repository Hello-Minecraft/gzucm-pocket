package com.gzucm.pocket.ui.components

import java.util.Locale

/**
 * 轻量数字格式化。
 *
 * `String.format("%.2f", v)` 每次调用都会新建一个 Formatter 并走正则解析，
 * 单次开销在微秒级。但成绩页一屏十几行、每行调用 3~4 次，
 * 滚动时每帧就是几十次分配 —— 这是 Compose 列表卡顿的常见来源。
 *
 * 这里用整数运算拼字符串，零 Formatter、零正则、零临时数组。
 */
object Fmt {

    /** 固定 1 位小数（和 d2 一样总是带小数点，用于对齐显示） */
    fun d1(v: Double): String {
        if (v.isNaN() || v.isInfinite()) return "-"
        val neg = v < 0
        val scaled = Math.round(Math.abs(v) * 10).toLong()
        val whole = scaled / 10
        val frac = scaled % 10
        val sb = StringBuilder(8)
        if (neg) sb.append('-')
        sb.append(whole)
        sb.append('.')
        sb.append(frac)
        return sb.toString()
    }

    /** 固定 2 位小数 */
    fun d2(v: Double): String {
        if (v.isNaN() || v.isInfinite()) return "-"
        val neg = v < 0
        val scaled = Math.round(Math.abs(v) * 100).toLong()
        val whole = scaled / 100
        val frac = scaled % 100
        val sb = StringBuilder(9)
        if (neg) sb.append('-')
        sb.append(whole)
        sb.append('.')
        if (frac < 10) sb.append('0')
        sb.append(frac)
        return sb.toString()
    }

    /** 去掉多余的 .0，保留 1 位小数（学分、绩点、单科成绩用） */
    fun trim1(v: Double): String = if (v == v.toLong().toDouble()) v.toLong().toString() else d1(v)

    /** 去掉多余的 .0，保留 2 位小数（电量、金额用 —— 11.78 不能被截成 11.8） */
    fun trim2(v: Double): String = if (v == v.toLong().toDouble()) v.toLong().toString() else d2(v)

    /** 截成两位小数用于图表轴标注，不补零 */
    fun axis(v: Double): String = d1(v)
}
