package com.gzucm.pocket

import android.app.Application
import android.content.Context
import com.gzucm.pocket.core.Net
import com.gzucm.pocket.core.SessionStore

class App : Application() {

    override fun onCreate() {
        super.onCreate()

        /*
         * 崩溃现场记录，**排在所有初始化之前**。
         *
         * 装晚了（比如放在 Graph.init 之后），启动阶段崩的那次就抓不到 ——
         * 而「一打开就闪退」正是最常见的崩法，也正是最需要日志的那种。
         * handler 内部已经 runCatching 兜过，不会因为它自己失败而影响启动。
         */
        com.gzucm.pocket.core.CrashLog.install(this)

        Graph.init(this)

        /*
         * 日程提醒：每次冷启动重排一次。
         *
         * 覆盖的是「App 被系统强停过、闹钟全废，但用户又打开了」这种情况。
         * 放后台线程 —— 要读日程 JSON + 十几次 AlarmManager 的 binder 调用，
         * 别占 Application.onCreate 的主线程。
         */
        Thread {
            runCatching { com.gzucm.pocket.core.AgendaReminder.rescheduleFromCache(this) }
                .onFailure { android.util.Log.w("AgendaReminder", "启动重排失败", it) }
        }.start()
    }
}

/** 极简依赖容器：一个会话存储 + 一个网络栈，够用了。 */
object Graph {

    lateinit var store: SessionStore
        private set

    fun init(context: Context) {
        store = SessionStore(context.applicationContext)
        Net.init(context.applicationContext)
    }
}
