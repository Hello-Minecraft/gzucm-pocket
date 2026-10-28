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

    @Test
    fun `版本号按从新到旧排列`() {
        val vers = ReleaseNotes.entries.map { it.version }
        val sorted = vers.sortedWith(compareBy({ it.split(".")[0].toInt() }, { it.split(".")[1].toInt() }, { it.split(".")[2].toInt() }))
            .reversed()
        assertEquals(sorted, vers)
    }

    @Test
    fun `版本号三段格式`() {
        ReleaseNotes.entries.forEach {
            assertTrue("${it.version} 应为 主.次.修订", Regex("""\d+\.\d+\.\d+""").matches(it.version))
        }
    }
}
