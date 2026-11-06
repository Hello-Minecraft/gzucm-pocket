package com.gzucm.pocket.core

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment

/**
 * 下载新版 APK。
 *
 * 用系统 DownloadManager 而不是自己写 IO：断点续传、通知栏进度这些都是现成的。
 * 装包交给用户点系统通知里的安装，避免申请 REQUEST_INSTALL_PACKAGES。
 *
 * ## 下到哪
 *
 * · API 29+：公共 Downloads 目录，不需要任何权限，用户在「文件」里也找得到
 * · API 26-28（Android 8/9）：写公共目录要 WRITE_EXTERNAL_STORAGE，没有就 enqueue 抛
 *   SecurityException —— 原来这两个版本上应用内更新**必定失败**（minSdk 是 26）。
 *   改为应用专属外部目录，它不需要任何权限，DownloadManager 照样能写、照样能点通知安装。
 */
object ApkDownloader {

    sealed class Result {
        data class Ok(val fileName: String) : Result()
        data class Failed(val reason: String) : Result()
    }

    fun enqueue(ctx: Context, url: String, version: String): Result {
        return runCatching {
            val fileName = "gzucm-pocket-v$version.apk"
            val req = DownloadManager.Request(Uri.parse(url))
                .setTitle("广中医口袋 v$version")
                .setDescription("正在下载新版本")
                .setNotificationVisibility(
                    DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED,
                )
                .setMimeType("application/vnd.android.package-archive")
                .apply {
                    if (Build.VERSION.SDK_INT >= 29) {
                        setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
                    } else {
                        setDestinationInExternalFilesDir(ctx, Environment.DIRECTORY_DOWNLOADS, fileName)
                    }
                }

            val dm = ctx.getSystemService(DownloadManager::class.java)
                ?: return Result.Failed("系统下载服务不可用")
            dm.enqueue(req)
            Result.Ok(fileName)
        }.getOrElse { e ->
            // 不吐类名：用户看不懂 SecurityException 是什么；但日志里要留，不然没法查
            android.util.Log.w("ApkDownloader", "enqueue 失败", e)
            Result.Failed("下载没能开始，检查网络后重试")
        }
    }
}
