package com.gzucm.pocket

import com.gzucm.pocket.ui.components.Fmt
import org.junit.Assert.assertEquals
import org.junit.Test

/** 数字格式化：既要快，也不能丢精度。 */
class FmtTest {

    @Test
    fun `两位小数不截断`() {
        assertEquals("11.78", Fmt.trim2(11.78))
        assertEquals("0.18", Fmt.trim2(0.18))
        assertEquals("69.21", Fmt.trim2(69.21))
    }

    @Test
    fun `整数不带小数点`() {
        assertEquals("12", Fmt.trim2(12.0))
        assertEquals("5", Fmt.trim2(5.0))
        assertEquals("0", Fmt.trim2(0.0))
    }

    @Test
    fun `一位小数用于学分绩点`() {
        assertEquals("3.7", Fmt.trim1(3.70))
        assertEquals("2", Fmt.trim1(2.0))
        assertEquals("87", Fmt.trim1(87.0))
        assertEquals("86.5", Fmt.trim1(86.5))
    }

    @Test
    fun `固定小数补零`() {
        assertEquals("1.50", Fmt.d2(1.5))
        assertEquals("0.05", Fmt.d2(0.05))
        assertEquals("3.0", Fmt.d1(3.0))
        assertEquals("81.8", Fmt.d1(81.83))
    }

    @Test
    fun `trim 版整数不留小数点`() {
        assertEquals("3", Fmt.trim1(3.0))
        assertEquals("20.5", Fmt.trim1(20.5))
        assertEquals("88", Fmt.trim2(88.0))
    }

    @Test
    fun `负数与小数进位`() {
        assertEquals("-11.78", Fmt.trim2(-11.78))
        assertEquals("1.24", Fmt.trim2(1.235))  // 四舍五入
        assertEquals("0.13", Fmt.trim2(0.125))
    }

    @Test
    fun `异常值不崩`() {
        assertEquals("-", Fmt.trim2(Double.NaN))
        assertEquals("-", Fmt.trim2(Double.POSITIVE_INFINITY))
        assertEquals("-", Fmt.d1(Double.NaN))
    }

    @Test
    fun `格式化结果与 String_format 一致`() {
        // 抽查一批真实量级的值，确保与旧实现输出相同
        val samples = listOf(0.0, 0.18, 1.08, 2.88, 11.78, 69.21, 74.04, 86.5, 100.0, 3.7)
        samples.forEach { v ->
            assertEquals("v=$v", "%.2f".format(v), Fmt.d2(v))
        }
    }
}