package com.gzucm.pocket.core

import android.content.Context
import android.os.Build
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 上次闪退的现场记录。
 *
 * ## 为什么需要它
 *
 * 2026-11-20 鸿蒙 4.2 的用户反馈「打开课表日视图就崩」，但我们这边一句日志都没有 ——
 * 崩在用户手机上，既没有 Play Console，也没有自建上报，全靠他转述「一打开就闪退」，
 * 只能凭代码猜。猜错的代价是发一个版本、等一轮反馈，来回好几天。
 *
 * 所以把崩溃现场先**落在本机**：下次打开 App 弹一次窗，用户点一下复制就能发过来。
 * 不做静默上报 —— 这个项目不维护服务器（见 [Feedback] 的说明），
 * 而且未经允许上传日志本身就是个该避免的事。
 *
 * ## 几条必须守住的细节
 *
 * 1. **写完必须交还给原来的 handler**。不调 `previous.uncaughtException`，
 *    系统就不会弹「应用已停止」，进程挂在后台，用户以为 App 没死。
 * 2. **写文件本身也要 runCatching**。崩溃可能是磁盘满、也可能是 IO 线程崩的，
 *    在 handler 里再抛一次异常会把原现场盖掉。
 * 3. **堆栈要截断**。Compose 的堆栈动辄上千行，全存下来用户复制不动、
 *    粘贴进问卷也会被截。留前 [MAX_STACK_CHARS] 字符，足够看到异常类型和前十几帧。
 * 4. **只留最后一次**。文件名固定，后一次覆盖前一次。留一摞日志对用户没意义，
 *    对定位也多半是同一个问题。
 */
object CrashLog {

    private const val FILE_NAME = "crash-last.txt"

    /** 堆栈保留长度。异常类型 + 前十几帧足够定位，再多用户也不愿意复制 */
    private const val MAX_STACK_CHARS = 6000

    /**
     * 装上全局崩溃捕获。**必须在 `Application.onCreate` 最早调** ——
     * 装晚了，启动阶段崩的那次就抓不到，而那恰恰是最常见的崩法。
     */
    fun install(context: Context) {
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { write(app, thread, error) }
            /*
             * 交还给系统（或链上之前的 handler）。
             *
             * 这里不能吞掉异常：吞了就不会有「应用已停止」的提示，
             * 进程被系统挂起，用户看到的是黑屏/无响应，比闪退更难理解。
             */
            if (previous != null) {
                previous.uncaughtException(thread, error)
            } else {
                android.os.Process.killProcess(android.os.Process.myPid())
                kotlin.system.exitProcess(10)
            }
        }
    }

    /** 读上次崩溃。没有就返回 null（绝大多数启动都是 null） */
    fun read(context: Context): Report? {
        val file = File(context.filesDir, FILE_NAME)
        if (!file.exists()) return null
        val text = runCatching { file.readText() }.getOrNull() ?: return null
        if (text.isBlank()) return null
        return Report(text = text, headline = headlineOf(text))
    }

    /** 用户处理完（关掉弹窗）就删掉，免得每次启动都弹 */
    fun clear(context: Context) {
        runCatching { File(context.filesDir, FILE_NAME).delete() }
    }

    private fun write(context: Context, thread: Thread, error: Throwable) {
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA).format(Date())
        val versionName = com.gzucm.pocket.BuildConfig.VERSION_NAME
        val versionCode = com.gzucm.pocket.BuildConfig.VERSION_CODE
        val body = buildString {
            appendLine(stamp)
            appendLine(
                "${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE}" +
                    " (API ${Build.VERSION.SDK_INT}) · $versionName ($versionCode)" +
                    if (com.gzucm.pocket.BuildConfig.DEBUG) " · debug" else "",
            )
            appendLine("线程 ${thread.name}")
            appendLine()
            // stackTraceToString 自带「异常类: message」首行 + 全部 at 帧
            appendLine(error.stackTraceToString().take(MAX_STACK_CHARS))
        }
        File(context.filesDir, FILE_NAME).writeText(body)
    }

    /**
     * 从整段文本里挑一行当「一句话摘要」。
     *
     * 优先挑第一行 `java.` / `android.` 开头的 —— 那才是异常类型那行；
     * 挑不到就退回文件第一行（至少有时间戳，不至于空白）。
     */
    private fun headlineOf(text: String): String {
        val lines = text.lineSequence()
        val hit = lines.firstOrNull { line ->
            line.startsWith("java.") || line.startsWith("android.") ||
                line.startsWith("kotlin.") || line.startsWith("androidx.")
        }
        return (hit ?: text.lineSequence().firstOrNull()).orEmpty().trim()
    }

    /** 一次崩溃的完整现场：整段文本给复制用，[headline] 给弹窗标题用 */
    data class Report(val text: String, val headline: String)
}
