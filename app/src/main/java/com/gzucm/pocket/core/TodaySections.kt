package com.gzucm.pocket.core

import java.time.LocalDate

/**
 * 今日页上的五块内容。
 *
 * 原来只有四块（天气 / 电费 / 接下来 / 本周概览），课程卡**固定夹在中间**，
 * 规则是「排前两个的摆课程上面、其余摆下面」。做完排序才发现这条规则会闹鬼：
 * 用户减掉电费和本周概览之后，剩下的「接下来」会突然从课程下方跳到上方 ——
 * 因为他从第三位变成了第二位。
 *
 * 所以把课程卡也放进顺序里：**它可以拖，但不能减**（[removable]）。
 * 这样「顺序」是唯一的真相，用户拖到哪就在哪，不会有意外的跳位。
 *
 * [key] 是写进 SharedPreferences 的稳定标识，**改名会丢用户已存的顺序**。
 */
enum class TodayBlock(val key: String, val label: String, val removable: Boolean = true) {
    WEATHER("weather", "天气"),
    POWER("power", "宿舍电量"),
    UPCOMING("upcoming", "接下来"),
    WEEK_OVERVIEW("week", "本周概览"),

    /** 今日课程。永远显示，但位置可以拖 —— 有人喜欢课程在最上面 */
    COURSE("course", "今日课程", removable = false),
    ;

    companion object {
        fun fromKey(k: String?): TodayBlock? = entries.firstOrNull { it.key == k }
    }
}

/**
 * 今日页显示配置。
 *
 * ## 为什么是「一个有序列表」而不是四个布尔
 *
 * 2026-11-20 加了排序之后，四个 `Boolean` 就表达不了顺序了。
 * 与其「四个开关 + 一个单独的 order 列表」两处状态互相打架
 * （关掉某块时 order 里还留着它，得处处判空），不如只留一份真相：
 *
 * **在 [order] 里 = 显示，顺序就是页面上的顺序；不在 = 被减掉了。**
 * 便捷属性 [weather] / [power] 等是由它推出来的只读视图，
 * 老代码读它们不受影响。
 *
 * 被减掉的块按 [hidden] 取（枚举声明顺序），界面上排在列表最下面、字变灰。
 */
data class TodaySections(
    /** 显示中的块，顺序 = 今日页顺序。空列表合法（今日页只剩课程） */
    val order: List<TodayBlock> = DefaultOrder,
    /** 「接下来」往后看几天（1~14） */
    val upcomingDays: Int = DefaultDays,
) {
    // ── 只读视图：老代码继续用 sections.power 这种写法 ──────────────
    val weather: Boolean get() = TodayBlock.WEATHER in order
    val power: Boolean get() = TodayBlock.POWER in order
    val upcoming: Boolean get() = TodayBlock.UPCOMING in order
    val weekOverview: Boolean get() = TodayBlock.WEEK_OVERVIEW in order

    /** 被减掉的块，按枚举顺序（用户不控制这一组，它永远排最后）。课程不会出现在这里 */
    val hidden: List<TodayBlock>
        get() = TodayBlock.entries.filter { it.removable && it !in order }

    fun isOn(block: TodayBlock): Boolean = block in order

    /** 减掉一块：从 order 里移除，其余顺序不变。课程不可减，直接原样返回 */
    fun hide(block: TodayBlock): TodaySections =
        if (block.removable && block in order) copy(order = order - block) else this

    /** 加回来：接到**已显示那组的末尾**（用户随时能看到它回到哪） */
    fun show(block: TodayBlock): TodaySections =
        if (block in order) this else copy(order = order + block)

    /** 点一下 −/+ 的统一入口 */
    fun toggle(block: TodayBlock): TodaySections =
        if (!block.removable) this
        else if (block in order) hide(block) else show(block)

    /**
     * 补齐「不可减」的块。
     *
     * 读存储、尤其是从老配置迁移过来时，`order` 里可能没有课程卡
     * （老版本根本没这个字段）。这里按**默认顺序里的相对位置**把它插回去：
     * 找到第一个在默认顺序中排在它后面的块，插在它前面。
     *
     * 这样「只开了天气和接下来」的老配置迁移出来是 `[天气, 课程, 接下来]`，
     * 和他升级前看到的页面一致；而不是把课程甩到最后。
     */
    fun normalized(): TodaySections {
        val missing = TodayBlock.entries.filter { !it.removable && it !in order }
        if (missing.isEmpty()) return this
        val list = order.toMutableList()
        missing.forEach { block ->
            val anchor = list.indexOfFirst {
                DefaultOrder.indexOf(it).let { i -> i >= 0 && i > DefaultOrder.indexOf(block) }
            }
            list.add(if (anchor < 0) list.size else anchor, block)
        }
        return copy(order = list)
    }

    /**
     * 拖动排序。[from] / [to] 都指 [order] 的下标。
     *
     * 越界直接忽略而不是抛异常：拖拽手势里的下标来自实时布局，
     * 一次快速甩动可能算出界，这里悄悄吞掉比闪退好。
     */
    fun move(from: Int, to: Int): TodaySections {
        if (from == to) return this
        if (from !in order.indices || to !in order.indices) return this
        val list = order.toMutableList()
        list.add(to, list.removeAt(from))
        return copy(order = list)
    }

    companion object {
        const val MinDays = 1
        const val MaxDays = 14
        const val DefaultDays = 7

        /**
         * 默认顺序 = 2026-11 之前写死的那个顺序（天气、电费、接下来、本周概览）。
         *
         * 老用户升级上来看到的是原样的页面，不会因为「新增排序功能」而变样。
         */
        val DefaultOrder: List<TodayBlock> = listOf(
            TodayBlock.WEATHER,
            TodayBlock.POWER,
            TodayBlock.COURSE,
            TodayBlock.UPCOMING,
            TodayBlock.WEEK_OVERVIEW,
        )
    }
}

object TodayLogic {

    /** 「接下来」最多摆几条。再多就不是「提醒」而是第二张日程表了 */
    const val MaxUpcoming = 5

    /**
     * 未来 N 天的日程 / 考试。
     *
     * 三条口径：
     * 1. **不含今天** —— 今天的内容已经在上面那一段里了，重复一遍会让人以为撞车；
     * 2. 含第 N 天当天（`<= today + N`），「七天内」就是连头带尾都在；
     * 3. 按日期、时间、标题排序，保证同一天里早的在前。
     *
     * 入参是 `agendaOccurrences + examOccurrences` 的合并结果，
     * 两种来源在这里不做区分 —— 对用户来说都是「那天有事」。
     */
    fun upcoming(
        all: List<AgendaOccurrence>,
        today: LocalDate,
        days: Int,
    ): List<AgendaOccurrence> {
        val span = days.coerceIn(TodaySections.MinDays, TodaySections.MaxDays).toLong()
        val until = today.plusDays(span)
        return all
            .filter { it.date.isAfter(today) && !it.date.isAfter(until) }
            .sortedWith(compareBy({ it.date }, { it.startMinute }, { it.title }))
    }
}
