package com.gzucm.pocket.widget

import android.content.Context
import com.gzucm.pocket.core.CourseSession
import com.gzucm.pocket.core.Net
import com.gzucm.pocket.core.Semester
import kotlinx.serialization.Serializable

/**
 * 快照里的一节课。
 *
 * **只留渲染要用的字段**。原始 [CourseSession] 有二十多个字段（教师、合班、
 * 教学模式、考试信息……），整份塞进 SharedPreferences 有两个坏处：
 *   · 体积大，部件进程冷启动时读它更慢；
 *   · 原始结构一改（加字段/改字段名），部件读旧快照就得处理迁移。
 *
 * 坐标（week / weekday / startUnit）必须留着 —— 渲染时要按「现在第几周、
 * 今天星期几」自己切片，见 [WidgetRender]。
 */
@Serializable
data class SnapshotSession(
    val week: Int,
    val weekday: Int,
    val startUnit: Int,
    val endUnit: Int,
    val startTime: String,
    val endTime: String,
    val name: String,
    val room: String,
    /**
     * 任课老师（`CourseSession.teachers`，可能是「张伟,李娜」这种逗号串）。
     *
     * **必须带默认值**：升级前写下的老快照里没有这个字段，
     * kotlinx.serialization 靠默认值才能把老 JSON 解出来。
     * 老数据读出来是空串，4x2 会整栏隐藏，不会留一片空白。
     */
    val teacher: String = "",
)

/**
 * 部件读取的课表快照。
 *
 * 存的是**整学期课表 + 学期起点**，不是「今天的三节课」。
 * 这样即使快照好几天没更新，只要周次算得对，显示就仍然正确 ——
 * 后台被厂商省电杀掉、隔夜没刷新这些事就只是「少一次重算」，
 * 而不是「显示昨天的课」。
 */
@Serializable
data class WidgetSnapshotData(
    /** 学期第一周周一的日期，ISO yyyy-MM-dd */
    val semesterStart: String,
    val totalWeeks: Int,
    val sessions: List<SnapshotSession> = emptyList(),
    /** 写入时刻（毫秒），排查「部件是不是没刷新」时用 */
    val savedAt: Long = 0L,
)

/**
 * 快照的落盘 / 读取。
 *
 * 存在**独立的 SharedPreferences 文件**里，不和 `gzucm_pocket` 混：
 * 部件只关心这一份精简数据，读的时候不必把 cookie、成绩、电费那一大堆
 * 一起载入内存。
 */
object WidgetSnapshot {

    private const val PREFS = "gzucm_widget"

    private const val KEY_SNAPSHOT = "snapshot_v1"

    /**
     * 把整学期课表写成快照。
     *
     * 调用方应当在**课表缓存写入之后**调（App 启动恢复缓存、同步成功两处），
     * 这样部件读到的永远和 App 内看到的是同一份数据。
     *
     * @param sessions 已按周展开的整学期课表（和 `SessionStore.cachedSessions` 同一份）
     */
    fun save(context: Context, semester: Semester, sessions: List<CourseSession>) {
        val data = WidgetSnapshotData(
            semesterStart = semester.firstMondayIso,
            totalWeeks = semester.totalWeeks,
            sessions = sessions.map {
                SnapshotSession(
                    week = it.week,
                    weekday = it.weekday,
                    startUnit = it.startUnit,
                    endUnit = it.endUnit,
                    startTime = it.startTime,
                    endTime = it.endTime,
                    name = it.name,
                    room = it.room,
                    teacher = it.teachers,
                )
            }.distinct(),
            savedAt = System.currentTimeMillis(),
        )
        runCatching {
            prefs(context).edit()
                .putString(KEY_SNAPSHOT, Net.json.encodeToString(WidgetSnapshotData.serializer(), data))
                .apply()
        }
    }

    /**
     * 清空快照（退出登录、切换学段时）。
     *
     * 不清的话，换账号/换学段后桌面部件还挂着上一个账号的课表 ——
     * 这比「显示空态」更让人困惑。
     */
    fun clear(context: Context) {
        runCatching { prefs(context).edit().remove(KEY_SNAPSHOT).apply() }
    }

    /** 读快照。没同步过课表 / 文件损坏都返回 null，渲染层会落成「打开 App 同步课表」 */
    fun load(context: Context): WidgetSnapshotData? {
        val raw = runCatching { prefs(context).getString(KEY_SNAPSHOT, null) }.getOrNull() ?: return null
        return runCatching { Net.json.decodeFromString(WidgetSnapshotData.serializer(), raw) }.getOrNull()
    }

    /** 写快照 + 立刻刷新所有桌面实例。App 侧统一走这个入口 */
    fun publish(context: Context, semester: Semester, sessions: List<CourseSession>) {
        runCatching { save(context, semester, sessions) }
        runCatching { WidgetUpdate.refreshAll(context) }
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
