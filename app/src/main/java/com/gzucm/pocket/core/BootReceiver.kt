package com.gzucm.pocket.core

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * 系统事件后重排提醒：开机、覆盖安装、改时间、改时区、精确闹钟授权变化。
 *
 * AlarmManager 的 RTC 闹钟记的是「绝对时刻」：
 *   · 重启后全部丢失
 *   · 用户手动改时间、自动校时、跨时区之后，原来算好的毫秒数就对不上课表了
 * 原来只监听前两个，后面几个会让闹钟整体错位（2026-11-06 排查提醒不准时补上）。
 *
 * 另外原来这里调 scheduleAll 时**没传用户设的提前量**，开机后一律退回默认的
 * 20 / 10 分钟。现在统一走 [ClassReminder.rescheduleFromCache]，从存储里读。
 *
 * 只读缓存，不联网。
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in HANDLED) return

        // 解析课表 JSON 不算轻，放后台线程，别占广播的主线程（ANR 上限 10 秒）
        val pending = goAsync()
        val app = context.applicationContext
        val action = intent.action
        Thread {
            try {
                runCatching { ClassReminder.rescheduleFromCache(app) }
                    .onFailure { android.util.Log.w("ClassReminder", "$action 后重排失败", it) }
            } finally {
                pending.finish()
            }
        }.start()
    }

    companion object {
        /**
         * 只认这几个 action。
         *
         * Receiver 是 exported 的（收开机广播必须），别的 App 理论上能发任意广播进来，
         * 所以要白名单校验 —— 好在这里只会触发「按本地缓存重排」，没有可利用的副作用。
         */
        val HANDLED = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
            Intent.ACTION_DATE_CHANGED,
            // 精确闹钟授权变化（Android 12+）：拿到授权后要从「非精确」换回精确
            "android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED",
            // 部分国产 ROM 的快速开机不发 BOOT_COMPLETED，发的是这个
            "android.intent.action.QUICKBOOT_POWERON",
        )
    }
}
