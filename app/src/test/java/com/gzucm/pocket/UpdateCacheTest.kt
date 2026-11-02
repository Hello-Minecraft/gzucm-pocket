package com.gzucm.pocket

import com.gzucm.pocket.core.Updater
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 更新信息的缓存与展示逻辑。
 *
 * 这两个问题都是致辉 2026-11-02 报的：
 *
 * ① 设置页显示「检测到新版本」，进关于页却一片空白，要手动点一次检查更新
 * ② 点「在浏览器打开」进的是 GitHub Release 页，没用过 GitHub 的人
 *    看不懂 Assets 折叠区在哪，不知道怎么下载
 *
 * 修法：静默检查时把**整条**信息落盘（不只版本号），关于页进来直接读；
 * 浏览器入口优先给 APK 直链。
 */
class UpdateCacheTest {

    private fun sample(
        version: String = "1.3.1",
        apk: String? = "https://github.com/x/y/releases/download/v1.3.1/a.apk",
        page: String = "https://github.com/x/y/releases/tag/v1.3.1",
    ) = Updater.UpdateInfo(
        latestVersion = version,
        notes = "更新说明",
        pageUrl = page,
        apkUrl = apk,
        sizeMb = 12.2,
    )

    // ── 缓存读写 ──────────────────────────────────────────

    @Test
    fun `存进去再读出来内容一致`() {
        val info = sample()
        val raw = com.gzucm.pocket.core.Net.json.encodeToString(
            Updater.UpdateInfo.serializer(), info,
        )
        val back = Updater.parseCachedInfo(raw, localVersion = "1.3.0")

        assertNotNull("缓存应能解析回来", back)
        assertEquals("1.3.1", back!!.latestVersion)
        assertEquals(12.2, back.sizeMb, 0.001)
        assertEquals(info.apkUrl, back.apkUrl)
        assertEquals(info.notes, back.notes)
    }

    @Test
    fun `本地版本已经追上时缓存作废`() {
        val raw = com.gzucm.pocket.core.Net.json.encodeToString(
            Updater.UpdateInfo.serializer(), sample("1.3.1"),
        )
        assertNull(
            "用户已经装了 1.3.1，不能再提示有新版本",
            Updater.parseCachedInfo(raw, localVersion = "1.3.1"),
        )
        assertNull(
            "本地比缓存还新时同样作废",
            Updater.parseCachedInfo(raw, localVersion = "1.4.0"),
        )
    }

    @Test
    fun `空缓存和坏缓存都返回null不炸`() {
        assertNull(Updater.parseCachedInfo(null, "1.3.0"))
        assertNull(Updater.parseCachedInfo("", "1.3.0"))
        assertNull(Updater.parseCachedInfo("   ", "1.3.0"))
        assertNull(Updater.parseCachedInfo("不是json", "1.3.0"))
        assertNull(Updater.parseCachedInfo("{}", "1.3.0"))
    }

    @Test
    fun `缓存里缺可选字段也能读`() {
        // 老版本 App 存的可能只有版本号，没有 sizeMb / apkUrl
        val minimal = """{"latestVersion":"1.3.1"}"""
        val back = Updater.parseCachedInfo(minimal, localVersion = "1.3.0")
        assertNotNull(back)
        assertEquals("1.3.1", back!!.latestVersion)
        assertEquals(0.0, back.sizeMb, 0.001)
        assertNull(back.apkUrl)
    }

    // ── 浏览器地址选择 ────────────────────────────────────

    @Test
    fun `有APK直链时用直链`() {
        val info = sample()
        assertEquals(
            "点下去应该是下载 APK，不是看网页",
            info.apkUrl,
            Updater.browserUrl(info),
        )
    }

    @Test
    fun `没有APK直链时才退回Release页`() {
        val info = sample(apk = null)
        assertEquals(info.pageUrl, Updater.browserUrl(info))
    }

    @Test
    fun `APK地址是空串也当没有`() {
        val info = sample(apk = "")
        assertEquals("空串不能拿去 openUrl，会打开空白页", info.pageUrl, Updater.browserUrl(info))
    }

    @Test
    fun `APK地址只有空格也当没有`() {
        val info = sample(apk = "   ")
        assertEquals(info.pageUrl, Updater.browserUrl(info))
    }

    // ── 兜底直链 ──────────────────────────────────────────

    /**
     * tag 原样进文件名，**不能把 `v` 去掉**。
     *
     * Release 的附件叫 `gzucm-pocket-v1.3.1.apk`，2026-11-02 这里写成
     * `removePrefix("v")` → 拼出 `gzucm-pocket-1.3.1.apk` → GitHub 404，
     * 用户点「浏览器下载」看到报错页。断言直接写完整 URL，不给它再错的机会。
     */
    @Test
    fun `兜底直链保留tag里的v`() {
        assertEquals(
            "https://github.com/Hello-Minecraft/gzucm-pocket/releases/latest/download/" +
                "gzucm-pocket-v1.3.1.apk",
            Updater.fallbackApkUrl("v1.3.1"),
        )
    }

    @Test
    fun `兜底直链指向latest而不是具体版本`() {
        // latest 路径让老版本 App 永远拿到最新包，不用先问 API 拿版本号
        assertTrue(
            "应该走 releases/latest/download",
            Updater.fallbackApkUrl("v9.9.9").contains("/releases/latest/download/"),
        )
    }
}
