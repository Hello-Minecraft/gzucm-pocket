package com.gzucm.pocket

import com.gzucm.pocket.core.ReleaseNotes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 版本日志的护卫测试。
 *
 * 规矩（2026-10-28 定）：每次发包必须更新日志。
 * 这里有两条兜底：
 *   1. 最新一条的版本号必须和 BuildConfig 一致（防止改了版本忘了写日志）
 *   2. 每条都得有 headline（防止占位空条目）
 */
class ReleaseNotesTest {

    @Test
    fun `最新日志的版本号与当前版本一致`() {
        val latest = ReleaseNotes.entries.first()
        assertEquals(
            "改了版本号就要在 ReleaseNotes 里加一条，否则设置页显示的是旧日志",
            BuildConfig.VERSION_NAME,
            latest.version,
        )
    }

    @Test
    fun `每条日志都有概要`() {
        ReleaseNotes.entries.forEach {
            assertTrue("v${it.version} 的 headline 不能为空", it.headline.isNotBlank())
            assertTrue("v${it.version} 的日期不能为空", it.date.isNotBlank())
        }
    }

    /**
     * 去掉 `-beta` / `-rc1` 这类预发布后缀，只留 x.y.z 用来比大小。
     *
     * 版本名允许带后缀（比如测试包叫 1.2.8-beta），
     * 但排序和格式校验只关心数字那三段。
     */
    private fun coreOf(v: String) = v.substringBefore("-")

    @Test
    fun `版本号按从新到旧排列`() {
        fun key(v: String): List<Int> = coreOf(v).split(".").map { it.toIntOrNull() ?: 0 }
        val vers = ReleaseNotes.entries.map { it.version }
        /*
         * 用降序比较器，不要「升序排完再 reversed()」。
         *
         * `1.5.0-beta4` 和 `1.5.0-beta` 去掉后缀后都是 1.5.0，在 key 上相等；
         * 升序排序是稳定的（保持原顺序），但整体 reversed() 会把这一对也翻过来，
         * 于是测试要求「beta 排在 beta4 前面」—— 而正确的 entries 里 beta4 在前。
         * 降序比较器同样是稳定排序，相等元素保持原顺序，不会自己制造矛盾。
         */
        val sorted = vers.sortedWith(
            compareByDescending<String> { key(it)[0] }
                .thenByDescending { key(it)[1] }
                .thenByDescending { key(it)[2] },
        )
        assertEquals(sorted, vers)
    }

    @Test
    fun `版本号三段格式`() {
        ReleaseNotes.entries.forEach {
            assertTrue(
                "${it.version} 应为 主.次.修订（可带 -beta 后缀）",
                Regex("""\d+\.\d+\.\d+(-[A-Za-z0-9.]+)?""").matches(it.version),
            )
        }
    }
}
