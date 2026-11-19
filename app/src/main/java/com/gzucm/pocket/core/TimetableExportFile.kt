package com.gzucm.pocket.core

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File

/**
 * 课表导出的「文件形态」。
 *
 * ## 为什么要有它（2026-11-18，致辉定）
 *
 * 分享串已经是压缩过的（约 1300 字符），但那是硬挤出来的：为了塞进微信一条
 * 文本消息，逐周上课内容被砍掉了。**文件没有长度限制**，所以同一份数据
 * 写成 `.json` 时把内容都带上，网页那边拿到的是完整版。
 *
 * 致辉的原话：「太长就导出为 json，微信发不出去？发文件就可以。」
 *
 * ## 为什么不写外部存储
 *
 * 写 `cacheDir/exports/`，再由 [FileProvider] 换成 `content://` 交给系统分享。
 * 好处是不用申请任何存储权限（分区存储之后写公共目录很麻烦），
 * 文件也不会在用户目录里堆积 —— cache 由系统按需回收。
 *
 * 注意：Android 7 起 `file://` URI 直接分享会抛 [android.os.FileUriExposedException]，
 * 所以必须走 FileProvider，见 `AndroidManifest.xml` 里的 provider 声明。
 */
object TimetableExportFile {

    /** 与 `res/xml/file_paths.xml` 里 `<cache-path path="exports/">` 对应 */
    private const val DIR = "exports"

    /** 与 Manifest 里 `${applicationId}.fileprovider` 对应 */
    fun authority(context: Context): String = "${context.packageName}.fileprovider"

    /**
     * 把导出文档写成缩进 JSON 文件，返回文件句柄。
     *
     * 用 [TimetableExportJson]（缩进两格）而不是紧凑版：文件是给人看、给网页解析的，
     * 出问题时能直接打开读，几 KB 的空白不值当省。
     */
    fun write(
        context: Context,
        doc: TimetableExport,
        name: String = timetableExportFileName(),
    ): File {
        val dir = File(context.cacheDir, DIR).apply { mkdirs() }
        val file = File(dir, name)
        file.writeText(
            TimetableExportJson.encodeToString(TimetableExport.serializer(), doc),
            Charsets.UTF_8,
        )
        return file
    }

    /**
     * 文件 → `content://` URI。
     *
     * ⚠️ **androidx.core 1.13.1 在 Windows 上有已知缺陷**：
     * `SimplePathStrategy.belongsToRoot` 拼的是 `root + "/"`，正斜杠写死，
     * 而 Windows 的 `File.getPath()` 用反斜杠 → 永远匹配不上，抛
     * 「Failed to find configured root that contains …」。
     * Robolectric 单测跑在 Windows JVM 上撞的就是这个；**Android 上分隔符本来就是
     * `/`，真机不受影响**（这也是为什么不能因为测试红就去改实现）。
     *
     * 所以单测改成验「文件落在 cache/exports/」+「file_paths.xml 声明了这个目录」，
     * 两边对上，真机上 FileProvider 自然算得出 URI。
     */
    fun uriOf(context: Context, file: File): Uri =
        FileProvider.getUriForFile(context, authority(context), file)

    /**
     * 系统分享面板的 Intent（微信 / QQ / 网盘 / 邮件都在里面挑）。
     *
     * 三处细节都是有原因的：
     * - `type = application/json`：微信、QQ 这类都注册了 `application/*`，
     *   所以会出现在选择器里；用 `*/*` 反而会带出一堆不相干的接收方。
     * - 同时写 `EXTRA_STREAM` 和 `ClipData`：有些接收方（含旧版微信）只从
     *   ClipData 里读授权，光靠 Intent flag 会拿到一个读不了的 URI，
     *   表现为「发送失败」或发过去是 0 字节。
     * - `FLAG_ACTIVITY_NEW_TASK`：Compose 里拿到的 Context 不保证是 Activity，
     *   不加这个在部分机型上会直接崩 `AndroidRuntimeException`。
     */
    fun shareIntent(context: Context, file: File, title: String = "发送课表文件"): Intent =
        shareIntent(file.name, uriOf(context, file), title)

    /**
     * 真正拼 Intent 的那一半，把 URI 当参数收。
     *
     * 拆开是为了能在单测里断言 type / flag / ClipData 这些**必须对**的东西：
     * `FileProvider.getUriForFile` 在 Windows 上跑不通（见 [uriOf] 的说明），
     * 而这几项配错的表现是「发过去 0 字节」这种很难查的问题。
     */
    fun shareIntent(
        name: String,
        uri: Uri,
        title: String = "发送课表文件",
    ): Intent {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "application/json"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, name)
            clipData = ClipData.newRawUri(name, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return Intent.createChooser(send, title).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    /**
     * 清掉历史导出文件，只留 [keep]。
     *
     * 每点一次「导出文件」就落一个几 KB~几十 KB 的文件，不清就会一直堆在 cache 里。
     * 分享面板的接收方是异步读的（微信可能过几秒才真正读），所以**不能立刻删**，
     * 只清上一次留下的。
     */
    fun prune(context: Context, keep: File?) {
        val dir = File(context.cacheDir, DIR)
        dir.listFiles()?.forEach { f ->
            if (f != keep && f.isFile) f.delete()
        }
    }
}

/**
 * 导出文件名：`广中医口袋课表-20261118-0042.json`
 *
 * 带时分是为了同一天导多次不撞名 —— 撞名后接收方会显示成「课表(1).json」，
 * 或者干脆问要不要覆盖，很碍事。
 *
 * 时区取本地：文件名是给人看的，不参与任何计算。
 */
fun timetableExportFileName(now: java.time.OffsetDateTime = java.time.OffsetDateTime.now()): String =
    "广中医口袋课表-" +
        now.format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmm")) +
        ".json"
