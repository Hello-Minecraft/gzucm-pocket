package com.gzucm.pocket

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 版本号守卫。
 *
 * v1.0.0 → v1.1.0 那次只改了 APK 文件名，忘了动 build.gradle.kts，
 * 结果安装时还显示 1.0.0。这条测试把「版本号必须和预期一致」钉住，
 * 改版本时它会失败，提醒同步改 versionCode。
 */
class VersionTest {

    @Test
    fun `版本名与期望值一致`() {
        assertEquals("1.2.5", BuildConfig.VERSION_NAME)
        assertEquals(10205, BuildConfig.VERSION_CODE)
    }

    @Test
    fun `版本名是语义化格式`() {
        val parts = BuildConfig.VERSION_NAME.split(".")
        assertEquals("版本名应为 x.y.z 三段", 3, parts.size)
        parts.forEach { assertTrue("每段必须是数字：$it", it.all(Char::isDigit)) }
    }

    @Test
    fun `版本号与版本名一致`() {
        // 约定：versionCode = 主*10000 + 次*100 + 修订
        val parts = BuildConfig.VERSION_NAME.split(".").map { it.toInt() }
        val expectedCode = parts[0] * 10000 + parts[1] * 100 + parts[2]
        assertEquals(
            "versionCode 应为 $expectedCode（由 ${BuildConfig.VERSION_NAME} 推出）",
            expectedCode,
            BuildConfig.VERSION_CODE,
        )
    }
}