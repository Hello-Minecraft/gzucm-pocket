package com.gzucm.pocket.core

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment

/**
 * 下载新版 APK。
 *
 * 用系统 DownloadManager 而不是自己写 IO：断点续传、通知栏进度、写入公共下载目录
 * 这些都是现成的，而且**不需要任何存储权限**（API 29+）。
 * 装包交给用户点系统通知里的安装，避免申请 REQUEST_INSTALL_PACKAGES。
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
                .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)

            val dm = ctx.getSystemService(DownloadManager::class.java)
                ?: return Result.Failed("系统下载服务不可用")
            dm.enqueue(req)
            Result.Ok(fileName)
        }.getOrElse { e ->
            Result.Failed("下载启动失败：${e.javaClass.simpleName}")
        }
    }
}
