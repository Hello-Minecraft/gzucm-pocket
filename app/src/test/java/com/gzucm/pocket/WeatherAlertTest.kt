package com.gzucm.pocket

import com.gzucm.pocket.core.WeatherAlert
import com.gzucm.pocket.core.WeatherSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * 每日天气提醒的文案。
 *
 * 这些句子是产品一条条定下来的（用户要的就是「今天比昨天冷几度」），
 * 所以这里断言的是**完整字符串**，不是 contains —— 少一个「度」、把「冷一点」
 * 写成「冷 1 度」，都是需要被测试挡下来的改动。
 */
class WeatherAlertTest {

    // ── 温差分档 ────────────────────────────────────────────────────

    @Test
    fun `骤降八度要叫人添衣服`() {
        assertEquals("今天比昨天冷 8 度，记得添件衣服", WeatherAlert.diffText(-8))
    }

    @Test
    fun `小幅降温三度只说冷一点`() {
        assertEquals("今天比昨天冷一点，差 3 度", WeatherAlert.diffText(-3))
    }

    @Test
    fun `温度和昨天持平`() {
        assertEquals("今天和昨天差不多", WeatherAlert.diffText(0))
    }

    @Test
    fun `小幅升温三度说暖一点`() {
        assertEquals("今天比昨天暖一点，高 3 度", WeatherAlert.diffText(3))
    }

    @Test
    fun `骤升八度叫少穿一件`() {
        assertEquals("今天比昨天热 8 度，少穿一件", WeatherAlert.diffText(8))
    }

    /** 分档边界：-5 已经算「骤降」，+5 已经算「骤升」，不能落在「一点」那档里 */
    @Test
    fun `温差分档的边界`() {
        assertEquals("今天比昨天冷 5 度，记得添件衣服", WeatherAlert.diffText(-5))
        assertEquals("今天比昨天冷一点，差 4 度", WeatherAlert.diffText(-4))
        assertEquals("今天比昨天暖一点，高 4 度", WeatherAlert.diffText(4))
        assertEquals("今天比昨天热 5 度，少穿一件", WeatherAlert.diffText(5))
    }

    // ── 早晨文案：合并规则 ──────────────────────────────────────────

    @Test
    fun `高温又下雨时两句合并且不超过两句`() {
        // 36 度 + 雷阵雨：雨排在高前面，两句正好，低温那句不该出现
        val text = WeatherAlert.morningText(todayMax = 36, todayMin = 26, todayText = "雷阵雨", yesterdayMax = null)
        assertEquals("今天有雨，记得带伞，今天最高 36 度，注意防暑", text)
        assertFalse("高温已经用掉了第二条，低温不能再挤进来", text.contains("注意保暖"))
    }

    @Test
    fun `雨优先于高温和低温`() {
        // 同时满足三条时，按 雨 > 高温 > 低温 只取前两条
        val text = WeatherAlert.morningText(todayMax = 36, todayMin = 2, todayText = "小雨", yesterdayMax = null)
        assertEquals("今天有雨，记得带伞，今天最高 36 度，注意防暑", text)
    }

    @Test
    fun `有昨天数据时温差永远是主句`() {
        // 昨天 20 度、今天 12 度 → d = -8；另外还有雨
        val text = WeatherAlert.morningText(todayMax = 12, todayMin = 8, todayText = "小雨", yesterdayMax = 20)
        assertEquals("今天比昨天冷 8 度，记得添件衣服，还有雨记得带伞", text)
    }

    @Test
    fun `昨天没数据就只讲高温`() {
        val text = WeatherAlert.morningText(todayMax = 36, todayMin = 26, todayText = "晴", yesterdayMax = null)
        assertEquals("今天最高 36 度，注意防暑", text)
    }

    @Test
    fun `低温单独一档`() {
        assertEquals(
            "今天最低 3 度，注意保暖",
            WeatherAlert.morningText(todayMax = 10, todayMin = 3, todayText = "晴", yesterdayMax = null),
        )
    }

    @Test
    fun `什么条件都不满足也要说一句今天多少度`() {
        // 昨天没数据、不下雨、不冷不热 —— 开关开着不该弹一条空通知
        val text = WeatherAlert.morningText(todayMax = 24, todayMin = 16, todayText = "多云", yesterdayMax = null)
        assertEquals("今天 16~24 度，多云", text)
    }

    @Test
    fun `温差为 0 时不再追加第二条`() {
        val text = WeatherAlert.morningText(todayMax = 20, todayMin = 14, todayText = "晴", yesterdayMax = 20)
        assertEquals("今天和昨天差不多", text)
    }

    // ── 晚上：只在雨雪时发 ──────────────────────────────────────────

    @Test
    fun `明天下雨就提醒带伞`() {
        assertEquals("明天有雨，记得带伞", WeatherAlert.eveningText("小雨"))
        assertEquals("明天有雨，记得带伞", WeatherAlert.eveningText("雷阵雨"))
    }

    @Test
    fun `明天有雪要说雪`() {
        assertEquals("明天有雪，记得带伞", WeatherAlert.eveningText("阵雪"))
        assertEquals("明天有雪，记得带伞", WeatherAlert.eveningText("雨夹雪"))
    }

    @Test
    fun `明天不下雨下雪就静默`() {
        assertNull(WeatherAlert.eveningText("晴"))
        assertNull(WeatherAlert.eveningText("多云"))
        assertNull(WeatherAlert.eveningText("阴"))
    }

    // ── 下一次触发时刻 ──────────────────────────────────────────────

    @Test
    fun `今天还没到点就排今天`() {
        val now = LocalDateTime.of(2026, 11, 26, 7, 30)
        assertEquals(
            LocalDateTime.of(2026, 11, 26, 8, 0),
            WeatherAlert.nextTrigger("08:00", now),
        )
    }

    @Test
    fun `已经过点就顺延到明天`() {
        val now = LocalDateTime.of(2026, 11, 26, 23, 0)
        assertEquals(
            LocalDateTime.of(2026, 11, 27, 22, 0),
            WeatherAlert.nextTrigger("22:00", now),
        )
    }

    @Test
    fun `时间格式坏了不排`() {
        assertNull(WeatherAlert.nextTrigger("25:99", LocalDateTime.of(2026, 11, 26, 7, 0)))
        assertNull(WeatherAlert.nextTrigger("早上八点", LocalDateTime.of(2026, 11, 26, 7, 0)))
    }

    // ── 快照保留窗口 ────────────────────────────────────────────────

    @Test
    fun `快照只留最近七天`() {
        val today = LocalDate.of(2026, 11, 26)
        // 从今天往前 10 天各一条
        val keys = (0..9).map { "wx_snap_" + today.minusDays(it.toLong()) }
        val dead = WeatherSnapshot.expiredKeys(keys, today)

        // 今天 + 前 6 天 = 7 条保留，第 7 天以前的 3 条清掉
        assertEquals(3, dead.size)
        assertTrue(dead.contains("wx_snap_" + today.minusDays(7)))
        assertTrue(dead.contains("wx_snap_" + today.minusDays(9)))
        assertFalse(dead.contains("wx_snap_" + today.minusDays(6)))
    }

    @Test
    fun `解析不出来的快照 key 一并清掉`() {
        val dead = WeatherSnapshot.expiredKeys(
            listOf("wx_snap_2026-11-26", "wx_snap_bad"),
            LocalDate.of(2026, 11, 26),
        )
        assertEquals(listOf("wx_snap_bad"), dead)
    }
}
