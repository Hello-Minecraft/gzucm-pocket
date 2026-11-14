package com.gzucm.pocket.core

import android.content.Context
import kotlinx.serialization.builtins.ListSerializer

/**
 * 用户自建日程的独立持久化。
 *
 * 为什么不直接放 SessionStore.cachedSessions 旁边：
 * 教务课表会在同步成功时整表覆盖写、在缓存版本升级时被清掉、
 * 在切学段 / 退出登录时被清掉。用户手输的日程不该跟着一起消失。
 * 所以这里用**独立的 key、独立的锁、独立的读写函数**，
 * 上面那些流程一行都不碰它。
 *
 * SessionStore 在项目里会被多处 new 出来，锁必须挂在 companion 上，
 * 不能挂在实例上 —— 这是 cookie 那边踩过的坑。
 */
class AgendaStore(context: Context) {

    private val sp = context.applicationContext
        .getSharedPreferences("gzucm_pocket_agenda", Context.MODE_PRIVATE)

    fun load(): List<Agenda> = synchronized(LOCK) { loadUnlocked() }

    private fun loadUnlocked(): List<Agenda> {
        val raw = sp.getString(KEY_AGENDA, null) ?: return emptyList()
        return runCatching {
            Net.json.decodeFromString(ListSerializer(Agenda.serializer()), raw)
        }.getOrDefault(emptyList())
    }

    fun save(list: List<Agenda>) = synchronized(LOCK) {
        val encoded = Net.json.encodeToString(ListSerializer(Agenda.serializer()), list)
        sp.edit().putString(KEY_AGENDA, encoded).apply()
    }

    /** 新增或修改（按 id 覆盖）；返回保存后的完整列表，方便调用方直接塞进 state */
    fun upsert(item: Agenda): List<Agenda> = synchronized(LOCK) {
        val next = loadUnlocked().filterNot { it.id == item.id } + item
        val encoded = Net.json.encodeToString(ListSerializer(Agenda.serializer()), next)
        sp.edit().putString(KEY_AGENDA, encoded).apply()
        next
    }

    fun delete(id: String): List<Agenda> = synchronized(LOCK) {
        val next = loadUnlocked().filterNot { it.id == id }
        val encoded = Net.json.encodeToString(ListSerializer(Agenda.serializer()), next)
        sp.edit().putString(KEY_AGENDA, encoded).apply()
        next
    }

    fun clear() = synchronized(LOCK) {
        sp.edit().remove(KEY_AGENDA).apply()
    }

    private companion object {
        const val KEY_AGENDA = "agenda_rules"
        val LOCK = Any()
    }
}
