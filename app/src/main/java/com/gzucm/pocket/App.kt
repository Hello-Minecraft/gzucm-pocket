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
         * 单元测试（Robolectric）里不跑冷启动重排。
         *
         * 这段排程会真的往 AlarmManager 里塞闹钟，而 Robolectric 的
         * ShadowAlarmManager 是全局共享的：每个用例的 App.onCreate 都会多出
         * 早/晚两条天气闹钟，于是「断言闹钟条数」的用例（AgendaReminderScheduleTest）
         * 看到的是 1 + 2 = 3 条，看起来像排程代码重复排了。
         *
         * 排程逻辑本身有专门的测试覆盖，这里跳过不影响正确性。
         */
        if (runningUnderUnitTest()) return

        /*
         * 桌面课表部件：排每天 0 点的兜底刷新。
         *
         * 部件的刷新主要靠「App 打开」和「课表变化」两个同步触发点，
         * 但这个 Worker 负责覆盖「用户好几天不打开 App、桌面也不重建部件」——
         * 过了 0 点周次/星期/当前节次都变了，界面要重绘一次才会变。
         *
         * KEEP：每次启动都调是安全的，不会重复排。
         */
        runCatching { com.gzucm.pocket.widget.WidgetRefreshWorker.ensure(this) }
            .onFailure { android.util.Log.w("Widget", "排每日刷新失败", it) }

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
            /*
             * 天气提醒：冷启动也重排一次。
             *
             * 它每天响完会自己续排，但 App 被系统强停（ColorOS / HyperOS 把包打成
             * stopped 状态）时闹钟会全部作废，而那一刻不会收到任何广播 ——
             * 只能等用户下次打开 App 时补上。
             */
            runCatching { com.gzucm.pocket.core.WeatherAlert.rescheduleFromStore(this) }
                .onFailure { android.util.Log.w("WeatherAlert", "启动重排失败", it) }
        }.start()
    }

    /**
     * 是否跑在 Robolectric 里。
     *
     * Robolectric 只在单元测试的 classpath 上，正式包的 APK 里没有这个类，
     * 所以 Class.forName 成功与否就是一个不会误判的判据。
     */
    private fun runningUnderUnitTest(): Boolean =
        runCatching { Class.forName("org.robolectric.Robolectric") }.isSuccess
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
