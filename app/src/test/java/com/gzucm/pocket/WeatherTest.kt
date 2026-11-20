package com.gzucm.pocket

import com.gzucm.pocket.core.Weather
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * 天气解析与节流。
 *
 * 这里的 JSON 都是和风 v7 的真实形状（字段名、字符串数字、`code` 也在 body 里），
 * 只把数值改小了一点。**不要**改成手写的简化结构 —— 那样测的是自己的想象，
 * 接口一换字段名测试还是绿的。
 */
class WeatherTest {

    private val nowJson = """
        {"code":"200","updateTime":"2026-11-20T13:20+08:00","fxLink":"https://www.qweather.com",
         "now":{"obsTime":"2026-11-20T13:15+08:00","temp":"14","feelsLike":"11","icon":"104",
         "text":"阴","wind360":"20","windDir":"东北风","windScale":"2","windSpeed":"9",
         "humidity":"63","precip":"0.0","pressure":"1016","vis":"18","cloud":"95","dew":"7"}}
    """.trimIndent()

    private val weekJson = """
        {"code":"200","updateTime":"2026-11-20T13:20+08:00",
         "daily":[
          {"fxDate":"2026-11-20","sunrise":"06:45","sunset":"17:42","tempMax":"18","tempMin":"12",
           "iconDay":"104","textDay":"阴","iconNight":"150","textNight":"晴","humidity":"65",
           "precip":"0.0","pressure":"1016","uvIndex":"4"},
          {"fxDate":"2026-11-21","sunrise":"06:46","sunset":"17:42","tempMax":"17","tempMin":"11",
           "iconDay":"305","textDay":"小雨","iconNight":"305","textNight":"小雨","humidity":"80",
           "precip":"3.2","pressure":"1014","uvIndex":"2"}
         ]}
    """.trimIndent()

    @Test
    fun `实时天气能解析出温度体感湿度`() {
        val info = Weather.parseNow(nowJson, fetchedAt = 1_000L)
        requireNotNull(info)
        assertEquals(14, info.temp)
        assertEquals(11, info.feelsLike)
        assertEquals(63, info.humidity)
        assertEquals("阴", info.text)
        assertEquals(Weather.Kind.Overcast, info.kind)
        assertEquals(1_000L, info.fetchedAt)
    }

    @Test
    fun `接口报错时返回 null 而不是半条数据`() {
        // 和风鉴权失败就是 code=401、没有 now 字段
        assertNull(Weather.parseNow("""{"code":"401"}""", 0L))
        // 字段缺失
        assertNull(Weather.parseNow("""{"code":"200","now":{}}""", 0L))
        // 根本不是 JSON
        assertNull(Weather.parseNow("<html>502 Bad Gateway</html>", 0L))
    }

    @Test
    fun `七天预报能解析并带上降水与湿度`() {
        val days = Weather.parseDays(weekJson)
        assertEquals(2, days.size)
        assertEquals(LocalDate.of(2026, 11, 20), LocalDate.parse(days[0].date))
        assertEquals(18, days[0].max)
        assertEquals(12, days[0].min)
        assertEquals(65, days[0].humidity)
        assertEquals(3.2, days[1].precip, 0.001)
        assertEquals(Weather.Kind.Rain, Weather.kindOf(days[1].icon))
    }

    @Test
    fun `解析不了的预报返回空表而不是抛异常`() {
        assertTrue(Weather.parseDays("not json").isEmpty())
        assertTrue(Weather.parseDays("""{"code":"401"}""").isEmpty())
    }

    /**
     * 天气现象码分段：1xx 晴到阴、3xx 雨、4xx 雪、5xx 雾霾。
     * 302~304 是雷阵雨，**必须先于 3xx 命中**，否则雷阵雨会被画成普通雨。
     */
    @Test
    fun `天气码映射到图形种类`() {
        assertEquals(Weather.Kind.Sunny, Weather.kindOf("100"))
        assertEquals(Weather.Kind.Sunny, Weather.kindOf("150"))
        assertEquals(Weather.Kind.Cloudy, Weather.kindOf("101"))
        assertEquals(Weather.Kind.Cloudy, Weather.kindOf("103"))
        assertEquals(Weather.Kind.Overcast, Weather.kindOf("104"))
        assertEquals(Weather.Kind.Storm, Weather.kindOf("302"))
        assertEquals(Weather.Kind.Storm, Weather.kindOf("304"))
        assertEquals(Weather.Kind.Rain, Weather.kindOf("305"))
        assertEquals(Weather.Kind.Rain, Weather.kindOf("318"))
        assertEquals(Weather.Kind.Snow, Weather.kindOf("400"))
        assertEquals(Weather.Kind.Fog, Weather.kindOf("501"))
        assertEquals(Weather.Kind.Unknown, Weather.kindOf("999"))
        assertEquals(Weather.Kind.Unknown, Weather.kindOf(""))
        assertEquals(Weather.Kind.Unknown, Weather.kindOf("abc"))
    }

    /**
     * 最多 1 次/小时（致辉 2026-11-20 定）。
     *
     * 边界要卡死：差 1 毫秒都不行。这条测试的意义是防止有人把
     * `>=` 改成 `>` 或把常量从 60 分钟调成 30 分钟却不改文档 ——
     * 免费额度是有限的，多打一次就是一次。
     */
    @Test
    fun `刷新节流刚好一小时`() {
        val hour = 60L * 60L * 1000L
        assertFalse(Weather.canRefresh(lastAt = 0L, now = hour - 1))
        assertTrue(Weather.canRefresh(lastAt = 0L, now = hour))
        assertEquals(1L, Weather.waitMs(lastAt = 0L, now = hour - 1))
        assertEquals(0L, Weather.waitMs(lastAt = 0L, now = hour + 5_000))
        // 从没拉过（fetchedAt=0）时，真实时间戳远大于一小时 → 立刻放行
        assertTrue(Weather.canRefresh(lastAt = 0L, now = 1_760_000_000_000L))
    }

    @Test
    fun `超过六小时的缓存不再显示`() {
        val hour = 60L * 60L * 1000L
        assertTrue(Weather.isFresh(lastAt = 0L, now = 5 * hour))
        assertTrue(Weather.isFresh(lastAt = 0L, now = 6 * hour))
        assertFalse(Weather.isFresh(lastAt = 0L, now = 6 * hour + 1))
    }

    @Test
    fun `拼上预报不改变实时那部分`() {
        val now = requireNotNull(Weather.parseNow(nowJson, 7L))
        val days = Weather.parseDays(weekJson)
        val merged = Weather.withDays(now, days)
        assertEquals(14, merged.temp)
        assertEquals(7L, merged.fetchedAt)
        assertEquals(2, merged.days.size)
    }
}
