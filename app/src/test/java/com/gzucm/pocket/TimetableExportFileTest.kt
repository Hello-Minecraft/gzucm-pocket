package com.gzucm.pocket

import android.content.Intent
import com.gzucm.pocket.core.CourseSession
import com.gzucm.pocket.core.Semester
import com.gzucm.pocket.core.TimetableExport
import com.gzucm.pocket.core.TimetableExportFile
import com.gzucm.pocket.core.buildTimetableExport
import com.gzucm.pocket.core.timetableExportFileName
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset

/**
 * 导出文件的写盘与分享（2026-11-18，致辉：「太长就导出为 json，发文件就可以」）。
 *
 * 这条链路上有三处只能靠真机/框架才能暴露的坑，单测逐条钉住：
 *
 * 1. **文件必须是能解析回来的明文 JSON** —— 别写成分享串那套压缩数据；
 * 2. **URI 必须来自 FileProvider**（`file://` 在 Android 7+ 会抛 FileUriExposedException）；
 * 3. **分享 Intent 必须带读权限**，否则接收方打开是 0 字节
 *    （老版本微信只认 ClipData 里的授权，这条最容易漏）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TimetableExportFileTest {

    private val semester = Semester.of(
        code = "202601",
        name = "2026-2027 学年 第一学期",
        firstMonday = LocalDate.of(2026, 10, 26),
        totalWeeks = 18,
    )

    private fun session(week: Int, content: String = "") = CourseSession(
        name = "中医内科学",
        code = "101227271",
        teachers = "李老师",
        week = week,
        weekday = 1,
        startUnit = 1,
        endUnit = 2,
        startTime = "08:30",
        endTime = "10:00",
        room = "新课室楼302",
        category = "理论教学",
        content = content,
    )

    private fun doc(includeContents: Boolean = true) = buildTimetableExport(
        semester = semester,
        sessions = listOf(session(1, "咳嗽"), session(2), session(3, "哮病")),
        loadedWeeks = (1..18).toSet(),
        versionName = "1.3.6",
        versionCode = 10306,
        now = OffsetDateTime.of(2026, 11, 18, 0, 42, 0, 0, ZoneOffset.ofHours(8)),
        includeContents = includeContents,
    )

    @Test
    fun `文件名带分钟级时间戳`() {
        val name = timetableExportFileName(
            OffsetDateTime.of(2026, 11, 18, 0, 42, 0, 0, ZoneOffset.ofHours(8)),
        )
        assertEquals("广中医口袋课表-20261118-0042.json", name)
    }

    @Test
    fun `写出来的文件是能原样解析回来的明文 JSON`() {
        val ctx = RuntimeEnvironment.getApplication()
        val original = doc(includeContents = true)

        val file = TimetableExportFile.write(ctx, original)

        assertTrue("文件要落在 cache/exports 下", file.exists())
        assertTrue(file.path.replace('\\', '/').contains("/cache/exports/"))
        assertEquals("文件名要带 .json", true, file.name.endsWith(".json"))

        // 明文：开头是 `{`，不是 GZP1: 那种压缩串
        val text = file.readText()
        assertTrue("文件应是明文 JSON", text.trimStart().startsWith("{"))
        assertTrue("缩进版要带换行", text.contains("\n"))

        val back = com.gzucm.pocket.core.TimetableExportJson
            .decodeFromString(TimetableExport.serializer(), text)
        assertEquals(original.courses.size, back.courses.size)
        assertEquals(original.contents.size, back.contents.size)
        assertEquals("文件形态要带上逐周内容", 2, back.contents.size)
        assertEquals(listOf(1, 3), back.contents.map { it.week })
    }

    @Test
    fun `文件落在 cache 的 exports 目录里`() {
        val ctx = RuntimeEnvironment.getApplication()
        val file = TimetableExportFile.write(ctx, doc())

        val expectedDir = java.io.File(ctx.cacheDir, "exports").absolutePath
        assertEquals(
            "FileProvider 的 <cache-path path=\"exports/\"> 认的是这个位置，挪了就对不上",
            expectedDir,
            file.parentFile?.absolutePath,
        )
    }

    /**
     * FileProvider 的映射靠两半：文件在 `cache/exports/`、`file_paths.xml` 声明了这个目录。
     * 两边都对上，真机上 `getUriForFile` 就能算出 URI。
     *
     * 为什么不直接调 `getUriForFile` 断言 URI：androidx.core 1.13.1 的
     * `belongsToRoot` 把 `/` 写死了，Windows 上路径是 `\`，必然匹配失败
     * （Robolectric 跑在 Windows JVM 里）。这是测试环境的限制，不是线上问题，
     * 详见 `TimetableExportFile.uriOf` 的注释。
     */
    @Test
    fun `file_paths 声明了 exports 目录且 provider 用得上`() {
        val ctx = RuntimeEnvironment.getApplication()

        /*
         * 收集**全部**映射再断言，不要只留最后一个。
         *
         * 2026-11-23 加「应用内更新」的 updates/ 之后，这里原来「取最后一个元素」
         * 的写法立刻红了 —— 不是映射错了，是测试假设了文件里只有一个 path。
         */
        val xml = ctx.resources.getXml(com.gzucm.pocket.R.xml.file_paths)
        val entries = mutableListOf<Triple<String, String, String>>()
        var event = xml.eventType
        while (event != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
            if (event == org.xmlpull.v1.XmlPullParser.START_TAG) {
                entries += Triple(
                    xml.name,
                    xml.getAttributeValue(null, "name").orEmpty(),
                    xml.getAttributeValue(null, "path").orEmpty(),
                )
            }
            event = xml.next()
        }
        xml.close()

        assertTrue(
            "file_paths 里必须有 exports 映射（导出表格分享要用）",
            entries.any { it.first == "cache-path" && it.second == "exports" && it.third == "exports/" },
        )
        assertTrue(
            "file_paths 里必须有 updates 映射（应用内更新装包要用）",
            entries.any { it.first == "cache-path" && it.second == "updates" && it.third == "updates/" },
        )

        // 清单里 provider 的 authority 必须和代码里算的一致，否则分享时找不到 provider
        val pi = ctx.packageManager.resolveContentProvider(
            "${ctx.packageName}.fileprovider", 0,
        )
        assertNotNull("Manifest 里没声明 FileProvider", pi)
        assertEquals("androidx.core.content.FileProvider", pi!!.name)
        assertTrue("必须允许临时授权，否则接收方读不到文件", pi.grantUriPermissions)
        assertEquals("默认应该是私有 provider", false, pi.exported)
        assertEquals(TimetableExportFile.authority(ctx), pi.authority)
    }

    @Test
    fun `分享意图带读权限和 json 类型`() {
        val ctx = RuntimeEnvironment.getApplication()
        val file = TimetableExportFile.write(ctx, doc())
        val uri = android.net.Uri.parse("content://${TimetableExportFile.authority(ctx)}/exports/x.json")

        val chooser = TimetableExportFile.shareIntent(file.name, uri)

        // 对外的是选择器，真正的 ACTION_SEND 在 EXTRA_INTENT 里
        assertEquals(Intent.ACTION_CHOOSER, chooser.action)
        assertTrue(
            "从非 Activity 上下文启动，必须带 NEW_TASK",
            chooser.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0,
        )

        @Suppress("DEPRECATION")
        val send = chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
        assertNotNull("选择器里要有 ACTION_SEND", send)
        send!!
        assertEquals(Intent.ACTION_SEND, send.action)
        assertEquals("application/json", send.type)
        assertEquals("主题要带文件名", file.name, send.getStringExtra(Intent.EXTRA_SUBJECT))
        assertTrue(
            "没给读权限，接收方拿到的是读不了的空文件",
            send.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0,
        )
        @Suppress("DEPRECATION")
        val stream = send.getParcelableExtra<android.net.Uri>(Intent.EXTRA_STREAM)
        assertEquals(uri, stream)
        // 老版本微信只从 ClipData 里读授权，这里必须也有
        assertEquals(stream, send.clipData?.getItemAt(0)?.uri)
    }

    @Test
    fun `拍照只留最新一份，别把 cache 堆满`() {
        val ctx = RuntimeEnvironment.getApplication()
        val first = TimetableExportFile.write(ctx, doc(), name = "广中医口袋课表-20261118-0040.json")
        val second = TimetableExportFile.write(ctx, doc(), name = "广中医口袋课表-20261118-0042.json")

        TimetableExportFile.prune(ctx, keep = second)

        assertTrue("最新那份要留着（接收方可能还在读）", second.exists())
        assertTrue("上一份要清掉", !first.exists())
    }
}
