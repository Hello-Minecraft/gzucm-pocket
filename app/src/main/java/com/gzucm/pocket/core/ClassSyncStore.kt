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

    /**
     * 已认领清单的**正文**缓存（2026-11-22 加）。
     *
     * 为什么必须存：首页那行点开要看「这份清单里到底有什么」，包括没导入的、
     * 学委刚删的。只靠 [loadIndex] 那份摘要拿不到条目，只靠 [AgendaStore]
     * 又只有「我导入了的」—— 两个都答不了这个问题。
     *
     * 存下来还顺带解决了三件事：没网也能看；打开不转圈；能算出「哪几条
     * 我还没导入」。一份 80 条约 15 KB，一个班最多 8 份，这个量级无所谓。
     *
     * 按服务端 id（= `planId(学委名字)`）分开存，不是只存最新一份 ——
     * 一个班可能有多份清单，同学认领两份时两边都要能点开。
     */
    fun savePlanCache(id: String, plan: ClassPlan) = synchronized(LOCK) {
        val encoded = Net.json.encodeToString(ClassPlan.serializer(), plan)
        sp.edit().putString(KEY_PLAN_CACHE + id, encoded).apply()
    }

    fun loadPlanCache(id: String): ClassPlan? = synchronized(LOCK) {
        val raw = sp.getString(KEY_PLAN_CACHE + id, null) ?: return@synchronized null
        runCatching { Net.json.decodeFromString(ClassPlan.serializer(), raw) }.getOrNull()
    }

    /**
     * 只保留这些 id 的正文，其余删掉。
     *
     * 学委把清单删了、或者用户取消认领时调 —— 不清理的话 prefs 会一直攒
     * 已经没人要的正文，而且下次同 id 的旧缓存可能盖住新内容。
     */
    fun prunePlanCache(keep: Set<String>) = synchronized(LOCK) {
        val edit = sp.edit()
        sp.all.keys
            .filter { it.startsWith(KEY_PLAN_CACHE) && it.removePrefix(KEY_PLAN_CACHE) !in keep }
            .forEach { edit.remove(it) }
        edit.apply()
    }

    /** 退出登录 / 换班时清干净。日程和学委清单不在这里，别顺手删 */
    fun clear() = synchronized(LOCK) {
        val edit = sp.edit().remove(KEY_STATE).remove(KEY_INDEX).remove(KEY_CLASS_NAME)
        // 正文缓存是同步状态的一部分：换班之后这些清单已经不属于这个人了
        sp.all.keys.filter { it.startsWith(KEY_PLAN_CACHE) }.forEach { edit.remove(it) }
        edit.apply()
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

        /** 正文缓存的键前缀，后面拼服务端 id：`plan_cache_1d841b` */
        const val KEY_PLAN_CACHE = "plan_cache_"

        /** 和另外两个 Store 一样：多实例共享一把锁，读写不能交错 */
        val LOCK = Any()
    }
}
