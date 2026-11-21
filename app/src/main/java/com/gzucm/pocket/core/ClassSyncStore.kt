package com.gzucm.pocket.core

import android.content.Context

/**
 * 班级日程云端版的本地持久化。
 *
 * 和 [ClassPlanStore]（「我要发出去的那份清单」）、[AgendaStore]（「我自己的日程」）
 * 三份数据互不相干，各用各的 prefs 文件、各用各的锁：
 *   · 清单 → `gzucm_pocket_class_plan`（学委编辑中的那份）
 *   · 日程 → `gzucm_pocket_agenda`（合并进来的结果）
 *   · 同步 → `gzucm_pocket_class_sync`（本文件：订阅状态 + index 缓存）
 *
 * 单独放一个文件的好处：清「同步状态」时不会顺手把用户日程和学委清单带走，
 * 反过来也一样。
 *
 * 这个类只负责**读写**。所有状态迁移（认领、弹窗、忽略计数）都是
 * ClassSync.kt 里的纯函数，返回新对象，这里只管把它落盘。
 */
class ClassSyncStore(context: Context) {

    private val sp = context.applicationContext
        .getSharedPreferences("gzucm_pocket_class_sync", Context.MODE_PRIVATE)

    /**
     * 当前学籍里的行政班原文（如「一院23中医(铁涛)5」）。
     *
     * 单独存一个键而不是塞进 [ClassSyncState]：它是「我属于哪个班」，
     * 是查询条件；state 是「同步到了什么」，是结果。两者生命周期也不同 ——
     * 退出登录清 state，班级要从学籍重新读。
     */
    var className: String
        get() = sp.getString(KEY_CLASS_NAME, "").orEmpty()
        set(v) = sp.edit().putString(KEY_CLASS_NAME, v).apply()

    fun loadState(): ClassSyncState = synchronized(LOCK) {
        val raw = sp.getString(KEY_STATE, null) ?: return@synchronized ClassSyncState()
        runCatching { Net.json.decodeFromString(ClassSyncState.serializer(), raw) }
            .getOrDefault(ClassSyncState())
    }

    fun saveState(state: ClassSyncState) = synchronized(LOCK) {
        val encoded = Net.json.encodeToString(ClassSyncState.serializer(), state)
        sp.edit().putString(KEY_STATE, encoded).apply()
    }

    /** 读 → 改 → 写。改的那一步用 ClassSync.kt 里的纯函数算 */
    fun updateState(block: (ClassSyncState) -> ClassSyncState): ClassSyncState =
        synchronized(LOCK) {
            val next = block(loadStateUnlocked())
            val encoded = Net.json.encodeToString(ClassSyncState.serializer(), next)
            sp.edit().putString(KEY_STATE, encoded).apply()
            next
        }

    /**
     * index 缓存。
     *
     * 只是加速用的：拿不到就退回「按班查一遍」，不影响正确性
     * （契约 §1 说得很清楚，服务端也是这么处理的）。
     */
    fun loadIndex(): ClassPlanIndex? = synchronized(LOCK) {
        val raw = sp.getString(KEY_INDEX, null) ?: return@synchronized null
        runCatching { Net.json.decodeFromString(ClassPlanIndex.serializer(), raw) }.getOrNull()
    }

    fun saveIndex(index: ClassPlanIndex) = synchronized(LOCK) {
        val encoded = Net.json.encodeToString(ClassPlanIndex.serializer(), index)
        sp.edit().putString(KEY_INDEX, encoded).apply()
    }

    fun clearIndex() = synchronized(LOCK) { sp.edit().remove(KEY_INDEX).apply() }

    /** 退出登录 / 换班时清干净。日程和学委清单不在这里，别顺手删 */
    fun clear() = synchronized(LOCK) {
        sp.edit().remove(KEY_STATE).remove(KEY_INDEX).remove(KEY_CLASS_NAME).apply()
    }

    private fun loadStateUnlocked(): ClassSyncState {
        val raw = sp.getString(KEY_STATE, null) ?: return ClassSyncState()
        return runCatching { Net.json.decodeFromString(ClassSyncState.serializer(), raw) }
            .getOrDefault(ClassSyncState())
    }

    private companion object {
        const val KEY_STATE = "sync_state"
        const val KEY_INDEX = "plan_index"
        const val KEY_CLASS_NAME = "class_name"

        /** 和另外两个 Store 一样：多实例共享一把锁，读写不能交错 */
        val LOCK = Any()
    }
}
