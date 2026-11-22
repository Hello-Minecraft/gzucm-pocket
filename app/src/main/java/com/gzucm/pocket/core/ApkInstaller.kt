package com.gzucm.pocket.core

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

/**
 * 下载新版 APK 并**直接调起系统安装器**。
 *
 * ## 为什么不用 [ApkDownloader]（系统 DownloadManager）
 *
 * 原来是 DownloadManager + 通知栏，装的时候要用户自己去点那条通知。
 * 2026-11-23 有同学反馈「找不到下载的安装包在哪」—— 通知一划就没，
 * 文件在「下载」目录里也未必找得到，整条路对不熟手机的人等于断了。
 *
 * 现在改成：App 内下载 → 下完立刻弹安装器。用户只需要点「安装」。
 *
 * 代价是自己处理进度和断点：没有断点续传，网络断了要重下。
 * 但包只有 9 MB，重下的成本远小于「装不上」。
 *
 * ## 那个「允许安装未知应用」的授权躲不掉
 *
 * Android 8 起，任何非应用商店的安装都要用户手动开一次
 * 「允许这个来源安装应用」。这不是能绕过的限制，只能：
 *   1. 下载前先查（[canInstall]），没开就跳设置页（[openInstallSettings]）
 *   2. 开完回来再点一次下载
 *
 * 之所以「下载前」查而不是「下载后」查：让人白下 9 MB 再告诉他装不了，
 * 比提前说一句难听得多。
 */
object ApkInstaller {

    sealed class Result {
        data class Ok(val file: File) : Result()
        data class Failed(val reason: String) : Result()
    }

    /** 安装器调没调起来。调起来了不代表装成功（用户可能点取消） */
    sealed class InstallResult {
        object Launched : InstallResult()

        /**
         * 安装器起来了，但系统多半已经把这次安装挡掉了。
         *
         * 为什么要单独一个分支：小米/HyperOS 在没开「安装未知应用」时，
         * 只弹一个**一闪而过的 Toast**「已禁止…安装…去设置」，安装器窗口立刻退。
         * 用户看不到就等于没有提示，所以由我们自己补一个明确的按钮。
         */
        object NeedsPermission : InstallResult()

        data class Failed(val reason: String) : InstallResult()
    }

    /**
     * 下载到 `cache/updates/`，边下边回调进度（0f~1f）。
     *
     * **阻塞调用**，必须在 IO 线程里跑。
     *
     * 每次下载前清空目录：同一版本重下时不会出现 `xxx.apk (1)` 这种残骸，
     * 也顺手回收上一次没装成的包（9 MB，攒几次就白占空间）。
     */
    fun download(
        ctx: Context,
        url: String,
        version: String,
        onProgress: (Float) -> Unit = {},
    ): Result {
        val dir = File(ctx.cacheDir, "updates").apply { mkdirs() }
        val target = File(dir, "gzucm-pocket-v$version.apk")

        // 上一次已经下好、只是没装成（比如去开权限了）：直接复用，别重下 9 MB
        if (target.length() > 1024 * 1024) {
            onProgress(1f)
            return Result.Ok(target)
        }

        // 没有可复用的就清空目录再下：不留 `xxx.apk (1)` 这种残骸，
        // 也顺手回收旧版本的包（9 MB，攒几次就白占空间）
        dir.listFiles()?.forEach { it.delete() }

        return try {
            val client = okhttp3.OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .build()

            client.newCall(okhttp3.Request.Builder().url(url).build()).execute().use { resp ->
                if (!resp.isSuccessful) {
                    return Result.Failed("下载失败（HTTP ${resp.code}），稍后再试")
                }
                val body = resp.body ?: return Result.Failed("下载失败：服务器没返回内容")
                val total = body.contentLength()

                body.byteStream().use { input ->
                    FileOutputStream(target).use { out ->
                        val buf = ByteArray(16 * 1024)
                        var done = 0L
                        var lastPct = -1
                        while (true) {
                            val read = input.read(buf)
                            if (read <= 0) break
                            out.write(buf, 0, read)
                            done += read
                            if (total > 0) {
                                // 每 1% 回调一次：每读一块就刷 UI 会把主线程压满
                                val pct = (done * 100 / total).toInt()
                                if (pct != lastPct) {
                                    lastPct = pct
                                    onProgress(done.toFloat() / total)
                                }
                            }
                        }
                    }
                }
            }

            // 9 MB 的包下出来只有几 KB，基本就是被重定向到错误页了。
            // 宁可报错也不把垃圾文件丢给安装器 —— 它只会报「解析包错误」，更难懂
            if (target.length() < 1024 * 1024) {
                target.delete()
                Result.Failed("下到的文件不完整，检查网络后重试")
            } else {
                Result.Ok(target)
            }
        } catch (e: Exception) {
            android.util.Log.w("ApkInstaller", "下载失败", e)
            Result.Failed("下载中断：${e.message ?: "网络问题"}")
        }
    }

    /** 有没有「安装未知应用」的权限。API 26 以下不需要，直接 true */
    fun canInstall(ctx: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ctx.packageManager.canRequestPackageInstalls()
        } else {
            true
        }

    /**
     * 跳到「允许安装未知应用」的系统设置页。
     *
     * 部分 ROM（尤其中低端机型）没有这个 Activity，跳不过去就静默失败 ——
     * 调用方已经把文案写清楚了，用户能自己从设置里找到。
     */
    fun openInstallSettings(ctx: Context) {
        runCatching {
            ctx.startActivity(
                Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:${ctx.packageName}"),
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }.onFailure { android.util.Log.w("ApkInstaller", "打不开安装权限设置", it) }
    }

    /**
     * 调起系统安装器。
     *
     * 必须走 FileProvider 的 content:// —— Android 7 起直接传 file:// 会抛
     * FileUriExposedException。provider 和 paths 见 Manifest 与 res/xml/file_paths.xml。
     */
    fun install(ctx: Context, file: File): InstallResult {
        val uri = runCatching {
            FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", file)
        }.getOrElse {
            android.util.Log.w("ApkInstaller", "拿不到 content URI", it)
            return InstallResult.Failed("安装包读不出来，重下试试")
        }

        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        /*
         * **不预检查「安装未知应用」**（2026-11-23 致辉指出的）。
         *
         * 大多数 App 就是这么干的：直接调起安装器，由系统在安装界面里问
         * 「是否允许来自此来源的安装」。提前跳到设置页等于把一次连贯的
         * 「下载 → 安装」拆成两段，还让人先看一屏系统设置的警告。
         *
         * 只有真的调不起来（个别 ROM 直接抛 SecurityException）才兜底跳设置。
         */
        val launched = runCatching { ctx.startActivity(intent) }.isSuccess
        if (!launched) {
            android.util.Log.w("ApkInstaller", "调起安装器失败")
            return InstallResult.Failed("没能打开安装界面，换个时间再试")
        }

        /*
         * 调起来了，但没授权的话很多 ROM 会立刻把窗口关掉（只留个 Toast）。
         * 这里同步再判一次权限，把「需要去开一下」这件事明确交给界面 ——
         * 不替他跳设置页（跳走就断了），让用户自己决定什么时候去。
         *
         * 包已经下到缓存了，所以从设置回来再点一次按钮会**直接装**，不用重下。
         */
        return if (canInstall(ctx)) InstallResult.Launched else InstallResult.NeedsPermission
    }
}
