package com.gzucm.pocket.core

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * 开机 / 覆盖安装后重排提醒。
 *
 * AlarmManager 里的闹钟在重启后全部丢失，不重排就等于提醒失效。
 * 这里只读缓存，不联网。
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) return

        runCatching {
            val app = context.applicationContext
            val store = SessionStore(app)
            if (!store.classReminderEnabled) return@runCatching

            val semJson = store.cachedSemester ?: return@runCatching
            val sesJson = store.cachedSessions ?: return@runCatching
            val semester = Net.json.decodeFromString(Semester.serializer(), semJson)
            val sessions = Net.json.decodeFromString<List<CourseSession>>(sesJson)
            ClassReminder.scheduleAll(app, semester, sessions, true)
        }
    }
}
